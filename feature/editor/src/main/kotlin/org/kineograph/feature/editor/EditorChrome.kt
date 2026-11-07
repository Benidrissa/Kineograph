package org.kineograph.feature.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.kineograph.data.ToolbarSide
import org.kineograph.engine.session.DrawingSession
import org.kineograph.engine.session.SessionState
import org.kineograph.feature.editor.canvas.CanvasTool
import org.kineograph.model.MAX_FPS
import org.kineograph.model.MIN_FPS
import org.kineograph.model.PlaybackMode
import org.kineograph.model.Project
import org.kineograph.ui.KgIconButton
import org.kineograph.ui.KgIcons
import org.kineograph.ui.VerticalSlider
import kotlin.math.roundToInt

internal fun CanvasTool.hasOptions(): Boolean = this != CanvasTool.LASSO

/** Above the 1.15 that Android offers just past normal, and below its next step of 1.3. */
private const val LARGE_FONT_SCALE = 1.2f

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EditorTopBar(
    state: SessionState,
    onBack: () -> Unit,
    onToggleOnion: () -> Unit,
    onOnionSettings: () -> Unit,
    onToggleGrid: () -> Unit,
    onProjectSettings: () -> Unit,
    onExport: () -> Unit,
    saveFailed: Boolean = false,
) {
    val onion = state.project.settings.onionSkin
    // At large font sizes the space between the buttons is narrower than a single long word, so the warning gets its own line.
    val warningHasOwnLine = LocalDensity.current.fontScale > LARGE_FONT_SCALE
    Column(Modifier.fillMaxWidth()) {
        // Only a minimum height, because large fonts need more, and the canvas can spare it.
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            KgIconButton(KgIcons.Back, stringResource(R.string.action_back), onBack)
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    state.project.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (saveFailed && !warningHasOwnLine) NotSavedWarning()
            }
            OnionButton(onion.enabled, onToggleOnion, onOnionSettings)
            KgIconButton(KgIcons.Grid, stringResource(R.string.editor_grid), onToggleGrid, selected = state.project.settings.grid.enabled)
            KgIconButton(KgIcons.Settings, stringResource(R.string.editor_project_settings), onProjectSettings)
            KgIconButton(KgIcons.Export, stringResource(R.string.editor_export), onExport, modifier = Modifier.testTag("export_button"))
        }
        if (saveFailed && warningHasOwnLine) NotSavedWarning(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    }
}

/** A snackbar goes away, but this stays true for as long as the failure lasts, however many saves fail. */
@Composable
private fun NotSavedWarning(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.editor_not_saved),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.testTag("not_saved"),
    )
}

/** Tap toggles the onion skin and a long press opens its settings. TalkBack gets the second one as a named action. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OnionButton(enabled: Boolean, onToggle: () -> Unit, onSettings: () -> Unit) {
    val label = stringResource(R.string.editor_onion_skin)
    val settingsLabel = stringResource(R.string.editor_onion_settings)
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (enabled) colors.primaryContainer else Color.Transparent)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 24.dp),
                onClick = onToggle,
                onLongClick = onSettings,
                onLongClickLabel = settingsLabel,
                role = Role.Switch,
            ).semantics {
                contentDescription = label
                selected = enabled
            }.testTag("onion_button"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(KgIcons.Onion, contentDescription = null, tint = if (enabled) colors.onPrimaryContainer else colors.onSurfaceVariant)
    }
}

/**
 * Keeps strokes that start at a side edge of the screen from being taken as the system's Back gesture. Android only grants this
 * for 200 dp of each edge, counted up from the bottom, so the bands stay at the bottom and leave room for the tool bar's handle,
 * which can dock to either side and is 48 dp tall. The rest of each edge goes on working as Back, as does the top bar's button.
 */
internal fun Modifier.canvasEdgeBands(density: Density): Modifier {
    val bandWidth = with(density) { 48.dp.toPx() }
    val bandHeight = with(density) { 152.dp.toPx() }
    return systemGestureExclusion { box ->
        val height = box.size.height.toFloat()
        Rect(0f, (height - bandHeight).coerceAtLeast(0f), bandWidth, height)
    }.systemGestureExclusion { box ->
        val width = box.size.width.toFloat()
        val height = box.size.height.toFloat()
        Rect(width - bandWidth, (height - bandHeight).coerceAtLeast(0f), width, height)
    }
}

/** The vertical tool bar. It docks to either edge, collapses out of the way, and its handle drags it across. */
@Composable
internal fun BoxScope.EditorToolBar(
    tools: ToolState,
    side: ToolbarSide,
    onSideChange: (ToolbarSide) -> Unit,
    onSelect: (CanvasTool) -> Unit,
    onOpenColor: () -> Unit,
) {
    var collapsed by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val latestSide by rememberUpdatedState(side)
    val flipDistance = with(LocalDensity.current) { 96.dp.toPx() }
    val moveLabel = stringResource(R.string.toolbar_move_side)
    Surface(
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier =
            Modifier
                .align(if (side == ToolbarSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 6.dp, vertical = 6.dp)
                .graphicsLayer { translationX = dragX },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val handleLabel = stringResource(if (collapsed) R.string.toolbar_expand else R.string.toolbar_collapse)
            Box(
                Modifier
                    .size(48.dp)
                    // Dragging this away from the edge is the very motion of the system's Back gesture.
                    .systemGestureExclusion()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val current = latestSide
                                val movedAcross = if (current == ToolbarSide.LEFT) dragX > flipDistance else dragX < -flipDistance
                                if (movedAcross) onSideChange(if (current == ToolbarSide.LEFT) ToolbarSide.RIGHT else ToolbarSide.LEFT)
                                dragX = 0f
                            },
                            onDragCancel = { dragX = 0f },
                        ) { change, amount ->
                            change.consume()
                            dragX += amount
                        }
                    }.clickable(onClickLabel = handleLabel) { collapsed = !collapsed }
                    .semantics {
                        contentDescription = handleLabel
                        // Dragging is no use to TalkBack.
                        customActions =
                            listOf(
                                CustomAccessibilityAction(moveLabel) {
                                    onSideChange(if (side == ToolbarSide.LEFT) ToolbarSide.RIGHT else ToolbarSide.LEFT)
                                    true
                                },
                            )
                    }.testTag("toolbar_handle"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(KgIcons.Handle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!collapsed) {
                // On a phone in landscape the tools are taller than the canvas. They scroll rather than get squeezed, and the handle
                // stays put, so the bar can still be collapsed or moved however far they have been scrolled.
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ToolButton(KgIcons.Brush, stringResource(R.string.tool_brush), CanvasTool.BRUSH, tools.tool, "tool_brush", onSelect)
                    ToolButton(KgIcons.Eraser, stringResource(R.string.tool_eraser), CanvasTool.ERASER, tools.tool, "tool_eraser", onSelect)
                    ToolButton(KgIcons.Lasso, stringResource(R.string.tool_lasso), CanvasTool.LASSO, tools.tool, "tool_lasso", onSelect)
                    ToolButton(KgIcons.Fill, stringResource(R.string.tool_fill), CanvasTool.FILL, tools.tool, "tool_fill", onSelect)
                    val colorLabel = stringResource(R.string.tool_color)
                    Box(
                        Modifier
                            .size(48.dp)
                            .clickable(role = Role.Button, onClickLabel = colorLabel, onClick = onOpenColor)
                            .semantics { contentDescription = colorLabel }
                            .testTag("tool_color"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(tools.colorArgb))
                                .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        )
                    }
                }
            }
        }
    }
}

/** A tool is a choice among the others. Tapping the one already chosen opens its options, and TalkBack says that before the tap. */
@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    tool: CanvasTool,
    active: CanvasTool,
    tag: String,
    onSelect: (CanvasTool) -> Unit,
) {
    val selected = tool == active
    val optionsLabel = stringResource(R.string.tool_options)
    val hint = if (selected && tool.hasOptions()) Modifier.semantics { onClick(label = optionsLabel, action = null) } else Modifier
    KgIconButton(icon, label, { onSelect(tool) }, selected = selected, modifier = Modifier.testTag(tag).then(hint))
}

/**
 * Quick size and opacity sliders on the free edge of the canvas, on a panel so that they stand out against whatever is on it. The sliders come first: their 48 dp is
 * not negotiable and the rest of the panel is. So on a short canvas, such as a phone held sideways, its icons and then its margins make way. Below two sliders' height
 * there is nowhere to put them, and the tool's own options sheet has the same two.
 */
@Composable
internal fun BoxScope.QuickSliders(tools: ToolState, side: ToolbarSide, onSize: (Float) -> Unit, onOpacity: (Float) -> Unit) {
    if (tools.tool != CanvasTool.BRUSH && tools.tool != CanvasTool.ERASER) return
    val sizeLabel = stringResource(R.string.slider_size)
    val opacityLabel = stringResource(R.string.slider_opacity)
    BoxWithConstraints(
        Modifier
            .align(if (side == ToolbarSide.LEFT) Alignment.CenterEnd else Alignment.CenterStart)
            .fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
        if (maxHeight < QUICK_SLIDERS_LEAST_HEIGHT) return@BoxWithConstraints
        val roomy = maxHeight >= QUICK_SLIDERS_ROOMY_HEIGHT
        val hasMargins = maxHeight >= QUICK_SLIDERS_MARGINS_HEIGHT
        Surface(
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 3.dp,
            shadowElevation = 3.dp,
            modifier =
                Modifier
                    .padding(horizontal = 6.dp, vertical = if (hasMargins) 6.dp else 0.dp)
                    // In this order: filling the height first would leave nothing for the limit to limit. Above and below them, strokes can start.
                    .heightIn(max = 400.dp)
                    .fillMaxHeight(if (roomy) 0.8f else 1f),
        ) {
            Column(
                Modifier.padding(vertical = if (hasMargins) 10.dp else 0.dp),
                // With nothing between them they would look like one long slider with two thumbs. The icons do that on a tall canvas.
                verticalArrangement = Arrangement.spacedBy(
                    if (roomy) 0.dp else (maxHeight - QUICK_SLIDERS_LEAST_HEIGHT).coerceAtMost(8.dp),
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (roomy) {
                    Icon(
                        KgIcons.BrushSize,
                        contentDescription = null,
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                VerticalSlider(
                    value = ToolState.sizeToSlider(tools.activeSize),
                    onValueChange = { onSize(ToolState.sliderToSize(it)) },
                    label = sizeLabel,
                    stateText = stringResource(R.string.slider_size_value, tools.activeSize.roundToInt()),
                    modifier = Modifier.weight(1f).padding(vertical = if (roomy) 6.dp else 0.dp).testTag("slider_size"),
                )
                if (roomy) {
                    Icon(
                        KgIcons.Opacity,
                        contentDescription = null,
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                VerticalSlider(
                    value = tools.activeOpacity,
                    onValueChange = onOpacity,
                    label = opacityLabel,
                    stateText = "${(tools.activeOpacity * 100f).roundToInt()}%",
                    modifier = Modifier.weight(1f).padding(vertical = if (roomy) 6.dp else 0.dp).testTag("slider_opacity"),
                )
            }
        }
    }
}

/** Two sliders of 48 dp. Nothing else is allowed to cost them anything below the next height. */
private val QUICK_SLIDERS_LEAST_HEIGHT = 96.dp

/** Twelve above and below, twenty inside the panel and eight between them, which leaves each slider a little over 48 dp. */
private val QUICK_SLIDERS_MARGINS_HEIGHT = 144.dp

/** Where the panel can leave some of the edge free and still show what each slider is for, with each slider about 80 dp. */
private val QUICK_SLIDERS_ROOMY_HEIGHT = 320.dp

@Composable
internal fun SelectionBar(onDone: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(24.dp), tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Row {
            KgIconButton(
                KgIcons.Close,
                stringResource(R.string.selection_cancel),
                onCancel,
                modifier = Modifier.testTag("selection_cancel"),
            )
            KgIconButton(KgIcons.Trash, stringResource(R.string.selection_delete), onDelete)
            KgIconButton(KgIcons.Check, stringResource(R.string.selection_done), onDone, modifier = Modifier.testTag("selection_done"))
        }
    }
}

/**
 * Which slot playback is showing. The picture itself is on a surface that tests cannot read, and this is how they can tell it is
 * moving. A property of its own, because TalkBack ignores those, and it changes far too often to be read out.
 */
public val PlaybackSlot: SemanticsPropertyKey<Int> = SemanticsPropertyKey("PlaybackSlot")

/** The controls left on screen during playback. They wrap, because at the largest font size they are wider than a small phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaybackBar(
    project: Project,
    playback: PlaybackState,
    onStop: () -> Unit,
    onTogglePause: () -> Unit,
    onMode: (PlaybackMode) -> Unit,
    onFps: (Int) -> Unit,
) {
    FlowRow(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 8.dp)
            .semantics { this[PlaybackSlot] = playback.slotIndex }
            .testTag("playback_bar"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        KgIconButton(KgIcons.Stop, stringResource(R.string.playback_stop), onStop, modifier = Modifier.testTag("stop_button"))
        // Until the first frame is up there is nothing to hold on.
        KgIconButton(
            if (playback.isPaused) KgIcons.Play else KgIcons.Pause,
            stringResource(if (playback.isPaused) R.string.playback_resume else R.string.playback_pause),
            onTogglePause,
            enabled = !playback.isPreparing,
            modifier = Modifier.testTag("pause_button"),
        )
        val mode = project.settings.playbackMode
        KgIconButton(
            icon =
                when (mode) {
                    PlaybackMode.LOOP -> KgIcons.Loop
                    PlaybackMode.PING_PONG -> KgIcons.PingPong
                    PlaybackMode.ONCE -> KgIcons.PlayOnce
                },
            contentDescription =
                stringResource(
                    when (mode) {
                        PlaybackMode.LOOP -> R.string.playback_mode_loop
                        PlaybackMode.PING_PONG -> R.string.playback_mode_ping_pong
                        PlaybackMode.ONCE -> R.string.playback_mode_once
                    },
                ),
            onClick = {
                onMode(
                    when (mode) {
                        PlaybackMode.LOOP -> PlaybackMode.PING_PONG
                        PlaybackMode.PING_PONG -> PlaybackMode.ONCE
                        PlaybackMode.ONCE -> PlaybackMode.LOOP
                    },
                )
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            FpsButton("−", stringResource(R.string.playback_fps_decrease), project.fps > MIN_FPS, "fps_down") {
                onFps((project.fps - 1).coerceAtLeast(MIN_FPS))
            }
            // Pressing a button says nothing by itself about where that got to.
            Text(
                stringResource(R.string.fps_value, project.fps),
                Modifier.padding(horizontal = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleMedium,
            )
            FpsButton("+", stringResource(R.string.playback_fps_increase), project.fps < MAX_FPS, "fps_up") {
                onFps((project.fps + 1).coerceAtMost(MAX_FPS))
            }
        }
    }
}

@Composable
private fun FpsButton(symbol: String, description: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(48.dp).semantics { contentDescription = description }.testTag(tag),
    ) {
        // A symbol to look at. Read out, it would say nothing about what it changes.
        Text(symbol, Modifier.clearAndSetSemantics { })
    }
}

@Composable
internal fun EditorBottomBar(
    state: SessionState,
    session: DrawingSession,
    thumbnails: ThumbnailLoader,
    wideLayout: Boolean,
    onOpenLayers: () -> Unit,
    onPlay: () -> Unit,
    onImportSvg: () -> Unit = {},
    onInBetweens: (String) -> Unit = {},
) {
    val frameActions = rememberFrameActions(session, onInBetweens)
    Surface(tonalElevation = 2.dp) {
        if (wideLayout) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                KgIconButton(
                    KgIcons.Undo,
                    stringResource(R.string.action_undo),
                    session::undo,
                    enabled = state.canUndo,
                    modifier = Modifier.testTag("undo_button"),
                )
                KgIconButton(
                    KgIcons.Redo,
                    stringResource(R.string.action_redo),
                    session::redo,
                    enabled = state.canRedo,
                    modifier = Modifier.testTag("redo_button"),
                )
                LayersButton(state, thumbnails, onOpenLayers)
                FrameStrip(state, thumbnails, frameActions, Modifier.weight(1f))
                KgIconButton(KgIcons.Add, stringResource(R.string.frame_add), session::addFrame, modifier = Modifier.testTag("add_frame"))
                KgIconButton(KgIcons.Play, stringResource(R.string.playback_play), onPlay, modifier = Modifier.testTag("play_button"))
                KgIconButton(KgIcons.Copy, stringResource(R.string.frame_copy), { session.copyFrames(listOf(state.frameId)) })
                KgIconButton(KgIcons.Paste, stringResource(R.string.frame_paste), session::pasteFrames, enabled = state.hasFrameClipboard)
                KgIconButton(KgIcons.Clear, stringResource(R.string.layer_clear), session::clearActiveCel)
                KgIconButton(
                    KgIcons.Import,
                    stringResource(R.string.svg_import_action),
                    onImportSvg,
                    modifier = Modifier.testTag("import_svg"),
                )
            }
        } else {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    FrameStrip(state, thumbnails, frameActions, Modifier.weight(1f))
                    KgIconButton(
                        KgIcons.Add,
                        stringResource(R.string.frame_add),
                        session::addFrame,
                        modifier = Modifier.testTag("add_frame"),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    KgIconButton(
                        KgIcons.Undo,
                        stringResource(R.string.action_undo),
                        session::undo,
                        enabled = state.canUndo,
                        modifier = Modifier.testTag("undo_button"),
                    )
                    KgIconButton(
                        KgIcons.Redo,
                        stringResource(R.string.action_redo),
                        session::redo,
                        enabled = state.canRedo,
                        modifier = Modifier.testTag("redo_button"),
                    )
                    LayersButton(state, thumbnails, onOpenLayers)
                    KgIconButton(KgIcons.Play, stringResource(R.string.playback_play), onPlay, modifier = Modifier.testTag("play_button"))
                    OverflowActions(state, session, onImportSvg)
                }
            }
        }
    }
}

@Composable
private fun OverflowActions(state: SessionState, session: DrawingSession, onImportSvg: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        KgIconButton(KgIcons.More, stringResource(R.string.editor_more), { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuItem(KgIcons.Copy, stringResource(R.string.frame_copy)) {
                open = false
                session.copyFrames(listOf(state.frameId))
            }
            MenuItem(KgIcons.Paste, stringResource(R.string.frame_paste), enabled = state.hasFrameClipboard) {
                open = false
                session.pasteFrames()
            }
            MenuItem(KgIcons.Clear, stringResource(R.string.layer_clear)) {
                open = false
                session.clearActiveCel()
            }
            MenuItem(KgIcons.Import, stringResource(R.string.svg_import_action)) {
                open = false
                onImportSvg()
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, enabled = enabled, onClick = onClick)
}

@Composable
private fun LayersButton(state: SessionState, thumbnails: ThumbnailLoader, onClick: () -> Unit) {
    val label = stringResource(R.string.layers_open, state.project.layers.size)
    val thumbnail = rememberLayerThumbnail(thumbnails, state.project, state.frameId, state.layerId)
    Box(
        Modifier
            .size(48.dp)
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
            }.testTag("layers_button"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(KgIcons.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        thumbnail?.let {
            Image(
                it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .size(20.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp)),
            )
        }
    }
}

/** What can be done to a frame from the strip. */
internal class FrameActions(
    val onSelect: (String) -> Unit,
    val onDuplicate: (String) -> Unit,
    val onInsertBefore: (String) -> Unit,
    val onCopy: (String) -> Unit,
    val onPaste: (String) -> Unit,
    val onClear: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onMove: (Int, Int) -> Unit,
    val onInBetweens: (String) -> Unit = {},
)

@Composable
private fun rememberFrameActions(session: DrawingSession, onInBetweens: (String) -> Unit): FrameActions =
    remember(session, onInBetweens) {
        FrameActions(
            onSelect = session::selectFrame,
            onDuplicate = session::duplicateFrame,
            onInsertBefore = session::insertFrameBefore,
            onCopy = { session.copyFrames(listOf(it)) },
            onPaste = {
                session.selectFrame(it)
                session.pasteFrames()
            },
            onClear = session::clearFrame,
            onDelete = session::deleteFrame,
            onMove = session::moveFrame,
            onInBetweens = onInBetweens,
        )
    }

/** The numbered frame thumbnails. Tap selects, long press opens a menu, and long press then drag reorders. */
@Composable
internal fun FrameStrip(state: SessionState, thumbnails: ThumbnailLoader, actions: FrameActions, modifier: Modifier = Modifier) {
    val project = state.project
    val listState = rememberLazyListState()
    val current = project.frameIndex(state.frameId)
    // A frame the user has just moved, by dragging it or through TalkBack. The strip goes with that one, as the current frame may only
    // have been passed on the way, and pulling the strip back to it would take away the one the user is looking at.
    var moving by remember { mutableStateOf<FrameMove?>(null) }
    val landed = moving?.takeIf { project.frameIndex(it.frameId) == it.to }
    LaunchedEffect(landed) {
        val move = landed ?: return@LaunchedEffect
        try {
            listState.keepInView(move.to)
            moving = null
        } finally {
            move.letGo()
        }
    }
    // Otherwise it follows the current frame wherever that goes, as it does when selecting one or on an undo.
    LaunchedEffect(state.frameId, current) {
        if (landed == null && current >= 0) listState.keepInView(current)
    }
    val moveFrame: (FrameMove, Int) -> Unit = { move, from ->
        moving?.letGo()
        moving = move
        actions.onMove(from, move.to)
    }
    val aspect = project.width.toFloat() / project.height
    val cellHeight = 56.dp
    val cellWidth = (cellHeight * aspect).coerceIn(48.dp, 100.dp)
    LazyRow(
        state = listState,
        modifier = modifier.height(64.dp).testTag("frame_strip"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(project.frames, key = { _, slot -> slot.id }) { index, slot ->
            FrameCell(state, index, slot.id, slot.hold, thumbnails, actions, moveFrame, listState, cellWidth, cellHeight)
        }
    }
}

/** A frame on its way to the place [to] in the strip, kept in the list by [held] if it has to be there all the way. */
private class FrameMove(val frameId: String, val to: Int, private var held: PinnableContainer.PinnedHandle? = null) {
    fun letGo() {
        held?.release()
        held = null
    }
}

/** Scrolls the item at [index] into view, if it is not all there. */
private suspend fun LazyListState.keepInView(index: Int) {
    // This runs ahead of the layout that puts the items in their new order, and that layout can move the list by itself.
    withFrameNanos { }
    val layout = layoutInfo
    val shown = layout.visibleItemsInfo.any { it.index == index && it.offset >= 0 && it.offset + it.size <= layout.viewportEndOffset }
    if (!shown) animateScrollToItem(index)
}

/**
 * How fast to scroll the strip, in pixels a second, with a finger at [x] in it. It starts within [zone] of either end, and is at
 * [maxSpeed] by the time the finger reaches the end. Past the end is no faster, since the strip often ends well before the screen does.
 */
internal fun autoScrollSpeed(x: Float, viewportWidth: Int, zone: Float, maxSpeed: Float): Float =
    when {
        x < zone -> -maxSpeed * ((zone - x) / zone).coerceAtMost(1f)
        x > viewportWidth - zone -> maxSpeed * ((x - (viewportWidth - zone)) / zone).coerceAtMost(1f)
        else -> 0f
    }

@Composable
private fun FrameCell(
    state: SessionState,
    index: Int,
    frameId: String,
    hold: Int,
    thumbnails: ThumbnailLoader,
    actions: FrameActions,
    moveFrame: (FrameMove, from: Int) -> Unit,
    listState: LazyListState,
    width: Dp,
    height: Dp,
) {
    val project = state.project
    val isCurrent = frameId == state.frameId
    var dragging by remember { mutableStateOf(false) }
    var dragTravel by remember { mutableFloatStateOf(0f) }
    var dragStartX by remember { mutableFloatStateOf(0f) }
    var placedX by remember { mutableFloatStateOf(0f) }
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val menuSlop = with(density) { 12.dp.toPx() }
    val scrollZone = with(density) { AUTO_SCROLL_ZONE_DP.dp.toPx() }
    val scrollSpeed = with(density) { AUTO_SCROLL_DP_PER_SECOND.dp.toPx() }
    val lastIndex = project.frames.lastIndex
    // The gesture below keeps running from the first touch on, and restarting it for each change would cut a drag short. So it reads these.
    val latestIndex by rememberUpdatedState(index)
    val latestLastIndex by rememberUpdatedState(lastIndex)
    val latestStepPx by rememberUpdatedState(with(density) { (width + 4.dp).toPx() })
    val latestActions by rememberUpdatedState(actions)
    val latestMoveFrame by rememberUpdatedState(moveFrame)
    val pinnable = LocalPinnableContainer.current
    val scope = rememberCoroutineScope()
    // TalkBack stays on a frame only for as long as the list keeps it. A frame moved out of view would be thrown away before the strip
    // caught up with it, and TalkBack would lose its place for the next move.
    val moveByAction: (Int) -> Unit = { target -> moveFrame(FrameMove(frameId, target, pinnable?.pin()), index) }
    val description =
        if (hold > 1) {
            stringResource(R.string.frame_cell_description_held, index + 1, project.frames.size, hold)
        } else {
            stringResource(R.string.frame_cell_description, index + 1, project.frames.size)
        }
    val selectLabel = stringResource(R.string.frame_select)
    val menuLabel = stringResource(R.string.frame_menu)
    val moveEarlier = stringResource(R.string.frame_move_earlier)
    val moveLater = stringResource(R.string.frame_move_later)
    val thumbnail = rememberFrameThumbnail(thumbnails, project, frameId)

    Box(
        Modifier
            .zIndex(if (dragging) 1f else 0f)
            .onPlaced { placedX = it.positionInParent().x }
            // Outside the layer below, so that distances are the finger's and not scaled down by the lifted look.
            .pointerInput(frameId) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val longPress = awaitLongPressOrCancellation(down.id)
                    if (longPress == null) {
                        // A short press that lifts cleanly is a tap. A press that turned into a scroll is consumed by the list.
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (up != null && !up.pressed && !up.isConsumed) latestActions.onSelect(frameId)
                        return@awaitEachGesture
                    }
                    dragStartX = placedX
                    dragTravel = 0f
                    dragging = true
                    // Scrolling can carry this cell far out of view, and the list must not throw it away with the gesture still in it.
                    val pinned = pinnable?.pin()
                    val fingerStartX = placedX + longPress.position.x
                    var moved = false
                    var scrolled = 0f
                    var autoScroll: Job? = null
                    val lifted =
                        try {
                            drag(longPress.id) { change ->
                                dragTravel += change.positionChange().x
                                // A long press with no movement is asking for the menu, wherever on the strip it happens.
                                if (!moved && kotlin.math.abs(dragTravel) >= menuSlop) {
                                    moved = true
                                    // A finger held still sends no events, so the strip has its own clock.
                                    autoScroll =
                                        scope.launch {
                                            var lastFrame = withFrameNanos { it }
                                            while (isActive) {
                                                val now = withFrameNanos { it }
                                                val seconds = (now - lastFrame) / NANOS_PER_SECOND
                                                lastFrame = now
                                                val viewport = listState.layoutInfo.viewportSize.width
                                                val speed = autoScrollSpeed(fingerStartX + dragTravel, viewport, scrollZone, scrollSpeed)
                                                if (speed != 0f) scrolled += listState.scrollBy(speed * seconds)
                                            }
                                        }
                                }
                                change.consume()
                            }
                        } finally {
                            autoScroll?.cancel()
                            pinned?.release()
                            dragging = false
                        }
                    val travelled = dragTravel + scrolled
                    dragTravel = 0f
                    // Taken away by the system or another gesture, which is no decision by the user.
                    if (!lifted) return@awaitEachGesture
                    if (moved) {
                        val target = (latestIndex + (travelled / latestStepPx).roundToInt()).coerceIn(0, latestLastIndex)
                        if (target != latestIndex) latestMoveFrame(FrameMove(frameId, target), latestIndex)
                    } else {
                        menuOpen = true
                    }
                }
            }.graphicsLayer {
                // Where the list places a cell it has only kept for the gesture says nothing of where it would be, so this goes by
                // where the cell is compared with where the finger wants it, which also takes care of any scrolling.
                translationX = if (dragging) dragStartX + dragTravel - placedX else 0f
                if (dragging) {
                    scaleX = 1.08f
                    scaleY = 1.08f
                }
            }.size(width, height)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfaceVariant)
            .border(if (isCurrent) 3.dp else 1.dp, if (isCurrent) colors.primary else colors.outlineVariant, RoundedCornerShape(6.dp))
            .testTag("frame_$index")
            // All one thing to TalkBack, with what is in it in the description, and selecting it an action that works without touch.
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
                selected = isCurrent
                onClick(label = selectLabel) {
                    actions.onSelect(frameId)
                    true
                }
                customActions =
                    buildList {
                        add(
                            CustomAccessibilityAction(menuLabel) {
                                menuOpen = true
                                true
                            },
                        )
                        if (index > 0) {
                            add(
                                CustomAccessibilityAction(moveEarlier) {
                                    moveByAction(index - 1)
                                    true
                                },
                            )
                        }
                        if (index < lastIndex) {
                            add(
                                CustomAccessibilityAction(moveLater) {
                                    moveByAction(index + 1)
                                    true
                                },
                            )
                        }
                    }
            },
    ) {
        thumbnail?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        Text(
            (index + 1).toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            color = if (isCurrent) colors.onPrimary else colors.onSurface,
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .background(
                        if (isCurrent) colors.primary else colors.surfaceVariant.copy(alpha = 0.85f),
                        RoundedCornerShape(topEnd = 4.dp),
                    ).padding(horizontal = 4.dp),
        )
        if (hold > 1) {
            Text(
                "×$hold",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSecondaryContainer,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .background(colors.secondaryContainer, RoundedCornerShape(bottomStart = 4.dp))
                        .padding(horizontal = 3.dp),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            MenuItem(KgIcons.Duplicate, stringResource(R.string.frame_duplicate)) {
                menuOpen = false
                actions.onDuplicate(frameId)
            }
            MenuItem(KgIcons.InsertBefore, stringResource(R.string.frame_insert_before)) {
                menuOpen = false
                actions.onInsertBefore(frameId)
            }
            MenuItem(KgIcons.Copy, stringResource(R.string.frame_copy)) {
                menuOpen = false
                actions.onCopy(frameId)
            }
            MenuItem(KgIcons.Paste, stringResource(R.string.frame_paste), enabled = state.hasFrameClipboard) {
                menuOpen = false
                actions.onPaste(frameId)
            }
            MenuItem(
                KgIcons.InsertBefore,
                stringResource(R.string.frame_in_betweens),
                enabled = state.project.frames
                    .lastOrNull()
                    ?.id != frameId,
            ) {
                menuOpen = false
                actions.onInBetweens(frameId)
            }
            MenuItem(KgIcons.Clear, stringResource(R.string.frame_clear)) {
                menuOpen = false
                actions.onClear(frameId)
            }
            MenuItem(KgIcons.Trash, stringResource(R.string.frame_delete)) {
                menuOpen = false
                actions.onDelete(frameId)
            }
        }
    }
}

@Composable
internal fun rememberFrameThumbnail(loader: ThumbnailLoader, project: Project, frameId: String): ImageBitmap? {
    val edge = with(LocalDensity.current) { THUMBNAIL_EDGE_DP.dp.roundToPx() }
    val key = loader.frameKey(project, frameId, edge)
    val image by produceState(loader.cached(key), key) { value = loader.loadFrame(project, frameId, edge) ?: value }
    return image
}

@Composable
internal fun rememberLayerThumbnail(loader: ThumbnailLoader, project: Project, frameId: String, layerId: String): ImageBitmap? {
    val edge = with(LocalDensity.current) { THUMBNAIL_EDGE_DP.dp.roundToPx() }
    val key = loader.layerKey(project, frameId, layerId, edge)
    val hasContent = project.cel(layerId, frameId) != null
    val image by produceState(loader.cached(key), key, hasContent) {
        value = if (hasContent) loader.loadLayer(project, frameId, layerId, edge) ?: value else null
    }
    return image
}

private const val THUMBNAIL_EDGE_DP = 96
private const val AUTO_SCROLL_ZONE_DP = 48
private const val AUTO_SCROLL_DP_PER_SECOND = 600
private const val NANOS_PER_SECOND = 1_000_000_000f

/** Simple button for sheets. */
@Composable
internal fun SheetTextButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label) }
}
