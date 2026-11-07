package org.kineograph.feature.gallery

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import org.kineograph.model.Background
import org.kineograph.model.CanvasPreset
import org.kineograph.model.DEFAULT_FPS
import org.kineograph.model.PaperKind
import org.kineograph.ui.ColorPicker
import org.kineograph.ui.DefaultSwatches
import org.kineograph.ui.KgIcons
import org.kineograph.ui.OptionGroupHeading
import org.kineograph.ui.VisualDropdown
import org.kineograph.ui.VisualOption
import java.io.File

/** The form for starting a new project. Every choice here can be changed later in the project's settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun NewProjectSheet(
    defaultNumber: Int,
    canEncode: (width: Int, height: Int) -> Boolean,
    onDismiss: () -> Unit,
    onCreate: (NewProjectRequest) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        NewProjectForm(
            defaultNumber = defaultNumber,
            canEncode = canEncode,
            onCreate = { request ->
                scope.launch {
                    sheetState.hide()
                    onCreate(request)
                }
            },
        )
    }
}

/**
 * Two drop-downs, each showing a picture of every option, and a live preview that shows the result of all the choices
 * together. With the options folded away, the whole form fits on a phone without scrolling.
 */
@Composable
internal fun NewProjectForm(defaultNumber: Int, canEncode: (Int, Int) -> Boolean, onCreate: (NewProjectRequest) -> Unit) {
    val defaultName = stringResource(R.string.default_project_name, defaultNumber)
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var fps by rememberSaveable { mutableIntStateOf(DEFAULT_FPS) }
    var presetId by rememberSaveable { mutableStateOf(CanvasPreset.DEFAULT.id) }
    var customWidth by rememberSaveable { mutableStateOf("1280") }
    var customHeight by rememberSaveable { mutableStateOf("720") }
    var background by remember { mutableStateOf<BackgroundChoice>(BackgroundChoice.PaperChoice(PaperKind.WHITE)) }
    var pickingColor by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val offeredPresets = remember { CanvasPreset.entries.filter { !it.requiresEncoderCheck || canEncode(it.width, it.height) } }
    val isCustom = presetId == CUSTOM_PRESET
    val preset = offeredPresets.firstOrNull { it.id == presetId }
    val widthValue = if (isCustom) customWidth.toIntOrNull() else preset?.width
    val heightValue = if (isCustom) customHeight.toIntOrNull() else preset?.height
    val sizeIsValid =
        widthValue != null && heightValue != null && widthValue in 1..CanvasPreset.MAX_CUSTOM_EDGE &&
            heightValue in 1..CanvasPreset.MAX_CUSTOM_EDGE

    val photoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) background = BackgroundChoice.ImageChoice(uri)
        }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            val uri = cameraUri
            if (saved && uri != null) background = BackgroundChoice.ImageChoice(uri)
        }
    val picture by rememberPictureThumbnail((background as? BackgroundChoice.ImageChoice)?.uri, context.contentResolver)

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.new_project_title), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(MAX_NAME_LENGTH) },
            label = { Text(stringResource(R.string.new_project_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("new_project_name"),
        )

        BackgroundDropdown(
            selected = background,
            picture = picture,
            cameraSelected = cameraUri != null && (background as? BackgroundChoice.ImageChoice)?.uri == cameraUri,
            onSelect = { background = it },
            onPickColor = { pickingColor = true },
            onPickImage = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onTakePhoto = {
                val directory = File(context.cacheDir, "camera").apply { mkdirs() }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(directory, "background.jpg"))
                cameraUri = uri
                camera.launch(uri)
            },
        )

        CanvasSizeDropdown(
            presets = offeredPresets,
            selectedId = presetId,
            width = widthValue,
            height = heightValue,
            onSelect = { presetId = it },
        )
        if (isCustom) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SizeField(stringResource(R.string.size_width), customWidth, Modifier.weight(1f)) { customWidth = it }
                SizeField(stringResource(R.string.size_height), customHeight, Modifier.weight(1f)) { customHeight = it }
            }
        }
        if (!sizeIsValid) {
            Text(
                stringResource(R.string.size_invalid, CanvasPreset.MAX_CUSTOM_EDGE),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (widthValue.toLong() * heightValue > LARGE_CANVAS_PIXELS) {
            Text(
                stringResource(R.string.size_large_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.new_project_fps),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FpsWheel(fps, onFpsChange = { fps = it })
                ProjectPreview(
                    fps = fps,
                    canvasWidth = if (sizeIsValid) widthValue else CanvasPreset.DEFAULT.width,
                    canvasHeight = if (sizeIsValid) heightValue else CanvasPreset.DEFAULT.height,
                    background = background,
                    image = picture,
                    modifier = Modifier.weight(1f).height(120.dp),
                )
            }
            Text(
                pluralStringResource(R.plurals.fps_helper, fps, fps),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("fps_helper"),
            )
        }

        Button(
            enabled = sizeIsValid && name.isNotBlank(),
            onClick = {
                val choice = background
                onCreate(
                    NewProjectRequest(
                        name = name.trim(),
                        width = widthValue ?: CanvasPreset.DEFAULT.width,
                        height = heightValue ?: CanvasPreset.DEFAULT.height,
                        fps = fps,
                        background =
                            when (choice) {
                                is BackgroundChoice.PaperChoice -> Background.Paper(choice.kind)
                                is BackgroundChoice.SolidChoice -> Background.Color(choice.argb)
                                is BackgroundChoice.ImageChoice -> Background.Paper(PaperKind.WHITE)
                                BackgroundChoice.TransparentChoice -> Background.Transparent
                            },
                        backgroundUri = (choice as? BackgroundChoice.ImageChoice)?.uri,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth().height(56.dp).testTag("create_project"),
        ) {
            Text(stringResource(R.string.new_project_create))
        }
        Spacer(Modifier.height(8.dp))
    }

    if (pickingColor) {
        var chosen by remember { mutableIntStateOf((background as? BackgroundChoice.SolidChoice)?.argb ?: DefaultSwatches[3]) }
        AlertDialog(
            onDismissRequest = { pickingColor = false },
            confirmButton = {
                TextButton(onClick = {
                    background = BackgroundChoice.SolidChoice(chosen)
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
private fun BackgroundDropdown(
    selected: BackgroundChoice,
    picture: ImageBitmap?,
    cameraSelected: Boolean,
    onSelect: (BackgroundChoice) -> Unit,
    onPickColor: () -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val pickAndClose: (() -> Unit) -> Unit = { action ->
        expanded = false
        action()
    }
    VisualDropdown(
        label = stringResource(R.string.new_project_background),
        valueText = backgroundName(selected, cameraSelected),
        expanded = expanded,
        onExpandedChange = { expanded = it },
        tag = "background_dropdown",
        leading = { BackgroundSwatch(selected, picture) },
    ) {
        PaperKind.entries.forEach { kind ->
            val choice = BackgroundChoice.PaperChoice(kind)
            VisualOption(
                title = paperName(kind),
                hint = paperHint(kind),
                selected = selected == choice,
                tag = "background_paper_${kind.name.lowercase()}",
                preview = { BackgroundSwatch(choice) },
                onClick = { pickAndClose { onSelect(choice) } },
            )
        }
        VisualOption(
            title = stringResource(R.string.background_solid),
            hint = stringResource(R.string.background_hint_solid),
            selected = selected is BackgroundChoice.SolidChoice,
            tag = "background_color",
            preview = {
                if (selected is BackgroundChoice.SolidChoice) BackgroundSwatch(selected) else BackgroundSwatch(null, icon = KgIcons.Palette)
            },
            onClick = { pickAndClose(onPickColor) },
        )
        VisualOption(
            title = stringResource(R.string.background_image),
            hint = stringResource(R.string.background_hint_image),
            selected = selected is BackgroundChoice.ImageChoice && !cameraSelected,
            tag = "background_image",
            preview = {
                if (selected is BackgroundChoice.ImageChoice && !cameraSelected && picture != null) {
                    BackgroundSwatch(selected, picture)
                } else {
                    BackgroundSwatch(null, icon = KgIcons.Image)
                }
            },
            onClick = { pickAndClose(onPickImage) },
        )
        VisualOption(
            title = stringResource(R.string.background_camera),
            hint = stringResource(R.string.background_hint_camera),
            selected = cameraSelected,
            tag = "background_camera",
            preview = {
                if (selected is BackgroundChoice.ImageChoice && cameraSelected && picture != null) {
                    BackgroundSwatch(selected, picture)
                } else {
                    BackgroundSwatch(null, icon = KgIcons.Camera)
                }
            },
            onClick = { pickAndClose(onTakePhoto) },
        )
        VisualOption(
            title = stringResource(R.string.background_transparent),
            hint = stringResource(R.string.background_hint_transparent),
            selected = selected == BackgroundChoice.TransparentChoice,
            tag = "background_transparent",
            preview = { BackgroundSwatch(BackgroundChoice.TransparentChoice) },
            onClick = { pickAndClose { onSelect(BackgroundChoice.TransparentChoice) } },
        )
    }
}

@Composable
private fun CanvasSizeDropdown(presets: List<CanvasPreset>, selectedId: String, width: Int?, height: Int?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = presets.firstOrNull { it.id == selectedId }
    val choose: (String) -> Unit = {
        expanded = false
        onSelect(it)
    }
    VisualDropdown(
        label = stringResource(R.string.new_project_size),
        valueText = if (selected != null) presetName(selected) else stringResource(R.string.preset_custom),
        expanded = expanded,
        onExpandedChange = { expanded = it },
        tag = "canvas_size_dropdown",
        leading = { CanvasShapeGlyph(width ?: 1, height ?: 1, showPixels = selected?.isPixelArt() == true) },
    ) {
        fun group(heading: Int, members: List<CanvasPreset>): Pair<Int, List<CanvasPreset>> = heading to members.filter { it in presets }
        val groups =
            listOf(
                group(
                    R.string.canvas_group_video,
                    listOf(CanvasPreset.HD, CanvasPreset.FULL_HD, CanvasPreset.UHD_4K, CanvasPreset.FOUR_THREE),
                ),
                group(R.string.canvas_group_social, listOf(CanvasPreset.VERTICAL, CanvasPreset.SQUARE)),
                group(R.string.canvas_group_pixel, listOf(CanvasPreset.PIXEL_64, CanvasPreset.PIXEL_128, CanvasPreset.PIXEL_256)),
            )
        groups.forEach { (heading, members) ->
            OptionGroupHeading(stringResource(heading))
            members.forEach { option ->
                VisualOption(
                    title = presetName(option),
                    hint = presetHint(option),
                    selected = selectedId == option.id,
                    tag = "preset_${option.id}",
                    preview = { CanvasShapeGlyph(option.width, option.height, showPixels = option.isPixelArt()) },
                    onClick = { choose(option.id) },
                )
            }
        }
        OptionGroupHeading(stringResource(R.string.canvas_group_other))
        VisualOption(
            title = stringResource(R.string.preset_custom),
            hint = stringResource(R.string.preset_hint_custom),
            selected = selectedId == CUSTOM_PRESET,
            tag = "preset_custom",
            preview = { CanvasShapeGlyph(CUSTOM_GLYPH_WIDTH, CUSTOM_GLYPH_HEIGHT) },
            onClick = { choose(CUSTOM_PRESET) },
        )
    }
}

private fun CanvasPreset.isPixelArt(): Boolean =
    this == CanvasPreset.PIXEL_64 || this == CanvasPreset.PIXEL_128 || this == CanvasPreset.PIXEL_256

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

@Composable
private fun backgroundName(choice: BackgroundChoice, fromCamera: Boolean): String =
    when (choice) {
        is BackgroundChoice.PaperChoice -> paperName(choice.kind)
        is BackgroundChoice.SolidChoice -> stringResource(R.string.background_solid)
        is BackgroundChoice.ImageChoice -> stringResource(if (fromCamera) R.string.background_camera else R.string.background_image)
        BackgroundChoice.TransparentChoice -> stringResource(R.string.background_transparent)
    }

@Composable
private fun paperName(kind: PaperKind): String =
    stringResource(
        when (kind) {
            PaperKind.WHITE -> R.string.paper_white
            PaperKind.OFF_WHITE -> R.string.paper_off_white
            PaperKind.GRID -> R.string.paper_grid
            PaperKind.DOTS -> R.string.paper_dots
            PaperKind.DARK -> R.string.paper_dark
        },
    )

@Composable
private fun paperHint(kind: PaperKind): String =
    stringResource(
        when (kind) {
            PaperKind.WHITE -> R.string.paper_hint_white
            PaperKind.OFF_WHITE -> R.string.paper_hint_off_white
            PaperKind.GRID -> R.string.paper_hint_grid
            PaperKind.DOTS -> R.string.paper_hint_dots
            PaperKind.DARK -> R.string.paper_hint_dark
        },
    )

@Composable
private fun presetName(preset: CanvasPreset): String =
    stringResource(
        when (preset) {
            CanvasPreset.HD -> R.string.preset_hd
            CanvasPreset.FULL_HD -> R.string.preset_full_hd
            CanvasPreset.VERTICAL -> R.string.preset_vertical
            CanvasPreset.SQUARE -> R.string.preset_square
            CanvasPreset.FOUR_THREE -> R.string.preset_four_three
            CanvasPreset.UHD_4K -> R.string.preset_4k
            CanvasPreset.PIXEL_64 -> R.string.preset_pixel_64
            CanvasPreset.PIXEL_128 -> R.string.preset_pixel_128
            CanvasPreset.PIXEL_256 -> R.string.preset_pixel_256
        },
    )

@Composable
private fun presetHint(preset: CanvasPreset): String =
    stringResource(
        when (preset) {
            CanvasPreset.HD -> R.string.preset_hint_hd
            CanvasPreset.FULL_HD -> R.string.preset_hint_full_hd
            CanvasPreset.VERTICAL -> R.string.preset_hint_vertical
            CanvasPreset.SQUARE -> R.string.preset_hint_square
            CanvasPreset.FOUR_THREE -> R.string.preset_hint_four_three
            CanvasPreset.UHD_4K -> R.string.preset_hint_4k
            CanvasPreset.PIXEL_64 -> R.string.preset_hint_pixel_64
            CanvasPreset.PIXEL_128 -> R.string.preset_hint_pixel_128
            CanvasPreset.PIXEL_256 -> R.string.preset_hint_pixel_256
        },
    )

private const val CUSTOM_PRESET = "custom"
private const val MAX_NAME_LENGTH = 80
private const val LARGE_CANVAS_PIXELS = 3840L * 2160L
private const val OPAQUE = 0xFF000000.toInt()
private const val CUSTOM_GLYPH_WIDTH = 5
private const val CUSTOM_GLYPH_HEIGHT = 4
