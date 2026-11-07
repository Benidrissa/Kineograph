package org.kineograph.feature.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.kineograph.engine.brush.BrushPresets
import org.kineograph.engine.session.DrawingSession
import org.kineograph.engine.session.SessionState
import org.kineograph.feature.editor.canvas.CanvasTool
import org.kineograph.model.Background
import org.kineograph.model.Layer
import org.kineograph.model.MAX_FPS
import org.kineograph.model.MIN_FPS
import org.kineograph.model.OnionSkinSettings
import org.kineograph.model.PaperKind
import org.kineograph.model.Project
import org.kineograph.ui.ColorPicker
import org.kineograph.ui.DefaultSwatches
import org.kineograph.ui.KgIconButton
import org.kineograph.ui.KgIcons
import org.kineograph.ui.kgSliderColors
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorBottomSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LabelledSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    testTag: String? = null,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val interaction = remember { MutableInteractionSource() }
            val colors = kgSliderColors()
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = range,
                steps = steps,
                onValueChangeFinished = onValueChangeFinished,
                colors = colors,
                interactionSource = interaction,
                // Left to itself, a slider takes the height of its 44 dp thumb, whatever it is asked for, and that is what can be touched.
                thumb = {
                    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                        SliderDefaults.Thumb(interactionSource = interaction, colors = colors)
                    }
                },
                // It would read out a bare number with no unit, and with the wrong decimal mark in some languages.
                modifier =
                    Modifier.semantics {
                        contentDescription = label
                        stateDescription = valueText
                    },
            )
        }
    }
}

@Composable
internal fun LabelledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolOptionsSheet(tools: ToolState, onChange: ((ToolState) -> ToolState) -> Unit, onDismiss: () -> Unit) {
    EditorBottomSheet(onDismiss) {
        when (tools.tool) {
            CanvasTool.BRUSH -> {
                Text(stringResource(R.string.tool_brush), style = MaterialTheme.typography.titleLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BrushPresets.DRAWING.forEach { brush ->
                        FilterChip(
                            selected = tools.brushId == brush.id,
                            onClick = { onChange { it.copy(brushId = brush.id) } },
                            label = { Text(brushName(brush.id)) },
                            modifier = Modifier.testTag("brush_${brush.id}"),
                        )
                    }
                }
                SizeAndOpacity(tools, onChange)
                StrokeFeel(tools, onChange)
            }

            CanvasTool.ERASER -> {
                Text(stringResource(R.string.tool_eraser), style = MaterialTheme.typography.titleLarge)
                SizeAndOpacity(tools, onChange)
                StrokeFeel(tools, onChange)
            }

            CanvasTool.FILL -> {
                Text(stringResource(R.string.tool_fill), style = MaterialTheme.typography.titleLarge)
                LabelledSlider(
                    label = stringResource(R.string.fill_tolerance),
                    value = tools.fillTolerance.toFloat(),
                    valueText = tools.fillTolerance.toString(),
                    range = 0f..128f,
                    onChange = { value -> onChange { it.copy(fillTolerance = value.roundToInt()) } },
                )
                LabelledSlider(
                    label = stringResource(R.string.fill_gap_close),
                    value = tools.fillGapClose.toFloat(),
                    valueText = if (tools.fillGapClose ==
                        0
                    ) {
                        stringResource(R.string.fill_gap_off)
                    } else {
                        stringResource(R.string.pixels_value, tools.fillGapClose)
                    },
                    range = 0f..24f,
                    onChange = { value -> onChange { it.copy(fillGapClose = value.roundToInt()) } },
                )
                Text(
                    stringResource(R.string.fill_gap_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LabelledSwitch(stringResource(R.string.fill_sample_all), tools.fillSampleAllLayers) { value ->
                    onChange {
                        it.copy(fillSampleAllLayers = value)
                    }
                }
            }

            CanvasTool.LASSO -> {
                Text(stringResource(R.string.tool_lasso), style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun SizeAndOpacity(tools: ToolState, onChange: ((ToolState) -> ToolState) -> Unit) {
    LabelledSlider(
        label = stringResource(R.string.slider_size),
        value = ToolState.sizeToSlider(tools.activeSize),
        valueText = stringResource(R.string.pixels_value, tools.activeSize.roundToInt()),
        onChange = { value -> onChange { it.withActiveSize(ToolState.sliderToSize(value)) } },
    )
    LabelledSlider(
        label = stringResource(R.string.slider_opacity),
        value = tools.activeOpacity,
        valueText = "${(tools.activeOpacity * 100f).roundToInt()}%",
        onChange = { value -> onChange { it.withActiveOpacity(value) } },
    )
}

@Composable
private fun StrokeFeel(tools: ToolState, onChange: ((ToolState) -> ToolState) -> Unit) {
    LabelledSlider(
        label = stringResource(R.string.slider_smoothing),
        value = tools.smoothing.toFloat(),
        valueText = tools.smoothing.toString(),
        range = 0f..100f,
        onChange = { value -> onChange { it.copy(smoothing = value.roundToInt()) } },
    )
    LabelledSwitch(stringResource(R.string.finger_pressure), tools.simulateFingerPressure) { value ->
        onChange {
            it.copy(simulateFingerPressure = value)
        }
    }
}

@Composable
private fun brushName(id: String): String =
    stringResource(
        when (id) {
            BrushPresets.PEN.id -> R.string.brush_pen
            BrushPresets.PENCIL.id -> R.string.brush_pencil
            BrushPresets.MARKER.id -> R.string.brush_marker
            else -> R.string.tool_brush
        },
    )

@Composable
internal fun ColorSheet(tools: ToolState, onColor: (Int) -> Unit, onOpacity: (Float) -> Unit, onDismiss: () -> Unit) {
    EditorBottomSheet(onDismiss) {
        Text(stringResource(R.string.tool_color), style = MaterialTheme.typography.titleLarge)
        val alpha = (tools.activeOpacity * 255f + 0.5f).toInt()
        // The opacity slider in the picker is the active brush's opacity: there is one setting, not two to confuse.
        val shown = (tools.colorArgb and 0xFFFFFF) or (alpha shl 24)
        ColorPicker(
            argb = shown,
            onColorChange = { argb ->
                val newAlpha = argb ushr 24
                if (newAlpha != alpha) onOpacity(newAlpha / 255f)
                if (argb and 0xFFFFFF != tools.colorArgb and 0xFFFFFF) onColor(argb or OPAQUE)
            },
            recentColors = tools.recentColors.ifEmpty { DefaultSwatches },
        )
    }
}

@Composable
internal fun LayersSheet(state: SessionState, session: DrawingSession, thumbnails: ThumbnailLoader, onDismiss: () -> Unit) {
    val project = state.project
    val defaultName = stringResource(R.string.layer_default_name, project.layers.size + 1)
    val copySuffix = stringResource(R.string.layer_copy_suffix)
    var renaming by remember { mutableStateOf<Layer?>(null) }
    EditorBottomSheet(onDismiss) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.layers_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            KgIconButton(
                KgIcons.Add,
                stringResource(R.string.layer_add),
                { session.addLayer(defaultName) },
                modifier = Modifier.testTag("add_layer"),
            )
        }
        // The list shows the top of the stack first, just as the layers appear on the canvas.
        project.layers.asReversed().forEachIndexed { reversedIndex, layer ->
            val stackIndex = project.layers.lastIndex - reversedIndex
            LayerRow(
                layer = layer,
                isActive = layer.id == state.layerId,
                canMergeDown = stackIndex > 0,
                canDelete = project.layers.size > 1,
                project = project,
                frameId = state.frameId,
                thumbnails = thumbnails,
                onSelect = { session.selectLayer(layer.id) },
                onVisibility = { session.setLayerVisible(layer.id, !layer.visible) },
                onLock = { session.setLayerLocked(layer.id, !layer.locked) },
                onRename = { renaming = layer },
                onDuplicate = { session.duplicateLayer(layer.id, "${layer.name} $copySuffix") },
                onMergeDown = { session.mergeLayerDown(layer.id) },
                onDelete = { session.deleteLayer(layer.id) },
                onDragBy = { rows ->
                    // Rows count down the list, but the stack counts up.
                    val target = (stackIndex - rows).coerceIn(0, project.layers.lastIndex)
                    if (target != stackIndex) session.moveLayer(stackIndex, target)
                },
            )
        }
        HorizontalDivider()
        project.layer(state.layerId)?.let { active ->
            var opacity by remember(active.id) { mutableFloatStateOf(active.opacity) }
            LabelledSlider(
                label = stringResource(R.string.layer_opacity, active.name),
                value = opacity,
                valueText = "${(opacity * 100f).roundToInt()}%",
                onValueChangeFinished = { session.commitLayerOpacity(active.id, opacity) },
                onChange = {
                    opacity = it
                    session.previewLayerOpacity(active.id, it)
                },
            )
        }
    }
    renaming?.let { layer ->
        NameDialog(stringResource(R.string.layer_rename), layer.name, onDismiss = { renaming = null }) { name ->
            session.renameLayer(layer.id, name)
            renaming = null
        }
    }
}

@Composable
private fun LayerRow(
    layer: Layer,
    isActive: Boolean,
    canMergeDown: Boolean,
    canDelete: Boolean,
    project: Project,
    frameId: String,
    thumbnails: ThumbnailLoader,
    onSelect: () -> Unit,
    onVisibility: () -> Unit,
    onLock: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onMergeDown: () -> Unit,
    onDelete: () -> Unit,
    onDragBy: (rows: Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val rowHeight = 64.dp
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
    val thumbnail = rememberLayerThumbnail(thumbnails, project, frameId, layer.id)
    val latestDrag by rememberUpdatedState(onDragBy)
    Row(
        Modifier
            .zIndex(if (dragY != 0f) 1f else 0f)
            .graphicsLayer { translationY = dragY }
            .fillMaxWidth()
            .height(rowHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isActive) colors.primaryContainer else colors.surfaceContainerHigh)
            .clickable(onClick = onSelect)
            .semantics {
                role = Role.Button
                selected = isActive
            }.testTag("layer_row_${layer.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp, 64.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            val rows = (dragY / rowHeightPx).roundToInt()
                            dragY = 0f
                            if (rows != 0) latestDrag(rows)
                        },
                        onDragCancel = { dragY = 0f },
                    ) { change, amount ->
                        change.consume()
                        dragY += amount.y
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(KgIcons.Handle, contentDescription = stringResource(R.string.layer_drag), tint = colors.onSurfaceVariant)
        }
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White)
                .border(1.dp, colors.outlineVariant, RoundedCornerShape(4.dp)),
        ) {
            thumbnail?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.size(48.dp)) }
        }
        Text(layer.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        KgIconButton(
            if (layer.visible) KgIcons.Eye else KgIcons.EyeOff,
            stringResource(if (layer.visible) R.string.layer_hide else R.string.layer_show, layer.name),
            onVisibility,
        )
        KgIconButton(
            if (layer.locked) KgIcons.Lock else KgIcons.Unlock,
            stringResource(if (layer.locked) R.string.layer_unlock else R.string.layer_lock, layer.name),
            onLock,
        )
        Box {
            KgIconButton(KgIcons.More, stringResource(R.string.layer_menu, layer.name), { menuOpen = true })
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                SheetMenuItem(KgIcons.Rename, stringResource(R.string.layer_rename)) {
                    menuOpen = false
                    onRename()
                }
                SheetMenuItem(KgIcons.Duplicate, stringResource(R.string.layer_duplicate)) {
                    menuOpen = false
                    onDuplicate()
                }
                SheetMenuItem(KgIcons.Merge, stringResource(R.string.layer_merge_down), enabled = canMergeDown) {
                    menuOpen = false
                    onMergeDown()
                }
                SheetMenuItem(KgIcons.Trash, stringResource(R.string.layer_delete), enabled = canDelete) {
                    menuOpen = false
                    onDelete()
                }
            }
        }
    }
}

@Composable
private fun SheetMenuItem(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, enabled = enabled, onClick = onClick)
}

@Composable
internal fun NameDialog(title: String, current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(80) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun OnionSkinSheet(settings: OnionSkinSettings, session: DrawingSession, onDismiss: () -> Unit) {
    fun change(update: (OnionSkinSettings) -> OnionSkinSettings) {
        session.updateSettings { it.copy(onionSkin = update(it.onionSkin)) }
    }
    EditorBottomSheet(onDismiss) {
        Text(stringResource(R.string.editor_onion_skin), style = MaterialTheme.typography.titleLarge)
        LabelledSwitch(stringResource(R.string.onion_enabled), settings.enabled) { value -> change { it.copy(enabled = value) } }
        LabelledSlider(
            label = stringResource(R.string.onion_previous),
            value = settings.previousFrames.toFloat(),
            valueText = settings.previousFrames.toString(),
            range = 0f..OnionSkinSettings.MAX_GHOSTS_PER_SIDE.toFloat(),
            steps = OnionSkinSettings.MAX_GHOSTS_PER_SIDE - 1,
            onChange = { value -> change { it.copy(previousFrames = value.roundToInt()) } },
        )
        LabelledSlider(
            label = stringResource(R.string.onion_next),
            value = settings.nextFrames.toFloat(),
            valueText = settings.nextFrames.toString(),
            range = 0f..OnionSkinSettings.MAX_GHOSTS_PER_SIDE.toFloat(),
            steps = OnionSkinSettings.MAX_GHOSTS_PER_SIDE - 1,
            onChange = { value -> change { it.copy(nextFrames = value.roundToInt()) } },
        )
        LabelledSlider(
            label = stringResource(R.string.slider_opacity),
            value = settings.opacity,
            valueText = "${(settings.opacity * 100f).roundToInt()}%",
            onChange = { value -> change { it.copy(opacity = value) } },
        )
        LabelledSlider(
            label = stringResource(R.string.onion_falloff),
            value = settings.falloff,
            valueText = "${(settings.falloff * 100f).roundToInt()}%",
            onChange = { value -> change { it.copy(falloff = value) } },
        )
        LabelledSwitch(stringResource(R.string.onion_tint), settings.tint) { value -> change { it.copy(tint = value) } }
        LabelledSwitch(stringResource(R.string.onion_current_layer), settings.currentLayerOnly) { value ->
            change {
                it.copy(currentLayerOnly = value)
            }
        }
        LabelledSwitch(stringResource(R.string.onion_wrap), settings.wrapAround) { value -> change { it.copy(wrapAround = value) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProjectSettingsSheet(project: Project, session: DrawingSession, viewModel: EditorViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(project.name) }
    var fps by remember { mutableIntStateOf(project.fps) }
    var widthText by remember { mutableStateOf(project.width.toString()) }
    var heightText by remember { mutableStateOf(project.height.toString()) }
    var pickingColor by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            if (uri != null) viewModel.setBackgroundImage(uri, context.contentResolver)
        }
    EditorBottomSheet(onDismiss) {
        Text(stringResource(R.string.editor_project_settings), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it.take(80)
                if (name.isNotBlank()) session.rename(name.trim())
            },
            label = { Text(stringResource(R.string.project_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LabelledSlider(
            label = stringResource(R.string.project_fps),
            value = fps.toFloat(),
            valueText = stringResource(R.string.fps_value, fps),
            range = MIN_FPS.toFloat()..MAX_FPS.toFloat(),
            onChange = {
                fps = it.roundToInt()
                viewModel.setFps(fps)
            },
        )
        Text(stringResource(R.string.project_size), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SizeField(stringResource(R.string.project_width), widthText, Modifier.weight(1f)) { widthText = it }
            SizeField(stringResource(R.string.project_height), heightText, Modifier.weight(1f)) { heightText = it }
            val newWidth = widthText.toIntOrNull()
            val newHeight = heightText.toIntOrNull()
            val valid = newWidth != null && newHeight != null && newWidth in 1..MAX_EDGE && newHeight in 1..MAX_EDGE
            val changed = newWidth != project.width || newHeight != project.height
            Button(enabled = valid && changed, onClick = {
                if (newWidth != null &&
                    newHeight != null
                ) {
                    session.resizeCanvas(newWidth, newHeight)
                }
            }) {
                Text(stringResource(R.string.project_apply))
            }
        }
        Text(
            stringResource(R.string.project_resize_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.project_background), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val papers =
                listOf(
                    PaperKind.WHITE to R.string.paper_white,
                    PaperKind.OFF_WHITE to R.string.paper_off_white,
                    PaperKind.GRID to R.string.paper_grid,
                    PaperKind.DOTS to R.string.paper_dots,
                    PaperKind.DARK to R.string.paper_dark,
                )
            papers.forEach { (kind, label) ->
                FilterChip(
                    selected = project.background == Background.Paper(kind),
                    onClick = { viewModel.setBackground(Background.Paper(kind)) },
                    label = { Text(stringResource(label)) },
                )
            }
            FilterChip(
                selected = project.background is Background.Color,
                onClick = { pickingColor = true },
                label = { Text(stringResource(R.string.background_color)) },
            )
            FilterChip(
                selected = project.background is Background.Image,
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                label = { Text(stringResource(R.string.background_image)) },
            )
            FilterChip(
                selected = project.background == Background.Transparent,
                onClick = { viewModel.setBackground(Background.Transparent) },
                label = { Text(stringResource(R.string.background_transparent)) },
            )
        }
        val grid = project.settings.grid
        LabelledSwitch(stringResource(R.string.editor_grid), grid.enabled) { value ->
            session.updateSettings {
                it.copy(grid = it.grid.copy(enabled = value))
            }
        }
        if (grid.enabled) {
            LabelledSlider(
                label = stringResource(R.string.grid_spacing),
                value = grid.spacingPx.toFloat(),
                valueText = stringResource(R.string.pixels_value, grid.spacingPx),
                range = 4f..256f,
                onChange = { value -> session.updateSettings { it.copy(grid = it.grid.copy(spacingPx = value.roundToInt())) } },
            )
        }
    }
    if (pickingColor) {
        var chosen by remember { mutableIntStateOf((project.background as? Background.Color)?.argb ?: DefaultSwatches[3]) }
        AlertDialog(
            onDismissRequest = { pickingColor = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setBackground(Background.Color(chosen))
                    pickingColor = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingColor = false }) { Text(stringResource(R.string.action_cancel)) } },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ColorPicker(chosen, { chosen = it or OPAQUE }, DefaultSwatches, showOpacity = false)
                }
            },
        )
    }
}

@Composable
private fun SizeField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(5)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

private const val OPAQUE = 0xFF000000.toInt()
private const val MAX_EDGE = 8192
