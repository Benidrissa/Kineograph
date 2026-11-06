package org.kineograph.engine.selection

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A 2D affine transform in pure Kotlin:
 * ```
 * x' = a·x + c·y + e
 * y' = b·x + d·y + f
 * ```
 * It exists so that view and selection maths can be unit-tested without Android's Matrix.
 */
public data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
    public fun mapX(x: Float, y: Float): Float = a * x + c * y + e

    public fun mapY(x: Float, y: Float): Float = b * x + d * y + f

    /** The transform that applies [other] first and this one second. */
    public fun then(other: Affine): Affine = other.compose(this)

    /** The transform that applies [inner] first and then this one. */
    public fun compose(inner: Affine): Affine =
        Affine(
            a = a * inner.a + c * inner.b,
            b = b * inner.a + d * inner.b,
            c = a * inner.c + c * inner.d,
            d = b * inner.c + d * inner.d,
            e = a * inner.e + c * inner.f + e,
            f = b * inner.e + d * inner.f + f,
        )

    /** The reverse transform, or null if this one has collapsed everything onto a line or a point. */
    public fun inverse(): Affine? {
        val determinant = a * d - b * c
        if (abs(determinant) < 1e-9f) return null
        val invDet = 1f / determinant
        return Affine(
            a = d * invDet,
            b = -b * invDet,
            c = -c * invDet,
            d = a * invDet,
            e = (c * f - d * e) * invDet,
            f = (b * e - a * f) * invDet,
        )
    }

    /** Writes this transform in the 9-value layout of android.graphics.Matrix.setValues. */
    public fun toMatrixValues(out: FloatArray) {
        out[0] = a
        out[1] = c
        out[2] = e
        out[3] = b
        out[4] = d
        out[5] = f
        out[6] = 0f
        out[7] = 0f
        out[8] = 1f
    }

    public companion object {
        public val IDENTITY: Affine = Affine(1f, 0f, 0f, 1f, 0f, 0f)

        public fun translation(x: Float, y: Float): Affine = Affine(1f, 0f, 0f, 1f, x, y)

        public fun scale(x: Float, y: Float): Affine = Affine(x, 0f, 0f, y, 0f, 0f)

        public fun rotation(radians: Float): Affine {
            val cos = cos(radians)
            val sin = sin(radians)
            return Affine(cos, sin, -sin, cos, 0f, 0f)
        }
    }
}
