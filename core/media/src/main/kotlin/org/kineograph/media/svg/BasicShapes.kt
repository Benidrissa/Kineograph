package org.kineograph.media.svg

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A CSS shape used as a `clip-path`, such as `circle(50%)`. It is measured against a box that is only known at drawing time. */
internal class BasicShape(val box: Int, private val build: (Box, Double) -> Path) {
    fun path(reference: Box, fontSize: Double): Path = build(reference, fontSize)

    companion object {
        const val STROKE_BOX: Int = 0
        const val FILL_BOX: Int = 1
        const val VIEW_BOX: Int = 2
    }
}

private val BOX_WORDS =
    mapOf(
        "fill-box" to BasicShape.FILL_BOX,
        "content-box" to BasicShape.FILL_BOX,
        "padding-box" to BasicShape.FILL_BOX,
        "stroke-box" to BasicShape.STROKE_BOX,
        "border-box" to BasicShape.STROKE_BOX,
        "margin-box" to BasicShape.STROKE_BOX,
        "view-box" to BasicShape.VIEW_BOX,
    )

/** Returns null for anything that is not a shape we know, such as `none` or a `url()`. */
internal fun parseBasicShape(raw: String): BasicShape? {
    var text = raw.trim()
    var box = BasicShape.STROKE_BOX
    // The box can stand on either side of the shape.
    for ((word, value) in BOX_WORDS) {
        if (text.startsWith(word, ignoreCase = true)) {
            box = value
            text = text.substring(word.length).trim()
        } else if (text.endsWith(word, ignoreCase = true)) {
            box = value
            text = text.substring(0, text.length - word.length).trim()
        }
    }
    val open = text.indexOf('(')
    if (open < 0 || !text.endsWith(")")) return null
    val inner = text.substring(open + 1, text.length - 1).trim()
    val build: ((Box, Double) -> Path)? =
        when (text.substring(0, open).trim().lowercase()) {
            "circle" -> parseCircle(inner)
            "ellipse" -> parseEllipse(inner)
            "inset" -> parseInset(inner)
            "polygon" -> parsePolygon(inner)
            "path" -> parsePathShape(inner)
            else -> null
        }
    return build?.let { BasicShape(box, it) }
}

/** `at x y`, with keywords allowed in either order. A missing side means the centre. */
private fun positionOf(tokens: List<String>): ((Box, Double) -> Pair<Double, Double>)? {
    if (tokens.isEmpty()) return { box, _ -> box.x + box.w / 2 to box.y + box.h / 2 }
    val origin = parseProperty("transform-origin", tokens.joinToString(" ")) as? LengthListValue ?: return null
    val (x, y) = origin.items
    return { box, fontSize -> box.x + x.resolve(box.w, fontSize) to box.y + y.resolve(box.h, fontSize) }
}

private fun splitAt(inner: String): Pair<List<String>, List<String>> {
    val tokens = splitList(inner)
    val at = tokens.indexOfFirst { it.equals("at", ignoreCase = true) }
    return if (at < 0) tokens to emptyList() else tokens.subList(0, at) to tokens.subList(at + 1, tokens.size)
}

private fun parseCircle(inner: String): ((Box, Double) -> Path)? {
    val (sizes, position) = splitAt(inner)
    if (sizes.size > 1) return null
    val place = positionOf(position) ?: return null
    val word = sizes.firstOrNull()?.lowercase() ?: "closest-side"
    val length = if (word == "closest-side" || word == "farthest-side") null else parseLengthOrNull(word) ?: return null
    return { box, fontSize ->
        val (cx, cy) = place(box, fontSize)
        val r =
            when {
                length != null -> length.resolve(sqrt((box.w * box.w + box.h * box.h) / 2.0), fontSize)
                word == "closest-side" -> min(min(abs(cx - box.x), abs(box.right - cx)), min(abs(cy - box.y), abs(box.bottom - cy)))
                else -> max(max(abs(cx - box.x), abs(box.right - cx)), max(abs(cy - box.y), abs(box.bottom - cy)))
            }
        Path().apply {
            if (r > 0.0) addCircle(cx.toFloat(), cy.toFloat(), r.toFloat(), Path.Direction.CW)
        }
    }
}

private fun parseEllipse(inner: String): ((Box, Double) -> Path)? {
    val (sizes, position) = splitAt(inner)
    if (sizes.size != 0 && sizes.size != 2) return null
    val place = positionOf(position) ?: return null

    fun radius(word: String?, resolve: (Double, Double, Length) -> Double): ((Double, Double) -> Double)? {
        val lower = word?.lowercase() ?: "closest-side"
        if (lower == "closest-side") return { near, far -> min(near, far) }
        if (lower == "farthest-side") return { near, far -> max(near, far) }
        val length = parseLengthOrNull(lower) ?: return null
        return { near, far -> resolve(near, far, length) }
    }
    return { box, fontSize ->
        val (cx, cy) = place(box, fontSize)
        val rxOf = radius(sizes.getOrNull(0)) { _, _, length -> length.resolve(box.w, fontSize) }
        val ryOf = radius(sizes.getOrNull(1)) { _, _, length -> length.resolve(box.h, fontSize) }
        val rx = rxOf?.invoke(abs(cx - box.x), abs(box.right - cx)) ?: 0.0
        val ry = ryOf?.invoke(abs(cy - box.y), abs(box.bottom - cy)) ?: 0.0
        Path().apply {
            if (rx > 0.0 && ry > 0.0) {
                addOval(RectF((cx - rx).toFloat(), (cy - ry).toFloat(), (cx + rx).toFloat(), (cy + ry).toFloat()), Path.Direction.CW)
            }
        }
    }
}

private fun parseInset(inner: String): ((Box, Double) -> Path)? {
    val tokens = splitList(inner)
    val roundAt = tokens.indexOfFirst { it.equals("round", ignoreCase = true) }
    val sides = (if (roundAt < 0) tokens else tokens.subList(0, roundAt)).map { parseLengthOrNull(it) ?: return null }
    if (sides.size !in 1..4) return null
    val top = sides[0]
    val right = sides.getOrElse(1) { top }
    val bottom = sides.getOrElse(2) { top }
    val left = sides.getOrElse(3) { right }
    val radius = if (roundAt < 0) null else tokens.getOrNull(roundAt + 1)?.let { parseLengthOrNull(it) ?: return null }
    return { box, fontSize ->
        val x1 = box.x + left.resolve(box.w, fontSize)
        val y1 = box.y + top.resolve(box.h, fontSize)
        val x2 = box.right - right.resolve(box.w, fontSize)
        val y2 = box.bottom - bottom.resolve(box.h, fontSize)
        Path().apply {
            if (x2 > x1 && y2 > y1) {
                val rect = RectF(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
                if (radius == null) {
                    addRect(rect, Path.Direction.CW)
                } else {
                    val rx = radius.resolve(box.w, fontSize).toFloat()
                    val ry = radius.resolve(box.h, fontSize).toFloat()
                    addRoundRect(rect, rx, ry, Path.Direction.CW)
                }
            }
        }
    }
}

private fun parsePolygon(inner: String): ((Box, Double) -> Path)? {
    val parts = splitTopLevel(inner, ',').map { it.trim() }.toMutableList()
    var evenOdd = false
    val first = parts.firstOrNull()?.lowercase()
    if (first == "evenodd" || first == "nonzero") {
        evenOdd = first == "evenodd"
        parts.removeAt(0)
    }
    val points =
        parts.map { part ->
            val pair = splitList(part)
            if (pair.size != 2) return null
            (parseLengthOrNull(pair[0]) ?: return null) to (parseLengthOrNull(pair[1]) ?: return null)
        }
    if (points.isEmpty()) return null
    return { box, fontSize ->
        Path().apply {
            fillType = if (evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            for ((index, point) in points.withIndex()) {
                val x = (box.x + point.first.resolve(box.w, fontSize)).toFloat()
                val y = (box.y + point.second.resolve(box.h, fontSize)).toFloat()
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }
}

private fun parsePathShape(inner: String): ((Box, Double) -> Path)? {
    val parts = splitTopLevel(inner, ',').map { it.trim() }
    val evenOdd = parts.size == 2 && parts[0].equals("evenodd", ignoreCase = true)
    val source = parts.last().trim('"', '\'')
    val data = parsePathData(source).data
    if (data.isEmpty) return null
    return { box, _ ->
        Path().apply {
            addPath(data.path, box.x.toFloat(), box.y.toFloat())
            fillType = if (evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        }
    }
}
