package org.kineograph.feature.gallery

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kineograph.engine.compositor.BackgroundPainter
import org.kineograph.model.PaperKind
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/** What is behind the drawing, as chosen in the New Project sheet. */
internal sealed interface BackgroundChoice {
    data class PaperChoice(val kind: PaperKind) : BackgroundChoice

    data class SolidChoice(val argb: Int) : BackgroundChoice

    data class ImageChoice(val uri: Uri) : BackgroundChoice

    data object TransparentChoice : BackgroundChoice
}

/** A small sample of a background, painted the way it will really look. */
@Composable
internal fun BackgroundSwatch(choice: BackgroundChoice?, image: ImageBitmap? = null, icon: ImageVector? = null) {
    val outline = MaterialTheme.colorScheme.outline
    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.size(SWATCH_WIDTH.dp, SWATCH_HEIGHT.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(SWATCH_WIDTH.dp, SWATCH_HEIGHT.dp)) {
            val corner = CornerRadius(3.dp.toPx())
            clipRect {
                if (choice != null) drawBackground(choice, image, Offset.Zero, size)
            }
            drawRoundRect(outline, size = size, cornerRadius = corner, style = Stroke(1.dp.toPx()))
        }
        if (icon != null) Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = iconTint)
    }
}

/** A picture of a canvas's shape, so the options can be compared at a glance. Pixel art shows its grid. */
@Composable
internal fun CanvasShapeGlyph(width: Int, height: Int, showPixels: Boolean = false) {
    val fill = MaterialTheme.colorScheme.primaryContainer
    val line = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(GLYPH_SIZE.dp)) {
        val scale = min(size.width / max(width, 1), size.height / max(height, 1))
        val shape = Size(max(width * scale, MIN_GLYPH_SIDE.dp.toPx()), max(height * scale, MIN_GLYPH_SIDE.dp.toPx()))
        val topLeft = Offset((size.width - shape.width) / 2f, (size.height - shape.height) / 2f)
        drawRect(fill, topLeft, shape)
        if (showPixels) {
            val step = shape.width / PIXEL_GRID_CELLS
            for (i in 1 until PIXEL_GRID_CELLS) {
                drawLine(
                    line.copy(alpha = 0.5f),
                    Offset(topLeft.x + step * i, topLeft.y),
                    Offset(
                        topLeft.x + step * i,
                        topLeft.y + shape.height,
                    ),
                    1f,
                )
                drawLine(
                    line.copy(alpha = 0.5f),
                    Offset(topLeft.x, topLeft.y + step * i),
                    Offset(
                        topLeft.x + shape.width,
                        topLeft.y + step * i,
                    ),
                    1f,
                )
            }
        }
        drawRect(line, topLeft, shape, style = Stroke(1.5.dp.toPx()))
    }
}

/** Paints a background into a rectangle, for swatches and for the live preview. */
internal fun DrawScope.drawBackground(choice: BackgroundChoice, image: ImageBitmap?, topLeft: Offset, area: Size) {
    when (choice) {
        is BackgroundChoice.PaperChoice -> {
            drawPaper(choice.kind, topLeft, area)
        }

        is BackgroundChoice.SolidChoice -> {
            drawRect(Color(choice.argb), topLeft, area)
        }

        is BackgroundChoice.ImageChoice -> {
            drawRect(Color.White, topLeft, area)
            if (image != null) drawCover(image, topLeft, area)
        }

        BackgroundChoice.TransparentChoice -> {
            drawChecker(topLeft, area)
        }
    }
}

private fun DrawScope.drawPaper(kind: PaperKind, topLeft: Offset, area: Size) {
    drawRect(Color(BackgroundPainter.paperColor(kind)), topLeft, area)
    val spacing = max(min(area.width, area.height) / PATTERN_CELLS, MIN_PATTERN_SPACING.dp.toPx())
    when (kind) {
        PaperKind.GRID -> {
            val color = Color(BackgroundPainter.GRID_LINE)
            var x = spacing
            while (x < area.width) {
                drawLine(color, Offset(topLeft.x + x, topLeft.y), Offset(topLeft.x + x, topLeft.y + area.height), 1.dp.toPx())
                x += spacing
            }
            var y = spacing
            while (y < area.height) {
                drawLine(color, Offset(topLeft.x, topLeft.y + y), Offset(topLeft.x + area.width, topLeft.y + y), 1.dp.toPx())
                y += spacing
            }
        }

        PaperKind.DOTS -> {
            val color = Color(BackgroundPainter.DOT_COLOR)
            var y = spacing
            while (y < area.height) {
                var x = spacing
                while (x < area.width) {
                    drawCircle(color, 1.dp.toPx(), Offset(topLeft.x + x, topLeft.y + y))
                    x += spacing
                }
                y += spacing
            }
        }

        PaperKind.WHITE, PaperKind.OFF_WHITE, PaperKind.DARK -> {
            Unit
        }
    }
}

private fun DrawScope.drawChecker(topLeft: Offset, area: Size) {
    drawRect(Color(BackgroundPainter.CHECKER_LIGHT), topLeft, area)
    val cell = CHECKER_CELL.dp.toPx()
    val dark = Color(BackgroundPainter.CHECKER_DARK)
    var row = 0
    var y = 0f
    while (y < area.height) {
        var column = row % 2
        var x = column * cell
        while (x < area.width) {
            drawRect(dark, Offset(topLeft.x + x, topLeft.y + y), Size(min(cell, area.width - x), min(cell, area.height - y)))
            column += 2
            x = column * cell
        }
        y += cell
        row++
    }
}

/** Scales the picture to fill the area, cropping what overflows, just as the real background does. */
private fun DrawScope.drawCover(image: ImageBitmap, topLeft: Offset, area: Size) {
    val scale = max(area.width / image.width, area.height / image.height)
    val cropWidth = (area.width / scale).toInt().coerceIn(1, image.width)
    val cropHeight = (area.height / scale).toInt().coerceIn(1, image.height)
    drawImage(
        image,
        srcOffset = IntOffset((image.width - cropWidth) / 2, (image.height - cropHeight) / 2),
        srcSize = IntSize(cropWidth, cropHeight),
        dstOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
        dstSize = IntSize(area.width.toInt(), area.height.toInt()),
    )
}

/** Loads a small version of a picture for previews, off the main thread. Null until ready, or if it cannot be read. */
@Composable
internal fun rememberPictureThumbnail(uri: Uri?, resolver: ContentResolver): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, uri) {
        value = uri?.let { withContext(Dispatchers.IO) { decodeThumbnail(it, resolver) } }
    }

private fun decodeThumbnail(uri: Uri, resolver: ContentResolver): ImageBitmap? =
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMBNAIL_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }?.asImageBitmap()
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

private const val SWATCH_WIDTH = 40
private const val SWATCH_HEIGHT = 28
private const val GLYPH_SIZE = 32
private const val MIN_GLYPH_SIDE = 6
private const val PIXEL_GRID_CELLS = 4
private const val PATTERN_CELLS = 5f
private const val MIN_PATTERN_SPACING = 5
private const val CHECKER_CELL = 5
private const val THUMBNAIL_EDGE = 320
