package org.kineograph.media.export

import android.app.NotificationManager
import android.content.Context
import android.database.SQLException
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.kineograph.data.SettingsRepository
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Where an export has got to. */
public sealed interface ExportStatus {
    public data class Running(val progress: Float) : ExportStatus

    public data class Finished(
        val file: File,
        val format: ExportFormat,
        val width: Int,
        val height: Int,
        val wasReduced: Boolean,
        val wasPadded: Boolean,
    ) : ExportStatus

    /** [code] is one of the ExportWorker.ERROR_ values. */
    public data class Failed(val code: String) : ExportStatus

    public data object Cancelled : ExportStatus
}

/**
 * Starts exports and watches them. An export belongs to WorkManager rather than to any screen, so it carries on without
 * the editor, and a new editor on the same project can pick it up again, finished or not. Each project has one export at
 * a time, which is what makes it possible to find.
 */
public class ExportManager(context: Context, private val settings: SettingsRepository) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    /**
     * Starts an export, unless the project already has one running, which then carries on instead. Replacing it would
     * throw away its progress. Returns the id of the one that is running.
     */
    @Throws(IOException::class)
    public suspend fun start(config: ExportConfig): UUID {
        val request =
            OneTimeWorkRequestBuilder<ExportWorker>()
                .setInputData(
                    workDataOf(
                        ExportWorker.KEY_CONFIG to Json.encodeToString(ExportConfig.serializer(), config),
                        ExportWorker.KEY_PROJECT_ID to config.projectId,
                    ),
                ).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(EXPORT_TAG)
                // The user may not come back for days, and the record is how the result gets found.
                .keepResultsForAtLeast(UNSEEN_RESULT_DAYS, TimeUnit.DAYS)
                .build()
        val name = uniqueName(config.projectId)
        try {
            workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).await()
            return workManager
                .getWorkInfosForUniqueWorkFlow(name)
                .first()
                .firstOrNull()
                ?.id ?: request.id
        } catch (e: SQLException) {
            // A full disk stops WorkManager recording the work.
            throw IOException("Could not queue the export", e)
        }
    }

    public fun cancel(id: UUID) {
        workManager.cancelWorkById(id)
    }

    /**
     * The project's export that the user has not finished with: one still running, or one that ended and has not been
     * looked at since. Being cancelled is an ending the user already knows about.
     */
    public suspend fun findUnseen(projectId: String): UUID? {
        val info =
            try {
                workManager.getWorkInfosForUniqueWorkFlow(uniqueName(projectId)).first().firstOrNull()
            } catch (_: SQLException) {
                null
            } ?: return null
        if (info.state == WorkInfo.State.CANCELLED) return null
        return info.id.takeUnless { it.toString() in settings.seenExports() }
    }

    /** The user has finished with this export's result, so it will not come back. */
    public suspend fun markSeen(id: UUID) {
        clearResultNotification(id)
        settings.markExportSeen(id.toString())
    }

    /** The user is looking at the result, so the notification saying it is ready has done its job. */
    public fun clearResultNotification(id: UUID) {
        appContext.getSystemService(NotificationManager::class.java)?.cancel(ExportWorker.RESULT_TAG, id.hashCode())
    }

    /** Removes a finished export's file, for when nothing can need it any more. */
    public suspend fun deleteFile(file: File) {
        withContext(Dispatchers.IO) { ExportFiles.delete(file) }
    }

    /**
     * Clears away old exports, which would otherwise pile up on phones short of space, even for projects long deleted. What
     * the user has not seen stays, or the file would be gone by the time they come back for it, although Android may still
     * clear it to make room.
     */
    public suspend fun pruneOldExports(nowMs: Long = System.currentTimeMillis()) {
        val infos = workManager.getWorkInfosByTagFlow(EXPORT_TAG).first()
        val seen = settings.seenExports()
        val keep =
            infos
                .filter { !it.state.isFinished || (it.state == WorkInfo.State.SUCCEEDED && it.id.toString() !in seen) }
                .map { it.id.toString() }
                .toSet()
        withContext(Dispatchers.IO) { ExportFiles.prune(ExportFiles.root(appContext), keep, nowMs - KEPT_MS) }
        settings.keepSeenExports(infos.map { it.id.toString() }.toSet())
    }

    public fun observe(id: UUID): Flow<ExportStatus> =
        workManager
            .getWorkInfoByIdFlow(id)
            .map { info ->
                when (info?.state) {
                    // Only just queued. An export that was looked up has a record, so this is always about to change.
                    null, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                        ExportStatus.Running(0f)
                    }

                    WorkInfo.State.RUNNING -> {
                        ExportStatus.Running(info.progress.getFloat(ExportWorker.KEY_PROGRESS, 0f))
                    }

                    WorkInfo.State.SUCCEEDED -> {
                        finished(info)
                    }

                    WorkInfo.State.FAILED -> {
                        ExportStatus.Failed(info.outputData.getString(ExportWorker.KEY_ERROR) ?: ExportWorker.ERROR_UNKNOWN)
                    }

                    WorkInfo.State.CANCELLED -> {
                        ExportStatus.Cancelled
                    }
                }
            }.flowOn(Dispatchers.IO)

    private fun finished(info: WorkInfo): ExportStatus {
        val output = info.outputData
        val path = output.getString(ExportWorker.KEY_OUTPUT_PATH)
        val format = ExportFormat.entries.firstOrNull { it.name == output.getString(ExportWorker.KEY_FORMAT) }
        if (path == null || format == null) return ExportStatus.Failed(ExportWorker.ERROR_UNKNOWN)
        val file = File(path)
        // It waits in the cache, which Android clears when it needs room.
        if (!file.exists()) return ExportStatus.Failed(ExportWorker.ERROR_FILE_GONE)
        return ExportStatus.Finished(
            file,
            format,
            output.getInt(ExportWorker.KEY_WIDTH, 0),
            output.getInt(ExportWorker.KEY_HEIGHT, 0),
            output.getBoolean(ExportWorker.KEY_REDUCED, false),
            output.getBoolean(ExportWorker.KEY_PADDED, false),
        )
    }

    private fun uniqueName(projectId: String) = "export-$projectId"

    private companion object {
        const val EXPORT_TAG = "export"
        const val UNSEEN_RESULT_DAYS = 7L
        const val KEPT_MS = 24L * 60L * 60L * 1000L
    }
}
