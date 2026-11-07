package org.kineograph.feature.editor.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import androidx.graphics.surface.SurfaceControlCompat
import org.kineograph.engine.brush.BrushSpec
import org.kineograph.engine.brush.StampRun
import org.kineograph.engine.compositor.SceneRenderer
import org.kineograph.engine.selection.Affine
import org.kineograph.engine.session.DrawingSession

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
 *
 * [WetStrokePainter] does this painting, and [WetStrokeSequencer] decides when all of it reaches the library. Failures on the
 * render thread go to [onFailure], which is called on that thread. It is how the view knows to fall back to the software path.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class FrontBufferRenderer(
    surfaceView: SurfaceView,
    private val session: DrawingSession,
    initialWorkspaceColor: Int,
    initialView: Affine,
    private val onFailure: () -> Unit,
) : CanvasRenderer {
    private val sceneRenderer = SceneRenderer()
    private val painter = WetStrokePainter(session)

    @Volatile
    private var workspaceColor: Int = initialWorkspaceColor

    @Volatile
    private var view: Affine = initialView

    @Volatile
    private var playbackFrame: Bitmap? = null

    // Stroke state on the main thread.
    private var wetStroke: WetStroke? = null

    private val sequencer: WetStrokeSequencer<WetCommand> =
        WetStrokeSequencer(
            object : FrontBufferLayers {
                override fun requestFrontPaint() {
                    renderer.renderFrontBufferedLayer(Unit)
                }

                override fun commit() {
                    renderer.commit()
                }

                override fun cancel() {
                    renderer.cancel()
                }
            },
        )

    // Last, because it starts calling back on the render thread as soon as it exists.
    private val renderer: CanvasFrontBufferedRenderer<Unit> =
        CanvasFrontBufferedRenderer(
            surfaceView,
            object : CanvasFrontBufferedRenderer.Callback<Unit> {
                override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: Unit) {
                    reportingFailure { drawFront(canvas) }
                }

                override fun onFrontBufferedLayerRenderComplete(
                    frontBufferedLayerSurfaceControl: SurfaceControlCompat,
                    transaction: SurfaceControlCompat.Transaction,
                ) {
                    transaction.setAlpha(frontBufferedLayerSurfaceControl, painter.layerAlpha)
                }

                override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, params: Collection<Unit>) {
                    sequencer.onSceneDrawStarted()
                    reportingFailure { drawScene(canvas) }
                    sequencer.onSceneDrawFinished()
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

    override fun setWorkspaceColor(color: Int) {
        workspaceColor = color
        // The eraser paints with an image of the scene, whose margin has to match.
        session.workspaceColor = color
        requestRedraw()
    }

    override fun requestRedraw() {
        sequencer.requestRedraw()
    }

    override fun beginWet(spec: BrushSpec, colorArgb: Int, layerOpacity: Float) {
        val alpha = if (spec.isEraser) spec.opacity else spec.opacity * layerOpacity
        wetStroke = WetStroke(sequencer.beginStroke(), spec, colorArgb, alpha)
        session.beginStroke(spec, colorArgb)
    }

    override fun drawWet(real: StampRun, predicted: StampRun) {
        val stroke = wetStroke ?: return
        val command = WetCommand(stroke, real, predicted)
        if (stroke.spec.isEraser) {
            // The eraser's reveal image is built on the engine thread, so stamps must wait until it is ready.
            session.addStamps(real) { sequencer.add(stroke.id, command) }
        } else {
            sequencer.add(stroke.id, command)
            if (!real.isEmpty) session.addStamps(real)
        }
    }

    override fun commitWet() {
        val stroke = wetStroke ?: return
        wetStroke = null
        session.endStroke {
            // On the engine thread, so all of the stroke's stamps are in, and the merged pixels are ready for the scene draw. The
            // finished stroke therefore replaces the wet one in a single frame, without a flicker.
            sequencer.commitStroke(stroke.id)
            showResultIfReplaced()
        }
    }

    override fun cancelWet() {
        val stroke = wetStroke ?: return
        wetStroke = null
        sequencer.cancelStroke(stroke.id)
        session.cancelStroke { showResultIfReplaced() }
    }

    override fun release() {
        sequencer.close()
        session.wantsRevealImage = false
        renderer.release(true)
    }

    /** A stroke can finish on the engine after the view has moved on to another renderer, which then needs to see the result. */
    private fun showResultIfReplaced() {
        if (sequencer.isClosed) session.sceneListener?.invoke()
    }

    /** Nothing can catch a failure on the render thread but this, and without it the whole app would go down. */
    private inline fun reportingFailure(draw: () -> Unit) {
        try {
            draw()
        } catch (_: RuntimeException) {
            onFailure()
        } catch (_: OutOfMemoryError) {
            onFailure()
        }
    }

    private fun drawFront(canvas: Canvas) {
        for (command in sequencer.takeCommands()) painter.paint(canvas, command, view)
    }

    private fun drawScene(canvas: Canvas) {
        val scene = session.scene ?: return
        if (fitsHardwareCanvas(scene.canvasWidth, scene.canvasHeight)) {
            // The stroke being drawn, if any, belongs on the front layer. Drawing it here as well would darken it.
            sceneRenderer.draw(canvas, scene.withoutWet(), view, workspaceColor, playbackFrame)
        } else {
            // The canvas has just been resized past what this path can draw, and the view is about to change to the other one.
            canvas.drawColor(workspaceColor)
        }
    }
}
