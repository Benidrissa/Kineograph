package org.kineograph.engine.brush

import kotlin.math.ceil

/**
 * A growable list of stamps held in one float array, so that a stroke creates no per-stamp objects.
 * Each stamp is [STRIDE] floats: x, y, diameter, alpha, angle in radians.
 */
public class StampBuffer(initialCapacity: Int = 64) {
    private var data = FloatArray(initialCapacity * STRIDE)

    /** The number of stamps held. */
    public var count: Int = 0
        private set

    private var minX = Float.POSITIVE_INFINITY
    private var minY = Float.POSITIVE_INFINITY
    private var maxX = Float.NEGATIVE_INFINITY
    private var maxY = Float.NEGATIVE_INFINITY

    public fun clear() {
        count = 0
        minX = Float.POSITIVE_INFINITY
        minY = Float.POSITIVE_INFINITY
        maxX = Float.NEGATIVE_INFINITY
        maxY = Float.NEGATIVE_INFINITY
    }

    public fun add(x: Float, y: Float, diameter: Float, alpha: Float, angle: Float) {
        val needed = (count + 1) * STRIDE
        if (needed > data.size) data = data.copyOf(maxOf(needed, data.size * 2))
        val offset = count * STRIDE
        data[offset] = x
        data[offset + 1] = y
        data[offset + 2] = diameter
        data[offset + 3] = alpha
        data[offset + 4] = angle
        count++
        // A rotated square tip reaches the corners of its bounding circle, hence the extra margin.
        val reach = diameter * BOUNDS_MARGIN
        if (x - reach < minX) minX = x - reach
        if (y - reach < minY) minY = y - reach
        if (x + reach > maxX) maxX = x + reach
        if (y + reach > maxY) maxY = y + reach
    }

    /** Copies the current stamps into an immutable run that is safe to hand to another thread. */
    public fun snapshot(): StampRun = StampRun(data.copyOf(count * STRIDE), count, minX, minY, maxX, maxY)

    public fun x(index: Int): Float = data[index * STRIDE]

    public fun y(index: Int): Float = data[index * STRIDE + 1]

    public fun diameter(index: Int): Float = data[index * STRIDE + 2]

    public fun alpha(index: Int): Float = data[index * STRIDE + 3]

    public fun angle(index: Int): Float = data[index * STRIDE + 4]

    public companion object {
        public const val STRIDE: Int = 5
        internal const val BOUNDS_MARGIN = 0.75f
    }
}

/** An immutable batch of stamps. See [StampBuffer] for the layout of [data]. */
public class StampRun(
    public val data: FloatArray,
    public val count: Int,
    public val minX: Float,
    public val minY: Float,
    public val maxX: Float,
    public val maxY: Float,
) {
    public val isEmpty: Boolean get() = count == 0

    /** Whether this run's area could overlap the given rectangle. */
    public fun intersects(left: Float, top: Float, right: Float, bottom: Float): Boolean =
        count > 0 && minX < right && maxX > left && minY < bottom && maxY > top

    public companion object {
        public val EMPTY: StampRun =
            StampRun(FloatArray(0), 0, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
    }
}

/** A floating-point rectangle accumulator. Empty until the first [add]. */
public class BoundsAccumulator {
    public var left: Float = Float.POSITIVE_INFINITY
        private set
    public var top: Float = Float.POSITIVE_INFINITY
        private set
    public var right: Float = Float.NEGATIVE_INFINITY
        private set
    public var bottom: Float = Float.NEGATIVE_INFINITY
        private set

    public val isEmpty: Boolean get() = right < left || bottom < top

    public fun clear() {
        left = Float.POSITIVE_INFINITY
        top = Float.POSITIVE_INFINITY
        right = Float.NEGATIVE_INFINITY
        bottom = Float.NEGATIVE_INFINITY
    }

    public fun add(run: StampRun) {
        if (run.isEmpty) return
        add(run.minX, run.minY, run.maxX, run.maxY)
    }

    public fun add(minX: Float, minY: Float, maxX: Float, maxY: Float) {
        if (minX < left) left = minX
        if (minY < top) top = minY
        if (maxX > right) right = maxX
        if (maxY > bottom) bottom = maxY
    }

    /** The smallest whole-pixel rectangle containing these bounds: left, top, right, bottom. */
    public fun roundedOut(): IntArray =
        if (isEmpty) {
            intArrayOf(0, 0, 0, 0)
        } else {
            intArrayOf(left.toInt() - 1, top.toInt() - 1, ceil(right).toInt() + 1, ceil(bottom).toInt() + 1)
        }
}
