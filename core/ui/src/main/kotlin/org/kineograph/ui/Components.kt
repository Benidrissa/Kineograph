package org.kineograph.ui

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * An icon button whose touch target always meets the 48 dp accessibility minimum. Giving [selected] a value makes it a toggle or a
 * choice, which looks and reads as on or off. Leaving it null makes it a plain action, which has no such state to tell TalkBack about.
 */
@Composable
public fun KgIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean? = null,
    tint: Color? = null,
) {
    val colors =
        if (selected == true) {
            IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            IconButtonDefaults.iconButtonColors()
        }
    val state = if (selected != null) Modifier.semantics { this.selected = selected } else Modifier
    IconButton(onClick = onClick, modifier = modifier.size(48.dp).then(state), enabled = enabled, colors = colors) {
        Icon(icon, contentDescription = contentDescription, tint = tint ?: LocalContentColor.current)
    }
}

/**
 * Whether the user has asked the system to remove animations. Decorative motion stops when this is true.
 */
@Composable
public fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * A slider standing on end, for the edge of the canvas. Top is the maximum. TalkBack users can set it directly.
 */
@Composable
public fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    stateText: String,
    modifier: Modifier = Modifier,
    onValueChangeFinished: () -> Unit = {},
) {
    var heightPx by remember { mutableFloatStateOf(1f) }
    val latestValue by rememberUpdatedState(onValueChange)
    val latestFinished by rememberUpdatedState(onValueChangeFinished)
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val thumbSize = 20.dp
    Box(
        modifier =
            modifier
                .width(48.dp)
                .fillMaxHeight()
                .onSizeChanged { heightPx = it.height.toFloat().coerceAtLeast(1f) }
                .semantics {
                    contentDescription = label
                    stateDescription = stateText
                    progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
                    setProgress { target ->
                        latestValue(target.coerceIn(0f, 1f))
                        latestFinished()
                        true
                    }
                }.pointerInput(Unit) {
                    detectTapGestures { offset ->
                        latestValue((1f - offset.y / heightPx).coerceIn(0f, 1f))
                        latestFinished()
                    }
                }.pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { latestFinished() },
                        onDragCancel = { latestFinished() },
                    ) { change, _ ->
                        change.consume()
                        latestValue((1f - change.position.y / heightPx).coerceIn(0f, 1f))
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(colors.onSurface.copy(alpha = TRACK_ALPHA)),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .width(4.dp)
                .fillMaxHeight(value.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(2.dp))
                .background(colors.primary),
        )
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset {
                    val thumb = with(density) { thumbSize.toPx() }
                    IntOffset(0, ((1f - value) * (heightPx - thumb)).roundToInt())
                }.size(thumbSize)
                .clip(CircleShape)
                .background(colors.primary),
        )
    }
}

/** A slider that can be touched over at least 48 dp. Left to itself, a slider takes the height of its 44 dp thumb, whatever it is asked for, and that is what can be touched. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun KgSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = kgSliderColors()
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interaction,
        thumb = {
            Box(Modifier.heightIn(min = MIN_TOUCH_TARGET), contentAlignment = Alignment.Center) {
                SliderDefaults.Thumb(interactionSource = interaction, colors = colors)
            }
        },
        modifier = modifier,
    )
}

/** The same for a range. Each thumb is a target of its own for touch and for TalkBack, so each has to be wide enough as well as high enough. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun KgRangeSlider(
    value: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
) {
    val start = remember { MutableInteractionSource() }
    val end = remember { MutableInteractionSource() }
    val colors = kgSliderColors()
    RangeSlider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        colors = colors,
        startInteractionSource = start,
        endInteractionSource = end,
        startThumb = {
            Box(Modifier.size(MIN_TOUCH_TARGET), contentAlignment = Alignment.Center) {
                SliderDefaults.Thumb(interactionSource = start, colors = colors)
            }
        },
        endThumb = {
            Box(Modifier.size(MIN_TOUCH_TARGET), contentAlignment = Alignment.Center) {
                SliderDefaults.Thumb(interactionSource = end, colors = colors)
            }
        },
        // The track leaves a gap as wide as the thumb is measured, which is now far wider than what can be seen of it.
        track = { state -> SliderDefaults.Track(rangeSliderState = state, colors = colors, thumbTrackGapSize = 0.dp) },
        modifier = modifier,
    )
}

/**
 * Slider colours that stay readable in both themes. The default unfilled part of the track uses a container colour
 * that nearly vanishes against a dark sheet.
 */
@Composable
public fun kgSliderColors(): SliderColors {
    val colors = MaterialTheme.colorScheme
    return SliderDefaults.colors(
        inactiveTrackColor = colors.onSurface.copy(alpha = TRACK_ALPHA),
        activeTickColor = colors.onPrimary,
        inactiveTickColor = colors.onSurface.copy(alpha = TICK_ALPHA),
    )
}

/** Enough for the 3:1 that a control's own edge needs, on a sheet and on the quick sliders' panel, in both themes. ThemeContrastTest holds it there. */
internal const val TRACK_ALPHA = 0.55f
private val MIN_TOUCH_TARGET = 48.dp
private const val TICK_ALPHA = 0.7f
