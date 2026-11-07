package org.kineograph.feature.editor.canvas

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import org.kineograph.engine.brush.BrushSpec
import org.kineograph.engine.brush.StampBuffer
import org.kineograph.engine.brush.StampPainter
import org.kineograph.engine.brush.StampRun
import org.kineograph.engine.selection.Affine
import org.kineograph.engine.session.DrawingSession

/** A stroke on the front layer. [alpha] is the layer's opacity while the stroke is on it. */
internal class WetStroke(val id: Long, val spec: BrushSpec, val colorArgb: Int, val alpha: Float)

/** More of a stroke to show. [real] stamps stay; [predicted] stamps only last until the next command. */
internal class WetCommand(val stroke: WetStroke, val real: StampRun, val predicted: StampRun)

/**
 * Paints strokes for the front layer, whose contents stay from one paint to the next. Each command clears what the last one predicted,
 * brings back the real stamps that were under it, and adds its own. Used on the render thread only.
 */
internal class WetStrokePainter(private val session: DrawingSession) {
    private val stampPainter = StampPainter(session.tips)
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val values = FloatArray(9)
    private var stroke: WetStroke? = null
    private val realRuns = ArrayList<StampRun>()
    private var predictedRun: StampRun = StampRun.EMPTY
    private var eraserShaderSource: Bitmap? = null

    /** The opacity the layer should have, which is that of the stroke last painted. */
    var layerAlpha: Float = 1f
        private set

    fun paint(canvas: Canvas, command: WetCommand, view: Affine) {
        if (command.stroke !== stroke) {
            stroke = command.stroke
            layerAlpha = command.stroke.alpha
            realRuns.clear()
            predictedRun = StampRun.EMPTY
            eraserShaderSource = null
        }
        view.toMatrixValues(values)
        matrix.setValues(values)
        val scene = session.scene
        val margin = scene?.let { (it.active?.left ?: 0).toFloat() } ?: 0f
        val right = (scene?.canvasWidth ?: 0) - margin
        val bottom = (scene?.canvasHeight ?: 0) - margin

        // Wipe where the last prediction was, then bring back the real stamps that were under it.
        val stale = predictedRun
        if (!stale.isEmpty) {
            val wipe = predictionWipe(view, stale.minX, stale.minY, stale.maxX, stale.maxY, WIPE_PADDING_PX)
            val checkpoint = canvas.save()
            canvas.clipRect(wipe.viewLeft, wipe.viewTop, wipe.viewRight, wipe.viewBottom)
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            canvas.concat(matrix)
            canvas.clipRect(margin, margin, right, bottom)
            for (run in realRuns) {
                if (run.intersects(wipe.canvasLeft, wipe.canvasTop, wipe.canvasRight, wipe.canvasBottom)) {
                    paintRun(canvas, command.stroke, run)
                }
            }
            canvas.restoreToCount(checkpoint)
        }

        val checkpoint = canvas.save()
        canvas.concat(matrix)
        canvas.clipRect(margin, margin, right, bottom)
        if (!command.real.isEmpty) {
            paintRun(canvas, command.stroke, command.real)
            realRuns += command.real
        }
        if (!command.predicted.isEmpty) paintRun(canvas, command.stroke, command.predicted)
        canvas.restoreToCount(checkpoint)
        predictedRun = command.predicted
    }

    private fun paintRun(canvas: Canvas, stroke: WetStroke, run: StampRun) {
        if (stroke.spec.isEraser) {
            paintEraser(canvas, run)
        } else {
            stampPainter.paint(canvas, stroke.spec, stroke.colorArgb, run)
        }
    }

    private fun paintEraser(canvas: Canvas, run: StampRun) {
        val reveal = session.revealImage ?: return
        if (eraserShaderSource !== reveal) {
            eraserShaderSource = reveal
            val margin = -(session.scene?.active?.left ?: 0).toFloat()
            eraserPaint.shader =
                BitmapShader(reveal, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply { setTranslate(-margin, -margin) })
                }
        }
        val data = run.data
        var offset = 0
        for (i in 0 until run.count) {
            eraserPaint.alpha = (data[offset + 3] * 255f + 0.5f).toInt().coerceIn(0, 255)
            canvas.drawCircle(data[offset], data[offset + 1], data[offset + 2] * 0.5f, eraserPaint)
            offset += StampBuffer.STRIDE
        }
    }

    private companion object {
        const val WIPE_PADDING_PX = 2f
    }
}
