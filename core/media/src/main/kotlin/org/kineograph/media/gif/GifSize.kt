package org.kineograph.media.gif

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The size a GIF comes out at. */
public data class GifSize(val width: Int, val height: Int, val wasReduced: Boolean)

public object GifSizes {
    /** The format stores each edge in 16 bits, so a larger size would wrap around and make a broken file. */
    private const val MAX_EDGE = 65_535

    /** One frame's pixels have to fit in an array. This leaves room for rounding either edge up afterwards. */
    private const val MAX_PIXELS = 2_147_000_000.0

    /**
     * The nearest size to [width] × [height] that a GIF can hold, keeping the shape. Nothing here is about what the
     * device can manage: running out of memory is still possible, and has its own message.
     */
    public fun fit(width: Int, height: Int): GifSize {
        val edgeFit = min(1.0, MAX_EDGE.toDouble() / max(width, height))
        val pixels = width.toDouble() * height * edgeFit * edgeFit
        val scale = edgeFit * min(1.0, sqrt(MAX_PIXELS / pixels))
        if (scale >= 1.0) return GifSize(width, height, wasReduced = false)
        return GifSize(
            (width * scale).roundToInt().coerceIn(1, MAX_EDGE),
            (height * scale).roundToInt().coerceIn(1, MAX_EDGE),
            wasReduced = true,
        )
    }
}
