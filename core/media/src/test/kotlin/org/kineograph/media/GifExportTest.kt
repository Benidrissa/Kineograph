package org.kineograph.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kineograph.data.ProjectRepository
import org.kineograph.data.SampleProject
import org.kineograph.data.db.AppDatabase
import org.kineograph.data.files.CelFileStore
import org.kineograph.data.files.ProjectFiles
import org.kineograph.engine.compositor.FlattenOptions
import org.kineograph.engine.compositor.FrameFlattener
import org.kineograph.media.export.ExportConfig
import org.kineograph.media.export.ExportFormat
import org.kineograph.media.export.ProjectExporter
import org.kineograph.media.export.drawVideoFrame
import org.kineograph.media.export.videoFillColor
import org.kineograph.media.video.VideoLayout
import org.kineograph.model.Background
import org.kineograph.model.OnionSkinSettings
import org.kineograph.model.Project
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exports the real sample project through the real flattener, then decodes the result independently. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class GifExportTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var database: AppDatabase
    private lateinit var repository: ProjectRepository
    private lateinit var project: Project
    private lateinit var store: CelFileStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val files = ProjectFiles(temp.newFolder(), temp.newFolder())
        repository = ProjectRepository(database.projects(), files, 16L * 1024 * 1024, Dispatchers.IO)
        val id = runBlocking { SampleProject.create(repository, "Bouncing Ball", "Ball", 0) }
        val opened = runBlocking { repository.open(id) } ?: error("no sample")
        project = opened.loaded.project
        store = opened.store
    }

    @After
    fun tearDown() {
        store.close()
        database.close()
    }

    private fun export(config: ExportConfig): TestGifDecoder {
        val output = temp.newFile()
        runBlocking { ProjectExporter(store).export(project, config, output) { } }
        return TestGifDecoder(output.readBytes())
    }

    private val config get() = ExportConfig(project.id, ExportFormat.GIF, scale = 0.25f, gifDither = false)

    @Test
    fun `the bouncing ball exports as twelve frames lasting one second`() {
        val gif = export(config)
        assertEquals(320, gif.width)
        assertEquals(180, gif.height)
        assertEquals(12, gif.frames.size)
        assertEquals(100, gif.frames.sumOf { it.delay })
        assertEquals(0, gif.loopCount)
    }

    @Test
    fun `the ball is where it should be in each frame`() {
        val gif = export(config)
        val firstFrameBallColor = gif.frames[0].pixels[28 * 320 + 60]
        assertTrue(
            "ball red, got ${Integer.toHexString(firstFrameBallColor)}",
            Color.red(firstFrameBallColor) > 200 && Color.blue(firstFrameBallColor) < 80,
        )
        // Frames differ from one another, since the ball moves.
        assertFalse(gif.frames[0].pixels.contentEquals(gif.frames[5].pixels))
    }

    @Test
    fun `no watermark - only ball and shadow differ from the paper`() {
        val gif = export(config)
        val paper = gif.frames[0].pixels[0]
        // A watermark would sit in a corner. Every corner region of every frame must be untouched paper.
        val width = gif.width
        val height = gif.height
        for (frame in gif.frames) {
            for (y in 0 until 12) {
                for (x in 0 until 40) {
                    assertEquals(paper, frame.pixels[y * width + x])
                    assertEquals(paper, frame.pixels[y * width + width - 1 - x])
                    assertEquals(paper, frame.pixels[(height - 1 - y) * width + x])
                    assertEquals(paper, frame.pixels[(height - 1 - y) * width + width - 1 - x])
                }
            }
        }
    }

    /** The strongest ghosts there can be, and on both sides, so that any of them reaching a frame would change its pixels. */
    private val onionSkinOn get() =
        project.copy(
            settings = project.settings.copy(
                onionSkin = OnionSkinSettings(
                    enabled = true,
                    previousFrames = OnionSkinSettings.MAX_GHOSTS_PER_SIDE,
                    nextFrames = OnionSkinSettings.MAX_GHOSTS_PER_SIDE,
                    opacity = 1f,
                    falloff = 1f,
                    tint = true,
                    wrapAround = true,
                    showDuringPlayback = true,
                ),
            ),
        )

    @Test
    fun `onion skin never reaches a GIF`() {
        val plain = export(config)
        project = onionSkinOn
        val withOnionSkin = export(config)
        assertEquals(plain.frames.size, withOnionSkin.frames.size)
        for ((index, frame) in plain.frames.withIndex()) {
            assertTrue("frame $index", frame.pixels.contentEquals(withOnionSkin.frames[index].pixels))
        }
    }

    @Test
    fun `onion skin never reaches a video frame`() {
        val layout = VideoLayout(320, 180, 0.25f, 320, 180, 0, 0, wasReduced = false)
        val options = FlattenOptions()
        val fill = videoFillColor(project.background, true)
        val flattener = FrameFlattener(store)

        fun framesOf(project: Project): List<IntArray> =
            project.frames.map { slot ->
                val frame = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
                drawVideoFrame(frame, Canvas(frame), flattener, project, slot.id, layout, fill, null, options)
                IntArray(layout.width * layout.height).also { frame.getPixels(it, 0, layout.width, 0, 0, layout.width, layout.height) }
            }

        val plain = framesOf(project)
        val withOnionSkin = framesOf(onionSkinOn)
        for ((index, pixels) in plain.withIndex()) {
            assertTrue("frame $index", pixels.contentEquals(withOnionSkin[index]))
        }
    }

    @Test
    fun `a transparent background stays transparent`() {
        project = project.copy(background = Background.Transparent)
        val gif = export(config)
        assertEquals(0, gif.frames[0].pixels[0])
    }

    @Test
    fun `leaving the background out makes it transparent`() {
        val gif = export(config.copy(includeBackground = false))
        assertEquals(0, gif.frames[0].pixels[0])
    }

    @Test
    fun `background is opaque when included`() {
        val gif = export(config)
        assertNotEquals(0, gif.frames[0].pixels[0])
    }

    @Test
    fun `a frame range exports only those frames`() {
        val gif = export(config.copy(rangeStart = 2, rangeEnd = 4))
        assertEquals(3, gif.frames.size)
    }

    @Test
    fun `holds extend how long a frame shows`() {
        project = project.copy(frames = project.frames.mapIndexed { i, slot -> if (i == 0) slot.copy(hold = 4) else slot })
        val gif = export(config)
        assertEquals(12, gif.frames.size)
        assertEquals(125, gif.frames.sumOf { it.delay })
        assertTrue(gif.frames[0].delay >= 33)
    }

    @Test
    fun `playing a set number of times is written into the file`() {
        val gif = export(config.copy(gifLoopForever = false, repeatCount = 2))
        assertEquals(1, gif.loopCount)
    }

    @Test
    fun `progress reaches the end`() {
        val output = temp.newFile("progress.gif")
        val seen = ArrayList<Float>()
        runBlocking { ProjectExporter(store).export(project, config, output) { seen += it } }
        assertEquals(1f, seen.last(), 0f)
        assertEquals(seen.sorted(), seen)
    }

    @Test
    fun `cancelling stops the export`() {
        val output = temp.newFile("cancel.gif")
        var progressCalls = 0
        try {
            runBlocking {
                val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                ProjectExporter(store).export(project, config, output) {
                    progressCalls++
                    if (progressCalls == 3) job?.cancel()
                }
            }
            fail("should have been cancelled")
        } catch (_: CancellationException) {
            assertTrue(progressCalls in 3..4)
        }
    }

    @Test
    fun `a small canvas can be exported well past four times its size`() {
        val tiny = project.copy(width = 64, height = 64)
        val output = temp.newFile("big.gif")
        val result = runBlocking { ProjectExporter(store).export(tiny, ExportConfig(tiny.id, ExportFormat.GIF, scale = 8f), output) { } }
        assertEquals(512, result.width)
        assertFalse(result.wasReduced)
        assertEquals(512, TestGifDecoder(output.readBytes()).width)
    }

    @Test
    fun `a size the format cannot hold comes down and says so, rather than writing a broken file`() {
        val long = project.copy(width = 8192, height = 1)
        val output = temp.newFile("long.gif")
        val config = ExportConfig(long.id, ExportFormat.GIF, scale = 10f, rangeStart = 0, rangeEnd = 0)
        val result = runBlocking { ProjectExporter(store).export(long, config, output) { } }
        assertTrue(result.wasReduced)
        assertEquals(65_535, result.width)
        val decoded = TestGifDecoder(output.readBytes())
        assertEquals(65_535, decoded.width)
        assertEquals(result.height, decoded.height)
    }

    @Test
    fun `a frame set for video does not reach a GIF`() {
        val output = temp.newFile("framed.gif")
        val config = ExportConfig(project.id, ExportFormat.GIF, scale = 0.25f, frameWidth = 1080, frameHeight = 1920)
        val result = runBlocking { ProjectExporter(store).export(project, config, output) { } }
        assertEquals((project.width * 0.25f).toInt(), result.width)
        assertEquals((project.height * 0.25f).toInt(), result.height)
    }
}
