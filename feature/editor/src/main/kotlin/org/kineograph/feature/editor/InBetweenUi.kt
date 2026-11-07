package org.kineograph.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
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
import org.kineograph.engine.vector.InBetweenResult
import org.kineograph.ui.KgIconButton
import org.kineograph.ui.KgIcons

internal sealed interface InBetweenState {
    data object Idle : InBetweenState

    /** Choosing how many frames to add after frame [frameNumber]. [problem] is why the last try made nothing. */
    data class Asking(val frameId: String, val frameNumber: Int, val count: Int = 1, val problem: InBetweenResult? = null) : InBetweenState

    data class Running(val frameId: String, val frameNumber: Int, val count: Int, val done: Int = 0) : InBetweenState
}

/**
 * Asks for and makes in-betweens after a frame, on the layer that is active when they are made.
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

    fun open(frameId: String, frameNumber: Int) {
        if (_state.value is InBetweenState.Running) return
        _state.value = InBetweenState.Asking(frameId, frameNumber)
    }

    fun change(count: Int) {
        _state.update { if (it is InBetweenState.Asking) it.copy(count = count.coerceAtLeast(1), problem = null) else it }
    }

    fun dismiss() {
        if (_state.value is InBetweenState.Asking) _state.value = InBetweenState.Idle
    }

    fun cancel() {
        job?.cancel()
    }

    fun start() {
        val asking = _state.value as? InBetweenState.Asking ?: return
        _state.value = InBetweenState.Running(asking.frameId, asking.frameNumber, asking.count)
        job =
            scope.launch {
                try {
                    makeRoom()
                    holdFiles()
                    val result =
                        try {
                            maker.make(activeLayer(), asking.frameId, asking.count) { done, _ ->
                                _state.update { if (it is InBetweenState.Running) it.copy(done = done) else it }
                            }
                        } finally {
                            releaseFiles()
                        }
                    _state.value =
                        if (result.outcome == InBetweenOutcome.DONE) InBetweenState.Idle else asking.copy(problem = result)
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
        is InBetweenState.Asking -> InBetweenAskDialog(state, controller::change, controller::start, controller::dismiss)
        is InBetweenState.Running -> InBetweenRunningDialog(state, controller::cancel)
    }
}

@Composable
internal fun InBetweenAskDialog(state: InBetweenState.Asking, onCount: (Int) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.in_betweens_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                Text(stringResource(R.string.in_betweens_hint, state.frameNumber), style = MaterialTheme.typography.bodyMedium)
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
            TextButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp).testTag("in_betweens_add")) {
                Text(stringResource(R.string.in_betweens_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

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
