package org.kineograph.media

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kineograph.data.ProjectRepository
import org.kineograph.data.SampleProject
import org.kineograph.data.SettingsRepository
import org.kineograph.data.db.AppDatabase
import org.kineograph.data.files.ProjectFiles
import org.kineograph.media.export.ExportConfig
import org.kineograph.media.export.ExportFormat
import org.kineograph.media.export.ExportManager
import org.kineograph.media.export.ExportStatus
import org.kineograph.media.export.ExportWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

/**
 * An export belongs to WorkManager and not to any screen, so this goes through the real one, starting and finding exports
 * the way an editor does, and judging by what a new editor would be able to see.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = ExportTestApp::class)
class ExportManagerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var database: AppDatabase
    private lateinit var projectId: String
    private lateinit var settings: SettingsRepository
    private lateinit var manager: ExportManager

    /** Holds workers back until told, which keeps an export running for as long as a test needs it to. */
    private class HeldBack : CoroutineDispatcher() {
        private val waiting = ConcurrentLinkedQueue<Runnable>()

        @Volatile
        var holding = true

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (holding) waiting.add(block) else Dispatchers.Default.dispatch(context, block)
        }

        fun release() {
            holding = false
            while (true) Dispatchers.Default.dispatch(Dispatchers.Default, waiting.poll() ?: break)
        }
    }

    private val workers = HeldBack()
    private val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val repository =
            ProjectRepository(database.projects(), ProjectFiles(temp.newFolder(), temp.newFolder()), 16L * 1024 * 1024, Dispatchers.IO)
        ExportTestApp.repository = repository
        projectId = runBlocking { SampleProject.create(repository, "Bouncing Ball", "Ball", 0) }
        // WorkManager is a singleton that would otherwise still be holding on to the previous test's world.
        val field = WorkManagerImpl::class.java.getDeclaredField("sDefaultInstance")
        field.isAccessible = true
        field.set(null, null)
        WorkManagerImpl.setDelegate(null)
        WorkManager.initialize(context, Configuration.Builder().setWorkerCoroutineContext(workers).build())
        settings = SettingsRepository(context)
        manager = ExportManager(context, settings)
    }

    @After
    fun tearDown() {
        watcherScope.cancel()
        workers.release()
        WorkManager.getInstance(context).cancelAllWork()
        database.close()
    }

    private val config get() = ExportConfig(projectId, ExportFormat.GIF, scale = 0.1f)

    private fun start(): UUID = runBlocking { manager.start(config) }

    /** WorkManager starts each worker on the main thread, which here means this one, so it has to keep turning over. */
    private fun endOf(id: UUID, watcher: ExportManager = manager): ExportStatus {
        val end = CompletableDeferred<ExportStatus>()
        val watching = watcherScope.launch { end.complete(watcher.observe(id).first { it !is ExportStatus.Running }) }
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!end.isCompleted) {
            check(System.currentTimeMillis() < deadline) { "The export never ended: ${stateOf(id)}" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        watching.cancel()
        return end.getCompleted()
    }

    private fun stateOf(id: UUID): WorkInfo.State? = WorkManager
        .getInstance(context)
        .getWorkInfoById(id)
        .get()
        ?.state

    private fun waitForRunning(id: UUID) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (stateOf(id) != WorkInfo.State.RUNNING) {
            check(System.currentTimeMillis() < deadline) { "The export never started: ${stateOf(id)}" }
            Thread.sleep(5)
        }
    }

    private fun finished(id: UUID): ExportStatus.Finished {
        workers.release()
        val status = endOf(id)
        assertTrue("expected a finished export, not $status", status is ExportStatus.Finished)
        return status as ExportStatus.Finished
    }

    /** What is left when the editor and everything in it has gone: only what WorkManager and the settings file know. */
    private fun afterTheEditorHasGone() = ExportManager(context, settings)

    @Test
    fun `a running export can be found by a new editor`() {
        val id = start()
        val found = runBlocking { afterTheEditorHasGone().findUnseen(projectId) }
        assertEquals(id, found)
    }

    @Test
    fun `starting again while one is running carries on with that one`() {
        val id = start()
        waitForRunning(id)
        val again = start()
        assertEquals(id, again)
    }

    @Test
    fun `a finished export can be found and opened by a new editor`() {
        val id = start()
        finished(id)
        val next = afterTheEditorHasGone()
        assertEquals(id, runBlocking { next.findUnseen(projectId) })
        val status = endOf(id, next)
        assertTrue(status is ExportStatus.Finished)
        status as ExportStatus.Finished
        assertEquals(ExportFormat.GIF, status.format)
        assertTrue(status.file.exists())
        assertTrue(status.width > 0)
    }

    @Test
    fun `a result the user has finished with does not come back`() {
        val id = start()
        finished(id)
        runBlocking { manager.markSeen(id) }
        assertNull(runBlocking { afterTheEditorHasGone().findUnseen(projectId) })
    }

    @Test
    fun `the notification saying it is ready goes once the user has seen it`() {
        val id = start()
        finished(id)
        val notifications = context.getSystemService(android.app.NotificationManager::class.java)
        val shadow = shadowOf(notifications)
        assertTrue(shadow.getNotification(ExportWorker.RESULT_TAG, id.hashCode()) != null)
        manager.clearResultNotification(id)
        assertNull(shadow.getNotification(ExportWorker.RESULT_TAG, id.hashCode()))
    }

    @Test
    fun `a new export after one was seen is a new thing to see`() {
        val first = start()
        finished(first)
        runBlocking { manager.markSeen(first) }
        val second = start()
        assertNotEquals(first, second)
        assertEquals(second, runBlocking { manager.findUnseen(projectId) })
        endOf(second)
    }

    @Test
    fun `a cancelled export is not offered to a new editor`() {
        val id = start()
        manager.cancel(id)
        assertEquals(ExportStatus.Cancelled, endOf(id))
        assertNull(runBlocking { afterTheEditorHasGone().findUnseen(projectId) })
    }

    @Test
    fun `a failed export is offered, because nobody may have been there to hear about it`() {
        val config = ExportConfig("not a project", ExportFormat.GIF)
        val id = runBlocking { manager.start(config) }
        workers.release()
        assertEquals(ExportStatus.Failed(ExportWorker.ERROR_NO_PROJECT), endOf(id))
        assertEquals(id, runBlocking { afterTheEditorHasGone().findUnseen("not a project") })
    }

    @Test
    fun `a file Android has cleared is explained rather than offered`() {
        val id = start()
        finished(id).file.delete()
        assertEquals(ExportStatus.Failed(ExportWorker.ERROR_FILE_GONE), endOf(id))
    }

    @Test
    fun `a project with nothing exported has nothing to find`() {
        assertNull(runBlocking { manager.findUnseen(projectId) })
    }

    @Test
    fun `tidying up leaves what the user has not seen and clears what they have`() {
        val unseen = start()
        val unseenFile = finished(unseen).file
        val seenProject = runBlocking { SampleProject.create(checkNotNull(ExportTestApp.repository), "Other", "Ball", 0) }
        val seen = runBlocking { manager.start(ExportConfig(seenProject, ExportFormat.GIF, scale = 0.1f)) }
        val seenFile = (endOf(seen) as ExportStatus.Finished).file
        runBlocking { manager.markSeen(seen) }

        runBlocking { manager.pruneOldExports(System.currentTimeMillis() + 2 * DAY) }

        assertTrue(unseenFile.exists())
        assertFalse(seenFile.exists())
    }

    @Test
    fun `tidying up leaves a recent export alone even once it has been seen`() {
        val id = start()
        val file = finished(id).file
        runBlocking { manager.markSeen(id) }
        runBlocking { manager.pruneOldExports() }
        assertTrue(file.exists())
    }

    @Test
    fun `seen exports that no longer exist are forgotten`() {
        runBlocking { settings.markExportSeen("long gone") }
        val id = start()
        finished(id)
        runBlocking { manager.markSeen(id) }
        runBlocking { manager.pruneOldExports() }
        val seen = runBlocking { settings.seenExports() }
        assertTrue(id.toString() in seen)
        assertFalse("long gone" in seen)
    }

    @Test
    fun `deleting a finished export's file takes its directory with it`() {
        val id = start()
        val file = finished(id).file
        runBlocking { manager.deleteFile(file) }
        assertFalse(file.parentFile?.exists() == true)
    }

    private companion object {
        const val WAIT_MS = 20_000L
        const val DAY = 24L * 60L * 60L * 1000L
    }
}
