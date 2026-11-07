package org.kineograph.model

import kotlin.math.sqrt

/** How a canvas of some size suits this device, going by what it has free at the moment. */
public sealed interface CanvasFit {
    /** Nothing to say. */
    public data object Fits : CanvasFit

    /** It should open, but it takes much of what is free and may run slowly. */
    public data object Large : CanvasFit

    /**
     * It will not open, and the suggested size, the same shape, should. If [memoryWouldHelp], it is only a matter of what is free
     * at the moment. If not, it is more than Android can hold as one picture on any device.
     */
    public data class TooBig(val suggestedWidth: Int, val suggestedHeight: Int, val memoryWouldHelp: Boolean) : CanvasFit
}

/**
 * Whether a canvas can work, in answer to what the device has, not to a limit of ours. It takes two bitmaps of its size at 4 bytes
 * per pixel, the surface and the stroke buffer, and Android will not make one over 2 GB. [availableBytes] is what the system says is
 * free. Zero or less means it did not say, and a device that cannot answer should not have sizes refused because of it.
 */
public fun canvasFit(width: Int, height: Int, availableBytes: Long): CanvasFit {
    val pixels = width.toLong() * height
    val needed = pixels * BYTES_PER_PIXEL * SURFACES
    val memoryKnown = availableBytes > 0
    val overOneBitmap = pixels > MAX_BITMAP_PIXELS
    if (overOneBitmap || (memoryKnown && needed > availableBytes)) {
        // Half of what is free, because a suggestion that only just fits would run straight into the next problem.
        val comfortable = if (memoryKnown) availableBytes / 2 / (BYTES_PER_PIXEL * SURFACES) else MAX_BITMAP_PIXELS
        val (suggestedWidth, suggestedHeight) = sameShapeWithin(width, height, comfortable.coerceIn(1, MAX_BITMAP_PIXELS))
        return CanvasFit.TooBig(suggestedWidth, suggestedHeight, memoryWouldHelp = !overOneBitmap)
    }
    return if (pixels > LARGE_CANVAS_PIXELS || (memoryKnown && needed > availableBytes / 2)) CanvasFit.Large else CanvasFit.Fits
}

/** The largest size of about this shape with at most [maxPixels] pixels. A very thin canvas can only keep its shape down to 1 pixel. */
private fun sameShapeWithin(width: Int, height: Int, maxPixels: Long): Pair<Int, Int> {
    val scale = sqrt(maxPixels.toDouble() / (width.toLong() * height))
    val scaledWidth = (width * scale).toLong().coerceIn(1, maxPixels)
    val scaledHeight = (height * scale).toLong().coerceIn(1, maxPixels)
    return if (scaledWidth * scaledHeight <= maxPixels) {
        scaledWidth.toInt() to scaledHeight.toInt()
    } else if (scaledWidth >= scaledHeight) {
        (maxPixels / scaledHeight).toInt() to scaledHeight.toInt()
    } else {
        scaledWidth.toInt() to (maxPixels / scaledWidth).toInt()
    }
}

private const val BYTES_PER_PIXEL = 4L
private const val SURFACES = 2L

/** Android counts a bitmap's bytes in an Int. */
private const val MAX_BITMAP_PIXELS = Int.MAX_VALUE / BYTES_PER_PIXEL

/** 4K. Beyond this a canvas is slow on a good phone, whatever memory is free. */
private const val LARGE_CANVAS_PIXELS = 3840L * 2160L
