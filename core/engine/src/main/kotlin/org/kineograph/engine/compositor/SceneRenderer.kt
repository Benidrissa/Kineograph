package org.kineograph.engine.compositor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import org.kineograph.engine.selection.Affine
import org.kineograph.model.BlendMode
import kotlin.math.hypot

/**
 * Draws a [Scene] to a canvas under a view transform. Both the GPU and the software renderer use this, so
 * they look the same. Not thread-safe: each thread keeps its own instance.
 */
public class SceneRenderer {
    private val layerPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val wetLayerPaint = Paint()
    private val wetPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val floatingPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint().apply { style = Paint.Style.STROKE }
    private val backgroundPainter = BackgroundPainter()
    private val matrix = Matrix()
    private val floatingMatrix = Matrix()
    private val values = FloatArray(9)
    private val destination = RectF()
    private val eraseMode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    private val tintFilters = HashMap<Int, PorterDuffColorFilter>()

    /** Draws everything. [playbackFrame], when given, replaces the layers with one finished image. */
    public fun draw(canvas: Canvas, scene: Scene, view: Affine, workspaceColor: Int, playbackFrame: Bitmap? = null) {
        canvas.drawColor(workspaceColor, PorterDuff.Mode.SRC)
        view.toMatrixValues(values)
        matrix.setValues(values)
        val zoom = hypot(view.a, view.b)
        val smooth = zoom < NEAREST_NEIGHBOUR_ZOOM
        layerPaint.isFilterBitmap = smooth
        wetPaint.isFilterBitmap = smooth

        val outer = canvas.save()
        canvas.concat(matrix)
        val width = scene.canvasWidth
        val height = scene.canvasHeight

        if (playbackFrame == null) drawOverflow(canvas, scene, width, height)

        val inner = canvas.save()
        canvas.clipRect(0, 0, width, height)
        if (playbackFrame != null) {
            // A playback frame already includes its background.
            layerPaint.alpha = 255
            layerPaint.xfermode = null
            destination.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(playbackFrame, null, destination, layerPaint)
        } else {
            backgroundPainter.draw(canvas, scene.background, width, height, scene.backgroundImage, forEditor = true)
            drawGhosts(canvas, scene, width, height)
            scene.below.forEach { drawLayer(canvas, it) }
            drawActive(canvas, scene)
            scene.above.forEach { drawLayer(canvas, it) }
            if (scene.grid.enabled) drawGrid(canvas, scene, width, height, zoom)
        }
        canvas.restoreToCount(inner)

        borderPaint.color = BORDER_COLOR
        borderPaint.strokeWidth = 0f
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)

        if (playbackFrame == null) scene.floating?.let { drawSelectionFrame(canvas, it, zoom) }
        canvas.restoreToCount(outer)
    }

    /** Anything past the canvas edge shows faintly, as a reminder that it will not be exported. */
    private fun drawOverflow(canvas: Canvas, scene: Scene, width: Int, height: Int) {
        val anyBelow = scene.below.any { it.overflows(width, height) }
        val anyAbove = scene.above.any { it.overflows(width, height) }
        if (!anyBelow && !anyAbove && !scene.activeOverflows && scene.wet == null && scene.floating == null) return
        val checkpoint = canvas.save()
        canvas.clipOutRect(0, 0, width, height)
        val layer = canvas.saveLayerAlpha(null, OVERFLOW_ALPHA)
        if (anyBelow) scene.below.forEach { if (it.overflows(width, height)) drawLayer(canvas, it) }
        drawActive(canvas, scene)
        if (anyAbove) scene.above.forEach { if (it.overflows(width, height)) drawLayer(canvas, it) }
        canvas.restoreToCount(layer)
        canvas.restoreToCount(checkpoint)
    }

    private fun drawGhosts(canvas: Canvas, scene: Scene, width: Int, height: Int) {
        if (scene.ghosts.isEmpty()) return
        destination.set(0f, 0f, width.toFloat(), height.toFloat())
        layerPaint.xfermode = null
        for (ghost in scene.ghosts) {
            layerPaint.alpha = (ghost.opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            layerPaint.colorFilter =
                ghost.tint?.let { tint -> tintFilters.getOrPut(tint) { PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_ATOP) } }
            canvas.drawBitmap(ghost.bitmap, null, destination, layerPaint)
        }
        layerPaint.colorFilter = null
    }

    private fun drawLayer(canvas: Canvas, draw: LayerDraw) {
        layerPaint.alpha = (draw.opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        layerPaint.applyLayerBlend(draw.blendMode)
        canvas.drawBitmap(draw.bitmap, draw.left.toFloat(), draw.top.toFloat(), layerPaint)
        layerPaint.xfermode = null
    }

    private fun drawActive(canvas: Canvas, scene: Scene) {
        val active = scene.active
        val wet = scene.wet
        val floating = scene.floating
        if (wet == null && floating == null) {
            if (active != null) drawLayer(canvas, active)
            return
        }
        // What is still loose and its layer are combined first, so that an eraser stroke only removes this layer, and a
        // lifted selection already looks the way it will once it is put down: at the layer's opacity, in the layer's place.
        wetLayerPaint.alpha = (((active?.opacity ?: 1f).coerceIn(0f, 1f)) * 255f + 0.5f).toInt()
        wetLayerPaint.applyLayerBlend(active?.blendMode ?: BlendMode.NORMAL)
        val checkpoint = canvas.saveLayer(null, wetLayerPaint)
        if (active != null) {
            layerPaint.alpha = 255
            layerPaint.xfermode = null
            canvas.drawBitmap(active.bitmap, active.left.toFloat(), active.top.toFloat(), layerPaint)
        }
        if (wet != null) {
            wetPaint.alpha = (wet.opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            wetPaint.xfermode = if (wet.erase) eraseMode else null
            canvas.drawBitmap(wet.buffer, wet.left.toFloat(), wet.top.toFloat(), wetPaint)
        }
        if (floating != null) {
            val source = floating.transform.source
            floating.transform.toAffine().toMatrixValues(values)
            floatingMatrix.setValues(values)
            floatingMatrix.preTranslate(source.left.toFloat(), source.top.toFloat())
            canvas.drawBitmap(floating.bitmap, floatingMatrix, floatingPaint)
        }
        canvas.restoreToCount(checkpoint)
    }

    private fun drawGrid(canvas: Canvas, scene: Scene, width: Int, height: Int, zoom: Float) {
        val spacing = scene.grid.spacingPx
            .coerceAtLeast(1)
            .toFloat()
        if (spacing * zoom < MIN_GRID_SCREEN_SPACING) return
        gridPaint.color = GRID_COLOR
        gridPaint.strokeWidth = 0f
        var x = spacing
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x += spacing
        }
        var y = spacing
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            y += spacing
        }
    }

    /** The box and handles, drawn over everything, so a selection can still be grabbed under a layer above or at low opacity. */
    private fun drawSelectionFrame(canvas: Canvas, floating: FloatingDraw, zoom: Float) {
        val transform = floating.transform
        val onePixel = 1f / zoom.coerceAtLeast(SMALLEST_ZOOM)
        val corners = transform.corners()
        // Dark and light dashes together stay visible over any artwork.
        selectionPaint.strokeWidth = onePixel * SELECTION_STROKE_DP
        selectionPaint.pathEffect = null
        selectionPaint.color = Color.WHITE
        drawBox(canvas, corners)
        selectionPaint.color = SELECTION_COLOR
        selectionPaint.pathEffect = DashPathEffect(floatArrayOf(onePixel * DASH, onePixel * DASH), 0f)
        drawBox(canvas, corners)
        selectionPaint.pathEffect = null

        val knob = transform.rotateKnob(onePixel * KNOB_OFFSET)
        canvas.drawLine((corners[0] + corners[2]) * 0.5f, (corners[1] + corners[3]) * 0.5f, knob.first, knob.second, selectionPaint)
        val radius = onePixel * HANDLE_RADIUS
        for (i in 0 until 4) drawHandle(canvas, corners[i * 2], corners[i * 2 + 1], radius)
        drawHandle(canvas, knob.first, knob.second, radius)
    }

    private fun drawBox(canvas: Canvas, c: FloatArray) {
        canvas.drawLine(c[0], c[1], c[2], c[3], selectionPaint)
        canvas.drawLine(c[2], c[3], c[4], c[5], selectionPaint)
        canvas.drawLine(c[4], c[5], c[6], c[7], selectionPaint)
        canvas.drawLine(c[6], c[7], c[0], c[1], selectionPaint)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float, radius: Float) {
        handlePaint.color = Color.WHITE
        canvas.drawCircle(x, y, radius, handlePaint)
        handlePaint.color = SELECTION_COLOR
        canvas.drawCircle(x, y, radius * 0.6f, handlePaint)
    }

    public companion object {
        private const val NEAREST_NEIGHBOUR_ZOOM = 3f

        /** Far below what the longest canvas Android can hold is fitted at. It is only there so as never to divide by nothing. */
        private const val SMALLEST_ZOOM = 1e-9f
        private const val OVERFLOW_ALPHA = 90
        private const val BORDER_COLOR = 0x66000000
        private const val GRID_COLOR = 0x5500A0B0
        private const val SELECTION_COLOR = 0xFF0F6E6E.toInt()
        private const val MIN_GRID_SCREEN_SPACING = 6f
        private const val SELECTION_STROKE_DP = 2f
        private const val DASH = 8f
        private const val HANDLE_RADIUS = 11f

        /** The distance from the selection box to its rotate knob, in view pixels. */
        public const val KNOB_OFFSET: Float = 48f
    }
}
