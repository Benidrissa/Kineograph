package org.kineograph.media.svg

import android.graphics.Matrix
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
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
 * Reads both SVG transform attributes, `rotate(45 10 10)`, and CSS transforms, `rotate(45deg) translate(10px, 5%)`, with one
 * parser. Files mix the two forms freely, so being strict about which is which would only break pictures. Returns null when
 * the list is malformed, which the caller treats as "no transform", as browsers do.
 */
internal fun parseTransformList(raw: String): List<TransformOp>? {
    val text = raw.trim()
    if (text.isEmpty() || text.equals("none", ignoreCase = true)) return emptyList()
    val ops = ArrayList<TransformOp>()
    var pos = 0
    while (pos < text.length) {
        while (pos < text.length && (isSvgSpace(text[pos]) || text[pos] == ',')) pos++
        if (pos >= text.length) break
        val open = text.indexOf('(', pos)
        if (open < 0) return null
        val close = text.indexOf(')', open)
        if (close < 0) return null
        val name = text.substring(pos, open).trim().lowercase()
        val op = parseTransformFunction(name, splitList(text.substring(open + 1, close))) ?: return null
        ops += op
        pos = close + 1
    }
    return ops
}

private fun parseTransformFunction(name: String, args: List<String>): TransformOp? {
    fun number(i: Int): Double? = args.getOrNull(i)?.let(::parseFraction)

    fun length(i: Int): Length? = args.getOrNull(i)?.let(::parseLengthOrNull)

    fun angle(i: Int): Double? = args.getOrNull(i)?.let(::parseAngle)
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
            val a = angle(0) ?: return null
            if (args.size >= 3) {
                TransformOp.Rotate(a, parseNumberOrNull(args[1]) ?: return null, parseNumberOrNull(args[2]) ?: return null)
            } else {
                TransformOp.Rotate(a)
            }
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
    val angle = parseAngle(args[3]) ?: return null
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

/** Interpolates two transform lists: function by function when they match, as blended matrices when they do not. */
internal fun interpolateTransforms(a: List<TransformOp>, b: List<TransformOp>, p: Double): List<TransformOp> {
    if (a.isEmpty() && b.isEmpty()) return a
    val from = if (a.isEmpty()) b.map(::identityLike) else a
    val to = if (b.isEmpty()) a.map(::identityLike) else b
    if (from.size == to.size) {
        val merged = ArrayList<TransformOp>(from.size)
        for (i in from.indices) merged += interpolateOp(from[i], to[i], p) ?: return listOf(TransformOp.Blend(from, to, p))
        return merged
    }
    return listOf(TransformOp.Blend(from, to, p))
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

private class Decomposed(
    val tx: Double,
    val ty: Double,
    val sx: Double,
    val sy: Double,
    val angle: Double,
    val m11: Double,
    val m12: Double,
    val m21: Double,
    val m22: Double,
)

/**
 * The matrix decomposition from the CSS Transforms specification: blending the parts rather than the entries keeps a shape from
 * collapsing through a point half way through a rotation.
 */
private fun decompose(m: Mat2D): Decomposed {
    var row0x = m.a
    var row0y = m.b
    var row1x = m.c
    var row1y = m.d
    var sx = hypot(row0x, row0y)
    var sy = hypot(row1x, row1y)
    if (row0x * row1y - row0y * row1x < 0) {
        if (row0x < row1y) sx = -sx else sy = -sy
    }
    if (sx != 0.0) {
        row0x /= sx
        row0y /= sx
    }
    if (sy != 0.0) {
        row1x /= sy
        row1y /= sy
    }
    val angleRadians = atan2(row0y, row0x)
    if (angleRadians != 0.0) {
        val sn = -row0y
        val cs = row0x
        val m11 = row0x
        val m12 = row0y
        val m21 = row1x
        val m22 = row1y
        row0x = cs * m11 + sn * m21
        row0y = cs * m12 + sn * m22
        row1x = -sn * m11 + cs * m21
        row1y = -sn * m12 + cs * m22
    }
    return Decomposed(m.e, m.f, sx, sy, Math.toDegrees(angleRadians), row0x, row0y, row1x, row1y)
}

internal fun blendMatrices(a: Mat2D, b: Mat2D, p: Double): Mat2D {
    val da = decompose(a)
    val db = decompose(b)
    var sxA = da.sx
    var syA = da.sy
    var angleA = da.angle
    var angleB = db.angle
    if ((sxA < 0 && db.sy < 0) || (syA < 0 && db.sx < 0)) {
        sxA = -sxA
        syA = -syA
        angleA += if (angleA < 0) 180.0 else -180.0
    }
    if (angleA == 0.0) angleA = 360.0
    if (angleB == 0.0) angleB = 360.0
    if (abs(angleA - angleB) > 180.0) {
        if (angleA > angleB) angleA -= 360.0 else angleB -= 360.0
    }

    fun mix(x: Double, y: Double): Double = x + (y - x) * p
    val inner = Mat2D(mix(da.m11, db.m11), mix(da.m12, db.m12), mix(da.m21, db.m21), mix(da.m22, db.m22))
    // The decomposition took the scale off first and the rotation second, so they go back on in the reverse order.
    return Mat2D.translate(mix(da.tx, db.tx), mix(da.ty, db.ty)) *
        inner *
        Mat2D.rotate(mix(angleA, angleB)) *
        Mat2D.scale(mix(sxA, db.sx), mix(syA, db.sy))
}
