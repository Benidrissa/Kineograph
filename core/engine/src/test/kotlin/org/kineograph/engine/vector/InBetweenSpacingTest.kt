package org.kineograph.engine.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InBetweenSpacingTest {
    private fun at(index: Int, count: Int, spacing: InBetweenSpacing) = InBetweens.position(index, count, spacing)

    @Test
    fun `every spacing keeps the in-betweens between the drawings, in order`() {
        for (spacing in InBetweenSpacing.entries) {
            for (count in 1..24) {
                var last = 0f
                for (k in 1..count) {
                    val t = at(k, count, spacing)
                    assertTrue("$spacing $k of $count", t > last && t < 1f)
                    last = t
                }
            }
        }
    }

    @Test
    fun `even spacing is even`() {
        assertEquals(0.25f, at(1, 3, InBetweenSpacing.EVEN), 1e-6f)
        assertEquals(0.5f, at(2, 3, InBetweenSpacing.EVEN), 1e-6f)
        assertEquals(0.75f, at(3, 3, InBetweenSpacing.EVEN), 1e-6f)
    }

    @Test
    fun `a slow start bunches up near the first drawing, and a slow end near the second`() {
        for (count in 2..12) {
            val startFirstGap = at(1, count, InBetweenSpacing.SLOW_START)
            val startLastGap = 1f - at(count, count, InBetweenSpacing.SLOW_START)
            assertTrue(startFirstGap < startLastGap)
            val endFirstGap = at(1, count, InBetweenSpacing.SLOW_END)
            val endLastGap = 1f - at(count, count, InBetweenSpacing.SLOW_END)
            assertTrue(endLastGap < endFirstGap)
        }
    }

    @Test
    fun `a slow start and end is the same seen from either drawing`() {
        for (count in 1..12) {
            for (k in 1..count) {
                assertEquals(1f, at(k, count, InBetweenSpacing.SLOW_BOTH) + at(count + 1 - k, count, InBetweenSpacing.SLOW_BOTH), 1e-5f)
            }
        }
        assertTrue(at(1, 5, InBetweenSpacing.SLOW_BOTH) < at(1, 5, InBetweenSpacing.EVEN))
    }
}
