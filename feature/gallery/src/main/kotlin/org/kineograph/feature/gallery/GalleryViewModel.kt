package org.kineograph.feature.gallery

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kineograph.data.ProjectRepository
import org.kineograph.data.ProjectSummary
import org.kineograph.model.Background
import org.kineograph.model.newId

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
    val nextDefaultNumber: Int = 1,
)

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

    public val state: StateFlow<GalleryUiState> =
        combine(repository.projects, repository.totalSizeBytes, controls) { projects, size, control ->
            buildState(projects, size, control)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GalleryUiState())

    public fun setQuery(query: String) {
        controls.update { it.copy(query = query) }
    }

    public fun setSort(sort: GallerySort) {
        controls.update { it.copy(sort = sort) }
    }

    public fun setFilter(filter: GalleryFilter) {
        controls.update { it.copy(filter = filter) }
    }

    /** Creates a project and reports its id. */
    public fun create(request: NewProjectRequest, layerName: String, resolver: ContentResolver, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val id = newId()
            var background = request.background
            val uri = request.backgroundUri
            if (uri != null) {
                repository.importBackgroundImage(id, uri, resolver, request.width, request.height)?.let { background = it }
            }
            repository.create(request.name, request.width, request.height, request.fps, background, layerName, id)
            onCreated(id)
        }
    }

    public fun rename(id: String, name: String) {
        viewModelScope.launch { repository.rename(id, name) }
    }

    public fun duplicate(id: String, newName: String) {
        viewModelScope.launch { repository.duplicate(id, newName) }
    }

    public fun moveToTrash(id: String) {
        viewModelScope.launch { repository.moveToTrash(id) }
    }

    public fun restore(id: String) {
        viewModelScope.launch { repository.restoreFromTrash(id) }
    }

    public fun deletePermanently(id: String) {
        viewModelScope.launch { repository.deletePermanently(id) }
    }

    private data class Controls(
        val query: String = "",
        val sort: GallerySort = GallerySort.DATE,
        val filter: GalleryFilter = GalleryFilter.ALL,
    )

    internal companion object {
        private const val STOP_TIMEOUT_MS = 5000L
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
                nextDefaultNumber = projects.size + 1,
            )
        }
    }
}
