package org.kineograph.feature.gallery

import android.content.Context
import android.database.sqlite.SQLiteFullException
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kineograph.data.ProjectRepository
import org.kineograph.data.db.ProjectDao
import org.kineograph.data.db.ProjectEntity
import org.kineograph.data.files.ProjectFiles
import org.kineograph.model.Background
import org.kineograph.model.PaperKind
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** A full disk or a broken index must only ever cost the user that one action, with a message saying so. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GalleryViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        listening.cancel()
    }

    /** An index that cannot store anything, as on a full disk. */
    private class FullDao(private val rows: Flow<List<ProjectEntity>> = flowOf(emptyList())) : ProjectDao {
        private fun full(): Nothing = throw SQLiteFullException("Test")

        override fun observeAll(): Flow<List<ProjectEntity>> = rows

        override suspend fun get(id: String): ProjectEntity? = null

        override suspend fun allIds(): List<String> = emptyList()

        override suspend fun count(): Int = 0

        override suspend fun upsert(entity: ProjectEntity): Unit = full()

        override suspend fun rename(id: String, name: String, updatedAt: Long): Unit = full()

        override suspend fun setTrashed(id: String, trashedAt: Long?): Unit = full()

        override suspend fun trashedBefore(before: Long): List<String> = emptyList()

        override suspend fun delete(id: String): Unit = full()

        override fun observeTotalSize(): Flow<Long> = flowOf(0L)
    }

    private fun viewModelWith(dao: ProjectDao, files: ProjectFiles = ProjectFiles(temp.newFolder(), temp.newFolder())): GalleryViewModel {
        val repository = ProjectRepository(dao, files, 1024L * 1024)
        val factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = GalleryViewModel(repository) as T
            }
        return ViewModelProvider(store, factory)[GalleryViewModel::class.java]
    }

    /** Messages are collected once, as the screen does, because cancelling a receive can lose what it was about to take. */
    private val received = CopyOnWriteArrayList<GalleryMessage>()
    private val listening = CoroutineScope(Dispatchers.Default)

    private fun listenTo(viewModel: GalleryViewModel): GalleryViewModel {
        listening.launch { viewModel.messages.collect { received += it } }
        return viewModel
    }

    private fun waitForMessages(count: Int) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (received.size < count) {
            check(System.currentTimeMillis() < deadline) { "Only got $received" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private val request = NewProjectRequest("New", 200, 100, 12, Background.Paper(PaperKind.WHITE))

    @Test
    fun `a project that cannot be created says so and does not open`() {
        val viewModel = listenTo(viewModelWith(FullDao()))
        var created = false
        viewModel.create(request, "Layer 1", context.contentResolver) { created = true }
        waitForMessages(1)
        assertEquals(GalleryMessage.STORAGE_PROBLEM, received.single())
        assertFalse(created)
    }

    @Test
    fun `other actions that cannot be saved say so rather than crash`() {
        val viewModel = listenTo(viewModelWith(FullDao()))
        viewModel.moveToTrash("p")
        waitForMessages(1)
        viewModel.restore("p")
        waitForMessages(2)
        assertEquals(listOf(GalleryMessage.STORAGE_PROBLEM, GalleryMessage.STORAGE_PROBLEM), received.toList())
    }

    @Test
    fun `a duplicate that fails says so rather than doing nothing`() {
        val viewModel = listenTo(viewModelWith(FullDao()))
        viewModel.duplicate("does-not-exist", "Copy")
        waitForMessages(1)
        assertEquals(GalleryMessage.STORAGE_PROBLEM, received.single())
    }

    @Test
    fun `deleting still works when the index cannot write, because that is how space comes back`() {
        val files = ProjectFiles(temp.newFolder(), temp.newFolder())
        val project = files.projectDirectory("p")
        File(project, "cels").mkdirs()
        File(project, "cels/drawing.png").writeText("Taking up space")
        val viewModel = listenTo(viewModelWith(FullDao(), files))
        val running = viewModel.viewModelScope.coroutineContext[Job] ?: error("no job")
        val alreadyRunning = running.children.count()

        viewModel.deletePermanently("p")
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (running.children.count() > alreadyRunning) {
            check(System.currentTimeMillis() < deadline) { "The delete never finished" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertFalse("the files must go", project.exists())

        // Messages arrive in order, so anything the delete had to say would come before this one.
        viewModel.notify(GalleryMessage.STARTUP_PROBLEM)
        waitForMessages(1)
        assertEquals(listOf(GalleryMessage.STARTUP_PROBLEM), received.toList())
    }

    @Test
    fun `a list that cannot be read shows as empty and says so, instead of crashing`() {
        val viewModel = listenTo(viewModelWith(FullDao(flow { throw SQLiteFullException("Test") })))
        // The state only runs while something watches it, and it hops to the main thread, which this test is on.
        listening.launch { viewModel.state.collect { } }
        waitForMessages(1)
        assertEquals(GalleryMessage.INDEX_PROBLEM, received.single())
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!viewModel.state.value.isLoaded && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue(viewModel.state.value.isLoaded)
        assertTrue(
            viewModel.state.value.all
                .isEmpty(),
        )
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
