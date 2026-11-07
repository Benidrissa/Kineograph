package org.kineograph.media.svg

import android.graphics.Paint

/**
 * The properties children inherit, in computed form. Elements that set nothing share their parent's object, which keeps the cost
 * of walking a big picture down, since most elements in real files set nothing that is inherited.
 */
internal class Style {
    var color: Rgba = Rgba.BLACK
    var fill: PaintValue = PaintValue.Solid(Rgba.BLACK)
    var stroke: PaintValue = PaintValue.None
    var fillOpacity: Double = 1.0
    var strokeOpacity: Double = 1.0
    var fillEvenOdd: Boolean = false
    var clipEvenOdd: Boolean = false
    var strokeWidth: Length = Length(px = 1.0)
    var lineCap: Paint.Cap = Paint.Cap.BUTT
    var lineJoin: Paint.Join = Paint.Join.MITER
    var miterLimit: Double = 4.0
    var dashArray: List<Length> = emptyList()
    var dashOffset: Length = Length.ZERO
    var fontFamily: String = ""
    var fontSize: Double = 16.0
    var fontWeight: Int = 400
    var italic: Boolean = false
    var letterSpacing: Length = Length.ZERO
    var wordSpacing: Length = Length.ZERO
    var textAnchor: Int = ANCHOR_START
    var baseline: String = "auto"
    var textTransform: String = "none"
    var linearColors: Boolean = false
    var fontFeatures: String = ""
    var visible: Boolean = true
    var crispEdges: Boolean = false
    var pixelatedImages: Boolean = false
    var preserveSpace: Boolean = false
    var markerStart: String? = null
    var markerMid: String? = null
    var markerEnd: String? = null
    var paintOrder: IntArray = DEFAULT_PAINT_ORDER

    fun copy(): Style {
        val s = Style()
        s.color = color
        s.fill = fill
        s.stroke = stroke
        s.fillOpacity = fillOpacity
        s.strokeOpacity = strokeOpacity
        s.fillEvenOdd = fillEvenOdd
        s.clipEvenOdd = clipEvenOdd
        s.strokeWidth = strokeWidth
        s.lineCap = lineCap
        s.lineJoin = lineJoin
        s.miterLimit = miterLimit
        s.dashArray = dashArray
        s.dashOffset = dashOffset
        s.fontFamily = fontFamily
        s.fontSize = fontSize
        s.fontWeight = fontWeight
        s.italic = italic
        s.letterSpacing = letterSpacing
        s.wordSpacing = wordSpacing
        s.textAnchor = textAnchor
        s.baseline = baseline
        s.textTransform = textTransform
        s.linearColors = linearColors
        s.fontFeatures = fontFeatures
        s.visible = visible
        s.crispEdges = crispEdges
        s.pixelatedImages = pixelatedImages
        s.preserveSpace = preserveSpace
        s.markerStart = markerStart
        s.markerMid = markerMid
        s.markerEnd = markerEnd
        s.paintOrder = paintOrder
        return s
    }

    /** This style's value of an inherited property in value form, as the starting point of an animation with no `from`. */
    fun inheritedValue(name: String): SvgValue? =
        when (name) {
            "color" -> PaintValue.Solid(color)
            "fill" -> fill
            "stroke" -> stroke
            "fill-opacity" -> NumberValue(fillOpacity)
            "stroke-opacity" -> NumberValue(strokeOpacity)
            "stroke-width" -> LengthValue(strokeWidth)
            "stroke-miterlimit" -> NumberValue(miterLimit)
            "stroke-dasharray" -> DashValue(dashArray)
            "stroke-dashoffset" -> LengthValue(dashOffset)
            "font-size" -> LengthValue(Length(px = fontSize))
            "letter-spacing" -> LengthValue(letterSpacing)
            "word-spacing" -> LengthValue(wordSpacing)
            "visibility" -> KeywordValue(if (visible) "visible" else "hidden")
            else -> null
        }

    companion object {
        const val ANCHOR_START: Int = 0
        const val ANCHOR_MIDDLE: Int = 1
        const val ANCHOR_END: Int = 2
        const val PAINT_FILL: Int = 0
        const val PAINT_STROKE: Int = 1
        const val PAINT_MARKERS: Int = 2
        val DEFAULT_PAINT_ORDER: IntArray = intArrayOf(PAINT_FILL, PAINT_STROKE, PAINT_MARKERS)
    }
}

/** The style of an element given its parent's, applying CSS inheritance and the initial values of `initial`. */
internal fun deriveStyle(parent: Style, view: PropView): Style {
    if (!view.el.setsInherited && !view.isAnimated) return parent
    val style = parent.copy()

    // Size comes first because `em` lengths in the same element are measured against it.
    val fontSize = view["font-size"]
    if (fontSize is LengthValue) {
        val size = fontSize.length.resolve(parent.fontSize, parent.fontSize)
        if (size >= 0.0) style.fontSize = size
    } else if (fontSize === InitialValue) {
        style.fontSize = 16.0
    }

    when (val color = view["color"]) {
        is PaintValue.Solid -> style.color = color.color
        InitialValue -> style.color = Rgba.BLACK
        else -> Unit
    }

    fun absolute(length: Length): Length = Length(length.px + length.em * style.fontSize, length.pct, 0.0)

    when (val fill = view["fill"]) {
        is PaintValue -> style.fill = fill
        InitialValue -> style.fill = PaintValue.Solid(Rgba.BLACK)
        else -> Unit
    }
    when (val stroke = view["stroke"]) {
        is PaintValue -> style.stroke = stroke
        InitialValue -> style.stroke = PaintValue.None
        else -> Unit
    }
    style.fillOpacity = numberOrKeep(view["fill-opacity"], style.fillOpacity, 1.0)
    style.strokeOpacity = numberOrKeep(view["stroke-opacity"], style.strokeOpacity, 1.0)
    style.miterLimit = numberOrKeep(view["stroke-miterlimit"], style.miterLimit, 4.0)
    when (val width = view["stroke-width"]) {
        is LengthValue -> style.strokeWidth = absolute(width.length)
        InitialValue -> style.strokeWidth = Length(px = 1.0)
        else -> Unit
    }
    when (val dashes = view["stroke-dasharray"]) {
        is DashValue -> style.dashArray = dashes.items.map(::absolute)
        InitialValue -> style.dashArray = emptyList()
        else -> Unit
    }
    when (val offset = view["stroke-dashoffset"]) {
        is LengthValue -> style.dashOffset = absolute(offset.length)
        InitialValue -> style.dashOffset = Length.ZERO
        else -> Unit
    }
    when (val spacing = view["letter-spacing"]) {
        is LengthValue -> style.letterSpacing = absolute(spacing.length)
        InitialValue -> style.letterSpacing = Length.ZERO
        else -> Unit
    }
    when (val spacing = view["word-spacing"]) {
        is LengthValue -> style.wordSpacing = absolute(spacing.length)
        InitialValue -> style.wordSpacing = Length.ZERO
        else -> Unit
    }
    keywordOf(view, "fill-rule")?.let { style.fillEvenOdd = it == "evenodd" }
    keywordOf(view, "clip-rule")?.let { style.clipEvenOdd = it == "evenodd" }
    when (keywordOf(view, "stroke-linecap")) {
        "butt" -> style.lineCap = Paint.Cap.BUTT
        "round" -> style.lineCap = Paint.Cap.ROUND
        "square" -> style.lineCap = Paint.Cap.SQUARE
    }
    when (keywordOf(view, "stroke-linejoin")) {
        // Android has no miter-clip or arcs join. Plain miter is the nearest look for both.
        "miter", "miter-clip", "arcs" -> style.lineJoin = Paint.Join.MITER

        "round" -> style.lineJoin = Paint.Join.ROUND

        "bevel" -> style.lineJoin = Paint.Join.BEVEL
    }
    when (val family = view["font-family"]) {
        is KeywordValue -> style.fontFamily = family.text
        InitialValue -> style.fontFamily = ""
        else -> Unit
    }
    keywordOf(view, "font-weight")?.let { style.fontWeight = computeFontWeight(it, parent.fontWeight) }
    keywordOf(view, "font-style")?.let { style.italic = it == "italic" || it.startsWith("oblique") }
    when (keywordOf(view, "text-anchor")) {
        "start" -> style.textAnchor = Style.ANCHOR_START
        "middle" -> style.textAnchor = Style.ANCHOR_MIDDLE
        "end" -> style.textAnchor = Style.ANCHOR_END
    }
    keywordOf(view, "dominant-baseline")?.let { style.baseline = it }
    keywordOf(view, "text-transform")?.let { style.textTransform = it }
    keywordOf(view, "color-interpolation")?.let { style.linearColors = it == "linearrgb" }
    val variant = keywordOf(view, "font-variant")
    val kerning = keywordOf(view, "font-kerning")
    if (variant != null || kerning != null) {
        val current = style.fontFeatures
        style.fontFeatures =
            listOfNotNull(
                when (variant) {
                    null -> "smcp".takeIf { "smcp" in current }
                    "small-caps" -> "smcp"
                    else -> null
                },
                when (kerning) {
                    null -> "\"kern\" 0".takeIf { "kern" in current }
                    "none" -> "\"kern\" 0"
                    else -> null
                },
            ).joinToString(", ")
    }
    keywordOf(view, "visibility")?.let { style.visible = it == "visible" }
    keywordOf(view, "shape-rendering")?.let { style.crispEdges = it == "crispedges" || it == "optimizespeed" }
    keywordOf(view, "image-rendering")?.let { style.pixelatedImages = it == "pixelated" || it == "crisp-edges" || it == "optimizespeed" }
    keywordOf(view, "xml:space")?.let { style.preserveSpace = it == "preserve" }
    keywordOf(view, "white-space")?.let { style.preserveSpace = it.startsWith("pre") }
    markerOf(view, "marker-start")?.let { style.markerStart = it.value }
    markerOf(view, "marker-mid")?.let { style.markerMid = it.value }
    markerOf(view, "marker-end")?.let { style.markerEnd = it.value }
    keywordOf(view, "paint-order")?.let { style.paintOrder = parsePaintOrder(it) }
    return style
}

private fun numberOrKeep(value: SvgValue?, keep: Double, initial: Double): Double =
    when (value) {
        is NumberValue -> value.value
        InitialValue -> initial
        else -> keep
    }

private fun keywordOf(view: PropView, name: String): String? {
    val value = view[name]
    if (value === InitialValue) return INITIAL_KEYWORDS[name]
    return if (value is KeywordValue) value.text else null
}

private val INITIAL_KEYWORDS =
    mapOf(
        "fill-rule" to "nonzero",
        "clip-rule" to "nonzero",
        "stroke-linecap" to "butt",
        "stroke-linejoin" to "miter",
        "font-weight" to "normal",
        "font-style" to "normal",
        "text-anchor" to "start",
        "dominant-baseline" to "auto",
        "text-transform" to "none",
        "color-interpolation" to "srgb",
        "font-variant" to "normal",
        "font-kerning" to "auto",
        "visibility" to "visible",
        "shape-rendering" to "auto",
        "image-rendering" to "auto",
        "xml:space" to "default",
        "white-space" to "normal",
        "paint-order" to "normal",
    )

private class MarkerChoice(val value: String?)

private fun markerOf(view: PropView, name: String): MarkerChoice? {
    val text = (view[name] as? KeywordValue)?.text ?: return if (view[name] === InitialValue) MarkerChoice(null) else null
    return MarkerChoice(urlReference(text))
}

private fun computeFontWeight(text: String, parent: Int): Int =
    when (text) {
        "normal" -> 400

        "bold" -> 700

        "bolder" -> if (parent < 400) {
            400
        } else if (parent < 600) {
            700
        } else {
            900
        }

        "lighter" -> if (parent < 600) {
            100
        } else if (parent < 800) {
            400
        } else {
            700
        }

        else -> text.toIntOrNull()?.coerceIn(1, 1000) ?: parent
    }

private fun parsePaintOrder(text: String): IntArray {
    if (text == "normal") return Style.DEFAULT_PAINT_ORDER
    val listed = ArrayList<Int>(3)
    for (token in text.split(' ').filter { it.isNotEmpty() }) {
        val step =
            when (token) {
                "fill" -> Style.PAINT_FILL
                "stroke" -> Style.PAINT_STROKE
                "markers" -> Style.PAINT_MARKERS
                else -> return Style.DEFAULT_PAINT_ORDER
            }
        if (step !in listed) listed += step
    }
    // Whatever is not listed follows in the usual order.
    for (step in Style.DEFAULT_PAINT_ORDER) if (step !in listed) listed += step
    return listed.toIntArray()
}
