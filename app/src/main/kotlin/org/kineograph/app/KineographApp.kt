package org.kineograph.app

import android.app.Application
import android.content.ComponentCallbacks2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.kineograph.data.ProjectRepository
import org.kineograph.data.SampleProject
import org.kineograph.data.SettingsRepository
import org.kineograph.data.db.openProjectDao
import org.kineograph.data.files.ProjectFiles
import org.kineograph.engine.session.MemoryBudget
import org.kineograph.media.export.ExportDependencies
import org.kineograph.media.export.ExportManager

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

    override fun onCreate() {
        super.onCreate()
        appScope.launch { prepareStorage() }
    }

    /** Runs after launch so it can never delay the first screen. */
    private suspend fun prepareStorage() {
        projectRepository.reconcile()
        val current = settings.settings.first()
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
}
