package org.kineograph.media.video

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import org.kineograph.media.export.ExportNotSupportedException
import java.io.File
import java.io.IOException

/**
 * Encodes bitmaps to an MP4 file with the device's H.264 encoder: bitmap, OpenGL texture, encoder's input surface,
 * MediaCodec, MediaMuxer. Must be used from one thread.
 *
 * Choosing this over Media3 Transformer keeps the APK small and exposes the exact timing needed here. See
 * docs/decisions.md.
 */
internal class H264Encoder(outputFile: File, private val width: Int, private val height: Int, fps: Int, bitrate: Int) {
    private val codec: MediaCodec =
        try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        } catch (e: IOException) {
            throw ExportNotSupportedException("Could not create an encoder: ${e.message}")
        }
    private val inputSurface: Surface
    private val renderer: EglSurfaceRenderer
    private val writer: Mp4Writer
    private val bufferInfo = MediaCodec.BufferInfo()
    private var finished = false

    init {
        // Whatever fails here, there is no encoder for the caller to release, so everything made so far goes back now.
        // The renderer goes before the surface it draws on. The failure carries on unchanged, since its type decides
        // what the user is told.
        var surface: Surface? = null
        var createdRenderer: EglSurfaceRenderer? = null
        try {
            configure(width, height, fps, bitrate)
            surface = codec.createInputSurface()
            createdRenderer = EglSurfaceRenderer(surface)
            codec.start()
            writer = Mp4Writer(PlatformMuxer(outputFile), outputFile, bitrate / BITS_PER_BYTE + MUXER_INDEX_BYTES)
            inputSurface = surface
            renderer = createdRenderer
        } catch (failure: Throwable) {
            createdRenderer?.release()
            surface?.release()
            codec.release()
            throw failure
        }
    }

    /**
     * Configures the encoder, asking for Baseline when it offers that. Baseline has no B-frames, so frames leave the
     * encoder in order. Older Android refuses a profile unless a level comes with it, so the level comes from what the
     * encoder itself reports. Some encoders still refuse, and any encoder can make a fine video without being told.
     */
    private fun configure(width: Int, height: Int, fps: Int, bitrate: Int) {
        val baseline = baselineProfileLevel()
        if (baseline != null) {
            if (tryConfigure(videoFormat(width, height, fps, bitrate, baseline))) return
            codec.reset()
        }
        val unspecified = videoFormat(width, height, fps, bitrate, profile = null)
        if (!tryConfigure(unspecified)) throw ExportNotSupportedException("The encoder refused ${width}x$height")
    }

    private fun tryConfigure(format: MediaFormat): Boolean =
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            true
        } catch (_: IllegalStateException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }

    /** The encoder's own highest-level Baseline entry, or null if it does not list one. */
    private fun baselineProfileLevel(): MediaCodecInfo.CodecProfileLevel? =
        try {
            codec.codecInfo
                .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                .profileLevels
                .filter { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline }
                .maxByOrNull { it.level }
        } catch (_: IllegalArgumentException) {
            null
        }

    /** Sends one frame. [frame] must be exactly the video's size. */
    fun addFrame(frame: Bitmap, presentationTimeUs: Long) {
        drain(endOfStream = false)
        renderer.render(frame, width, height, presentationTimeUs * NANOS_PER_MICRO)
    }

    /** Flushes the encoder and finishes the file, or throws if the file did not come out whole. */
    fun finish() {
        if (finished) return
        finished = true
        codec.signalEndOfInputStream()
        drain(endOfStream = true)
        writer.finish()
    }

    /** Frees everything. Safe to call at any point, including after a failure. */
    fun release() {
        try {
            codec.stop()
        } catch (_: IllegalStateException) {
            // It may never have started, or may have failed. Either way it is about to be released.
        }
        codec.release()
        renderer.release()
        inputSurface.release()
        writer.release()
    }

    private fun drain(endOfStream: Boolean) {
        var idleSpins = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) DRAIN_TIMEOUT_US else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (++idleSpins > MAX_IDLE_DRAINS) throw ExportNotSupportedException("The encoder stopped responding")
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    writer.start(codec.outputFormat)
                }

                index >= 0 -> {
                    idleSpins = 0
                    val buffer = codec.getOutputBuffer(index) ?: throw ExportNotSupportedException("The encoder returned no data")
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // The configuration is already in the format the muxer was given.
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0) {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        writer.write(buffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private companion object {
        const val NANOS_PER_MICRO = 1000L
        const val DRAIN_TIMEOUT_US = 10_000L
        const val MAX_IDLE_DRAINS = 1000
        const val BITS_PER_BYTE = 8L
        const val MUXER_INDEX_BYTES = 1024L * 1024L
    }
}

private const val KEYFRAME_INTERVAL_SECONDS = 1

/**
 * A new format for each attempt, with or without a profile. Taking a profile back out of a format needs MediaFormat.removeKey,
 * which only exists from Android 10, and an encoder refusing Baseline is just as possible on the older phones.
 */
internal fun videoFormat(width: Int, height: Int, fps: Int, bitrate: Int, profile: MediaCodecInfo.CodecProfileLevel?): MediaFormat =
    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL_SECONDS)
        if (profile != null) {
            setInteger(MediaFormat.KEY_PROFILE, profile.profile)
            setInteger(MediaFormat.KEY_LEVEL, profile.level)
        }
    }
