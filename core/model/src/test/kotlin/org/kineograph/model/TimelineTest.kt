package org.kineograph.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineTest {
    private val slots = listOf(FrameSlot("a", 1), FrameSlot("b", 3), FrameSlot("c", 2))

    @Test
    fun `total ticks sum the holds`() {
        assertEquals(6, TimelineIndex(slots).totalTicks)
    }

    @Test
    fun `slot lookup honours holds`() {
        val index = TimelineIndex(slots)
        assertEquals(listOf(0, 1, 1, 1, 2, 2), (0 until 6).map(index::slotAtTick))
    }

    @Test
    fun `slot lookup clamps out of range ticks`() {
        val index = TimelineIndex(slots)
        assertEquals(0, index.slotAtTick(-5))
        assertEquals(2, index.slotAtTick(100))
    }

    @Test
    fun `start ticks follow holds`() {
        val index = TimelineIndex(slots)
        assertEquals(listOf(0, 1, 4), (0 until 3).map(index::startTick))
    }

    @Test
    fun `an empty timeline has no slot`() {
        assertEquals(-1, TimelineIndex(emptyList()).slotAtTick(0))
    }

    @Test
    fun `holds below one count as one`() {
        assertEquals(2, TimelineIndex(listOf(FrameSlot("a", 0), FrameSlot("b", -3))).totalTicks)
    }

    @Test
    fun `twelve frames at twelve fps last one second`() {
        val frames = List(12) { FrameSlot("f$it") }
        assertEquals(1000L, Timeline.durationMs(frames, 12))
    }

    @Test
    fun `presentation times do not accumulate rounding error`() {
        // 1/24 s is not a whole number of microseconds; tick 24 must still land exactly on one second.
        assertEquals(1_000_000L, Timeline.presentationTimeUs(24, 24))
        assertEquals(41_666L, Timeline.presentationTimeUs(1, 24))
        assertEquals(3_600_000_000L, Timeline.presentationTimeUs(86_400, 24))
    }

    @Test
    fun `durations are formatted and round up`() {
        assertEquals("00:01", Timeline.formatDuration(500))
        assertEquals("00:02", Timeline.formatDuration(2000))
        assertEquals("01:05", Timeline.formatDuration(65_000))
        assertEquals("00:00", Timeline.formatDuration(0))
    }

    @Test
    fun `loop playback wraps`() {
        assertEquals(listOf(0, 1, 2, 0, 1), (0L until 5L).map { Timeline.playbackTick(it, 3, PlaybackMode.LOOP) })
    }

    @Test
    fun `play once stops at the end`() {
        assertEquals(2, Timeline.playbackTick(2, 3, PlaybackMode.ONCE))
        assertNull(Timeline.playbackTick(3, 3, PlaybackMode.ONCE))
    }

    @Test
    fun `ping pong does not repeat the end frames`() {
        assertEquals(
            listOf(0, 1, 2, 3, 2, 1, 0, 1),
            (0L until 8L).map { Timeline.playbackTick(it, 4, PlaybackMode.PING_PONG) },
        )
    }

    @Test
    fun `ping pong handles a single tick`() {
        assertEquals(0, Timeline.playbackTick(9, 1, PlaybackMode.PING_PONG))
    }

    @Test
    fun `empty range has nothing to play`() {
        assertNull(Timeline.playbackTick(0, 0, PlaybackMode.LOOP))
    }
}
