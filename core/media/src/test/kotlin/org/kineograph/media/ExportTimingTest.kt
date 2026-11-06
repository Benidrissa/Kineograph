package org.kineograph.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kineograph.media.export.ExportConfig
import org.kineograph.media.export.ExportFormat
import org.kineograph.media.export.ExportPlan
import org.kineograph.media.export.ExportWorker
import org.kineograph.media.video.EncoderLimits
import org.kineograph.media.video.VideoFrameRate
import org.kineograph.media.video.VideoSizeResolver
import org.kineograph.media.video.videoBitrate
import org.kineograph.model.FrameSlot

class ExportTimingTest {
    private fun slots(vararg holds: Int) = holds.mapIndexed { i, hold -> FrameSlot("f$i", hold) }

    private val config = ExportConfig("p", ExportFormat.MP4)

    @Test
    fun `twelve frames at twelve fps run for exactly one second`() {
        val plan = ExportPlan.of(List(12) { FrameSlot("f$it") }, 12, config)
        assertEquals(12L, plan.totalTicks)
        assertEquals(1_000_000L, plan.durationUs)
        assertEquals(0L, plan.tickAt(0).presentationUs)
        assertEquals(83_333L, plan.tickAt(1).presentationUs)
        assertEquals(916_666L, plan.tickAt(11).presentationUs)
    }

    @Test
    fun `timestamps always increase and never accumulate error`() {
        val plan = ExportPlan.of(List(1000) { FrameSlot("f$it") }, 24, config)
        var last = -1L
        for (i in 0 until plan.totalTicks) {
            val t = plan.tickAt(i).presentationUs
            assertTrue(t > last)
            last = t
        }
        assertEquals(999L * 1_000_000 / 24, last)
    }

    @Test
    fun `holds repeat a drawing for their length`() {
        val plan = ExportPlan.of(slots(1, 3, 2), 12, config)
        assertEquals(6L, plan.totalTicks)
        assertEquals(listOf(0, 1, 1, 1, 2, 2), (0L until 6L).map { plan.tickAt(it).slotIndex })
    }

    @Test
    fun `repeats play the whole animation again with time carrying on`() {
        val plan = ExportPlan.of(slots(1, 1), 10, config.copy(repeatCount = 3))
        assertEquals(6L, plan.totalTicks)
        assertEquals(listOf(0, 1, 0, 1, 0, 1), (0L until 6L).map { plan.tickAt(it).slotIndex })
        assertEquals(500_000L, plan.tickAt(5).presentationUs)
        assertEquals(600_000L, plan.durationUs)
    }

    @Test
    fun `a frame range limits the export`() {
        val plan = ExportPlan.of(slots(1, 1, 1, 1, 1), 10, config.copy(rangeStart = 1, rangeEnd = 2))
        assertEquals(2L, plan.totalTicks)
    }

    @Test
    fun `a wild frame range is clamped rather than failing`() {
        val plan = ExportPlan.of(slots(1, 1, 1), 10, config.copy(rangeStart = 50, rangeEnd = -3))
        assertEquals(1L, plan.totalTicks)
    }

    @Test
    fun `the frame rate can be overridden`() {
        val plan = ExportPlan.of(slots(1, 1, 1, 1), 12, config.copy(fpsOverride = 2))
        assertEquals(2_000_000L, plan.durationUs)
    }

    @Test
    fun `low frame rates encode extra ticks but last the same`() {
        assertEquals(1, VideoFrameRate.ticksMultiplier(12))
        assertEquals(1, VideoFrameRate.ticksMultiplier(10))
        assertEquals(10, VideoFrameRate.ticksMultiplier(1))
        assertEquals(2, VideoFrameRate.ticksMultiplier(5))
        assertEquals(4, VideoFrameRate.ticksMultiplier(3))
        val plan = ExportPlan.of(slots(1, 1, 1), 1, config, VideoFrameRate::ticksMultiplier)
        assertEquals(30L, plan.totalTicks)
        assertEquals(10, plan.encodedFps)
        assertEquals(3_000_000L, plan.durationUs)
        assertEquals(0, plan.tickAt(9).slotIndex)
        assertEquals(1, plan.tickAt(10).slotIndex)
    }

    // ----- Size resolution -----

    private class FakeLimits(
        override val widthAlignment: Int = 2,
        override val heightAlignment: Int = 2,
        override val maxWidth: Int = 4096,
        override val maxHeight: Int = 4096,
        private val maxPixelRate: Double = Double.MAX_VALUE,
    ) : EncoderLimits {
        override fun isSizeSupported(width: Int, height: Int) =
            width <= maxWidth && height <= maxHeight && width % widthAlignment == 0 && height % heightAlignment == 0

        override fun areSizeAndRateSupported(width: Int, height: Int, fps: Double) =
            isSizeSupported(width, height) && width * height * fps <= maxPixelRate
    }

    @Test
    fun `a supported size is used as it is`() {
        val size = VideoSizeResolver.resolve(1280, 720, 12, FakeLimits())
        assertEquals(1280, size?.width)
        assertEquals(720, size?.height)
        assertFalse(size?.wasReduced == true)
    }

    @Test
    fun `odd sizes round up to even, leaving the picture its own size`() {
        val size = VideoSizeResolver.resolve(641, 361, 12, FakeLimits())
        assertEquals(642, size?.width)
        assertEquals(362, size?.height)
        assertEquals(641, size?.contentWidth)
        assertEquals(361, size?.contentHeight)
        assertFalse(size?.wasReduced == true)
    }

    @Test
    fun `an encoder that needs multiples of sixteen gets them with thin bars`() {
        val size = VideoSizeResolver.resolve(1920, 1080, 12, FakeLimits(widthAlignment = 16, heightAlignment = 16))
        assertEquals(1920, size?.width)
        assertEquals(1088, size?.height)
        assertEquals(1080, size?.contentHeight)
        assertFalse(size?.wasReduced == true)
    }

    @Test
    fun `too large for the encoder scales down keeping the shape and says so`() {
        val size = VideoSizeResolver.resolve(3840, 2160, 12, FakeLimits(maxWidth = 1920, maxHeight = 1088))
        assertNotNull(size)
        assertTrue(size?.wasReduced == true)
        val w = size?.contentWidth ?: 0
        val h = size?.contentHeight ?: 1
        assertTrue(w <= 1920 && h <= 1088)
        assertEquals(16.0 / 9.0, w.toDouble() / h, 0.01)
    }

    @Test
    fun `too much for the encoder's pixel rate steps down`() {
        val size = VideoSizeResolver.resolve(1920, 1080, 60, FakeLimits(maxPixelRate = 1280.0 * 720 * 30))
        assertTrue(size?.wasReduced == true)
        assertTrue((size?.width ?: 0) * (size?.height ?: 0) * 60.0 <= 1280.0 * 720 * 30)
    }

    @Test
    fun `tiny pixel art canvases are padded up to the encoder's minimum`() {
        val size = VideoSizeResolver.resolve(8, 8, 12, FakeLimits())
        assertEquals(16, size?.width)
        assertEquals(8, size?.contentWidth)
    }

    @Test
    fun `an encoder that can do nothing gives null rather than a loop forever`() {
        val impossible =
            object : EncoderLimits {
                override val widthAlignment = 2
                override val heightAlignment = 2
                override val maxWidth = 1
                override val maxHeight = 1

                override fun isSizeSupported(width: Int, height: Int) = false

                override fun areSizeAndRateSupported(width: Int, height: Int, fps: Double) = false
            }
        assertNull(VideoSizeResolver.resolve(1280, 720, 12, impossible))
    }

    @Test
    fun `bitrate rises with size and stays in a sane range`() {
        val small = videoBitrate(320, 240, 12, 1f)
        val large = videoBitrate(1920, 1080, 30, 1f)
        assertTrue(small < large)
        assertTrue(small >= 400_000)
        assertTrue(videoBitrate(8192, 8192, 60, 4f) <= 60_000_000)
    }

    // ----- File names -----

    @Test
    fun `file names are safe and keep accents`() {
        assertEquals("Mon été.mp4", ExportWorker.fileName("Mon été", ExportFormat.MP4))
        assertEquals("a_b_c.gif", ExportWorker.fileName("a/b\\c", ExportFormat.GIF))
        assertEquals("animation.gif", ExportWorker.fileName("   ", ExportFormat.GIF))
        assertEquals("..._etc_passwd.gif", ExportWorker.fileName(".../etc/passwd", ExportFormat.GIF))
    }
}
