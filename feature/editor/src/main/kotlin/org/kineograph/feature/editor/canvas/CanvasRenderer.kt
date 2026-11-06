package org.kineograph.feature.editor.canvas

import android.graphics.Bitmap
import org.kineograph.engine.brush.BrushSpec
import org.kineograph.engine.brush.StampRun
import org.kineograph.engine.selection.Affine

/**
 * Puts the canvas on screen. There are two implementations: [FrontBufferRenderer] for the lowest latency on
 * Android 10 and up, and [SoftwareRenderer] for everything else. Input code talks only to this.
 */
internal interface CanvasRenderer {
    /** Whether display-only frames may be hardware bitmaps, which a software canvas cannot draw. */
    val supportsHardwareBitmaps: Boolean

    /** Sets the canvas-to-view transform. Takes effect at the next redraw. */
    fun setView(view: Affine)

    /** Shows one finished image in place of the layers, for playback. Null goes back to the layers. */
    fun setPlaybackFrame(frame: Bitmap?)

    /** Asks for the whole scene to be drawn again. Requests are merged, so this can be called freely. */
    fun requestRedraw()

    /** Starts showing a stroke. */
    fun beginWet(spec: BrushSpec, colorArgb: Int, layerOpacity: Float)

    /** Shows more of the stroke. [real] stamps are final; [predicted] stamps only last until the next call. */
    fun drawWet(real: StampRun, predicted: StampRun)

    /** Finishes the stroke, merging it into the layer. */
    fun commitWet()

    /** Abandons the stroke without leaving a mark. */
    fun cancelWet()

    /** Frees resources. Nothing else may be called afterwards. */
    fun release()
}
