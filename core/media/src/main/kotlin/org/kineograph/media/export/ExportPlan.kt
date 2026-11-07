package org.kineograph.media.export

import kotlinx.serialization.Serializable
import org.kineograph.model.FrameSlot
import org.kineograph.model.MAX_FPS
import org.kineograph.model.MIN_FPS
import org.kineograph.model.TimelineIndex
import kotlin.math.roundToInt

@Serializable
public enum class ExportFormat { MP4, GIF }

/** How to export a project. */
@Serializable
public data class ExportConfig(
    val projectId: String,
    val format: ExportFormat,
    /** Output size as a fraction of the canvas. */
    val scale: Float = 1f,
    /** A frame rate that replaces the project's, or null to keep it. Timing stays the same; only the speed changes. */
    val fpsOverride: Int? = null,
    /** First frame to export, counting from 0. */
    val rangeStart: Int? = null,
    /** Last frame to export, counting from 0. */
    val rangeEnd: Int? = null,
    /** How many times to play the animation in the output. */
    val repeatCount: Int = 1,
    val includeBackground: Boolean = true,
    val gifDither: Boolean = true,
    val gifMaxColors: Int = 256,
    /** Whether a GIF repeats forever. Otherwise it plays [repeatCount] times and stops. */
    val gifLoopForever: Boolean = true,
    /** Video bitrate multiplier. 1 is normal. */
    val videoQuality: Float = 1f,
    /**
     * The video's size when a destination insists on a shape: the picture fits inside and bars fill the rest, which is
     * the only way to make, say, a landscape drawing into a vertical video without cutting any of it off. Null means the
     * video takes the picture's own shape. A GIF always takes the picture's shape.
     */
    val frameWidth: Int? = null,
    val frameHeight: Int? = null,
) {
    /** The size this asks for, before what the device can do comes into it. */
    public fun requestedSize(projectWidth: Int, projectHeight: Int): Pair<Int, Int> {
        val width = frameWidth
        val height = frameHeight
        if (format == ExportFormat.MP4 && width != null && height != null) return width to height
        return (projectWidth * scale).roundToInt().coerceAtLeast(1) to (projectHeight * scale).roundToInt().coerceAtLeast(1)
    }
}

/** One tick of an export: which slot is on screen and when. */
public data class PlannedTick(val slotIndex: Int, val presentationUs: Long)

/** Pure timing for an export, so that frame timestamps can be unit-tested. */
public class ExportPlan(
    private val slots: List<FrameSlot>,
    public val fps: Int,
    public val repeatCount: Int,
    /** Encoded ticks per timeline tick. */
    public val ticksPerStep: Int = 1,
) {
    private val index = TimelineIndex(slots)

    /** How many encoded frames the output will have. */
    public val totalTicks: Long = index.totalTicks.toLong() * repeatCount * ticksPerStep

    /** The rate of encoded frames per second. */
    public val encodedFps: Int get() = fps * ticksPerStep

    /** The tick at position [tick] of the output. */
    public fun tickAt(tick: Long): PlannedTick {
        val single = index.totalTicks.toLong()
        val timelineTick = ((tick / ticksPerStep) % single).toInt()
        return PlannedTick(index.slotAtTick(timelineTick), tick * MICROS_PER_SECOND / encodedFps)
    }

    /** The output's total length in microseconds. */
    public val durationUs: Long get() = totalTicks * MICROS_PER_SECOND / encodedFps

    public companion object {
        private const val MICROS_PER_SECOND = 1_000_000L

        /** Applies a config's range, repeat and frame-rate choices to a project's frames. */
        public fun of(frames: List<FrameSlot>, projectFps: Int, config: ExportConfig, ticksPerStep: (fps: Int) -> Int = { 1 }): ExportPlan {
            val last = frames.lastIndex
            val start = (config.rangeStart ?: 0).coerceIn(0, last)
            val end = (config.rangeEnd ?: last).coerceIn(start, last)
            val fps = (config.fpsOverride ?: projectFps).coerceIn(MIN_FPS, MAX_FPS)
            return ExportPlan(frames.subList(start, end + 1), fps, config.repeatCount.coerceAtLeast(1), ticksPerStep(fps))
        }
    }
}
