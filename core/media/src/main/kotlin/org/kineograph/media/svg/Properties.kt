package org.kineograph.media.svg

/** How a property's text becomes a [SvgValue]. The same choice is used for the static value and for every animated value. */
internal enum class PropKind {
    PAINT,
    COLOR,
    NUMBER,
    LENGTH,
    LENGTHS,
    DASH,
    TRANSFORM,
    TRANSLATE,
    ROTATE,
    SCALE,
    PATH,
    POINTS,
    NUMBERS,
    ORIGIN,
    FONT_SIZE,
    OFFSET_PATH,

    /** Case does not matter, such as `display: NONE`. */
    KEYWORD,

    /** Case matters, usually because the text holds an id, such as `clip-path: url(#Shape)`. */
    RAW,
}

/**
 * @property inherited whether children take this value when they do not set their own.
 * @property css whether a stylesheet may set it. Geometry such as `points` is attribute-only, and letting a rule set it would
 *   make a stray declaration change a shape that no browser would change.
 */
internal class PropDef(val kind: PropKind, val inherited: Boolean = false, val css: Boolean = true) {
    /** False for properties only CSS can set. The `rotate` attribute of `text` means something else entirely: a turn for each letter. */
    var attribute: Boolean = true
}

/** The prefix of a value the renderer adds itself: the extra transform from animateMotion, which no file can set directly. */
internal const val MOTION_PROPERTY: String = "\$motion"

internal val PROPERTIES: Map<String, PropDef> =
    buildMap {
        fun add(kind: PropKind, inherited: Boolean, css: Boolean, vararg names: String) {
            val def = PropDef(kind, inherited, css)
            for (name in names) put(name, def)
        }
        add(PropKind.PAINT, true, true, "fill", "stroke")
        add(PropKind.COLOR, true, true, "color")
        add(PropKind.COLOR, false, true, "stop-color")
        put("background-color", PropDef(PropKind.COLOR).apply { attribute = false })
        add(PropKind.NUMBER, false, true, "opacity", "stop-opacity")
        add(PropKind.NUMBER, true, true, "fill-opacity", "stroke-opacity", "stroke-miterlimit")
        add(PropKind.NUMBER, false, false, "pathLength", "offset")
        add(PropKind.LENGTH, true, true, "stroke-width", "stroke-dashoffset", "letter-spacing", "word-spacing")
        add(PropKind.FONT_SIZE, true, true, "font-size")
        add(PropKind.LENGTH, false, true, "cx", "cy", "r", "rx", "ry", "width", "height")
        add(PropKind.LENGTH, false, false, "textLength")
        put("offset-distance", PropDef(PropKind.LENGTH).apply { attribute = false })
        put("offset-path", PropDef(PropKind.OFFSET_PATH).apply { attribute = false })
        put("offset-rotate", PropDef(PropKind.KEYWORD).apply { attribute = false })
        add(
            PropKind.LENGTH,
            false,
            false,
            "x1",
            "y1",
            "x2",
            "y2",
            "fx",
            "fy",
            "fr",
            "refX",
            "refY",
            "markerWidth",
            "markerHeight",
            "startOffset",
        )
        add(PropKind.LENGTHS, false, true, "x", "y")
        add(PropKind.LENGTHS, false, false, "dx", "dy")
        add(PropKind.DASH, true, true, "stroke-dasharray")
        add(PropKind.TRANSFORM, false, true, "transform")
        add(PropKind.TRANSFORM, false, false, "gradientTransform", "patternTransform")
        put("translate", PropDef(PropKind.TRANSLATE).apply { attribute = false })
        put("rotate", PropDef(PropKind.ROTATE).apply { attribute = false })
        put("scale", PropDef(PropKind.SCALE).apply { attribute = false })
        add(PropKind.PATH, false, true, "d")
        add(PropKind.POINTS, false, false, "points")
        add(PropKind.NUMBERS, false, false, "viewBox")
        add(PropKind.ORIGIN, false, true, "transform-origin")
        add(
            PropKind.KEYWORD,
            true,
            true,
            "visibility",
            "fill-rule",
            "clip-rule",
            "stroke-linecap",
            "stroke-linejoin",
            "font-weight",
            "font-style",
            "text-anchor",
            "dominant-baseline",
            "shape-rendering",
            "image-rendering",
            "paint-order",
            "white-space",
            "text-transform",
            "font-variant",
            "font-kerning",
            "color-interpolation",
            "direction",
            "writing-mode",
            "text-orientation",
        )
        add(
            PropKind.KEYWORD,
            false,
            true,
            "display",
            "overflow",
            "transform-box",
            "mask-type",
            "vector-effect",
            "mix-blend-mode",
            "text-decoration",
            "text-decoration-line",
            "baseline-shift",
            "alignment-baseline",
        )
        add(PropKind.KEYWORD, true, false, "xml:space")
        add(PropKind.RAW, true, true, "font-family", "marker-start", "marker-mid", "marker-end")
        add(PropKind.RAW, false, true, "clip-path", "mask", "filter")
        add(
            PropKind.RAW,
            false,
            false,
            "href",
            "gradientUnits",
            "patternUnits",
            "patternContentUnits",
            "clipPathUnits",
            "maskUnits",
            "maskContentUnits",
            "spreadMethod",
            "preserveAspectRatio",
            "markerUnits",
            "orient",
            "lengthAdjust",
        )
    }

/** Longhands of the CSS `animation` shorthand. Never inherited, and read into timing rather than stored as a drawing property. */
internal val ANIMATION_LONGHANDS: Set<String> =
    setOf(
        "animation-name",
        "animation-duration",
        "animation-delay",
        "animation-iteration-count",
        "animation-direction",
        "animation-fill-mode",
        "animation-timing-function",
        "animation-play-state",
    )

/**
 * Reads a property's value. [css] says it came from a stylesheet or a style attribute rather than a presentation attribute. For most
 * properties that makes no difference, but CSS is stricter: there `x: 10` is a mistake, where the attribute `x="10"` is fine.
 */
internal fun parseProperty(name: String, raw: String, css: Boolean = false): SvgValue? {
    val def = PROPERTIES[name] ?: return null
    val text = raw.trim()
    if (text.isEmpty()) return null
    when (text.lowercase()) {
        "inherit" -> return InheritValue

        "initial" -> return InitialValue

        "unset", "revert", "revert-layer" -> return if (def.inherited) InheritValue else InitialValue

        // Lengths have no keyword of their own for "the usual". Both of these mean the initial value of the property.
        "auto", "normal" -> if (def.kind == PropKind.LENGTH || def.kind == PropKind.LENGTHS) return InitialValue
    }
    if (css) {
        if (def.kind == PropKind.TRANSFORM) return parseCssTransformList(text)?.let(::TransformValue)
        if (name !in NUMBERS_ALLOWED_IN_CSS && def.kind in LENGTH_KINDS && splitList(text).any { !hasCssUnit(it) }) return null
    }
    return parseKind(def.kind, text)
}

/**
 * Turns `rem` into pixels in a property's text, before it is read. Only where lengths can be: any text can contain those letters, and
 * the picture in a `data:` address is full of accidental matches.
 */
internal fun resolveRemIn(name: String, text: String, rootFontSize: Double): String {
    val kind = PROPERTIES[name]?.kind
    return if (kind == null || kind == PropKind.RAW || kind == PropKind.KEYWORD) text else resolveRem(text, rootFontSize)
}

/** The kinds holding lengths, which in CSS must have units, along with the properties that browsers exempt from that rule. */
private val LENGTH_KINDS = setOf(PropKind.LENGTH, PropKind.LENGTHS, PropKind.FONT_SIZE, PropKind.ORIGIN, PropKind.TRANSLATE)
private val NUMBERS_ALLOWED_IN_CSS = setOf("stroke-width", "stroke-dashoffset", "x", "y", "cx", "cy", "r", "rx", "ry", "width", "height")

private fun parseKind(kind: PropKind, text: String): SvgValue? =
    when (kind) {
        PropKind.PAINT -> parsePaint(text)
        PropKind.COLOR -> parseColorValue(text)
        PropKind.NUMBER -> parseFraction(text)?.let(::NumberValue)
        PropKind.LENGTH -> parseLengthOrNull(text)?.let(::LengthValue)
        PropKind.LENGTHS -> parseLengthList(text)?.let { if (it.size == 1) LengthValue(it[0]) else LengthListValue(it) }
        PropKind.DASH -> parseDash(text)
        PropKind.TRANSFORM -> parseTransformList(text)?.let(::TransformValue)
        PropKind.TRANSLATE -> parseTranslateProperty(text)
        PropKind.ROTATE -> parseRotateProperty(text)
        PropKind.SCALE -> parseScaleProperty(text)
        PropKind.PATH -> parsePathProperty(text)
        PropKind.POINTS, PropKind.NUMBERS -> parseNumberList(text)?.let { numbers -> LengthListValue(numbers.map { Length(px = it) }) }
        PropKind.ORIGIN -> parseOrigin(text)
        PropKind.FONT_SIZE -> parseFontSize(text)
        PropKind.OFFSET_PATH -> parseOffsetPath(text)
        PropKind.KEYWORD -> KeywordValue(text.lowercase())
        PropKind.RAW -> KeywordValue(text)
    }

/** The id inside `url(#id)`, or null when the text is not a same-file reference. */
internal fun urlReference(text: String): String? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("url(", ignoreCase = true)) return null
    val close = trimmed.indexOf(')')
    if (close < 0) return null
    val inner = trimmed
        .substring(4, close)
        .trim()
        .trim('"', '\'')
        .trim()
    return if (inner.startsWith("#") && inner.length > 1) inner.substring(1) else null
}

internal fun parsePaint(raw: String): PaintValue? = parsePaint(raw, 0)

/** The fallback of a paint is one colour in real files. A longer list only needs a limit, so that it cannot use up the stack. */
private fun parsePaint(raw: String, depth: Int): PaintValue? {
    if (depth > MAX_PAINT_FALLBACKS) return null
    val text = raw.trim()
    when (text.lowercase()) {
        "none" -> return PaintValue.None
        "currentcolor" -> return PaintValue.Current
        "context-fill" -> return PaintValue.ContextFill
        "context-stroke" -> return PaintValue.ContextStroke
    }
    if (text.startsWith("url(", ignoreCase = true)) {
        val close = text.indexOf(')')
        if (close < 0) return null
        val rest = text.substring(close + 1).trim()
        val fallback = if (rest.isEmpty()) null else parsePaint(rest, depth + 1) ?: return null
        return PaintValue.Ref(urlReference(text), fallback)
    }
    return parseColor(text)?.let(PaintValue::Solid)
}

private const val MAX_PAINT_FALLBACKS = 8

private fun parseColorValue(text: String): PaintValue? =
    if (text.equals("currentcolor", ignoreCase = true)) PaintValue.Current else parseColor(text)?.let(PaintValue::Solid)

private fun parseDash(text: String): DashValue? {
    if (text.equals("none", ignoreCase = true)) return DashValue(emptyList())
    val items = parseLengthList(text) ?: return null
    return if (items.any { it.px < 0 || it.pct < 0 || it.em < 0 }) null else DashValue(items)
}

private fun parseTranslateProperty(text: String): TransformValue? {
    if (text.equals("none", ignoreCase = true)) return TransformValue(emptyList())
    val parts = splitList(text)
    val x = parts.getOrNull(0)?.let(::parseLengthOrNull) ?: return null
    val y = if (parts.size > 1) parseLengthOrNull(parts[1]) ?: return null else Length.ZERO
    return TransformValue(listOf(TransformOp.Translate(x, y)))
}

private fun parseRotateProperty(text: String): TransformValue? {
    if (text.equals("none", ignoreCase = true)) return TransformValue(emptyList())
    return parseAngle(text)?.let { TransformValue(listOf(TransformOp.Rotate(it))) }
}

private fun parseScaleProperty(text: String): TransformValue? {
    if (text.equals("none", ignoreCase = true)) return TransformValue(emptyList())
    val parts = splitList(text)
    val x = parts.getOrNull(0)?.let(::parseFraction) ?: return null
    val y = if (parts.size > 1) parseFraction(parts[1]) ?: return null else x
    return TransformValue(listOf(TransformOp.Scale(x, y)))
}

/** The `d` attribute holds plain path data, while the `d` CSS property wraps it in `path("...")`. */
private fun parsePathProperty(text: String): PathValue? {
    if (text.equals("none", ignoreCase = true)) return PathValue(PathData.EMPTY)
    val source =
        if (text.startsWith("path(", ignoreCase = true) && text.endsWith(")")) {
            text.substring(5, text.length - 1).trim().trim('"', '\'')
        } else {
            text
        }
    val parsed = parsePathData(source)
    return PathValue(parsed.data, parsed.hadError)
}

/** Rays and shapes need a box to be measured against, and no file seen in the wild uses them, so those stay unread. */
internal fun parseOffsetPath(text: String): OffsetPathValue? {
    if (text.equals("none", ignoreCase = true)) return OffsetPathValue(null, null)
    urlReference(text)?.let { return OffsetPathValue(null, it) }
    if (!text.startsWith("path(", ignoreCase = true) || !text.endsWith(")")) return null
    val parsed = parsePathData(text.substring(5, text.length - 1).trim().trim('"', '\''))
    return if (parsed.data.isEmpty) null else OffsetPathValue(parsed.data, null)
}

private fun parseOrigin(text: String): LengthListValue? {
    val parts = splitList(text)
    if (parts.isEmpty()) return null
    var x: Length? = null
    var y: Length? = null
    val rest = ArrayList<Length>()
    for (part in parts) {
        when (part.lowercase()) {
            "left" -> x = Length(pct = 0.0)
            "right" -> x = Length(pct = 100.0)
            "top" -> y = Length(pct = 0.0)
            "bottom" -> y = Length(pct = 100.0)
            "center" -> rest += Length(pct = 50.0)
            else -> rest += parseLengthOrNull(part) ?: return null
        }
    }
    // Keywords may come in either order, while plain lengths always read as x then y. A missing axis means the centre.
    if (x == null && rest.isNotEmpty()) x = rest.removeAt(0)
    if (y == null && rest.isNotEmpty()) y = rest.removeAt(0)
    return LengthListValue(listOf(x ?: Length(pct = 50.0), y ?: Length(pct = 50.0)))
}

private fun parseFontSize(text: String): LengthValue? {
    val keyword =
        when (text.lowercase()) {
            "xx-small" -> Length(px = 9.0)
            "x-small" -> Length(px = 10.0)
            "small" -> Length(px = 13.0)
            "medium" -> Length(px = 16.0)
            "large" -> Length(px = 18.0)
            "x-large" -> Length(px = 24.0)
            "xx-large" -> Length(px = 32.0)
            "larger" -> Length(em = 1.2)
            "smaller" -> Length(em = 1.0 / 1.2)
            else -> null
        }
    return (keyword ?: parseLengthOrNull(text))?.let(::LengthValue)
}

/** A property's default where nothing sets it, needed as the starting point of an animation with no explicit `from`. */
internal fun initialValueOf(name: String): SvgValue? =
    when (name) {
        "fill" -> {
            PaintValue.Solid(Rgba.BLACK)
        }

        "stroke" -> {
            PaintValue.None
        }

        "color", "stop-color" -> {
            PaintValue.Solid(Rgba.BLACK)
        }

        "opacity", "stop-opacity", "fill-opacity", "stroke-opacity" -> {
            NumberValue(1.0)
        }

        "stroke-miterlimit" -> {
            NumberValue(4.0)
        }

        "offset" -> {
            NumberValue(0.0)
        }

        "stroke-width" -> {
            LengthValue(Length(px = 1.0))
        }

        "font-size" -> {
            LengthValue(Length(px = 16.0))
        }

        "stroke-dasharray" -> {
            DashValue(emptyList())
        }

        "transform", "translate", "rotate", "scale", "gradientTransform", "patternTransform" -> {
            TransformValue(emptyList())
        }

        MOTION_PROPERTY -> {
            TransformValue(emptyList())
        }

        "d" -> {
            PathValue(PathData.EMPTY)
        }

        "visibility" -> {
            KeywordValue("visible")
        }

        "display" -> {
            KeywordValue("inline")
        }

        else -> {
            val def = PROPERTIES[name]
            when (def?.kind) {
                PropKind.LENGTH, PropKind.LENGTHS -> LengthValue(Length.ZERO)
                else -> null
            }
        }
    }
