package org.kineograph.media.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import org.kineograph.media.export.ExportNotSupportedException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** The calls made on the muxer, so that one failing can be tried off-device. */
internal interface SampleMuxer {
    fun addTrack(format: MediaFormat): Int

    fun start()

    fun writeSampleData(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo)

    fun stop()

    fun release()
}

internal class PlatformMuxer(outputFile: File) : SampleMuxer {
    private val muxer = MediaMuxer(outputFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    override fun addTrack(format: MediaFormat): Int = muxer.addTrack(format)

    override fun start() = muxer.start()

    override fun writeSampleData(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) = muxer.writeSampleData(track, buffer, info)

    override fun stop() = muxer.stop()

    override fun release() = muxer.release()
}

/**
 * Writes the encoder's output to the file, and makes sure a file that did not make it is never reported as finished.
 *
 * That takes more than catching errors. Android 8 ignores failed writes altogether: on a full disk the muxer carries on and
 * stops as if nothing happened, leaving a video that will not play. So free space is checked before each sample, while a
 * friendly message is still possible, and the file's length is checked at the end.
 */
internal class Mp4Writer(
    private val muxer: SampleMuxer,
    private val outputFile: File,
    /** Room to keep free for what the muxer holds back: a second or so of video, and the index it writes when it stops. */
    private val spareBytes: Long,
    private val usableSpace: () -> Long = { outputFile.absoluteFile.parentFile?.usableSpace ?: Long.MAX_VALUE },
) {
    private var track = -1
    private var writtenBytes = 0L

    /** How many samples reached the muxer. */
    var writtenSamples: Int = 0
        private set

    /** Whether it has been started and not yet stopped. */
    var isStarted: Boolean = false
        private set

    fun start(format: MediaFormat) {
        if (isStarted) throw ExportNotSupportedException("The encoder changed format mid-stream")
        track = muxer.addTrack(format)
        muxer.start()
        isStarted = true
    }

    fun write(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!isStarted) throw ExportNotSupportedException("The encoder sent data before its format")
        if (usableSpace() < info.size + spareBytes) throw IOException("Storage is running out")
        try {
            muxer.writeSampleData(track, buffer, info)
        } catch (e: IllegalStateException) {
            // This is how newer Android reports a write that failed. Only the muxer's calls are caught this way:
            // a failing encoder throws the same type, and that has nothing to do with storage.
            throw IOException("Could not write the video", e)
        }
        writtenBytes += info.size
        writtenSamples++
    }

    /** Stops the muxer and makes sure the file is whole. */
    fun finish() {
        if (writtenSamples == 0) throw ExportNotSupportedException("The encoder produced no frames")
        isStarted = false
        try {
            muxer.stop()
        } catch (e: IllegalStateException) {
            throw IOException("Could not finish the video", e)
        }
        if (outputFile.length() < writtenBytes) throw IOException("The video was cut short")
    }

    /** Safe at any point, and never throws, so it cannot hide the error that is already on its way to the user. */
    fun release() {
        if (isStarted) {
            isStarted = false
            try {
                muxer.stop()
            } catch (_: IllegalStateException) {
                // Something else has already gone wrong, and the worker deletes the file.
            }
        }
        try {
            muxer.release()
        } catch (_: IllegalStateException) {
            // Android 8 tries a failed stop again here.
        }
    }
}
