package org.kineograph.data

import android.content.Context
import android.database.sqlite.SQLiteFullException
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kineograph.data.db.AppDatabase
import org.kineograph.data.db.ProjectDao
import org.kineograph.data.db.ProjectEntity
import org.kineograph.data.files.CelFileStore
import org.kineograph.data.files.ProjectFiles
import org.kineograph.data.manifest.ManifestCodec
import org.kineograph.engine.brush.BrushSpec
import org.kineograph.engine.brush.PressureCurve
import org.kineograph.engine.brush.StampBuffer
import org.kineograph.engine.brush.StrokeEngine
import org.kineograph.engine.compositor.FlattenOptions
import org.kineograph.engine.compositor.FrameFlattener
import org.kineograph.engine.session.CelStore
import org.kineograph.engine.session.DrawingSession
import org.kineograph.engine.session.MemoryBudget
import org.kineograph.model.Background
import org.kineograph.model.PaperKind
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

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
    fun `only a file that has really gone counts as gone`() {
        val project = create()
        val directory = files.projectDirectory(project.id)
        val store = CelFileStore(directory, 1024 * 1024)
        assertTrue("it was never there", store.isGone("cels/missing.webp"))
        assertTrue("this could never be read", store.isGone("../manifest.json"))

        val name = store.write(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
        assertFalse("still on its way to the disk", store.isGone(name))
        runBlocking { store.awaitWrites() }
        assertFalse(store.isGone(name))
        store.close()

        val fresh = CelFileStore(directory, 1024 * 1024)
        assertFalse("on disk, not in memory", fresh.isGone(name))
        File(directory, name).delete()
        assertTrue(fresh.isGone(name))
        fresh.close()
    }

    @Test
    fun `a read that fails with the file still there does not count as gone`() {
        val project = create()
        val directory = files.projectDirectory(project.id)
        val store = CelFileStore(directory, 1024 * 1024)
        // Reading a directory fails with an error, which is as close as this gets to a passing disk problem.
        File(directory, "cels/unreadable.webp").mkdirs()
        assertNull(store.read("cels/unreadable.webp"))
        assertFalse(store.isGone("cels/unreadable.webp"))
        store.close()
    }

    @Test
    fun `not being able to tell whether a file is there does not count as gone`() {
        val project = create()
        val directory = files.projectDirectory(project.id)
        val store = CelFileStore(directory, 1024 * 1024)
        // A plain file where the directory should be makes the system answer with an error rather than "no such file".
        File(directory, "cels").deleteRecursively()
        File(directory, "cels").writeText("not a directory")
        assertNull(store.read("cels/any.webp"))
        assertFalse(store.isGone("cels/any.webp"))
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

    // ----- What a sweep may delete -----

    private inner class Editor(project: org.kineograph.model.Project, store: CelStore? = null) {
        val opened = runBlocking { repository.open(project.id) } ?: error("could not open")
        val session =
            DrawingSession(opened.loaded.project, store ?: opened.store, MemoryBudget.forMemoryClass(256, false), temp.newFolder())
        val autosaver = Autosaver(session, repository, opened.store, project.id)

        fun stroke(y: Float = 50f) {
            val spec = BrushSpec(id = "t", sizePx = 10f, sizeCurve = PressureCurve.FLAT)
            val out = StampBuffer()
            StrokeEngine(spec, 1L).run {
                begin(20f, y, 1f, 0f, 0f, out)
                add(100f, y, 1f, 0f, 0f, out)
                finish(out)
            }
            session.beginStroke(spec, Color.BLACK)
            session.addStamps(out.snapshot())
            session.endStroke { }
        }

        fun close() {
            session.close()
            opened.store.close()
        }
    }

    private fun reopened(id: String) = runBlocking { repository.open(id) } ?: error("could not reopen")

    private fun inkAt(id: String, x: Int, y: Int): Int {
        val opened = reopened(id)
        val project = opened.loaded.project
        val image = Bitmap.createBitmap(project.width, project.height, Bitmap.Config.ARGB_8888)
        val options = FlattenOptions(includeBackground = false)
        FrameFlattener(opened.store).flatten(project, project.frames[0].id, Canvas(image), 1f, null, options)
        opened.store.close()
        return image.getPixel(x, y)
    }

    /** Nothing can be written over a directory, which is the nearest thing here to a disk with no space left. */
    private fun blockManifestWrites(id: String) {
        File(files.projectDirectory(id), "manifest.tmp/keep").mkdirs()
    }

    private fun unblockManifestWrites(id: String) {
        File(files.projectDirectory(id), "manifest.tmp").deleteRecursively()
    }

    @Test
    fun `a sweep never deletes what a manifest on disk refers to`() {
        val project = create()
        val editor = Editor(project)
        editor.stroke()
        assertTrue(runBlocking { editor.autosaver.saveNow() })
        val saved = reopened(project.id)
            .also { it.store.close() }
            .loaded.project
            .referencedFiles()
        assertEquals(1, saved.size)

        runBlocking { repository.sweep(project.id, editor.opened.store, saved, emptySet()) }

        assertTrue(File(files.projectDirectory(project.id), saved.single()).isFile)
        assertEquals(0, reopened(project.id).also { it.store.close() }.loaded.missingCels)
        editor.close()
    }

    @Test
    fun `a sweep deletes nothing while a manifest cannot be read, as it could need anything`() {
        val project = create()
        val editor = Editor(project)
        val leftover = File(files.projectDirectory(project.id), "cels/leftover.webp").apply {
            parentFile?.mkdirs()
            writeText("x")
        }
        File(files.projectDirectory(project.id), "manifest.json").writeText("{ damaged")
        runBlocking { repository.sweep(project.id, editor.opened.store, setOf("cels/leftover.webp"), emptySet()) }
        assertTrue(leftover.isFile)
        editor.close()
    }

    @Test
    fun `leaving after a failed save keeps the last good drawing and sweeps nothing`() {
        val project = create()
        val editor = Editor(project)
        editor.stroke(y = 30f)
        assertTrue(runBlocking { editor.autosaver.saveNow() })
        val lastGood = reopened(project.id)
            .also { it.store.close() }
            .loaded.project
            .referencedFiles()
            .single()

        // The drawing moves on, so the session no longer refers to the last good file, and then the disk fills up.
        editor.stroke(y = 70f)
        val leftover = File(files.projectDirectory(project.id), "cels/leftover.webp").apply { writeText("x") }
        blockManifestWrites(project.id)
        runBlocking { editor.autosaver.finish() }
        editor.close()
        unblockManifestWrites(project.id)

        assertTrue(editor.autosaver.saveFailed.value)
        assertTrue("a failed save must not be followed by a sweep", leftover.isFile)
        assertTrue(File(files.projectDirectory(project.id), lastGood).isFile)
        val back = reopened(project.id)
        assertEquals(0, back.loaded.missingCels)
        assertEquals(setOf(lastGood), back.loaded.project.referencedFiles())
        back.store.close()
        assertEquals(Color.BLACK, inkAt(project.id, 60, 30))
    }

    @Test
    fun `leaving after a good save still sweeps leftovers`() {
        val project = create()
        val editor = Editor(project)
        editor.stroke()
        val leftover = File(files.projectDirectory(project.id), "cels/leftover.webp").apply {
            parentFile?.mkdirs()
            writeText("x")
        }
        runBlocking { editor.autosaver.finish() }
        editor.close()
        assertFalse(leftover.exists())
    }

    @Test
    fun `a save reports whether it reached the disk and can recover once there is room`() {
        val project = create()
        val editor = Editor(project)
        editor.stroke()
        blockManifestWrites(project.id)
        assertFalse(runBlocking { editor.autosaver.saveNow() })
        assertTrue(editor.autosaver.saveFailed.value)
        assertFalse("still nothing on disk, however often it is asked", runBlocking { editor.autosaver.saveNow() })
        unblockManifestWrites(project.id)
        assertTrue(runBlocking { editor.autosaver.saveNow() })
        assertFalse(editor.autosaver.saveFailed.value)
        editor.close()
        assertEquals(Color.BLACK, inkAt(project.id, 60, 50))
    }

    @Test
    fun `abandoning writes nothing, so the last save is what comes back`() {
        val project = create()
        val editor = Editor(project)
        editor.stroke(y = 30f)
        assertTrue(runBlocking { editor.autosaver.saveNow() })
        editor.stroke(y = 70f)
        runBlocking {
            editor.session.awaitIdle()
            editor.autosaver.abandon()
        }
        editor.close()
        assertEquals(Color.BLACK, inkAt(project.id, 60, 30))
        assertEquals(0, inkAt(project.id, 60, 70))
    }

    @Test
    fun `after abandoning nothing can save, however it is asked and even with room on the disk`() {
        val project = create()
        val editor = Editor(project)
        editor.stroke(y = 30f)
        assertTrue(runBlocking { editor.autosaver.saveNow() })
        val manifest = File(files.projectDirectory(project.id), "manifest.json")
        val before = manifest.readText()
        editor.stroke(y = 70f)
        runBlocking { editor.session.awaitIdle() }
        editor.autosaver.abandon()
        now += 5000
        assertFalse(runBlocking { editor.autosaver.saveNow() })
        assertFalse(runBlocking { editor.autosaver.saveNow(commitSelection = true) })
        runBlocking { editor.autosaver.finish() }
        assertEquals(before, manifest.readText())
        editor.close()
        assertEquals(0, inkAt(project.id, 60, 70))
    }

    @Test
    fun `leaving with nothing changed does not write a new revision`() {
        val project = create()
        val editor = Editor(project)
        editor.autosaver.start(CoroutineScope(Dispatchers.Default))
        val manifest = File(files.projectDirectory(project.id), "manifest.json")
        val before = manifest.readText()
        now += 5000
        assertTrue(runBlocking { editor.autosaver.saveNow(commitSelection = true) })
        assertEquals(before, manifest.readText())
        runBlocking { editor.autosaver.finish() }
        assertEquals(before, manifest.readText())
        editor.close()
    }

    @Test
    fun `a save straight after a stroke includes that stroke, whatever is still queued`() {
        val project = create()
        val editor = Editor(project)
        editor.autosaver.start(CoroutineScope(Dispatchers.Default))
        editor.stroke()
        assertTrue(runBlocking { editor.autosaver.saveNow(commitSelection = true) })
        editor.close()
        assertEquals(Color.BLACK, inkAt(project.id, 60, 50))
    }

    @Test
    fun `pixels held back by a floating selection are saved once it ends without an edit`() {
        for (end in listOf<(DrawingSession) -> Unit>({ it.cancelSelection() }, { it.commitSelection() })) {
            val project = create("Selection")
            val editor = Editor(project)
            editor.autosaver.start(CoroutineScope(Dispatchers.Default))
            editor.stroke()
            editor.stroke(y = 25f)
            editor.session.liftSelection(floatArrayOf(10f, 10f, 190f, 10f, 190f, 40f, 10f, 40f))
            runBlocking {
                editor.session.awaitIdle()
                assertTrue(editor.autosaver.saveNow())
            }
            end(editor.session)
            runBlocking {
                editor.session.awaitIdle()
                assertTrue(editor.autosaver.saveNow())
            }
            editor.close()
            assertEquals("the stroke below the selection", Color.BLACK, inkAt(project.id, 60, 50))
            assertEquals("the lifted stroke", Color.BLACK, inkAt(project.id, 60, 25))
        }
    }

    // ----- Running out of memory while saving -----

    private class OutOfMemoryOnce(private val inner: CelStore) : CelStore by inner {
        @Volatile
        var fail = false

        override fun write(image: Bitmap): String {
            if (fail) {
                fail = false
                throw OutOfMemoryError("Test")
            }
            return inner.write(image)
        }
    }

    @Test
    fun `running out of memory while saving is a failed save, and the next one works`() {
        val project = create()
        val opened = runBlocking { repository.open(project.id) } ?: error("could not open")
        val failing = OutOfMemoryOnce(opened.store)
        val session = DrawingSession(opened.loaded.project, failing, MemoryBudget.forMemoryClass(256, false), temp.newFolder())
        val autosaver = Autosaver(session, repository, opened.store, project.id)
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
        failing.fail = true
        assertFalse(runBlocking { autosaver.saveNow() })
        assertTrue(autosaver.saveFailed.value)
        assertTrue(runBlocking { autosaver.saveNow() })
        assertFalse(autosaver.saveFailed.value)
        session.close()
        opened.store.close()
        assertEquals(Color.BLACK, inkAt(project.id, 60, 50))
    }

    // ----- Copies -----

    @Test
    fun `a duplicate has a manifest of its own and leaves nothing being staged`() {
        val project = create("Source")
        val editor = Editor(project)
        editor.stroke()
        assertTrue(runBlocking { editor.autosaver.saveNow() })
        editor.close()
        val copy = runBlocking { repository.duplicate(project.id, "Copy") } ?: error("no copy")
        val directory = files.projectDirectory(copy)
        assertEquals(copy, ManifestCodec.decode(File(directory, "manifest.json").readText()).id)
        assertFalse("the source's manifests must not come along", File(directory, "manifest.prev.json").exists())
        assertFalse(files.stagingDirectory(copy).exists())
        assertEquals(emptyList<String>(), files.listStagingIds())
        assertEquals(Color.BLACK, inkAt(copy, 60, 50))
    }

    @Test
    fun `start-up clears copies that were never finished`() {
        val leftover = File(files.stagingDirectory("cut-short"), "cels/a.webp").apply {
            parentFile?.mkdirs()
            writeText("x")
        }
        runBlocking { repository.reconcile() }
        assertFalse(leftover.exists())
        assertEquals(emptyList<String>(), files.listStagingIds())
        assertTrue(list().isEmpty())
    }

    /** What an earlier build left when killed part-way through a copy: a directory with the source's manifests and only some cels. */
    private fun interruptedCopyOf(id: String): String {
        val copyId = "interrupted-copy"
        val copy = files.projectDirectory(copyId)
        files.projectDirectory(id).copyRecursively(copy)
        File(copy, "cels").deleteRecursively()
        return copyId
    }

    @Test
    fun `an interrupted copy never touches the original, however many times the app starts`() {
        val project = create("Source")
        val editor = Editor(project)
        editor.stroke()
        assertTrue(runBlocking { editor.autosaver.saveNow() })
        editor.stroke(y = 20f)
        assertTrue(runBlocking { editor.autosaver.saveNow() })
        editor.close()
        val source = files.projectDirectory(project.id)
        val manifest = File(source, "manifest.json").readText()
        val backup = File(source, "manifest.prev.json").readText()
        val copyId = interruptedCopyOf(project.id)

        repeat(2) { runBlocking { repository.reconcile() } }

        assertEquals(manifest, File(source, "manifest.json").readText())
        assertEquals(backup, File(source, "manifest.prev.json").readText())
        assertEquals(listOf(project.id), list().map { it.id })
        assertFalse("nothing in it adds to the original", files.projectDirectory(copyId).exists())
        assertEquals(Color.BLACK, inkAt(project.id, 60, 50))
    }

    @Test
    fun `an unfinished copy whose original has gone is kept as a project of its own`() {
        val project = create("Source")
        val editor = Editor(project)
        editor.stroke()
        assertTrue(runBlocking { editor.autosaver.saveNow() })
        editor.close()
        val copyId = "orphan-copy"
        files.projectDirectory(project.id).copyRecursively(files.projectDirectory(copyId))
        runBlocking { repository.deletePermanently(project.id) }

        runBlocking { repository.reconcile() }

        assertEquals(listOf(copyId), list().map { it.id })
        assertEquals(copyId, ManifestCodec.decode(File(files.projectDirectory(copyId), "manifest.json").readText()).id)
        assertEquals(Color.BLACK, inkAt(copyId, 60, 50))
    }

    @Test
    fun `a copy falling back to a backup with the source's id keeps saving in its own directory`() {
        val project = create("Source")
        val copy = runBlocking { repository.duplicate(project.id, "Copy") } ?: error("no copy")
        val source = files.projectDirectory(project.id)
        val sourceManifest = File(source, "manifest.json").readText()
        val directory = files.projectDirectory(copy)
        // An earlier build's copy: its first save rotated the source's manifest into the backup. Then its own is damaged.
        File(source, "manifest.json").copyTo(File(directory, "manifest.prev.json"))
        File(directory, "manifest.json").writeText("{ damaged")

        val opened = reopened(copy)
        assertEquals(copy, opened.loaded.project.id)
        assertTrue(opened.loaded.recoveredFromBackup)
        runBlocking { repository.save(opened.loaded.project, opened.store) }
        opened.store.close()
        runBlocking { repository.rename(copy, "Renamed") }

        assertEquals(sourceManifest, File(source, "manifest.json").readText())
        assertEquals("Renamed", ManifestCodec.decode(File(directory, "manifest.json").readText()).name)
        assertEquals(copy, ManifestCodec.decode(File(directory, "manifest.json").readText()).id)
    }

    // ----- Storage problems never reach callers as anything but an IOException -----

    private class FailingDao(private val inner: ProjectDao) : ProjectDao by inner {
        @Volatile
        var failing = false

        private fun check() {
            if (failing) throw SQLiteFullException("Test")
        }

        override suspend fun upsert(entity: ProjectEntity) {
            check()
            inner.upsert(entity)
        }

        override suspend fun setTrashed(id: String, trashedAt: Long?) {
            check()
            inner.setTrashed(id, trashedAt)
        }

        override suspend fun delete(id: String) {
            check()
            inner.delete(id)
        }

        override suspend fun allIds(): List<String> {
            check()
            return inner.allIds()
        }

        override suspend fun count(): Int {
            check()
            return inner.count()
        }
    }

    private val failingDao by lazy { FailingDao(database.projects()) }

    private fun useFailingDao() {
        repository = ProjectRepository(failingDao, files, 8L * 1024 * 1024, Dispatchers.IO) { now }
    }

    @Test
    fun `a project that cannot be created leaves nothing behind, including its imported background`() {
        useFailingDao()
        failingDao.failing = true
        val id = "new-project"
        val background = File(files.projectDirectory(id), "media/background.webp").apply {
            parentFile?.mkdirs()
            writeText("x")
        }
        assertThrows(IOException::class.java) {
            runBlocking { repository.create("New", 200, 100, 12, Background.Transparent, "Layer 1", id) }
        }
        assertFalse(background.exists())
        assertFalse(files.projectDirectory(id).exists())
    }

    @Test
    fun `index failures reach callers as a storage problem`() {
        useFailingDao()
        val project = create()
        failingDao.failing = true
        assertThrows(IOException::class.java) { runBlocking { repository.moveToTrash(project.id) } }
        assertThrows(IOException::class.java) { runBlocking { repository.restoreFromTrash(project.id) } }
        assertThrows(IOException::class.java) { runBlocking { repository.rename(project.id, "New name") } }
        assertThrows(IOException::class.java) { runBlocking { repository.isEmpty() } }
        assertThrows(IOException::class.java) { runBlocking { repository.reconcile() } }
    }

    @Test
    fun `a failed duplicate leaves nothing behind and reports it`() {
        useFailingDao()
        val project = create()
        failingDao.failing = true
        assertNull(runBlocking { repository.duplicate(project.id, "Copy") })
        assertEquals(listOf(project.id), files.listProjectIds())
        assertEquals(emptyList<String>(), files.listStagingIds())
    }

    @Test
    fun `a project can be deleted even when the index cannot write, and start-up finishes the job`() {
        useFailingDao()
        val project = create()
        failingDao.failing = true
        runBlocking { repository.deletePermanently(project.id) }
        assertFalse(files.projectDirectory(project.id).exists())
        failingDao.failing = false
        runBlocking { repository.reconcile() }
        assertTrue(list().isEmpty())
    }

    @Test
    fun `autosave treats an index that cannot write as a failed save`() {
        useFailingDao()
        val project = create()
        val editor = Editor(project)
        editor.stroke()
        failingDao.failing = true
        assertFalse(runBlocking { editor.autosaver.saveNow() })
        assertTrue(editor.autosaver.saveFailed.value)
        failingDao.failing = false
        assertTrue(runBlocking { editor.autosaver.saveNow() })
        editor.close()
    }

    @Test
    fun `a directory that cannot be added to the index does not stop the rest of start-up`() {
        useFailingDao()
        val adrift = create("Adrift")
        database.projects().let { dao -> runBlocking { dao.delete(adrift.id) } }
        val manifest = File(files.projectDirectory(adrift.id), "manifest.json").readText()
        val failOnlyUpsert =
            object : ProjectDao by database.projects() {
                override suspend fun upsert(entity: ProjectEntity): Unit = throw SQLiteFullException("Test")
            }
        repository = ProjectRepository(failOnlyUpsert, files, 8L * 1024 * 1024, Dispatchers.IO) { now }
        runBlocking { repository.reconcile() }
        assertEquals(manifest, File(files.projectDirectory(adrift.id), "manifest.json").readText())

        repository = ProjectRepository(database.projects(), files, 8L * 1024 * 1024, Dispatchers.IO) { now }
        runBlocking { repository.reconcile() }
        assertEquals(listOf(adrift.id), list().map { it.id })
    }

    @Test
    fun `rebuilding a lost index does not write over the backup`() {
        val project = create()
        val editor = Editor(project)
        editor.stroke()
        assertTrue(runBlocking { editor.autosaver.saveNow() })
        editor.close()
        val directory = files.projectDirectory(project.id)
        val manifest = File(directory, "manifest.json").readText()
        val backup = File(directory, "manifest.prev.json").readText()
        runBlocking { database.projects().delete(project.id) }
        runBlocking { repository.reconcile() }
        assertEquals(1, list().size)
        assertEquals(manifest, File(directory, "manifest.json").readText())
        assertEquals(backup, File(directory, "manifest.prev.json").readText())
    }

    // ----- The sample project -----

    @Test
    fun `a sample that cannot be saved leaves nothing, and the next launch can make it`() {
        useFailingDao()
        failingDao.failing = true
        assertThrows(IOException::class.java) {
            runBlocking { SampleProject.create(repository, "Ball", "Layer", now) }
        }
        assertEquals(emptyList<String>(), files.listProjectIds())
        failingDao.failing = false
        assertTrue(runBlocking { repository.isEmpty() })
        runBlocking { SampleProject.create(repository, "Ball", "Layer", now) }
        assertEquals(12, list().single().frameCount)
    }

    @Test
    fun `a directory that never got a manifest does not count as a project`() {
        val orphan = files.projectDirectory("cut-short")
        File(orphan, "cels/a.webp").apply {
            parentFile?.mkdirs()
            writeText("x")
        }
        assertTrue(runBlocking { repository.isEmpty() })
        File(orphan, "manifest.tmp").writeText("{ half written")
        assertTrue("an unfinished first write", runBlocking { repository.isEmpty() })
    }

    @Test
    fun `a damaged manifest still counts as a project`() {
        val project = files.projectDirectory("damaged")
        project.mkdirs()
        File(project, "manifest.json").writeText("{ damaged")
        assertFalse(runBlocking { repository.isEmpty() })
    }

    @Test
    fun `start-up removes an old directory that never got a manifest and keeps a recent one`() {
        val orphan = files.projectDirectory("cut-short")
        val cel = File(orphan, "cels/a.webp").apply {
            parentFile?.mkdirs()
            writeText("x")
        }
        now = System.currentTimeMillis()
        runBlocking { repository.reconcile() }
        assertTrue("this could be a project being created right now", cel.exists())
        now += 2 * 60 * 60 * 1000L
        runBlocking { repository.reconcile() }
        assertFalse(orphan.exists())
    }

    @Test
    fun `start-up keeps a directory whose manifest is damaged however old it is`() {
        val project = files.projectDirectory("damaged")
        project.mkdirs()
        val manifest = File(project, "manifest.json").apply { writeText("{ damaged") }
        now = System.currentTimeMillis() + 10 * 24 * 60 * 60 * 1000L
        runBlocking { repository.reconcile() }
        assertTrue(manifest.exists())
        assertTrue(list().isEmpty())
    }

    private companion object {
        const val FINISH_TIMEOUT_MS = 20_000L
        const val SHORT_WAIT_MS = 300L
    }
}
