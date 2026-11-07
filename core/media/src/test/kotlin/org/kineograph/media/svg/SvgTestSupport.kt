package org.kineograph.media.svg

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs

/** Wraps a body in an svg element sized one user unit to one pixel, which keeps expected pixel positions easy to read. */
internal fun svg(body: String, size: Int = 100, attributes: String = ""): String =
    """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="$size" height="$size" $attributes>$body</svg>"""

internal fun parse(source: String): SvgDocument = SvgParser.parse(source)

internal fun SvgDocument.frame(time: Double = 0.0, width: Int = 100, height: Int = 100): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    render(Canvas(bitmap), time, RectF(0f, 0f, width.toFloat(), height.toFloat()))
    return bitmap
}

internal fun drawn(body: String, time: Double = 0.0, size: Int = 100, attributes: String = ""): Bitmap =
    parse(svg(body, size, attributes)).frame(time, size, size)

internal fun fixture(name: String): ByteArray {
    val stream = checkNotNull(object {}.javaClass.getResourceAsStream("/svg/$name")) { "missing fixture $name" }
    return stream.use { it.readBytes() }
}

internal const val CLEAR = Color.TRANSPARENT
internal const val RED = Color.RED
internal const val LIME = 0xFF00FF00.toInt()
internal const val BLUE = Color.BLUE
internal const val BLACK = Color.BLACK
internal const val WHITE = Color.WHITE

private fun hex(color: Int): String = "#%08X".format(color)

/** Exact check, for pixels well inside a shape, where antialiasing has no say. */
internal fun Bitmap.assertPixel(x: Int, y: Int, expected: Int) {
    val actual = getPixel(x, y)
    assertEquals("pixel at ($x, $y) was ${hex(actual)}, expected ${hex(expected)}", hex(expected), hex(actual))
}

/** For values that go through blending, where 8-bit rounding can move a channel by a step or two. */
internal fun Bitmap.assertPixelNear(x: Int, y: Int, expected: Int, tolerance: Int = 2) {
    val actual = getPixel(x, y)
    val close =
        abs(Color.alpha(actual) - Color.alpha(expected)) <= tolerance &&
            abs(Color.red(actual) - Color.red(expected)) <= tolerance &&
            abs(Color.green(actual) - Color.green(expected)) <= tolerance &&
            abs(Color.blue(actual) - Color.blue(expected)) <= tolerance
    assertTrue("pixel at ($x, $y) was ${hex(actual)}, expected about ${hex(expected)}", close)
}

/** The number of pixels with any coverage, for pictures whose exact outline depends on the fonts of the machine. */
internal fun Bitmap.paintedPixels(): Int {
    var count = 0
    for (y in 0 until height) for (x in 0 until width) if (Color.alpha(getPixel(x, y)) != 0) count++
    return count
}

internal fun assertSamePixels(a: Bitmap, b: Bitmap) {
    assertEquals(a.width, b.width)
    assertEquals(a.height, b.height)
    for (y in 0 until a.height) {
        for (x in 0 until a.width) {
            assertEquals("pixel ($x, $y)", hex(a.getPixel(x, y)), hex(b.getPixel(x, y)))
        }
    }
}

internal fun assertDiffer(a: Bitmap, b: Bitmap) {
    for (y in 0 until a.height) for (x in 0 until a.width) if (a.getPixel(x, y) != b.getPixel(x, y)) return
    throw AssertionError("the two frames are identical")
}

/** The left edge of the shape on a row, to a fraction of a pixel, read from the antialiasing coverage of its first pixel. */
internal fun Bitmap.leftEdge(y: Int = height / 2): Double {
    for (x in 0 until width) {
        val alpha = Color.alpha(getPixel(x, y))
        if (alpha > 0) return x + 1.0 - alpha / 255.0
    }
    return Double.NaN
}

internal fun Bitmap.rightEdge(y: Int = height / 2): Double {
    for (x in width - 1 downTo 0) {
        val alpha = Color.alpha(getPixel(x, y))
        if (alpha > 0) return x + alpha / 255.0
    }
    return Double.NaN
}

internal fun Bitmap.topEdge(x: Int = width / 2): Double {
    for (y in 0 until height) {
        val alpha = Color.alpha(getPixel(x, y))
        if (alpha > 0) return y + 1.0 - alpha / 255.0
    }
    return Double.NaN
}

/** How opaque the centre pixel is, from 0 to 1. Animating opacity of a full-canvas rectangle turns this into a value probe. */
internal fun Bitmap.centreAlpha(): Double = Color.alpha(getPixel(width / 2, height / 2)) / 255.0
