package org.kineograph.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.kineograph.engine.compositor.BackgroundPainter
import org.kineograph.media.svg.SvgDocument
import org.kineograph.media.svg.SvgWarning
import org.kineograph.media.svgimport.SvgFit
import org.kineograph.media.svgimport.SvgImportOutcome
import org.kineograph.media.svgimport.SvgImportPlace
import org.kineograph.media.svgimport.sampleStartSeconds
import org.kineograph.media.svgimport.svgDestination
import org.kineograph.model.ImportEdits
import org.kineograph.model.MAX_FPS
import org.kineograph.model.MIN_FPS
import org.kineograph.model.Project
import org.kineograph.ui.KgIcons
import org.kineograph.ui.VisualDropdown
import org.kineograph.ui.VisualOption
import org.kineograph.ui.rememberReduceMotion
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.foundation.Canvas as ComposeCanvas

/** Shows whatever the import is doing right now: reading the file, asking what to do with it, or working. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SvgImportHost(
    controller: SvgImportController,
    state: SvgImportState,
    project: Project,
    frameId: String,
    canDrawOnActiveLayer: Boolean,
    backgroundImage: Bitmap?,
) {
    when (state) {
        SvgImportState.Idle -> {
            Unit
        }

        SvgImportState.Reading -> {
            SvgBusyDialog(stringResource(R.string.svg_import_reading), null, null, controller::cancel)
        }

        is SvgImportState.Ready -> {
            ModalBottomSheet(
                onDismissRequest = controller::dismiss,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                SvgImportForm(
                    file = state.file,
                    project = project,
                    frameNumber = project.frameIndex(frameId) + 1,
                    canDrawOnActiveLayer = canDrawOnActiveLayer,
                    choices = state.choices,
                    problem = state.problem,
                    backgroundImage = backgroundImage,
                    onChange = controller::change,
                    onImport = controller::start,
                    onDismiss = controller::dismiss,
                )
            }
        }

        is SvgImportState.Running -> {
            SvgBusyDialog(
                title = stringResource(R.string.svg_import_running),
                detail = if (state.done > 0) stringResource(R.string.svg_import_progress, state.done, state.total) else null,
                progress = state.done.toFloat() / max(state.total, 1),
                onCancel = controller::cancel,
            )
        }
    }
}

/** Progress that cannot be dismissed by accident, with a Cancel that always works. Without [progress], it shows only that something is happening. */
@Composable
private fun SvgBusyDialog(title: String, detail: String?, progress: Float?, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().testTag("svg_import_progress"))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("svg_import_progress"))
                }
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("svg_import_cancel_running")) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * The choices for one import. Everything scrolls except the buttons, so that Import stays on screen however long the list of warnings gets.
 * The parent has to give this a bounded height, as a bottom sheet does.
 */
@Composable
internal fun SvgImportForm(
    file: SvgFile,
    project: Project,
    frameNumber: Int,
    canDrawOnActiveLayer: Boolean,
    choices: SvgImportChoices,
    problem: SvgImportOutcome?,
    backgroundImage: Bitmap?,
    onChange: (SvgImportChoices) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
    reduceMotion: Boolean = rememberReduceMotion(),
) {
    val refused = choices.place == SvgImportPlace.CURRENT_LAYER && !choices.asAnimation && !canDrawOnActiveLayer
    // Drawing can find more that the file asks too much of. What the preview finds out is worth saying here, not only after the import.
    var warnings by remember(file) { mutableStateOf(file.document.warnings) }
    Column(Modifier.fillMaxWidth().imePadding()) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.svg_import_title), style = MaterialTheme.typography.titleLarge)
            SvgFileSummary(file)
            SvgPreview(file.document, project, backgroundImage, choices, previewMotion(file, choices, project.fps, reduceMotion)) {
                warnings = file.document.warnings
            }
            if (file.canAnimate) ImportAsDropdown(choices.asAnimation) { onChange(choices.copy(asAnimation = it)) }
            if (choices.asAnimation) {
                DurationChoice(file, project, choices, onChange)
                AnimationPlacement(frameNumber)
            } else {
                if (file.canAnimate) MomentChoice(file, choices, onChange)
                WhereDropdown(choices.place) { onChange(choices.copy(place = it)) }
            }
            FitDropdown(choices.fit) { onChange(choices.copy(fit = it)) }
            SvgWarnings(warnings)
            if (refused) {
                ProblemText(stringResource(R.string.message_layer_locked))
            } else if (problem != null) {
                ProblemText(stringResource(problem.textRes()))
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp).testTag("svg_import_dismiss")) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(
                onClick = onImport,
                enabled = choices.toRequest(file) != null,
                modifier = Modifier.weight(1f).height(48.dp).testTag("svg_import_button"),
            ) {
                Text(stringResource(R.string.svg_import_start))
            }
        }
    }
}

@Composable
private fun ProblemText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("svg_import_problem").semantics { liveRegion = LiveRegionMode.Polite },
    )
}

private fun SvgImportOutcome.textRes(): Int =
    when (this) {
        SvgImportOutcome.LAYER_NOT_DRAWABLE -> R.string.message_layer_locked
        SvgImportOutcome.NOTHING_TO_IMPORT -> R.string.svg_import_nothing
        SvgImportOutcome.OUT_OF_MEMORY -> R.string.svg_import_out_of_memory
        SvgImportOutcome.OUT_OF_STORAGE -> R.string.svg_import_out_of_storage
        SvgImportOutcome.DONE, SvgImportOutcome.FAILED -> R.string.svg_import_failed
    }

/** What the file is: its name, its size, and how it moves. */
@Composable
private fun SvgFileSummary(file: SvgFile) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val animation = file.document.animation
    val seconds = formatSeconds(animation.durationSeconds, locale)
    Column {
        Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            stringResource(
                R.string.svg_import_size,
                file.document.width.roundToInt(),
                file.document.height.roundToInt(),
                Formatter.formatShortFileSize(context, file.sizeBytes),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            when {
                !file.canAnimate -> stringResource(R.string.svg_import_static)
                animation.loopsForever -> stringResource(R.string.svg_import_animated_loops, seconds)
                else -> stringResource(R.string.svg_import_animated_once, seconds)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("svg_import_motion"),
        )
    }
}

/** Seconds, with as many decimals as the number needs and at least the one that makes it read as a measurement. */
internal fun formatSeconds(seconds: Double, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).run {
        isGroupingUsed = false
        minimumFractionDigits = 1
        maximumFractionDigits = 2
        format(seconds)
    }

@Composable
private fun DurationChoice(file: SvgFile, project: Project, choices: SvgImportChoices, onChange: (SvgImportChoices) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val suggested = file.document.animation.durationSeconds
    val duration = choices.durationSeconds(file)
    // The field keeps its own text, because choices come back a moment later, and a text field that lags behind the keyboard loses letters.
    var text by remember { mutableStateOf(choices.durationText ?: formatSeconds(suggested, locale)) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { typed ->
                text = typed.filter { it.isDigit() || it == '.' || it == ',' }.take(MAX_DURATION_CHARS)
                onChange(choices.copy(durationText = text))
            },
            label = { Text(stringResource(R.string.svg_import_duration)) },
            suffix = { Text(stringResource(R.string.svg_import_seconds_unit)) },
            singleLine = true,
            isError = duration == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f).testTag("svg_import_duration"),
        )
        if (choices.durationText != null) {
            TextButton(
                onClick = {
                    text = formatSeconds(suggested, locale)
                    onChange(choices.copy(durationText = null))
                },
                modifier = Modifier.heightIn(min = 48.dp).testTag("svg_import_duration_reset"),
            ) {
                Text(stringResource(R.string.svg_import_duration_reset, formatSeconds(suggested, locale)))
            }
        }
    }
    if (duration == null) {
        ProblemText(stringResource(R.string.svg_import_duration_invalid))
    } else {
        val frames = ImportEdits.tickCount(duration, project.fps)
        Text(
            pluralStringResource(R.plurals.svg_import_frames, frames, formatSeconds(duration, locale), project.fps, frames),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("svg_import_frames").semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (isLargeImport(frames, project.width, project.height)) {
            Text(
                stringResource(
                    R.string.svg_import_large_warning,
                    Formatter.formatShortFileSize(LocalContext.current, likelyStorageBytes(frames, project.width, project.height)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.testTag("svg_import_large"),
            )
        }
    }
}

/** What an uncompressed frame would hold, summed. Nearly all of it is empty or flat, which is what keeps the real cost far lower. */
private fun rawBytes(frames: Int, width: Int, height: Int): Long = frames.toLong() * width * height * BYTES_PER_PIXEL

internal fun likelyStorageBytes(frames: Int, width: Int, height: Int): Long = rawBytes(frames, width, height) / LIKELY_COMPRESSION

/** Big enough to deserve a word of warning, on this phone. Nothing is ever refused because of this. */
internal fun isLargeImport(frames: Int, width: Int, height: Int, heapBytes: Long = Runtime.getRuntime().maxMemory()): Boolean =
    frames >= LARGE_FRAME_COUNT || rawBytes(frames, width, height) >= heapBytes * LARGE_HEAPS

@Composable
private fun AnimationPlacement(frameNumber: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PlaceGlyph(SvgImportPlace.NEW_LAYER)
        Text(
            stringResource(R.string.svg_import_animation_where, frameNumber),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("svg_import_animation_where"),
        )
    }
}

@Composable
private fun MomentChoice(file: SvgFile, choices: SvgImportChoices, onChange: (SvgImportChoices) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val last = file.lastMomentSeconds.toFloat()
    LabelledSlider(
        label = stringResource(R.string.svg_import_moment),
        value = choices.momentSeconds.toFloat().coerceIn(0f, last),
        valueText = stringResource(R.string.svg_import_moment_value, formatSeconds(choices.momentSeconds, locale)),
        range = 0f..last,
        testTag = "svg_import_moment",
        onChange = { onChange(choices.copy(momentSeconds = it.toDouble())) },
    )
}

@Composable
private fun ImportAsDropdown(asAnimation: Boolean, onChange: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val choose: (Boolean) -> Unit = {
        expanded = false
        onChange(it)
    }
    VisualDropdown(
        label = stringResource(R.string.svg_import_as),
        valueText = stringResource(if (asAnimation) R.string.svg_import_as_animation else R.string.svg_import_as_image),
        expanded = expanded,
        onExpandedChange = { expanded = it },
        tag = "svg_import_as",
        leading = { ImportAsGlyph(asAnimation) },
    ) {
        VisualOption(
            title = stringResource(R.string.svg_import_as_animation),
            hint = stringResource(R.string.svg_import_as_animation_hint),
            selected = asAnimation,
            tag = "svg_import_as_animation",
            preview = { ImportAsGlyph(true) },
            onClick = { choose(true) },
        )
        VisualOption(
            title = stringResource(R.string.svg_import_as_image),
            hint = stringResource(R.string.svg_import_as_image_hint),
            selected = !asAnimation,
            tag = "svg_import_as_image",
            preview = { ImportAsGlyph(false) },
            onClick = { choose(false) },
        )
    }
}

@Composable
private fun WhereDropdown(place: SvgImportPlace, onChange: (SvgImportPlace) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    VisualDropdown(
        label = stringResource(R.string.svg_import_where),
        valueText = stringResource(place.titleRes()),
        expanded = expanded,
        onExpandedChange = { expanded = it },
        tag = "svg_import_where",
        leading = { PlaceGlyph(place) },
    ) {
        SvgImportPlace.entries.forEach { option ->
            VisualOption(
                title = stringResource(option.titleRes()),
                hint =
                    stringResource(
                        when (option) {
                            SvgImportPlace.NEW_LAYER -> R.string.svg_import_where_new_layer_hint
                            SvgImportPlace.CURRENT_LAYER -> R.string.svg_import_where_current_layer_hint
                            SvgImportPlace.BACKGROUND -> R.string.svg_import_where_background_hint
                        },
                    ),
                selected = place == option,
                tag = "svg_import_where_${option.name.lowercase()}",
                preview = { PlaceGlyph(option) },
                onClick = {
                    expanded = false
                    onChange(option)
                },
            )
        }
    }
}

private fun SvgImportPlace.titleRes(): Int =
    when (this) {
        SvgImportPlace.NEW_LAYER -> R.string.svg_import_where_new_layer
        SvgImportPlace.CURRENT_LAYER -> R.string.svg_import_where_current_layer
        SvgImportPlace.BACKGROUND -> R.string.svg_import_where_background
    }

@Composable
private fun FitDropdown(fit: SvgFit, onChange: (SvgFit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    VisualDropdown(
        label = stringResource(R.string.svg_import_fit),
        valueText = stringResource(fit.titleRes()),
        expanded = expanded,
        onExpandedChange = { expanded = it },
        tag = "svg_import_fit",
        leading = { FitGlyph(fit) },
    ) {
        SvgFit.entries.forEach { option ->
            VisualOption(
                title = stringResource(option.titleRes()),
                hint =
                    stringResource(
                        when (option) {
                            SvgFit.FIT_INSIDE -> R.string.svg_import_fit_inside_hint
                            SvgFit.FILL_CANVAS -> R.string.svg_import_fit_fill_hint
                            SvgFit.ORIGINAL_SIZE -> R.string.svg_import_fit_original_hint
                        },
                    ),
                selected = fit == option,
                tag = "svg_import_fit_${option.name.lowercase()}",
                preview = { FitGlyph(option) },
                onClick = {
                    expanded = false
                    onChange(option)
                },
            )
        }
    }
}

private fun SvgFit.titleRes(): Int =
    when (this) {
        SvgFit.FIT_INSIDE -> R.string.svg_import_fit_inside
        SvgFit.FILL_CANVAS -> R.string.svg_import_fit_fill
        SvgFit.ORIGINAL_SIZE -> R.string.svg_import_fit_original
    }

/** A canvas with the picture on it, as each fit would place it. The picture is taller than the canvas, so that the difference shows. */
@Composable
private fun FitGlyph(fit: SvgFit) {
    val colors = MaterialTheme.colorScheme
    val outline = colors.outline
    val picture = colors.primary
    val spill = colors.primary.copy(alpha = OVERFLOW_ALPHA)
    ComposeCanvas(Modifier.size(GLYPH_SIZE.dp)) {
        val canvasWidth = size.width * GLYPH_CANVAS_WIDTH
        val canvasHeight = size.height * GLYPH_CANVAS_HEIGHT
        val canvasLeft = (size.width - canvasWidth) / 2f
        val canvasTop = (size.height - canvasHeight) / 2f
        val side =
            when (fit) {
                SvgFit.FIT_INSIDE -> canvasHeight
                SvgFit.FILL_CANVAS -> canvasWidth
                SvgFit.ORIGINAL_SIZE -> canvasHeight * GLYPH_ORIGINAL_SIZE
            }
        val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
        // What falls outside the canvas shows faintly, as it would be cut off.
        drawRect(spill, topLeft, Size(side, side))
        clipRect(canvasLeft, canvasTop, canvasLeft + canvasWidth, canvasTop + canvasHeight) {
            drawRect(picture, topLeft, Size(side, side))
        }
        drawRect(outline, Offset(canvasLeft, canvasTop), Size(canvasWidth, canvasHeight), style = Stroke(GLYPH_LINE.dp.toPx()))
    }
}

/** A stack of two sheets, with the part this choice touches drawn solid. */
@Composable
private fun PlaceGlyph(place: SvgImportPlace) {
    val colors = MaterialTheme.colorScheme
    val outline = colors.outline
    val solid = colors.primary
    val faint = colors.surfaceVariant
    ComposeCanvas(Modifier.size(GLYPH_SIZE.dp)) {
        val side = size.width * GLYPH_SHEET_SIZE
        val back = Offset(size.width - side, 0f)
        val front = Offset(0f, size.height - side)
        val sheet = Size(side, side)
        val line = Stroke(GLYPH_LINE.dp.toPx())
        drawRect(if (place == SvgImportPlace.BACKGROUND) solid else faint, back, sheet)
        drawRect(outline, back, sheet, style = line)
        when (place) {
            SvgImportPlace.NEW_LAYER -> {
                drawRect(solid, front, sheet)
            }

            SvgImportPlace.CURRENT_LAYER -> {
                drawRect(faint, front, sheet)
                val inner = side * GLYPH_INNER_SIZE
                drawRect(solid, Offset(front.x + (side - inner) / 2f, front.y + (side - inner) / 2f), Size(inner, inner))
            }

            SvgImportPlace.BACKGROUND -> {
                drawRect(faint, front, sheet)
            }
        }
        drawRect(outline, front, sheet, style = line)
    }
}

/** A strip of frames for an animation, and a single frame for one image. */
@Composable
private fun ImportAsGlyph(asAnimation: Boolean) {
    val colors = MaterialTheme.colorScheme
    val outline = colors.outline
    val solid = colors.primary
    ComposeCanvas(Modifier.size(GLYPH_SIZE.dp)) {
        val line = Stroke(GLYPH_LINE.dp.toPx())
        if (asAnimation) {
            val cell = size.width / GLYPH_STRIP_CELLS
            for (index in 0 until GLYPH_STRIP_CELLS) {
                val topLeft = Offset(index * cell, (size.height - cell) / 2f)
                drawRect(solid.copy(alpha = (index + 1f) / GLYPH_STRIP_CELLS), topLeft, Size(cell, cell))
                drawRect(outline, topLeft, Size(cell, cell), style = line)
            }
        } else {
            val side = size.width * GLYPH_SHEET_SIZE
            val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
            drawRect(solid, topLeft, Size(side, side))
            drawRect(outline, topLeft, Size(side, side), style = line)
        }
    }
}

@Composable
private fun SvgWarnings(warnings: Set<SvgWarning>) {
    warnings.forEach { warning ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.testTag("svg_import_warning_${warning.name.lowercase()}"),
        ) {
            Icon(KgIcons.Info, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(warning.textRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun SvgWarning.textRes(): Int =
    when (this) {
        SvgWarning.SCRIPT_IGNORED -> R.string.svg_warning_script
        SvgWarning.EXTERNAL_REFERENCE_IGNORED -> R.string.svg_warning_external
        SvgWarning.FOREIGN_OBJECT_IGNORED -> R.string.svg_warning_foreign_object
        SvgWarning.FILTER_IGNORED -> R.string.svg_warning_filter
        SvgWarning.EVENT_TRIGGER_IGNORED -> R.string.svg_warning_event
        SvgWarning.UNSUPPORTED_FEATURE_IGNORED -> R.string.svg_warning_unsupported
        SvgWarning.INVALID_CONTENT_IGNORED -> R.string.svg_warning_invalid
        SvgWarning.LIMIT_REACHED -> R.string.svg_warning_limit
    }

/** What the preview shows over time. */
internal sealed interface PreviewMotion {
    data class Still(val seconds: Double) : PreviewMotion

    /** The same moments an import would sample, at the rate the project will play them. */
    data class Playing(val startSeconds: Double, val tickCount: Int, val fps: Int) : PreviewMotion {
        fun tickAt(elapsedNanos: Long): Int = ((elapsedNanos.toDouble() * fps / NANOS_PER_SECOND).toLong() % tickCount).toInt()

        fun secondsAt(tick: Int): Double = startSeconds + tick.toDouble() / fps

        /** Waits only for when there is something new to show, so that a slow rate is not woken up sixty times a second for nothing. */
        fun millisToNextTick(elapsedNanos: Long): Long {
            val tickNanos = NANOS_PER_SECOND / fps
            return ((tickNanos - elapsedNanos % tickNanos) / NANOS_PER_MILLI).toLong().coerceAtLeast(1L)
        }
    }
}

/**
 * Plays an animation unless the user has turned animations off, and then shows the middle of it, which is far more likely than the first frame
 * to have something on it. What was typed may not be a length of time yet, and the preview just keeps going with the suggestion until it is.
 */
internal fun previewMotion(file: SvgFile, choices: SvgImportChoices, fps: Int, reduceMotion: Boolean): PreviewMotion {
    val animation = file.document.animation
    val rate = fps.coerceIn(MIN_FPS, MAX_FPS)
    if (!choices.asAnimation) return PreviewMotion.Still(choices.momentSeconds)
    val duration = choices.durationSeconds(file) ?: animation.durationSeconds
    val start = animation.sampleStartSeconds
    if (reduceMotion) return PreviewMotion.Still(start + duration / 2.0)
    return PreviewMotion.Playing(start, ImportEdits.tickCount(duration, rate), rate)
}

@Composable
private fun SvgPreview(
    document: SvgDocument,
    project: Project,
    backgroundImage: Bitmap?,
    choices: SvgImportChoices,
    motion: PreviewMotion,
    onDrawn: () -> Unit,
) {
    val description = stringResource(R.string.svg_import_preview)
    val onWhite = !choices.asAnimation && choices.place == SvgImportPlace.BACKGROUND
    val image = rememberSvgPreview(document, project, backgroundImage, choices.fit, onWhite, motion, onDrawn)
    Box(
        Modifier
            .fillMaxWidth()
            .height(PREVIEW_HEIGHT.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .padding(6.dp)
                .aspectRatio(project.width.toFloat() / project.height, matchHeightConstraintsFirst = true)
                .border(1.dp, MaterialTheme.colorScheme.outline)
                .testTag("svg_import_preview"),
        ) {
            if (image != null) {
                Image(
                    image,
                    contentDescription = description,
                    contentScale = ContentScale.Fit,
                    // A small canvas really is that blocky, and a smoothed preview would hide it.
                    filterQuality = if (min(project.width, project.height) < PIXEL_ART_EDGE) FilterQuality.None else FilterQuality.Low,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Draws the preview off the main thread. A picture that takes long to draw only makes the preview lag, and a change of choices drops whatever
 * was being drawn. A frame is never drawn into the buffer on screen, which is why there are several and they take turns.
 */
@Composable
private fun rememberSvgPreview(
    document: SvgDocument,
    project: Project,
    backgroundImage: Bitmap?,
    fit: SvgFit,
    onWhite: Boolean,
    motion: PreviewMotion,
    onDrawn: () -> Unit,
): ImageBitmap? {
    val latestOnDrawn by rememberUpdatedState(onDrawn)
    val width = project.width
    val height = project.height
    val background = project.background
    val buffers = remember(width, height) { PreviewBuffers(width, height) }
    val image by produceState<ImageBitmap?>(null, document, buffers, background, backgroundImage, fit, onWhite, motion) {
        withContext(Dispatchers.Default) {
            val painter = BackgroundPainter()
            val destination = svgDestination(document.width, document.height, width, height, fit)
            suspend fun draw(seconds: Double): ImageBitmap? {
                val buffer = buffers.next() ?: return null
                val canvas = buffer.canvas
                val checkpoint = canvas.save()
                try {
                    buffer.bitmap.eraseColor(Color.TRANSPARENT)
                    canvas.scale(buffers.scale, buffers.scale)
                    if (onWhite) {
                        // How a picture used as the background really looks, whatever is behind the layers now.
                        canvas.drawColor(Color.WHITE)
                    } else {
                        painter.draw(canvas, background, width, height, backgroundImage, forEditor = true)
                    }
                    document.renderCancellable(canvas, seconds, destination)
                } finally {
                    canvas.restoreToCount(checkpoint)
                }
                latestOnDrawn()
                return buffer.image
            }
            try {
                when (motion) {
                    is PreviewMotion.Still -> {
                        draw(motion.seconds)?.let { value = it }
                    }

                    is PreviewMotion.Playing -> {
                        val started = System.nanoTime()
                        var shown = -1
                        while (isActive) {
                            val tick = motion.tickAt(System.nanoTime() - started)
                            if (tick != shown) {
                                draw(motion.secondsAt(tick))?.let { value = it }
                                shown = tick
                            }
                            delay(motion.millisToNextTick(System.nanoTime() - started))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: OutOfMemoryError) {
                // A preview is a nicety. The sheet still works without one, and the import has its own way of saying if memory is short.
            } catch (_: RuntimeException) {
                // Same as above. A failed preview must never take the app down, whatever the file holds.
            }
        }
    }
    return image
}

/** Reused pictures for the preview, no bigger than the canvas, since a preview sharper than the result would flatter it. */
private class PreviewBuffers(width: Int, height: Int) {
    val scale: Float = min(1f, PREVIEW_EDGE_PX.toFloat() / max(width, height))
    private val pixelWidth = (width * scale).roundToInt().coerceAtLeast(1)
    private val pixelHeight = (height * scale).roundToInt().coerceAtLeast(1)
    private val turn = AtomicInteger()
    private val buffers = arrayOfNulls<Buffer>(PREVIEW_BUFFERS)

    fun next(): Buffer? {
        val index = turn.getAndIncrement().mod(PREVIEW_BUFFERS)
        val existing = buffers[index]
        if (existing != null) return existing
        val bitmap = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
        return Buffer(bitmap, Canvas(bitmap), bitmap.asImageBitmap()).also { buffers[index] = it }
    }

    class Buffer(val bitmap: Bitmap, val canvas: Canvas, val image: ImageBitmap)
}

private const val PREVIEW_HEIGHT = 150
private const val PREVIEW_EDGE_PX = 480
private const val PREVIEW_BUFFERS = 3
private const val PIXEL_ART_EDGE = 256
private const val NANOS_PER_SECOND = 1e9
private const val NANOS_PER_MILLI = 1e6
private const val GLYPH_SIZE = 32
private const val GLYPH_LINE = 1.5f
private const val GLYPH_CANVAS_WIDTH = 0.94f
private const val GLYPH_CANVAS_HEIGHT = 0.62f
private const val GLYPH_ORIGINAL_SIZE = 0.5f
private const val GLYPH_SHEET_SIZE = 0.68f
private const val GLYPH_INNER_SIZE = 0.5f
private const val GLYPH_STRIP_CELLS = 3
private const val OVERFLOW_ALPHA = 0.3f
private const val MAX_DURATION_CHARS = 10
private const val BYTES_PER_PIXEL = 4L
private const val LIKELY_COMPRESSION = 8L
private const val LARGE_FRAME_COUNT = 1000
private const val LARGE_HEAPS = 8L
