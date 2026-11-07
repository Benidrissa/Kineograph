package org.kineograph.media.svg

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A CSS shape used as a `clip-path`, such as `circle(50%)`. It is measured against a box that is only known at drawing time. It holds what the text
 * says instead of a recipe for a path, which is what makes it possible to animate one: `inset(0 100% 0 0)` to `inset(0)` is how files wipe a shape in.
 */
internal class BasicShape(val box: Int, private val spec: ShapeSpec) {
    val segments: Int get() = spec.segments

    fun path(reference: Box, fontSize: Double): Path = spec.path(reference, fontSize)

    /** Null when the two have no in-between, and then a browser flips half way. */
    fun interpolate(other: BasicShape, p: Double): BasicShape? {
        if (box != other.box) return null
        return spec.interpolate(other.spec, p)?.let { BasicShape(box, it) }
    }

    companion object {
        const val STROKE_BOX: Int = 0
        const val FILL_BOX: Int = 1
        const val VIEW_BOX: Int = 2
    }
}

/** A word instead of a length, for how far a circle or an ellipse reaches. */
internal enum class SideWord { CLOSEST, FARTHEST }

/** A radius is one or the other. */
internal class ShapeRadius(val length: Length?, val word: SideWord?) {
    fun interpolate(other: ShapeRadius, p: Double): ShapeRadius? {
        if (length != null && other.length != null) return ShapeRadius(length + (other.length - length) * p, null)
        return if (word != null && word == other.word) this else null
    }
}

internal sealed interface ShapeSpec {
    val segments: Int

    fun path(reference: Box, fontSize: Double): Path

    fun interpolate(other: ShapeSpec, p: Double): ShapeSpec?

    class Circle(val radius: ShapeRadius, val x: Length, val y: Length) : ShapeSpec {
        override val segments: Int get() = SIMPLE_SHAPE_SEGMENTS

        override fun path(reference: Box, fontSize: Double): Path {
            val cx = reference.x + x.resolve(reference.w, fontSize)
            val cy = reference.y + y.resolve(reference.h, fontSize)
            val toLeft = abs(cx - reference.x)
            val toRight = abs(reference.right - cx)
            val toTop = abs(cy - reference.y)
            val toBottom = abs(reference.bottom - cy)
            val r =
                when {
                    radius.length != null -> radius.length.resolve(
                        sqrt((reference.w * reference.w + reference.h * reference.h) / 2.0),
                        fontSize,
                    )

                    radius.word == SideWord.CLOSEST -> min(min(toLeft, toRight), min(toTop, toBottom))

                    else -> max(max(toLeft, toRight), max(toTop, toBottom))
                }
            return Path().apply {
                if (r > 0.0) addCircle(cx.toFloat(), cy.toFloat(), r.toFloat(), Path.Direction.CW)
            }
        }

        override fun interpolate(other: ShapeSpec, p: Double): ShapeSpec? {
            if (other !is Circle) return null
            val r = radius.interpolate(other.radius, p) ?: return null
            return Circle(r, x + (other.x - x) * p, y + (other.y - y) * p)
        }
    }

    class Ellipse(val rx: ShapeRadius, val ry: ShapeRadius, val x: Length, val y: Length) : ShapeSpec {
        override val segments: Int get() = SIMPLE_SHAPE_SEGMENTS

        override fun path(reference: Box, fontSize: Double): Path {
            val cx = reference.x + x.resolve(reference.w, fontSize)
            val cy = reference.y + y.resolve(reference.h, fontSize)

            fun reach(radius: ShapeRadius, size: Double, near: Double, far: Double): Double =
                when {
                    radius.length != null -> radius.length.resolve(size, fontSize)
                    radius.word == SideWord.CLOSEST -> min(near, far)
                    else -> max(near, far)
                }
            val a = reach(rx, reference.w, abs(cx - reference.x), abs(reference.right - cx))
            val b = reach(ry, reference.h, abs(cy - reference.y), abs(reference.bottom - cy))
            return Path().apply {
                if (a > 0.0 && b > 0.0) {
                    addOval(RectF((cx - a).toFloat(), (cy - b).toFloat(), (cx + a).toFloat(), (cy + b).toFloat()), Path.Direction.CW)
                }
            }
        }

        override fun interpolate(other: ShapeSpec, p: Double): ShapeSpec? {
            if (other !is Ellipse) return null
            val a = rx.interpolate(other.rx, p) ?: return null
            val b = ry.interpolate(other.ry, p) ?: return null
            return Ellipse(a, b, x + (other.x - x) * p, y + (other.y - y) * p)
        }
    }

    class Inset(val top: Length, val right: Length, val bottom: Length, val left: Length, val round: Length?) : ShapeSpec {
        override val segments: Int get() = SIMPLE_SHAPE_SEGMENTS

        override fun path(reference: Box, fontSize: Double): Path {
            val x1 = reference.x + left.resolve(reference.w, fontSize)
            val y1 = reference.y + top.resolve(reference.h, fontSize)
            val x2 = reference.right - right.resolve(reference.w, fontSize)
            val y2 = reference.bottom - bottom.resolve(reference.h, fontSize)
            return Path().apply {
                if (x2 > x1 && y2 > y1) {
                    val rect = RectF(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
                    if (round == null) {
                        addRect(rect, Path.Direction.CW)
                    } else {
                        addRoundRect(
                            rect,
                            round.resolve(reference.w, fontSize).toFloat(),
                            round.resolve(reference.h, fontSize).toFloat(),
                            Path.Direction.CW,
                        )
                    }
                }
            }
        }

        override fun interpolate(other: ShapeSpec, p: Double): ShapeSpec? {
            if (other !is Inset) return null

            fun mix(a: Length, b: Length): Length = a + (b - a) * p
            val mixedRound =
                if (round == null && other.round == null) null else mix(round ?: Length.ZERO, other.round ?: Length.ZERO)
            return Inset(mix(top, other.top), mix(right, other.right), mix(bottom, other.bottom), mix(left, other.left), mixedRound)
        }
    }

    class Polygon(val points: List<Pair<Length, Length>>, val evenOdd: Boolean) : ShapeSpec {
        override val segments: Int get() = points.size

        override fun path(reference: Box, fontSize: Double): Path =
            Path().apply {
                fillType = if (evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
                for ((index, point) in points.withIndex()) {
                    val x = (reference.x + point.first.resolve(reference.w, fontSize)).toFloat()
                    val y = (reference.y + point.second.resolve(reference.h, fontSize)).toFloat()
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }

        override fun interpolate(other: ShapeSpec, p: Double): ShapeSpec? {
            if (other !is Polygon || other.points.size != points.size || other.evenOdd != evenOdd) return null
            val mixed =
                List(points.size) { i ->
                    val a = points[i]
                    val b = other.points[i]
                    (a.first + (b.first - a.first) * p) to (a.second + (b.second - a.second) * p)
                }
            return Polygon(mixed, evenOdd)
        }
    }

    class PathShape(val data: PathData, val evenOdd: Boolean) : ShapeSpec {
        override val segments: Int get() = data.commands.size

        override fun path(reference: Box, fontSize: Double): Path =
            Path().apply {
                addPath(data.path, reference.x.toFloat(), reference.y.toFloat())
                fillType = if (evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            }

        override fun interpolate(other: ShapeSpec, p: Double): ShapeSpec? {
            if (other !is PathShape || other.evenOdd != evenOdd) return null
            return data.interpolate(other.data, p)?.let { PathShape(it, evenOdd) }
        }
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

/**
 * Every clip shape of one file, each read once however many elements and animations name it. A rule like `rect{animation:k}` asks for the same text from thousands of
 * tracks, and one long shape takes a long time to read and a lot of memory to keep. What has been read stays for as long as the picture, so reading is paid for, by whoever
 * got there first.
 */
internal class BasicShapeTable {
    private val shapes = HashMap<String, BasicShape?>()

    /** The shape this text means, or null if it means none, or if there is no longer the time or the memory for it. */
    fun read(text: String, work: WorkMeter): BasicShape? {
        if (shapes.containsKey(text)) return shapes[text]
        if (!HeapWatch.hasRoomForText(text.length, BYTES_PER_SHAPE_CHAR) || !work.spend(text.length * WorkCost.SHAPE_CHAR_READ)) return null
        return parseBasicShape(text).also { shapes[text] = it }
    }

    /** Only asks what is already known, for code that has no allowance to pay from. */
    fun known(text: String): BasicShape? = shapes[text]
}

/** A polygon's point can be as short as four characters, and comes to a pair of lengths. */
private const val BYTES_PER_SHAPE_CHAR = 30

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
    val spec =
        when (text.substring(0, open).trim().lowercase()) {
            "circle" -> parseCircle(inner)
            "ellipse" -> parseEllipse(inner)
            "inset" -> parseInset(inner)
            "polygon" -> parsePolygon(inner)
            "path" -> parsePathShape(inner)
            else -> null
        } ?: return null
    return BasicShape(box, spec)
}

private const val SIMPLE_SHAPE_SEGMENTS = 8

/** `at x y`, with keywords allowed in either order. A missing side means the centre. */
private fun positionOf(tokens: List<String>): Pair<Length, Length>? {
    if (tokens.isEmpty()) return Length(pct = 50.0) to Length(pct = 50.0)
    val origin = parseProperty("transform-origin", tokens.joinToString(" ")) as? LengthListValue ?: return null
    return origin.items[0] to origin.items[1]
}

private fun splitAt(inner: String): Pair<List<String>, List<String>> {
    val tokens = splitList(inner)
    val at = tokens.indexOfFirst { it.equals("at", ignoreCase = true) }
    return if (at < 0) tokens to emptyList() else tokens.subList(0, at) to tokens.subList(at + 1, tokens.size)
}

private fun radiusOf(token: String?): ShapeRadius? =
    when (token?.lowercase()) {
        null, "closest-side" -> ShapeRadius(null, SideWord.CLOSEST)
        "farthest-side" -> ShapeRadius(null, SideWord.FARTHEST)
        else -> parseLengthOrNull(token)?.let { ShapeRadius(it, null) }
    }

private fun parseCircle(inner: String): ShapeSpec? {
    val (sizes, position) = splitAt(inner)
    if (sizes.size > 1) return null
    val (x, y) = positionOf(position) ?: return null
    return ShapeSpec.Circle(radiusOf(sizes.firstOrNull()) ?: return null, x, y)
}

private fun parseEllipse(inner: String): ShapeSpec? {
    val (sizes, position) = splitAt(inner)
    if (sizes.size != 0 && sizes.size != 2) return null
    val (x, y) = positionOf(position) ?: return null
    return ShapeSpec.Ellipse(radiusOf(sizes.getOrNull(0)) ?: return null, radiusOf(sizes.getOrNull(1)) ?: return null, x, y)
}

private fun parseInset(inner: String): ShapeSpec? {
    val tokens = splitList(inner)
    val roundAt = tokens.indexOfFirst { it.equals("round", ignoreCase = true) }
    val sides = (if (roundAt < 0) tokens else tokens.subList(0, roundAt)).map { parseLengthOrNull(it) ?: return null }
    if (sides.size !in 1..4) return null
    val top = sides[0]
    val right = sides.getOrElse(1) { top }
    val bottom = sides.getOrElse(2) { top }
    val left = sides.getOrElse(3) { right }
    val round = if (roundAt < 0) null else tokens.getOrNull(roundAt + 1)?.let { parseLengthOrNull(it) ?: return null }
    return ShapeSpec.Inset(top, right, bottom, left, round)
}

private fun parsePolygon(inner: String): ShapeSpec? {
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
    return ShapeSpec.Polygon(points, evenOdd)
}

private fun parsePathShape(inner: String): ShapeSpec? {
    val parts = splitTopLevel(inner, ',').map { it.trim() }
    val evenOdd = parts.size == 2 && parts[0].equals("evenodd", ignoreCase = true)
    val source = parts.last().trim('"', '\'')
    val data = parsePathData(source).data
    if (data.isEmpty) return null
    return ShapeSpec.PathShape(data, evenOdd)
}
