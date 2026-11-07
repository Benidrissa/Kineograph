package org.kineograph.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.kineograph.data.files.CelFileStore
import org.kineograph.engine.session.DrawingSession
import org.kineograph.model.Project
import java.io.IOException

/**
 * Saves an open project about a second after the last change, and whenever [saveNow] is called, such as when
 * the app goes to the background. A failed save (a full disk, usually) keeps everything in memory, reports
 * it through [saveFailed], and is tried again after the next change. What is only in memory cannot outlive the
 * process, so the editor has to tell the user while that is so.
 */
public class Autosaver(
    private val session: DrawingSession,
    private val repository: ProjectRepository,
    private val store: CelFileStore,
    private val projectId: String,
    private val debounceMs: Long = 1000L,
) {
    private val mutex = Mutex()
    private var savedRevision = -1L
    private var savesSinceSweep = 0
    private var thumbnailFiles: Set<String>? = null
    private var watcher: Job? = null

    private val _saveFailed = MutableStateFlow(false)

    /** True when the most recent save did not reach the disk. */
    public val saveFailed: StateFlow<Boolean> get() = _saveFailed

    public fun start(scope: CoroutineScope) {
        watcher?.cancel()
        savedRevision = session.state.value.revision
        watcher =
            scope.launch {
                session.state
                    .map { it.revision }
                    .distinctUntilChanged()
                    .drop(1)
                    .collectLatest {
                        delay(debounceMs)
                        saveNow()
                    }
            }
    }

    /**
     * Saves immediately if anything changed, and returns whether everything is now on disk. Safe to call from any
     * coroutine; it finishes even if the caller is cancelled. The work holds a lock across suspensions, so it runs on a
     * background dispatcher: the lock's holder must never need the main thread to make progress, or a caller waiting on
     * that thread could deadlock.
     */
    public suspend fun saveNow(commitSelection: Boolean = false): Boolean =
        withContext(Dispatchers.Default + NonCancellable) {
            mutex.withLock { saveIfChanged(commitSelection) }
        }

    private suspend fun saveIfChanged(commitSelection: Boolean): Boolean {
        // A stroke that has only just ended may still be in the session's queue, and must count as a change.
        session.awaitIdle()
        val state = session.state.value
        val revision = state.revision
        if (revision == savedRevision && (!commitSelection || state.selection == null)) return true
        val project =
            try {
                val snapshot = session.snapshot(commitSelection)
                repository.save(snapshot, store)
                snapshot
            } catch (_: IOException) {
                _saveFailed.value = true
                return false
            } catch (_: OutOfMemoryError) {
                session.trimMemory()
                _saveFailed.value = true
                return false
            }
        savedRevision = revision
        _saveFailed.value = false
        tidyUpAfterSave(project)
        return true
    }

    /** Nothing here puts the save itself in doubt, so a failure only means trying again some other time. */
    private suspend fun tidyUpAfterSave(project: Project) {
        try {
            // Only redraw the thumbnail when its images may have changed.
            val files = project.referencedFiles()
            if (files != thumbnailFiles) {
                repository.updateThumbnail(project, store)
                thumbnailFiles = files
            }
            if (++savesSinceSweep >= SAVES_BETWEEN_SWEEPS) {
                savesSinceSweep = 0
                sweep()
            }
        } catch (_: IOException) {
            // Leftover files cost a little space, and the next save tries again.
        } catch (_: OutOfMemoryError) {
            session.trimMemory()
        }
    }

    /** Deletes image files that nothing refers to any more. */
    private suspend fun sweep() {
        val candidates = store.listFiles()
        val keep = session.referencedFiles()
        repository.sweep(projectId, store, candidates, keep)
    }

    /**
     * Saves one last time and stops watching. Call before closing the session. Files are only tidied up when that save
     * worked: after a failure the session no longer refers to the last good state, and nothing is worth risking it.
     */
    public suspend fun finish() {
        watcher?.cancel()
        if (!saveNow(commitSelection = true)) return
        withContext(Dispatchers.Default + NonCancellable) {
            mutex.withLock {
                try {
                    sweep()
                } catch (_: IOException) {
                    // Leftover files cost a little space and are swept next time.
                } catch (_: OutOfMemoryError) {
                    session.trimMemory()
                }
            }
        }
    }

    /** Stops without saving, for when the user chose to discard what could not be saved. Call before closing the session. */
    public suspend fun abandon() {
        watcher?.cancel()
        // Lets a save already under way finish, so it does not reach a closed session.
        mutex.withLock { }
    }

    private companion object {
        const val SAVES_BETWEEN_SWEEPS = 25
    }
}
