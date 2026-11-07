package org.kineograph.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.kineograph.engine.compositor.FlattenOptions
import org.kineograph.engine.compositor.FrameFlattener
import org.kineograph.engine.session.CelStore
import org.kineograph.media.export.drawVideoFrame
import org.kineograph.media.export.videoFillColor
import org.kineograph.media.video.EncoderLimits
import org.kineograph.media.video.VideoLayout
import org.kineograph.media.video.VideoSizeResolver
import org.kineograph.model.Background
import org.kineograph.model.Cel
import org.kineograph.model.CelKey
import org.kineograph.model.IntRect
import org.kineograph.model.PaperKind
import org.kineograph.model.Project
import org.kineograph.model.newProject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What the encoder gets to see, drawn by real Skia, because the sizes being right says nothing about the pixels. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class VideoFrameTest {
    private class OneImageStore(private val image: Bitmap?) : CelStore {
        override fun write(image: Bitmap) = "cel"

        override fun read(file: String, sampleSize: Int) = image

        override fun isGone(file: String) = false

        override suspend fun awaitWrites() = Unit

        override fun trimMemory() = Unit
    }

    private object AnySize : EncoderLimits {
        override val widthAlignment = 2
        override val heightAlignment = 2
        override val maxWidth = 8192
        override val maxHeight = 8192

        override fun isSizeSupported(width: Int, height: Int) = width % 2 == 0 && height % 2 == 0

        override fun areSizeAndRateSupported(width: Int, height: Int, fps: Double) = isSizeSupported(width, height)
    }

    private fun project(width: Int, height: Int, background: Background): Project =
        newProject("Test", width, height, 12, background, "Layer", 0L)

    private fun Project.withCel(bounds: IntRect): Project {
        val layer = layers.first().id
        val frame = frames.first().id
        return copy(cels = mapOf(CelKey(layer, frame) to Cel(layer, frame, "cel", bounds)))
    }

    private fun draw(
        project: Project,
        layout: VideoLayout,
        includeBackground: Boolean = true,
        image: Bitmap? = null,
        fill: Int = videoFillColor(project.background, includeBackground),
    ): Bitmap {
        val frame = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        drawVideoFrame(
            frame,
            Canvas(frame),
            FrameFlattener(OneImageStore(image)),
            project,
            project.frames.first().id,
            layout,
            fill,
            null,
            FlattenOptions(includeBackground = includeBackground),
        )
        return frame
    }

    private fun verticalLayout(width: Int, height: Int) = checkNotNull(VideoSizeResolver.layout(width, height, 1080, 1920, 12, AnySize))

    @Test
    fun `bars take the fill and the picture stays inside its own rectangle`() {
        val solid = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888).apply { eraseColor(BLUE) }
        val project = project(1280, 720, Background.Color(RED)).withCel(IntRect(0, 0, 1280, 720))
        val frame = draw(project, verticalLayout(1280, 720), image = solid)
        assertEquals("above the picture", RED, frame.getPixel(540, 600))
        assertEquals("the top edge", BLUE, frame.getPixel(540, 660))
        assertEquals("the middle", BLUE, frame.getPixel(540, 960))
        assertEquals("the bottom edge", BLUE, frame.getPixel(540, 1260))
        assertEquals("below the picture", RED, frame.getPixel(540, 1320))
    }

    @Test
    fun `a painted background is not allowed to flood the bars`() {
        val frame = draw(project(1280, 720, Background.Color(RED)), verticalLayout(1280, 720), fill = BLUE)
        assertEquals("the bar above", BLUE, frame.getPixel(540, 600))
        assertEquals("the picture", RED, frame.getPixel(540, 960))
        assertEquals("the bar below", BLUE, frame.getPixel(540, 1320))
    }

    @Test
    fun `turning the background off really changes a video on a solid colour`() {
        val project = project(100, 100, Background.Color(LIGHT_GREEN))
        val layout = checkNotNull(VideoSizeResolver.layout(100, 100, 100, 100, 12, AnySize))
        assertEquals(LIGHT_GREEN, draw(project, layout).getPixel(50, 50))
        assertEquals(Color.WHITE, draw(project, layout, includeBackground = false).getPixel(50, 50))
    }

    @Test
    fun `a canvas of odd size is not blurred by the bar that evens it up`() {
        // One black pixel in the middle of a white drawing, on a canvas that needs one more pixel to be even.
        val drawing = Bitmap.createBitmap(101, 101, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            setPixel(50, 50, Color.BLACK)
        }
        val project = project(101, 101, Background.Paper(PaperKind.WHITE)).withCel(IntRect(0, 0, 101, 101))
        val layout = checkNotNull(VideoSizeResolver.layout(101, 101, 101, 101, 12, AnySize))
        val frame = draw(project, layout, image = drawing)
        assertEquals(102, frame.width)
        val colours = (0 until 101).flatMap { y -> (0 until 101).map { x -> frame.getPixel(x, y) } }.toSet()
        assertEquals("every pixel is still pure black or white", setOf(Color.BLACK, Color.WHITE), colours)
        assertEquals(Color.BLACK, frame.getPixel(50, 50))
    }

    private companion object {
        const val RED = 0xFFC02020.toInt()
        const val BLUE = 0xFF2020C0.toInt()
        const val LIGHT_GREEN = 0xFFA0F0A0.toInt()
    }
}
