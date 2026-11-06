package org.kineograph.engine.compositor

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import org.kineograph.model.Background
import org.kineograph.model.PaperKind
import kotlin.math.max

/** Paints what sits behind the layers. Not thread-safe: each thread keeps its own instance. */
public class BackgroundPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()
    private val destination = RectF()
    private var checkerShader: BitmapShader? = null

    /**
     * Draws the background over (0, 0, [width], [height]). A transparent background shows a checkerboard
     * when [forEditor] is set, and nothing at all otherwise, so exports keep their transparency.
     */
    public fun draw(canvas: Canvas, background: Background, width: Int, height: Int, image: Bitmap?, forEditor: Boolean) {
        when (background) {
            is Background.Color -> {
                canvas.drawColor(background.argb)
            }

            is Background.Image -> {
                canvas.drawColor(Color.WHITE)
                if (image != null) drawCover(canvas, image, width, height)
            }

            is Background.Paper -> {
                drawPaper(canvas, background.kind, width, height)
            }

            Background.Transparent -> {
                if (forEditor) drawCheckerboard(canvas, width, height)
            }
        }
    }

    private fun drawPaper(canvas: Canvas, kind: PaperKind, width: Int, height: Int) {
        canvas.drawColor(paperColor(kind))
        paint.shader = null
        when (kind) {
            PaperKind.GRID -> {
                val spacing = patternSpacing(width, height)
                paint.color = GRID_LINE
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(1f, spacing / 32f)
                var x = spacing
                while (x < width) {
                    canvas.drawLine(x, 0f, x, height.toFloat(), paint)
                    x += spacing
                }
                var y = spacing
                while (y < height) {
                    canvas.drawLine(0f, y, width.toFloat(), y, paint)
                    y += spacing
                }
            }

            PaperKind.DOTS -> {
                val spacing = patternSpacing(width, height)
                paint.color = DOT_COLOR
                paint.style = Paint.Style.FILL
                val radius = max(1f, spacing / 18f)
                var y = spacing
                while (y < height) {
                    var x = spacing
                    while (x < width) {
                        canvas.drawCircle(x, y, radius, paint)
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

    /** Scales [image] to fill the canvas, cropping whatever overflows. */
    private fun drawCover(canvas: Canvas, image: Bitmap, width: Int, height: Int) {
        val scale = max(width.toFloat() / image.width, height.toFloat() / image.height)
        val cropWidth = (width / scale).toInt().coerceIn(1, image.width)
        val cropHeight = (height / scale).toInt().coerceIn(1, image.height)
        val left = (image.width - cropWidth) / 2
        val top = (image.height - cropHeight) / 2
        source.set(left, top, left + cropWidth, top + cropHeight)
        destination.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        canvas.drawBitmap(image, source, destination, paint)
    }

    private fun drawCheckerboard(canvas: Canvas, width: Int, height: Int) {
        val shader = checkerShader ?: createCheckerShader().also { checkerShader = it }
        paint.shader = shader
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }

    private fun createCheckerShader(): BitmapShader {
        val tile = Bitmap.createBitmap(CHECKER_CELL * 2, CHECKER_CELL * 2, Bitmap.Config.ARGB_8888)
        val tileCanvas = Canvas(tile)
        tileCanvas.drawColor(CHECKER_LIGHT)
        val tilePaint = Paint().apply { color = CHECKER_DARK }
        tileCanvas.drawRect(0f, 0f, CHECKER_CELL.toFloat(), CHECKER_CELL.toFloat(), tilePaint)
        tileCanvas.drawRect(CHECKER_CELL.toFloat(), CHECKER_CELL.toFloat(), CHECKER_CELL * 2f, CHECKER_CELL * 2f, tilePaint)
        return BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun patternSpacing(width: Int, height: Int): Float = max(4f, minOf(width, height) / PATTERN_DIVISIONS)

    public companion object {
        private const val CHECKER_CELL = 16
        private const val CHECKER_LIGHT = 0xFFFFFFFF.toInt()
        private const val CHECKER_DARK = 0xFFDADDE1.toInt()
        private const val GRID_LINE = 0xFFCFDDEB.toInt()
        private const val DOT_COLOR = 0xFFBFC6CE.toInt()
        private const val PATTERN_DIVISIONS = 18f

        public fun paperColor(kind: PaperKind): Int =
            when (kind) {
                PaperKind.WHITE, PaperKind.GRID, PaperKind.DOTS -> Color.WHITE
                PaperKind.OFF_WHITE -> 0xFFFAF5E8.toInt()
                PaperKind.DARK -> 0xFF20252B.toInt()
            }
    }
}
