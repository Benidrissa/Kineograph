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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
) {
    val onion = state.project.settings.onionSkin
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        KgIconButton(KgIcons.Back, stringResource(R.string.action_back), onBack)
        Text(
            state.project.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        OnionButton(onion.enabled, onToggleOnion, onOnionSettings)
        KgIconButton(KgIcons.Grid, stringResource(R.string.editor_grid), onToggleGrid, selected = state.project.settings.grid.enabled)
        KgIconButton(KgIcons.Settings, stringResource(R.string.editor_project_settings), onProjectSettings)
        KgIconButton(KgIcons.Export, stringResource(R.string.editor_export), onExport, modifier = Modifier.testTag("export_button"))
    }
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
                    .size(48.dp, 32.dp)
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
                    .semantics { contentDescription = handleLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(KgIcons.Handle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!collapsed) {
                ToolButton(
                    KgIcons.Brush,
                    stringResource(R.string.tool_brush),
                    tools.tool == CanvasTool.BRUSH,
                    "tool_brush",
                ) { onSelect(CanvasTool.BRUSH) }
                ToolButton(KgIcons.Eraser, stringResource(R.string.tool_eraser), tools.tool == CanvasTool.ERASER, "tool_eraser") {
                    onSelect(CanvasTool.ERASER)
                }
                ToolButton(
                    KgIcons.Lasso,
                    stringResource(R.string.tool_lasso),
                    tools.tool == CanvasTool.LASSO,
                    "tool_lasso",
                ) { onSelect(CanvasTool.LASSO) }
                ToolButton(
                    KgIcons.Fill,
                    stringResource(R.string.tool_fill),
                    tools.tool == CanvasTool.FILL,
                    "tool_fill",
                ) { onSelect(CanvasTool.FILL) }
                val colorLabel = stringResource(R.string.tool_color)
                Box(
                    Modifier
                        .size(48.dp)
                        .clickable(onClickLabel = colorLabel, onClick = onOpenColor)
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

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    KgIconButton(icon, label, onClick, selected = selected, modifier = Modifier.testTag(tag))
}

/** Quick size and opacity sliders on the free edge of the canvas. */
@Composable
internal fun BoxScope.QuickSliders(tools: ToolState, side: ToolbarSide, onSize: (Float) -> Unit, onOpacity: (Float) -> Unit) {
    if (tools.tool != CanvasTool.BRUSH && tools.tool != CanvasTool.ERASER) return
    val sizeLabel = stringResource(R.string.slider_size)
    val opacityLabel = stringResource(R.string.slider_opacity)
    Surface(
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier =
            Modifier
                .align(if (side == ToolbarSide.LEFT) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(6.dp)
                .heightIn(max = 400.dp)
                .fillMaxHeight(0.8f),
    ) {
        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(KgIcons.BrushSize, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            VerticalSlider(
                value = ToolState.sizeToSlider(tools.activeSize),
                onValueChange = { onSize(ToolState.sliderToSize(it)) },
                label = sizeLabel,
                stateText = stringResource(R.string.slider_size_value, tools.activeSize.roundToInt()),
                modifier = Modifier.weight(1f).padding(vertical = 6.dp).testTag("slider_size"),
            )
            Icon(KgIcons.Opacity, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            VerticalSlider(
                value = tools.activeOpacity,
                onValueChange = onOpacity,
                label = opacityLabel,
                stateText = "${(tools.activeOpacity * 100f).roundToInt()}%",
                modifier = Modifier.weight(1f).padding(vertical = 6.dp).testTag("slider_opacity"),
            )
        }
    }
}

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

/** The controls left on screen during playback. */
@Composable
internal fun PlaybackBar(project: Project, onStop: () -> Unit, onMode: (PlaybackMode) -> Unit, onFps: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        KgIconButton(KgIcons.Stop, stringResource(R.string.playback_stop), onStop, modifier = Modifier.testTag("stop_button"))
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
            OutlinedButton(onClick = {
                onFps((project.fps - 1).coerceAtLeast(MIN_FPS))
            }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp)) {
                Text("−")
            }
            Text(
                stringResource(R.string.fps_value, project.fps),
                Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(onClick = {
                onFps((project.fps + 1).coerceAtMost(MAX_FPS))
            }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp)) {
                Text("+")
            }
        }
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
) {
    val frameActions = rememberFrameActions(session)
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
)

@Composable
private fun rememberFrameActions(session: DrawingSession): FrameActions =
    remember(session) {
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
        )
    }

/** The numbered frame thumbnails. Tap selects, long press opens a menu, and long press then drag reorders. */
@Composable
internal fun FrameStrip(state: SessionState, thumbnails: ThumbnailLoader, actions: FrameActions, modifier: Modifier = Modifier) {
    val project = state.project
    val listState = rememberLazyListState()
    val current = project.frameIndex(state.frameId)
    LaunchedEffect(current) {
        if (current >= 0) {
            val visible = listState.layoutInfo.visibleItemsInfo
            val shown = visible.any {
                it.index == current && it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset
            }
            if (!shown) listState.animateScrollToItem(current)
        }
    }
    val aspect = project.width.toFloat() / project.height
    val cellHeight = 56.dp
    val cellWidth = (cellHeight * aspect).coerceIn(44.dp, 100.dp)
    LazyRow(
        state = listState,
        modifier = modifier.height(64.dp).testTag("frame_strip"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(project.frames, key = { _, slot -> slot.id }) { index, slot ->
            FrameCell(state, index, slot.id, slot.hold, thumbnails, actions, cellWidth, cellHeight)
        }
    }
}

@Composable
private fun FrameCell(
    state: SessionState,
    index: Int,
    frameId: String,
    hold: Int,
    thumbnails: ThumbnailLoader,
    actions: FrameActions,
    width: Dp,
    height: Dp,
) {
    val project = state.project
    val isCurrent = frameId == state.frameId
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val stepPx = with(density) { (width + 4.dp).toPx() }
    val menuSlop = with(density) { 12.dp.toPx() }
    val latestIndex by rememberUpdatedState(index)
    val lastIndex = project.frames.lastIndex
    val latestActions by rememberUpdatedState(actions)
    val description = stringResource(R.string.frame_cell_description, index + 1, project.frames.size)
    val menuLabel = stringResource(R.string.frame_menu)
    val moveEarlier = stringResource(R.string.frame_move_earlier)
    val moveLater = stringResource(R.string.frame_move_later)
    val thumbnail = rememberFrameThumbnail(thumbnails, project, frameId)

    Box(
        Modifier
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationX = dragOffset
                if (dragging) {
                    scaleX = 1.08f
                    scaleY = 1.08f
                }
            }.size(width, height)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfaceVariant)
            .border(if (isCurrent) 3.dp else 1.dp, if (isCurrent) colors.primary else colors.outlineVariant, RoundedCornerShape(6.dp))
            .testTag("frame_$index")
            .semantics {
                contentDescription = description
                role = Role.Button
                selected = isCurrent
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
                                    actions.onMove(index, index - 1)
                                    true
                                },
                            )
                        }
                        if (index < lastIndex) {
                            add(
                                CustomAccessibilityAction(moveLater) {
                                    actions.onMove(index, index + 1)
                                    true
                                },
                            )
                        }
                    }
            }.pointerInput(frameId) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val longPress = awaitLongPressOrCancellation(down.id)
                    if (longPress == null) {
                        // A short press that lifts cleanly is a tap. A press that turned into a scroll is consumed by the list.
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (up != null && !up.pressed && !up.isConsumed) latestActions.onSelect(frameId)
                        return@awaitEachGesture
                    }
                    dragging = true
                    var total = 0f
                    drag(longPress.id) { change ->
                        total += change.positionChange().x
                        dragOffset = total
                        change.consume()
                    }
                    dragging = false
                    dragOffset = 0f
                    if (kotlin.math.abs(total) < menuSlop) {
                        menuOpen = true
                    } else {
                        val target = (latestIndex + (total / stepPx).roundToInt()).coerceIn(0, lastIndex)
                        if (target != latestIndex) latestActions.onMove(latestIndex, target)
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

/** Simple button for sheets. */
@Composable
internal fun SheetTextButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label) }
}
