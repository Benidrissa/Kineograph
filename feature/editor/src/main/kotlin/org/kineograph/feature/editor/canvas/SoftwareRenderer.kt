package org.kineograph.feature.editor.canvas

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.view.SurfaceView
import org.kineograph.engine.brush.BoundsAccumulator
import org.kineograph.engine.brush.BrushSpec
import org.kineograph.engine.brush.StampPainter
import org.kineograph.engine.brush.StampRun
import org.kineograph.engine.compositor.SceneRenderer
import org.kineograph.engine.selection.Affine
import org.kineograph.engine.session.DrawingSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The fallback path, for Android 8 and 9 and any device where the low-latency path is unavailable. It draws
 * into a SurfaceView with a software canvas and only touches the rectangle that changed, which keeps a stroke cheap
 * however large the canvas is, even on the slowest phones.
 *
 * Stamps reach the stroke buffer first and the screen second, so what shows is always what the buffer holds.
 */
internal class SoftwareRenderer(
    private val surfaceView: SurfaceView,
    private val session: DrawingSession,
    initialWorkspaceColor: Int,
) : CanvasRenderer {
    private val thread = HandlerThread("kg-render").apply { start() }
    private val handler = Handler(thread.looper)
    private val sceneRenderer = SceneRenderer()
    private val stampPainter = StampPainter(session.tips)
    private val lock = Any()

    // Guarded by lock.
    private var drawScheduled = false
    private var fullRedraw = true
    private val dirty = Rect()
    private var predicted: StampRun = StampRun.EMPTY

    // What the prediction belongs to. The pen's button starts the next stroke before the engine has finished with the last one's
    // prediction, and painting that with the eraser's spec would show a black mark for a frame.
    private var predictedSpec: BrushSpec? = null
    private var predictedColor = 0
    private var released = false

    @Volatile
    private var view: Affine = Affine.IDENTITY

    @Volatile
    private var workspaceColor: Int = initialWorkspaceColor

    @Volatile
    private var playbackFrame: Bitmap? = null

    // Touched only from the main thread.
    private var wetSpec: BrushSpec? = null
    private var wetColor = 0
    private val strokeBounds = BoundsAccumulator()

    private val matrix = Matrix()
    private val values = FloatArray(9)
    private val drawRunnable = Runnable { drawNow() }

    override val supportsHardwareBitmaps: Boolean get() = false

    override fun setView(view: Affine) {
        this.view = view
    }

    override fun setPlaybackFrame(frame: Bitmap?) {
        playbackFrame = frame
        requestRedraw()
    }

    override fun setWorkspaceColor(color: Int) {
        workspaceColor = color
        requestRedraw()
    }

    override fun requestRedraw() {
        synchronized(lock) { fullRedraw = true }
        schedule()
    }

    override fun beginWet(spec: BrushSpec, colorArgb: Int, layerOpacity: Float) {
        wetSpec = spec
        wetColor = colorArgb
        strokeBounds.clear()
        session.beginStroke(spec, colorArgb)
    }

    override fun drawWet(real: StampRun, predicted: StampRun) {
        val spec = wetSpec ?: return
        strokeBounds.add(real)
        // Predicting an erased area would need the layer composited just for the guess, so erasing skips prediction.
        val shown = if (spec.isEraser) StampRun.EMPTY else predicted
        val color = wetColor
        session.addStamps(real) { invalidate(real, shown, spec, color) }
    }

    override fun commitWet() {
        wetSpec = null
        val bounds = BoundsAccumulator().also { copy ->
            if (!strokeBounds.isEmpty) copy.add(strokeBounds.left, strokeBounds.top, strokeBounds.right, strokeBounds.bottom)
        }
        session.endStroke {
            if (showResultIfReplaced()) return@endStroke
            synchronized(lock) {
                addDirty(bounds.left, bounds.top, bounds.right, bounds.bottom, isEmpty = bounds.isEmpty)
                val stale = predicted
                addDirty(stale.minX, stale.minY, stale.maxX, stale.maxY, stale.isEmpty)
                predicted = StampRun.EMPTY
            }
            schedule()
        }
    }

    override fun cancelWet() {
        wetSpec = null
        session.cancelStroke {
            if (showResultIfReplaced()) return@cancelStroke
            synchronized(lock) { predicted = StampRun.EMPTY }
            requestRedraw()
        }
    }

    override fun release() {
        synchronized(lock) { released = true }
        handler.removeCallbacksAndMessages(null)
        // The surface may be going away and must not be touched after this returns, so wait out any drawing in progress.
        val finished = CountDownLatch(1)
        handler.post { finished.countDown() }
        finished.await(SURFACE_TEARDOWN_WAIT_MS, TimeUnit.MILLISECONDS)
        thread.quitSafely()
    }

    /** A stroke can finish on the engine after the view has moved on to another renderer, which then needs to see the result. */
    private fun showResultIfReplaced(): Boolean {
        val replaced = synchronized(lock) { released }
        if (replaced) session.sceneListener?.invoke()
        return replaced
    }

    private fun invalidate(real: StampRun, newPredicted: StampRun, spec: BrushSpec, color: Int) {
        synchronized(lock) {
            addDirty(real.minX, real.minY, real.maxX, real.maxY, real.isEmpty)
            val stale = predicted
            addDirty(stale.minX, stale.minY, stale.maxX, stale.maxY, stale.isEmpty)
            addDirty(newPredicted.minX, newPredicted.minY, newPredicted.maxX, newPredicted.maxY, newPredicted.isEmpty)
            predicted = newPredicted
            predictedSpec = spec
            predictedColor = color
        }
        schedule()
    }

    /** Adds a canvas-space rectangle to the area to redraw, converted to view space. Call with the lock held. */
    private fun addDirty(left: Float, top: Float, right: Float, bottom: Float, isEmpty: Boolean) {
        if (isEmpty) return
        val affine = view
        val m = affine
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (corner in 0 until 4) {
            val x = if (corner and 1 == 0) left else right
            val y = if (corner and 2 == 0) top else bottom
            val mappedX = m.mapX(x, y)
            val mappedY = m.mapY(x, y)
            if (mappedX < minX) minX = mappedX
            if (mappedX > maxX) maxX = mappedX
            if (mappedY < minY) minY = mappedY
            if (mappedY > maxY) maxY = mappedY
        }
        dirty.union(minX.toInt() - DIRTY_MARGIN, minY.toInt() - DIRTY_MARGIN, maxX.toInt() + DIRTY_MARGIN, maxY.toInt() + DIRTY_MARGIN)
    }

    private fun schedule() {
        synchronized(lock) {
            if (drawScheduled || released) return
            drawScheduled = true
        }
        handler.post(drawRunnable)
    }

    private fun drawNow() {
        val holder = surfaceView.holder
        val region = Rect()
        val full: Boolean
        val prediction: StampRun
        val spec: BrushSpec?
        val color: Int
        synchronized(lock) {
            drawScheduled = false
            if (released) return
            full = fullRedraw
            region.set(dirty)
            prediction = predicted
            spec = predictedSpec
            color = predictedColor
            fullRedraw = false
            dirty.setEmpty()
        }
        if (!full && region.isEmpty) return
        val scene = session.scene
        if (scene == null) return
        val canvas =
            (if (full) holder.lockCanvas() else holder.lockCanvas(region))
                ?: return
        try {
            val affine = view
            val frame = softwareFrame()
            sceneRenderer.draw(canvas, scene, affine, workspaceColor, frame)
            if (!prediction.isEmpty && frame == null && spec != null) {
                affine.toMatrixValues(values)
                matrix.setValues(values)
                val checkpoint = canvas.save()
                canvas.concat(matrix)
                stampPainter.paint(canvas, spec, color, prediction)
                canvas.restoreToCount(checkpoint)
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    /** Playback may have begun on the low-latency path, with hardware bitmaps a software canvas refuses, before the view fell back. */
    private fun softwareFrame(): Bitmap? =
        playbackFrame?.let { frame ->
            if (frame.config == Bitmap.Config.HARDWARE) frame.copy(Bitmap.Config.ARGB_8888, false) else frame
        }

    private companion object {
        const val DIRTY_MARGIN = 3
        const val SURFACE_TEARDOWN_WAIT_MS = 500L
    }
}
