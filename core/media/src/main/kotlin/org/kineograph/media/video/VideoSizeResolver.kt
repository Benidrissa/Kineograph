package org.kineograph.media.video

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What an encoder says it can do, abstracted from MediaCodecInfo so the sizing rules can be tested off-device. */
public interface EncoderLimits {
    public val widthAlignment: Int
    public val heightAlignment: Int
    public val maxWidth: Int
    public val maxHeight: Int

    /** The smallest size it accepts. Hardware encoders often refuse anything under about 96 pixels. */
    public val minWidth: Int get() = 1
    public val minHeight: Int get() = 1

    public fun isSizeSupported(width: Int, height: Int): Boolean

    public fun areSizeAndRateSupported(width: Int, height: Int, fps: Double): Boolean
}

/** What to encode at, and how the picture fits inside it. */
public data class ResolvedVideoSize(
    /** The size of the video. */
    val width: Int,
    val height: Int,
    /** The size of the picture within it, centred. Different from the video size when bars were added. */
    val contentWidth: Int,
    val contentHeight: Int,
    /** The encoder could not handle what was asked for, so the size was reduced. The user should be told. */
    val wasReduced: Boolean,
)

/** A finished video's size, and where the picture sits in it. */
public data class VideoLayout(
    val width: Int,
    val height: Int,
    /** From canvas pixels to video pixels. */
    val pictureScale: Float,
    val pictureWidth: Int,
    val pictureHeight: Int,
    /**
     * Where the picture's corner goes. These are whole pixels because a half-pixel shift would resample every line of
     * the drawing and soften it, which is far worse than a bar one pixel wider on one side.
     */
    val offsetX: Int,
    val offsetY: Int,
    /** The encoder could not handle what was asked for, so the size was reduced. */
    val wasReduced: Boolean,
) {
    /** Bars fill whatever the picture does not cover. The user should be told about these too. */
    val wasPadded: Boolean get() = pictureWidth != width || pictureHeight != height
}

public object VideoSizeResolver {
    /**
     * Fits a [pictureWidth] × [pictureHeight] canvas into a video as close as the encoder allows to [requestedWidth] ×
     * [requestedHeight]. The picture keeps its shape and is centred, so a request of another shape gets bars, and it is
     * never cropped. Returns null when nothing usable exists.
     */
    public fun layout(
        pictureWidth: Int,
        pictureHeight: Int,
        requestedWidth: Int,
        requestedHeight: Int,
        fps: Int,
        limits: EncoderLimits,
    ): VideoLayout? {
        val size = resolve(requestedWidth, requestedHeight, fps, limits) ?: return null
        val scale = min(size.contentWidth.toFloat() / pictureWidth, size.contentHeight.toFloat() / pictureHeight)
        val shownWidth = (pictureWidth * scale).roundToInt().coerceIn(1, size.width)
        val shownHeight = (pictureHeight * scale).roundToInt().coerceIn(1, size.height)
        return VideoLayout(
            width = size.width,
            height = size.height,
            pictureScale = scale,
            pictureWidth = shownWidth,
            pictureHeight = shownHeight,
            offsetX = (size.width - shownWidth) / 2,
            offsetY = (size.height - shownHeight) / 2,
            wasReduced = size.wasReduced,
        )
    }

    /**
     * Finds the closest size [limits] can encode to [requestedWidth] × [requestedHeight]. Low-cost phones often
     * cap at 1080p or need sizes in multiples of 16, and hardware encoders have a minimum as well.
     *
     * The picture keeps its shape: when rounding to a supported size would change the aspect ratio, the difference
     * becomes thin bars instead, and a picture below the minimum gets bars around it rather than being blown up.
     * Returns null when nothing usable exists.
     */
    public fun resolve(requestedWidth: Int, requestedHeight: Int, fps: Int, limits: EncoderLimits): ResolvedVideoSize? {
        val alignWidth = max(limits.widthAlignment, MIN_ALIGNMENT)
        val alignHeight = max(limits.heightAlignment, MIN_ALIGNMENT)

        var scale = 1.0
        repeat(MAX_ATTEMPTS) {
            val scaledWidth = (requestedWidth * scale).roundToInt().coerceAtLeast(1)
            val scaledHeight = (requestedHeight * scale).roundToInt().coerceAtLeast(1)

            // Up to the next aligned size, so the picture is never scaled for the sake of a few pixels of alignment.
            val width = roundUp(maxOf(scaledWidth, limits.minWidth, MIN_EDGE), alignWidth)
            val height = roundUp(maxOf(scaledHeight, limits.minHeight, MIN_EDGE), alignHeight)

            if (limits.isSizeSupported(width, height) && limits.areSizeAndRateSupported(width, height, fps.toDouble())) {
                return ResolvedVideoSize(
                    width = width,
                    height = height,
                    contentWidth = min(scaledWidth, width),
                    contentHeight = min(scaledHeight, height),
                    wasReduced = scale < 1.0,
                )
            }
            scale =
                if (scale == 1.0) {
                    // Jump straight to the size limits, then keep shrinking if size and rate together are too much.
                    val fitsLimit =
                        min(limits.maxWidth.toDouble() / max(scaledWidth, 1), limits.maxHeight.toDouble() / max(scaledHeight, 1))
                    if (fitsLimit < 1.0) fitsLimit else STEP_DOWN
                } else {
                    scale * STEP_DOWN
                }
        }
        return null
    }

    /** The free size entry takes any number, and the sum here must not wrap around to a negative size. */
    private fun roundUp(value: Int, alignment: Int): Int =
        ((value.coerceAtMost(Int.MAX_VALUE - alignment) + alignment - 1) / alignment) * alignment

    private const val MIN_ALIGNMENT = 2
    private const val MIN_EDGE = 16
    private const val MAX_ATTEMPTS = 24
    private const val STEP_DOWN = 0.85
}

/** How many encoded frames to make per tick. Encoders' rate control often misbehaves at very low frame rates. */
public object VideoFrameRate {
    private const val MIN_ENCODED_FPS = 10

    public fun ticksMultiplier(fps: Int): Int {
        if (fps >= MIN_ENCODED_FPS) return 1
        return (MIN_ENCODED_FPS + fps - 1) / fps.coerceAtLeast(1)
    }

    /** The rate the encoder actually runs at for an animation at [fps]. */
    public fun encodedFps(fps: Int): Int = fps * ticksMultiplier(fps)
}

/** A sensible bitrate for simple drawn animation. [quality] scales it: 1 is normal. */
public fun videoBitrate(width: Int, height: Int, fps: Int, quality: Float): Int {
    val pixelsPerSecond = width.toLong() * height.toLong() * fps.coerceAtLeast(1)
    val bits = pixelsPerSecond * BITS_PER_PIXEL * quality
    return bits.toLong().coerceIn(MIN_BITRATE, MAX_BITRATE).toInt()
}

private const val BITS_PER_PIXEL = 0.14f
private const val MIN_BITRATE = 400_000L
private const val MAX_BITRATE = 60_000_000L
