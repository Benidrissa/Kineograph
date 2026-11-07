package org.kineograph.media

import android.media.MediaCodec
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kineograph.media.export.ExportNotSupportedException
import org.kineograph.media.video.Mp4Writer
import org.kineograph.media.video.SampleMuxer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** A file that did not come out whole must never be reported as finished, however the muxer happens to let that show. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Mp4WriterTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** Behaves like the real muxer on whichever Android version the test needs. */
    private class FakeMuxer(private val file: File) : SampleMuxer {
        var stopFails = false
        var writeFails = false
        var releaseFails = false
        var dropsWrites = false
        var stops = 0
        var released = false

        override fun addTrack(format: MediaFormat) = 0

        override fun start() = Unit

        override fun writeSampleData(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            check(!writeFails) { "writeSampleData returned an error" }
            if (!dropsWrites) file.appendBytes(ByteArray(info.size))
        }

        override fun stop() {
            stops++
            check(!stopFails) { "Failed to stop the muxer" }
        }

        override fun release() {
            released = true
            check(!releaseFails) { "Failed to stop the muxer" }
        }
    }

    private val file by lazy { temp.newFile("out.mp4") }
    private val muxer by lazy { FakeMuxer(file) }
    private var space = Long.MAX_VALUE
    private val writer by lazy { Mp4Writer(muxer, file, spareBytes = 1000L) { space } }

    private val buffer = ByteBuffer.allocate(100)

    private fun sample(bytes: Int = 100) = MediaCodec.BufferInfo().apply { set(0, bytes, 0L, 0) }

    private fun started(): Mp4Writer = writer.also { it.start(MediaFormat()) }

    @Test
    fun `a good file finishes`() {
        started().write(buffer, sample())
        writer.finish()
        writer.release()
        assertEquals(1, writer.writtenSamples)
        assertEquals(1, muxer.stops)
        assertTrue(muxer.released)
    }

    @Test
    fun `a muxer that fails to stop is a failure, not a finished video`() {
        started().write(buffer, sample())
        muxer.stopFails = true
        val failure = assertThrows(IOException::class.java) { writer.finish() }
        assertFalse(failure is ExportNotSupportedException)
    }

    @Test
    fun `stopping is not tried again after it failed`() {
        started().write(buffer, sample())
        muxer.stopFails = true
        assertThrows(IOException::class.java) { writer.finish() }
        writer.release()
        assertEquals(1, muxer.stops)
    }

    @Test
    fun `android 8 failing again on release cannot hide the real error`() {
        started().write(buffer, sample())
        muxer.releaseFails = true
        writer.release()
        assertTrue(muxer.released)
    }

    @Test
    fun `a file shorter than what went in is a failure, as on android 8 when the disk fills`() {
        muxer.dropsWrites = true
        started().write(buffer, sample())
        val failure = assertThrows(IOException::class.java) { writer.finish() }
        assertFalse(failure is ExportNotSupportedException)
    }

    @Test
    fun `running out of space stops the export while it can still say why`() {
        started().write(buffer, sample())
        space = 1050L
        val failure = assertThrows(IOException::class.java) { writer.write(buffer, sample()) }
        assertFalse(failure is ExportNotSupportedException)
        assertEquals(1, writer.writtenSamples)
    }

    @Test
    fun `just enough space is enough`() {
        started()
        space = 1100L
        writer.write(buffer, sample())
        assertEquals(1, writer.writtenSamples)
    }

    @Test
    fun `a failed write is about storage`() {
        started()
        muxer.writeFails = true
        val failure = assertThrows(IOException::class.java) { writer.write(buffer, sample()) }
        assertFalse(failure is ExportNotSupportedException)
    }

    @Test
    fun `an encoder that made nothing is not told to free up storage`() {
        started()
        assertThrows(ExportNotSupportedException::class.java) { writer.finish() }
    }

    @Test
    fun `an encoder that sends data before its format is not told to free up storage`() {
        assertThrows(ExportNotSupportedException::class.java) { writer.write(buffer, sample()) }
    }

    @Test
    fun `an encoder that changes format half way is not told to free up storage`() {
        started()
        assertThrows(ExportNotSupportedException::class.java) { writer.start(MediaFormat()) }
    }

    @Test
    fun `releasing a muxer that never started does not stop it`() {
        writer.release()
        assertEquals(0, muxer.stops)
        assertTrue(muxer.released)
    }

    @Test
    fun `releasing after a failure stops what was started and hides nothing`() {
        started().write(buffer, sample())
        muxer.stopFails = true
        writer.release()
        assertEquals(1, muxer.stops)
        assertTrue(muxer.released)
    }
}
