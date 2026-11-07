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
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Saves an open project about a second after the last change, and whenever [saveNow] is called, such as when
 * the app goes to the background. A failed save (a full disk, usually) keeps everything in memory, reports
 * it through [saveFailed], and is tried again after the next change.
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
    private val sweepHolds = AtomicInteger()

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
     * Saves immediately if anything changed. Safe to call from any coroutine; it finishes even if the caller is cancelled.
     * The work holds a lock across suspensions, so it runs on a background dispatcher: the lock's holder must never
     * need the main thread to make progress, or a caller waiting on that thread could deadlock.
     */
    public suspend fun saveNow(commitSelection: Boolean = false) {
        withContext(Dispatchers.Default + NonCancellable) {
            mutex.withLock {
                val revision = session.state.value.revision
                if (revision == savedRevision && !commitSelection) return@withLock
                try {
                    val project = session.snapshot(commitSelection)
                    repository.save(project, store)
                    savedRevision = revision
                    _saveFailed.value = false
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
                    _saveFailed.value = true
                }
            }
        }
    }

    /**
     * Stops image files from being swept until [releaseSweeps]. An import writes many files that nothing refers to until its very end, and a
     * sweep in the meantime would take them for garbage. A sweep already under way is safe too: it listed its files before these existed.
     */
    public fun holdSweeps() {
        sweepHolds.incrementAndGet()
    }

    /** Ends a [holdSweeps]. Whatever is still not referred to by then goes in the next sweep. */
    public fun releaseSweeps() {
        sweepHolds.decrementAndGet()
    }

    /** Deletes image files that nothing refers to any more. */
    private suspend fun sweep() {
        if (sweepHolds.get() > 0) return
        val candidates = store.listFiles()
        val keep = session.referencedFiles()
        repository.sweep(projectId, store, candidates, keep)
    }

    /** Saves one last time and stops watching. Call before closing the session. */
    public suspend fun finish() {
        watcher?.cancel()
        saveNow(commitSelection = true)
        withContext(Dispatchers.Default + NonCancellable) {
            mutex.withLock {
                try {
                    sweep()
                } catch (_: IOException) {
                    // Leftover files cost a little space and are swept next time.
                }
            }
        }
    }

    private companion object {
        const val SAVES_BETWEEN_SWEEPS = 25
    }
}
