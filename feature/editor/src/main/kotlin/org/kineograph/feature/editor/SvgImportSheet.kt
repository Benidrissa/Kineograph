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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.kineograph.engine.compositor.BackgroundPainter
import org.kineograph.media.svg.SvgDocument
import org.kineograph.media.svg.SvgWarning
import org.kineograph.media.svgimport.SvgFit
import org.kineograph.media.svgimport.SvgImportOutcome
import org.kineograph.media.svgimport.SvgImportPlace
import org.kineograph.media.svgimport.SvgSampling
import org.kineograph.media.svgimport.maxImportFrames
import org.kineograph.media.svgimport.suggestedSeconds
import org.kineograph.media.svgimport.svgDestination
import org.kineograph.model.ImportEdits
import org.kineograph.model.MAX_FPS
import org.kineograph.model.MIN_FPS
import org.kineograph.model.Project
import org.kineograph.ui.KgIcons
import org.kineograph.ui.VisualDropdown
import org.kineograph.ui.VisualOption
import org.kineograph.ui.rememberReduceMotion
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
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
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val scope = rememberCoroutineScope()
            ModalBottomSheet(onDismissRequest = controller::dismiss, sheetState = sheetState) {
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
                    // Slides away first, as it does when swiped down, instead of being gone from one frame to the next.
                    onDismiss = {
                        scope.launch {
                            try {
                                sheetState.hide()
                            } finally {
                                controller.dismiss()
                            }
                        }
                    },
                )
            }
        }

        is SvgImportState.Running -> {
            // One picture has no count worth giving, and a bar at the end of it would say it is done while the file may still be saving.
            val counts = state.total > 1
            SvgBusyDialog(
                title = stringResource(R.string.svg_import_running),
                detail = if (counts && state.done > 0) stringResource(R.string.svg_import_progress, state.done, state.total) else null,
                progress = if (counts) state.done.toFloat() / state.total else null,
                onCancel = controller::cancel,
            )
        }
    }
}

/**
 * Progress that cannot be dismissed by accident, with a Cancel that always works. Without [progress], it shows only that something is happening.
 *
 * TalkBack hears how far it has got each time another tenth is done. Every frame would be all it ever said.
 */
@Composable
internal fun SvgBusyDialog(title: String, detail: String?, progress: Float?, onCancel: () -> Unit) {
    val tenths = ((progress ?: 0f) * SPOKEN_STEPS).toInt()
    val spoken = remember(tenths, detail == null) { detail }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val barModifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = title }
                        .testTag("svg_import_progress")
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = barModifier)
                } else {
                    LinearProgressIndicator(modifier = barModifier)
                }
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier =
                            Modifier.testTag("svg_import_progress_text").clearAndSetSemantics {
                                spoken?.let { contentDescription = it }
                                liveRegion = LiveRegionMode.Polite
                            },
                    )
                }
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
 * The choices for one import. Everything scrolls except the buttons and the reason they did not work or will not, so that both stay on screen however
 * long the list of warnings gets. The parent has to give this a bounded height, as a bottom sheet does.
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
    val tooLong = choices.isTooLong(file, project.fps)
    val frameId = project.frames.getOrNull(frameNumber - 1)?.id ?: project.frames.first().id
    val motion =
        remember(
            file,
            choices.asAnimation,
            choices.durationText,
            choices.withIntro,
            choices.momentSeconds,
            project.fps,
            project.frames,
            frameId,
            reduceMotion,
        ) {
            previewMotion(file, choices, project, frameId, reduceMotion)
        }
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
            SvgPreview(file.document, project, backgroundImage, choices, motion) {
                warnings = file.document.warnings
            }
            if (file.canAnimate) ImportAsDropdown(choices.asAnimation) { onChange(choices.copy(asAnimation = it)) }
            if (choices.asAnimation) {
                if (file.document.animation.hasIntro) StartFromDropdown(choices.withIntro) { onChange(choices.copy(withIntro = it)) }
                DurationChoice(file, project, frameId, choices, tooLong, onChange)
                AnimationPlacement(frameNumber)
            } else {
                if (file.canAnimate) MomentChoice(file, choices, onChange)
                WhereDropdown(choices.place) { onChange(choices.copy(place = it)) }
            }
            FitDropdown(choices.fit) { onChange(choices.copy(fit = it)) }
            SvgWarnings(warnings)
        }
        // Next to the buttons and out of the scrolling, because the sheet comes back from a failed import scrolled to the top.
        val blocker =
            when {
                refused -> R.string.message_layer_locked
                tooLong -> R.string.svg_import_too_many_frames
                problem != null -> problem.textRes()
                else -> null
            }
        if (blocker != null) ProblemText(stringResource(blocker), Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Free to grow, as words can run to two lines at large text sizes, and both stay the same height as each other.
            OutlinedButton(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .heightIn(min = 48.dp)
                        .testTag("svg_import_dismiss"),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(
                onClick = onImport,
                // What the sheet already says will be refused should not need a tap to find out.
                enabled = choices.toRequest(file) != null && !refused && !tooLong,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .heightIn(min = 48.dp)
                        .testTag("svg_import_button"),
            ) {
                Text(stringResource(R.string.svg_import_start))
            }
        }
    }
}

@Composable
private fun ProblemText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.testTag("svg_import_problem").semantics { liveRegion = LiveRegionMode.Polite },
    )
}

private fun SvgImportOutcome.textRes(): Int =
    when (this) {
        SvgImportOutcome.LAYER_NOT_DRAWABLE -> R.string.message_layer_locked
        SvgImportOutcome.NOTHING_TO_IMPORT -> R.string.svg_import_nothing
        SvgImportOutcome.TOO_MANY_FRAMES -> R.string.svg_import_too_many_frames
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
                !file.canAnimate -> {
                    stringResource(R.string.svg_import_static)
                }

                animation.hasIntro -> {
                    stringResource(R.string.svg_import_animated_intro, formatSeconds(animation.loopStartSeconds, locale), seconds)
                }

                animation.loopsForever && !animation.repeatsExactly -> {
                    stringResource(R.string.svg_import_animated_uneven, seconds)
                }

                animation.loopsForever -> {
                    stringResource(R.string.svg_import_animated_loops, seconds)
                }

                else -> {
                    stringResource(R.string.svg_import_animated_once, seconds)
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("svg_import_motion"),
        )
    }
}

/**
 * Seconds, with as many decimals as the number has, and at least the one that makes it read as a measurement, so `0.125` shows as that and does not
 * come back looking like some other number than was typed. Only a number that never ends gets rounded, and to two places, unless that leaves nothing.
 */
internal fun formatSeconds(seconds: Double, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).run {
        isGroupingUsed = false
        roundingMode = RoundingMode.HALF_UP
        minimumFractionDigits = 1
        maximumFractionDigits = decimalsFor(seconds)
        format(seconds)
    }

private fun decimalsFor(seconds: Double): Int {
    var scale = 10.0
    for (decimals in 1..MAX_TYPED_DECIMALS) {
        if (abs(seconds * scale - Math.rint(seconds * scale)) < EXACT_ENOUGH * scale) return decimals
        scale *= 10.0
    }
    var decimals = NEVER_ENDING_DECIMALS
    scale = 100.0
    while (decimals < MAX_TYPED_DECIMALS && seconds * scale < 1.0) {
        decimals++
        scale *= 10.0
    }
    return decimals
}

/** How many frames a choice really comes to. A slot held for several ticks is still one picture, on one frame of the timeline. */
private fun framesFor(sampling: SvgSampling, project: Project, frameId: String): Int =
    ImportEdits.ticksToSample(project, frameId, sampling.tickCount).size

@Composable
private fun ColumnScope.DurationChoice(
    file: SvgFile,
    project: Project,
    frameId: String,
    choices: SvgImportChoices,
    tooLong: Boolean,
    onChange: (SvgImportChoices) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val suggested = file.document.animation.suggestedSeconds(choices.withIntro)
    val duration = choices.durationSeconds(file)
    // The field keeps its own text, because choices come back a moment later, and a text field that lags behind the keyboard loses letters.
    var text by remember(suggested) { mutableStateOf(choices.durationText ?: formatSeconds(suggested, locale)) }
    val spokenUnit = stringResource(R.string.svg_import_seconds_spoken)
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed.filter { it.isDigit() || it == '.' || it == ',' }.take(MAX_DURATION_CHARS)
            onChange(choices.copy(durationText = text))
        },
        label = { Text(stringResource(R.string.svg_import_duration)) },
        suffix = {
            // On its own, a screen reader only has a letter to read out.
            Text(stringResource(R.string.svg_import_seconds_unit), Modifier.clearAndSetSemantics { contentDescription = spokenUnit })
        },
        singleLine = true,
        isError = duration == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag("svg_import_duration"),
    )
    // On a line of its own, because long words at large text sizes would otherwise leave nothing of the field beside it.
    if (choices.durationText != null) {
        TextButton(
            onClick = {
                text = formatSeconds(suggested, locale)
                onChange(choices.copy(durationText = null))
            },
            modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("svg_import_duration_reset"),
        ) {
            Text(stringResource(R.string.svg_import_duration_reset, formatSeconds(suggested, locale)))
        }
    }
    if (duration == null) {
        ProblemText(stringResource(R.string.svg_import_duration_invalid))
    } else if (!tooLong) {
        // Past the limit there is no sentence to make, and what it would say could be off by billions.
        val sampling = choices.sampling(file, project.fps) ?: return
        val frames = framesFor(sampling, project, frameId)
        Text(
            pluralStringResource(R.plurals.svg_import_frames, frames, formatSeconds(duration, locale), project.fps, frames),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("svg_import_frames").semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (isLargeImport(frames, project.width, project.height)) {
            // A note like the file's own, and not an error: Import is still there to be pressed.
            InfoNote(
                stringResource(
                    R.string.svg_import_large_warning,
                    Formatter.formatShortFileSize(LocalContext.current, likelyStorageBytes(frames, project.width, project.height)),
                ),
                Modifier.testTag("svg_import_large"),
            )
        }
    }
}

@Composable
private fun InfoNote(text: String, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(KgIcons.Info, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
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

/** For a file that plays something once and then loops: whether the first part is wanted, or only a loop that repeats smoothly. */
@Composable
private fun StartFromDropdown(withIntro: Boolean, onChange: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val choose: (Boolean) -> Unit = {
        expanded = false
        onChange(it)
    }
    VisualDropdown(
        label = stringResource(R.string.svg_import_start_from),
        valueText = stringResource(if (withIntro) R.string.svg_import_start_beginning else R.string.svg_import_start_loop),
        expanded = expanded,
        onExpandedChange = { expanded = it },
        tag = "svg_import_start_from",
        leading = { StartFromGlyph(withIntro) },
    ) {
        VisualOption(
            title = stringResource(R.string.svg_import_start_beginning),
            hint = stringResource(R.string.svg_import_start_beginning_hint),
            selected = withIntro,
            tag = "svg_import_start_beginning",
            preview = { StartFromGlyph(true) },
            onClick = { choose(true) },
        )
        VisualOption(
            title = stringResource(R.string.svg_import_start_loop),
            hint = stringResource(R.string.svg_import_start_loop_hint),
            selected = !withIntro,
            tag = "svg_import_start_loop",
            preview = { StartFromGlyph(false) },
            onClick = { choose(false) },
        )
    }
}

/** A line for the intro, then a ring for the loop, with what gets imported drawn solid. */
@Composable
private fun StartFromGlyph(withIntro: Boolean) {
    val colors = MaterialTheme.colorScheme
    val solid = colors.primary
    val faint = colors.outline
    ComposeCanvas(Modifier.size(GLYPH_SIZE.dp)) {
        val line = Stroke(GLYPH_THICK_LINE.dp.toPx())
        val middle = size.height / 2f
        val ringRadius = size.width * GLYPH_RING
        drawLine(
            if (withIntro) solid else faint,
            Offset(0f, middle),
            Offset(size.width - ringRadius * 2f, middle),
            strokeWidth = GLYPH_THICK_LINE.dp.toPx(),
        )
        drawCircle(solid, ringRadius, Offset(size.width - ringRadius, middle), style = line)
    }
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
        Box(Modifier.testTag("svg_import_warning_${warning.name.lowercase()}")) {
            InfoNote(stringResource(warning.textRes()))
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

    /**
     * The same moments an import would sample, at the rate the project will play them.
     *
     * @property seen the ticks the import would really make a picture for, when that is known. A slot held for several ticks shows the one picture.
     */
    class Playing(val sampling: SvgSampling, val seen: IntArray?, val fps: Int) : PreviewMotion {
        val tickCount: Int get() = sampling.tickCount

        fun tickAt(elapsedNanos: Long): Int = ((elapsedNanos.toDouble() * fps / NANOS_PER_SECOND).toLong() % tickCount).toInt()

        /** The tick whose picture is on screen at [tick], which is itself unless a held slot is covering it. */
        fun shownTick(tick: Int): Int {
            val ticks = seen ?: return tick
            val found = ticks.binarySearch(tick)
            return if (found >= 0) ticks[found] else ticks[(-found - 2).coerceAtLeast(0)]
        }

        fun secondsAt(tick: Int): Double = sampling.secondsAt(tick)

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
internal fun previewMotion(
    file: SvgFile,
    choices: SvgImportChoices,
    project: Project,
    frameId: String,
    reduceMotion: Boolean,
): PreviewMotion {
    val animation = file.document.animation
    val rate = project.fps.coerceIn(MIN_FPS, MAX_FPS)
    if (!choices.asAnimation) return PreviewMotion.Still(choices.momentSeconds)
    val duration = choices.durationSeconds(file) ?: animation.suggestedSeconds(choices.withIntro)
    val sampling = SvgSampling.of(animation, duration, rate, choices.withIntro)
    if (reduceMotion) return PreviewMotion.Still(sampling.secondsAt(sampling.tickCount / 2))
    // Listing them for more than a device can hold would be the one thing here that could run out of memory.
    val seen = if (sampling.tickCount <= maxImportFrames()) ImportEdits.ticksToSample(project, frameId, sampling.tickCount) else null
    return PreviewMotion.Playing(sampling, seen, rate)
}

@Composable
internal fun SvgPreview(
    document: SvgDocument,
    project: Project,
    backgroundImage: Bitmap?,
    choices: SvgImportChoices,
    motion: PreviewMotion,
    onDrawn: () -> Unit,
) {
    val onWhite = !choices.asAnimation && choices.place == SvgImportPlace.BACKGROUND
    val preview = rememberSvgPreview(document, project, backgroundImage, choices.fit, onWhite, motion, onDrawn)
    SvgPreviewBox(project, preview.image, preview.failed)
}

/** Empty for as long as nothing is known, with something to say about that for both eyes and ears. */
@Composable
internal fun SvgPreviewBox(project: Project, image: ImageBitmap?, failed: Boolean) {
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
            contentAlignment = Alignment.Center,
        ) {
            when {
                image != null -> {
                    Image(
                        image,
                        contentDescription = stringResource(R.string.svg_import_preview),
                        contentScale = ContentScale.Fit,
                        // A small canvas really is that blocky, and a smoothed preview would hide it.
                        filterQuality = if (min(project.width, project.height) < PIXEL_ART_EDGE) FilterQuality.None else FilterQuality.Low,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                failed -> {
                    Text(
                        stringResource(R.string.svg_import_preview_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp).testTag("svg_import_preview_unavailable"),
                    )
                }

                else -> {
                    val spoken = stringResource(R.string.svg_import_preview_loading)
                    CircularProgressIndicator(
                        Modifier
                            .size(24.dp)
                            .semantics { contentDescription = spoken }
                            .testTag("svg_import_preview_loading"),
                    )
                }
            }
        }
    }
}

/** What the preview has to show so far. It only counts as failed for as long as it has had nothing to show at all. */
private class PreviewPicture(val image: ImageBitmap?, val failed: Boolean)

/**
 * Draws the preview off the main thread. A picture that takes long to draw only makes the preview lag, and a change of choices drops whatever
 * was being drawn. See [PreviewBuffers] for what keeps that from showing. It stops when the app is not on screen, where it would only use up the battery.
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
): PreviewPicture {
    val latestOnDrawn by rememberUpdatedState(onDrawn)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val width = project.width
    val height = project.height
    val background = project.background
    val buffers = remember(width, height) { PreviewBuffers(width, height) }
    val picture by produceState(
        PreviewPicture(null, false),
        document,
        buffers,
        background,
        backgroundImage,
        fit,
        onWhite,
        motion,
        lifecycle,
    ) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            withContext(Dispatchers.Default) {
                val painter = BackgroundPainter()
                val destination = svgDestination(document.width, document.height, width, height, fit)

                // One at a time, because a draw that was just cancelled can take a moment to notice, and it must not share a canvas with the next one.
                suspend fun draw(seconds: Double) {
                    buffers.turn.withLock {
                        val buffer = buffers.next()
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
                        currentCoroutineContext().ensureActive()
                        latestOnDrawn()
                        value = PreviewPicture(buffer.image, false)
                        buffers.nowShowing(buffer)
                    }
                }
                try {
                    when (motion) {
                        is PreviewMotion.Still -> {
                            draw(motion.seconds)
                        }

                        is PreviewMotion.Playing -> {
                            val started = System.nanoTime()
                            var shown = -1
                            while (isActive) {
                                val tick = motion.shownTick(motion.tickAt(System.nanoTime() - started))
                                if (tick != shown) {
                                    draw(motion.secondsAt(tick))
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
                    value = PreviewPicture(value.image, true)
                } catch (_: RuntimeException) {
                    // Same as above. A failed preview must never take the app down, whatever the file holds.
                    value = PreviewPicture(value.image, true)
                }
            }
        }
    }
    return picture
}

/**
 * Reused pictures for the preview, no bigger than the canvas, since a preview sharper than the result would flatter it.
 *
 * What is being drawn must never be what is on screen, or a slow picture would be seen half drawn each time. So what is showing stays out of it, and
 * so does what just stopped showing, because the screen can still be reading that for a moment. A draw that gets cancelled before it shows leaves
 * its buffer free, which is then simply the next one to be used.
 */
internal class PreviewBuffers(width: Int, height: Int) {
    val scale: Float = min(1f, PREVIEW_EDGE_PX.toFloat() / max(width, height))
    val turn: Mutex = Mutex()
    private val pixelWidth = (width * scale).roundToInt().coerceAtLeast(1)
    private val pixelHeight = (height * scale).roundToInt().coerceAtLeast(1)
    private val buffers = arrayOfNulls<Buffer>(PREVIEW_BUFFERS)
    private var showing: Buffer? = null
    private var showed: Buffer? = null

    fun next(): Buffer {
        for (index in buffers.indices) {
            val existing = buffers[index] ?: return create(index)
            if (existing !== showing && existing !== showed) return existing
        }
        error("Three buffers cannot all be taken by two")
    }

    private fun create(index: Int): Buffer {
        val bitmap = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
        return Buffer(bitmap, Canvas(bitmap), bitmap.asImageBitmap()).also { buffers[index] = it }
    }

    fun nowShowing(buffer: Buffer) {
        showed = showing
        showing = buffer
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
private const val GLYPH_THICK_LINE = 2.5f
private const val GLYPH_RING = 0.22f
private const val MAX_DURATION_CHARS = 10
private const val SPOKEN_STEPS = 10

/** As many decimals as fit in the duration field, so that whatever is typed there can be shown as it was typed. */
private const val MAX_TYPED_DECIMALS = 8
private const val NEVER_ENDING_DECIMALS = 2
private const val EXACT_ENOUGH = 1e-10
private const val BYTES_PER_PIXEL = 4L
private const val LIKELY_COMPRESSION = 8L
private const val LARGE_FRAME_COUNT = 1000
private const val LARGE_HEAPS = 8L
