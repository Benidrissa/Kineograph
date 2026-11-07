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
import org.kineograph.model.Project
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Saves an open project about a second after the last change, and whenever [saveNow] is called, such as when
 * the app goes to the background. Images that a full disk will not take first clear out files that nothing refers to and
 * try again. A save that still fails keeps everything in memory, reports it through [saveFailed], and is tried again after
 * the next change. What is only in memory cannot outlive the process, so the editor has to tell the user while that is so.
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

    @Volatile
    private var abandoned = false

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
     * that thread could deadlock. After [abandon] this saves nothing and returns false.
     */
    public suspend fun saveNow(commitSelection: Boolean = false): Boolean = save(commitSelection, leaving = false)

    private suspend fun save(commitSelection: Boolean, leaving: Boolean): Boolean =
        withContext(Dispatchers.Default + NonCancellable) {
            mutex.withLock { if (abandoned) false else saveIfChanged(commitSelection, leaving) }
        }

    private suspend fun saveIfChanged(commitSelection: Boolean, leaving: Boolean): Boolean {
        // A stroke that has only just ended may still be in the session's queue, and must count as a change.
        session.awaitIdle()
        val state = session.state.value
        val revision = state.revision
        if (revision == savedRevision && (!commitSelection || state.selection == null)) return true
        val project =
            try {
                val snapshot = session.snapshot(commitSelection)
                awaitImagesMakingRoom(leaving)
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

    /**
     * Gets the images onto the disk, clearing out what nothing refers to if they will not fit. Files like that may be what filled the disk, and a
     * save is what normally leads to them being swept. This is the only failure that sweeps. Images are where the space goes, so a sweep is likely to
     * help them, whereas a manifest or an index that cannot be written once its images fitted points to something else being wrong, and then the
     * files stay exactly as they are. What any manifest refers to is safe either way, as [ProjectRepository.sweep] sees to that. Only when leaving can
     * replaced backgrounds go too: until then one may still be on its way into the session.
     */
    @Throws(IOException::class)
    private suspend fun awaitImagesMakingRoom(leaving: Boolean) {
        try {
            store.awaitWrites()
        } catch (e: IOException) {
            if (!sweep()) throw e
            if (leaving) repository.sweepBackgrounds(projectId, session.referencedFiles())
            store.awaitWrites()
        }
    }

    /** Nothing here puts the save itself in doubt, so a failure only means trying again some other time. */
    private suspend fun tidyUpAfterSave(project: Project) {
        try {
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
        } catch (_: IOException) {
            // Leftover files cost a little space, and the next save tries again.
        } catch (_: OutOfMemoryError) {
            session.trimMemory()
        }
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

    /**
     * Saves one last time and stops watching. Call before closing the session. Files are only tidied up when that save
     * worked: after a failure the session no longer refers to the last good state, and nothing is worth risking it. A save
     * already under way finishes first, so it cannot reach a closed session, even after [abandon].
     */
    public suspend fun finish() {
        watcher?.cancel()
        if (!save(commitSelection = true, leaving = true)) return
        withContext(Dispatchers.Default + NonCancellable) {
            mutex.withLock {
                try {
                    if (sweep()) repository.sweepBackgrounds(projectId, session.referencedFiles())
                } catch (_: IOException) {
                    // Leftover files cost a little space and are swept next time.
                } catch (_: OutOfMemoryError) {
                    session.trimMemory()
                }
            }
        }
    }

    /**
     * Stops all saving for good, for when the user chose to discard what could not be saved. This has to work at once, not
     * when the session closes: the screen lives on for a moment as it leaves, going through ON_STOP on the way, the timer
     * may still be running, and by then the disk may have room for exactly what the user said to throw away.
     */
    public fun abandon() {
        abandoned = true
        watcher?.cancel()
    }

    private companion object {
        const val SAVES_BETWEEN_SWEEPS = 25
    }
}
