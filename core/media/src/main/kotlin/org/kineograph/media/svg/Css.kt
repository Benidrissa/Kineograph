package org.kineograph.media.svg

internal class Declaration(val name: String, val value: String, val important: Boolean)

internal class StyleRule(val selector: Selector, val declarations: List<Declaration>, val order: Int)

internal class Keyframe(val offset: Double, val declarations: List<Declaration>)

internal class Stylesheet {
    val rules: ArrayList<StyleRule> = ArrayList()
    val keyframes: HashMap<String, List<Keyframe>> = HashMap()
}

private const val BYTES_PER_CHAR_OF_RULES = 200

private val PREFIXED_PROPERTIES = ANIMATION_LONGHANDS + setOf("animation", "transform", "transform-origin", "transform-box")
private val VENDOR_PREFIXES = listOf("-webkit-", "-moz-", "-ms-", "-o-")

/**
 * A forgiving CSS reader: whatever it cannot understand is skipped up to the next rule, because a stylesheet with one odd line
 * should still style the rest of the picture.
 */
internal class CssParser(
    text: String,
    private val sheet: Stylesheet,
    private val warnings: MutableSet<SvgWarning>,
    private val media: MediaContext,
    private val work: WorkMeter,
    private val limits: SvgLimits,
    private val nesting: Int = 0,
) {
    private val text: String = stripComments(text)
    private var pos = 0

    fun parse() {
        // A rule is a few small objects, and can be only a few characters.
        if (!HeapWatch.hasRoomForText(text.length, BYTES_PER_CHAR_OF_RULES)) {
            warnings += SvgWarning.LIMIT_REACHED
            return
        }
        work.spend(text.length.toLong())
        while (true) {
            skipSpace()
            // What was read so far stays, and whatever follows counts for nothing more. The caller adds the warning.
            if (pos >= text.length || work.isStopped) return
            if (text[pos] == '@') {
                parseAtRule()
            } else {
                parseRule()
            }
        }
    }

    private fun skipSpace() {
        while (pos < text.length && (text[pos].isWhitespace() || text[pos] == ';')) pos++
    }

    private fun parseRule() {
        val start = pos
        val open = findBlockStart()
        if (open < 0) {
            pos = text.length
            return
        }
        val selectorText = text.substring(start, open)
        pos = open
        val body = readBlock()
        val declarations = parseDeclarations(body)
        for (part in splitTopLevel(selectorText, ',')) {
            if (!work.spend(WorkCost.STYLE_RULE_READ + part.length)) return
            val selector = parseSelector(part, limits.maxSelectorParts) { warnings += SvgWarning.LIMIT_REACHED } ?: continue
            sheet.rules += StyleRule(selector, declarations, sheet.rules.size)
        }
    }

    private fun parseAtRule() {
        val nameEnd = indexOfFirstFrom(pos + 1) { !(it.isLetterOrDigit() || it == '-') }
        val name = text.substring(pos + 1, nameEnd).lowercase()
        pos = nameEnd
        val end = findStatementEnd()
        val hasBlock = end < text.length && text[end] == '{'
        val prelude = text.substring(pos, end).trim()
        pos = end
        if (!hasBlock) {
            pos = (end + 1).coerceAtMost(text.length)
            if (name == "import") warnings += SvgWarning.EXTERNAL_REFERENCE_IGNORED
            return
        }
        val body = readBlock()
        when {
            name == "media" -> if (mediaMatches(prelude, media)) nested(body)
            name == "supports" -> nested(body)
            name == "font-face" -> if ("url(" in body.lowercase()) warnings += SvgWarning.EXTERNAL_REFERENCE_IGNORED
            name.endsWith("keyframes") -> parseKeyframes(prelude, body)
        }
    }

    /** Each level reads all of its text again, and goes one level further down the stack. */
    private fun nested(body: String) {
        if (nesting >= limits.maxStylesheetNesting) {
            warnings += SvgWarning.LIMIT_REACHED
            return
        }
        CssParser(body, sheet, warnings, media, work, limits, nesting + 1).parse()
    }

    private fun parseKeyframes(rawName: String, body: String) {
        val name = rawName.trim().trim('"', '\'')
        if (name.isEmpty()) return
        val frames = ArrayList<Keyframe>()
        val inner = CssBlockReader(body)
        while (true) {
            val (selector, block) = inner.next() ?: break
            val declarations = parseDeclarations(block).filterNot { it.important }
            for (part in selector.split(',')) {
                if (!work.spend(WorkCost.SELECTOR_STEP)) break
                val offset =
                    when (val key = part.trim().lowercase()) {
                        "from" -> 0.0
                        "to" -> 1.0
                        else -> if (key.endsWith("%")) parseNumberOrNull(key.dropLast(1))?.div(100.0) else null
                    }
                if (offset != null && offset in 0.0..1.0) frames += Keyframe(offset, declarations)
            }
        }
        sheet.keyframes[name] = frames
    }

    private inline fun indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        var i = start
        while (i < text.length && !predicate(text[i])) i++
        return i
    }

    private fun findBlockStart(): Int {
        val end = scanTo(pos) { it == '{' }
        return if (end < text.length) end else -1
    }

    private fun findStatementEnd(): Int = scanTo(pos) { it == '{' || it == ';' }

    private inline fun scanTo(start: Int, stop: (Char) -> Boolean): Int {
        var i = start
        var quote = '\u0000'
        var depth = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote != '\u0000' -> if (c == quote) quote = '\u0000'
                c == '"' || c == '\'' -> quote = c
                c == '(' -> depth++
                c == ')' -> depth--
                depth <= 0 && stop(c) -> return i
            }
            i++
        }
        return text.length
    }

    /** Reads the `{...}` at the cursor and returns what is inside, tolerating a stylesheet that ends before it closes. */
    private fun readBlock(): String {
        val (body, end) = readBalancedBlock(text, pos)
        pos = end
        return body
    }
}

private fun readBalancedBlock(text: String, open: Int): Pair<String, Int> {
    var i = open + 1
    var depth = 1
    var quote = '\u0000'
    while (i < text.length) {
        val c = text[i]
        when {
            quote != '\u0000' -> {
                if (c == quote) quote = '\u0000'
            }

            c == '"' || c == '\'' -> {
                quote = c
            }

            c == '{' -> {
                depth++
            }

            c == '}' -> {
                depth--
                if (depth == 0) return text.substring(open + 1, i) to i + 1
            }
        }
        i++
    }
    return text.substring(open + 1) to text.length
}

/** Reads `selector { body }` pairs out of a keyframes block. */
private class CssBlockReader(private val text: String) {
    private var pos = 0

    fun next(): Pair<String, String>? {
        val open = text.indexOf('{', pos)
        if (open < 0) return null
        val selector = text.substring(pos, open).trim()
        val (body, end) = readBalancedBlock(text, open)
        pos = end
        return selector to body
    }
}

private fun stripComments(css: String): String {
    if ("/*" !in css && "<!--" !in css && "-->" !in css) return css
    val out = StringBuilder(css.length)
    var i = 0
    var quote = '\u0000'
    while (i < css.length) {
        val c = css[i]
        if (quote != '\u0000') {
            if (c == quote) quote = '\u0000'
            out.append(c)
            i++
        } else if (c == '"' || c == '\'') {
            quote = c
            out.append(c)
            i++
        } else if (css.startsWith("/*", i)) {
            val end = css.indexOf("*/", i + 2)
            i = if (end < 0) css.length else end + 2
            out.append(' ')
        } else if (css.startsWith("<!--", i)) {
            i += 4
        } else if (css.startsWith("-->", i)) {
            i += 3
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

/** The picture's own size, which media queries about the window are answered with, since an import has no window. */
internal class MediaContext(val width: Double, val height: Double)

/** Queries are answered for an offline render on a screen with default settings, so every file gets its everyday look. */
internal fun mediaMatches(query: String, context: MediaContext): Boolean {
    if (query.isBlank()) return true
    return splitTopLevel(query, ',').any { part -> singleQueryMatches(part.trim().lowercase(), context) }
}

internal class MediaFeature(val name: String, val value: String, val end: Int)

/** Reads `(name)` or `(name: value)` at exactly [start]. By hand, since spaces around a value made a regular expression take cubic time. */
internal fun readMediaFeature(text: String, start: Int): MediaFeature? {
    if (start >= text.length || text[start] != '(') return null
    var i = skipBlanks(text, start + 1)
    val nameStart = i
    while (i < text.length && (text[i] in 'a'..'z' || text[i] == '-')) i++
    if (i == nameStart) return null
    val name = text.substring(nameStart, i)
    i = skipBlanks(text, i)
    if (i >= text.length) return null
    if (text[i] == ')') return MediaFeature(name, "", i + 1)
    if (text[i] != ':') return null
    val valueStart = skipBlanks(text, i + 1)
    val close = text.indexOf(')', valueStart)
    if (close < 0) return null
    var valueEnd = close
    while (valueEnd > valueStart && text[valueEnd - 1].isWhitespace()) valueEnd--
    return MediaFeature(name, text.substring(valueStart, valueEnd), close + 1)
}

private fun skipBlanks(text: String, from: Int): Int {
    var i = from
    while (i < text.length && text[i].isWhitespace()) i++
    return i
}

private fun singleQueryMatches(query: String, context: MediaContext): Boolean {
    var rest = query
    val negated = rest.startsWith("not ")
    if (negated) rest = rest.removePrefix("not ").trim()
    if (rest.startsWith("only ")) rest = rest.removePrefix("only ").trim()
    var matches = true
    if (!rest.startsWith("(")) {
        val type = rest.substringBefore(' ').trim()
        // A query with a type this renderer has never heard of is simply about some other device.
        matches = type == "screen" || type == "all"
        rest = rest.removePrefix(type).trim()
    }
    // One position walks along the text. Cutting off what has been read each time would copy the rest once per feature.
    var position = 0
    while (matches && position < rest.length) {
        if (rest.startsWith("and", position)) position = skipBlanks(rest, position + 3)
        val feature = readMediaFeature(rest, position) ?: return false
        matches = mediaFeatureMatches(feature.name, feature.value, context)
        position = skipBlanks(rest, feature.end)
    }
    return matches != negated
}

private fun mediaFeatureMatches(name: String, value: String, context: MediaContext): Boolean {
    fun px(): Double? = parseLengthOrNull(value)?.let { it.px + it.em * 16.0 }
    return when (name) {
        "width" -> px()?.let { context.width == it } ?: false
        "min-width" -> px()?.let { context.width >= it } ?: false
        "max-width" -> px()?.let { context.width <= it } ?: false
        "height" -> px()?.let { context.height == it } ?: false
        "min-height" -> px()?.let { context.height >= it } ?: false
        "max-height" -> px()?.let { context.height <= it } ?: false
        "orientation" -> value == if (context.width > context.height) "landscape" else "portrait"
        "prefers-color-scheme" -> value == "light"
        "prefers-reduced-motion" -> value == "no-preference" || value.isEmpty()
        "prefers-contrast" -> value == "no-preference" || value.isEmpty()
        "color" -> true
        "hover", "pointer", "any-hover", "any-pointer" -> value != "none"
        else -> false
    }
}

internal fun parseDeclarations(block: String): List<Declaration> {
    val out = ArrayList<Declaration>()
    for (part in splitTopLevel(block, ';')) {
        val colon = part.indexOf(':')
        if (colon <= 0) continue
        var name = part.substring(0, colon).trim()
        if (name.isEmpty()) continue
        if (!name.startsWith("--")) name = canonicalName(name)
        var value = part.substring(colon + 1).trim()
        var important = false
        val bang = value.lastIndexOf('!')
        if (bang >= 0 && value.substring(bang + 1).trim().equals("important", ignoreCase = true)) {
            important = true
            value = value.substring(0, bang).trim()
        }
        if (value.isNotEmpty()) out += Declaration(name, value, important)
    }
    return out
}

private fun canonicalName(raw: String): String {
    val name = raw.lowercase()
    for (prefix in VENDOR_PREFIXES) {
        if (name.startsWith(prefix)) {
            val bare = name.substring(prefix.length)
            if (bare in PREFIXED_PROPERTIES) return bare
        }
    }
    return name
}

internal class AttributeMatch(val name: String, val operator: String, val value: String) {
    fun matches(el: SvgElement, work: WorkMeter): Boolean {
        val actual =
            when (name) {
                "id" -> el.id
                "class" -> el.classes.joinToString(" ").ifEmpty { null }
                else -> el.attrs[name]
            } ?: return false
        work.spend(actual.length / CHARS_PER_UNIT)
        return when (operator) {
            "" -> true
            "=" -> actual == value
            "~=" -> value in actual.split(' ', '\t', '\n')
            "^=" -> value.isNotEmpty() && actual.startsWith(value)
            "$=" -> value.isNotEmpty() && actual.endsWith(value)
            "*=" -> value.isNotEmpty() && value in actual
            "|=" -> actual == value || actual.startsWith("$value-")
            else -> false
        }
    }

    private companion object {
        const val CHARS_PER_UNIT = 8L
    }
}

internal sealed interface Pseudo {
    fun matches(el: SvgElement, work: WorkMeter): Boolean

    data object Root : Pseudo {
        override fun matches(el: SvgElement, work: WorkMeter): Boolean = el.parent == null
    }

    /** `:nth-child(an+b)` and its relatives. All of them reduce to counting a position among some siblings. */
    class Nth(val a: Int, val b: Int, val fromEnd: Boolean, val ofType: Boolean) : Pseudo {
        override fun matches(el: SvgElement, work: WorkMeter): Boolean {
            val parent = el.parent ?: return a == 0 && b == 1
            val position: Int
            if (ofType) {
                val counts = parent.typeCounts ?: countTypes(parent)
                position = if (fromEnd) (counts[el.tag] ?: 0) - el.typeIndex else el.typeIndex + 1
            } else {
                position = if (fromEnd) parent.rawChildCount - el.rawIndex else el.rawIndex + 1
            }
            if (a == 0) return position == b
            val n = (position - b) / a
            return n >= 0 && a * n + b == position
        }

        /** Once for each parent. Going through the siblings for every test would make a long list of them quadratic. */
        private fun countTypes(parent: SvgElement): Map<String, Int> {
            val counts = HashMap<String, Int>()
            for (child in parent.children) {
                val seen = counts[child.tag] ?: 0
                child.typeIndex = seen
                counts[child.tag] = seen + 1
            }
            parent.typeCounts = counts
            return counts
        }
    }

    class Not(val inner: List<CompoundSelector>) : Pseudo {
        override fun matches(el: SvgElement, work: WorkMeter): Boolean {
            work.spend(inner.size * WorkCost.SELECTOR_STEP)
            return inner.none { it.matches(el, work) }
        }
    }
}

internal class CompoundSelector(
    val tag: String?,
    val id: String?,
    val classes: List<String>,
    val attributes: List<AttributeMatch>,
    val pseudos: List<Pseudo>,
    val specificity: Int,
) {
    fun matches(el: SvgElement, work: WorkMeter): Boolean {
        if (tag != null && tag != el.tag) return false
        if (id != null && id != el.id) return false
        if (classes.isNotEmpty()) work.spend(classes.size * el.classes.size * WorkCost.SELECTOR_STEP)
        for (name in classes) if (name !in el.classes) return false
        for (attribute in attributes) if (!attribute.matches(el, work)) return false
        for (pseudo in pseudos) if (!pseudo.matches(el, work)) return false
        return true
    }
}

/** [parts] run left to right. `combinators[i]` joins `parts[i]` and `parts[i + 1]`: a space, `>`, `+` or `~`. */
internal class Selector(val parts: List<CompoundSelector>, val combinators: List<Char>) {
    val specificity: Int = parts.sumOf { it.specificity }

    val subject: CompoundSelector get() = parts.last()

    fun matches(el: SvgElement, work: WorkMeter): Boolean = matchFrom(el, parts.lastIndex, work) == MATCHES

    /**
     * Whether the selector up to [index] matches with its last part on [el]. A plain search would try every ancestor or earlier sibling
     * for every part, which multiplies out to hours for a selector of a few dozen bytes. How it fails says what is worth trying next,
     * the way browsers do it. When no ancestor could satisfy what is left, none further up can either, since they have fewer ancestors.
     */
    private fun matchFrom(el: SvgElement, index: Int, work: WorkMeter): Int {
        // With nothing left, it is simply not a match, and every search stops at once.
        if (!work.spend(WorkCost.SELECTOR_STEP)) return NO_ANCESTOR_CAN
        if (!parts[index].matches(el, work)) return FAILS_HERE
        if (index == 0) return MATCHES
        return when (combinators[index - 1]) {
            ' ' -> {
                var ancestor = el.parent
                while (ancestor != null) {
                    val result = matchFrom(ancestor, index - 1, work)
                    if (result == MATCHES || result == NO_ANCESTOR_CAN) return result
                    ancestor = ancestor.parent
                }
                NO_ANCESTOR_CAN
            }

            '>' -> {
                val parent = el.parent ?: return NO_ANCESTOR_CAN
                matchFrom(parent, index - 1, work)
            }

            '+' -> {
                val previous = previousSibling(el, el.siblingIndex) ?: return NO_SIBLING_CAN
                matchFrom(previous, index - 1, work)
            }

            '~' -> {
                matchEarlierSibling(el, index, work)
            }

            else -> {
                FAILS_HERE
            }
        }
    }

    /** From the nearest back. When a sibling says none before it can match, that goes for those still to be tried here as well. */
    private fun matchEarlierSibling(el: SvgElement, index: Int, work: WorkMeter): Int {
        var position = el.siblingIndex
        while (true) {
            val previous = previousSibling(el, position) ?: return NO_SIBLING_CAN
            val result = matchFrom(previous, index - 1, work)
            if (result != FAILS_HERE) return result
            position--
        }
    }

    private fun previousSibling(el: SvgElement, position: Int): SvgElement? {
        val siblings = el.parent?.children ?: return null
        return if (position in 1..siblings.size) siblings[position - 1] else null
    }

    companion object {
        const val ID_WEIGHT: Int = 1_000_000
        const val CLASS_WEIGHT: Int = 1_000
        private const val MATCHES = 0
        private const val FAILS_HERE = 1
        private const val NO_SIBLING_CAN = 2
        private const val NO_ANCESTOR_CAN = 3
    }
}

/** Far past any real selector, and it keeps the recursion in the matching small. */
internal const val DEFAULT_MAX_SELECTOR_PARTS: Int = 256

/** `:not` can hold `:not`. Each level looks through the text again, and goes further down the stack. */
private const val MAX_NOT_DEPTH = 8

/**
 * Returns null for a selector this renderer cannot honour, such as `:hover`. Treating those as "never matches" is exactly right
 * for an import: nothing is ever hovered.
 */
internal fun parseSelector(raw: String, maxParts: Int = DEFAULT_MAX_SELECTOR_PARTS, tooLong: () -> Unit = {}): Selector? =
    parseSelector(raw, maxParts, tooLong, 0)

private fun parseSelector(raw: String, maxParts: Int, tooLong: () -> Unit, notDepth: Int): Selector? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val parts = ArrayList<CompoundSelector>()
    val combinators = ArrayList<Char>()
    var pos = 0
    while (pos < text.length) {
        var sawSpace = false
        while (pos < text.length && text[pos].isWhitespace()) {
            pos++
            sawSpace = true
        }
        if (pos >= text.length) break
        val c = text[pos]
        if (parts.isNotEmpty()) {
            if (c == '>' || c == '+' || c == '~') {
                combinators += c
                pos++
                while (pos < text.length && text[pos].isWhitespace()) pos++
            } else if (sawSpace) {
                combinators += ' '
            } else {
                return null
            }
        }
        val (compound, end) = parseCompound(text, pos, notDepth) ?: return null
        parts += compound
        if (parts.size > maxParts) {
            tooLong()
            return null
        }
        pos = end
    }
    if (parts.isEmpty() || combinators.size != parts.size - 1) return null
    return Selector(parts, combinators)
}

private fun parseCompound(text: String, start: Int, notDepth: Int): Pair<CompoundSelector, Int>? {
    var pos = start
    var tag: String? = null
    var id: String? = null
    val classes = ArrayList<String>(1)
    val attributes = ArrayList<AttributeMatch>(0)
    val pseudos = ArrayList<Pseudo>(0)
    var specificity = 0

    fun readIdent(): String {
        val out = StringBuilder()
        while (pos < text.length) {
            val c = text[pos]
            if (c == '\\' && pos + 1 < text.length) {
                out.append(text[pos + 1])
                pos += 2
            } else if (c.isLetterOrDigit() || c == '-' || c == '_' || c.code > 127) {
                out.append(c)
                pos++
            } else {
                break
            }
        }
        return out.toString()
    }

    if (pos < text.length && text[pos] == '*') {
        pos++
    } else if (pos < text.length && (text[pos].isLetter() || text[pos] == '_' || text[pos] == '\\')) {
        tag = readIdent()
        // A namespace prefix adds nothing here, since only SVG elements reach the cascade.
        if (pos < text.length && text[pos] == '|') {
            pos++
            tag = readIdent().ifEmpty { null }
        }
        specificity += 1
    }
    loop@ while (pos < text.length) {
        when (text[pos]) {
            '.' -> {
                pos++
                val name = readIdent()
                if (name.isEmpty()) return null
                classes += name
                specificity += Selector.CLASS_WEIGHT
            }

            '#' -> {
                pos++
                val name = readIdent()
                if (name.isEmpty()) return null
                id = name
                specificity += Selector.ID_WEIGHT
            }

            '[' -> {
                val close = text.indexOf(']', pos)
                if (close < 0) return null
                attributes += parseAttributeMatch(text.substring(pos + 1, close)) ?: return null
                pos = close + 1
                specificity += Selector.CLASS_WEIGHT
            }

            ':' -> {
                val (pseudo, end, weight) = parsePseudo(text, pos, notDepth) ?: return null
                pseudos += pseudo
                pos = end
                specificity += weight
            }

            else -> {
                break@loop
            }
        }
    }
    if (pos == start) return null
    return CompoundSelector(tag, id, classes, attributes, pseudos, specificity) to pos
}

private val ATTRIBUTE_SELECTOR =
    Regex("""^\s*([\w:-]+)\s*(?:([~^$*|]?=)\s*(?:"([^"]*)"|'([^']*)'|([^\s"']+))\s*(?:[iIsS]\s*)?)?$""")

private fun parseAttributeMatch(inner: String): AttributeMatch? {
    val match = ATTRIBUTE_SELECTOR.find(inner) ?: return null
    val (name, operator, doubleQuoted, singleQuoted, bare) = match.destructured
    return AttributeMatch(name, operator, doubleQuoted.ifEmpty { singleQuoted.ifEmpty { bare } })
}

private fun parsePseudo(text: String, start: Int, notDepth: Int): Triple<Pseudo, Int, Int>? {
    if (text.startsWith("::", start)) return null
    var pos = start + 1
    val nameStart = pos
    while (pos < text.length && (text[pos].isLetterOrDigit() || text[pos] == '-')) pos++
    val name = text.substring(nameStart, pos).lowercase()
    var argument = ""
    if (pos < text.length && text[pos] == '(') {
        val close = matchingParen(text, pos)
        if (close < 0) return null
        argument = text.substring(pos + 1, close).trim()
        pos = close + 1
    }
    val pseudo: Pseudo
    var weight = Selector.CLASS_WEIGHT
    when (name) {
        "root" -> {
            pseudo = Pseudo.Root
        }

        "first-child" -> {
            pseudo = Pseudo.Nth(0, 1, fromEnd = false, ofType = false)
        }

        "last-child" -> {
            pseudo = Pseudo.Nth(0, 1, fromEnd = true, ofType = false)
        }

        "first-of-type" -> {
            pseudo = Pseudo.Nth(0, 1, fromEnd = false, ofType = true)
        }

        "last-of-type" -> {
            pseudo = Pseudo.Nth(0, 1, fromEnd = true, ofType = true)
        }

        "nth-child", "nth-last-child", "nth-of-type", "nth-last-of-type" -> {
            val (a, b) = parseNth(argument) ?: return null
            pseudo = Pseudo.Nth(a, b, fromEnd = "last" in name, ofType = name.endsWith("type"))
        }

        "not" -> {
            if (notDepth >= MAX_NOT_DEPTH) return null
            val inner = ArrayList<CompoundSelector>()
            weight = 0
            for (part in splitTopLevel(argument, ',')) {
                val selector = parseSelector(part, DEFAULT_MAX_SELECTOR_PARTS, {}, notDepth + 1) ?: return null
                if (selector.parts.size != 1) return null
                inner += selector.subject
                weight = maxOf(weight, selector.specificity)
            }
            pseudo = Pseudo.Not(inner)
        }

        else -> {
            return null
        }
    }
    return Triple(pseudo, pos, weight)
}

private fun matchingParen(text: String, open: Int): Int {
    var depth = 0
    for (i in open until text.length) {
        if (text[i] == '(') depth++
        if (text[i] == ')') {
            depth--
            if (depth == 0) return i
        }
    }
    return -1
}

private fun parseNth(raw: String): Pair<Int, Int>? {
    val text = raw.lowercase().replace(" ", "")
    when (text) {
        "odd" -> return 2 to 1
        "even" -> return 2 to 0
    }
    val nIndex = text.indexOf('n')
    if (nIndex < 0) return text.toIntOrNull()?.let { 0 to it }
    val a =
        when (val head = text.substring(0, nIndex)) {
            "", "+" -> 1
            "-" -> -1
            else -> head.toIntOrNull() ?: return null
        }
    val tail = text.substring(nIndex + 1)
    val b = if (tail.isEmpty()) 0 else tail.toIntOrNull() ?: return null
    return a to b
}
