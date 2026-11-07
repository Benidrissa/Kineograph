package org.kineograph.feature.editor

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.kineograph.data.SettingsRepository
import org.kineograph.data.db.openProjectDao
import org.kineograph.data.files.ProjectFiles
import org.kineograph.engine.session.MemoryBudget
import org.kineograph.media.export.ExportConfig
import org.kineograph.media.export.ExportDependencies
import org.kineograph.media.export.ExportFormat
import org.kineograph.media.export.ExportManager
import org.kineograph.media.export.ExportStatus
import org.kineograph.model.Background
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

/** Workers are made by the system, so this is how they get what they need in the app, and here. */
class EditorTestApp :
    Application(),
    ExportDependencies {
    override val projectRepository: ProjectRepository get() = checkNotNull(repository)

    companion object {
        var repository: ProjectRepository? = null
    }
}

/** A real export through the real WorkManager, watched by editors that come and go, as they do on a phone. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = EditorTestApp::class)
class EditorExportTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var store = ViewModelStore()
    private lateinit var files: ProjectFiles
    private lateinit var repository: ProjectRepository
    private lateinit var projectId: String
    private var saveToGallery: (File, ExportFormat) -> Unit = { _, _ -> }

    @Before
    fun setUp() {
        // WorkManager is a singleton that would otherwise still be holding on to the previous test's world.
        val field = WorkManagerImpl::class.java.getDeclaredField("sDefaultInstance")
        field.isAccessible = true
        field.set(null, null)
        WorkManagerImpl.setDelegate(null)
        WorkManager.initialize(context, Configuration.Builder().build())

        files = ProjectFiles(temp.newFolder(), temp.newFolder())
        repository = ProjectRepository(openProjectDao(context), files, 8L * 1024 * 1024)
        EditorTestApp.repository = repository
        projectId = runBlocking { repository.create("Test", 64, 64, 12, Background.Transparent, "Layer 1") }.id
    }

    @After
    fun tearDown() {
        store.clear()
        appScope.cancel()
        WorkManager.getInstance(context).cancelAllWork()
    }

    private fun openEditor(): EditorViewModel {
        val dependencies =
            EditorDependencies(
                repository,
                SettingsRepository(context),
                MemoryBudget.forMemoryClass(256, isLowRam = false),
                ExportManager(context, SettingsRepository(context)),
                files::spillDirectory,
                appScope,
                { file, format -> saveToGallery(file, format) },
            )
        val factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = EditorViewModel(dependencies, projectId) as T
            }
        val viewModel = ViewModelProvider(store, factory)[EditorViewModel::class.java]
        waitUntil("the editor to open") { viewModel.load.value is EditorLoadState.Ready }
        return viewModel
    }

    /** What happens when the task is swiped away: the screen and everything it held goes, and the export carries on. */
    private fun closeEditor() {
        store.clear()
        store = ViewModelStore()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun EditorViewModel.awaitFinished(): ExportStatus.Finished {
        waitUntil("the export to finish") {
            val status = export.value?.status
            check(status !is ExportStatus.Failed) { "The export failed: $status" }
            status is ExportStatus.Finished
        }
        return export.value?.status as ExportStatus.Finished
    }

    private fun EditorViewModel.startGif() = startExport(ExportConfig(projectId, ExportFormat.GIF))

    /** Long enough for a new editor to have looked and found whatever there is to find. */
    private fun settle() {
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @Test
    fun `a finished export is offered by a new editor after the first has gone`() {
        val first = openEditor()
        first.startGif()
        val file = first.awaitFinished().file
        closeEditor()

        val second = openEditor()
        val found = second.awaitFinished()
        assertEquals(file, found.file)
        assertEquals(ExportFormat.GIF, found.format)
    }

    @Test
    fun `an export that finishes after its editor has gone is still offered`() {
        val first = openEditor()
        first.startGif()
        waitUntil("the export to be under way") { first.export.value != null }
        closeEditor()

        val second = openEditor()
        assertTrue(second.awaitFinished().file.exists())
    }

    @Test
    fun `dismissing a result means it does not come back`() {
        val first = openEditor()
        first.startGif()
        val id = checkNotNull(first.awaitFinished().file.parentFile).name
        first.dismissExport()
        assertNull(first.export.value)
        waitUntil("the answer to reach the disk") { id in runBlocking { SettingsRepository(context).seenExports() } }
        closeEditor()

        val second = openEditor()
        settle()
        assertNull(second.export.value)
    }

    @Test
    fun `an export is kept after Done without saving, in case the user changes their mind`() {
        val editor = openEditor()
        editor.startGif()
        val file = editor.awaitFinished().file
        editor.dismissExport()
        settle()
        assertTrue(file.exists())
    }

    @Test
    fun `a saved export's cache copy goes when the dialog closes`() {
        val editor = openEditor()
        editor.startGif()
        val file = editor.awaitFinished().file
        editor.saveExportToGallery()
        waitUntil("the save") { editor.export.value?.saved == true }
        editor.dismissExport()
        waitUntil("the file to go") { !file.exists() }
    }

    @Test
    fun `a saved export that was also shared keeps its file, because another app may still read it`() {
        val editor = openEditor()
        editor.startGif()
        val file = editor.awaitFinished().file
        editor.markExportHandedOff()
        editor.saveExportToGallery()
        waitUntil("the save") { editor.export.value?.saved == true }
        editor.dismissExport()
        settle()
        assertTrue(file.exists())
    }

    @Test
    fun `saving never runs on the main thread, and the screen knows it is happening`() {
        val release = CompletableDeferred<Unit>()
        var thread: Thread? = null
        saveToGallery = { _, _ ->
            thread = Thread.currentThread()
            runBlocking { release.await() }
        }
        val editor = openEditor()
        editor.startGif()
        editor.awaitFinished()

        editor.saveExportToGallery()
        assertTrue("the copy must not hold this up", editor.export.value?.saving == true)
        assertFalse(editor.export.value?.saved == true)
        release.complete(Unit)
        waitUntil("the save") { editor.export.value?.saved == true }

        assertNotEquals(Looper.getMainLooper().thread, thread)
        assertFalse(editor.export.value?.saving == true)
    }

    @Test
    fun `a second tap on Save does nothing while the first is running`() {
        val release = CompletableDeferred<Unit>()
        var saves = 0
        saveToGallery = { _, _ ->
            saves++
            runBlocking { release.await() }
        }
        val editor = openEditor()
        editor.startGif()
        editor.awaitFinished()

        editor.saveExportToGallery()
        editor.saveExportToGallery()
        release.complete(Unit)
        waitUntil("the save") { editor.export.value?.saved == true }
        editor.saveExportToGallery()
        settle()

        assertEquals(1, saves)
    }

    @Test
    fun `the dialog cannot be dismissed from under a running save`() {
        val release = CompletableDeferred<Unit>()
        saveToGallery = { _, _ -> runBlocking { release.await() } }
        val editor = openEditor()
        editor.startGif()
        editor.awaitFinished()

        editor.saveExportToGallery()
        editor.dismissExport()
        assertTrue("still showing the save", editor.export.value?.saving == true)

        release.complete(Unit)
        waitUntil("the save") { editor.export.value?.saved == true }
        editor.dismissExport()
        assertNull(editor.export.value)
    }

    @Test
    fun `a failed save says so and can be tried again`() {
        var fail = true
        saveToGallery = { _, _ -> if (fail) throw IOException("no room") }
        val editor = openEditor()
        editor.startGif()
        editor.awaitFinished()

        editor.saveExportToGallery()
        waitUntil("the failure") { editor.export.value?.saveFailed == true }
        fail = false
        editor.saveExportToGallery()
        waitUntil("the save") { editor.export.value?.saved == true }
        assertFalse(editor.export.value?.saveFailed == true)
    }

    @Test
    fun `saving without permission is a failed save, not a crash`() {
        saveToGallery = { _, _ -> throw SecurityException("no permission") }
        val editor = openEditor()
        editor.startGif()
        editor.awaitFinished()
        editor.saveExportToGallery()
        waitUntil("the failure") { editor.export.value?.saveFailed == true }
    }

    private companion object {
        const val WAIT_MS = 20_000L
    }
}
