package org.kineograph.feature.ai

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.kineograph.ui.KgIconButton
import org.kineograph.ui.KgIcons
import org.kineograph.ui.VisualDropdown
import org.kineograph.ui.VisualOption

/** What the AI screen can ask for. */
public class AiActions(
    public val onImport: () -> Unit,
    public val onCancelImport: () -> Unit,
    public val onDelete: () -> Unit,
    public val onBackend: (AiBackend) -> Unit,
    public val onAsk: (String) -> Unit,
    public val onStop: () -> Unit,
)

/** The model on this phone: importing it, and trying it with a prompt. */
@Composable
public fun AiScreen(ai: LocalAi, onBack: () -> Unit) {
    val state by ai.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) ai.import(ModelSource.of(resolver, uri))
        }
    AiContent(
        state,
        onBack,
        AiActions(
            // A .litertlm file has no type of its own that providers agree on, so any file can be picked, and the import checks it.
            onImport = { picker.launch(arrayOf("*/*")) },
            onCancelImport = ai::cancelImport,
            onDelete = ai::delete,
            onBackend = ai::chooseBackend,
            onAsk = ai::ask,
            onStop = ai::stop,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun AiContent(state: AiState, onBack: () -> Unit, actions: AiActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_title)) },
                navigationIcon = { KgIconButton(KgIcons.Back, stringResource(R.string.ai_back), onBack) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.ai_intro), style = MaterialTheme.typography.bodyMedium)
            Heading(stringResource(R.string.ai_section_model))
            ModelSection(state, actions)
            if (state.model != null && state.importing == null) {
                HorizontalDivider()
                Heading(stringResource(R.string.ai_section_try))
                TrySection(state, actions)
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun ModelSection(state: AiState, actions: AiActions) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val importing = state.importing
    val model = state.model
    when {
        importing != null -> {
            val (copied, total) = importing
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { (copied.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().testTag("ai_import_progress"),
                )
                Text(
                    stringResource(R.string.ai_copying, size(copied), size(total)),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("ai_import_progress"))
                Text(stringResource(R.string.ai_copying_unknown, size(copied)))
            }
            OutlinedButton(onClick = actions.onCancelImport, modifier = Modifier.heightIn(min = 48.dp).testTag("ai_cancel_import")) {
                Text(stringResource(R.string.ai_cancel))
            }
        }

        model != null -> {
            Text(model.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("ai_model_name"))
            Text(size(model.bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.onImport, modifier = Modifier.heightIn(min = 48.dp).testTag("ai_replace")) {
                    Text(stringResource(R.string.ai_replace))
                }
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("ai_delete")) {
                    Text(stringResource(R.string.ai_delete))
                }
            }
        }

        else -> {
            Text(stringResource(R.string.ai_no_model))
            Button(onClick = actions.onImport, modifier = Modifier.heightIn(min = 48.dp).testTag("ai_import")) {
                Text(stringResource(R.string.ai_import))
            }
        }
    }
    state.importProblem?.let { problem ->
        Text(
            importProblemText(problem, ::size),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("ai_import_problem").semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (confirmDelete && model != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.ai_delete_title)) },
            text = { Text(stringResource(R.string.ai_delete_text, size(model.bytes))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        actions.onDelete()
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("ai_delete_confirm"),
                ) { Text(stringResource(R.string.ai_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.ai_cancel))
                }
            },
        )
    }
}

@Composable
private fun importProblemText(problem: ImportOutcome, size: (Long) -> String): String =
    when (problem) {
        is ImportOutcome.Done -> ""
        ImportOutcome.NotAModel -> stringResource(R.string.ai_not_a_model)
        is ImportOutcome.NotEnoughSpace -> stringResource(R.string.ai_not_enough_space, size(problem.needed), size(problem.free))
        ImportOutcome.Failed -> stringResource(R.string.ai_import_failed)
    }

@Composable
private fun TrySection(state: AiState, actions: AiActions) {
    val busy = state.phase != AiPhase.IDLE
    BackendDropdown(state.backend, enabled = !busy, onBackend = actions.onBackend)
    val defaultPrompt = stringResource(R.string.ai_prompt_default)
    var prompt by rememberSaveable { mutableStateOf(defaultPrompt) }
    OutlinedTextField(
        value = prompt,
        onValueChange = { prompt = it },
        label = { Text(stringResource(R.string.ai_prompt)) },
        enabled = !busy,
        minLines = 2,
        modifier = Modifier.fillMaxWidth().testTag("ai_prompt"),
    )
    if (busy) {
        OutlinedButton(onClick = actions.onStop, modifier = Modifier.heightIn(min = 48.dp).testTag("ai_stop")) {
            Text(stringResource(R.string.ai_stop))
        }
    } else {
        Button(
            onClick = { actions.onAsk(prompt) },
            enabled = prompt.isNotBlank(),
            modifier = Modifier.heightIn(min = 48.dp).testTag("ai_ask"),
        ) { Text(stringResource(R.string.ai_ask)) }
    }
    when (state.phase) {
        AiPhase.LOADING -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.ai_loading), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }

        AiPhase.REPLYING -> {
            if (state.reply.isEmpty()) Text(stringResource(R.string.ai_replying))
        }

        AiPhase.IDLE -> {
            Unit
        }
    }
    if (state.reply.isNotEmpty()) {
        SelectionContainer {
            Text(state.reply, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("ai_reply"))
        }
    }
    state.timings?.let { timings ->
        Text(
            timingsText(timings),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("ai_timings"),
        )
    }
    state.problem?.let { problem ->
        Text(
            stringResource(
                when (problem) {
                    AiProblem.UNSUPPORTED_PHONE -> R.string.ai_unsupported
                    AiProblem.OUT_OF_MEMORY -> R.string.ai_out_of_memory
                    AiProblem.LOAD_FAILED -> R.string.ai_load_failed
                    AiProblem.REPLY_FAILED -> R.string.ai_reply_failed
                },
            ),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("ai_problem").semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun timingsText(timings: AiTimings): String =
    listOfNotNull(
        timings.loadSeconds?.let { stringResource(R.string.ai_timing_load, it) },
        timings.firstWordsSeconds?.let { stringResource(R.string.ai_timing_first, it) },
        timings.tokensPerSecond?.let { stringResource(R.string.ai_timing_speed, it) },
    ).joinToString(" · ")

@Composable
private fun BackendDropdown(backend: AiBackend, enabled: Boolean, onBackend: (AiBackend) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    VisualDropdown(
        label = stringResource(R.string.ai_backend),
        valueText = backendName(backend),
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
        tag = "ai_backend",
        leading = { BackendBadge(backend) },
    ) {
        for (option in AiBackend.entries) {
            VisualOption(
                title = backendName(option),
                hint =
                    stringResource(
                        when (option) {
                            AiBackend.CPU -> R.string.ai_backend_cpu_hint
                            AiBackend.GPU -> R.string.ai_backend_gpu_hint
                        },
                    ),
                selected = option == backend,
                tag = "ai_backend_${option.name.lowercase()}",
                preview = { BackendBadge(option) },
                onClick = {
                    onBackend(option)
                    expanded = false
                },
            )
        }
    }
}

/** The backend's short name in a small chip outline, the way it is printed on a spec sheet. */
@Composable
private fun BackendBadge(backend: AiBackend) {
    Box(
        Modifier
            .widthIn(min = 40.dp)
            .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(backend.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun backendName(backend: AiBackend): String =
    stringResource(
        when (backend) {
            AiBackend.CPU -> R.string.ai_backend_cpu
            AiBackend.GPU -> R.string.ai_backend_gpu
        },
    )
