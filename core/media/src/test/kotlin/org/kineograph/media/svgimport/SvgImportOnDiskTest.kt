package org.kineograph.media.svgimport

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kineograph.data.Autosaver
import org.kineograph.data.ProjectRepository
import org.kineograph.data.db.AppDatabase
import org.kineograph.data.files.ProjectFiles
import org.kineograph.engine.compositor.FlattenOptions
import org.kineograph.engine.compositor.FrameFlattener
import org.kineograph.engine.session.DrawingSession
import org.kineograph.engine.session.MemoryBudget
import org.kineograph.media.svg.SvgParser
import org.kineograph.model.Background
import org.kineograph.model.IntRect
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** An import with the real store, autosave and sweep around it, which is where files could go missing. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SvgImportOnDiskTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var database: AppDatabase
    private lateinit var files: ProjectFiles
    private lateinit var repository: ProjectRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        files = ProjectFiles(temp.newFolder(), temp.newFolder())
        repository = ProjectRepository(database.projects(), files, 8L * 1024 * 1024, Dispatchers.IO)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun hops() =
        checkNotNull(javaClass.getResourceAsStream("/svg/import-hops.svg")) { "missing fixture" }.use { SvgParser.parse(it) }

    /** Runs [block] on a freshly opened editor's parts, then closes them the way the editor does. */
    private fun withEditor(id: String, block: suspend (DrawingSession, Autosaver, SvgImporter) -> Unit) {
        runBlocking {
            val opened = repository.open(id) ?: error("the project did not open")
            val session = DrawingSession(opened.loaded.project, opened.store, MemoryBudget.forMemoryClass(256, false), temp.newFolder())
            val autosaver = Autosaver(session, repository, opened.store, id)
            val importer = SvgImporter(session, opened.store, { image -> repository.saveBackgroundImage(id, image) })
            block(session, autosaver, importer)
            autosaver.finish()
            session.close()
            opened.store.close()
        }
    }

    private fun cels(id: String): List<File> = File(files.projectDirectory(id), "cels")
        .listFiles()
        .orEmpty()
        .filter { it.isFile }
        .toList()

    @Test
    fun `an import survives a sweep in the middle of it, then saving, closing and opening again`() {
        val project = runBlocking { repository.create("One", 200, 100, 12, Background.Transparent, "Layer 1") }
        withEditor(project.id) { _, autosaver, importer ->
            autosaver.holdSweeps()
            val outcome =
                importer.import(hops(), SvgImportRequest.Animation(2.0, SvgFit.FIT_INSIDE, "Hops")) { done, _ ->
                    // This is what happens if the app goes to the background partway through.
                    if (done == 12) runBlocking { autosaver.finish() }
                }
            autosaver.releaseSweeps()
            assertEquals(SvgImportOutcome.DONE, outcome)
        }
        val reopened = runBlocking { repository.open(project.id) } ?: error("the project did not reopen")
        assertEquals(0, reopened.loaded.missingCels)
        val saved = reopened.loaded.project
        assertEquals(24, saved.frames.size)
        val layer = saved.layers.last()
        assertEquals("Hops", layer.name)
        assertEquals(IntRect(70, 0, 90, 20), saved.cel(layer.id, saved.frames[7].id)?.bounds)
        val picture = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val options = FlattenOptions(includeBackground = false)
        FrameFlattener(reopened.store).flatten(saved, saved.frames[7].id, Canvas(picture), 1f, null, options)
        assertEquals(Color.RED, picture.getPixel(80, 10))
        assertEquals(0, Color.alpha(picture.getPixel(60, 10)))
        // Three different pictures, however many frames show them.
        assertEquals(3, cels(project.id).size)
        reopened.store.close()
    }

    @Test
    fun `a cancelled import takes its files away with it, without waiting for a sweep`() {
        val project = runBlocking { repository.create("One", 200, 100, 12, Background.Transparent, "Layer 1") }
        withEditor(project.id) { session, autosaver, importer ->
            autosaver.holdSweeps()
            val before = session.snapshot()
            val thrown =
                runCatching {
                    importer.import(hops(), SvgImportRequest.Animation(2.0, SvgFit.FIT_INSIDE, "Hops")) { done, _ ->
                        if (done == 12) throw CancellationException("the user cancelled")
                    }
                }.exceptionOrNull()
            assertTrue(thrown is CancellationException)
            autosaver.releaseSweeps()
            assertEquals(before, session.snapshot())
            assertTrue(cels(project.id).isEmpty())
        }
    }

    @Test
    fun `a background left behind by a cancel goes when the project closes`() {
        val project = runBlocking { repository.create("One", 200, 100, 12, Background.Transparent, "Layer 1") }
        val square = SvgParser.parse(
            """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20"><rect width="20" height="20" fill="red"/></svg>""",
        )
        val media = File(files.projectDirectory(project.id), "media")
        withEditor(project.id) { _, _, _ ->
            repository.saveBackgroundImage(project.id, Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
            assertEquals(1, media.listFiles().orEmpty().size)
        }
        assertEquals(0, media.listFiles().orEmpty().size)
        withEditor(project.id) { session, _, importer ->
            val request = SvgImportRequest.SingleImage(0.0, SvgImportPlace.BACKGROUND, SvgFit.ORIGINAL_SIZE, "Picture")
            assertEquals(SvgImportOutcome.DONE, importer.import(square, request))
            assertEquals(1, media.listFiles().orEmpty().size)
            session.undo()
        }
        // Redo could still bring it back until the project closed. With that gone, nothing can.
        withEditor(project.id) { _, _, _ -> }
        assertEquals(0, media.listFiles().orEmpty().size)
    }

    @Test
    fun `a picture imported as the background is still there after saving, closing and opening`() {
        val project = runBlocking { repository.create("One", 200, 100, 12, Background.Transparent, "Layer 1") }
        val square = SvgParser.parse(
            """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20"><rect width="20" height="20" fill="red"/></svg>""",
        )
        withEditor(project.id) { _, _, importer ->
            val request = SvgImportRequest.SingleImage(0.0, SvgImportPlace.BACKGROUND, SvgFit.ORIGINAL_SIZE, "Picture")
            assertEquals(SvgImportOutcome.DONE, importer.import(square, request))
        }
        val reopened = runBlocking { repository.open(project.id) } ?: error("the project did not reopen")
        val background = reopened.loaded.project.background as? Background.Image ?: error("the background was not saved")
        assertTrue(background.file.startsWith("media/"))
        val image = reopened.store.read(background.file) ?: error("the background could not be read")
        assertEquals(200, image.width)
        assertEquals(100, image.height)
        assertEquals(Color.RED, image.getPixel(100, 50))
        assertEquals(0, Color.alpha(image.getPixel(10, 10)))
        reopened.store.close()
    }

    @Test
    fun `importing on to the current layer survives closing and opening, and so does what was there`() {
        val project = runBlocking { repository.create("One", 200, 100, 12, Background.Transparent, "Layer 1") }
        val left = SvgParser.parse(
            """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100"><rect width="50" height="100" fill="blue"/></svg>""",
        )
        val right = SvgParser.parse(
            """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100"><rect x="150" width="50" height="100" fill="red"/></svg>""",
        )
        withEditor(project.id) { _, _, importer ->
            val request = SvgImportRequest.SingleImage(0.0, SvgImportPlace.CURRENT_LAYER, SvgFit.FIT_INSIDE, "Picture")
            assertEquals(SvgImportOutcome.DONE, importer.import(left, request))
            assertEquals(SvgImportOutcome.DONE, importer.import(right, request))
        }
        val reopened = runBlocking { repository.open(project.id) } ?: error("the project did not reopen")
        val saved = reopened.loaded.project
        val cel = saved.cel(saved.layers.single().id, saved.frames.single().id) ?: error("no cel")
        assertEquals(IntRect(0, 0, 200, 100), cel.bounds)
        val image = reopened.store.read(checkNotNull(cel.file)) ?: error("the cel could not be read")
        assertEquals(Color.BLUE, image.getPixel(10, 50))
        assertEquals(Color.RED, image.getPixel(190, 50))
        reopened.store.close()
    }
}
