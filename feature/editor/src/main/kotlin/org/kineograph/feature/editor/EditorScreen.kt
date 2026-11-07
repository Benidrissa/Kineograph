package org.kineograph.feature.editor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
            val sessionState by state.session.state.collectAsStateWithLifecycle()
            if (sessionState.openFailed) {
                CanvasTooLarge(viewModel, sessionState.project.width, sessionState.project.height, onBack)
            } else {
                EditorContent(viewModel, state, wideLayout, onBack)
            }
        }
    }
}

/** There is no canvas to show, so this stays on screen, with something the user can do about it, until one fits. */
@Composable
private fun CanvasTooLarge(viewModel: EditorViewModel, width: Int, height: Int, onBack: () -> Unit) {
    EditorRootSurface {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(32.dp)
                .testTag("canvas_too_large"),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.message_cannot_open, width, height),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = { viewModel.retryOpen() }) { Text(stringResource(R.string.action_try_again)) }
            val smallerWidth = (width / 2).coerceAtLeast(1)
            val smallerHeight = (height / 2).coerceAtLeast(1)
            if (smallerWidth != width || smallerHeight != height) {
                OutlinedButton(onClick = { viewModel.retryOpen(smallerWidth, smallerHeight) }) {
                    Text(stringResource(R.string.action_use_smaller_canvas, smallerWidth, smallerHeight))
                }
            }
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
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
    val saveFailed by ready.autosaver.saveFailed.collectAsStateWithLifecycle()
    val leaving by viewModel.leaving.collectAsStateWithLifecycle()
    val unsavedOnLeave by viewModel.unsavedOnLeave.collectAsStateWithLifecycle()
    val playbackController = viewModel.playback
    val playback = playbackController?.state?.collectAsStateWithLifecycle()?.value ?: PlaybackState()
    val thumbnails = viewModel.thumbnails
    val svgImport = viewModel.svgImport
    val svgState = svgImport?.state?.collectAsStateWithLifecycle()?.value ?: SvgImportState.Idle

    var sheet by remember { mutableStateOf<EditorSheet?>(null) }
    var canvasView by remember { mutableStateOf<KineographCanvasView?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val workspaceColor = MaterialTheme.colorScheme.surfaceContainerHighest.toArgb()

    val leave = { viewModel.requestLeave(onBack) }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        // Only an export is meant to carry on after the user has left. Multi-window only pauses, so playback goes on there.
        viewModel.stopPlayback()
        viewModel.saveBeforeBackground()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.retryFailedSave() }
    // The handler declared last goes first, so playback keeps priority over leaving.
    BackHandler { leave() }
    BackHandler(enabled = playback.isPlaying) { viewModel.stopPlayback() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbar.showSnackbar(context.getString(message.textRes())) }
    }
    LaunchedEffect(playback.isPlaying, canvasView) { canvasView?.isPlaying = playback.isPlaying }
    HideSystemBars(playback.isPlaying)
    // Project settings, undo and redo can all change this, and nothing about them changes the size of the view.
    LaunchedEffect(sessionState.project.width, sessionState.project.height, canvasView) { canvasView?.onCanvasSizeChanged() }

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

    val startPlayback: () -> Unit = {
        canvasView?.let { view -> viewModel.startPlayback(view, view.fitScale()) }
    }

    EditorRootSurface {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (!playback.isPlaying) {
                EditorTopBar(
                    state = sessionState,
                    saveFailed = saveFailed,
                    onBack = leave,
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
                    // A change of theme reaches here without the activity starting over, so the view has to be told.
                    update = { it.setWorkspaceColor(workspaceColor) },
                    // During playback, Back stops it from anywhere along the edge.
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .then(if (playback.isPlaying) Modifier else Modifier.canvasEdgeBands(LocalDensity.current))
                            .testTag("canvas_host"),
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
                if (sessionState.busy || playback.isPreparing || leaving) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                // A sheet covers this, so while one is open it shows the messages instead.
                if (sheet == null && svgState !is SvgImportState.Ready) SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
            if (playback.isPlaying) {
                PlaybackBar(
                    project = sessionState.project,
                    playback = playback,
                    onStop = viewModel::stopPlayback,
                    onTogglePause = viewModel::togglePlaybackPause,
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

    CompositionLocalProvider(LocalEditorSnackbar provides snackbar) {
        when (sheet) {
            EditorSheet.TOOL_OPTIONS -> {
                ToolOptionsSheet(tools, viewModel::updateTools, onOpenColor = { sheet = EditorSheet.COLOR }) { sheet = null }
            }

            EditorSheet.COLOR -> {
                ColorSheet(
                    tools = tools,
                    onColor = viewModel::pickColor,
                    onColorCommit = viewModel::commitColor,
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
        if (svgImport != null) {
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
    exportState?.let { state ->
        ExportDialog(
            state = state,
            actions =
                remember(viewModel) {
                    ExportDialogActions(
                        onCancel = viewModel::cancelExport,
                        onDismiss = viewModel::dismissExport,
                        onSave = viewModel::saveExportToGallery,
                        onHandedOff = viewModel::markExportHandedOff,
                        onResultShown = viewModel::exportResultShown,
                    )
                },
        )
    }
    if (unsavedOnLeave) {
        UnsavedChangesDialog(
            onStay = viewModel::stayAfterFailedSave,
            onFreeSpace = {
                viewModel.stayAfterFailedSave()
                openStorageSettings(context)
            },
            onDiscard = { viewModel.discardAndLeave(onBack) },
        )
    }
}

/**
 * Takes the system bars away for a full-screen preview. A swipe brings them back for a moment, so the system is never out of reach.
 * Leaving the screen brings them back for good. The space they leave is taken up by the canvas, which fits itself to it.
 */
@Composable
private fun HideSystemBars(hidden: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    DisposableEffect(hidden, window, view) {
        val controller = WindowCompat.getInsetsController(window, view)
        if (hidden) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (hidden) controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Staying is the safe choice, so it is the main button and what dismissing the dialog does. */
@Composable
internal fun UnsavedChangesDialog(onStay: () -> Unit, onFreeSpace: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text(stringResource(R.string.unsaved_title)) },
        text = { Text(stringResource(R.string.unsaved_message)) },
        confirmButton = { TextButton(onClick = onStay) { Text(stringResource(R.string.unsaved_stay)) } },
        dismissButton = {
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.unsaved_discard)) }
            TextButton(onClick = onFreeSpace) { Text(stringResource(R.string.unsaved_free_space)) }
        },
    )
}

/** Opens the system's storage screen. Saving is tried again when the user comes back. */
private fun openStorageSettings(context: Context) {
    for (action in listOf(StorageManager.ACTION_MANAGE_STORAGE, Settings.ACTION_INTERNAL_STORAGE_SETTINGS, Settings.ACTION_SETTINGS)) {
        try {
            context.startActivity(Intent(action))
            return
        } catch (_: ActivityNotFoundException) {
            // Some devices leave this screen out, so try the next one.
        }
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
        EditorMessage.SAVE_FAILED -> R.string.message_save_failed
        EditorMessage.RECOVERED_FROM_BACKUP -> R.string.message_recovered
        EditorMessage.MISSING_DRAWINGS -> R.string.message_missing_drawings
        EditorMessage.DRAWING_NOT_LOADED -> R.string.message_drawing_not_loaded
        EditorMessage.UNDO_STEPS_LOST -> R.string.message_undo_steps_lost
        EditorMessage.SETTING_NOT_SAVED -> R.string.message_setting_not_saved
        EditorMessage.LAYER_NOT_SHOWN -> R.string.message_layer_not_shown
        EditorMessage.CANVAS_DOES_NOT_FIT -> R.string.message_canvas_does_not_fit
        EditorMessage.BACKGROUND_NOT_USED -> R.string.message_background_not_used
        EditorMessage.BACKGROUND_NOT_CHANGED -> R.string.message_background_not_changed
        EditorMessage.NO_FILE_PICKER -> R.string.message_no_file_picker
        EditorMessage.SVG_EMPTY -> R.string.svg_problem_empty
        EditorMessage.SVG_UNREADABLE -> R.string.svg_problem_unreadable
        EditorMessage.SVG_TOO_LARGE -> R.string.svg_problem_too_large
        EditorMessage.SVG_DAMAGED -> R.string.svg_problem_damaged
        EditorMessage.SVG_NOT_SVG -> R.string.svg_problem_not_svg
        EditorMessage.SVG_IMPORTED_INCOMPLETE -> R.string.svg_problem_incomplete
    }
