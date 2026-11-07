package org.kineograph.feature.editor

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.kineograph.feature.editor.canvas.CanvasListener
import org.kineograph.feature.editor.canvas.KineographCanvasView

/** The editor, or a message if the project could not be opened. */
@Composable
public fun EditorRoute(viewModel: EditorViewModel, wideLayout: Boolean, onBack: () -> Unit) {
    val load by viewModel.load.collectAsStateWithLifecycle()
    when (val state = load) {
        EditorLoadState.Loading -> {
            EditorRootSurface {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        EditorLoadState.NotFound -> {
            OpenProblem(stringResource(R.string.editor_not_found), onBack)
        }

        EditorLoadState.NeedsNewerApp -> {
            OpenProblem(stringResource(R.string.editor_needs_newer_app), onBack)
        }

        is EditorLoadState.Ready -> {
            EditorContent(viewModel, state, wideLayout, onBack)
        }
    }
}

@Composable
private fun OpenProblem(message: String, onBack: () -> Unit) {
    EditorRootSurface {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        }
    }
}

/**
 * The editor's backdrop. A Surface is what tells the text and icons inside which colour to be. With only a coloured
 * background they fall back to black, which cannot be seen in the dark theme.
 */
@Composable
internal fun EditorRootSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface, content = content)
}

/** Which sheet is showing. Only ever one at a time. */
internal enum class EditorSheet { TOOL_OPTIONS, COLOR, LAYERS, ONION_SKIN, PROJECT_SETTINGS, EXPORT }

@Composable
private fun EditorContent(viewModel: EditorViewModel, ready: EditorLoadState.Ready, wideLayout: Boolean, onBack: () -> Unit) {
    val session = ready.session
    val sessionState by session.state.collectAsStateWithLifecycle()
    val tools by viewModel.tools.collectAsStateWithLifecycle()
    val settings by viewModel.appSettings.collectAsStateWithLifecycle()
    val exportState by viewModel.export.collectAsStateWithLifecycle()
    val playbackController = viewModel.playback
    val playback = playbackController?.state?.collectAsStateWithLifecycle()?.value ?: PlaybackState()
    val thumbnails = viewModel.thumbnails
    val svgImport = viewModel.svgImport

    var sheet by remember { mutableStateOf<EditorSheet?>(null) }
    var canvasView by remember { mutableStateOf<KineographCanvasView?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val workspaceColor = MaterialTheme.colorScheme.surfaceContainerHighest.toArgb()

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.saveBeforeBackground() }
    BackHandler(enabled = playback.isPlaying) { viewModel.stopPlayback() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbar.showSnackbar(context.getString(message.textRes())) }
    }
    LaunchedEffect(playback.isPlaying, canvasView) { canvasView?.isPlaying = playback.isPlaying }

    val fallbackSvgName = stringResource(R.string.svg_import_default_name)
    val svgPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) svgImport?.open(uri, context.contentResolver, fallbackSvgName)
        }
    val pickSvg = {
        try {
            svgPicker.launch(SVG_MIME_TYPES)
        } catch (_: ActivityNotFoundException) {
            viewModel.notify(EditorMessage.NO_FILE_PICKER)
        }
    }

    val startPlayback = {
        val view = canvasView
        val renderer = view?.renderer
        if (view != null && renderer != null) viewModel.startPlayback(renderer, view.fitScale())
    }

    EditorRootSurface {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (!playback.isPlaying) {
                EditorTopBar(
                    state = sessionState,
                    onBack = onBack,
                    onToggleOnion = {
                        session.updateSettings { it.copy(onionSkin = it.onionSkin.copy(enabled = !it.onionSkin.enabled)) }
                    },
                    onOnionSettings = { sheet = EditorSheet.ONION_SKIN },
                    onToggleGrid = { session.updateSettings { it.copy(grid = it.grid.copy(enabled = !it.grid.enabled)) } },
                    onProjectSettings = { sheet = EditorSheet.PROJECT_SETTINGS },
                    onExport = { sheet = EditorSheet.EXPORT },
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { viewContext ->
                        KineographCanvasView(
                            viewContext,
                            session,
                            ready.lowLatency,
                            workspaceColor,
                            toolConfig = { viewModel.tools.value.toCanvasConfig() },
                            listener =
                                object : CanvasListener {
                                    override fun onLayerNotDrawable() = viewModel.notify(EditorMessage.LAYER_LOCKED)

                                    override fun onPlaybackTapped() = viewModel.stopPlayback()
                                },
                        ).also { canvasView = it }
                    },
                    modifier = Modifier.fillMaxSize().testTag("canvas_host"),
                )
                if (!playback.isPlaying) {
                    EditorToolBar(
                        tools = tools,
                        side = settings.toolbarSide,
                        onSideChange = viewModel::setToolbarSide,
                        onSelect = { tool ->
                            if (tools.tool == tool && tool.hasOptions()) {
                                sheet = EditorSheet.TOOL_OPTIONS
                            } else {
                                viewModel.updateTools { it.copy(tool = tool) }
                            }
                        },
                        onOpenColor = { sheet = EditorSheet.COLOR },
                    )
                    QuickSliders(
                        tools = tools,
                        side = settings.toolbarSide,
                        onSize = { size -> viewModel.updateTools { it.withActiveSize(size) } },
                        onOpacity = { opacity -> viewModel.updateTools { it.withActiveOpacity(opacity) } },
                    )
                    if (sessionState.selection != null) {
                        SelectionBar(
                            onDone = session::commitSelection,
                            onCancel = session::cancelSelection,
                            onDelete = session::deleteSelection,
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                        )
                    }
                }
                if (sessionState.busy || playback.isPreparing) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
            if (playback.isPlaying) {
                PlaybackBar(
                    project = sessionState.project,
                    onStop = viewModel::stopPlayback,
                    onMode = viewModel::setPlaybackMode,
                    onFps = viewModel::setFps,
                )
            } else if (thumbnails != null) {
                EditorBottomBar(
                    state = sessionState,
                    session = session,
                    thumbnails = thumbnails,
                    wideLayout = wideLayout,
                    onOpenLayers = { sheet = EditorSheet.LAYERS },
                    onPlay = startPlayback,
                    onImportSvg = pickSvg,
                )
            }
        }
    }

    when (sheet) {
        EditorSheet.TOOL_OPTIONS -> {
            ToolOptionsSheet(tools, viewModel::updateTools) { sheet = null }
        }

        EditorSheet.COLOR -> {
            ColorSheet(
                tools = tools,
                onColor = viewModel::pickColor,
                onOpacity = { opacity -> viewModel.updateTools { it.withActiveOpacity(opacity) } },
                onDismiss = { sheet = null },
            )
        }

        EditorSheet.LAYERS -> {
            if (thumbnails != null) LayersSheet(sessionState, session, thumbnails) { sheet = null }
        }

        EditorSheet.ONION_SKIN -> {
            OnionSkinSheet(sessionState.project.settings.onionSkin, session) { sheet = null }
        }

        EditorSheet.PROJECT_SETTINGS -> {
            ProjectSettingsSheet(sessionState.project, session, viewModel) { sheet = null }
        }

        EditorSheet.EXPORT -> {
            ExportSheet(
                project = sessionState.project,
                onExport = { config ->
                    sheet = null
                    viewModel.startExport(config)
                },
                onDismiss = { sheet = null },
            )
        }

        null -> {
            Unit
        }
    }
    exportState?.let { state ->
        ExportDialog(state = state, onCancel = viewModel::cancelExport, onDismiss = viewModel::dismissExport)
    }
    if (svgImport != null) {
        val svgState by svgImport.state.collectAsStateWithLifecycle()
        SvgImportHost(
            controller = svgImport,
            state = svgState,
            project = sessionState.project,
            frameId = sessionState.frameId,
            canDrawOnActiveLayer = sessionState.canDrawOnActiveLayer,
            backgroundImage = session.scene?.backgroundImage,
        )
    }
}

/**
 * SVG first, then anything, because some apps and file systems label these files differently. A file that turns out not to be SVG gets a
 * friendly message when it is read.
 */
private val SVG_MIME_TYPES = arrayOf("image/svg+xml", "*/*")

private fun EditorMessage.textRes(): Int =
    when (this) {
        EditorMessage.LAYER_LOCKED -> R.string.message_layer_locked
        EditorMessage.LOW_MEMORY -> R.string.message_low_memory
        EditorMessage.CANNOT_OPEN -> R.string.message_cannot_open
        EditorMessage.SAVE_FAILED -> R.string.message_save_failed
        EditorMessage.RECOVERED_FROM_BACKUP -> R.string.message_recovered
        EditorMessage.MISSING_DRAWINGS -> R.string.message_missing_drawings
        EditorMessage.NO_FILE_PICKER -> R.string.message_no_file_picker
        EditorMessage.SVG_EMPTY -> R.string.svg_problem_empty
        EditorMessage.SVG_UNREADABLE -> R.string.svg_problem_unreadable
        EditorMessage.SVG_TOO_LARGE -> R.string.svg_problem_too_large
        EditorMessage.SVG_DAMAGED -> R.string.svg_problem_damaged
        EditorMessage.SVG_NOT_SVG -> R.string.svg_problem_not_svg
        EditorMessage.SVG_IMPORTED_INCOMPLETE -> R.string.svg_problem_incomplete
    }
