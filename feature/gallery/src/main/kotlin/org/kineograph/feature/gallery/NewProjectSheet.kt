package org.kineograph.feature.gallery

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import org.kineograph.engine.compositor.BackgroundPainter
import org.kineograph.model.Background
import org.kineograph.model.CanvasPreset
import org.kineograph.model.DEFAULT_FPS
import org.kineograph.model.PaperKind
import org.kineograph.ui.ColorPicker
import org.kineograph.ui.DefaultSwatches
import java.io.File

private sealed interface BackgroundChoice {
    data class PaperChoice(val kind: PaperKind) : BackgroundChoice

    data class SolidChoice(val argb: Int) : BackgroundChoice

    data class ImageChoice(val uri: Uri) : BackgroundChoice

    data object TransparentChoice : BackgroundChoice
}

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

@OptIn(ExperimentalLayoutApi::class)
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

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.new_project_title), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(MAX_NAME_LENGTH) },
            label = { Text(stringResource(R.string.new_project_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("new_project_name"),
        )

        Section(stringResource(R.string.new_project_background)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PaperKind.entries.forEach { kind ->
                    BackgroundChip(
                        label = paperName(kind),
                        selected = background == BackgroundChoice.PaperChoice(kind),
                        swatch = Color(BackgroundPainter.paperColor(kind)),
                        onClick = { background = BackgroundChoice.PaperChoice(kind) },
                    )
                }
                BackgroundChip(
                    label = stringResource(R.string.background_solid),
                    selected = background is BackgroundChoice.SolidChoice,
                    swatch = (background as? BackgroundChoice.SolidChoice)?.let { Color(it.argb) },
                    onClick = { pickingColor = true },
                )
                BackgroundChip(
                    label = stringResource(R.string.background_image),
                    selected =
                        (background as? BackgroundChoice.ImageChoice)?.uri != null &&
                            (background as? BackgroundChoice.ImageChoice)?.uri != cameraUri,
                    swatch = null,
                    onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
                BackgroundChip(
                    label = stringResource(R.string.background_camera),
                    selected = cameraUri != null && (background as? BackgroundChoice.ImageChoice)?.uri == cameraUri,
                    swatch = null,
                    onClick = {
                        val directory = File(context.cacheDir, "camera").apply { mkdirs() }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(directory, "background.jpg"))
                        cameraUri = uri
                        camera.launch(uri)
                    },
                )
                BackgroundChip(
                    label = stringResource(R.string.background_transparent),
                    selected = background == BackgroundChoice.TransparentChoice,
                    swatch = null,
                    onClick = { background = BackgroundChoice.TransparentChoice },
                )
            }
        }

        Section(stringResource(R.string.new_project_size)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                offeredPresets.forEach { option ->
                    FilterChip(
                        selected = presetId == option.id,
                        onClick = { presetId = option.id },
                        label = { Text(presetName(option)) },
                    )
                }
                FilterChip(
                    selected = isCustom,
                    onClick = { presetId = CUSTOM_PRESET },
                    label = { Text(stringResource(R.string.preset_custom)) },
                )
            }
            if (isCustom) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
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
        }

        Section(stringResource(R.string.new_project_fps)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FpsWheel(fps, onFpsChange = { fps = it })
                BouncePreview(fps, Modifier.weight(1f).height(120.dp))
            }
            Text(
                pluralStringResource(R.plurals.fps_helper, fps, fps),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp).testTag("fps_helper"),
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
        Spacer(Modifier.height(12.dp))
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
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun BackgroundChip(label: String, selected: Boolean, swatch: Color?, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon =
            swatch?.let { color ->
                {
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.outline)
                            .padding(1.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(color),
                    )
                }
            },
    )
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

private const val CUSTOM_PRESET = "custom"
private const val MAX_NAME_LENGTH = 80
private const val LARGE_CANVAS_PIXELS = 3840L * 2160L
private const val OPAQUE = 0xFF000000.toInt()
