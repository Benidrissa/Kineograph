package org.kineograph.media.svg

import android.util.Base64
import java.net.URLDecoder
import java.util.Locale

internal class BuiltModel(val model: SvgModel, val animation: SvgAnimationInfo)

/**
 * Runs the steps between the raw XML tree and a document that can be drawn: find ids, read stylesheets, run the cascade, build
 * the animations and work out their timing.
 */
internal class SvgModelBuilder(
    private val root: SvgElement,
    private val limits: SvgLimits,
    private val warnings: MutableSet<SvgWarning>,
    private val work: ParseWork,
    private val parseNested: (ByteArray, SvgLimits, ParseWork, MutableSet<SvgWarning>) -> SvgDocument?,
) {
    private val byId = HashMap<String, SvgElement>()
    private val sheet = Stylesheet()
    private val cssTimings = ArrayList<CssTiming>()
    private val embeddedAnimations = ArrayList<SvgAnimationInfo>()
    private var foundCssAnimation = false

    /** One table per property and origin, keyed by the text. The same declaration stays one string, whose hash is only worked out once. */
    private val parseCache = HashMap<String, HashMap<String, SvgValue?>>()
    private val shapeCache = HashMap<String, Boolean>()
    private var trackOrder = 0
    private var rootFontSize = DEFAULT_ROOT_FONT_SIZE
    private val mediaContext: MediaContext by lazy(LazyThreadSafetyMode.NONE) { readMediaContext() }

    fun build(): BuiltModel {
        index(root)
        readStylesheets(root)
        val rules = RuleIndex(sheet.rules)
        cascade(root, CustomProperties(emptyMap()), rules)
        val smil = SmilBuilder(root, byId, warnings, rootFontSize, work)
        smil.build()
        val summary =
            AnimationSummary.of(smil.foundAnimation || foundCssAnimation, smil.timings, cssTimings, embeddedAnimations, limits)
        val (width, height) = intrinsicSize()
        checkUseExpansion()
        freeAttributes(root)
        return BuiltModel(SvgModel(root, byId, width, height, limits, work.resources), summary)
    }

    private fun index(element: SvgElement) {
        element.id?.let { byId.putIfAbsent(it, element) }
        element.conditionsPass = evaluateConditions(element)
        for (child in element.children) index(child)
    }

    /** Conditional attributes pick what to draw inside `switch`. No extension is implemented, so anything requiring one fails. */
    private fun evaluateConditions(element: SvgElement): Boolean {
        val attrs = element.attrs
        if (attrs["requiredExtensions"] != null) return false
        val languages = attrs["systemLanguage"] ?: return true
        val device = Locale.getDefault().language.lowercase()
        return languages.split(',').any { it.trim().lowercase().substringBefore('-') == device }
    }

    private fun readStylesheets(element: SvgElement) {
        if (element.tag == "style") {
            val type = element.attrs["type"]?.trim()?.lowercase()
            val media = element.attrs["media"]
            if ((type == null || type.isEmpty() || type == "text/css") && (media == null || mediaMatches(media, mediaContext))) {
                val text = element.content?.joinToString("") { it as? String ?: "" }.orEmpty()
                CssParser(text, sheet, warnings, mediaContext, work, limits).parse()
            }
            return
        }
        for (child in element.children) readStylesheets(child)
    }

    /** Media queries come before the cascade, so this is the plain attributes' idea of the size, which is all a rule can rely on anyway. */
    private fun readMediaContext(): MediaContext {
        val box = root.attrs["viewBox"]?.let(::parseNumberList)?.takeIf { it.size == 4 }

        fun absolute(name: String): Double? = root.attrs[name]?.let(::parseLengthOrNull)?.takeIf { it.pct == 0.0 }?.let {
            it.px +
                it.em * 16.0
        }
        return MediaContext(absolute("width") ?: box?.get(2) ?: DEFAULT_WIDTH, absolute("height") ?: box?.get(3) ?: DEFAULT_HEIGHT)
    }

    /** Finds candidate rules by what their rightmost part requires, so a big stylesheet is not tested in full on every element. */
    private class RuleIndex(rules: List<StyleRule>) {
        private val byId = HashMap<String, ArrayList<StyleRule>>()
        private val byClass = HashMap<String, ArrayList<StyleRule>>()
        private val byTag = HashMap<String, ArrayList<StyleRule>>()
        private val universal = ArrayList<StyleRule>()

        init {
            for (rule in rules) {
                val subject = rule.selector.subject
                val bucket =
                    when {
                        subject.id != null -> byId.getOrPut(subject.id) { ArrayList(1) }
                        subject.classes.isNotEmpty() -> byClass.getOrPut(subject.classes[0]) { ArrayList(1) }
                        subject.tag != null -> byTag.getOrPut(subject.tag) { ArrayList(1) }
                        else -> universal
                    }
                bucket += rule
            }
        }

        fun matching(element: SvgElement, work: WorkMeter): List<StyleRule> {
            if (work.isStopped) return emptyList()
            val out = ArrayList<StyleRule>(0)

            fun test(rules: List<StyleRule>?) {
                if (rules == null) return
                for (rule in rules) if (rule.selector.matches(element, work)) out += rule
            }
            test(universal)
            test(byTag[element.tag])
            element.id?.let { test(byId[it]) }
            for (name in element.classes) test(byClass[name])
            if (out.size <= 1) return out
            work.spend(out.size * WorkCost.SELECTOR_STEP)
            return out.sortedWith(compareBy({ it.selector.specificity }, { it.order }))
        }
    }

    /**
     * Applies the CSS cascade to one element and then its children. From lowest to highest: presentation attributes, rules by specificity
     * and then order, the style attribute, important rules, and an important style attribute. Each level overwrites the one below.
     */
    private fun cascade(element: SvgElement, inheritedCustom: CustomProperties, rules: RuleIndex) {
        // Their attributes only look like properties. The `fill` of an animate element says "freeze", and that is no colour.
        if (element.tag in ANIMATION_TAGS) return
        work.spend(WorkCost.ELEMENT_STYLED)
        val matched = rules.matching(element, work)
        val inline = readInlineStyle(element)
        val ordered = ArrayList<Declaration>()
        var affordable = 0
        for (rule in matched) {
            // Once the allowance has gone, nothing more comes from stylesheets, which leaves a cost that only grows with the file.
            if (!work.spend(rule.declarations.size * WorkCost.DECLARATION_APPLIED)) break
            affordable++
            for (declaration in rule.declarations) if (!declaration.important) ordered += declaration
        }
        for (declaration in inline) if (!declaration.important) ordered += declaration
        for (i in 0 until affordable) for (declaration in matched[i].declarations) if (declaration.important) ordered += declaration
        for (declaration in inline) if (declaration.important) ordered += declaration

        var custom = inheritedCustom
        for (declaration in ordered) {
            if (declaration.name.startsWith("--") && !work.isStopped && custom.values[declaration.name] != declaration.value) {
                // Only an element that changes something gets its own copy. A rule that sets the same values everywhere then costs little.
                if (custom === inheritedCustom) {
                    work.spend(custom.values.size * WorkCost.SELECTOR_STEP)
                    custom = CustomProperties(HashMap(inheritedCustom.values))
                }
                (custom.values as MutableMap)[declaration.name] = declaration.value
            }
        }

        if (element === root) rootFontSize = readRootFontSize(ordered)
        val state = CascadeState(element, custom)
        for ((name, value) in element.attrs) state.applyAttribute(name, value)
        for (declaration in ordered) state.applyDeclaration(declaration)
        element.props = state.props
        element.important = state.important
        element.setsInherited = state.props.keys.any { PROPERTIES[it]?.inherited == true }
        settleInherit(element, state.props)
        readImage(element, state)
        buildCssAnimations(element, state)
        for (child in element.children) cascade(child, custom, rules)
    }

    private fun readInlineStyle(element: SvgElement): List<Declaration> {
        val text = element.attrs["style"] ?: return emptyList()
        // Each declaration can be a few characters, and comes to a few small objects.
        if (!HeapWatch.hasRoomForText(text.length, BYTES_PER_CHAR_OF_DECLARATIONS)) {
            warnings += SvgWarning.LIMIT_REACHED
            return emptyList()
        }
        return parseDeclarations(text)
    }

    /** `inherit` on a property that does not inherit by itself. A parent is always finished before its children, so this takes one look. */
    private fun settleInherit(element: SvgElement, props: Map<String, SvgValue>) {
        var settled: HashMap<String, SvgValue>? = null
        for ((name, value) in props) {
            if (value !== InheritValue || PROPERTIES[name]?.inherited == true) continue
            val fromParent = element.parent?.staticView?.get(name) ?: continue
            val map = settled ?: HashMap<String, SvgValue>(4).also { settled = it }
            map[name] = fromParent
        }
        element.inheritedFromParent = settled
    }

    /** Everything measured in `rem` depends on this, including on the outermost element itself, so it is read before that is styled. */
    private fun readRootFontSize(declarations: List<Declaration>): Double {
        var size = root.attrs["font-size"]?.let { parseProperty("font-size", it) } as? LengthValue
        for (declaration in declarations) {
            if (declaration.name != "font-size") continue
            (parseProperty("font-size", declaration.value, css = true) as? LengthValue)?.let { size = it }
        }
        return size?.length?.resolve(DEFAULT_ROOT_FONT_SIZE, DEFAULT_ROOT_FONT_SIZE)?.takeIf { it > 0.0 } ?: DEFAULT_ROOT_FONT_SIZE
    }

    /** The custom properties in force on an element. What their values come to is kept, since many elements can share one set of them. */
    private class CustomProperties(val values: Map<String, String>) {
        val resolved = HashMap<String, String?>()
        val beingResolved = HashSet<String>()
    }

    private inner class CascadeState(val element: SvgElement, val custom: CustomProperties) {
        val props = HashMap<String, SvgValue>()
        val important = HashSet<String>(0)
        val animationProps = HashMap<String, String>(0)

        fun applyAttribute(name: String, value: String) {
            when (name) {
                // Text keeps its words but loses these effects, which cannot be noticed from the file alone, so it gets a warning.
                "rotate", "textLength", "lengthAdjust" -> {
                    if (element.tag == "text" || element.tag == "tspan") warnings += SvgWarning.UNSUPPORTED_FEATURE_IGNORED
                }

                "font" -> {
                    expandFont(value)?.forEach { (longhand, part) -> store(longhand, part, fromCss = false, isImportant = false) }
                }

                else -> {
                    store(name, value, fromCss = false, isImportant = false)
                }
            }
        }

        fun applyDeclaration(declaration: Declaration) {
            val name = declaration.name
            if (name.startsWith("--")) return
            work.spend(declaration.value.length / LONG_TEXT_CHARS_PER_UNIT)
            val value = substitute(declaration.value, custom) ?: return
            when {
                name == "animation" -> {
                    animationProps.putAll(expandAnimationShorthand(value))
                }

                name in ANIMATION_LONGHANDS -> {
                    animationProps[name] = value
                }

                name == "font" -> {
                    expandFont(value)?.forEach { (longhand, part) -> store(longhand, part, true, declaration.important) }
                }

                // The parts of the CSS masks model that a picture from this file format can mean.
                name == "mask-image" || name == "-webkit-mask-image" || name == "-webkit-mask" -> {
                    store("mask", value, true, declaration.important)
                }

                name == "marker" -> {
                    store("marker-start", value, true, declaration.important)
                    store("marker-mid", value, true, declaration.important)
                    store("marker-end", value, true, declaration.important)
                }

                name == "offset-path" -> {
                    warnings += SvgWarning.UNSUPPORTED_FEATURE_IGNORED
                }

                else -> {
                    store(name, value, true, declaration.important)
                }
            }
        }

        private fun store(name: String, rawValue: String, fromCss: Boolean, isImportant: Boolean) {
            val def = PROPERTIES[name] ?: return
            if (fromCss && !def.css) return
            if (!fromCss && !def.attribute) return
            // An empty attribute says nothing, so there is nothing to warn about.
            if (rawValue.isBlank()) return
            // Looking through a long value is not free, and one stylesheet rule can make every element do it.
            if (!fromCss) work.spend(rawValue.length / LONG_TEXT_CHARS_PER_UNIT)
            // A plain presentation attribute may not use var(), but real files do, and browsers treat it as a declaration.
            val text = resolveRemIn(name, if ("var(" in rawValue) substitute(rawValue, custom) ?: return else rawValue, rootFontSize)
            if (!HeapWatch.hasRoomForText(text.length, bytesPerChar(def.kind))) {
                warnings += SvgWarning.LIMIT_REACHED
                return
            }
            val value = parsedValue(name, text, fromCss)
            if (value == null) {
                warnings += SvgWarning.INVALID_CONTENT_IGNORED
                return
            }
            noteUnsupported(element, name, value)
            props[name] = value
            if (isImportant) important += name else important -= name
        }
    }

    private fun parsedValue(name: String, text: String, css: Boolean): SvgValue? {
        val cache = parseCache.getOrPut(if (css) name else "attribute:$name") { HashMap() }
        if (cache.containsKey(text)) return cache[text]
        val value = parseProperty(name, text, css)
        cache[text] = value
        return value
    }

    /** About how much memory a value can take for each character of its text. A list of numbers is by far the worst. */
    private fun bytesPerChar(kind: PropKind): Int =
        when (kind) {
            PropKind.LENGTHS, PropKind.POINTS, PropKind.NUMBERS, PropKind.DASH -> 20
            PropKind.PATH -> 6
            else -> 2
        }

    /** Warnings about what a value asks for, as opposed to whether it can be read at all. */
    private fun noteUnsupported(element: SvgElement, name: String, value: SvgValue) {
        when (name) {
            "filter" -> {
                if ((value as? KeywordValue)?.text?.lowercase() != "none") warnings += SvgWarning.FILTER_IGNORED
            }

            "mix-blend-mode" -> {
                if ((value as? KeywordValue)?.text != "normal") warnings += SvgWarning.UNSUPPORTED_FEATURE_IGNORED
            }

            "fill", "stroke" -> {
                if (value is PaintValue.Ref && value.id == null) warnings += SvgWarning.EXTERNAL_REFERENCE_IGNORED
            }

            "clip-path", "mask" -> {
                val text = (value as? KeywordValue)?.text.orEmpty()
                // Only worked out once for each text. One rule can give every element the same shape, and it can be a very long one.
                val isShape = name == "clip-path" && shapeCache.getOrPut(text) { parseBasicShape(text) != null }
                if (!isShape && !text.equals("none", ignoreCase = true) && urlReference(text) == null) {
                    warnings +=
                        if (text.startsWith("url(", ignoreCase = true)) {
                            SvgWarning.EXTERNAL_REFERENCE_IGNORED
                        } else {
                            SvgWarning.UNSUPPORTED_FEATURE_IGNORED
                        }
                }
            }

            "d" -> {
                if ((value as? PathValue)?.hadError == true) warnings += SvgWarning.INVALID_CONTENT_IGNORED
            }

            "href" -> {
                val text = (value as? KeywordValue)?.text?.trim().orEmpty()
                // A link is only followed if someone taps it, so unlike the others it brings nothing into the picture.
                val isResource = element.tag != "a"
                if (isResource && text.isNotEmpty() && !text.startsWith("#") && !text.startsWith("data:", ignoreCase = true)) {
                    warnings += SvgWarning.EXTERNAL_REFERENCE_IGNORED
                }
            }
        }
    }

    /**
     * Replaces `var(--name, fallback)`. Returns null when a variable has no value and no fallback, which voids the declaration.
     *
     * Variables can refer to variables, many times each, which is exponential if it is simply followed. So what each one comes to is
     * kept, loops through a variable are refused as CSS says they should be, and all of it is paid for.
     */
    private fun substitute(value: String, custom: CustomProperties, depth: Int = 0): String? {
        var start = value.indexOf("var(")
        if (start < 0) return value
        if (depth > MAX_VAR_DEPTH) return null
        val out = StringBuilder(value.length)
        var position = 0
        while (start >= 0) {
            if (!work.spend(WorkCost.VAR_REPLACED)) return null
            val close = closingBracket(value, start + 3)
            if (close < 0) return null
            val inner = value.substring(start + 4, close)
            val comma = inner.indexOf(',')
            val name = (if (comma >= 0) inner.substring(0, comma) else inner).trim()
            val replacement =
                customValue(name, custom, depth)
                    ?: if (comma >= 0) substitute(inner.substring(comma + 1).trim(), custom, depth + 1) else null
            if (replacement == null) return null
            out.append(value, position, start).append(replacement)
            if (out.length > MAX_SUBSTITUTED_CHARS) {
                warnings += SvgWarning.LIMIT_REACHED
                return null
            }
            // Short texts copied again and again are the only way this grows, and that is what is paid for here.
            work.spend(replacement.length / LONG_TEXT_CHARS_PER_UNIT)
            position = close + 1
            start = value.indexOf("var(", position)
        }
        return out.append(value, position, value.length).toString()
    }

    private fun customValue(name: String, custom: CustomProperties, depth: Int): String? {
        if (custom.resolved.containsKey(name)) return custom.resolved[name]
        val raw = custom.values[name] ?: return null
        if (!custom.beingResolved.add(name)) return null
        val result = substitute(raw, custom, depth + 1)
        custom.beingResolved -= name
        // A cut-off result says nothing about what this variable means, and only short ones are worth holding on to for many elements.
        if (!work.isStopped && (result == null || result.length <= MAX_KEPT_SUBSTITUTION_CHARS)) custom.resolved[name] = result
        return result
    }

    private fun closingBracket(text: String, open: Int): Int {
        var nesting = 0
        for (i in open until text.length) {
            if (text[i] == '(') nesting++
            if (text[i] == ')') {
                nesting--
                if (nesting == 0) return i
            }
        }
        return -1
    }

    private fun readImage(element: SvgElement, state: CascadeState) {
        if (element.tag != "image") return
        val href = (state.props["href"] as? KeywordValue)?.text?.trim() ?: return
        if (!href.startsWith("data:", ignoreCase = true)) return
        val image = decodeDataUri(href)
        if (image == null) {
            warnings += SvgWarning.INVALID_CONTENT_IGNORED
            return
        }
        element.image = image
        if (image.mimeType == "image/svg+xml") {
            val bytes = image.bytes
            if (limits.maxNestedImages <= 0 || work.isStopped || bytes == null) {
                warnings += SvgWarning.LIMIT_REACHED
                element.image = null
                return
            }
            // Read now rather than at first draw, so that what the inner picture ignored is known before the import starts.
            val nested = parseNested(bytes, limits.copy(maxNestedImages = limits.maxNestedImages - 1), work, warnings)
            if (nested == null) {
                element.image = null
            } else {
                image.nested = nested
                image.bytes = null
                warnings += nested.warnings
                embeddedAnimations += nested.animation
            }
        }
    }

    private fun decodeDataUri(uri: String): EmbeddedImage? {
        val comma = uri.indexOf(',')
        if (comma < 0) return null
        val meta = uri.substring(5, comma).lowercase()
        val payload = uri.substring(comma + 1)
        val isBase64 = meta.endsWith(";base64")
        val mime = meta.substringBefore(';').trim().ifEmpty { "text/plain" }
        val bytes =
            try {
                if (isBase64) {
                    Base64.decode(percentDecodeIfNeeded(payload), Base64.DEFAULT)
                } else {
                    percentDecodeBytes(payload)
                }
            } catch (_: IllegalArgumentException) {
                return null
            }
        return EmbeddedImage(mime, bytes)
    }

    private fun percentDecodeIfNeeded(text: String): String =
        if ('%' in text) URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") else text

    private fun percentDecodeBytes(text: String): ByteArray =
        URLDecoder.decode(text.replace("+", "%2B"), "UTF-8").toByteArray(Charsets.UTF_8)

    private fun buildCssAnimations(element: SvgElement, state: CascadeState) {
        val names = state.animationProps["animation-name"] ?: return
        val nameList = splitTopLevel(names, ',').map { it.trim().trim('"', '\'') }
        // Split once. Splitting a long list again for every entry of another long list would be quadratic.
        val lists = HashMap<String, List<String>>()

        fun longhand(key: String, index: Int, default: String): String {
            val list = lists.getOrPut(key) { state.animationProps[key]?.let { splitTopLevel(it, ',') }.orEmpty() }
            return if (list.isEmpty()) default else list[index % list.size].trim()
        }
        for ((index, name) in nameList.withIndex()) {
            if (name.equals("none", ignoreCase = true) || name.isEmpty()) continue
            if (!work.spend(WorkCost.ANIMATION_ENTRY)) return
            val frames = sheet.keyframes[name] ?: continue
            foundCssAnimation = true
            val duration = parseCssTime(longhand("animation-duration", index, "0s"))?.coerceAtLeast(0.0)?.let(::usableTime) ?: 0.0
            val delay = parseCssTime(longhand("animation-delay", index, "0s"))?.coerceIn(-MAX_TIME_SECONDS, MAX_TIME_SECONDS) ?: 0.0
            val iterationText = longhand("animation-iteration-count", index, "1").lowercase()
            val iterations =
                if (iterationText == "infinite") {
                    Double.POSITIVE_INFINITY
                } else {
                    parseNumberOrNull(iterationText)?.coerceAtLeast(0.0) ?: 1.0
                }
            val timing =
                CssTiming(
                    duration,
                    delay,
                    iterations,
                    longhand("animation-direction", index, "normal").lowercase(),
                    longhand("animation-fill-mode", index, "none").lowercase(),
                    longhand("animation-play-state", index, "running").lowercase() == "paused",
                )
            val defaultEasing = parseEasing(longhand("animation-timing-function", index, "ease")) ?: Easing.EASE
            cssTimings += timing
            buildCssTracks(element, frames, timing, defaultEasing, state)
        }
    }

    private fun buildCssTracks(element: SvgElement, frames: List<Keyframe>, timing: CssTiming, defaultEasing: Easing, state: CascadeState) {
        val perProperty = LinkedHashMap<String, ArrayList<CssFrame>>()
        work.spend(frames.size * WorkCost.SELECTOR_STEP)
        for (frame in frames.sortedBy { it.offset }) {
            if (!work.spend(frame.declarations.size * WorkCost.KEYFRAME_VALUE)) break
            val easing =
                frame.declarations
                    .lastOrNull { it.name == "animation-timing-function" }
                    ?.let { parseEasing(it.value) }
                    ?: defaultEasing
            for (declaration in frame.declarations) {
                val def = PROPERTIES[declaration.name] ?: continue
                if (!def.css) continue
                val text = substitute(declaration.value, state.custom) ?: continue
                val value = parsedValue(declaration.name, resolveRemIn(declaration.name, text, rootFontSize), css = true) ?: continue
                // inherit in a keyframe would need the parent at each frame. Reading it as "the underlying value" looks the same.
                val entry = CssFrame(frame.offset, value.takeUnless { it === InheritValue || it === InitialValue }, easing)
                val list = perProperty.getOrPut(declaration.name) { ArrayList(4) }
                // Two blocks can name the same moment. The last one wins, as with any other CSS.
                if (list.isNotEmpty() && list.last().offset == frame.offset) list[list.lastIndex] = entry else list += entry
            }
        }
        for ((property, list) in perProperty) {
            if (list.isEmpty()) continue
            // A moment the file leaves out is filled in with the value the property has without the animation.
            if (list.first().offset > 0.0) list.add(0, CssFrame(0.0, null, defaultEasing))
            if (list.last().offset < 1.0) list += CssFrame(1.0, null, defaultEasing)
            element.addTrack(CssTrack(property, trackOrder++, timing, list))
        }
    }

    /** A zero duration means something of its own, and must stay zero. Anything else is kept to what a timeline can work with. */
    private fun usableTime(seconds: Double): Double = if (seconds > 0.0) usableDuration(seconds) else 0.0

    private fun intrinsicSize(): Pair<Double, Double> {
        val view = root.staticView
        val viewBox = (root.props["viewBox"] as? LengthListValue)?.items?.takeIf { it.size == 4 }
        val boxWidth = viewBox?.get(2)?.px?.takeIf { it > 0.0 }
        val boxHeight = viewBox?.get(3)?.px?.takeIf { it > 0.0 }

        // A percentage has nothing to refer to before the picture is placed, so it counts as "not set", leaving the viewBox to decide.
        fun absolute(length: Length?): Double? = length?.takeIf { it.pct == 0.0 }?.let { it.px + it.em * 16.0 }?.takeIf { it >= 0.0 }
        val width = absolute(view.length("width"))
        val height = absolute(view.length("height"))
        val aspect = if (boxWidth != null && boxHeight != null) boxWidth / boxHeight else null
        return when {
            width != null && height != null -> width to height
            width != null -> width to (aspect?.let { width / it } ?: DEFAULT_HEIGHT)
            height != null -> (aspect?.let { height * it } ?: DEFAULT_WIDTH) to height
            boxWidth != null && boxHeight != null -> boxWidth to boxHeight
            else -> DEFAULT_WIDTH to DEFAULT_HEIGHT
        }
    }

    /**
     * References can multiply: ten levels of ten `use` elements each draw ten billion shapes, and a long chain of them goes far deeper
     * than the file does. Drawing is capped either way. This only tells the person in advance that some of the picture will be missing,
     * so that the cap is never silent.
     *
     * Without recursion, because a chain can be as long as the file: walking it down must not need more stack than drawing it would.
     */
    private fun checkUseExpansion() {
        class Visit(val element: SvgElement, val next: List<SvgElement>, val depth: Int) {
            var position = 0
            var total = 1L
            var deepest = depth
        }

        class Result(val total: Long, val deepest: Int)

        fun nextOf(element: SvgElement): List<SvgElement> {
            if (element.tag != "use") return element.children
            val href = (element.props["href"] as? KeywordValue)?.text?.trim()
            val target = href?.takeIf { it.startsWith("#") }?.let { byId[it.substring(1)] } ?: return element.children
            return element.children + target
        }
        val done = HashMap<SvgElement, Result>()
        val visiting = HashSet<SvgElement>()
        val stack = ArrayList<Visit>()
        visiting += root
        stack += Visit(root, nextOf(root), 0)
        var whole = Result(1L, 0)
        while (stack.isNotEmpty()) {
            val visit = stack[stack.lastIndex]
            if (visit.position < visit.next.size) {
                val child = visit.next[visit.position++]
                val known = done[child]
                if (known != null) {
                    visit.total = (visit.total + known.total).coerceAtMost(EXPANSION_CAP)
                    visit.deepest = maxOf(visit.deepest, visit.depth + 1 + known.deepest)
                } else if (visiting.add(child)) {
                    stack += Visit(child, nextOf(child), visit.depth + 1)
                }
                continue
            }
            stack.removeAt(stack.lastIndex)
            visiting -= visit.element
            // Depth below this element, which is what can be reused from wherever else it is referenced.
            val result = Result(visit.total, visit.deepest - visit.depth)
            done[visit.element] = result
            whole = result
            stack.lastOrNull()?.let { parent ->
                parent.total = (parent.total + result.total).coerceAtMost(EXPANSION_CAP)
                parent.deepest = maxOf(parent.deepest, visit.deepest)
            }
        }
        if (whole.total > limits.maxRenderedNodes || whole.deepest > limits.maxDepth) warnings += SvgWarning.LIMIT_REACHED
    }

    /** The raw text has been parsed into typed values by now, so it can go. On a large file this is a good part of the memory. */
    private fun freeAttributes(element: SvgElement) {
        element.attrs = emptyMap()
        for (child in element.children) freeAttributes(child)
    }

    private companion object {
        // What CSS gives a replaced element that says nothing about its size.
        const val DEFAULT_WIDTH = 300.0
        const val DEFAULT_HEIGHT = 150.0
        const val EXPANSION_CAP = 1L shl 56
        const val DEFAULT_ROOT_FONT_SIZE = 16.0

        /** Reading through long text is quick, and a charge per character would swamp everything else. */
        const val LONG_TEXT_CHARS_PER_UNIT = 8L
        const val BYTES_PER_CHAR_OF_DECLARATIONS = 30

        /** Variables in real files hold a colour or a length, and only rarely a path. This is enough for any of those. */
        const val MAX_VAR_DEPTH = 64
        const val MAX_SUBSTITUTED_CHARS = 4 * 1024 * 1024
        const val MAX_KEPT_SUBSTITUTION_CHARS = 64 * 1024
    }
}

internal fun expandAnimationShorthand(value: String): Map<String, String> {
    val names = ArrayList<String>()
    val durations = ArrayList<String>()
    val delays = ArrayList<String>()
    val iterations = ArrayList<String>()
    val directions = ArrayList<String>()
    val fillModes = ArrayList<String>()
    val easings = ArrayList<String>()
    val playStates = ArrayList<String>()
    for (part in splitTopLevel(value, ',')) {
        var name: String? = null
        var duration: String? = null
        var delay: String? = null
        var iteration: String? = null
        var direction: String? = null
        var fillMode: String? = null
        var easing: String? = null
        var playState: String? = null
        for (token in splitList(part)) {
            val lower = token.lowercase()
            when {
                parseCssTime(token) != null -> if (duration == null) {
                    duration = token
                } else if (delay == null) {
                    delay = token
                }

                easing == null && parseEasing(lower) != null -> easing = token

                iteration == null && (lower == "infinite" || parseNumberOrNull(token) != null) -> iteration = token

                direction == null && lower in setOf("normal", "reverse", "alternate", "alternate-reverse") -> direction = lower

                fillMode == null && lower in setOf("none", "forwards", "backwards", "both") -> fillMode = lower

                playState == null && lower in setOf("running", "paused") -> playState = lower

                name == null -> name = token.trim('"', '\'')
            }
        }
        names += name ?: "none"
        durations += duration ?: "0s"
        delays += delay ?: "0s"
        iterations += iteration ?: "1"
        directions += direction ?: "normal"
        fillModes += fillMode ?: "none"
        easings += easing ?: "ease"
        playStates += playState ?: "running"
    }
    return mapOf(
        "animation-name" to names.joinToString(","),
        "animation-duration" to durations.joinToString(","),
        "animation-delay" to delays.joinToString(","),
        "animation-iteration-count" to iterations.joinToString(","),
        "animation-direction" to directions.joinToString(","),
        "animation-fill-mode" to fillModes.joinToString(","),
        "animation-timing-function" to easings.joinToString(","),
        "animation-play-state" to playStates.joinToString(","),
    )
}

/** Splits the `font` shorthand. Returns null when it holds no size, which is what makes the shorthand valid. */
internal fun expandFont(value: String): List<Pair<String, String>>? {
    val tokens = ArrayList<String>()
    val pieces = StringBuilder()
    var quote = '\u0000'
    for (c in value) {
        if (quote != '\u0000') {
            if (c == quote) quote = '\u0000'
            pieces.append(c)
        } else if (c == '"' || c == '\'') {
            quote = c
            pieces.append(c)
        } else if (isSvgSpace(c)) {
            if (pieces.isNotEmpty()) {
                tokens += pieces.toString()
                pieces.clear()
            }
        } else {
            pieces.append(c)
        }
    }
    if (pieces.isNotEmpty()) tokens += pieces.toString()
    val out = ArrayList<Pair<String, String>>()
    var index = 0
    while (index < tokens.size) {
        val token = tokens[index]
        val lower = token.lowercase()
        when {
            lower == "italic" || lower == "oblique" -> {
                out += "font-style" to lower
            }

            lower == "bold" || lower == "bolder" || lower == "lighter" -> {
                out += "font-weight" to lower
            }

            lower.toIntOrNull()?.let { it in 1..1000 } == true -> {
                out += "font-weight" to lower
            }

            lower == "normal" || lower == "small-caps" || lower.endsWith("condensed") || lower.endsWith("expanded") -> {
                Unit
            }

            else -> {
                val size = token.substringBefore('/')
                if (parseProperty("font-size", size, css = true) == null) return null
                out += "font-size" to size
                val family = tokens.subList(index + 1, tokens.size).joinToString(" ")
                if (family.isEmpty()) return null
                out += "font-family" to family
                return out
            }
        }
        index++
    }
    return null
}
