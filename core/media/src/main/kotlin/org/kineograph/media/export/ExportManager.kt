package org.kineograph.media.export

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Where an export has got to. */
public sealed interface ExportStatus {
    public data class Running(val progress: Float) : ExportStatus

    public data class Finished(
        val file: File,
        val format: ExportFormat,
        val width: Int,
        val height: Int,
        val wasReduced: Boolean,
    ) : ExportStatus

    /** [code] is one of the ExportWorker.ERROR_ values. */
    public data class Failed(val code: String) : ExportStatus

    public data object Cancelled : ExportStatus
}

/** Starts exports and watches them. */
public class ExportManager(context: Context) {
    private val workManager = WorkManager.getInstance(context)

    public fun start(config: ExportConfig): UUID {
        val request =
            OneTimeWorkRequestBuilder<ExportWorker>()
                .setInputData(workDataOf(ExportWorker.KEY_CONFIG to Json.encodeToString(ExportConfig.serializer(), config)))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        workManager.enqueue(request)
        return request.id
    }

    public fun cancel(id: UUID) {
        workManager.cancelWorkById(id)
    }

    public fun observe(id: UUID, format: ExportFormat): Flow<ExportStatus> =
        workManager.getWorkInfoByIdFlow(id).map { info ->
            when (info?.state) {
                null, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                    ExportStatus.Running(0f)
                }

                WorkInfo.State.RUNNING -> {
                    ExportStatus.Running(info.progress.getFloat(ExportWorker.KEY_PROGRESS, 0f))
                }

                WorkInfo.State.SUCCEEDED -> {
                    val path = info.outputData.getString(ExportWorker.KEY_OUTPUT_PATH)
                    if (path == null) {
                        ExportStatus.Failed(ExportWorker.ERROR_UNKNOWN)
                    } else {
                        ExportStatus.Finished(
                            File(path),
                            format,
                            info.outputData.getInt(ExportWorker.KEY_WIDTH, 0),
                            info.outputData.getInt(ExportWorker.KEY_HEIGHT, 0),
                            info.outputData.getBoolean(ExportWorker.KEY_REDUCED, false),
                        )
                    }
                }

                WorkInfo.State.FAILED -> {
                    ExportStatus.Failed(info.outputData.getString(ExportWorker.KEY_ERROR) ?: ExportWorker.ERROR_UNKNOWN)
                }

                WorkInfo.State.CANCELLED -> {
                    ExportStatus.Cancelled
                }
            }
        }
}
