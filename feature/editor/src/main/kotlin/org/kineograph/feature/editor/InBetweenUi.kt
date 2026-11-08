package org.kineograph.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kineograph.engine.vector.InBetweenMaker
import org.kineograph.engine.vector.InBetweenOutcome
import org.kineograph.engine.vector.InBetweenPlan
import org.kineograph.engine.vector.InBetweenResult
import org.kineograph.engine.vector.InBetweenSketch
import org.kineograph.engine.vector.InBetweenSpacing
import org.kineograph.engine.vector.InBetweens
import org.kineograph.ui.KgIconButton
import org.kineograph.ui.KgIcons
import org.kineograph.ui.VisualDropdown
import org.kineograph.ui.VisualOption

internal sealed interface InBetweenState {
    data object Idle : InBetweenState

    /** Drawing both frames again from their strokes, to see whether in-betweens can be made. */
    data class Checking(val frameId: String, val frameNumber: Int) : InBetweenState

    /**
     * Choosing how many frames to add after frame [frameNumber], and how to space them. [sketch] shows them before they are made, and is
     * null when none can be. [problem] is why none can be, or why the last try made nothing.
     */
    data class Asking(
        val frameId: String,
        val frameNumber: Int,
        val count: Int = 1,
        val spacing: InBetweenSpacing = InBetweenSpacing.EVEN,
        val sketch: InBetweenSketch? = null,
        val problem: InBetweenResult? = null,
    ) : InBetweenState

    data class Running(val frameId: String, val frameNumber: Int, val count: Int, val done: Int = 0) : InBetweenState
}

/**
 * Asks for and makes in-betweens after a frame, on the layer that is active when the dialog opens.
 *
 * @param holdFiles and [releaseFiles] keep the sweep off the drawings while they are written, as nothing refers to them until the end.
 */
internal class InBetweenController(
    private val scope: CoroutineScope,
    private val maker: InBetweenMaker,
    private val activeLayer: () -> String,
    private val makeRoom: suspend () -> Unit,
    private val holdFiles: () -> Unit,
    private val releaseFiles: () -> Unit,
) {
    private val _state = MutableStateFlow<InBetweenState>(InBetweenState.Idle)
    val state: StateFlow<InBetweenState> get() = _state
    private var job: Job? = null
    private var plan: InBetweenPlan? = null

    fun open(frameId: String, frameNumber: Int) {
        if (_state.value is InBetweenState.Running) return
        job?.cancel()
        plan = null
        _state.value = InBetweenState.Checking(frameId, frameNumber)
        job =
            scope.launch {
                val checked = maker.prepare(activeLayer(), frameId)
                plan = checked
                _state.value = InBetweenState.Asking(frameId, frameNumber, sketch = checked.sketch, problem = checked.problem)
            }
    }

    fun change(count: Int) {
        _state.update {
            if (it is InBetweenState.Asking &&
                it.sketch != null
            ) {
                it.copy(count = count.coerceAtLeast(1), problem = null)
            } else {
                it
            }
        }
    }

    fun space(spacing: InBetweenSpacing) {
        _state.update { if (it is InBetweenState.Asking && it.sketch != null) it.copy(spacing = spacing, problem = null) else it }
    }

    fun dismiss() {
        val current = _state.value
        if (current !is InBetweenState.Asking && current !is InBetweenState.Checking) return
        job?.cancel()
        plan = null
        _state.value = InBetweenState.Idle
    }

    fun cancel() {
        if (_state.value is InBetweenState.Running) job?.cancel()
    }

    fun start() {
        val asking = _state.value as? InBetweenState.Asking ?: return
        val ready = plan?.takeIf { it.problem == null } ?: return
        _state.value = InBetweenState.Running(asking.frameId, asking.frameNumber, asking.count)
        job =
            scope.launch {
                try {
                    makeRoom()
                    holdFiles()
                    val result =
                        try {
                            maker.make(ready, asking.count, asking.spacing) { done, _ ->
                                _state.update { if (it is InBetweenState.Running) it.copy(done = done) else it }
                            }
                        } finally {
                            releaseFiles()
                        }
                    if (result.outcome == InBetweenOutcome.DONE) {
                        plan = null
                        _state.value = InBetweenState.Idle
                    } else {
                        _state.value = asking.copy(problem = result)
                    }
                } catch (e: CancellationException) {
                    _state.value = asking.copy(problem = null)
                    throw e
                }
            }
    }
}

@Composable
internal fun InBetweenDialog(controller: InBetweenController, state: InBetweenState) {
    when (state) {
        InBetweenState.Idle -> Unit
        is InBetweenState.Checking -> InBetweenCheckingDialog(controller::dismiss)
        is InBetweenState.Asking -> InBetweenAskDialog(state, controller::change, controller::space, controller::start, controller::dismiss)
        is InBetweenState.Running -> InBetweenRunningDialog(state, controller::cancel)
    }
}

@Composable
private fun InBetweenCheckingDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.in_betweens_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("in_betweens_checking"))
                Text(stringResource(R.string.in_betweens_checking), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
internal fun InBetweenAskDialog(
    state: InBetweenState.Asking,
    onCount: (Int) -> Unit,
    onSpacing: (InBetweenSpacing) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sketch = state.sketch
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.in_betweens_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (sketch != null) {
                    Text(stringResource(R.string.in_betweens_count, state.frameNumber, state.frameNumber + 1))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KgIconButton(
                            KgIcons.Minus,
                            stringResource(R.string.in_betweens_fewer),
                            { onCount(state.count - 1) },
                            modifier = Modifier.testTag("in_betweens_fewer"),
                            enabled = state.count > 1,
                        )
                        Text(
                            state.count.toString(),
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(min = 48.dp).testTag("in_betweens_count"),
                        )
                        KgIconButton(
                            KgIcons.Add,
                            stringResource(R.string.in_betweens_more),
                            { onCount(state.count + 1) },
                            modifier = Modifier.testTag("in_betweens_more"),
                        )
                    }
                    SpacingDropdown(state.spacing, state.count, onSpacing)
                    InBetweenPreview(sketch, state.count, state.spacing, state.frameNumber)
                    Text(stringResource(R.string.in_betweens_hint, state.frameNumber), style = MaterialTheme.typography.bodyMedium)
                }
                state.problem?.let { problem ->
                    Text(
                        problemText(problem),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("in_betweens_problem").semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            if (sketch != null) {
                TextButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp).testTag("in_betweens_add")) {
                    Text(stringResource(R.string.in_betweens_add))
                }
            } else {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("in_betweens_ok")) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        },
        dismissButton = {
            if (sketch != null) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}

@Composable
private fun SpacingDropdown(spacing: InBetweenSpacing, count: Int, onSpacing: (InBetweenSpacing) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    VisualDropdown(
        label = stringResource(R.string.in_betweens_spacing),
        valueText = spacingName(spacing),
        expanded = expanded,
        onExpandedChange = { expanded = it },
        tag = "in_betweens_spacing",
        leading = { SpacingChart(spacing, count) },
    ) {
        for (option in InBetweenSpacing.entries) {
            VisualOption(
                title = spacingName(option),
                hint = spacingHint(option),
                selected = option == spacing,
                tag = "in_betweens_spacing_${option.name.lowercase()}",
                preview = { SpacingChart(option, count) },
                onClick = {
                    onSpacing(option)
                    expanded = false
                },
            )
        }
    }
}

/** A line from the first drawing to the second with a mark where each in-between falls, as an animator's spacing chart shows it. */
@Composable
private fun SpacingChart(spacing: InBetweenSpacing, count: Int) {
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    val mark = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(width = 56.dp, height = 24.dp)) {
        val y = size.height / 2
        val key = size.height * KEY_MARK
        val tick = size.height * TICK_MARK
        val width = 1.5.dp.toPx()
        drawLine(line, Offset(0f, y), Offset(size.width, y), width)
        drawLine(line, Offset(width / 2, y - key), Offset(width / 2, y + key), width * 2)
        drawLine(line, Offset(size.width - width / 2, y - key), Offset(size.width - width / 2, y + key), width * 2)
        for (k in 1..count.coerceAtMost(MAX_CHART_MARKS)) {
            val x = InBetweens.position(k, count.coerceAtMost(MAX_CHART_MARKS), spacing) * size.width
            drawLine(mark, Offset(x, y - tick), Offset(x, y + tick), width)
        }
    }
}

/** The strokes' paths in both drawings and in every in-between, one over the other, as the onion skin would show them once made. */
@Composable
private fun InBetweenPreview(sketch: InBetweenSketch, count: Int, spacing: InBetweenSpacing, frameNumber: Int) {
    val area = sketch.area
    val wide = (area[2] - area[0]).coerceAtLeast(1f)
    val tall = (area[3] - area[1]).coerceAtLeast(1f)
    val key = MaterialTheme.colorScheme.onSurfaceVariant
    val between = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.in_betweens_preview, frameNumber, frameNumber + 1)
    val line = remember(sketch) { FloatArray(sketch.points * 2) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio((wide / tall).coerceIn(MIN_PREVIEW_RATIO, MAX_PREVIEW_RATIO))
            .semantics { contentDescription = description }
            .testTag("in_betweens_preview"),
    ) {
        val scale = minOf(size.width / wide, size.height / tall)
        val left = (size.width - wide * scale) / 2 - area[0] * scale
        val top = (size.height - tall * scale) / 2 - area[1] * scale
        fun frame(t: Float, color: Color) {
            for (stroke in 0 until sketch.strokeCount) {
                sketch.line(stroke, t, line)
                drawSketchLine(line, sketch.points, left, top, scale, (sketch.size(stroke, t) * scale).coerceAtLeast(1.dp.toPx()), color)
            }
        }
        frame(0f, key)
        frame(1f, key)
        for (k in 1..count) frame(InBetweens.position(k, count, spacing), between.copy(alpha = IN_BETWEEN_ALPHA))
    }
}

private fun DrawScope.drawSketchLine(points: FloatArray, n: Int, left: Float, top: Float, scale: Float, width: Float, color: Color) {
    val path = Path()
    path.moveTo(left + points[0] * scale, top + points[1] * scale)
    for (i in 0 until n) path.lineTo(left + points[i * 2] * scale, top + points[i * 2 + 1] * scale)
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Composable
private fun spacingName(spacing: InBetweenSpacing): String =
    stringResource(
        when (spacing) {
            InBetweenSpacing.EVEN -> R.string.in_betweens_spacing_even
            InBetweenSpacing.SLOW_START -> R.string.in_betweens_spacing_slow_start
            InBetweenSpacing.SLOW_END -> R.string.in_betweens_spacing_slow_end
            InBetweenSpacing.SLOW_BOTH -> R.string.in_betweens_spacing_slow_both
        },
    )

@Composable
private fun spacingHint(spacing: InBetweenSpacing): String =
    stringResource(
        when (spacing) {
            InBetweenSpacing.EVEN -> R.string.in_betweens_spacing_even_hint
            InBetweenSpacing.SLOW_START -> R.string.in_betweens_spacing_slow_start_hint
            InBetweenSpacing.SLOW_END -> R.string.in_betweens_spacing_slow_end_hint
            InBetweenSpacing.SLOW_BOTH -> R.string.in_betweens_spacing_slow_both_hint
        },
    )

@Composable
private fun InBetweenRunningDialog(state: InBetweenState.Running, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.in_betweens_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(
                    progress = { state.done.toFloat() / state.count.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().testTag("in_betweens_progress"),
                )
                Text(
                    stringResource(R.string.in_betweens_running, (state.done + 1).coerceAtMost(state.count), state.count),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("in_betweens_cancel")) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun problemText(problem: InBetweenResult): String =
    when (problem.outcome) {
        InBetweenOutcome.DONE -> ""
        InBetweenOutcome.NO_NEXT_FRAME -> stringResource(R.string.in_betweens_no_next_frame, problem.frame)
        InBetweenOutcome.NOT_RECORDED -> stringResource(R.string.in_betweens_not_recorded, problem.frame)
        InBetweenOutcome.DRAWN_OTHERWISE -> stringResource(R.string.in_betweens_drawn_otherwise, problem.frame)
        InBetweenOutcome.NOTHING_DRAWN -> stringResource(R.string.in_betweens_nothing_drawn)
        InBetweenOutcome.STROKE_COUNTS_DIFFER -> stringResource(R.string.in_betweens_stroke_counts, problem.first, problem.second)
        InBetweenOutcome.BRUSH_DIFFERS -> stringResource(R.string.in_betweens_brush_differs, problem.first)
        InBetweenOutcome.OUT_OF_MEMORY -> stringResource(R.string.in_betweens_out_of_memory)
        InBetweenOutcome.OUT_OF_STORAGE -> stringResource(R.string.in_betweens_out_of_storage)
        InBetweenOutcome.FAILED -> stringResource(R.string.in_betweens_failed)
    }

private const val KEY_MARK = 0.42f
private const val TICK_MARK = 0.26f
private const val MAX_CHART_MARKS = 12
private const val IN_BETWEEN_ALPHA = 0.7f
private const val MIN_PREVIEW_RATIO = 1.25f
private const val MAX_PREVIEW_RATIO = 3f
