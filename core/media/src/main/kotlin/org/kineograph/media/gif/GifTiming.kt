package org.kineograph.media.gif

/**
 * GIF stores delays in whole hundredths of a second, so most frame rates cannot be hit exactly. This works from
 * the running total, so rounding errors never build up: 12 FPS gives 8, 8, 9, 8, 8, 9… and stays on time.
 * Viewers mishandle delays below 2 (many treat them as 10), so faster animations drop frames instead,
 * with the frames that remain still on time.
 */
public class GifTiming(private val fps: Int) {
    private var ticksDone = 0L
    private var centisecondsWritten = 0L

    /**
     * Call once per tick. Returns the delay to give the frame on screen once it has lasted through this tick, or 0 if the
     * tick is too short to show by itself and should be merged into the next.
     */
    public fun nextDelay(): Int {
        ticksDone++
        val target = ticksDone * 100L / fps
        val delay = (target - centisecondsWritten).toInt()
        if (delay < MIN_DELAY) return 0
        centisecondsWritten += delay
        return delay
    }

    /** The delay to close out the very last frame, if the final tick was merged away. */
    public fun finalDelay(): Int {
        val target = ticksDone * 100L / fps
        val remaining = (target - centisecondsWritten).toInt()
        centisecondsWritten = target
        return maxOf(remaining, MIN_DELAY)
    }

    private companion object {
        const val MIN_DELAY = 2
    }
}
