package org.kineograph.feature.editor.canvas

import android.graphics.Bitmap
import org.kineograph.engine.brush.BrushSpec
import org.kineograph.engine.brush.StampRun
import org.kineograph.engine.selection.Affine

/** Where playback shows its frames. */
internal interface PlaybackDisplay {
    /** Whether display-only frames may be hardware bitmaps, which a software canvas cannot draw. */
    val supportsHardwareBitmaps: Boolean

    /** Shows one finished image in place of the layers, for playback. Null goes back to the layers. */
    fun setPlaybackFrame(frame: Bitmap?)
}

/**
 * Puts the canvas on screen. There are two implementations: [FrontBufferRenderer] for the lowest latency on
 * Android 10 and up, and [SoftwareRenderer] for everything else. Input code talks only to this.
 *
 * A renderer lasts for one surface at one size. [KineographCanvasView] creates it once the surface exists, replaces it when the
 * surface changes shape or a different renderer suits the canvas better, and releases it before the surface goes.
 */
internal interface CanvasRenderer : PlaybackDisplay {
    /** Sets the canvas-to-view transform. Takes effect at the next redraw. */
    fun setView(view: Affine)

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

    /** Frees resources. Nothing else may be called afterwards, and the surface is not touched once this returns. */
    fun release()
}

/**
 * Whether a canvas of this size can go through a hardware canvas, which refuses bitmaps over 100 MB. The bitmap in question is the
 * surface: the canvas plus a margin that at most multiplies its area by about 1.6, which the allowance here covers.
 */
internal fun fitsHardwareCanvas(canvasWidth: Int, canvasHeight: Int): Boolean =
    canvasWidth.toLong() * canvasHeight * BYTES_PER_PIXEL * OVERSCAN_ALLOWANCE < HARDWARE_BITMAP_LIMIT

private const val BYTES_PER_PIXEL = 4L
private const val OVERSCAN_ALLOWANCE = 2L
private const val HARDWARE_BITMAP_LIMIT = 100L * 1024 * 1024
