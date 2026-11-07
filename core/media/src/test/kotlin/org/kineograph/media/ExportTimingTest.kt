package org.kineograph.media

import android.graphics.Color
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kineograph.engine.compositor.BackgroundPainter
import org.kineograph.media.export.ExportConfig
import org.kineograph.media.export.ExportFormat
import org.kineograph.media.export.ExportPlan
import org.kineograph.media.export.ExportWorker
import org.kineograph.media.export.videoFillColor
import org.kineograph.media.gif.GifSize
import org.kineograph.media.gif.GifSizes
import org.kineograph.media.video.EncoderLimits
import org.kineograph.media.video.VideoFrameRate
import org.kineograph.media.video.VideoSizeResolver
import org.kineograph.media.video.videoBitrate
import org.kineograph.model.Background
import org.kineograph.model.FrameSlot
import org.kineograph.model.PaperKind

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
        override val minWidth: Int = 1,
        override val minHeight: Int = 1,
    ) : EncoderLimits {
        override fun isSizeSupported(width: Int, height: Int) =
            width in minWidth..maxWidth &&
                height in minHeight..maxHeight &&
                width % widthAlignment == 0 &&
                height % heightAlignment == 0

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
    fun `a canvas below a hardware encoder's minimum gets bars rather than failing`() {
        val size = VideoSizeResolver.resolve(64, 64, 12, FakeLimits(minWidth = 96, minHeight = 96))
        assertEquals(96, size?.width)
        assertEquals(96, size?.height)
        assertEquals(64, size?.contentWidth)
        assertEquals(64, size?.contentHeight)
        assertFalse(size?.wasReduced == true)
    }

    @Test
    fun `only the edge below the minimum grows`() {
        val size = VideoSizeResolver.resolve(1920, 64, 12, FakeLimits(minWidth = 96, minHeight = 96))
        assertEquals(1920, size?.width)
        assertEquals(96, size?.height)
        assertEquals(64, size?.contentHeight)
    }

    @Test
    fun `an encoder's low minimum does not lift the floor every video needs`() {
        val size = VideoSizeResolver.resolve(8, 8, 12, FakeLimits(minWidth = 2, minHeight = 2))
        assertEquals(16, size?.width)
        assertEquals(8, size?.contentWidth)
    }

    @Test
    fun `any number typed as a size comes down to what the encoder can do`() {
        val size = VideoSizeResolver.resolve(Int.MAX_VALUE, Int.MAX_VALUE, 12, FakeLimits(maxWidth = 1920, maxHeight = 1088))
        assertTrue(size?.wasReduced == true)
        assertTrue((size?.width ?: 0) in 1..1920)
        assertTrue((size?.height ?: 0) in 1..1088)
    }

    // ----- Where the picture sits -----

    private fun verticalLayout(
        pictureWidth: Int,
        pictureHeight: Int,
        limits: EncoderLimits = FakeLimits(
            maxWidth = 4096,
            maxHeight = 4096,
        ),
    ) =
        VideoSizeResolver.layout(pictureWidth, pictureHeight, 1080, 1920, 30, limits)

    @Test
    fun `a landscape picture sits centred in a vertical video, with bars above and below`() {
        val layout = verticalLayout(1280, 720)
        assertEquals(1080, layout?.width)
        assertEquals(1920, layout?.height)
        assertEquals(1080, layout?.pictureWidth)
        assertEquals(608, layout?.pictureHeight)
        assertEquals(0, layout?.offsetX)
        assertEquals(656, layout?.offsetY)
        assertEquals(1080f / 1280f, layout?.pictureScale ?: 0f, 0.0001f)
        assertTrue(layout?.wasPadded == true)
        assertFalse(layout?.wasReduced == true)
    }

    @Test
    fun `a square picture sits centred in a vertical video rather than becoming a larger square`() {
        val layout = verticalLayout(1080, 1080)
        assertEquals(1080, layout?.width)
        assertEquals(1920, layout?.height)
        assertEquals(1080, layout?.pictureHeight)
        assertEquals(420, layout?.offsetY)
    }

    @Test
    fun `a picture already of the vertical shape fills the video with no bars`() {
        val layout = verticalLayout(720, 1280)
        assertEquals(1080, layout?.pictureWidth)
        assertEquals(1920, layout?.pictureHeight)
        assertEquals(0, layout?.offsetX)
        assertEquals(0, layout?.offsetY)
        assertFalse(layout?.wasPadded == true)
    }

    @Test
    fun `a vertical video stays vertical on an encoder that cannot go that tall`() {
        val layout = verticalLayout(1280, 720, FakeLimits(maxWidth = 1920, maxHeight = 1088))
        assertTrue(layout?.wasReduced == true)
        assertTrue((layout?.height ?: 0) <= 1088)
        assertTrue((layout?.height ?: 0) > (layout?.width ?: 0))
        assertTrue((layout?.pictureWidth ?: 0) <= (layout?.width ?: 0))
        assertEquals(16.0 / 9.0, (layout?.pictureWidth ?: 0).toDouble() / (layout?.pictureHeight ?: 1), 0.02)
    }

    @Test
    fun `a one pixel difference never shifts the picture by half a pixel`() {
        val layout = VideoSizeResolver.layout(1001, 1001, 1001, 1001, 12, FakeLimits())
        assertEquals(1002, layout?.width)
        assertEquals(1.0f, layout?.pictureScale)
        assertEquals(0, layout?.offsetX)
        assertEquals(0, layout?.offsetY)
    }

    @Test
    fun `an uneven difference still lands on a whole pixel`() {
        val layout = VideoSizeResolver.layout(1001, 1001, 1001, 1001, 12, FakeLimits(widthAlignment = 16, heightAlignment = 16))
        assertEquals(1008, layout?.width)
        assertEquals(3, layout?.offsetX)
    }

    @Test
    fun `padding is reported whichever way it came about, and separately from reducing`() {
        val thin = VideoSizeResolver.layout(1920, 1080, 1920, 1080, 12, FakeLimits(widthAlignment = 16, heightAlignment = 16))
        assertTrue(thin?.wasPadded == true)
        assertFalse(thin?.wasReduced == true)
        assertTrue(VideoSizeResolver.layout(641, 361, 641, 361, 12, FakeLimits())?.wasPadded == true)
        assertTrue(VideoSizeResolver.layout(8, 8, 8, 8, 12, FakeLimits())?.wasPadded == true)
        assertTrue(VideoSizeResolver.layout(64, 64, 64, 64, 12, FakeLimits(minWidth = 96, minHeight = 96))?.wasPadded == true)
        assertFalse(VideoSizeResolver.layout(1280, 720, 1280, 720, 12, FakeLimits())?.wasPadded == true)
    }

    @Test
    fun `a size that is both reduced and padded reports both`() {
        val aligned = VideoSizeResolver.layout(3840, 2160, 3840, 2160, 12, FakeLimits(16, 16, 1920, 1088))
        assertTrue(aligned?.wasReduced == true)
        assertTrue(aligned?.wasPadded == true)
    }

    @Test
    fun `a frame is only for video`() {
        val video = config.copy(scale = 0.5f, frameWidth = 1080, frameHeight = 1920)
        assertEquals(1080 to 1920, video.requestedSize(1280, 720))
        assertEquals(640 to 360, video.copy(format = ExportFormat.GIF).requestedSize(1280, 720))
        assertEquals(640 to 360, config.copy(scale = 0.5f).requestedSize(1280, 720))
    }

    @Test
    fun `work queued before frames existed still reads`() {
        val old = Json.decodeFromString(ExportConfig.serializer(), "{\"projectId\":\"p\",\"format\":\"MP4\",\"scale\":2.0}")
        assertNull(old.frameWidth)
        assertEquals(2560 to 1440, old.requestedSize(1280, 720))
    }

    // ----- What a video's frames start from -----

    private val darkPaper = Background.Paper(PaperKind.DARK)

    @Test
    fun `with the background included, bars match it`() {
        assertEquals(BackgroundPainter.paperColor(PaperKind.DARK), videoFillColor(darkPaper, includeBackground = true))
        assertEquals(0xFF102030.toInt(), videoFillColor(Background.Color(0xFF102030.toInt()), includeBackground = true))
        assertEquals(Color.WHITE, videoFillColor(Background.Transparent, includeBackground = true))
    }

    @Test
    fun `leaving out a coloured background changes what the video shows`() {
        val cream = Background.Paper(PaperKind.OFF_WHITE)
        val yellow = Background.Color(0xFFFFEE00.toInt())
        assertNotEquals(videoFillColor(cream, true), videoFillColor(cream, false))
        assertNotEquals(videoFillColor(yellow, true), videoFillColor(yellow, false))
        assertNotEquals(videoFillColor(darkPaper, true), videoFillColor(darkPaper, false))
    }

    @Test
    fun `light lines drawn for a dark background stay visible without it`() {
        assertEquals(Color.BLACK, videoFillColor(darkPaper, includeBackground = false))
        assertEquals(Color.BLACK, videoFillColor(Background.Color(0xFF000040.toInt()), includeBackground = false))
    }

    @Test
    fun `everything else without its background goes on white`() {
        assertEquals(Color.WHITE, videoFillColor(Background.Paper(PaperKind.OFF_WHITE), includeBackground = false))
        assertEquals(Color.WHITE, videoFillColor(Background.Paper(PaperKind.GRID), includeBackground = false))
        assertEquals(Color.WHITE, videoFillColor(Background.Image("background.png"), includeBackground = false))
        assertEquals(Color.WHITE, videoFillColor(Background.Transparent, includeBackground = false))
    }

    // ----- GIF sizes -----

    @Test
    fun `a size a GIF can hold is left alone`() {
        assertEquals(GifSize(512, 512, wasReduced = false), GifSizes.fit(512, 512))
        assertEquals(GifSize(65_535, 8, wasReduced = false), GifSizes.fit(65_535, 8))
    }

    @Test
    fun `an edge past the format's limit comes down, keeping the shape`() {
        val size = GifSizes.fit(81_920, 10)
        assertEquals(65_535, size.width)
        assertEquals(8, size.height)
        assertTrue(size.wasReduced)
    }

    @Test
    fun `a frame too big to hold in memory at all comes down without overflowing`() {
        val size = GifSizes.fit(60_000, 60_000)
        assertTrue(size.wasReduced)
        assertTrue(size.width.toLong() * size.height <= Int.MAX_VALUE)
        assertEquals(size.width, size.height)
        val huge = GifSizes.fit(Int.MAX_VALUE, Int.MAX_VALUE)
        assertTrue(huge.width.toLong() * huge.height <= Int.MAX_VALUE)
        assertTrue(huge.width <= 65_535)
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
