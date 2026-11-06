package org.kineograph.engine.brush

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.floor

/**
 * Draws stamps onto any [Canvas]: the CPU stroke buffer, or the GPU's front buffer. Each thread uses its
 * own instance, because the [Paint] and scratch objects here are not thread-safe.
 */
public class StampPainter(private val tips: TipCache) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val destination = RectF()
    private val matrix = Matrix()

    /** Paints the first [count] stamps in [data]. Eraser strokes are painted in black, as a mask. */
    public fun paint(canvas: Canvas, spec: BrushSpec, colorArgb: Int, data: FloatArray, count: Int) {
        val set = tips.tipsFor(spec)
        val base = if (spec.isEraser) Color.BLACK else colorArgb or OPAQUE
        val baseAlpha = if (spec.isEraser) 255 else Color.alpha(colorArgb)
        paint.color = base
        paint.isFilterBitmap = spec.antiAlias
        val rotatable = !spec.isRotationInvariant
        var offset = 0
        for (i in 0 until count) {
            val x = data[offset]
            val y = data[offset + 1]
            val diameter = data[offset + 2]
            val alpha = data[offset + 3]
            val angle = data[offset + 4]
            offset += StampBuffer.STRIDE
            val tip = set.forDiameter(diameter)
            val half = diameter * tip.imageToShape * 0.5f
            paint.alpha = (alpha * baseAlpha + 0.5f).toInt().coerceIn(0, 255)
            if (rotatable && angle != 0f) {
                val image = tip.bitmap
                val scale = (half * 2f) / image.width
                matrix.setTranslate(-image.width * 0.5f, -image.height * 0.5f)
                matrix.postScale(scale, scale)
                matrix.postRotate(angle * RADIANS_TO_DEGREES)
                matrix.postTranslate(x, y)
                canvas.drawBitmap(image, matrix, paint)
            } else {
                if (spec.antiAlias) {
                    destination.set(x - half, y - half, x + half, y + half)
                } else {
                    val left = floor(x - half)
                    val top = floor(y - half)
                    val side = floor(half * 2f + 0.5f)
                    destination.set(left, top, left + side, top + side)
                }
                canvas.drawBitmap(tip.bitmap, null, destination, paint)
            }
        }
    }

    public fun paint(canvas: Canvas, spec: BrushSpec, colorArgb: Int, run: StampRun) {
        paint(canvas, spec, colorArgb, run.data, run.count)
    }

    private companion object {
        const val OPAQUE = 0xFF000000.toInt()
        const val RADIANS_TO_DEGREES = 57.29578f
    }
}
