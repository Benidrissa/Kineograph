package org.kineograph.media.video

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
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
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val muxer: MediaMuxer
    private val renderer: EglSurfaceRenderer
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxerStarted = false
    private var finished = false

    /** How many frames reached the file. */
    var writtenFrames: Int = 0
        private set

    init {
        configure(width, height, fps, bitrate)
        val inputSurface = codec.createInputSurface()
        try {
            renderer = EglSurfaceRenderer(inputSurface)
            codec.start()
            muxer = MediaMuxer(outputFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: IOException) {
            inputSurface.release()
            codec.release()
            throw e
        }
    }

    /**
     * Configures the encoder, asking for Baseline when it offers that. Baseline has no B-frames, so frames leave the
     * encoder in order. Older Android refuses a profile unless a level comes with it, so the level comes from what the
     * encoder itself reports. Some encoders still refuse, and any encoder can make a fine video without being told.
     */
    private fun configure(width: Int, height: Int, fps: Int, bitrate: Int) {
        val format =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL_SECONDS)
            }
        val baseline = baselineProfileLevel()
        if (baseline != null) {
            format.setInteger(MediaFormat.KEY_PROFILE, baseline.profile)
            format.setInteger(MediaFormat.KEY_LEVEL, baseline.level)
            if (tryConfigure(format)) return
            codec.reset()
            format.removeKey(MediaFormat.KEY_PROFILE)
            format.removeKey(MediaFormat.KEY_LEVEL)
        }
        if (!tryConfigure(format)) {
            codec.release()
            throw ExportNotSupportedException("The encoder refused ${width}x$height")
        }
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

    /** Flushes the encoder and finishes the file. */
    fun finish() {
        if (finished) return
        finished = true
        codec.signalEndOfInputStream()
        drain(endOfStream = true)
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
        if (muxerStarted) {
            try {
                muxer.stop()
            } catch (_: IllegalStateException) {
                // The muxer refuses to stop when no frames were written. The caller deletes such a file.
            }
        }
        muxer.release()
    }

    private fun drain(endOfStream: Boolean) {
        var idleSpins = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) DRAIN_TIMEOUT_US else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (++idleSpins > MAX_IDLE_DRAINS) throw IOException("The encoder stopped responding")
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) throw IOException("The encoder changed format mid-stream")
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }

                index >= 0 -> {
                    idleSpins = 0
                    val buffer = codec.getOutputBuffer(index) ?: throw IOException("The encoder returned no data")
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // The configuration is already in the format the muxer was given.
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0) {
                        if (!muxerStarted) throw IOException("The encoder sent data before its format")
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                        writtenFrames++
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private companion object {
        const val KEYFRAME_INTERVAL_SECONDS = 1
        const val NANOS_PER_MICRO = 1000L
        const val DRAIN_TIMEOUT_US = 10_000L
        const val MAX_IDLE_DRAINS = 1000
    }
}
