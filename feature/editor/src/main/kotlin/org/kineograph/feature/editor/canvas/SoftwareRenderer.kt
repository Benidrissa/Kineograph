package org.kineograph.feature.editor.canvas

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.view.SurfaceHolder
import android.view.SurfaceView
import org.kineograph.engine.brush.BoundsAccumulator
import org.kineograph.engine.brush.BrushSpec
import org.kineograph.engine.brush.StampPainter
import org.kineograph.engine.brush.StampRun
import org.kineograph.engine.compositor.SceneRenderer
import org.kineograph.engine.selection.Affine
import org.kineograph.engine.session.DrawingSession

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
    private val workspaceColor: Int,
) : CanvasRenderer, SurfaceHolder.Callback {
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
    private var surfaceReady = false

    @Volatile
    private var view: Affine = Affine.IDENTITY

    @Volatile
    private var playbackFrame: Bitmap? = null

    // Touched only from the main thread.
    private var wetSpec: BrushSpec? = null
    private var wetColor = 0
    private val strokeBounds = BoundsAccumulator()

    private val matrix = Matrix()
    private val values = FloatArray(9)
    private val drawRunnable = Runnable { drawNow() }

    init {
        surfaceView.holder.setFormat(PixelFormat.RGBA_8888)
        surfaceView.holder.addCallback(this)
        surfaceReady = surfaceView.holder.surface?.isValid == true
    }

    override val supportsHardwareBitmaps: Boolean get() = false

    override fun setView(view: Affine) {
        this.view = view
    }

    override fun setPlaybackFrame(frame: Bitmap?) {
        playbackFrame = frame
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
        session.addStamps(real) { invalidate(real, shown) }
    }

    override fun commitWet() {
        wetSpec = null
        val bounds = BoundsAccumulator().also { copy ->
            if (!strokeBounds.isEmpty) copy.add(strokeBounds.left, strokeBounds.top, strokeBounds.right, strokeBounds.bottom)
        }
        session.endStroke {
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
            synchronized(lock) { predicted = StampRun.EMPTY }
            requestRedraw()
        }
    }

    override fun release() {
        surfaceView.holder.removeCallback(this)
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronized(lock) { surfaceReady = true }
        requestRedraw()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        requestRedraw()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        synchronized(lock) { surfaceReady = false }
        // The surface must not be touched after this returns, so wait out any drawing in progress.
        handler.removeCallbacks(drawRunnable)
        val finished = java.util.concurrent.CountDownLatch(1)
        handler.post { finished.countDown() }
        finished.await(SURFACE_TEARDOWN_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun invalidate(real: StampRun, newPredicted: StampRun) {
        synchronized(lock) {
            addDirty(real.minX, real.minY, real.maxX, real.maxY, real.isEmpty)
            val stale = predicted
            addDirty(stale.minX, stale.minY, stale.maxX, stale.maxY, stale.isEmpty)
            addDirty(newPredicted.minX, newPredicted.minY, newPredicted.maxX, newPredicted.maxY, newPredicted.isEmpty)
            predicted = newPredicted
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
            if (drawScheduled || !surfaceReady) return
            drawScheduled = true
        }
        handler.post(drawRunnable)
    }

    private fun drawNow() {
        val holder = surfaceView.holder
        val region = Rect()
        val full: Boolean
        val prediction: StampRun
        synchronized(lock) {
            drawScheduled = false
            if (!surfaceReady) return
            full = fullRedraw
            region.set(dirty)
            prediction = predicted
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
            sceneRenderer.draw(canvas, scene, affine, workspaceColor, playbackFrame)
            if (!prediction.isEmpty && playbackFrame == null) {
                val spec = wetSpec
                if (spec != null) {
                    affine.toMatrixValues(values)
                    matrix.setValues(values)
                    val checkpoint = canvas.save()
                    canvas.concat(matrix)
                    stampPainter.paint(canvas, spec, wetColor, prediction)
                    canvas.restoreToCount(checkpoint)
                }
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private companion object {
        const val DIRTY_MARGIN = 3
        const val SURFACE_TEARDOWN_WAIT_MS = 500L
    }
}
