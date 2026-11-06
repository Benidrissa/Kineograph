package org.kineograph.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnionSkinPlanTest {
    private fun settings(previous: Int = 1, next: Int = 0, wrap: Boolean = false, enabled: Boolean = true) =
        OnionSkinSettings(
            enabled = enabled,
            previousFrames = previous,
            nextFrames = next,
            opacity = 0.5f,
            falloff = 0.5f,
            wrapAround = wrap,
        )

    @Test
    fun `off means no ghosts`() {
        assertTrue(planOnionSkin(settings(enabled = false), 5, 2).isEmpty())
    }

    @Test
    fun `a single frame has nothing to ghost`() {
        assertTrue(planOnionSkin(settings(), 1, 0).isEmpty())
    }

    @Test
    fun `shows the previous frame by default`() {
        val ghosts = planOnionSkin(settings(), 5, 2)
        assertEquals(listOf(1), ghosts.map { it.frameIndex })
        assertTrue(ghosts[0].isPrevious)
    }

    @Test
    fun `no previous ghost on the first frame without wrap`() {
        assertTrue(planOnionSkin(settings(), 5, 0).isEmpty())
    }

    @Test
    fun `wrap shows the last frame from the first`() {
        assertEquals(listOf(4), planOnionSkin(settings(wrap = true), 5, 0).map { it.frameIndex })
    }

    @Test
    fun `opacity falls off with distance and far ghosts come first`() {
        val ghosts = planOnionSkin(settings(previous = 3), 10, 5)
        assertEquals(listOf(2, 3, 4), ghosts.map { it.frameIndex })
        assertEquals(listOf(0.125f, 0.25f, 0.5f), ghosts.map { it.opacity })
    }

    @Test
    fun `wrap never shows a frame twice or shows the current frame`() {
        val ghosts = planOnionSkin(settings(previous = 5, next = 5, wrap = true), 3, 1)
        assertEquals(2, ghosts.size)
        assertEquals(setOf(0, 2), ghosts.map { it.frameIndex }.toSet())
    }

    @Test
    fun `counts above the limit are clamped`() {
        assertEquals(5, planOnionSkin(settings(previous = 50), 100, 50).size)
    }

    @Test
    fun `next ghosts are marked as next`() {
        val ghosts = planOnionSkin(settings(previous = 0, next = 2), 10, 5)
        assertEquals(listOf(7, 6), ghosts.map { it.frameIndex })
        assertTrue(ghosts.none { it.isPrevious })
    }

    @Test
    fun `an out of range current frame is safe`() {
        assertTrue(planOnionSkin(settings(), 5, 9).isEmpty())
    }
}
