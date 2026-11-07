package org.kineograph.media.svg

import android.graphics.Matrix
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A 2D affine matrix in SVG's order, `matrix(a b c d e f)`. It uses doubles, unlike android.graphics.Matrix, so that a long chain
 * of nested transforms and animation steps does not build up visible rounding errors.
 */
internal data class Mat2D(
    val a: Double = 1.0,
    val b: Double = 0.0,
    val c: Double = 0.0,
    val d: Double = 1.0,
    val e: Double = 0.0,
    val f: Double = 0.0,
) {
    /** `this * other` applies [other] first, which is how nested SVG transforms compose. */
    operator fun times(o: Mat2D): Mat2D =
        Mat2D(
            a * o.a + c * o.b,
            b * o.a + d * o.b,
            a * o.c + c * o.d,
            b * o.c + d * o.d,
            a * o.e + c * o.f + e,
            b * o.e + d * o.f + f,
        )

    fun mapX(x: Double, y: Double): Double = a * x + c * y + e

    fun mapY(x: Double, y: Double): Double = b * x + d * y + f

    val determinant: Double get() = a * d - b * c

    val isIdentity: Boolean get() = this == IDENTITY

    fun inverse(): Mat2D? {
        val det = determinant
        if (abs(det) < 1e-300 || !det.isFinite()) return null
        return Mat2D(d / det, -b / det, -c / det, a / det, (c * f - d * e) / det, (b * e - a * f) / det)
    }

    fun toAndroid(): Matrix {
        val matrix = Matrix()
        matrix.setValues(
            floatArrayOf(a.toFloat(), c.toFloat(), e.toFloat(), b.toFloat(), d.toFloat(), f.toFloat(), 0f, 0f, 1f),
        )
        return matrix
    }

    companion object {
        val IDENTITY: Mat2D = Mat2D()

        fun translate(x: Double, y: Double): Mat2D = Mat2D(e = x, f = y)

        fun scale(x: Double, y: Double): Mat2D = Mat2D(a = x, d = y)

        fun rotate(degrees: Double): Mat2D {
            // Exact values at right angles keep rotated rectangles on the pixel grid instead of blurring their edges.
            val (s, c) = sinCosDegrees(degrees)
            return Mat2D(c, s, 0.0 - s, c)
        }

        fun skew(xDegrees: Double, yDegrees: Double): Mat2D = Mat2D(c = tanDegrees(xDegrees), b = tanDegrees(yDegrees))
    }
}

internal fun sinCosDegrees(degrees: Double): Pair<Double, Double> {
    val normalized = degrees % 360.0
    return when (if (normalized < 0) normalized + 360.0 else normalized) {
        0.0 -> {
            0.0 to 1.0
        }

        90.0 -> {
            1.0 to 0.0
        }

        180.0 -> {
            0.0 to -1.0
        }

        270.0 -> {
            -1.0 to 0.0
        }

        else -> {
            val radians = Math.toRadians(degrees)
            sin(radians) to cos(radians)
        }
    }
}

private fun tanDegrees(degrees: Double): Double = tan(Math.toRadians(degrees))

/** A rectangle in user space. */
internal data class Box(val x: Double, val y: Double, val w: Double, val h: Double) {
    val right: Double get() = x + w
    val bottom: Double get() = y + h

    fun union(other: Box): Box {
        val left = minOf(x, other.x)
        val top = minOf(y, other.y)
        return Box(left, top, maxOf(right, other.right) - left, maxOf(bottom, other.bottom) - top)
    }

    fun outset(amount: Double): Box = Box(x - amount, y - amount, w + 2 * amount, h + 2 * amount)

    fun transformed(m: Mat2D): Box {
        if (m.isIdentity) return this
        val xs = doubleArrayOf(m.mapX(x, y), m.mapX(right, y), m.mapX(x, bottom), m.mapX(right, bottom))
        val ys = doubleArrayOf(m.mapY(x, y), m.mapY(right, y), m.mapY(x, bottom), m.mapY(right, bottom))
        val left = xs.min()
        val top = ys.min()
        return Box(left, top, xs.max() - left, ys.max() - top)
    }
}

/** What percentages and font-relative lengths inside a transform are measured against. */
internal class TransformContext(val width: Double, val height: Double, val fontSize: Double)

/** One function of a transform list, kept in its original form because animations interpolate functions rather than matrices. */
internal sealed interface TransformOp {
    fun matrix(context: TransformContext): Mat2D

    data class Translate(val x: Length, val y: Length) : TransformOp {
        override fun matrix(context: TransformContext): Mat2D =
            Mat2D.translate(x.resolve(context.width, context.fontSize), y.resolve(context.height, context.fontSize))
    }

    data class Scale(val x: Double, val y: Double) : TransformOp {
        override fun matrix(context: TransformContext): Mat2D = Mat2D.scale(x, y)
    }

    data class Rotate(val degrees: Double, val cx: Double = 0.0, val cy: Double = 0.0) : TransformOp {
        override fun matrix(context: TransformContext): Mat2D {
            val rotation = Mat2D.rotate(degrees)
            if (cx == 0.0 && cy == 0.0) return rotation
            return Mat2D.translate(cx, cy) * rotation * Mat2D.translate(-cx, -cy)
        }
    }

    data class Skew(val xDegrees: Double, val yDegrees: Double) : TransformOp {
        override fun matrix(context: TransformContext): Mat2D = Mat2D.skew(xDegrees, yDegrees)
    }

    data class Raw(val m: Mat2D) : TransformOp {
        override fun matrix(context: TransformContext): Mat2D = m
    }

    /**
     * Two lists that have no function-by-function match are blended as matrices. This stays deferred until drawing because
     * percentages inside them can only be resolved then.
     */
    data class Blend(val from: List<TransformOp>, val to: List<TransformOp>, val p: Double) : TransformOp {
        override fun matrix(context: TransformContext): Mat2D =
            blendMatrices(combine(from, context), combine(to, context), p)
    }
}

internal fun combine(ops: List<TransformOp>, context: TransformContext): Mat2D {
    var result = Mat2D.IDENTITY
    for (op in ops) result *= op.matrix(context)
    return result
}

/**
 * Reads a `transform` attribute, whose numbers have no units and whose `rotate` can name a centre. It is a different language from the
 * CSS property of the same name, see [parseCssTransformList]. Mixing them up is a mistake browsers answer with no transform at all,
 * so the picture a file is known by is one without it. Returns null when the list is malformed.
 */
internal fun parseTransformList(raw: String): List<TransformOp>? {
    val text = raw.trim()
    if (text.isEmpty() || text.equals("none", ignoreCase = true)) return emptyList()
    return parseFunctions(text, commaBetween = true) { name, args -> parseAttributeFunction(name, args) }
}

/** Reads the CSS `transform` property: lengths and angles have units, arguments are split by commas, and functions by spaces only. */
internal fun parseCssTransformList(raw: String): List<TransformOp>? {
    val text = raw.trim()
    if (text.isEmpty() || text.equals("none", ignoreCase = true)) return emptyList()
    return parseFunctions(text, commaBetween = false) { name, args ->
        // Whitespace alone between arguments is the attribute's way of writing them. In CSS it joins them into one bad argument.
        val parts = splitTopLevel(args, ',').map { it.trim() }
        if (parts.any { it.isEmpty() || splitList(it).size != 1 }) null else parseCssFunction(name.lowercase(), parts)
    }
}

private inline fun parseFunctions(text: String, commaBetween: Boolean, parseOne: (String, String) -> TransformOp?): List<TransformOp>? {
    val ops = ArrayList<TransformOp>()
    var pos = 0
    while (pos < text.length) {
        while (pos < text.length && (isSvgSpace(text[pos]) || (commaBetween && text[pos] == ','))) pos++
        if (pos >= text.length) break
        val open = text.indexOf('(', pos)
        if (open < 0) return null
        val close = text.indexOf(')', open)
        if (close < 0) return null
        ops += parseOne(text.substring(pos, open).trim(), text.substring(open + 1, close)) ?: return null
        pos = close + 1
    }
    return ops
}

private fun parseAttributeFunction(name: String, text: String): TransformOp? {
    val args = parseNumberList(text) ?: return null
    return when (name) {
        "translate" -> {
            when (args.size) {
                1 -> TransformOp.Translate(Length(px = args[0]), Length.ZERO)
                2 -> TransformOp.Translate(Length(px = args[0]), Length(px = args[1]))
                else -> null
            }
        }

        "scale" -> {
            when (args.size) {
                1 -> TransformOp.Scale(args[0], args[0])
                2 -> TransformOp.Scale(args[0], args[1])
                else -> null
            }
        }

        "rotate" -> {
            when (args.size) {
                1 -> TransformOp.Rotate(args[0])
                3 -> TransformOp.Rotate(args[0], args[1], args[2])
                else -> null
            }
        }

        "skewX" -> {
            if (args.size == 1) TransformOp.Skew(args[0], 0.0) else null
        }

        "skewY" -> {
            if (args.size == 1) TransformOp.Skew(0.0, args[0]) else null
        }

        "matrix" -> {
            if (args.size == 6) TransformOp.Raw(Mat2D(args[0], args[1], args[2], args[3], args[4], args[5])) else null
        }

        else -> {
            null
        }
    }
}

/** A bare number is only a length or an angle in CSS when it is zero. */
private fun cssLength(text: String?): Length? = text?.takeIf { hasCssUnit(it) }?.let(::parseLengthOrNull)

private fun cssAngle(text: String?): Double? = text?.takeIf { hasCssUnit(it) }?.let(::parseAngle)

private fun parseCssFunction(name: String, args: List<String>): TransformOp? {
    fun number(i: Int): Double? = args.getOrNull(i)?.let(::parseFraction)

    fun length(i: Int): Length? = cssLength(args.getOrNull(i))

    fun angle(i: Int): Double? = cssAngle(args.getOrNull(i))
    return when (name) {
        "translate" -> {
            val x = length(0) ?: return null
            TransformOp.Translate(x, if (args.size > 1) length(1) ?: return null else Length.ZERO)
        }

        "translatex" -> {
            TransformOp.Translate(length(0) ?: return null, Length.ZERO)
        }

        "translatey" -> {
            TransformOp.Translate(Length.ZERO, length(0) ?: return null)
        }

        // Without a 3D scene the z part has no effect on the picture, so only x and y are kept.
        "translate3d" -> {
            TransformOp.Translate(length(0) ?: return null, length(1) ?: return null)
        }

        "translatez", "perspective" -> {
            TransformOp.Translate(Length.ZERO, Length.ZERO)
        }

        "scale", "scale3d" -> {
            val x = number(0) ?: return null
            TransformOp.Scale(x, if (args.size > 1) number(1) ?: return null else x)
        }

        "scalex" -> {
            TransformOp.Scale(number(0) ?: return null, 1.0)
        }

        "scaley" -> {
            TransformOp.Scale(1.0, number(0) ?: return null)
        }

        "scalez" -> {
            TransformOp.Scale(1.0, 1.0)
        }

        "rotate", "rotatez" -> {
            if (args.size != 1) return null
            TransformOp.Rotate(angle(0) ?: return null)
        }

        // Seen straight on, without perspective, a turn around x or y only squashes the picture along the other axis.
        "rotatex" -> {
            TransformOp.Scale(1.0, cos(Math.toRadians(angle(0) ?: return null)))
        }

        "rotatey" -> {
            TransformOp.Scale(cos(Math.toRadians(angle(0) ?: return null)), 1.0)
        }

        "rotate3d" -> {
            parseRotate3d(args)
        }

        "skew" -> {
            val x = angle(0) ?: return null
            TransformOp.Skew(x, if (args.size > 1) angle(1) ?: return null else 0.0)
        }

        "skewx" -> {
            TransformOp.Skew(angle(0) ?: return null, 0.0)
        }

        "skewy" -> {
            TransformOp.Skew(0.0, angle(0) ?: return null)
        }

        "matrix" -> {
            val v = args.map { parseNumberOrNull(it) ?: return null }
            if (v.size != 6) return null
            TransformOp.Raw(Mat2D(v[0], v[1], v[2], v[3], v[4], v[5]))
        }

        "matrix3d" -> {
            val v = args.map { parseNumberOrNull(it) ?: return null }
            if (v.size != 16) return null
            TransformOp.Raw(Mat2D(v[0], v[1], v[4], v[5], v[12], v[13]))
        }

        else -> {
            null
        }
    }
}

private fun parseRotate3d(args: List<String>): TransformOp? {
    if (args.size != 4) return null
    val x = parseNumberOrNull(args[0]) ?: return null
    val y = parseNumberOrNull(args[1]) ?: return null
    val z = parseNumberOrNull(args[2]) ?: return null
    val angle = cssAngle(args[3]) ?: return null
    return when {
        x == 0.0 && y == 0.0 -> TransformOp.Rotate(if (z < 0) -angle else angle)
        x == 0.0 && z == 0.0 -> TransformOp.Scale(cos(Math.toRadians(angle)), 1.0)
        y == 0.0 && z == 0.0 -> TransformOp.Scale(1.0, cos(Math.toRadians(angle)))
        else -> null
    }
}

/** A transform that does nothing, shaped like [op], so that `none` can be interpolated with a real list. */
private fun identityLike(op: TransformOp): TransformOp =
    when (op) {
        is TransformOp.Translate -> TransformOp.Translate(Length.ZERO, Length.ZERO)
        is TransformOp.Scale -> TransformOp.Scale(1.0, 1.0)
        is TransformOp.Rotate -> TransformOp.Rotate(0.0, op.cx, op.cy)
        is TransformOp.Skew -> TransformOp.Skew(0.0, 0.0)
        is TransformOp.Raw -> TransformOp.Raw(Mat2D.IDENTITY)
        is TransformOp.Blend -> TransformOp.Raw(Mat2D.IDENTITY)
    }

/** The zero point for numeric addition, which `by` and `accumulate` count from. It differs from identity only for scale. */
internal fun zeroLike(op: TransformOp): TransformOp =
    when (op) {
        is TransformOp.Scale -> TransformOp.Scale(0.0, 0.0)
        else -> identityLike(op)
    }

/**
 * Interpolates two transform lists. As far as their functions match, they go function by function, with a list that is too short
 * padded out by functions that do nothing. From the first pair that does not match, what is left of each is blended as matrices.
 */
internal fun interpolateTransforms(a: List<TransformOp>, b: List<TransformOp>, p: Double): List<TransformOp> {
    if (a.isEmpty() && b.isEmpty()) return a
    val size = maxOf(a.size, b.size)
    val from = if (a.size == size) a else a + b.subList(a.size, size).map(::identityLike)
    val to = if (b.size == size) b else b + a.subList(b.size, size).map(::identityLike)
    val merged = ArrayList<TransformOp>(size)
    for (i in 0 until size) {
        val op = interpolateOp(from[i], to[i], p)
        if (op == null) {
            merged += TransformOp.Blend(from.subList(i, size), to.subList(i, size), p)
            return merged
        }
        merged += op
    }
    return merged
}

private fun interpolateOp(a: TransformOp, b: TransformOp, p: Double): TransformOp? {
    fun mix(x: Double, y: Double): Double = x + (y - x) * p
    return when {
        a is TransformOp.Translate && b is TransformOp.Translate -> {
            TransformOp.Translate(a.x + (b.x - a.x) * p, a.y + (b.y - a.y) * p)
        }

        a is TransformOp.Scale && b is TransformOp.Scale -> {
            TransformOp.Scale(mix(a.x, b.x), mix(a.y, b.y))
        }

        a is TransformOp.Rotate && b is TransformOp.Rotate -> {
            TransformOp.Rotate(mix(a.degrees, b.degrees), mix(a.cx, b.cx), mix(a.cy, b.cy))
        }

        a is TransformOp.Skew && b is TransformOp.Skew -> {
            TransformOp.Skew(mix(a.xDegrees, b.xDegrees), mix(a.yDegrees, b.yDegrees))
        }

        a is TransformOp.Raw && b is TransformOp.Raw -> {
            TransformOp.Blend(listOf(a), listOf(b), p)
        }

        else -> {
            null
        }
    }
}

/** Numeric sum of two lists of the same shape, which is what `accumulate="sum"` means for animateTransform. */
internal fun addTransformsNumerically(a: List<TransformOp>, b: List<TransformOp>): List<TransformOp>? {
    if (a.size != b.size) return null
    val out = ArrayList<TransformOp>(a.size)
    for (i in a.indices) {
        val x = a[i]
        val y = b[i]
        out +=
            when {
                x is TransformOp.Translate && y is TransformOp.Translate -> TransformOp.Translate(x.x + y.x, x.y + y.y)
                x is TransformOp.Scale && y is TransformOp.Scale -> TransformOp.Scale(x.x + y.x, x.y + y.y)
                x is TransformOp.Rotate && y is TransformOp.Rotate -> TransformOp.Rotate(x.degrees + y.degrees, x.cx + y.cx, x.cy + y.cy)
                x is TransformOp.Skew && y is TransformOp.Skew -> TransformOp.Skew(x.xDegrees + y.xDegrees, x.yDegrees + y.yDegrees)
                else -> return null
            }
    }
    return out
}

internal fun scaleTransformsNumerically(a: List<TransformOp>, k: Double): List<TransformOp>? {
    val out = ArrayList<TransformOp>(a.size)
    for (op in a) {
        out +=
            when (op) {
                is TransformOp.Translate -> TransformOp.Translate(op.x * k, op.y * k)
                is TransformOp.Scale -> TransformOp.Scale(op.x * k, op.y * k)
                is TransformOp.Rotate -> TransformOp.Rotate(op.degrees * k, op.cx * k, op.cy * k)
                is TransformOp.Skew -> TransformOp.Skew(op.xDegrees * k, op.yDegrees * k)
                else -> return null
            }
    }
    return out
}

/**
 * A flat matrix taken apart into translate, rotate, skew and scale. Blending the parts rather than the entries keeps a shape from
 * collapsing through a point half way through a rotation. The rotation is kept as half-angle sine and cosine, a flat quaternion,
 * since the direction a half turn goes in comes from how those are blended.
 */
private class Decomposed(
    val tx: Double,
    val ty: Double,
    val sx: Double,
    val sy: Double,
    val skew: Double,
    val quaternionZ: Double,
    val quaternionW: Double,
)

/** Null for a matrix with no area left to it, which no angle or scale can describe. */
private fun decompose(m: Mat2D): Decomposed? {
    val determinant = m.determinant
    if (determinant == 0.0 || !determinant.isFinite()) return null
    var m11 = m.a
    var m21 = m.b
    var m12 = m.c
    var m22 = m.d
    // A mirror image has to flip one scale. Flipping both would be the same as turning by 180 degrees.
    var sx = 1.0
    var sy = 1.0
    if (determinant < 0.0) {
        if (m11 < m22) sx = -1.0 else sy = -1.0
    }
    sx *= hypot(m11, m21)
    m11 /= sx
    m21 /= sx
    val shear = m11 * m12 + m21 * m22
    m12 -= m11 * shear
    m22 -= m21 * shear
    sy *= hypot(m12, m22)
    val angle = atan2(m21, m11)
    return Decomposed(m.e, m.f, sx, sy, shear / sy, sin(angle / 2.0), cos(angle / 2.0))
}

/** How a browser blends two matrices that have no function-by-function match. A matrix that cannot be taken apart flips half way. */
internal fun blendMatrices(a: Mat2D, b: Mat2D, p: Double): Mat2D {
    val from = decompose(a)
    val to = decompose(b)
    if (from == null || to == null) return if (p < 0.5) a else b

    fun mix(x: Double, y: Double): Double = x + (y - x) * p
    var fromZ = from.quaternionZ
    var fromW = from.quaternionW
    var dot = fromZ * to.quaternionZ + fromW * to.quaternionW
    // The shorter way round.
    if (dot < 0.0) {
        dot = -dot
        fromZ = -fromZ
        fromW = -fromW
    }
    dot = dot.coerceAtMost(1.0)
    val z: Double
    val w: Double
    if (dot > 0.9995) {
        z = mix(fromZ, to.quaternionZ)
        w = mix(fromW, to.quaternionW)
    } else {
        val theta = acos(dot)
        val toShare = sin(p * theta) / sqrt(1.0 - dot * dot)
        val fromShare = cos(p * theta) - dot * toShare
        z = fromZ * fromShare + to.quaternionZ * toShare
        w = fromW * fromShare + to.quaternionW * toShare
    }
    val degrees = Math.toDegrees(2.0 * atan2(z, w))
    return Mat2D.translate(mix(from.tx, to.tx), mix(from.ty, to.ty)) *
        Mat2D.rotate(degrees) *
        Mat2D(c = mix(from.skew, to.skew)) *
        Mat2D.scale(mix(from.sx, to.sx), mix(from.sy, to.sy))
}
