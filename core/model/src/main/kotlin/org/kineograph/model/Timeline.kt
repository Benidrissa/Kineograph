package org.kineograph.model

/**
 * Maps between timeline ticks and frame slots.
 *
 * One tick lasts `1 / fps` seconds. A slot with `hold = n` covers `n` consecutive ticks, so holds
 * change how long a drawing stays on screen without any copies being made.
 */
public class TimelineIndex(frames: List<FrameSlot>) {
    private val startTicks = IntArray(frames.size + 1)

    init {
        var tick = 0
        frames.forEachIndexed { index, slot ->
            startTicks[index] = tick
            tick += maxOf(1, slot.hold)
        }
        startTicks[frames.size] = tick
    }

    /** The number of ticks in the whole timeline. */
    public val totalTicks: Int get() = startTicks.last()

    /** The number of slots on the timeline. */
    public val slotCount: Int get() = startTicks.size - 1

    /** The first tick of slot [slotIndex]. */
    public fun startTick(slotIndex: Int): Int = startTicks[slotIndex.coerceIn(0, slotCount)]

    /** The slot on screen at [tick]. Ticks past either end clamp to the first or last slot. */
    public fun slotAtTick(tick: Int): Int {
        if (slotCount == 0) return -1
        if (tick <= 0) return 0
        if (tick >= totalTicks) return slotCount - 1
        var low = 0
        var high = slotCount - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (startTicks[mid] <= tick) low = mid else high = mid - 1
        }
        return low
    }
}

public object Timeline {
    /** How long the whole timeline lasts in milliseconds at [fps]. */
    public fun durationMs(frames: List<FrameSlot>, fps: Int): Long {
        val ticks = frames.sumOf { maxOf(1, it.hold).toLong() }
        return ticks * 1000L / fps.coerceIn(MIN_FPS, MAX_FPS)
    }

    /** The presentation time of [tick] in microseconds. Computed from the tick so errors never add up. */
    public fun presentationTimeUs(tick: Long, fps: Int): Long = tick * 1_000_000L / fps.coerceIn(MIN_FPS, MAX_FPS)

    /** Formats a duration as `mm:ss`, rounding up so a half-second animation does not read `00:00`. */
    public fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs + 999) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    /**
     * Converts time spent playing into the tick to show, given the playback [mode], for a range of
     * [rangeTicks] ticks. [elapsedTicks] counts up from zero. The result is relative to the range start,
     * or null when play-once has run past the end.
     */
    public fun playbackTick(elapsedTicks: Long, rangeTicks: Int, mode: PlaybackMode): Int? {
        if (rangeTicks <= 0) return null
        val elapsed = maxOf(0L, elapsedTicks)
        return when (mode) {
            PlaybackMode.LOOP -> {
                (elapsed % rangeTicks).toInt()
            }

            PlaybackMode.ONCE -> {
                if (elapsed >= rangeTicks) null else elapsed.toInt()
            }

            PlaybackMode.PING_PONG -> {
                // The end ticks are shown once per cycle, so a cycle is 2n - 2 ticks long.
                if (rangeTicks == 1) {
                    0
                } else {
                    val cycle = 2L * rangeTicks - 2
                    val position = elapsed % cycle
                    (if (position < rangeTicks) position else cycle - position).toInt()
                }
            }
        }
    }
}
