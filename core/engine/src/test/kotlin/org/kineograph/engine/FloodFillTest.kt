package org.kineograph.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kineograph.engine.fill.FillMask
import org.kineograph.engine.fill.FloodFill
import org.kineograph.model.IntRect

class FloodFillTest {
    private val black = 0xFF000000.toInt()
    private val red = 0xFFFF0000.toInt()
    private val empty = 0

    private fun blank(width: Int, height: Int) = IntArray(width * height)

    /** A hollow black square outline on a transparent image. */
    private fun outline(size: Int, thickness: Int = 2, gapAtTop: Int = 0): IntArray {
        val pixels = blank(size, size)
        val margin = size / 5
        val far = size - margin - 1
        for (y in margin..far) {
            for (x in margin..far) {
                val onEdge = x < margin + thickness || x > far - thickness || y < margin + thickness || y > far - thickness
                val inGap = y < margin + thickness && kotlin.math.abs(x - size / 2) < gapAtTop / 2.0
                if (onEdge && !inGap) pixels[y * size + x] = black
            }
        }
        return pixels
    }

    private fun requireMask(mask: FillMask?): FillMask {
        assertNotNull(mask)
        return mask ?: error("no mask")
    }

    @Test
    fun `seed outside the image gives nothing`() {
        assertNull(FloodFill.computeMask(blank(4, 4), 4, 4, -1, 0, 0))
        assertNull(FloodFill.computeMask(blank(4, 4), 4, 4, 0, 4, 0))
    }

    @Test
    fun `fills a blank image completely`() {
        val mask = requireMask(FloodFill.computeMask(blank(8, 6), 8, 6, 3, 3, 0))
        assertEquals(IntRect(0, 0, 8, 6), mask.bounds)
        for (y in 0 until 6) for (x in 0 until 8) assertTrue(mask.isSet(x, y))
    }

    @Test
    fun `stays inside a closed outline`() {
        val mask = requireMask(FloodFill.computeMask(outline(50), 50, 50, 25, 25, 0))
        assertTrue(mask.isSet(25, 25))
        assertFalse(mask.isSet(1, 1))
        assertFalse(mask.isSet(10, 10))
    }

    @Test
    fun `stays outside a closed outline when seeded outside`() {
        val mask = requireMask(FloodFill.computeMask(outline(50), 50, 50, 1, 1, 0))
        assertTrue(mask.isSet(48, 48))
        assertFalse(mask.isSet(25, 25))
    }

    @Test
    fun `a concave spiral is filled through all its turns`() {
        val size = 40
        val pixels = blank(size, size)
        // A wall with an opening at the far end forces the scanline to double back.
        for (y in 0 until size - 2) pixels[y * size + 13] = black
        for (y in 2 until size) pixels[y * size + 26] = black
        val mask = requireMask(FloodFill.computeMask(pixels, size, size, 5, 5, 0))
        assertTrue(mask.isSet(5, 5))
        assertTrue(mask.isSet(20, 20))
        assertTrue(mask.isSet(35, 35))
        assertFalse(mask.isSet(13, 5))
    }

    @Test
    fun `tolerance decides what counts as the same colour`() {
        val pixels = intArrayOf(0xFF808080.toInt(), 0xFF8A8A8A.toInt(), 0xFFA0A0A0.toInt())
        val strict = requireMask(FloodFill.computeMask(pixels, 3, 1, 0, 0, 0))
        assertEquals(IntRect(0, 0, 1, 1), strict.bounds)
        val loose = requireMask(FloodFill.computeMask(pixels, 3, 1, 0, 0, 16))
        assertEquals(IntRect(0, 0, 2, 1), loose.bounds)
        val looser = requireMask(FloodFill.computeMask(pixels, 3, 1, 0, 0, 64))
        assertEquals(IntRect(0, 0, 3, 1), looser.bounds)
    }

    @Test
    fun `transparent pixels match whatever their hidden colour is`() {
        val pixels = intArrayOf(0x00FF0000, 0x0000FF00, 0x000000FF)
        val mask = requireMask(FloodFill.computeMask(pixels, 3, 1, 0, 0, 0))
        assertEquals(IntRect(0, 0, 3, 1), mask.bounds)
    }

    @Test
    fun `without gap closing a fill leaks through a gap`() {
        val mask = requireMask(FloodFill.computeMask(outline(60, gapAtTop = 6), 60, 60, 30, 30, 0))
        assertTrue("leak expected", mask.isSet(2, 2))
    }

    @Test
    fun `gap closing stops the leak`() {
        val mask = requireMask(FloodFill.computeMask(outline(60, gapAtTop = 6), 60, 60, 30, 30, 0, gapClose = 4))
        assertTrue(mask.isSet(30, 30))
        assertFalse("must not leak outside", mask.isSet(2, 2))
        assertFalse("must not leak outside", mask.isSet(57, 57))
    }

    @Test
    fun `gap closing still reaches right up to the lines`() {
        val pixels = outline(60, gapAtTop = 6)
        val mask = requireMask(FloodFill.computeMask(pixels, 60, 60, 30, 30, 0, gapClose = 4))
        // The inside of the outline runs from 14 to 45; the pixels right next to the wall must be filled.
        assertTrue(mask.isSet(14, 30))
        assertTrue(mask.isSet(45, 30))
        assertTrue(mask.isSet(30, 45))
        assertFalse(mask.isSet(13, 30))
    }

    @Test
    fun `a gap wider than the setting still leaks`() {
        val mask = requireMask(FloodFill.computeMask(outline(60, gapAtTop = 20), 60, 60, 30, 30, 0, gapClose = 4))
        assertTrue(mask.isSet(2, 2))
    }

    @Test
    fun `gap closing works when the seed is in a space narrower than the closing distance`() {
        val size = 40
        val pixels = blank(size, size)
        for (x in 0 until size) {
            pixels[10 * size + x] = black
            pixels[13 * size + x] = black
        }
        // The channel between rows 11 and 12 is only two pixels tall.
        val mask = requireMask(FloodFill.computeMask(pixels, size, size, 20, 11, 0, gapClose = 4))
        assertTrue(mask.isSet(20, 11))
        assertTrue(mask.isSet(20, 12))
        assertFalse(mask.isSet(20, 20))
    }

    @Test
    fun `the seed search never hops over a line into the next region`() {
        val size = 60
        val pixels = blank(size, size)
        // One thin wall. The seed sits right beside it, too close to the wall to count as clear.
        for (y in 0 until size) pixels[y * size + 30] = black
        for (y in 0 until size) pixels[y * size + 26] = black
        val mask = requireMask(FloodFill.computeMask(pixels, size, size, 28, 30, 0, gapClose = 4))
        assertTrue(mask.isSet(28, 30))
        assertFalse("hopped the right wall", mask.isSet(45, 30))
        assertFalse("hopped the left wall", mask.isSet(10, 30))
    }

    @Test
    fun `fills every shape of region exactly`() {
        // Compares the span fill against a slow, obviously correct fill on random mazes.
        val random = java.util.Random(1234)
        repeat(200) {
            val width = 1 + random.nextInt(40)
            val height = 1 + random.nextInt(40)
            val pixels = IntArray(width * height) { if (random.nextInt(100) < 38) black else empty }
            val seedX = random.nextInt(width)
            val seedY = random.nextInt(height)
            val mask = requireMask(FloodFill.computeMask(pixels, width, height, seedX, seedY, 0))
            val expected = BooleanArray(width * height)
            val pending = ArrayDeque<Int>()
            pending.add(seedY * width + seedX)
            expected[seedY * width + seedX] = true
            val seedColor = pixels[seedY * width + seedX]
            while (pending.isNotEmpty()) {
                val i = pending.removeFirst()
                val x = i % width
                val y = i / width
                listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1).forEach { (nx, ny) ->
                    if (nx in 0 until width && ny in 0 until height) {
                        val n = ny * width + nx
                        if (!expected[n] && pixels[n] == seedColor) {
                            expected[n] = true
                            pending.add(n)
                        }
                    }
                }
            }
            for (i in expected.indices) {
                assertEquals("pixel $i of ${width}x$height, seed $seedX,$seedY", expected[i], mask.isSet(i % width, i / width))
            }
        }
    }

    @Test
    fun `thickening walls matches a slow reference`() {
        // Gap closing must treat a gap as closed exactly when it is narrower than the thickened walls.
        for (gap in 1..12) {
            val size = 80
            val pixels = blank(size, size)
            for (x in 0 until size) if (kotlin.math.abs(x - 40) * 2 >= gap) pixels[40 * size + x] = black
            val mask = requireMask(FloodFill.computeMask(pixels, size, size, 40, 10, 0, gapClose = 3))
            val leaked = mask.isSet(40, 70)
            assertEquals("gap of $gap", gap > 6, leaked)
        }
    }

    @Test
    fun `a seed on a line fills the line`() {
        val mask = requireMask(FloodFill.computeMask(outline(50), 50, 50, 10, 25, 0, gapClose = 3))
        assertTrue(mask.isSet(10, 25))
        assertFalse(mask.isSet(25, 25))
    }

    @Test
    fun `expansion grows the region under the lines`() {
        val pixels = outline(60)
        val plain = requireMask(FloodFill.computeMask(pixels, 60, 60, 30, 30, 0))
        assertFalse(plain.isSet(13, 30))
        val expanded = requireMask(FloodFill.computeMask(pixels, 60, 60, 30, 30, 0, expand = 1))
        assertTrue(expanded.isSet(13, 30))
        assertFalse(expanded.isSet(12, 30))
    }

    @Test
    fun `expansion stops at the image edge and keeps bounds valid`() {
        val pixels = blank(10, 10)
        val mask = requireMask(FloodFill.computeMask(pixels, 10, 10, 5, 5, 0, expand = 5))
        assertEquals(IntRect(0, 0, 10, 10), mask.bounds)
    }

    @Test
    fun `apply replaces selected pixels only`() {
        val pixels = intArrayOf(empty, black, empty)
        val mask = requireMask(FloodFill.computeMask(pixels, 3, 1, 0, 0, 0))
        FloodFill.apply(pixels, 3, mask, red, behind = false)
        assertEquals(red, pixels[0])
        assertEquals(black, pixels[1])
        assertEquals(empty, pixels[2])
    }

    @Test
    fun `filling behind keeps opaque line art on top`() {
        val pixels = intArrayOf(empty, black)
        val mask = FillMask.of(2, 1) { _, _ -> true }
        FloodFill.apply(pixels, 2, mask, red, behind = true)
        assertEquals(red, pixels[0])
        assertEquals(black, pixels[1])
    }

    @Test
    fun `filling behind blends under anti-aliased pixels with no pale halo`() {
        // A half-transparent black edge pixel over a red fill must come out dark red and opaque.
        val pixels = intArrayOf(0x80000000.toInt())
        val mask = FillMask.of(1, 1) { _, _ -> true }
        FloodFill.apply(pixels, 1, mask, red, behind = true)
        val result = pixels[0]
        assertEquals(255, result ushr 24)
        assertEquals(0, result and 0xFF)
        val r = (result shr 16) and 0xFF
        assertTrue("got $r", r in 120..135)
    }

    @Test
    fun `growing reaches exactly the requested distance, diagonals included`() {
        val size = 21
        val pixels = blank(size, size)
        // A single open pixel region: everything is wall except the centre.
        for (i in pixels.indices) pixels[i] = black
        pixels[10 * size + 10] = empty
        val mask = requireMask(FloodFill.computeMask(pixels, size, size, 10, 10, 0, expand = 3))
        for (y in 0 until size) {
            for (x in 0 until size) {
                val inside = kotlin.math.abs(x - 10) <= 3 && kotlin.math.abs(y - 10) <= 3
                assertEquals("pixel $x,$y", inside, mask.isSet(x, y))
            }
        }
        assertEquals(IntRect(7, 7, 14, 14), mask.bounds)
    }

    @Test
    fun `growth is correct across word boundaries and image edges`() {
        for (width in listOf(63, 64, 65, 127, 128, 129)) {
            val pixels = IntArray(width * 3) { black }
            for (x in 0 until width) pixels[width + x] = empty
            val mask = requireMask(FloodFill.computeMask(pixels, width, 3, 0, 1, 0, expand = 1))
            assertEquals("width $width", IntRect(0, 0, width, 3), mask.bounds)
            for (y in 0 until 3) for (x in 0 until width) assertTrue("width $width at $x,$y", mask.isSet(x, y))
        }
    }

    @Test
    fun `fills are right on images around word-size widths`() {
        for (width in listOf(1, 2, 63, 64, 65, 128)) {
            val mask = requireMask(FloodFill.computeMask(blank(width, 2), width, 2, width - 1, 1, 0))
            assertEquals(IntRect(0, 0, width, 2), mask.bounds)
        }
    }

    @Test
    fun `an empty mask region changes nothing`() {
        val pixels = intArrayOf(black)
        val mask = FillMask.of(1, 1) { _, _ -> false }
        FloodFill.apply(pixels, 1, mask, red, behind = false)
        assertEquals(black, pixels[0])
    }

    @Test
    fun `gap-closing fill of a full HD image is fast enough to have headroom on slow phones`() {
        val width = 1920
        val height = 1080
        val pixels = blank(width, height)
        for (x in 200 until 1700) {
            pixels[100 * width + x] = black
            pixels[980 * width + x] = black
        }
        for (y in 100 until 980) {
            pixels[y * width + 200] = black
            pixels[y * width + 1700] = black
        }
        // Warm up so the JIT does not dominate the measurement.
        repeat(20) { FloodFill.computeMask(pixels, width, height, 900, 500, 8, gapClose = 4, expand = 2) }
        var bestMs = Long.MAX_VALUE
        var mask: FillMask? = null
        repeat(5) {
            val start = System.nanoTime()
            mask = FloodFill.computeMask(pixels, width, height, 900, 500, 8, gapClose = 4, expand = 2)
            bestMs = minOf(bestMs, (System.nanoTime() - start) / 1_000_000)
        }
        val result = requireMask(mask)
        assertTrue(result.isSet(900, 500))
        assertFalse(result.isSet(100, 50))
        // The phone target is 150 ms for the whole operation. Build machines vary too much to demand a
        // precise figure, but this catches the algorithm getting an order of magnitude slower.
        assertTrue("took $bestMs ms", bestMs < 150)
    }
}
