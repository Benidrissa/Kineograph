package org.kineograph.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import android.graphics.Color as AndroidColor

/**
 * The colour picker: a hue ring around a saturation and brightness square, a hex field, an opacity slider, and
 * recent colours. [argb] is the current colour, reported back through [onColorChange], which a drag calls many times on its way
 * to the colour it ends on. [onColorCommit] says the user has settled on one, once per tap, drag, swatch or complete hex value.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun ColorPicker(
    argb: Int,
    onColorChange: (Int) -> Unit,
    recentColors: List<Int>,
    modifier: Modifier = Modifier,
    showOpacity: Boolean = true,
    onColorCommit: () -> Unit = {},
) {
    // Hue must survive black and white, where it cannot be recovered from the colour.
    var hue by remember { mutableFloatStateOf(hsvOf(argb)[0]) }
    var saturation by remember { mutableFloatStateOf(hsvOf(argb)[1]) }
    var brightness by remember { mutableFloatStateOf(hsvOf(argb)[2]) }
    val alpha = AndroidColor.alpha(argb)

    LaunchedEffect(argb) {
        val current = AndroidColor.HSVToColor(alpha, floatArrayOf(hue, saturation, brightness))
        if (current != argb) {
            val hsv = hsvOf(argb)
            if (hsv[1] > 0f && hsv[2] > 0f) hue = hsv[0]
            saturation = hsv[1]
            brightness = hsv[2]
        }
    }

    fun publish() {
        onColorChange(AndroidColor.HSVToColor(alpha, floatArrayOf(hue, saturation, brightness)))
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HueRingAndSquare(
            hue = hue,
            saturation = saturation,
            brightness = brightness,
            onHue = {
                hue = it
                publish()
            },
            onSquare = { s, v ->
                saturation = s
                brightness = v
                publish()
            },
            onCommit = onColorCommit,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = 280.dp)
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        HexField(argb, onColorChange, onColorCommit)
        if (showOpacity) {
            val opacityLabel = stringResource(R.string.color_opacity)
            Column {
                Text("$opacityLabel ${(alpha * 100f / 255f).toInt()}%", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = alpha / 255f,
                    // Only the alpha changes. Going through hue and back could shift the colour by a level.
                    onValueChange = { onColorChange((argb and RGB_MASK) or ((it * 255f + 0.5f).toInt() shl 24)) },
                    colors = kgSliderColors(),
                    modifier = Modifier.semantics { contentDescription = opacityLabel },
                )
            }
        }
        if (recentColors.isNotEmpty()) {
            Text(stringResource(R.string.color_recent), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recentColors.forEach { color ->
                    // A swatch is a colour, not an opacity, so it leaves the opacity as it is, and matches whatever that is.
                    val rgb = color and RGB_MASK
                    ColorSwatch(color, selected = rgb == argb and RGB_MASK) {
                        onColorChange(rgb or (argb and ALPHA_MASK))
                        onColorCommit()
                    }
                }
            }
        }
    }
}

/** A round colour sample that can be tapped. */
@Composable
public fun ColorSwatch(argb: Int, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val description = "#%06X".format(argb and 0xFFFFFF)
    Box(
        modifier
            .size(48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(argb))
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
        )
    }
}

@Composable
private fun HueRingAndSquare(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onHue: (Float) -> Unit,
    onSquare: (Float, Float) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier,
) {
    var size by remember { mutableStateOf(Size.Zero) }
    val hueLabel = stringResource(R.string.color_hue_ring)
    // The gestures below keep running from the first touch on, and restarting them for each colour would cut a drag short.
    // So they read these, or they would go on comparing against the colour from that first touch.
    val latestHue by rememberUpdatedState(onHue)
    val latestSquare by rememberUpdatedState(onSquare)
    val latestCommit by rememberUpdatedState(onCommit)

    fun handle(position: Offset, grabbedRing: Boolean?): Boolean {
        val radius = min(size.width, size.height) / 2f
        if (radius <= 0f) return false
        val dx = position.x - size.width / 2f
        val dy = position.y - size.height / 2f
        val onRing = grabbedRing ?: (hypot(dx, dy) >= radius * RING_INNER)
        if (onRing) {
            var degrees = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
            if (degrees < 0f) degrees += 360f
            latestHue(degrees)
        } else {
            val half = radius * SQUARE_HALF
            latestSquare(((dx + half) / (half * 2f)).coerceIn(0f, 1f), (1f - (dy + half) / (half * 2f)).coerceIn(0f, 1f))
        }
        return onRing
    }

    var grabbedRing by remember { mutableStateOf<Boolean?>(null) }
    Canvas(
        modifier
            .onSizeChanged { size = Size(it.width.toFloat(), it.height.toFloat()) }
            .semantics { contentDescription = hueLabel }
            .pointerInput(Unit) {
                detectTapGestures {
                    handle(it, null)
                    latestCommit()
                }
            }.pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { grabbedRing = handle(it, null) },
                    onDragEnd = {
                        grabbedRing = null
                        latestCommit()
                    },
                    // The colour has already changed by now, so it counts as much as a drag that ended normally.
                    onDragCancel = {
                        grabbedRing = null
                        latestCommit()
                    },
                ) { change, _ ->
                    change.consume()
                    handle(change.position, grabbedRing)
                }
            },
    ) {
        val radius = min(this.size.width, this.size.height) / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val ringWidth = radius * (1f - RING_INNER)
        val rainbow = List(HUE_STOPS + 1) { Color(AndroidColor.HSVToColor(floatArrayOf(it * 360f / HUE_STOPS, 1f, 1f))) }
        // A sweep starts pointing right and turns clockwise, just as the touch maths above does.
        drawCircle(Brush.sweepGradient(rainbow, center), radius - ringWidth / 2f, center, style = Stroke(ringWidth))

        val half = radius * SQUARE_HALF
        val topLeft = Offset(center.x - half, center.y - half)
        val squareSize = Size(half * 2f, half * 2f)
        val pure = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
        drawRect(Brush.horizontalGradient(listOf(Color.White, pure), topLeft.x, topLeft.x + squareSize.width), topLeft, squareSize)
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, Color.Black), topLeft.y, topLeft.y + squareSize.height),
            topLeft,
            squareSize,
        )

        val markerRadius = ringWidth * 0.42f
        val angle = hue * PI.toFloat() / 180f
        val ringPoint = Offset(center.x + cos(angle) * (radius - ringWidth / 2f), center.y + sin(angle) * (radius - ringWidth / 2f))
        drawCircle(Color.White, markerRadius, ringPoint, style = Stroke(3.dp.toPx()))
        drawCircle(Color.Black, markerRadius + 2.dp.toPx(), ringPoint, style = Stroke(1.dp.toPx()))

        val squarePoint = Offset(topLeft.x + saturation * squareSize.width, topLeft.y + (1f - brightness) * squareSize.height)
        drawCircle(Color.White, markerRadius, squarePoint, style = Stroke(3.dp.toPx()))
        drawCircle(Color.Black, markerRadius + 2.dp.toPx(), squarePoint, style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun HexField(argb: Int, onColorChange: (Int) -> Unit, onColorCommit: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val canonical = "%06X".format(argb and 0xFFFFFF)
    LaunchedEffect(canonical) {
        if (parseHex(text) != canonical) text = canonical
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val cleaned = input
                .removePrefix("#")
                .filter { it.isLetterOrDigit() }
                .take(6)
                .uppercase()
            text = cleaned
            parseHex(cleaned)?.let { hex ->
                onColorChange((argb and ALPHA_MASK) or hex.toInt(16))
                onColorCommit()
            }
        },
        label = { Text(stringResource(R.string.color_hex)) },
        prefix = { Text("#") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        isError = text.isNotEmpty() && parseHex(text) == null,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun parseHex(text: String): String? = text.takeIf { it.length == 6 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }

private fun hsvOf(argb: Int): FloatArray = FloatArray(3).also { AndroidColor.colorToHSV(argb, it) }

private const val RING_INNER = 0.82f
private const val SQUARE_HALF = 0.56f
private const val HUE_STOPS = 12
private const val ALPHA_MASK = 0xFF000000.toInt()
private const val RGB_MASK = 0xFFFFFF

/** Colours offered before the user has any recent ones. */
public val DefaultSwatches: List<Int> =
    listOf(
        0xFF1B1C1A,
        0xFFFFFFFF,
        0xFF8D8D8D,
        0xFFE4572E,
        0xFFF3A712,
        0xFFF7D84B,
        0xFF4CAF50,
        0xFF0F6E6E,
        0xFF2979FF,
        0xFF6A4C93,
        0xFFE91E63,
        0xFF795548,
    ).map { it.toInt() }

@Composable
public fun SwatchRow(colors: List<Int>, selected: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        colors.forEach { color -> ColorSwatch(color, selected = color == selected) { onPick(color) } }
    }
}
