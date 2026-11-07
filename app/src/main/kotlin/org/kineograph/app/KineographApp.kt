package org.kineograph.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.database.SQLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.kineograph.data.ProjectRepository
import org.kineograph.data.SampleProject
import org.kineograph.data.SettingsRepository
import org.kineograph.data.db.openProjectDao
import org.kineograph.data.files.ProjectFiles
import org.kineograph.engine.session.MemoryBudget
import org.kineograph.feature.gallery.GalleryMessage
import org.kineograph.media.export.ExportDependencies
import org.kineograph.media.export.ExportManager
import java.io.IOException

/**
 * Wires the app together by hand. Doing this without a dependency-injection framework keeps the APK small and start-up
 * fast, and at this size a few lazy properties are all that is needed (see docs/decisions.md).
 */
class KineographApp : Application(), ExportDependencies {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val files: ProjectFiles by lazy { ProjectFiles(this) }
    val budget: MemoryBudget by lazy { MemoryBudget.forDevice(this) }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val exportManager: ExportManager by lazy { ExportManager(this) }

    override val projectRepository: ProjectRepository by lazy {
        ProjectRepository(openProjectDao(this), files, budget.compressedCelCacheBytes)
    }

    /** What start-up has to tell the user. These wait here until the gallery is up and collects them. */
    private val startupMessages = Channel<GalleryMessage>(capacity = STARTUP_MESSAGES)
    val startupNotices: Flow<GalleryMessage> = startupMessages.receiveAsFlow()

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            // Whatever stops this, the app has to come up: freeing space and getting at the projects happen from inside it.
            // Nothing was marked as done, so the next launch simply tries again.
            try {
                prepareStorage()
            } catch (_: IOException) {
                startupMessages.trySend(GalleryMessage.STARTUP_PROBLEM)
            } catch (_: SQLException) {
                startupMessages.trySend(GalleryMessage.STARTUP_PROBLEM)
            } catch (_: OutOfMemoryError) {
                startupMessages.trySend(GalleryMessage.STARTUP_PROBLEM)
            }
        }
    }

    /** Runs after launch so it can never delay the first screen. */
    private suspend fun prepareStorage() {
        projectRepository.reconcile()
        val current = settings.settings.first()
        if (settings.wereSettingsReset()) startupMessages.trySend(GalleryMessage.SETTINGS_RESET)
        if (!current.sampleCreated) {
            if (projectRepository.isEmpty()) {
                SampleProject.create(
                    projectRepository,
                    getString(R.string.sample_project_name),
                    getString(R.string.sample_layer_name),
                    System.currentTimeMillis(),
                )
            }
            settings.setSampleCreated()
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) lowMemoryListeners.forEach { it() }
    }

    /** Things that can give memory back when the system is short. */
    val lowMemoryListeners: MutableSet<() -> Unit> = java.util.concurrent.ConcurrentHashMap
        .newKeySet()

    private companion object {
        const val STARTUP_MESSAGES = 4
    }
}
