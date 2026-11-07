package org.kineograph.media.export

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BadParcelableException
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import org.kineograph.data.ProjectRepository
import org.kineograph.media.R
import java.io.File
import java.io.IOException
import java.util.UUID

/** Supplies what the worker needs. The application provides this, because workers are created by the system. */
public interface ExportDependencies {
    public val projectRepository: ProjectRepository
}

/**
 * Runs an export in the background, with a notification showing progress and a Cancel button. It keeps going if
 * the user leaves the app. WorkManager holds a wake lock only while this runs.
 */
public class ExportWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val notificationId = id.hashCode()
    private val notifications: NotificationManager? get() = applicationContext.getSystemService(NotificationManager::class.java)
    private val projectId: String? get() = inputData.getString(KEY_PROJECT_ID)

    override suspend fun doWork(): Result {
        val configText = inputData.getString(KEY_CONFIG) ?: return failure(ERROR_UNKNOWN)
        val config =
            try {
                Json.decodeFromString(ExportConfig.serializer(), configText)
            } catch (_: IllegalArgumentException) {
                return failure(ERROR_UNKNOWN)
            }
        val dependencies = applicationContext as? ExportDependencies ?: return failure(ERROR_UNKNOWN)
        val repository = dependencies.projectRepository
        val opened = repository.open(config.projectId) ?: return failure(ERROR_NO_PROJECT)

        val directory = ExportFiles.directoryFor(applicationContext, id)
        var succeeded = false
        var lastReported = -1
        return try {
            try {
                setForeground(foregroundInfo(0f))
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalStateException) {
                // The system may refuse to start a foreground service while in the background. The export still runs and
                // can still show its notification, but it could be stopped sooner.
            }
            directory.mkdirs()
            val project = opened.loaded.project
            val output = File(directory, fileName(project.name, config.format))
            val result =
                ProjectExporter(opened.store).export(project, config, output) { progress ->
                    val percent = (progress * 100f).toInt()
                    if (percent != lastReported) {
                        lastReported = percent
                        reportProgress(progress, percent)
                    }
                }
            // The exporter only checks at the start of each tick, so a cancel during the last one or while the file was being
            // finished gets this far. WorkManager ignores what a stopped worker returns, so nobody else would clean up.
            if (isStopped || !currentCoroutineContext().isActive) throw CancellationException("The export was cancelled")
            succeeded = true
            notifyResult(R.string.export_result_ready_title, R.string.export_result_ready_text)
            Result.success(
                workDataOf(
                    KEY_OUTPUT_PATH to result.file.path,
                    KEY_FORMAT to config.format.name,
                    KEY_WIDTH to result.width,
                    KEY_HEIGHT to result.height,
                    KEY_REDUCED to result.wasReduced,
                    KEY_PADDED to result.wasPadded,
                ),
            )
        } catch (e: CancellationException) {
            // This has to come first, because it is also an IllegalStateException.
            throw e
        } catch (_: ExportNotSupportedException) {
            failure(ERROR_NOT_SUPPORTED)
        } catch (_: IllegalStateException) {
            // This is what MediaCodec throws when an encoder fails part-way through.
            failure(ERROR_NOT_SUPPORTED)
        } catch (_: IllegalArgumentException) {
            // This is what the platform throws for a size it cannot make a bitmap or video of at all.
            failure(ERROR_NOT_SUPPORTED)
        } catch (_: IOException) {
            failure(ERROR_STORAGE)
        } catch (_: OutOfMemoryError) {
            failure(ERROR_MEMORY)
        } finally {
            opened.store.close()
            // Cancelling comes through here as well, and half a video is of no use to anyone.
            if (!succeeded) directory.deleteRecursively()
            // The last progress update has happened by now, and nothing else removes one that went up without the
            // foreground service, or that a tick finishing after a cancel put back. While the service is attached this
            // does nothing, and WorkManager removes it.
            notifications?.cancel(notificationId)
        }
    }

    private fun failure(code: String): Result {
        notifyResult(R.string.export_result_failed_title, R.string.export_result_failed_text)
        return Result.failure(workDataOf(KEY_ERROR to code))
    }

    /**
     * Says how the export ended, for a user who has left the app. It has its own identity because WorkManager removes the
     * progress notification when the work ends. A denied notification permission just means this is not seen, and the
     * editor shows the same result when the project is next opened. Someone who cancelled has been told all they need.
     */
    private fun notifyResult(title: Int, text: Int) {
        if (isStopped) return
        val project = projectId ?: return
        ensureChannel()
        val notification =
            NotificationCompat
                .Builder(applicationContext, CHANNEL_ID)
                .setContentTitle(applicationContext.getString(title))
                .setContentText(applicationContext.getString(text))
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setAutoCancel(true)
                .setContentIntent(openProjectIntent(project))
                .build()
        notifications?.notify(RESULT_TAG, notificationId, notification)
    }

    private fun reportProgress(progress: Float, percent: Int) {
        // Progress goes to the UI and notification in the background, so it can never slow encoding down.
        setProgressAsync(workDataOf(KEY_PROGRESS to progress))
        // A cancelled export must not put back the notification that was just taken away.
        if (percent % NOTIFICATION_STEP == 0 && !isStopped) notifications?.notify(notificationId, buildNotification(progress))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0f)

    private fun foregroundInfo(progress: Float): ForegroundInfo {
        val notification = buildNotification(progress)
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> {
                ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }

            else -> {
                ForegroundInfo(notificationId, notification)
            }
        }
    }

    private fun ensureChannel() {
        notifications?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.export_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(progress: Float): android.app.Notification {
        ensureChannel()
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        return NotificationCompat
            .Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.export_notification_title))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(PROGRESS_MAX, (progress * PROGRESS_MAX).toInt(), false)
            .setContentIntent(projectId?.let(::openProjectIntent))
            .addAction(0, applicationContext.getString(R.string.export_cancel), cancel)
            .build()
    }

    /**
     * Comes back to the app, on the project being exported. It is the same intent the launcher uses, because anything
     * else would start a second copy of the screen on top of the one showing the export's progress.
     */
    private fun openProjectIntent(project: String): PendingIntent? {
        val intent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName) ?: return null
        intent.setPackage(null)
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.putExtra(EXTRA_PROJECT_ID, project)
        // One per project, or two exports' notifications would share the one that was made last.
        return PendingIntent.getActivity(
            applicationContext,
            project.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    public companion object {
        public const val KEY_CONFIG: String = "config"
        public const val KEY_PROJECT_ID: String = "project"
        public const val KEY_PROGRESS: String = "progress"
        public const val KEY_OUTPUT_PATH: String = "output"
        public const val KEY_FORMAT: String = "format"
        public const val KEY_WIDTH: String = "width"
        public const val KEY_HEIGHT: String = "height"
        public const val KEY_REDUCED: String = "reduced"
        public const val KEY_PADDED: String = "padded"
        public const val KEY_ERROR: String = "error"

        public const val ERROR_NO_PROJECT: String = "no_project"
        public const val ERROR_NOT_SUPPORTED: String = "not_supported"
        public const val ERROR_STORAGE: String = "storage"
        public const val ERROR_MEMORY: String = "memory"

        /** It finished, but Android cleared the file to make room before the user came back for it. */
        public const val ERROR_FILE_GONE: String = "file_gone"
        public const val ERROR_UNKNOWN: String = "unknown"

        /** On an intent that opens the app: which project to go to. */
        public const val EXTRA_PROJECT_ID: String = "org.kineograph.extra.PROJECT_ID"

        internal const val RESULT_TAG = "export_result"
        private const val CHANNEL_ID = "exports"
        private const val PROGRESS_MAX = 100
        private const val NOTIFICATION_STEP = 5

        /**
         * The project an intent that opens the app asks for. The launcher screen is open to every other app, and they can put
         * anything at all in an intent, so this only answers with what could be a project. An id is always a UUID, and
         * so is safe to go in a navigation route.
         */
        public fun requestedProject(intent: Intent): String? {
            val id =
                try {
                    intent.getStringExtra(EXTRA_PROJECT_ID)
                } catch (_: BadParcelableException) {
                    // Older Android reads all of an intent's extras at once, including classes only the sender has.
                    null
                }
            return id?.takeIf(::isProjectId)
        }

        private fun isProjectId(text: String): Boolean =
            try {
                UUID.fromString(text).toString() == text
            } catch (_: IllegalArgumentException) {
                false
            }

        /** A safe file name from a project name, keeping accented letters since users see it in their gallery. */
        public fun fileName(projectName: String, format: ExportFormat): String {
            val base = projectName
                .replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")
                .trim()
                .take(MAX_NAME)
                .ifEmpty { "animation" }
            return "$base.${GalleryWriter.extension(format)}"
        }

        private const val MAX_NAME = 80
    }
}
