package org.kineograph.media.svg

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * How far one picture is from another.
 *
 * @property meanDifference the average difference of a colour channel, from 0 to 255.
 * @property strayShare the share of pixels, from 0 to 1, that differ by a lot, and not just along the edge of something.
 * @property solidBlocks how many pixels differ even a little, with only other such pixels around them. An outline that is off makes
 *   thin lines of different pixels, while something that is wrong makes a block, which even a small shape has an inside of. That catches
 *   a slightly wrong colour on a small shape, which the other two would average away.
 */
internal data class PictureDifference(val meanDifference: Double, val strayShare: Double, val solidBlocks: Int) {
    val isClose: Boolean get() = meanDifference <= MEAN_LIMIT && strayShare <= STRAY_LIMIT && solidBlocks <= SOLID_LIMIT

    /**
     * For pictures with letters in them. A browser smooths small letters its own way, with some contrast added, where we fill their
     * outlines. So letters come out a little lighter here, along every stem, and it adds up where stems are close together.
     */
    val isCloseWithLetters: Boolean
        get() = meanDifference <= MEAN_LIMIT_WITH_LETTERS && strayShare <= STRAY_LIMIT && solidBlocks <= SOLID_LIMIT_WITH_LETTERS

    override fun toString(): String =
        "mean colour difference %.2f, %.2f%% of pixels far off, %d in solid blocks".format(meanDifference, strayShare * 100, solidBlocks)

    companion object {
        /** Antialiasing and 8-bit rounding stay well under this. A colour that is off, or a large shape in the wrong place, does not. */
        const val MEAN_LIMIT = 0.7
        const val MEAN_LIMIT_WITH_LETTERS = 1.0

        /**
         * A small wrong shape moves the average very little, which is what this share is for. Set above the few pixels two rasterizers
         * can disagree on along an outline: on a 200 by 200 picture this is 120 pixels.
         */
        const val STRAY_LIMIT = 0.003

        /** Above zero only so that where two thin lines of difference happen to cross does not count. */
        const val SOLID_LIMIT = 2
        const val SOLID_LIMIT_WITH_LETTERS = 40

        /** How far apart two channels must be, out of 255, to count towards a block. Well past 8-bit rounding and blending error. */
        const val BLOCK_DIFFERENCE = 12

        /** How far apart two channels must be, out of 255, for a pixel to count as far off. */
        const val FAR_OFF = 64
    }
}

/**
 * Compares two pictures of the same size, colour by colour, with colours scaled by their opacity. That way the colour under a
 * nearly clear pixel, which nobody can see, cannot count against either side, while an opacity that is wrong always does.
 *
 * A pixel only counts as far off if, in one of the pictures, nothing around it is close to what the other picture has there. An edge
 * that lands a fraction of a pixel away has a match next door in both. A shape that is missing does not: only the picture that has
 * the shape shows colour that the other cannot match.
 */
internal fun comparePictures(ours: Bitmap, reference: Bitmap): PictureDifference {
    require(ours.width == reference.width && ours.height == reference.height) { "sizes differ" }
    val width = ours.width
    val height = ours.height
    val a = premultiplied(ours)
    val b = premultiplied(reference)
    var total = 0L
    var stray = 0
    val differs = BooleanArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val at = (y * width + x) * 4
            var largest = 0
            for (channel in 0 until 4) {
                val difference = abs(a[at + channel] - b[at + channel])
                total += difference
                largest = max(largest, difference)
            }
            if (largest > PictureDifference.FAR_OFF &&
                (!hasCloseNeighbour(a, b, x, y, width, height) || !hasCloseNeighbour(b, a, x, y, width, height))
            ) {
                stray++
            }
            if (largest > PictureDifference.BLOCK_DIFFERENCE) differs[y * width + x] = true
        }
    }
    val pixels = (width * height).toDouble()
    return PictureDifference(total / (pixels * 4.0), stray / pixels, countSolid(differs, width, height))
}

private fun countSolid(differs: BooleanArray, width: Int, height: Int): Int {
    var solid = 0
    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            var surrounded = true
            for (ny in y - 1..y + 1) for (nx in x - 1..x + 1) if (!differs[ny * width + nx]) surrounded = false
            if (surrounded) solid++
        }
    }
    return solid
}

private fun hasCloseNeighbour(a: IntArray, b: IntArray, x: Int, y: Int, width: Int, height: Int): Boolean {
    val at = (y * width + x) * 4
    for (ny in max(0, y - 1)..min(height - 1, y + 1)) {
        for (nx in max(0, x - 1)..min(width - 1, x + 1)) {
            val other = (ny * width + nx) * 4
            var close = true
            for (channel in 0 until 4) {
                if (abs(a[at + channel] - b[other + channel]) > PictureDifference.FAR_OFF) {
                    close = false
                    break
                }
            }
            if (close) return true
        }
    }
    return false
}

private fun premultiplied(bitmap: Bitmap): IntArray {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val out = IntArray(pixels.size * 4)
    for (i in pixels.indices) {
        val color = pixels[i]
        val alpha = Color.alpha(color)
        out[i * 4] = Color.red(color) * alpha / 255
        out[i * 4 + 1] = Color.green(color) * alpha / 255
        out[i * 4 + 2] = Color.blue(color) * alpha / 255
        out[i * 4 + 3] = alpha
    }
    return out
}

/** Our picture, the browser's, and where they differ, side by side on a checkerboard so that transparency can be seen. */
internal fun sideBySide(ours: Bitmap, reference: Bitmap): Bitmap {
    val gap = 4
    val width = ours.width
    val height = ours.height
    val sheet = Bitmap.createBitmap(width * 3 + gap * 2, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(sheet)
    canvas.drawColor(Color.BLACK)
    val paint = Paint()
    for ((index, picture) in listOf(ours, reference).withIndex()) {
        val left = index * (width + gap)
        canvas.save()
        canvas.clipRect(Rect(left, 0, left + width, height))
        val cell = 10
        for (y in 0 until height step cell) {
            for (x in 0 until width step cell) {
                paint.color = if ((x + y) / cell % 2 == 0) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt()
                canvas.drawRect(Rect(left + x, y, left + x + cell, y + cell), paint)
            }
        }
        canvas.drawBitmap(picture, left.toFloat(), 0f, null)
        canvas.restore()
    }
    val a = premultiplied(ours)
    val b = premultiplied(reference)
    val left = 2 * (width + gap)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val at = (y * width + x) * 4
            var largest = 0
            for (channel in 0 until 4) largest = max(largest, abs(a[at + channel] - b[at + channel]))
            // Small differences are made easy to see, while anything far off goes to full white.
            val level = min(255, largest * 4)
            sheet.setPixel(left + x, y, Color.argb(255, level, level, level))
        }
    }
    return sheet
}
