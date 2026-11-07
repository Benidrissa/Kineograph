package org.kineograph.feature.editor

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kineograph.data.AppSettings
import org.kineograph.data.Autosaver
import org.kineograph.data.ProjectRepository
import org.kineograph.data.SettingsRepository
import org.kineograph.data.ToolbarSide
import org.kineograph.data.files.CelFileStore
import org.kineograph.engine.session.DrawingSession
import org.kineograph.engine.session.MemoryBudget
import org.kineograph.engine.session.SessionEvent
import org.kineograph.feature.editor.canvas.CanvasRenderer
import org.kineograph.media.export.ExportConfig
import org.kineograph.media.export.ExportManager
import org.kineograph.media.export.ExportStatus
import org.kineograph.media.svgimport.SvgImporter
import org.kineograph.model.Background
import org.kineograph.model.PlaybackMode
import java.io.File
import java.util.UUID

/** What the editor needs from the rest of the app. */
public class EditorDependencies(
    public val repository: ProjectRepository,
    public val settings: SettingsRepository,
    public val budget: MemoryBudget,
    public val exportManager: ExportManager,
    public val spillDirectory: (String) -> File,
    /** Lives as long as the app, for finishing up after a screen has gone. */
    public val appScope: CoroutineScope,
)

/** What the editor needs to show while a project opens. */
internal sealed interface EditorLoadState {
    data object Loading : EditorLoadState

    data class Ready(
        val session: DrawingSession,
        val autosaver: Autosaver,
        val store: CelFileStore,
        val lowLatency: Boolean,
    ) : EditorLoadState

    data object NotFound : EditorLoadState

    data object NeedsNewerApp : EditorLoadState
}

/** Short messages for the user. */
internal enum class EditorMessage {
    LAYER_LOCKED,
    LOW_MEMORY,
    CANNOT_OPEN,
    SAVE_FAILED,
    RECOVERED_FROM_BACKUP,
    MISSING_DRAWINGS,
    NO_FILE_PICKER,
    SVG_EMPTY,
    SVG_UNREADABLE,
    SVG_TOO_LARGE,
    SVG_DAMAGED,
    SVG_NOT_SVG,
    SVG_IMPORTED_INCOMPLETE,
}

/** An export in progress or just finished, for the export dialog. */
internal data class ExportUiState(val config: ExportConfig, val status: ExportStatus)

/**
 * Owns one open project for as long as the editor is on screen, surviving window changes.
 */
public class EditorViewModel(private val dependencies: EditorDependencies, private val projectId: String) : ViewModel() {
    private val _load = MutableStateFlow<EditorLoadState>(EditorLoadState.Loading)
    internal val load: StateFlow<EditorLoadState> get() = _load

    private val _tools = MutableStateFlow(ToolState())
    internal val tools: StateFlow<ToolState> get() = _tools

    private val _messages = MutableSharedFlow<EditorMessage>(extraBufferCapacity = 8)
    internal val messages: SharedFlow<EditorMessage> get() = _messages

    private val _export = MutableStateFlow<ExportUiState?>(null)
    internal val export: StateFlow<ExportUiState?> get() = _export

    internal val appSettings: StateFlow<AppSettings> =
        dependencies.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private var exportId: UUID? = null
    private var exportWatcher: Job? = null

    internal var playback: PlaybackController? = null
        private set
    internal var thumbnails: ThumbnailLoader? = null
        private set
    internal var svgImport: SvgImportController? = null
        private set

    init {
        viewModelScope.launch { open() }
    }

    private suspend fun open() {
        val opened = dependencies.repository.open(projectId)
        if (opened == null) {
            _load.value = if (dependencies.repository.needsNewerApp(projectId)) EditorLoadState.NeedsNewerApp else EditorLoadState.NotFound
            return
        }
        if (opened.loaded.recoveredFromBackup) _messages.tryEmit(EditorMessage.RECOVERED_FROM_BACKUP)
        if (opened.loaded.missingCels > 0) _messages.tryEmit(EditorMessage.MISSING_DRAWINGS)

        val settings = dependencies.settings.settings.first()
        val project = opened.loaded.project
        _tools.value =
            ToolState(smoothing = settings.defaultSmoothing, simulateFingerPressure = settings.simulateFingerPressure)
                .scaledForCanvas(project.width, project.height)

        val budget = dependencies.budget
        val session = DrawingSession(project, opened.store, budget, dependencies.spillDirectory(projectId))
        val autosaver = Autosaver(session, dependencies.repository, opened.store, projectId)
        autosaver.start(viewModelScope)
        playback = PlaybackController(viewModelScope, opened.store, budget.playbackCacheBytes, budget.isLowRam)
        thumbnails = ThumbnailLoader(opened.store, THUMBNAIL_CACHE_BYTES)
        svgImport =
            SvgImportController(
                scope = viewModelScope,
                importer = SvgImporter(session, opened.store, { image -> dependencies.repository.saveBackgroundImage(projectId, image) }),
                holdFiles = autosaver::holdSweeps,
                releaseFiles = autosaver::releaseSweeps,
                onMessage = { _messages.tryEmit(it) },
            )

        viewModelScope.launch {
            session.events.collect { event ->
                when (event) {
                    SessionEvent.LowMemory -> _messages.tryEmit(EditorMessage.LOW_MEMORY)
                    SessionEvent.CannotOpen -> _messages.tryEmit(EditorMessage.CANNOT_OPEN)
                }
            }
        }
        viewModelScope.launch {
            autosaver.saveFailed.collect { failed -> if (failed) _messages.tryEmit(EditorMessage.SAVE_FAILED) }
        }
        _load.value = EditorLoadState.Ready(session, autosaver, opened.store, settings.lowLatencyDrawing)
    }

    private val ready: EditorLoadState.Ready? get() = _load.value as? EditorLoadState.Ready

    internal fun setToolbarSide(side: ToolbarSide) {
        viewModelScope.launch { dependencies.settings.setToolbarSide(side) }
    }

    internal fun updateTools(change: (ToolState) -> ToolState) {
        _tools.update(change)
    }

    internal fun pickColor(argb: Int) {
        _tools.update { it.copy(colorArgb = argb or OPAQUE).withRecent(argb or OPAQUE) }
    }

    internal fun notify(message: EditorMessage) {
        _messages.tryEmit(message)
    }

    /** Saves now, for example when the app goes to the background. */
    internal fun saveBeforeBackground() {
        val state = ready ?: return
        viewModelScope.launch { withContext(NonCancellable) { state.autosaver.saveNow(commitSelection = true) } }
    }

    internal fun startPlayback(renderer: CanvasRenderer, displayScale: Float) {
        val state = ready ?: return
        val controller = playback ?: return
        viewModelScope.launch {
            // Frames are drawn from what is saved, so anything pending has to get there first.
            state.session.snapshot(commitSelection = true)
            val session = state.session.state.value
            val project = state.session.snapshot()
            val start = project.frameIndex(session.frameId).coerceAtLeast(0)
            controller.start(project, start, project.settings.playbackMode, displayScale, renderer)
        }
    }

    internal fun stopPlayback() {
        val state = ready ?: return
        val controller = playback ?: return
        val slot = controller.state.value.slotIndex
        val frames = state.session.state.value.project.frames
        controller.stop()
        frames.getOrNull(slot)?.let { state.session.selectFrame(it.id) }
    }

    internal fun setPlaybackMode(mode: PlaybackMode) {
        val state = ready ?: return
        state.session.updateSettings { it.copy(playbackMode = mode) }
        playback?.setMode(mode)
    }

    internal fun setFps(fps: Int) {
        ready?.session?.setFps(fps)
        playback?.setFps(fps)
    }

    internal fun setBackgroundImage(uri: Uri, resolver: ContentResolver) {
        val state = ready ?: return
        val project = state.session.state.value.project
        viewModelScope.launch {
            val image = dependencies.repository.importBackgroundImage(projectId, uri, resolver, project.width, project.height)
            if (image != null) state.session.setBackground(image)
        }
    }

    internal fun setBackground(background: Background) {
        ready?.session?.setBackground(background)
    }

    /** Saves everything, then starts the export in the background. */
    internal fun startExport(config: ExportConfig) {
        val state = ready ?: return
        viewModelScope.launch {
            state.autosaver.saveNow(commitSelection = true)
            val id = dependencies.exportManager.start(config)
            exportId = id
            _export.value = ExportUiState(config, ExportStatus.Running(0f))
            exportWatcher?.cancel()
            exportWatcher =
                launch {
                    dependencies.exportManager.observe(id, config.format).collectLatest { status ->
                        _export.value = ExportUiState(config, status)
                    }
                }
        }
    }

    internal fun cancelExport() {
        exportId?.let(dependencies.exportManager::cancel)
    }

    internal fun dismissExport() {
        exportWatcher?.cancel()
        exportWatcher = null
        exportId = null
        _export.value = null
    }

    /** Gives back whatever can be rebuilt later. */
    public fun onLowMemory() {
        ready?.session?.trimMemory()
    }

    override fun onCleared() {
        val state = ready
        playback?.stop()
        if (state != null) {
            // This scope outlives the screen, so the last save still happens. The main thread must not wait for it.
            val job =
                dependencies.appScope.launch {
                    state.autosaver.finish()
                    state.session.close()
                    state.store.close()
                }
            dependencies.repository.trackClosing(projectId, job)
        }
        super.onCleared()
    }

    private companion object {
        const val THUMBNAIL_CACHE_BYTES = 16 * 1024 * 1024
        const val OPAQUE = 0xFF000000.toInt()
    }
}
