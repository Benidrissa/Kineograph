package org.kineograph.feature.editor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.kineograph.media.export.ExportConfig
import org.kineograph.media.export.ExportFormat
import org.kineograph.media.export.ExportStatus
import org.kineograph.media.export.ExportWorker
import org.kineograph.media.export.GalleryWriter
import org.kineograph.model.MAX_FPS
import org.kineograph.model.MIN_FPS
import org.kineograph.model.Project
import org.kineograph.ui.KgIcons
import org.kineograph.ui.kgSliderColors
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** One-tap settings for common destinations. */
private enum class ExportPreset(
    val label: Int,
    val format: ExportFormat,
    val longestEdge: Int,
    val quality: Float,
    val gifColors: Int = 256,
) {
    SMALL(R.string.export_preset_small, ExportFormat.MP4, 640, 0.7f),
    VERTICAL(R.string.export_preset_vertical, ExportFormat.MP4, 1920, 1.2f),
    YOUTUBE(R.string.export_preset_youtube, ExportFormat.MP4, 1280, 1.2f),
    GIF_CHAT(R.string.export_preset_gif, ExportFormat.GIF, 480, 1f, 128),
}

private val SCALE_CHOICES = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f)

/** The choices for an export. It never adds a watermark, and there is no option that would. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExportSheet(project: Project, onExport: (ExportConfig) -> Unit, onDismiss: () -> Unit) {
    var format by remember { mutableStateOf(ExportFormat.MP4) }
    var scale by remember { mutableFloatStateOf(1f) }
    var fpsOverride by remember { mutableStateOf<Int?>(null) }
    var repeat by remember { mutableIntStateOf(1) }
    var includeBackground by remember { mutableStateOf(true) }
    var dither by remember { mutableStateOf(true) }
    var gifColors by remember { mutableIntStateOf(256) }
    var loopForever by remember { mutableStateOf(true) }
    var quality by remember { mutableFloatStateOf(1f) }
    val lastFrame = project.frames.lastIndex
    var range by remember { mutableStateOf(0f..lastFrame.toFloat()) }
    val context = LocalContext.current

    // Notifications only show progress, so the export carries on whatever the answer to this is.
    var pendingConfig by remember { mutableStateOf<ExportConfig?>(null) }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            pendingConfig?.let(onExport)
            pendingConfig = null
        }

    EditorBottomSheet(onDismiss) {
        Text(stringResource(R.string.export_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.export_no_watermark),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExportPreset.entries.forEach { preset ->
                OutlinedButton(onClick = {
                    format = preset.format
                    scale = preset.longestEdge.toFloat() / max(project.width, project.height)
                    quality = preset.quality
                    gifColors = preset.gifColors
                }) { Text(stringResource(preset.label)) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = format == ExportFormat.MP4, onClick = {
                format = ExportFormat.MP4
            }, label = { Text(stringResource(R.string.export_format_mp4)) }, modifier = Modifier.testTag("format_mp4"))
            FilterChip(selected = format == ExportFormat.GIF, onClick = {
                format = ExportFormat.GIF
            }, label = { Text(stringResource(R.string.export_format_gif)) }, modifier = Modifier.testTag("format_gif"))
        }

        val outWidth = (project.width * scale).roundToInt().coerceAtLeast(1)
        val outHeight = (project.height * scale).roundToInt().coerceAtLeast(1)
        Text(stringResource(R.string.export_resolution, outWidth, outHeight), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SCALE_CHOICES.forEach { choice ->
                FilterChip(selected = scale == choice, onClick = { scale = choice }, label = { Text("$choice×") })
            }
        }

        if (lastFrame > 0) {
            val first = range.start.roundToInt() + 1
            val last = range.endInclusive.roundToInt() + 1
            Text(stringResource(R.string.export_range, first, last), style = MaterialTheme.typography.labelLarge)
            RangeSlider(
                value = range,
                onValueChange = { range = it },
                valueRange = 0f..lastFrame.toFloat(),
                colors = kgSliderColors(),
                steps = (lastFrame - 1).coerceIn(0, MAX_RANGE_STEPS),
            )
        }

        val shownFps = fpsOverride ?: project.fps
        LabelledSlider(
            label = stringResource(R.string.project_fps),
            value = shownFps.toFloat(),
            valueText = stringResource(R.string.fps_value, shownFps),
            range = MIN_FPS.toFloat()..MAX_FPS.toFloat(),
            onChange = {
                val value = it.roundToInt()
                fpsOverride = if (value == project.fps) null else value
            },
        )

        if (format == ExportFormat.MP4 || !loopForever) {
            LabelledSlider(
                label = stringResource(R.string.export_repeat),
                value = repeat.toFloat(),
                valueText = stringResource(R.string.export_repeat_value, repeat),
                range = 1f..10f,
                steps = 8,
                onChange = { repeat = it.roundToInt() },
            )
        }

        LabelledSwitch(stringResource(R.string.export_include_background), includeBackground) { includeBackground = it }

        if (format == ExportFormat.GIF) {
            LabelledSwitch(stringResource(R.string.export_loop_forever), loopForever) { loopForever = it }
            LabelledSwitch(stringResource(R.string.export_dither), dither) { dither = it }
            LabelledSlider(
                label = stringResource(R.string.export_colors),
                value = gifColors.toFloat(),
                valueText = gifColors.toString(),
                range = 8f..256f,
                onChange = { gifColors = it.roundToInt() },
            )
        }

        Button(
            onClick = {
                val config =
                    ExportConfig(
                        projectId = project.id,
                        format = format,
                        scale = scale,
                        fpsOverride = fpsOverride,
                        rangeStart = range.start.roundToInt(),
                        rangeEnd = range.endInclusive.roundToInt(),
                        repeatCount = repeat,
                        includeBackground = includeBackground,
                        gifDither = dither,
                        gifMaxColors = gifColors,
                        gifLoopForever = loopForever,
                        videoQuality = quality,
                    )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isGranted(context, Manifest.permission.POST_NOTIFICATIONS)) {
                    pendingConfig = config
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    onExport(config)
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp).testTag("start_export"),
        ) {
            Text(stringResource(R.string.export_start))
        }
    }
}

private fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Shows progress with a working Cancel, then what can be done with the result. */
@Composable
internal fun ExportDialog(state: ExportUiState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    when (val status = state.status) {
        is ExportStatus.Running -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.export_running)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LinearProgressIndicator(
                            progress = { status.progress },
                            modifier = Modifier.fillMaxWidth().testTag("export_progress"),
                        )
                        Text("${(status.progress * 100f).roundToInt()}%")
                        Text(stringResource(R.string.export_keeps_running), style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("export_cancel"),
                    ) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }

        is ExportStatus.Finished -> {
            ExportFinishedDialog(status, onDismiss)
        }

        is ExportStatus.Failed -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.export_failed_title), Modifier.testTag("export_failed")) },
                text = { Text(stringResource(failureText(status.code))) },
                confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
            )
        }

        ExportStatus.Cancelled -> {
            LaunchedEffect(Unit) { onDismiss() }
        }
    }
}

private fun failureText(code: String): Int =
    when (code) {
        ExportWorker.ERROR_NOT_SUPPORTED -> R.string.export_failed_not_supported
        ExportWorker.ERROR_STORAGE -> R.string.export_failed_storage
        ExportWorker.ERROR_MEMORY -> R.string.export_failed_memory
        ExportWorker.ERROR_NO_PROJECT -> R.string.export_failed_no_project
        else -> R.string.export_failed_unknown
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExportFinishedDialog(status: ExportStatus.Finished, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }

    fun saveNow() {
        try {
            GalleryWriter.save(context, status.file, status.format)
            saved = true
            saveFailed = false
        } catch (_: IOException) {
            saveFailed = true
        } catch (_: SecurityException) {
            saveFailed = true
        }
    }

    val storagePermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) saveNow() else saveFailed = true
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_finished)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.export_finished_size, status.width, status.height))
                if (status.wasReduced) {
                    Text(
                        stringResource(R.string.export_size_reduced),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (saved) Text(stringResource(R.string.export_saved), color = MaterialTheme.colorScheme.primary)
                if (saveFailed) Text(stringResource(R.string.export_save_failed), color = MaterialTheme.colorScheme.error)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ResultButton(KgIcons.Save, stringResource(R.string.export_save), "export_save", enabled = !saved) {
                        if (GalleryWriter.needsStoragePermission && !isGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            saveNow()
                        }
                    }
                    ResultButton(
                        KgIcons.Share,
                        stringResource(R.string.export_share),
                        "export_share",
                    ) { share(context, status.file, status.format) }
                    ResultButton(
                        KgIcons.Open,
                        stringResource(R.string.export_open),
                        "export_open",
                    ) { open(context, status.file, status.format) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("export_done")) { Text(stringResource(R.string.export_done)) }
        },
    )
}

@Composable
private fun ResultButton(icon: ImageVector, label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) {
        Icon(icon, contentDescription = null)
        Text(label)
    }
}

private fun share(context: Context, file: File, format: ExportFormat) {
    launchChooser(
        context,
        Intent(Intent.ACTION_SEND).apply {
            type = GalleryWriter.mimeType(format)
            putExtra(Intent.EXTRA_STREAM, GalleryWriter.shareableUri(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
    )
}

private fun open(context: Context, file: File, format: ExportFormat) {
    val uri: Uri = GalleryWriter.shareableUri(context, file)
    launchChooser(
        context,
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, GalleryWriter.mimeType(format))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
    )
}

private fun launchChooser(context: Context, intent: Intent) {
    try {
        context.startActivity(Intent.createChooser(intent, null))
    } catch (_: android.content.ActivityNotFoundException) {
        // Nothing on this phone can handle it. The file is still there to be saved.
    }
}

private const val MAX_RANGE_STEPS = 100
