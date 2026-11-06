package org.kineograph.feature.editor.canvas

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import androidx.graphics.surface.SurfaceControlCompat
import org.kineograph.engine.brush.BrushSpec
import org.kineograph.engine.brush.StampBuffer
import org.kineograph.engine.brush.StampPainter
import org.kineograph.engine.brush.StampRun
import org.kineograph.engine.compositor.SceneRenderer
import org.kineograph.engine.selection.Affine
import org.kineograph.engine.session.DrawingSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The low-latency path for Android 10 and up.
 *
 * Finished content goes to an ordinary buffered layer. The stroke being drawn goes to a second layer on top, whose
 * buffer the display is reading directly, so new stamps appear almost as soon as they are drawn.
 *
 * Each stamp is drawn twice: here as GPU operations for the screen, and by the session on the CPU for the layer's
 * pixels. Uploading the whole layer to the GPU for every touch would be the slow way, and on a budget phone a
 * touch event could not afford it.
 *
 * Three things make the stroke look right while it is wet:
 *
 *  - **Opacity:** the top layer's alpha is the stroke's opacity, so overlapping stamps within a stroke cannot darken
 *    beyond it, exactly as when the stroke is merged later.
 *  - **Prediction:** predicted stamps must vanish when the next event arrives. Their area is cleared and the real
 *    stamps there are drawn again.
 *  - **Erasing:** a layer on top cannot remove what is below it, so the eraser paints with an image of everything
 *    except the active layer, which looks identical. The stamps are plain circles rather than the tip's shape
 *    until the stroke merges.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class FrontBufferRenderer(
    surfaceView: SurfaceView,
    private val session: DrawingSession,
    private val workspaceColor: Int,
) : CanvasRenderer {
    private class WetCommand(val real: StampRun, val predicted: StampRun, val spec: BrushSpec, val colorArgb: Int, val isFirst: Boolean)

    private val sceneRenderer = SceneRenderer()
    private val stampPainter = StampPainter(session.tips)
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val redrawQueued = AtomicBoolean(false)
    private val matrix = Matrix()
    private val values = FloatArray(9)
    private val viewRect = RectF()

    @Volatile
    private var view: Affine = Affine.IDENTITY

    @Volatile
    private var playbackFrame: Bitmap? = null

    @Volatile
    private var wetAlpha = 1f

    @Volatile
    private var pendingCommit: CountDownLatch? = null

    @Volatile
    private var redrawAfterCommit = false

    // Stroke state on the main thread.
    private var wetSpec: BrushSpec? = null
    private var wetColor = 0
    private var wetIsFirst = false
    private var wetActive = false

    // Stroke state on the render thread.
    private val realRuns = ArrayList<StampRun>()
    private var predictedRun: StampRun = StampRun.EMPTY
    private var eraserShaderSource: Bitmap? = null

    private val renderer =
        CanvasFrontBufferedRenderer(
            surfaceView,
            object : CanvasFrontBufferedRenderer.Callback<WetCommand> {
                override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: WetCommand) {
                    drawFront(canvas, param)
                }

                override fun onFrontBufferedLayerRenderComplete(
                    frontBufferedLayerSurfaceControl: SurfaceControlCompat,
                    transaction: SurfaceControlCompat.Transaction,
                ) {
                    transaction.setAlpha(frontBufferedLayerSurfaceControl, wetAlpha)
                }

                override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, params: Collection<WetCommand>) {
                    drawScene(canvas)
                }
            },
        )

    init {
        session.wantsRevealImage = true
        session.workspaceColor = workspaceColor
    }

    override val supportsHardwareBitmaps: Boolean get() = true

    override fun setView(view: Affine) {
        this.view = view
    }

    override fun setPlaybackFrame(frame: Bitmap?) {
        playbackFrame = frame
        requestRedraw()
    }

    override fun requestRedraw() {
        // A redraw hides the front layer, so while a stroke is on it the redraw waits for the commit, which redraws anyway.
        if (wetActive) return
        if (redrawQueued.compareAndSet(false, true)) renderer.renderMultiBufferedLayer(emptyList())
    }

    override fun beginWet(spec: BrushSpec, colorArgb: Int, layerOpacity: Float) {
        wetSpec = spec
        wetColor = colorArgb
        wetIsFirst = true
        wetActive = true
        wetAlpha = if (spec.isEraser) spec.opacity else spec.opacity * layerOpacity
        session.beginStroke(spec, colorArgb)
    }

    override fun drawWet(real: StampRun, predicted: StampRun) {
        val spec = wetSpec ?: return
        val command = WetCommand(real, predicted, spec, wetColor, wetIsFirst)
        wetIsFirst = false
        if (spec.isEraser) {
            // The eraser's reveal image is built on the engine thread, so stamps must wait until it is ready.
            session.addStamps(real) { renderer.renderFrontBufferedLayer(command) }
        } else {
            renderer.renderFrontBufferedLayer(command)
            if (!real.isEmpty) session.addStamps(real)
        }
    }

    override fun commitWet() {
        wetSpec = null
        wetActive = false
        val done = CountDownLatch(1)
        pendingCommit = done
        session.endStroke {
            done.countDown()
            if (redrawAfterCommit) {
                redrawAfterCommit = false
                requestRedraw()
            }
        }
        // The commit waits on its own thread for the pixels above, so the finished stroke replaces the wet one without a flicker.
        renderer.commit()
    }

    override fun cancelWet() {
        wetSpec = null
        wetActive = false
        session.cancelStroke()
        renderer.cancel()
    }

    override fun release() {
        session.wantsRevealImage = false
        renderer.release(true)
    }

    private fun drawFront(canvas: Canvas, command: WetCommand) {
        if (command.isFirst) {
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
            viewRect.set(stale.minX, stale.minY, stale.maxX, stale.maxY)
            matrix.mapRect(viewRect)
            val checkpoint = canvas.save()
            canvas.clipRect(viewRect.left - 2f, viewRect.top - 2f, viewRect.right + 2f, viewRect.bottom + 2f)
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            canvas.concat(matrix)
            canvas.clipRect(margin, margin, right, bottom)
            for (run in realRuns) {
                if (run.intersects(stale.minX, stale.minY, stale.maxX, stale.maxY)) paintRun(canvas, command, run)
            }
            canvas.restoreToCount(checkpoint)
        }

        val checkpoint = canvas.save()
        canvas.concat(matrix)
        canvas.clipRect(margin, margin, right, bottom)
        if (!command.real.isEmpty) {
            paintRun(canvas, command, command.real)
            realRuns += command.real
        }
        if (!command.predicted.isEmpty) paintRun(canvas, command, command.predicted)
        canvas.restoreToCount(checkpoint)
        predictedRun = command.predicted
    }

    private fun paintRun(canvas: Canvas, command: WetCommand, run: StampRun) {
        if (command.spec.isEraser) {
            paintEraser(canvas, run)
        } else {
            stampPainter.paint(canvas, command.spec, command.colorArgb, run)
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

    private fun drawScene(canvas: Canvas) {
        redrawQueued.set(false)
        pendingCommit?.let { latch ->
            pendingCommit = null
            if (!latch.await(COMMIT_WAIT_MS, TimeUnit.MILLISECONDS)) redrawAfterCommit = true
        }
        val scene = session.scene ?: return
        sceneRenderer.draw(canvas, scene, view, workspaceColor, playbackFrame)
    }

    private companion object {
        const val COMMIT_WAIT_MS = 400L
    }
}
