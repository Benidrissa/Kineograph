package org.kineograph.media.svg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The path grammar is plain Kotlin, so these run without Android. Pixel checks of the same paths are in SvgShapeRenderTest. */
class SvgPathGrammarTest {
    private class Recorder : PathSink {
        val log = StringBuilder()

        private fun n(value: Double): String {
            val rounded = Math.round(value * 1000.0) / 1000.0
            return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
        }

        override fun moveTo(x: Double, y: Double) {
            log.append("M${n(x)},${n(y)} ")
        }

        override fun lineTo(x: Double, y: Double) {
            log.append("L${n(x)},${n(y)} ")
        }

        override fun quadTo(x1: Double, y1: Double, x: Double, y: Double) {
            log.append("Q${n(x1)},${n(y1)},${n(x)},${n(y)} ")
        }

        override fun cubicTo(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double) {
            log.append("C${n(x1)},${n(y1)},${n(x2)},${n(y2)},${n(x)},${n(y)} ")
        }

        override fun close() {
            log.append("Z ")
        }
    }

    private fun walked(d: String): String {
        val recorder = Recorder()
        parsePathData(d).data.walk(recorder)
        return recorder.log.toString().trim()
    }

    @Test
    fun `absolute commands pass through`() {
        assertEquals("M10,20 L30,40 Z", walked("M10 20 L30 40 Z"))
    }

    @Test
    fun `relative commands add to the current point`() {
        assertEquals("M10,20 L15,25 L20,20", walked("M10 20 l5 5 l5 -5"))
    }

    @Test
    fun `a relative first moveto counts as absolute`() {
        assertEquals("M10,20 L11,21", walked("m10 20 l1 1"))
    }

    @Test
    fun `extra moveto pairs are implicit lines`() {
        assertEquals("M1,2 L3,4 L5,6", walked("M1 2 3 4 5 6"))
    }

    @Test
    fun `extra relative moveto pairs are relative lines`() {
        assertEquals("M1,2 L4,6 L9,12", walked("m1 2 3 4 5 6"))
    }

    @Test
    fun `other commands repeat implicitly`() {
        assertEquals("M0,0 L1,1 L2,2 L3,3", walked("M0 0 L1 1 2 2 3 3"))
        assertEquals("M0,0 L1,0 L3,0 L3,1 L3,3", walked("M0 0 h1 2 v1 2"))
        assertEquals("M0,0 C1,1,2,2,3,3 C4,4,5,5,6,6", walked("M0 0 C1 1 2 2 3 3 4 4 5 5 6 6"))
    }

    @Test
    fun `horizontal and vertical lines keep the other coordinate`() {
        assertEquals("M10,20 L30,20 L30,50 L5,50 L5,45", walked("M10 20 H30 V50 h-25 v-5"))
    }

    @Test
    fun `cubic smooth reflects the previous control point`() {
        assertEquals("M0,0 C1,1,2,2,3,3 C4,4,5,6,7,8", walked("M0 0 C1 1 2 2 3 3 S5 6 7 8"))
    }

    @Test
    fun `cubic smooth with no cubic before it starts at the current point`() {
        assertEquals("M0,0 L3,3 C3,3,5,6,7,8", walked("M0 0 L3 3 S5 6 7 8"))
    }

    @Test
    fun `quadratic smooth reflects and chains`() {
        assertEquals("M0,0 Q1,1,2,0 Q3,-1,4,0 Q5,1,6,0", walked("M0 0 Q1 1 2 0 T4 0 T6 0"))
    }

    @Test
    fun `quadratic smooth with no quadratic before it starts at the current point`() {
        assertEquals("M0,0 L2,2 Q2,2,4,4", walked("M0 0 L2 2 T4 4"))
    }

    @Test
    fun `a smooth command does not reflect across the other curve type`() {
        assertEquals("M0,0 Q1,1,2,0 C2,0,3,3,4,4", walked("M0 0 Q1 1 2 0 S3 3 4 4"))
    }

    @Test
    fun `relative smooth forms work`() {
        assertEquals("M0,0 C1,1,2,2,3,3 C4,4,5,6,7,8", walked("M0 0 c1 1 2 2 3 3 s2 3 4 5"))
        assertEquals("M0,0 Q1,1,2,0 Q3,-1,4,0", walked("M0 0 q1 1 2 0 t2 0"))
    }

    @Test
    fun `numbers need no separators where the grammar allows it`() {
        assertEquals("M0.5,-1000", walked("M.5-1e3"))
        assertEquals("M1.5,0.5", walked("M1.5.5"))
        assertEquals("M0,0.5 L0.25,-0.75", walked("M0 .5L.25-.75"))
        assertEquals("M1,2 L3,4", walked("M1,2,3,4"))
        assertEquals("M-1,-2", walked("M-1-2"))
    }

    @Test
    fun `exponents are read in all their forms`() {
        assertEquals("M10,100 L0.1,1000", walked("M1e1 1E2 L1e-1 1E+3"))
    }

    @Test
    fun `a dot needs digits on one side`() {
        assertEquals("M1,2", walked("M1 2 ."))
        assertTrue(parsePathData("M1 2 .").hadError)
    }

    @Test
    fun `an e that starts no exponent ends the number`() {
        val parsed = parsePathData("M1e 2")
        assertTrue(parsed.hadError)
    }

    @Test
    fun `arc flags need no separators`() {
        val spaced = walked("M0 0 A5 5 0 0 1 10 0")
        assertEquals(spaced, walked("M0 0 A5 5 0 0110 0"))
        assertEquals(spaced, walked("M0 0 A5 5 0 01 10 0"))
        assertEquals(spaced, walked("M0 0 A5,5,0,0,1,10,0"))
        // The flag is one character, so ".5" after "00" is the next coordinate, not part of a number.
        assertEquals(walked("M0 0 a1 1 0 0 0 .5 .5"), walked("M0 0 a1 1 0 00.5.5"))
    }

    @Test
    fun `an arc flag must be zero or one`() {
        val parsed = parsePathData("M0 0 A5 5 0 2 1 10 0")
        assertTrue(parsed.hadError)
        assertEquals("M0,0", walked("M0 0 A5 5 0 2 1 10 0"))
    }

    @Test
    fun `an arc with a zero radius is a straight line`() {
        assertEquals("M0,0 L10,5", walked("M0 0 A0 5 0 0 1 10 5"))
    }

    @Test
    fun `an arc to the same point draws nothing`() {
        assertEquals("M3,3", walked("M3 3 A5 5 0 0 1 3 3"))
    }

    @Test
    fun `an arc ends exactly at its end point`() {
        val recorder = walked("M0 0 A5 5 0 0 1 10 0")
        assertTrue(recorder, recorder.endsWith(",10,0"))
    }

    @Test
    fun `a half circle bulges the way the sweep flag says`() {
        val clockwise = parsePathData("M0 0 A5 5 0 0 1 10 0").data.bounds
        val counter = parsePathData("M0 0 A5 5 0 0 0 10 0").data.bounds
        // In SVG's y-down space a sweep of 1 is drawn clockwise, so going right it passes above the line.
        assertEquals(-5.0, checkNotNull(clockwise).y, 0.01)
        assertEquals(0.0, clockwise.bottom, 0.01)
        assertEquals(0.0, checkNotNull(counter).y, 0.01)
        assertEquals(5.0, counter.bottom, 0.01)
    }

    @Test
    fun `the large arc flag picks the long way round`() {
        val small = checkNotNull(parsePathData("M0 0 A10 10 0 0 1 10 10").data.bounds)
        val large = checkNotNull(parsePathData("M0 0 A10 10 0 1 1 10 10").data.bounds)
        assertTrue(large.w > small.w)
        assertEquals(10.0, small.w, 0.05)
        assertEquals(20.0, large.w, 0.05)
    }

    @Test
    fun `radii too small to reach are scaled up`() {
        val box = checkNotNull(parsePathData("M0 0 A1 1 0 0 1 10 0").data.bounds)
        assertEquals(5.0, -box.y, 0.01)
    }

    @Test
    fun `negative radii count as positive`() {
        assertEquals(walked("M0 0 A5 5 0 0 1 10 0"), walked("M0 0 A-5 -5 0 0 1 10 0"))
    }

    @Test
    fun `a rotated arc tilts its ellipse`() {
        val box = checkNotNull(parsePathData("M0 0 A10 2 90 0 1 4 0").data.bounds)
        // With the long axis turned upright, the shape is taller than it is wide.
        assertTrue("was $box", box.h > box.w)
    }

    @Test
    fun `an error keeps everything before it`() {
        assertEquals("M10,10 L20,30", walked("M10 10 L20 30 X5 5"))
        assertTrue(parsePathData("M10 10 L20 30 X5 5").hadError)
    }

    @Test
    fun `incomplete arguments stop at the last whole command`() {
        assertEquals("M10,10 L20,20", walked("M10 10 L20 20 30"))
        assertTrue(parsePathData("M10 10 L20 20 30").hadError)
    }

    @Test
    fun `a path must begin with a moveto`() {
        assertTrue(parsePathData("L10 10").data.isEmpty)
        assertTrue(parsePathData("L10 10").hadError)
        assertTrue(parsePathData("10 10").data.isEmpty)
    }

    @Test
    fun `an empty path is not an error`() {
        assertTrue(parsePathData("").data.isEmpty)
        assertFalse(parsePathData("").hadError)
        assertFalse(parsePathData("   ").hadError)
    }

    @Test
    fun `numbers beyond double range are errors rather than infinity`() {
        val parsed = parsePathData("M1e999 0")
        assertTrue(parsed.hadError)
        assertTrue(parsed.data.isEmpty)
    }

    @Test
    fun `things Double parseDouble accepts but SVG does not are refused`() {
        assertTrue(parsePathData("MNaN 0").hadError)
        assertTrue(parsePathData("MInfinity 0").hadError)
        assertTrue(parsePathData("M1f 0").hadError)
        assertTrue(parsePathData("M0x10 0").hadError)
    }

    @Test
    fun `closing returns the current point to the start of the subpath`() {
        assertEquals("M10,10 L20,10 Z L15,15", walked("M10 10 L20 10 Z l5 5"))
        assertEquals("M10,10 L20,10 Z M15,15", walked("M10 10 L20 10 z m5 5"))
    }

    @Test
    fun `tight bounds see the true extent of a curve`() {
        // Control points at y = 100 would give a box of height 100. The curve itself only rises to 75.
        val box = checkNotNull(parsePathData("M0 0 C0 100 100 100 100 0").data.bounds)
        assertEquals(0.0, box.x, 1e-6)
        assertEquals(0.0, box.y, 1e-6)
        assertEquals(100.0, box.w, 1e-6)
        assertEquals(75.0, box.h, 1e-6)
    }

    @Test
    fun `tight bounds see the true extent of a quadratic`() {
        val box = checkNotNull(parsePathData("M0 0 Q50 100 100 0").data.bounds)
        assertEquals(50.0, box.h, 1e-6)
    }

    @Test
    fun `a trailing moveto does not stretch the bounds`() {
        val box = checkNotNull(parsePathData("M0 0 L10 10 M500 500").data.bounds)
        assertEquals(10.0, box.w, 1e-6)
    }

    @Test
    fun `a lone moveto has no bounds`() {
        assertNull(parsePathData("M5 5").data.bounds)
    }

    @Test
    fun `paths with matching commands can animate and others cannot`() {
        val a = parsePathData("M0 0 L10 10").data
        val b = parsePathData("m5 5 l5 5").data
        val c = parsePathData("M0 0 H10").data
        assertTrue(a.hasSameCommands(b))
        assertFalse(a.hasSameCommands(c))
    }

    @Test
    fun `numbers are stored absolute so relative and absolute forms interpolate`() {
        val data = parsePathData("m5 5 l5 5").data
        assertArrayEquals(doubleArrayOf(5.0, 5.0, 10.0, 10.0), data.args, 1e-9)
    }
}
