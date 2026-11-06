package org.kineograph.media.export

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.kineograph.data.ProjectRepository
import org.kineograph.media.R
import java.io.File
import java.io.IOException

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

    override suspend fun doWork(): Result {
        val configText = inputData.getString(KEY_CONFIG) ?: return Result.failure(failure(ERROR_UNKNOWN))
        val config =
            try {
                Json.decodeFromString(ExportConfig.serializer(), configText)
            } catch (_: IllegalArgumentException) {
                return Result.failure(failure(ERROR_UNKNOWN))
            }
        val dependencies = applicationContext as? ExportDependencies ?: return Result.failure(failure(ERROR_UNKNOWN))
        val repository = dependencies.projectRepository
        val opened = repository.open(config.projectId) ?: return Result.failure(failure(ERROR_NO_PROJECT))

        try {
            setForeground(foregroundInfo(0f))
        } catch (_: IllegalStateException) {
            // The system may refuse to start a foreground service while in the background.
            // The export still runs; it just will not show a notification and could be stopped sooner.
        }

        val directory = File(applicationContext.cacheDir, EXPORT_DIRECTORY).apply { mkdirs() }
        val project = opened.loaded.project
        val output = File(directory, fileName(project.name, config.format))
        var lastReported = -1
        return try {
            val result =
                ProjectExporter(opened.store).export(project, config, output) { progress ->
                    val percent = (progress * 100f).toInt()
                    if (percent != lastReported) {
                        lastReported = percent
                        reportProgress(progress, percent)
                    }
                }
            Result.success(
                workDataOf(
                    KEY_OUTPUT_PATH to result.file.path,
                    KEY_WIDTH to result.width,
                    KEY_HEIGHT to result.height,
                    KEY_REDUCED to result.wasReduced,
                ),
            )
        } catch (e: CancellationException) {
            output.delete()
            throw e
        } catch (_: ExportNotSupportedException) {
            output.delete()
            Result.failure(failure(ERROR_NOT_SUPPORTED))
        } catch (_: IllegalStateException) {
            // This is what MediaCodec throws when an encoder fails part-way through.
            output.delete()
            Result.failure(failure(ERROR_NOT_SUPPORTED))
        } catch (_: IOException) {
            output.delete()
            Result.failure(failure(ERROR_STORAGE))
        } catch (_: OutOfMemoryError) {
            output.delete()
            Result.failure(failure(ERROR_MEMORY))
        } finally {
            opened.store.close()
        }
    }

    private fun failure(code: String) = workDataOf(KEY_ERROR to code)

    private fun reportProgress(progress: Float, percent: Int) {
        // Progress goes to the UI and notification in the background, so it can never slow encoding down.
        setProgressAsync(workDataOf(KEY_PROGRESS to progress))
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (percent % NOTIFICATION_STEP == 0) manager?.notify(notificationId, buildNotification(progress))
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

    private fun buildNotification(progress: Float): android.app.Notification {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    applicationContext.getString(R.string.export_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        return NotificationCompat
            .Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.export_notification_title))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(PROGRESS_MAX, (progress * PROGRESS_MAX).toInt(), false)
            .addAction(0, applicationContext.getString(R.string.export_cancel), cancel)
            .build()
    }

    public companion object {
        public const val KEY_CONFIG: String = "config"
        public const val KEY_PROGRESS: String = "progress"
        public const val KEY_OUTPUT_PATH: String = "output"
        public const val KEY_WIDTH: String = "width"
        public const val KEY_HEIGHT: String = "height"
        public const val KEY_REDUCED: String = "reduced"
        public const val KEY_ERROR: String = "error"

        public const val ERROR_NO_PROJECT: String = "no_project"
        public const val ERROR_NOT_SUPPORTED: String = "not_supported"
        public const val ERROR_STORAGE: String = "storage"
        public const val ERROR_MEMORY: String = "memory"
        public const val ERROR_UNKNOWN: String = "unknown"

        private const val CHANNEL_ID = "exports"
        private const val EXPORT_DIRECTORY = "exports"
        private const val PROGRESS_MAX = 100
        private const val NOTIFICATION_STEP = 5

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
