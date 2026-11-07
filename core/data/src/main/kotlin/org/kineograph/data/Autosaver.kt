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
import org.kineograph.model.Background
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Saves an open project about a second after the last change, and whenever [saveNow] is called, such as when
 * the app goes to the background. A failed save (a full disk, usually) first clears out files that nothing refers to and tries again. If that
 * does not help, it keeps everything in memory, reports it through [saveFailed], and is tried again after the next change.
 *
 * @param listCelFiles what a sweep looks through. Only a test has any reason to change this.
 */
public class Autosaver(
    private val session: DrawingSession,
    private val repository: ProjectRepository,
    private val store: CelFileStore,
    private val projectId: String,
    private val debounceMs: Long = 1000L,
    private val listCelFiles: () -> Set<String> = store::listFiles,
) {
    private val mutex = Mutex()
    private var savedRevision = -1L
    private var savesSinceSweep = 0
    private var thumbnailLook: Pair<Set<String>, Background>? = null
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
                    save(revision, commitSelection)
                } catch (_: IOException) {
                    // Files that nothing refers to any more may be what filled the disk, and saving is what normally leads to them being swept.
                    if (!sweepAndSaveAgain(revision, commitSelection)) _saveFailed.value = true
                } catch (_: OutOfMemoryError) {
                    _saveFailed.value = true
                }
            }
        }
    }

    @Throws(IOException::class)
    private suspend fun save(revision: Long, commitSelection: Boolean) {
        val project = session.snapshot(commitSelection)
        repository.save(project, store)
        savedRevision = revision
        _saveFailed.value = false
        // Only redraw the thumbnail when what it shows may have changed, and what is behind the layers shows too.
        val look = project.referencedFiles() to project.background
        if (look != thumbnailLook) {
            repository.updateThumbnail(project, store)
            thumbnailLook = look
        }
        if (++savesSinceSweep >= SAVES_BETWEEN_SWEEPS) {
            savesSinceSweep = 0
            sweep()
        }
    }

    private suspend fun sweepAndSaveAgain(revision: Long, commitSelection: Boolean): Boolean =
        try {
            if (sweep()) {
                save(revision, commitSelection)
                true
            } else {
                false
            }
        } catch (_: IOException) {
            false
        } catch (_: OutOfMemoryError) {
            false
        }

    /**
     * Stops image files from being swept until [releaseSweeps]. An import writes many files that nothing refers to until its very end, and a
     * sweep in the meantime would take them for garbage. A sweep already under way looks again once it has listed its files, so whatever it
     * could have found of these, it leaves alone.
     */
    public fun holdSweeps() {
        sweepHolds.incrementAndGet()
    }

    /** Ends a [holdSweeps]. Whatever is still not referred to by then goes in the next sweep. */
    public fun releaseSweeps() {
        sweepHolds.decrementAndGet()
    }

    /** Deletes image files that nothing refers to any more. False if a hold stopped it, which leaves everything where it was. */
    private suspend fun sweep(): Boolean {
        if (sweepHolds.get() > 0) return false
        val candidates = listCelFiles()
        // A hold that began during the listing may have a file in it already. One that begins after this cannot.
        if (sweepHolds.get() > 0) return false
        val keep = session.referencedFiles()
        repository.sweep(projectId, store, candidates, keep)
        return true
    }

    /** Saves one last time and stops watching. Call before closing the session. */
    public suspend fun finish() {
        watcher?.cancel()
        saveNow(commitSelection = true)
        withContext(Dispatchers.Default + NonCancellable) {
            mutex.withLock {
                try {
                    if (sweep()) repository.sweepBackgrounds(projectId, session.referencedFiles())
                } catch (_: IOException) {
                    // Leftover files cost a little space and are swept next time.
                }
            }
        }
        // What was just swept may be the room a last save needed, and whatever is still only in memory goes with the session.
        if (_saveFailed.value) saveNow(commitSelection = true)
    }

    private companion object {
        const val SAVES_BETWEEN_SWEEPS = 25
    }
}
