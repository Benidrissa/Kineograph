package org.kineograph.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kineograph.feature.editor.PlaybackWindow.firstWanted
import org.kineograph.feature.editor.PlaybackWindow.timeUntilShown

class PlaybackWindowTest {
    /** Every slot in the order it would be decoded from [head], when none is ready. */
    private fun decodeOrder(head: Playhead, slots: Int, capacity: Int): List<Int> {
        val order = ArrayList<Int>()
        while (true) {
            order += firstWanted(head, slots, capacity) { it !in order } ?: return order
        }
    }

    private fun forward(slot: Int) = Playhead(slot, reversed = false, pingPong = true)

    private fun back(slot: Int) = Playhead(slot, reversed = true, pingPong = true)

    // ----- Which frames to decode -----

    @Test
    fun `looping looks ahead and wraps around`() {
        assertEquals(listOf(8, 9, 0, 1), decodeOrder(Playhead(8), 10, 4))
    }

    @Test
    fun `ping-pong going forward looks ahead`() {
        assertEquals(listOf(2, 3, 4, 5), decodeOrder(forward(2), 10, 4))
    }

    @Test
    fun `ping-pong near the far end looks back instead of wrapping to the start`() {
        assertEquals(listOf(8, 9, 7, 6), decodeOrder(forward(8), 10, 4))
    }

    @Test
    fun `ping-pong coming back looks down`() {
        assertEquals(listOf(7, 6, 5, 4), decodeOrder(back(7), 10, 4))
    }

    @Test
    fun `ping-pong near the start looks forward again instead of wrapping to the end`() {
        assertEquals(listOf(1, 0, 2, 3), decodeOrder(back(1), 10, 4))
    }

    @Test
    fun `nothing needs decoding again just because the direction changed`() {
        val turningAtEnd = decodeOrder(forward(9), 10, 4).toSet()
        assertEquals(turningAtEnd, decodeOrder(back(9), 10, 4).toSet())
        val turningAtStart = decodeOrder(back(0), 10, 4).toSet()
        assertEquals(turningAtStart, decodeOrder(forward(0), 10, 4).toSet())
    }

    @Test
    fun `a cache that holds everything wants everything, whichever way play is going`() {
        assertEquals(setOf(0, 1, 2), decodeOrder(forward(1), 3, 50).toSet())
        assertEquals(setOf(0, 1, 2), decodeOrder(back(1), 3, 50).toSet())
        assertEquals(setOf(0, 1), decodeOrder(forward(1), 2, 4).toSet())
        assertEquals(setOf(0, 1), decodeOrder(back(0), 2, 4).toSet())
    }

    @Test
    fun `a single frame is simple`() {
        assertEquals(listOf(0), decodeOrder(forward(0), 1, 4))
        assertEquals(listOf(0), decodeOrder(Playhead(0), 1, 4))
    }

    @Test
    fun `nothing is wanted once everything in the window is ready`() {
        assertNull(firstWanted(forward(0), 10, 4) { false })
    }

    // ----- What to throw out -----

    @Test
    fun `looping counts the distance ahead`() {
        assertEquals(0, timeUntilShown(5, Playhead(5), 10))
        assertEquals(9, timeUntilShown(4, Playhead(5), 10))
        assertEquals(1, timeUntilShown(0, Playhead(9), 10))
    }

    @Test
    fun `ping-pong counts the way to a slot and, if needed, the way back from the turn`() {
        assertEquals(2, timeUntilShown(5, forward(3), 10))
        assertEquals(6 + 7, timeUntilShown(2, forward(3), 10))
        assertEquals(2, timeUntilShown(1, back(3), 10))
        assertEquals(3 + 5, timeUntilShown(5, back(3), 10))
    }

    @Test
    fun `the frames just played on the way forward are kept for the way back`() {
        // Around the far turn, what was just shown is sooner than anything at the start.
        val head = forward(9)
        assertTrue(timeUntilShown(8, head, 10) < timeUntilShown(0, head, 10))
    }

    // ----- All of it together -----

    /** Plays a whole cycle with room for [capacity] frames and [decodesPerFrame] decoded per frame shown. */
    private fun framesMissed(slots: Int, capacity: Int, decodesPerFrame: Int): Int {
        val cache = HashSet<Int>()
        fun decode(head: Playhead) {
            repeat(decodesPerFrame) {
                val next = firstWanted(head, slots, capacity) { it !in cache } ?: return
                while (cache.size >= capacity) {
                    cache.remove(cache.filter { it != next }.maxBy { timeUntilShown(it, head, slots) })
                }
                cache += next
            }
        }
        var head = forward(0)
        // A head start, as playback gets.
        repeat(capacity) { decode(head) }
        var missed = 0
        val cycle = 2 * slots - 2
        repeat(3 * cycle) { position ->
            val place = position % cycle
            val slot = if (place < slots) place else cycle - place
            head = Playhead(slot, reversed = place >= slots - 1, pingPong = true)
            if (slot !in cache) missed++
            decode(head)
        }
        return missed
    }

    @Test
    fun `ping-pong with a cache smaller than the animation misses nothing in either direction`() {
        assertEquals(0, framesMissed(slots = 300, capacity = 109, decodesPerFrame = 2))
        assertEquals(0, framesMissed(slots = 300, capacity = 4, decodesPerFrame = 2))
        assertEquals(0, framesMissed(slots = 20, capacity = 19, decodesPerFrame = 2))
    }

    @Test
    fun `at the turn ping-pong decodes nothing, because the frames coming back are already there`() {
        val cache = HashSet<Int>()
        val capacity = 10
        val slots = 30
        // Arrive at the far end with the window full.
        val head = forward(slots - 2)
        repeat(capacity) {
            val next = firstWanted(head, slots, capacity) { it !in cache }
            cache += checkNotNull(next)
        }
        assertNull(firstWanted(forward(slots - 1), slots, capacity) { it !in cache })
        assertNull(firstWanted(back(slots - 1), slots, capacity) { it !in cache })
        // From here on it only wants what comes next on the way back.
        assertEquals(slots - 2 - capacity + 1, firstWanted(back(slots - 2), slots, capacity) { it !in cache })
    }

    @Test
    fun `looping with a cache smaller than the animation misses nothing either`() {
        val slots = 30
        val capacity = 8
        val cache = HashSet<Int>()
        var missed = 0
        fun decode(head: Playhead) {
            repeat(2) {
                val next = firstWanted(head, slots, capacity) { it !in cache } ?: return
                while (cache.size >= capacity) cache.remove(cache.filter { it != next }.maxBy { timeUntilShown(it, head, slots) })
                cache += next
            }
        }
        repeat(capacity) { decode(Playhead(0)) }
        for (position in 0 until 3 * slots) {
            val head = Playhead(position % slots)
            if (head.slot !in cache) missed++
            decode(head)
        }
        assertEquals(0, missed)
    }
}
