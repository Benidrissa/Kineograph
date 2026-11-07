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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kineograph.media.export.ExportConfig
import org.kineograph.media.export.ExportFormat
import org.kineograph.media.export.ExportStatus
import org.kineograph.media.export.ExportWorker
import org.kineograph.media.export.GalleryWriter
import org.kineograph.media.gif.GifSizes
import org.kineograph.media.video.EncoderLimits
import org.kineograph.media.video.PlatformEncoder
import org.kineograph.media.video.VideoFrameRate
import org.kineograph.media.video.VideoSizeResolver
import org.kineograph.model.Background
import org.kineograph.model.MAX_FPS
import org.kineograph.model.MIN_FPS
import org.kineograph.model.Project
import org.kineograph.ui.KgIcons
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The shape TikTok and Reels want, whatever shape the drawing is. */
internal val VERTICAL_FRAME = ExportFrame(1080, 1920)

/** A video size that does not depend on the shape of the picture. */
internal data class ExportFrame(val width: Int, val height: Int)

/** One-tap settings for common destinations. */
internal enum class ExportPreset(
    val label: Int,
    val format: ExportFormat,
    val longestEdge: Int,
    val quality: Float,
    val gifColors: Int = 256,
    val frame: ExportFrame? = null,
) {
    SMALL(R.string.export_preset_small, ExportFormat.MP4, 640, 0.7f),
    VERTICAL(R.string.export_preset_vertical, ExportFormat.MP4, 1920, 1.2f, frame = VERTICAL_FRAME),
    YOUTUBE(R.string.export_preset_youtube, ExportFormat.MP4, 1280, 1.2f),
    GIF_CHAT(R.string.export_preset_gif, ExportFormat.GIF, 480, 1f, 128),
    ;

    /** A preset with a frame fits the picture inside it. Going by the longest edge would make a 1920 × 1920 video of a square. */
    fun scaleFor(width: Int, height: Int): Float =
        if (frame != null) {
            min(frame.width.toFloat() / width, frame.height.toFloat() / height)
        } else {
            longestEdge.toFloat() / max(width, height)
        }
}

private val SCALE_CHOICES = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f)

/** The scale that makes the canvas's longest edge [text] pixels long, or null if that is not a usable size. */
internal fun scaleForLongestEdge(text: String, width: Int, height: Int): Float? {
    val edge = text.toIntOrNull() ?: return null
    if (edge < 1) return null
    return edge.toFloat() / max(width, height)
}

/**
 * The slider's stops, which must include every frame or it would move a frame chosen exactly to the nearest stop the next
 * time it was touched. Past a point, a tick per frame costs time on every drag and could not be told apart anyway, so a long
 * animation has none, and no stops.
 */
internal fun rangeSliderSteps(lastFrame: Int): Int = if (lastFrame - 1 <= MAX_RANGE_TICKS) (lastFrame - 1).coerceAtLeast(0) else 0

/** Moves the first frame, which stays between the start of the animation and the last frame. */
internal fun moveRangeStart(range: ClosedFloatingPointRange<Float>, by: Int): ClosedFloatingPointRange<Float> {
    val last = range.endInclusive.roundToInt()
    return (range.start.roundToInt() + by).coerceIn(0, last).toFloat()..last.toFloat()
}

/** Moves the last frame, which stays between the first frame and the end of the animation. */
internal fun moveRangeEnd(range: ClosedFloatingPointRange<Float>, by: Int, lastFrame: Int): ClosedFloatingPointRange<Float> {
    val first = range.start.roundToInt()
    return first.toFloat()..(range.endInclusive.roundToInt() + by).coerceIn(first, lastFrame).toFloat()
}

/** What to say under the size, before the export starts rather than after a long wait. */
internal sealed interface ExportSizeNote {
    /** The encoder cannot do the size asked for. */
    data class Reduced(val width: Int, val height: Int) : ExportSizeNote

    /** The encoder needs a slightly different size, so thin bars make up the difference. */
    data class Padded(val width: Int, val height: Int) : ExportSizeNote

    /** The encoder cannot do this size or anything near it. */
    data object Unsupported : ExportSizeNote

    /** A size so large the GIF format cannot hold it. */
    data class GifReduced(val width: Int, val height: Int) : ExportSizeNote

    /** The picture is not the frame's shape, so it fits inside with bars. */
    data class FitsInFrame(val width: Int, val height: Int, val pictureWidth: Int, val pictureHeight: Int) : ExportSizeNote

    /** The vertical frame was chosen and then the format changed to one that takes the picture's shape, which is not vertical. */
    data object GifNotVertical : ExportSizeNote
}

/** Goes through the same sizing as the export itself, so what this says is what happens. [limits] is null without an encoder. */
internal fun exportSizeNotes(
    project: Project,
    config: ExportConfig,
    chosenFrame: ExportFrame?,
    limits: EncoderLimits?,
): List<ExportSizeNote> {
    val (wantedWidth, wantedHeight) = config.requestedSize(project.width, project.height)
    if (config.format == ExportFormat.GIF) {
        val size = GifSizes.fit(wantedWidth, wantedHeight)
        return buildList {
            if (size.wasReduced) add(ExportSizeNote.GifReduced(size.width, size.height))
            if (chosenFrame != null && project.width >= project.height) add(ExportSizeNote.GifNotVertical)
        }
    }
    val fps = VideoFrameRate.encodedFps((config.fpsOverride ?: project.fps).coerceIn(MIN_FPS, MAX_FPS))
    val layout =
        limits?.let { VideoSizeResolver.layout(project.width, project.height, wantedWidth, wantedHeight, fps, it) }
            ?: return listOf(ExportSizeNote.Unsupported)
    return buildList {
        if (layout.wasReduced) add(ExportSizeNote.Reduced(layout.width, layout.height))
        if (layout.wasPadded) {
            if (chosenFrame != null) {
                add(ExportSizeNote.FitsInFrame(layout.width, layout.height, layout.pictureWidth, layout.pictureHeight))
            } else if (!layout.wasReduced) {
                add(ExportSizeNote.Padded(layout.width, layout.height))
            }
        }
    }
}

/** What finding the encoder turned up, which is slow on a cheap phone, so it happens once and away from the main thread. */
private class EncoderCheck(val limits: EncoderLimits?)

/** The choices for an export. It never adds a watermark, and there is no option that would. */
@Composable
internal fun ExportSheet(project: Project, onExport: (ExportConfig) -> Unit, onDismiss: () -> Unit) {
    EditorBottomSheet(onDismiss) {
        ExportOptions(project, onExport)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExportOptions(project: Project, onExport: (ExportConfig) -> Unit) {
    var format by remember { mutableStateOf(ExportFormat.MP4) }
    var scale by remember { mutableFloatStateOf(1f) }
    var frame by remember { mutableStateOf<ExportFrame?>(null) }
    var edgeText by remember { mutableStateOf("") }
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
    val encoder by produceState<EncoderCheck?>(null) {
        value = EncoderCheck(withContext(Dispatchers.Default) { PlatformEncoder.avcLimits() })
    }

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
            // A GIF takes the picture's shape, but going back to video brings the frame back.
            frameWidth = frame?.width,
            frameHeight = frame?.height,
        )

    // Notifications show progress and how it ended, and the editor shows both as well, so the export carries on whatever
    // the answer to this is.
    var pendingConfig by remember { mutableStateOf<ExportConfig?>(null) }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            pendingConfig?.let(onExport)
            pendingConfig = null
        }

    Text(stringResource(R.string.export_title), style = MaterialTheme.typography.titleLarge)
    Text(
        stringResource(R.string.export_no_watermark),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExportPreset.entries.forEach { preset ->
            OutlinedButton(
                onClick = {
                    format = preset.format
                    scale = preset.scaleFor(project.width, project.height)
                    frame = preset.frame
                    edgeText = ""
                    quality = preset.quality
                    gifColors = preset.gifColors
                },
                modifier = Modifier.testTag("preset_${preset.name}"),
            ) { Text(stringResource(preset.label)) }
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

    val (outWidth, outHeight) = config.requestedSize(project.width, project.height)
    Text(
        stringResource(R.string.export_resolution, outWidth, outHeight),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.testTag("export_size"),
    )
    encoder?.let { check ->
        exportSizeNotes(project, config, frame, check.limits).forEach { note ->
            Text(
                note.text(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.testTag("export_size_note"),
            )
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SCALE_CHOICES.forEach { choice ->
            FilterChip(
                selected = frame == null && scale == choice,
                onClick = {
                    scale = choice
                    // Choosing a scale means choosing the picture's own shape, so the label must stop promising the frame.
                    frame = null
                    edgeText = ""
                },
                label = { Text("$choice×") },
                modifier = Modifier.testTag("scale_$choice"),
            )
        }
    }
    OutlinedTextField(
        value = edgeText,
        onValueChange = { text ->
            val digits = text.filter(Char::isDigit)
            edgeText = digits
            scaleForLongestEdge(digits, project.width, project.height)?.let { chosen ->
                scale = chosen
                frame = null
            }
        },
        label = { Text(stringResource(R.string.export_longest_edge)) },
        singleLine = true,
        isError = edgeText.isNotEmpty() && scaleForLongestEdge(edgeText, project.width, project.height) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("export_longest_edge"),
    )

    if (lastFrame > 0) {
        val first = range.start.roundToInt() + 1
        val last = range.endInclusive.roundToInt() + 1
        Text(
            stringResource(R.string.export_range, first, last),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.testTag("export_range"),
        )
        RangeSlider(
            value = range,
            onValueChange = { range = it },
            valueRange = 0f..lastFrame.toFloat(),
            steps = rangeSliderSteps(lastFrame),
            modifier = Modifier.testTag("export_range_slider"),
        )
        // On a long animation a finger cannot pick out one frame on the slider, and a loop often needs exactly one trimmed.
        FrameStepper(
            label = stringResource(R.string.export_range_first),
            value = first,
            canGoDown = first > 1,
            canGoUp = first < last,
            downDescription = stringResource(R.string.export_range_first_earlier),
            upDescription = stringResource(R.string.export_range_first_later),
            tag = "export_first",
            onMove = { by -> range = moveRangeStart(range, by) },
        )
        FrameStepper(
            label = stringResource(R.string.export_range_last),
            value = last,
            canGoDown = last > first,
            canGoUp = last <= lastFrame,
            downDescription = stringResource(R.string.export_range_last_earlier),
            upDescription = stringResource(R.string.export_range_last_later),
            tag = "export_last",
            onMove = { by -> range = moveRangeEnd(range, by, lastFrame) },
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

    // A project with no background has nothing to leave out.
    val hasBackground = project.background != Background.Transparent
    if (hasBackground) {
        LabelledSwitch(stringResource(R.string.export_include_background), includeBackground) { includeBackground = it }
    }
    if (format == ExportFormat.MP4 && (!hasBackground || !includeBackground)) {
        Text(
            stringResource(R.string.export_video_not_transparent),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("export_video_fill_note"),
        )
    }

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

@Composable
private fun ExportSizeNote.text(): String =
    when (this) {
        is ExportSizeNote.Reduced -> stringResource(R.string.export_note_reduced, width, height)
        is ExportSizeNote.Padded -> stringResource(R.string.export_note_padded, width, height)
        ExportSizeNote.Unsupported -> stringResource(R.string.export_note_unsupported)
        is ExportSizeNote.GifReduced -> stringResource(R.string.export_note_gif_reduced, width, height)
        is ExportSizeNote.FitsInFrame -> stringResource(R.string.export_note_fits_frame, width, height, pictureWidth, pictureHeight)
        ExportSizeNote.GifNotVertical -> stringResource(R.string.export_note_gif_not_vertical)
    }

@Composable
private fun FrameStepper(
    label: String,
    value: Int,
    canGoDown: Boolean,
    canGoUp: Boolean,
    downDescription: String,
    upDescription: String,
    tag: String,
    onMove: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = { onMove(-1) }, enabled = canGoDown, modifier = Modifier.testTag("${tag}_down")) {
            Icon(KgIcons.Minus, contentDescription = downDescription)
        }
        Text(value.toString(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("${tag}_value"))
        IconButton(onClick = { onMove(1) }, enabled = canGoUp, modifier = Modifier.testTag("${tag}_up")) {
            Icon(KgIcons.Add, contentDescription = upDescription)
        }
    }
}

private fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Shows progress with a working Cancel, then what can be done with the result. */
@Composable
internal fun ExportDialog(state: ExportUiState, actions: ExportDialogActions) {
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
                        onClick = actions.onCancel,
                        modifier = Modifier.testTag("export_cancel"),
                    ) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }

        is ExportStatus.Finished -> {
            LaunchedEffect(status) { actions.onResultShown() }
            ExportFinishedDialog(status, state, actions)
        }

        is ExportStatus.Failed -> {
            LaunchedEffect(status) { actions.onResultShown() }
            AlertDialog(
                onDismissRequest = actions.onDismiss,
                title = { Text(stringResource(R.string.export_failed_title), Modifier.testTag("export_failed")) },
                text = { Text(stringResource(failureText(status.code))) },
                confirmButton = { TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.action_ok)) } },
            )
        }

        ExportStatus.Cancelled -> {
            LaunchedEffect(Unit) { actions.onDismiss() }
        }
    }
}

/** What the user can do from the export dialog. */
internal class ExportDialogActions(
    val onCancel: () -> Unit,
    val onDismiss: () -> Unit,
    val onSave: () -> Unit,
    /** Another app was given the file and may read it at any time from now on. */
    val onHandedOff: () -> Unit,
    /** The result is on the screen. */
    val onResultShown: () -> Unit,
)

private fun failureText(code: String): Int =
    when (code) {
        ExportWorker.ERROR_NOT_SUPPORTED -> R.string.export_failed_not_supported
        ExportWorker.ERROR_STORAGE -> R.string.export_failed_storage
        ExportWorker.ERROR_MEMORY -> R.string.export_failed_memory
        ExportWorker.ERROR_NO_PROJECT -> R.string.export_failed_no_project
        ExportWorker.ERROR_FILE_GONE -> R.string.export_failed_file_gone
        else -> R.string.export_failed_unknown
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExportFinishedDialog(status: ExportStatus.Finished, state: ExportUiState, actions: ExportDialogActions) {
    val context = LocalContext.current
    var permissionRefused by remember { mutableStateOf(false) }

    val storagePermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionRefused = !granted
            if (granted) actions.onSave()
        }

    AlertDialog(
        // Closing while saving would leave the user never knowing whether it worked.
        onDismissRequest = { if (!state.saving) actions.onDismiss() },
        title = { Text(stringResource(R.string.export_finished)) },
        text = {
            // Two notes at the largest font size can be more than fits.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.export_finished_size, status.width, status.height))
                if (status.wasReduced) {
                    Text(
                        stringResource(R.string.export_size_reduced),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (status.wasPadded) {
                    Text(
                        stringResource(R.string.export_size_padded),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.saving) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("export_saving"))
                    Text(stringResource(R.string.export_saving), style = MaterialTheme.typography.bodySmall)
                }
                if (state.saved) Text(stringResource(R.string.export_saved), color = MaterialTheme.colorScheme.primary)
                if (state.saveFailed || permissionRefused) {
                    Text(stringResource(R.string.export_save_failed), color = MaterialTheme.colorScheme.error)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ResultButton(
                        KgIcons.Save,
                        stringResource(R.string.export_save),
                        "export_save",
                        enabled = !state.saved && !state.saving,
                    ) {
                        if (GalleryWriter.needsStoragePermission && !isGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            actions.onSave()
                        }
                    }
                    ResultButton(
                        KgIcons.Share,
                        stringResource(R.string.export_share),
                        "export_share",
                    ) {
                        actions.onHandedOff()
                        share(context, status.file, status.format)
                    }
                    ResultButton(
                        KgIcons.Open,
                        stringResource(R.string.export_open),
                        "export_open",
                    ) {
                        actions.onHandedOff()
                        open(context, status.file, status.format)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onDismiss, enabled = !state.saving, modifier = Modifier.testTag("export_done")) {
                Text(stringResource(R.string.export_done))
            }
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

private const val MAX_RANGE_TICKS = 100
