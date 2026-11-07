package org.kineograph.feature.gallery

import android.content.ContentResolver
import android.database.SQLException
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kineograph.data.ProjectRepository
import org.kineograph.data.ProjectSummary
import org.kineograph.model.Background
import org.kineograph.model.newId
import java.io.IOException

/** Short messages for the user. */
public enum class GalleryMessage {
    /** A change could not be saved, which nearly always means the storage is full. */
    STORAGE_PROBLEM,

    /** The app could not finish setting itself up when it started. */
    STARTUP_PROBLEM,

    /** The settings file was damaged, so the settings went back to their defaults. */
    SETTINGS_RESET,

    /** The project list could not be read. */
    INDEX_PROBLEM,
}

public enum class GallerySort { DATE, NAME }

public enum class GalleryFilter { ALL, TRASH }

/** What the gallery screen shows. */
public data class GalleryUiState(
    val recent: List<ProjectSummary> = emptyList(),
    val all: List<ProjectSummary> = emptyList(),
    val query: String = "",
    val sort: GallerySort = GallerySort.DATE,
    val filter: GalleryFilter = GalleryFilter.ALL,
    val totalSizeBytes: Long = 0,
    val isLoaded: Boolean = false,
    /** Every project's name, including the ones in the trash, which can come back. */
    val allNames: Set<String> = emptySet(),
)

/** The smallest number whose default name no project has. This goes by the name, so the sample project takes no number. */
internal fun nextDefaultNumber(names: Set<String>, nameFor: (Int) -> String): Int = generateSequence(1) { it + 1 }.first {
    nameFor(it) !in
        names
}

/** What a new project needs. An image [backgroundUri] takes priority over [background]. */
public data class NewProjectRequest(
    val name: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val background: Background,
    val backgroundUri: Uri? = null,
)

public class GalleryViewModel(private val repository: ProjectRepository) : ViewModel() {
    private val controls = MutableStateFlow(Controls())

    /** Messages wait here until the screen collects them. Each is shown once. */
    private val _messages = Channel<GalleryMessage>(capacity = MESSAGE_BUFFER)
    public val messages: Flow<GalleryMessage> = _messages.receiveAsFlow()

    public val state: StateFlow<GalleryUiState> =
        combine(repository.projects, repository.totalSizeBytes, controls) { projects, size, control ->
            buildState(projects, size, control)
        }.catch { problem ->
            // The files are all still there, and the next launch rebuilds the index from them. Until then, say so and stay up.
            if (problem !is SQLException && problem !is IOException) throw problem
            notify(GalleryMessage.INDEX_PROBLEM)
            emit(GalleryUiState(isLoaded = true))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GalleryUiState())

    /** Shows a message, including ones from outside the gallery such as start-up problems. */
    public fun notify(message: GalleryMessage) {
        _messages.trySend(message)
    }

    /** Runs a change to stored projects. A full disk must only ever cost the user that change, with a message saying so. */
    private fun launchChange(change: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                change()
            } catch (_: IOException) {
                notify(GalleryMessage.STORAGE_PROBLEM)
            }
        }
    }

    public fun setQuery(query: String) {
        controls.update { it.copy(query = query) }
    }

    public fun setSort(sort: GallerySort) {
        controls.update { it.copy(sort = sort) }
    }

    public fun setFilter(filter: GalleryFilter) {
        controls.update { it.copy(filter = filter) }
    }

    /**
     * Creates a project and reports its id, and whether its picture could not be used, in which case it starts on white paper. The user
     * has to hear that in the editor, where they end up, because this screen is about to go away.
     */
    public fun create(
        request: NewProjectRequest,
        layerName: String,
        resolver: ContentResolver,
        onCreated: (id: String, backgroundFailed: Boolean) -> Unit,
    ) {
        launchChange {
            val id = newId()
            var background = request.background
            val uri = request.backgroundUri
            val imported = uri?.let { repository.importBackgroundImage(id, it, resolver, request.width, request.height) }
            if (imported != null) background = imported
            repository.create(request.name, request.width, request.height, request.fps, background, layerName, id)
            onCreated(id, uri != null && imported == null)
        }
    }

    public fun rename(id: String, name: String) {
        launchChange { repository.rename(id, name) }
    }

    public fun duplicate(id: String, newName: String) {
        viewModelScope.launch {
            if (repository.duplicate(id, newName) == null) notify(GalleryMessage.STORAGE_PROBLEM)
        }
    }

    public fun moveToTrash(id: String) {
        launchChange { repository.moveToTrash(id) }
    }

    public fun restore(id: String) {
        launchChange { repository.restoreFromTrash(id) }
    }

    public fun deletePermanently(id: String) {
        launchChange { repository.deletePermanently(id) }
    }

    private data class Controls(
        val query: String = "",
        val sort: GallerySort = GallerySort.DATE,
        val filter: GalleryFilter = GalleryFilter.ALL,
    )

    internal companion object {
        private const val STOP_TIMEOUT_MS = 5000L
        private const val MESSAGE_BUFFER = 8
        private const val RECENT_COUNT = 4

        private fun buildState(projects: List<ProjectSummary>, size: Long, control: Controls): GalleryUiState {
            val wantTrash = control.filter == GalleryFilter.TRASH
            val query = control.query.trim()
            val matching = projects.filter { it.isTrashed == wantTrash && (query.isEmpty() || it.name.contains(query, ignoreCase = true)) }
            val sorted =
                when (control.sort) {
                    GallerySort.DATE -> matching.sortedByDescending { it.updatedAt }
                    GallerySort.NAME -> matching.sortedBy { it.name.lowercase() }
                }
            val showRecent = !wantTrash && query.isEmpty() && matching.size > RECENT_COUNT
            return GalleryUiState(
                recent = if (showRecent) matching.sortedByDescending { it.updatedAt }.take(RECENT_COUNT) else emptyList(),
                all = sorted,
                query = control.query,
                sort = control.sort,
                filter = control.filter,
                totalSizeBytes = size,
                isLoaded = true,
                allNames = projects.mapTo(HashSet()) { it.name },
            )
        }
    }
}
