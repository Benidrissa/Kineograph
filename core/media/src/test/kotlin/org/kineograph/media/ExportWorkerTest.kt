package org.kineograph.media

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import org.kineograph.data.ProjectRepository
import org.kineograph.data.SampleProject
import org.kineograph.data.db.AppDatabase
import org.kineograph.data.files.ProjectFiles
import org.kineograph.media.export.ExportConfig
import org.kineograph.media.export.ExportDependencies
import org.kineograph.media.export.ExportFormat
import org.kineograph.media.export.ExportWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Workers are made by the system, so this is how they get what they need in the app, and here. */
class ExportTestApp :
    Application(),
    ExportDependencies {
    override val projectRepository: ProjectRepository get() = checkNotNull(repository)

    companion object {
        var repository: ProjectRepository? = null
    }
}

/** Runs the real worker on the real sample project, as far as a GIF goes, with only WorkManager's plumbing stood in for. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = ExportTestApp::class)
class ExportWorkerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private lateinit var database: AppDatabase
    private lateinit var projectId: String

    private val executor = Executors.newSingleThreadExecutor()

    /** Stands in for the foreground service. Refusing is what the system may do when the app is in the background. */
    private var foregroundRefused = false
    private var onProgress: () -> Unit = {}

    @Before
    fun setUp() {
        try {
            WorkManager.initialize(context, Configuration.Builder().build())
        } catch (_: IllegalStateException) {
            // Already set up by an earlier test in this process.
        }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val repository =
            ProjectRepository(database.projects(), ProjectFiles(temp.newFolder(), temp.newFolder()), 16L * 1024 * 1024, Dispatchers.IO)
        ExportTestApp.repository = repository
        projectId = runBlocking { SampleProject.create(repository, "Bouncing Ball", "Ball", 0) }

        // The launcher's entry for this app, which is what a tap on a notification goes back through.
        val launcher =
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = context.packageName
                        name = "org.kineograph.TestActivity"
                    }
            }
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        shadowOf(context.packageManager).addResolveInfoForIntent(launch, launcher)
    }

    @After
    fun tearDown() {
        executor.shutdown()
        database.close()
    }

    private fun done(): ListenableFuture<Void> = SettableFuture.create<Void>().apply { set(null) }

    private fun worker(
        id: UUID = UUID.randomUUID(),
        config: ExportConfig = ExportConfig(
            projectId,
            ExportFormat.GIF,
            scale = 0.1f,
        ),
    ): ExportWorker {
        val input =
            workDataOf(
                ExportWorker.KEY_CONFIG to Json.encodeToString(ExportConfig.serializer(), config),
                ExportWorker.KEY_PROJECT_ID to config.projectId,
            )
        val parameters =
            WorkerParameters(
                id,
                input,
                emptyList(),
                WorkerParameters.RuntimeExtras(),
                0,
                0,
                executor,
                Dispatchers.Default,
                WorkManagerTaskExecutor(executor),
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) = null
                },
                object : ProgressUpdater {
                    override fun updateProgress(context: Context, id: UUID, data: Data): ListenableFuture<Void> {
                        onProgress()
                        return done()
                    }
                },
                object : ForegroundUpdater {
                    override fun setForegroundAsync(context: Context, id: UUID, foregroundInfo: ForegroundInfo): ListenableFuture<Void> {
                        check(!foregroundRefused) { "Not allowed in the background" }
                        return done()
                    }
                },
            )
        return ExportWorker(context, parameters)
    }

    private fun success(result: ListenableWorker.Result): Data {
        assertTrue("expected success, not $result", result is ListenableWorker.Result.Success)
        return result.outputData
    }

    private fun failure(result: ListenableWorker.Result): String? {
        assertTrue("expected failure, not $result", result is ListenableWorker.Result.Failure)
        return result.outputData.getString(ExportWorker.KEY_ERROR)
    }

    private fun resultNotification(id: UUID) = shadowOf(notifications).getNotification(ExportWorker.RESULT_TAG, id.hashCode())

    private fun progressNotification(id: UUID) = shadowOf(notifications).getNotification(null, id.hashCode())

    private fun projectTheTapOpens(notification: android.app.Notification): String? =
        shadowOf(notification.contentIntent).savedIntent.getStringExtra(ExportWorker.EXTRA_PROJECT_ID)

    @Test
    fun `each export gets its own file, so one in use elsewhere is not written over`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val firstPath = success(runBlocking { worker(first).doWork() }).getString(ExportWorker.KEY_OUTPUT_PATH)
        val secondPath = success(runBlocking { worker(second).doWork() }).getString(ExportWorker.KEY_OUTPUT_PATH)
        assertNotEquals(firstPath, secondPath)
        assertEquals(File(context.cacheDir, "exports/$first/Bouncing Ball.gif").path, firstPath)
        assertTrue(File(checkNotNull(firstPath)).exists())
        assertTrue(File(checkNotNull(secondPath)).exists())
    }

    @Test
    fun `the result records what is needed to find it again`() {
        val data = success(runBlocking { worker().doWork() })
        assertEquals(ExportFormat.GIF.name, data.getString(ExportWorker.KEY_FORMAT))
        assertTrue(data.getInt(ExportWorker.KEY_WIDTH, 0) > 0)
    }

    @Test
    fun `a finished export says so, and a tap goes to the project`() {
        val id = UUID.randomUUID()
        success(runBlocking { worker(id).doWork() })
        val notification = resultNotification(id)
        assertNotNull(notification)
        assertEquals(projectId, projectTheTapOpens(notification))
        assertTrue(notification.flags and android.app.Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun `tapping the progress notification goes to the project too`() {
        val id = UUID.randomUUID()
        foregroundRefused = true
        var seen: android.app.Notification? = null
        onProgress = { seen = progressNotification(id) ?: seen }
        success(runBlocking { worker(id).doWork() })
        assertEquals(projectId, projectTheTapOpens(checkNotNull(seen)))
    }

    @Test
    fun `progress posted without the foreground service does not outlive the export`() {
        val id = UUID.randomUUID()
        foregroundRefused = true
        var appeared = false
        onProgress = { appeared = appeared || progressNotification(id) != null }
        success(runBlocking { worker(id).doWork() })
        assertTrue("the premise: it was showing during the export", appeared)
        assertNull(progressNotification(id))
    }

    @Test
    fun `a failure is reported to a user who has left the app`() {
        val id = UUID.randomUUID()
        val config = ExportConfig("not a project", ExportFormat.GIF)
        assertEquals(ExportWorker.ERROR_NO_PROJECT, failure(runBlocking { worker(id, config).doWork() }))
        assertEquals("not a project", projectTheTapOpens(checkNotNull(resultNotification(id))))
    }

    @Test
    fun `cancelling leaves nothing behind and announces nothing`() {
        val id = UUID.randomUUID()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                val job = async(Dispatchers.Default) { worker(id).doWork() }
                onProgress = { job.cancel() }
                job.await()
            }
        }
        // The worker winds up by itself after a cancel, since nothing waits for it.
        val deadline = System.currentTimeMillis() + WAIT_MS
        val directory = File(context.cacheDir, "exports/$id")
        while (directory.exists() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertFalse(directory.exists())
        assertNull(resultNotification(id))
        assertNull(progressNotification(id))
    }

    private companion object {
        const val WAIT_MS = 20_000L
    }
}
