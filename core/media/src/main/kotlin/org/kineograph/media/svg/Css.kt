package org.kineograph.media.svg

internal class Declaration(val name: String, val value: String, val important: Boolean)

internal class StyleRule(val selector: Selector, val declarations: List<Declaration>, val order: Int)

internal class Keyframe(val offset: Double, val declarations: List<Declaration>)

internal class Stylesheet {
    val rules: ArrayList<StyleRule> = ArrayList()
    val keyframes: HashMap<String, List<Keyframe>> = HashMap()
}

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
) {
    private val text: String = stripComments(text)
    private var pos = 0

    fun parse() {
        while (true) {
            skipSpace()
            if (pos >= text.length) return
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
            val selector = parseSelector(part) ?: continue
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

    private fun nested(body: String) {
        CssParser(body, sheet, warnings, media).parse()
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

private val MEDIA_FEATURE = Regex("""\(\s*([a-z-]+)\s*(?::\s*([^)]*?)\s*)?\)""")

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
    while (matches && rest.isNotEmpty()) {
        if (rest.startsWith("and")) rest = rest.removePrefix("and").trim()
        val feature = MEDIA_FEATURE.find(rest)?.takeIf { it.range.first == 0 } ?: return false
        matches = mediaFeatureMatches(feature.groupValues[1], feature.groupValues[2], context)
        rest = rest.substring(feature.range.last + 1).trim()
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
    fun matches(el: SvgElement): Boolean {
        val actual =
            when (name) {
                "id" -> el.id
                "class" -> el.classes.joinToString(" ").ifEmpty { null }
                else -> el.attrs[name]
            } ?: return false
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
}

internal sealed interface Pseudo {
    fun matches(el: SvgElement): Boolean

    data object Root : Pseudo {
        override fun matches(el: SvgElement): Boolean = el.parent == null
    }

    /** `:nth-child(an+b)` and its relatives. All of them reduce to counting a position among some siblings. */
    class Nth(val a: Int, val b: Int, val fromEnd: Boolean, val ofType: Boolean) : Pseudo {
        override fun matches(el: SvgElement): Boolean {
            val parent = el.parent ?: return a == 0 && b == 1
            val position: Int
            if (ofType) {
                val pool = parent.children.filter { it.tag == el.tag }
                val index = pool.indexOf(el)
                position = if (fromEnd) pool.size - index else index + 1
            } else {
                position = if (fromEnd) parent.rawChildCount - el.rawIndex else el.rawIndex + 1
            }
            if (a == 0) return position == b
            val n = (position - b) / a
            return n >= 0 && a * n + b == position
        }
    }

    class Not(val inner: List<CompoundSelector>) : Pseudo {
        override fun matches(el: SvgElement): Boolean = inner.none { it.matches(el) }
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
    fun matches(el: SvgElement): Boolean {
        if (tag != null && tag != el.tag) return false
        if (id != null && id != el.id) return false
        for (name in classes) if (name !in el.classes) return false
        for (attribute in attributes) if (!attribute.matches(el)) return false
        for (pseudo in pseudos) if (!pseudo.matches(el)) return false
        return true
    }
}

/** [parts] run left to right. `combinators[i]` joins `parts[i]` and `parts[i + 1]`: a space, `>`, `+` or `~`. */
internal class Selector(val parts: List<CompoundSelector>, val combinators: List<Char>) {
    val specificity: Int = parts.sumOf { it.specificity }

    val subject: CompoundSelector get() = parts.last()

    fun matches(el: SvgElement): Boolean = matchesAt(el, parts.lastIndex)

    private fun matchesAt(el: SvgElement, index: Int): Boolean {
        if (!parts[index].matches(el)) return false
        if (index == 0) return true
        return when (combinators[index - 1]) {
            ' ' -> {
                var ancestor = el.parent
                while (ancestor != null) {
                    if (matchesAt(ancestor, index - 1)) return true
                    ancestor = ancestor.parent
                }
                false
            }

            '>' -> {
                el.parent?.let { matchesAt(it, index - 1) } ?: false
            }

            '+' -> {
                previousSiblings(el).lastOrNull()?.let { matchesAt(it, index - 1) } ?: false
            }

            '~' -> {
                previousSiblings(el).any { matchesAt(it, index - 1) }
            }

            else -> {
                false
            }
        }
    }

    private fun previousSiblings(el: SvgElement): List<SvgElement> {
        val siblings = el.parent?.children ?: return emptyList()
        return siblings.subList(0, el.siblingIndex.coerceIn(0, siblings.size))
    }

    companion object {
        const val ID_WEIGHT: Int = 1_000_000
        const val CLASS_WEIGHT: Int = 1_000
    }
}

/**
 * Returns null for a selector this renderer cannot honour, such as `:hover`. Treating those as "never matches" is exactly right
 * for an import: nothing is ever hovered.
 */
internal fun parseSelector(raw: String): Selector? {
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
        val (compound, end) = parseCompound(text, pos) ?: return null
        parts += compound
        pos = end
    }
    if (parts.isEmpty() || combinators.size != parts.size - 1) return null
    return Selector(parts, combinators)
}

private fun parseCompound(text: String, start: Int): Pair<CompoundSelector, Int>? {
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
                val (pseudo, end, weight) = parsePseudo(text, pos) ?: return null
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

private fun parsePseudo(text: String, start: Int): Triple<Pseudo, Int, Int>? {
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
            val inner = ArrayList<CompoundSelector>()
            weight = 0
            for (part in splitTopLevel(argument, ',')) {
                val selector = parseSelector(part) ?: return null
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
