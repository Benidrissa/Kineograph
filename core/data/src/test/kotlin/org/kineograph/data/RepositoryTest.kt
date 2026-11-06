package org.kineograph.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kineograph.data.db.AppDatabase
import org.kineograph.data.files.CelFileStore
import org.kineograph.data.files.ProjectFiles
import org.kineograph.engine.brush.BrushSpec
import org.kineograph.engine.brush.PressureCurve
import org.kineograph.engine.brush.StampBuffer
import org.kineograph.engine.brush.StrokeEngine
import org.kineograph.engine.compositor.FlattenOptions
import org.kineograph.engine.compositor.FrameFlattener
import org.kineograph.engine.session.DrawingSession
import org.kineograph.engine.session.MemoryBudget
import org.kineograph.model.Background
import org.kineograph.model.PaperKind
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var database: AppDatabase
    private lateinit var files: ProjectFiles
    private lateinit var repository: ProjectRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        files = ProjectFiles(temp.newFolder(), temp.newFolder())
        repository = ProjectRepository(database.projects(), files, 8L * 1024 * 1024, Dispatchers.IO) { now }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun create(name: String = "One") =
        runBlocking { repository.create(name, 200, 100, 12, Background.Paper(PaperKind.WHITE), "Layer 1") }

    private fun list() = runBlocking { repository.projects.first() }

    private fun image(color: Int = Color.RED): Bitmap {
        val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(2f, 2f, 18f, 8f, Paint().apply { this.color = color })
        return bitmap
    }

    @Test
    fun `a created project is on disk and in the index`() {
        val project = create()
        val summary = list().single()
        assertEquals(project.id, summary.id)
        assertEquals("One", summary.name)
        assertEquals(1, summary.frameCount)
        assertEquals(12, summary.fps)
        assertTrue(File(files.projectDirectory(project.id), "manifest.json").isFile)
        assertNotNull(runBlocking { repository.open(project.id) })
    }

    @Test
    fun `opening something missing returns null`() {
        assertNull(runBlocking { repository.open("nope") })
    }

    @Test
    fun `identifiers cannot escape the projects directory`() {
        val dir = files.projectDirectory("../../evil").canonicalFile
        assertTrue(
            dir.parentFile?.canonicalPath == files
                .projectDirectory("x")
                .canonicalFile.parentFile
                ?.canonicalPath,
        )
    }

    @Test
    fun `the store reads back what it wrote, before and after reaching the disk`() {
        val project = create()
        val store = CelFileStore(files.projectDirectory(project.id), 1024 * 1024)
        val name = store.write(image())
        assertEquals(Color.RED, store.read(name)?.getPixel(10, 5))
        runBlocking { store.awaitWrites() }
        assertTrue(File(files.projectDirectory(project.id), name).isFile)
        assertEquals(Color.RED, store.read(name)?.getPixel(10, 5))

        val fresh = CelFileStore(files.projectDirectory(project.id), 1024 * 1024)
        assertEquals(Color.RED, fresh.read(name)?.getPixel(10, 5))
        assertEquals(0, fresh.read(name)?.getPixel(0, 0))
        store.close()
        fresh.close()
    }

    @Test
    fun `transparency survives the file format`() {
        val project = create()
        val store = CelFileStore(files.projectDirectory(project.id), 0)
        val half = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        half.eraseColor(Color.argb(128, 10, 200, 30))
        val name = store.write(half)
        runBlocking { store.awaitWrites() }
        store.trimMemory()
        val back = store.read(name) ?: error("no image")
        assertEquals(128, Color.alpha(back.getPixel(1, 1)))
        store.close()
    }

    @Test
    fun `sampled reads are smaller`() {
        val project = create()
        val store = CelFileStore(files.projectDirectory(project.id), 1024 * 1024)
        val big = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        big.eraseColor(Color.BLUE)
        val name = store.write(big)
        runBlocking { store.awaitWrites() }
        assertEquals(32, store.read(name, 2)?.width)
        store.close()
    }

    @Test
    fun `reading a missing file or a path outside the project returns null`() {
        val project = create()
        val store = CelFileStore(files.projectDirectory(project.id), 1024)
        assertNull(store.read("cels/missing.webp"))
        assertNull(store.read("../../../etc/hosts"))
        assertFalse(store.exists("../manifest.json"))
        store.close()
    }

    @Test
    fun `corrupt image files read as null rather than crashing`() {
        val project = create()
        val directory = File(files.projectDirectory(project.id), "cels").apply { mkdirs() }
        File(directory, "bad.webp").writeText("not an image")
        val store = CelFileStore(files.projectDirectory(project.id), 1024)
        assertNull(store.read("cels/bad.webp"))
        store.close()
    }

    @Test
    fun `many queued writes all arrive`() {
        val project = create()
        val store = CelFileStore(files.projectDirectory(project.id), 1024 * 1024)
        val names = List(40) { store.write(image()) }
        runBlocking { store.awaitWrites() }
        names.forEach { assertTrue(File(files.projectDirectory(project.id), it).isFile) }
        store.close()
    }

    @Test
    fun `sweep deletes only unreferenced files`() {
        val project = create()
        val store = CelFileStore(files.projectDirectory(project.id), 1024 * 1024)
        val keep = store.write(image())
        val garbage = store.write(image())
        runBlocking {
            store.awaitWrites()
            repository.sweep(project.id, store, store.listFiles(), setOf(keep))
        }
        assertTrue(store.exists(keep))
        assertFalse(store.exists(garbage))
        store.close()
    }

    /** Draws through a real session and checks that it all comes back from disk: the core promise of autosave. */
    @Test
    fun `a session's work survives closing and reopening the project`() {
        val project = create()
        val opened = runBlocking { repository.open(project.id) } ?: error("could not open")
        val session = DrawingSession(opened.loaded.project, opened.store, MemoryBudget.forMemoryClass(256, false), temp.newFolder())
        val autosaver = Autosaver(session, repository, opened.store, project.id, debounceMs = 1)
        val spec = BrushSpec(id = "t", sizePx = 10f, sizeCurve = PressureCurve.FLAT)
        val out = StampBuffer()
        StrokeEngine(spec, 1L).run {
            begin(20f, 50f, 1f, 0f, 0f, out)
            add(100f, 50f, 1f, 0f, 0f, out)
            finish(out)
        }
        session.beginStroke(spec, Color.BLACK)
        session.addStamps(out.snapshot())
        session.endStroke { }
        session.addFrame()
        runBlocking {
            session.awaitIdle()
            autosaver.finish()
        }
        session.close()
        opened.store.close()

        val reopened = runBlocking { repository.open(project.id) } ?: error("could not reopen")
        val saved = reopened.loaded.project
        assertEquals(2, saved.frames.size)
        assertEquals(0, reopened.loaded.missingCels)
        val out2 = Bitmap.createBitmap(saved.width, saved.height, Bitmap.Config.ARGB_8888)
        FrameFlattener(reopened.store).flatten(saved, saved.frames[0].id, Canvas(out2), 1f, null, FlattenOptions(includeBackground = false))
        assertEquals(Color.BLACK, out2.getPixel(60, 50))
        assertEquals(0, out2.getPixel(60, 10))
        assertTrue(files.thumbnail(project.id).isFile)
        assertEquals(2, list().single().frameCount)
        reopened.store.close()
    }

    @Test
    fun `autosave only writes after changes and is debounced`() {
        val project = create()
        val opened = runBlocking { repository.open(project.id) } ?: error("could not open")
        val session = DrawingSession(opened.loaded.project, opened.store, MemoryBudget.forMemoryClass(256, false), temp.newFolder())
        val autosaver = Autosaver(session, repository, opened.store, project.id)
        val manifest = File(files.projectDirectory(project.id), "manifest.json")
        val before = manifest.readText()
        runBlocking {
            session.awaitIdle()
            autosaver.saveNow()
        }
        assertEquals(before, manifest.readText())
        session.addFrame()
        runBlocking {
            session.awaitIdle()
            autosaver.saveNow()
        }
        assertNotEquals(before, manifest.readText())
        session.close()
        opened.store.close()
    }

    /** The freeze this guards against: blocking a thread on finish() while a save started on that same thread was in flight. */
    @Test
    fun `finishing while blocking the thread that started a save cannot deadlock`() {
        val project = create()
        val opened = runBlocking { repository.open(project.id) } ?: error("could not open")
        val session = DrawingSession(opened.loaded.project, opened.store, MemoryBudget.forMemoryClass(256, false), temp.newFolder())
        val autosaver = Autosaver(session, repository, opened.store, project.id)
        session.addFrame()
        runBlocking { session.awaitIdle() }

        val executor = java.util.concurrent.Executors
            .newSingleThreadExecutor()
        val mainLike = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(mainLike)
        val finished =
            executor.submit<Boolean> {
                scope.launch(start = CoroutineStart.UNDISPATCHED) { autosaver.saveNow() }
                runBlocking { withTimeoutOrNull(FINISH_TIMEOUT_MS) { autosaver.finish() } } != null
            }
        assertTrue("finish() never returned", finished.get(FINISH_TIMEOUT_MS * 2, java.util.concurrent.TimeUnit.MILLISECONDS))
        executor.shutdown()
        session.close()
        opened.store.close()
    }

    @Test
    fun `opening waits for the previous editor to finish saving`() {
        val project = create("before")
        val gate = CompletableDeferred<Unit>()
        val closing = CoroutineScope(Dispatchers.IO).launch { gate.await() }
        repository.trackClosing(project.id, closing)
        val reopened = CoroutineScope(Dispatchers.IO).async {
            repository
                .open(project.id)
                ?.loaded
                ?.project
                ?.name
        }
        assertEquals("open must wait for the save", null, runBlocking { withTimeoutOrNull(SHORT_WAIT_MS) { reopened.await() } })
        gate.complete(Unit)
        assertEquals("before", runBlocking { reopened.await() })
    }

    @Test
    fun `rename updates the manifest and the index`() {
        val project = create()
        runBlocking { repository.rename(project.id, "Better") }
        assertEquals("Better", list().single().name)
        assertEquals("Better", runBlocking { repository.open(project.id) }?.loaded?.project?.name)
    }

    @Test
    fun `duplicate makes an independent copy`() {
        val project = create()
        val copyId = runBlocking { repository.duplicate(project.id, "Copy") } ?: error("no copy")
        assertNotEquals(project.id, copyId)
        assertEquals(2, list().size)
        assertEquals("Copy", runBlocking { repository.open(copyId) }?.loaded?.project?.name)
        runBlocking { repository.deletePermanently(project.id) }
        assertNotNull(runBlocking { repository.open(copyId) })
    }

    @Test
    fun `trash can be undone`() {
        val project = create()
        runBlocking { repository.moveToTrash(project.id) }
        assertTrue(list().single().isTrashed)
        assertNotNull(runBlocking { repository.open(project.id) })
        runBlocking { repository.restoreFromTrash(project.id) }
        assertFalse(list().single().isTrashed)
    }

    @Test
    fun `saving a trashed project does not bring it back from the trash`() {
        val project = create()
        runBlocking {
            repository.moveToTrash(project.id)
            val store = repository.storeFor(project.id)
            repository.save(project, store)
            store.close()
        }
        assertTrue(list().single().isTrashed)
    }

    @Test
    fun `old trash is emptied at startup but recent trash stays`() {
        val old = create("old")
        val recent = create("recent")
        runBlocking { repository.moveToTrash(old.id) }
        now += 31L * 24 * 60 * 60 * 1000
        runBlocking {
            repository.moveToTrash(recent.id)
            repository.reconcile()
        }
        assertEquals(listOf("recent"), list().map { it.name })
        assertFalse(files.projectDirectory(old.id).exists())
    }

    @Test
    fun `permanent delete removes the files`() {
        val project = create()
        runBlocking { repository.deletePermanently(project.id) }
        assertTrue(list().isEmpty())
        assertFalse(files.projectDirectory(project.id).exists())
    }

    @Test
    fun `a lost index is rebuilt from the files`() {
        val project = create()
        runBlocking {
            database.projects().delete(project.id)
            assertTrue(repository.projects.first().isEmpty())
            repository.reconcile()
        }
        assertEquals("One", list().single().name)
    }

    @Test
    fun `an index row with no files is removed`() {
        val project = create()
        files.projectDirectory(project.id).deleteRecursively()
        runBlocking { repository.reconcile() }
        assertTrue(list().isEmpty())
    }

    @Test
    fun `the sample project is twelve frames at twelve fps with a drawing on each`() {
        val id = runBlocking { SampleProject.create(repository, "Bouncing Ball", "Ball", now) }
        val summary = list().single()
        assertEquals("Bouncing Ball", summary.name)
        assertEquals(12, summary.frameCount)
        assertEquals(12, summary.fps)
        assertEquals(1000L, summary.durationMs)
        assertNotNull(summary.thumbnail)
        val opened = runBlocking { repository.open(id) } ?: error("no sample")
        val project = opened.loaded.project
        assertEquals(12, project.cels.size)
        assertEquals(0, opened.loaded.missingCels)
        val first = Bitmap.createBitmap(project.width, project.height, Bitmap.Config.ARGB_8888)
        FrameFlattener(opened.store).flatten(project, project.frames[0].id, Canvas(first), 1f, null)
        // The ball starts high on the left, over off-white paper, and nothing is below it yet but its shadow.
        assertEquals(0xFFE4572E.toInt(), first.getPixel(240, 112))
        opened.store.close()
    }

    private companion object {
        const val FINISH_TIMEOUT_MS = 20_000L
        const val SHORT_WAIT_MS = 300L
    }
}
