package org.kineograph.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasFitTest {
    private val plenty = 4L * 1024 * 1024 * 1024

    private fun tooBig(width: Int, height: Int, available: Long) = canvasFit(width, height, available) as CanvasFit.TooBig

    @Test
    fun `a canvas wider than the old limit is fine when memory allows it`() {
        assertEquals(CanvasFit.Fits, canvasFit(9000, 720, plenty))
    }

    @Test
    fun `what matters is the pixels, not the edge`() {
        assertEquals(CanvasFit.Fits, canvasFit(100_000, 10, plenty))
    }

    @Test
    fun `a canvas past 4K is large even with memory to spare`() {
        assertEquals(CanvasFit.Fits, canvasFit(3840, 2160, plenty))
        assertEquals(CanvasFit.Large, canvasFit(3841, 2160, plenty))
    }

    @Test
    fun `a canvas taking more than half of what is free is large`() {
        val needed = 1000L * 1000 * 4 * 2
        assertEquals(CanvasFit.Fits, canvasFit(1000, 1000, needed * 2))
        assertEquals(CanvasFit.Large, canvasFit(1000, 1000, needed * 2 - 1))
        assertEquals(CanvasFit.Large, canvasFit(1000, 1000, needed))
    }

    @Test
    fun `a canvas needing more than is free is too big, and closing apps might help`() {
        val needed = 1000L * 1000 * 4 * 2
        assertTrue(tooBig(1000, 1000, needed - 1).memoryWouldHelp)
    }

    @Test
    fun `what is suggested fits and keeps the shape`() {
        val available = 512L * 1024 * 1024
        val suggestion = tooBig(40_000, 20_000, available)
        val fit = canvasFit(suggestion.suggestedWidth, suggestion.suggestedHeight, available)
        assertFalse("$suggestion is $fit", fit is CanvasFit.TooBig)
        assertEquals(2.0, suggestion.suggestedWidth.toDouble() / suggestion.suggestedHeight, 0.01)
    }

    @Test
    fun `a thin canvas gets a suggestion that really is smaller`() {
        val available = 1024L * 1024
        val suggestion = tooBig(900_000_000, 1, available)
        assertEquals(1, suggestion.suggestedHeight)
        assertTrue("$suggestion", suggestion.suggestedWidth.toLong() * 4 * 2 <= available)
    }

    @Test
    fun `no memory to speak of still suggests a size that exists`() {
        val suggestion = tooBig(1000, 1000, 1)
        assertEquals(1, suggestion.suggestedWidth)
        assertEquals(1, suggestion.suggestedHeight)
    }

    @Test
    fun `a device that does not say what is free is not refused anything it could hold`() {
        assertEquals(CanvasFit.Fits, canvasFit(2000, 2000, 0))
        assertEquals(CanvasFit.Large, canvasFit(20_000, 20_000, 0))
    }

    @Test
    fun `no bitmap can be over 2 GB, whatever is free, and closing apps would not help`() {
        val edge = 23_171 // Past 2 GB by a little: the square root of 2^31 / 4 is about 23170.
        assertEquals(CanvasFit.Large, canvasFit(23_170, 23_170, 0))
        val suggestion = tooBig(edge, edge, 0)
        assertFalse(suggestion.memoryWouldHelp)
        assertEquals(CanvasFit.Large, canvasFit(suggestion.suggestedWidth, suggestion.suggestedHeight, 0))
    }

    @Test
    fun `sizes whose product overflows an Int are still caught`() {
        assertTrue(canvasFit(65_536, 65_536, plenty) is CanvasFit.TooBig)
        assertTrue(canvasFit(999_999_999, 999_999_999, 0) is CanvasFit.TooBig)
    }
}
