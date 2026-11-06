package org.kineograph.engine.selection

import org.kineograph.model.IntRect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** A part of the selection box the user can grab. */
public enum class SelectionHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT, ROTATE, MOVE }

/**
 * Where a lifted selection has been moved to. Scale and rotation happen about the centre of
 * [source], the rectangle the pixels were lifted from, and then [translateX] and [translateY] apply.
 */
public data class SelectionTransform(
    val source: IntRect,
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
) {
    private val centerX: Float get() = (source.left + source.right) * 0.5f
    private val centerY: Float get() = (source.top + source.bottom) * 0.5f

    public val isIdentity: Boolean
        get() = translateX == 0f && translateY == 0f && scale == 1f && rotation == 0f

    /** Maps source pixel coordinates to canvas coordinates. */
    public fun toAffine(): Affine =
        Affine
            .translation(centerX + translateX, centerY + translateY)
            .compose(Affine.rotation(rotation))
            .compose(Affine.scale(scale, scale))
            .compose(Affine.translation(-centerX, -centerY))

    /** The four corners on the canvas, clockwise from the top left: x0, y0, x1, y1, and so on. */
    public fun corners(): FloatArray {
        val m = toAffine()
        val l = source.left.toFloat()
        val t = source.top.toFloat()
        val r = source.right.toFloat()
        val b = source.bottom.toFloat()
        return floatArrayOf(
            m.mapX(l, t),
            m.mapY(l, t),
            m.mapX(r, t),
            m.mapY(r, t),
            m.mapX(r, b),
            m.mapY(r, b),
            m.mapX(l, b),
            m.mapY(l, b),
        )
    }

    /** Position of the rotate knob, which sits [offset] canvas pixels above the middle of the top edge. */
    public fun rotateKnob(offset: Float): Pair<Float, Float> {
        val m = toAffine()
        // The offset must not change with the selection's own scale.
        val midTopY = source.top.toFloat() - offset / maxOf(scale, MIN_SCALE)
        return m.mapX(centerX, midTopY) to m.mapY(centerX, midTopY)
    }

    /** What is under the point (canvas coordinates), trying the small handles before the body. */
    public fun hitTest(x: Float, y: Float, touchRadius: Float, knobOffset: Float): SelectionHandle? {
        val knob = rotateKnob(knobOffset)
        if (hypot(x - knob.first, y - knob.second) <= touchRadius) return SelectionHandle.ROTATE
        val c = corners()
        val handles =
            arrayOf(SelectionHandle.TOP_LEFT, SelectionHandle.TOP_RIGHT, SelectionHandle.BOTTOM_RIGHT, SelectionHandle.BOTTOM_LEFT)
        for (i in handles.indices) {
            if (hypot(x - c[i * 2], y - c[i * 2 + 1]) <= touchRadius) return handles[i]
        }
        val inverse = toAffine().inverse() ?: return null
        val sx = inverse.mapX(x, y)
        val sy = inverse.mapY(x, y)
        return if (sx >= source.left && sx <= source.right && sy >= source.top && sy <= source.bottom) SelectionHandle.MOVE else null
    }

    /** The transform after dragging [handle] from one canvas point to another. */
    public fun dragged(handle: SelectionHandle, fromX: Float, fromY: Float, toX: Float, toY: Float): SelectionTransform {
        val pivotX = centerX + translateX
        val pivotY = centerY + translateY
        return when (handle) {
            SelectionHandle.MOVE -> {
                copy(translateX = translateX + toX - fromX, translateY = translateY + toY - fromY)
            }

            SelectionHandle.ROTATE -> {
                val before = atan2(fromY - pivotY, fromX - pivotX)
                val after = atan2(toY - pivotY, toX - pivotX)
                copy(rotation = normalizeAngle(rotation + after - before))
            }

            else -> {
                val before = hypot(fromX - pivotX, fromY - pivotY)
                val after = hypot(toX - pivotX, toY - pivotY)
                if (before < 1f) this else copy(scale = (scale * after / before).coerceIn(MIN_SCALE, MAX_SCALE))
            }
        }
    }

    /** The whole-pixel bounding box of the transformed selection, grown slightly for filtered edges. */
    public fun bounds(): IntRect {
        val c = corners()
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until 4) {
            minX = minOf(minX, c[i * 2])
            maxX = maxOf(maxX, c[i * 2])
            minY = minOf(minY, c[i * 2 + 1])
            maxY = maxOf(maxY, c[i * 2 + 1])
        }
        return IntRect(minX.toInt() - 2, minY.toInt() - 2, maxX.toInt() + 3, maxY.toInt() + 3)
    }

    private fun normalizeAngle(angle: Float): Float {
        var a = angle
        while (a > PI) a -= (2 * PI).toFloat()
        while (a < -PI) a += (2 * PI).toFloat()
        return if (abs(a) < 1e-4f) 0f else a
    }

    public companion object {
        public const val MIN_SCALE: Float = 0.02f
        public const val MAX_SCALE: Float = 32f
    }
}
