package org.kineograph.feature.editor

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.kineograph.data.AppSettings
import org.kineograph.data.Autosaver
import org.kineograph.data.ProjectRepository
import org.kineograph.data.SettingsRepository
import org.kineograph.data.ToolbarSide
import org.kineograph.data.files.CelFileStore
import org.kineograph.engine.session.DrawingSession
import org.kineograph.engine.session.MemoryBudget
import org.kineograph.engine.session.SessionEvent
import org.kineograph.feature.editor.canvas.PlaybackDisplay
import org.kineograph.media.export.ExportConfig
import org.kineograph.media.export.ExportFormat
import org.kineograph.media.export.ExportManager
import org.kineograph.media.export.ExportStatus
import org.kineograph.media.export.ExportWorker
import org.kineograph.model.Background
import org.kineograph.model.PlaybackMode
import java.io.File
import java.io.IOException
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
    /** Copies a finished export to the gallery, however long that takes, or throws an IOException. Never called on the main thread. */
    public val saveToGallery: (File, ExportFormat) -> Unit,
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
    SAVE_FAILED,
    RECOVERED_FROM_BACKUP,
    MISSING_DRAWINGS,
    DRAWING_NOT_LOADED,
    UNDO_STEPS_LOST,
    SETTING_NOT_SAVED,
    LAYER_NOT_SHOWN,
    CANVAS_DOES_NOT_FIT,
}

/** An export in progress or finished, and what has happened to the result so far, for the export dialog. */
internal data class ExportUiState(
    val status: ExportStatus,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val saveFailed: Boolean = false,
    /** Another app was given the file and may read it at any time. */
    val handedOff: Boolean = false,
)

/**
 * Owns one open project for as long as the editor is on screen, surviving window changes.
 */
public class EditorViewModel(private val dependencies: EditorDependencies, private val projectId: String) : ViewModel() {
    private val _load = MutableStateFlow<EditorLoadState>(EditorLoadState.Loading)
    internal val load: StateFlow<EditorLoadState> get() = _load

    private val _tools = MutableStateFlow(ToolState())
    internal val tools: StateFlow<ToolState> get() = _tools

    /**
     * Messages wait here until the screen collects them, which is only once the editor has opened, so the warnings
     * from opening a project still arrive. Each is shown once: a window change does not repeat it. If this ever fills up
     * it is the newest that are dropped, so a burst of low-memory messages cannot push out those warnings.
     */
    private val _messages = Channel<EditorMessage>(capacity = MESSAGE_BUFFER)
    internal val messages: Flow<EditorMessage> = _messages.receiveAsFlow()

    /** True while Back is waiting for the last save. */
    private val _leaving = MutableStateFlow(false)
    internal val leaving: StateFlow<Boolean> get() = _leaving

    /** True when Back was refused because the last changes could not be saved, so the user has to choose what happens. */
    private val _unsavedOnLeave = MutableStateFlow(false)
    internal val unsavedOnLeave: StateFlow<Boolean> get() = _unsavedOnLeave

    /**
     * The project to go to when this editor closes, if the user asked for one rather than for going back. It is kept here
     * because leaving can wait on a save and then on the user's answer, and the screen that hears the answer may be a newer
     * one than the screen that asked.
     */
    public var leavingFor: String? = null
        private set

    private val _export = MutableStateFlow<ExportUiState?>(null)
    internal val export: StateFlow<ExportUiState?> get() = _export

    internal val appSettings: StateFlow<AppSettings> =
        dependencies.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private var exportId: UUID? = null
    private var exportWatcher: Job? = null
    private var playbackStart: Job? = null
    private var discarded = false

    internal var playback: PlaybackController? = null
        private set
    internal var thumbnails: ThumbnailLoader? = null
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
        if (opened.loaded.recoveredFromBackup) _messages.trySend(EditorMessage.RECOVERED_FROM_BACKUP)
        if (opened.loaded.missingCels > 0) _messages.trySend(EditorMessage.MISSING_DRAWINGS)

        val settings =
            try {
                dependencies.settings.settings.first()
            } catch (e: CancellationException) {
                opened.store.close()
                throw e
            }
        val project = opened.loaded.project
        _tools.value =
            ToolState(smoothing = settings.defaultSmoothing, simulateFingerPressure = settings.simulateFingerPressure)
                .scaledForCanvas(project.width, project.height)

        val budget = dependencies.budget
        val session = DrawingSession(project, opened.store, budget, dependencies.spillDirectory(projectId))
        viewModelScope.launch {
            session.events.collect { event ->
                _messages.trySend(
                    when (event) {
                        SessionEvent.LowMemory -> EditorMessage.LOW_MEMORY
                        SessionEvent.CelNotLoaded -> EditorMessage.DRAWING_NOT_LOADED
                        SessionEvent.LayerNotEditable -> EditorMessage.LAYER_LOCKED
                        SessionEvent.UndoStepsLost -> EditorMessage.UNDO_STEPS_LOST
                        SessionEvent.LayerNotShown -> EditorMessage.LAYER_NOT_SHOWN
                        SessionEvent.CanvasDoesNotFit -> EditorMessage.CANVAS_DOES_NOT_FIT
                    },
                )
            }
        }
        // The screen should know from the start whether the canvas fitted in memory, rather than show a dead one first.
        try {
            session.awaitIdle()
        } catch (e: CancellationException) {
            // onCleared only cleans up what reached Ready.
            session.close()
            opened.store.close()
            throw e
        }
        val autosaver = Autosaver(session, dependencies.repository, opened.store, projectId)
        autosaver.start(viewModelScope)
        playback = PlaybackController(viewModelScope, opened.store, budget.playbackCacheBytes, budget.isLowRam)
        thumbnails = ThumbnailLoader(opened.store, THUMBNAIL_CACHE_BYTES)
        viewModelScope.launch {
            autosaver.saveFailed.collect { failed -> if (failed) _messages.trySend(EditorMessage.SAVE_FAILED) }
        }
        _load.value = EditorLoadState.Ready(session, autosaver, opened.store, settings.lowLatencyDrawing)
        // An export outlives the editor that started it, so this may be the first chance to show how one went. A running
        // one also has to show here, or it would look as if another could be started.
        viewModelScope.launch {
            val unseen = dependencies.exportManager.findUnseen(projectId)
            if (unseen != null && exportId == null) watchExport(unseen)
        }
    }

    private val ready: EditorLoadState.Ready? get() = _load.value as? EditorLoadState.Ready

    internal fun setToolbarSide(side: ToolbarSide) {
        viewModelScope.launch {
            if (!dependencies.settings.setToolbarSide(side)) notify(EditorMessage.SETTING_NOT_SAVED)
        }
    }

    internal fun updateTools(change: (ToolState) -> ToolState) {
        _tools.update(change)
    }

    internal fun pickColor(argb: Int) {
        _tools.update { it.copy(colorArgb = argb or OPAQUE).withRecent(argb or OPAQUE) }
    }

    internal fun notify(message: EditorMessage) {
        _messages.trySend(message)
    }

    /**
     * Saves when the app goes to the background, waiting a moment for it, because the system may kill a stopped app
     * at any time. The wait has a limit, and the save carries on regardless. It runs on the app's scope and needs nothing
     * from the main thread, so blocking that thread here cannot hold it up, and it returns at once if nothing changed.
     * After a discard there is nothing to wait for, even though leaving the screen brings it through here.
     */
    internal fun saveBeforeBackground() {
        if (discarded) return
        val state = ready ?: return
        val save = dependencies.appScope.launch { state.autosaver.saveNow(commitSelection = true) }
        runBlocking { withTimeoutOrNull(STOP_SAVE_WAIT_MS) { save.join() } }
    }

    /** Saving is otherwise only tried again after the next change, but coming back may well mean space was freed. */
    internal fun retryFailedSave() {
        val state = ready ?: return
        if (!state.autosaver.saveFailed.value) return
        viewModelScope.launch {
            // Only the question is settled by this. A leave that is on its way has not asked one, and still knows where it is going.
            if (state.autosaver.saveNow() && _unsavedOnLeave.value) stayAfterFailedSave()
        }
    }

    /**
     * Back goes through here, so the screen only closes once everything has reached the disk. If it cannot, the user
     * stays in the editor and is asked, because what could not be saved exists nowhere but in memory. Once the user has
     * discarded, that answer stands, so there is nothing left to save or ask.
     */
    internal fun requestLeave(onLeft: () -> Unit) {
        val state = ready
        if (state == null || discarded) {
            onLeft()
            return
        }
        if (_leaving.value) return
        _leaving.value = true
        viewModelScope.launch {
            val saved = state.autosaver.saveNow(commitSelection = true)
            _leaving.value = false
            if (saved) onLeft() else _unsavedOnLeave.value = true
        }
    }

    /**
     * Closes this editor to go to another project, for a tap on a notification, which can happen at any time. There is only
     * ever one editor, so this one has to close first, and it does so the way Back does: what could not be saved exists only
     * in memory, so the user is asked. [onLeft] goes to [leavingFor], and the screen's way of leaving has to do the same.
     */
    public fun requestLeaveFor(project: String, onLeft: () -> Unit) {
        // The user has moved on from anything they were being asked. If it still applies, the save below fails and asks again.
        stayAfterFailedSave()
        if (project == projectId) return
        leavingFor = project
        stopPlayback()
        requestLeave(onLeft)
    }

    internal fun stayAfterFailedSave() {
        _unsavedOnLeave.value = false
        leavingFor = null
    }

    /** The user chose to give up what could not be saved. The last save on disk is what the project goes back to. */
    internal fun discardAndLeave(onLeft: () -> Unit) {
        _unsavedOnLeave.value = false
        discarded = true
        ready?.autosaver?.abandon()
        onLeft()
    }

    /** Tries again to open a canvas that did not fit in memory, at a size the user chose if they did. */
    internal fun retryOpen(width: Int? = null, height: Int? = null) {
        val state = ready ?: return
        if (width != null && height != null) {
            state.session.retryOpen(width, height)
            _tools.update { it.scaledForCanvas(width, height) }
        } else {
            state.session.retryOpen()
        }
    }

    internal fun startPlayback(display: PlaybackDisplay, displayScale: Float) {
        val state = ready ?: return
        val controller = playback ?: return
        playbackStart?.cancel()
        playbackStart = viewModelScope.launch {
            // Frames are drawn from what is saved, so anything pending has to get there first.
            val project =
                try {
                    state.session.snapshot(commitSelection = true)
                    state.session.snapshot()
                } catch (_: IOException) {
                    // Only running out of memory does this, and the session has already said so.
                    return@launch
                }
            val session = state.session.state.value
            val start = project.frameIndex(session.frameId).coerceAtLeast(0)
            controller.start(project, start, project.settings.playbackMode, displayScale, display)
        }
    }

    /**
     * Stops playback, or a start still on its way, leaving the editor on the frame that was showing. When nothing is
     * playing there is no such frame, and the editor stays where it is. Leaving the app comes through here every time.
     */
    internal fun stopPlayback() {
        playbackStart?.cancel()
        val state = ready ?: return
        val controller = playback ?: return
        val current = controller.state.value
        if (!current.isPlaying) return
        val frames = state.session.state.value.project.frames
        controller.stop()
        frames.getOrNull(current.slotIndex)?.let { state.session.selectFrame(it.id) }
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
            // An export reads what is saved, so it would otherwise quietly leave out the latest work.
            if (!state.autosaver.saveNow(commitSelection = true)) {
                notify(EditorMessage.SAVE_FAILED)
                return@launch
            }
            val id =
                try {
                    dependencies.exportManager.start(config)
                } catch (_: IOException) {
                    _export.value = ExportUiState(ExportStatus.Failed(ExportWorker.ERROR_STORAGE))
                    return@launch
                }
            watchExport(id)
        }
    }

    private fun watchExport(id: UUID) {
        exportId = id
        _export.value = ExportUiState(ExportStatus.Running(0f))
        exportWatcher?.cancel()
        exportWatcher =
            viewModelScope.launch {
                dependencies.exportManager.observe(id).collectLatest { status ->
                    _export.update { it?.copy(status = status) ?: ExportUiState(status) }
                }
            }
    }

    internal fun cancelExport() {
        exportId?.let(dependencies.exportManager::cancel)
    }

    /**
     * Copies the finished export to the gallery. It is a long copy on a slow phone, so it stays off the main thread, and on
     * the app's scope, because the user leaving the screen is no reason to abandon it half way.
     */
    internal fun saveExportToGallery() {
        val current = _export.value ?: return
        val finished = current.status as? ExportStatus.Finished ?: return
        // This runs on the main thread, as does anything that could change it, so a double tap stops here.
        if (current.saving || current.saved) return
        _export.value = current.copy(saving = true, saveFailed = false)
        dependencies.appScope.launch(Dispatchers.IO) {
            val saved =
                try {
                    dependencies.saveToGallery(finished.file, finished.format)
                    true
                } catch (_: IOException) {
                    false
                } catch (_: SecurityException) {
                    false
                }
            _export.update { it?.copy(saving = false, saved = saved, saveFailed = !saved) }
        }
    }

    internal fun markExportHandedOff() {
        _export.update { it?.copy(handedOff = true) }
    }

    internal fun exportResultShown() {
        exportId?.let(dependencies.exportManager::clearResultNotification)
    }

    /**
     * The user has finished with the result, so it must not come back next time the project opens. A copy that has reached
     * the gallery leaves nothing the cache file is needed for, unless another app has been given a link to it.
     */
    internal fun dismissExport() {
        val current = _export.value
        // The result of the save would have nowhere to go.
        if (current?.saving == true) return
        val id = exportId
        exportWatcher?.cancel()
        exportWatcher = null
        exportId = null
        _export.value = null
        val finished = current?.status as? ExportStatus.Finished
        dependencies.appScope.launch {
            if (id != null) dependencies.exportManager.markSeen(id)
            if (finished != null && current.saved && !current.handedOff) dependencies.exportManager.deleteFile(finished.file)
        }
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
                    try {
                        state.autosaver.finish()
                    } finally {
                        state.session.close()
                        state.store.close()
                    }
                }
            dependencies.repository.trackClosing(projectId, job)
        }
        super.onCleared()
    }

    private companion object {
        const val THUMBNAIL_CACHE_BYTES = 16 * 1024 * 1024
        const val MESSAGE_BUFFER = 16
        const val STOP_SAVE_WAIT_MS = 2000L
        const val OPAQUE = 0xFF000000.toInt()
    }
}
