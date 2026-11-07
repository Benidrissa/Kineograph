package org.kineograph.media.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/** The real device's H.264 encoders. */
public object PlatformEncoder {
    /** Limits of the device's best H.264 encoder, or null if it has none. */
    public fun avcLimits(): EncoderLimits? {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        // Hardware encoders are faster and usually have the higher limits, so they get asked first.
        val candidates =
            infos
                .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } }
                .sortedByDescending { info -> if (info.isHardwareEncoder) 1 else 0 }
        val best = candidates.firstOrNull() ?: return null
        val capabilities =
            try {
                best.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
            } catch (_: IllegalArgumentException) {
                null
            } ?: return null
        return RealLimits(capabilities)
    }

    /** Whether this device can record a video of this size at all, which decides whether to offer 4K. */
    public fun canEncode(width: Int, height: Int, fps: Int): Boolean =
        avcLimits()?.let { VideoSizeResolver.resolve(width, height, fps, it)?.wasReduced == false } ?: false

    /** [canEncode] at an ordinary animation frame rate. */
    public fun canEncodeAtDefaultRate(width: Int, height: Int): Boolean = canEncode(width, height, DEFAULT_CHECK_FPS)

    private const val DEFAULT_CHECK_FPS = 24

    /**
     * MediaCodecInfo gained isHardwareAccelerated in Android 10. This must not share that name: a member always wins over
     * an extension, so the version check would silently never run and older phones would crash.
     */
    private val MediaCodecInfo.isHardwareEncoder: Boolean
        get() =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                isHardwareAccelerated
            } else {
                !name.startsWith("OMX.google.") && !name.startsWith("c2.android.")
            }

    private class RealLimits(private val capabilities: MediaCodecInfo.VideoCapabilities) : EncoderLimits {
        override val widthAlignment: Int get() = capabilities.widthAlignment
        override val heightAlignment: Int get() = capabilities.heightAlignment
        override val maxWidth: Int get() = capabilities.supportedWidths.upper
        override val maxHeight: Int get() = capabilities.supportedHeights.upper
        override val minWidth: Int get() = capabilities.supportedWidths.lower
        override val minHeight: Int get() = capabilities.supportedHeights.lower

        override fun isSizeSupported(width: Int, height: Int): Boolean = capabilities.isSizeSupported(width, height)

        override fun areSizeAndRateSupported(width: Int, height: Int, fps: Double): Boolean = capabilities.areSizeAndRateSupported(
            width,
            height,
            fps,
        )
    }
}
