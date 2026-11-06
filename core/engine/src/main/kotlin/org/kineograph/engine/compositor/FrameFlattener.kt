package org.kineograph.engine.compositor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.kineograph.engine.session.CelStore
import org.kineograph.model.Layer
import org.kineograph.model.Project

/** What to include when flattening a frame. */
public data class FlattenOptions(
    val includeBackground: Boolean = true,
    /** Draw a checkerboard for a transparent background rather than leaving it empty. */
    val forEditor: Boolean = false,
    /** Include layers flagged as reference, which exports normally leave out. */
    val includeReferenceLayers: Boolean = false,
    /** Only draw this layer. */
    val onlyLayerId: String? = null,
    /** Leave this layer out. */
    val excludeLayerId: String? = null,
)

/**
 * Draws one whole frame from its cel files onto a canvas: thumbnails, onion ghosts, playback and export
 * all come through here, so they can never disagree about what a frame looks like. The drawing is
 * clipped to the canvas, which is why anything drawn past the edge never gets exported.
 *
 * Not thread-safe: each thread keeps its own instance.
 */
public class FrameFlattener(private val store: CelStore) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val backgroundPainter = BackgroundPainter()
    private val destination = RectF()

    /** Draws frame [frameId] at [scale]. The caller's canvas must already be positioned at the frame's origin. */
    public fun flatten(
        project: Project,
        frameId: String,
        canvas: Canvas,
        scale: Float,
        backgroundImage: Bitmap?,
        options: FlattenOptions = FlattenOptions(),
    ) {
        val sampleSize = sampleSizeFor(scale)
        val checkpoint = canvas.save()
        canvas.scale(scale, scale)
        canvas.clipRect(0, 0, project.width, project.height)
        if (options.includeBackground) {
            backgroundPainter.draw(canvas, project.background, project.width, project.height, backgroundImage, options.forEditor)
        }
        for (layer in project.layers) {
            if (!shouldDraw(layer, options)) continue
            val cel = project.cel(layer.id, frameId) ?: continue
            val file = cel.file ?: continue
            val bounds = cel.bounds ?: continue
            val image = store.read(file, sampleSize) ?: continue
            paint.alpha = (layer.opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            paint.applyLayerBlend(layer.blendMode)
            destination.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
            canvas.drawBitmap(image, null, destination, paint)
        }
        canvas.restoreToCount(checkpoint)
    }

    /** Whether a frame would draw anything at all from its layers. */
    public fun hasContent(project: Project, frameId: String, options: FlattenOptions = FlattenOptions()): Boolean =
        project.layers.any { shouldDraw(it, options) && project.cel(it.id, frameId) != null }

    private fun shouldDraw(layer: Layer, options: FlattenOptions): Boolean {
        if (options.onlyLayerId != null) return layer.id == options.onlyLayerId
        if (layer.id == options.excludeLayerId) return false
        if (!layer.visible) return false
        return options.includeReferenceLayers || !layer.isReference
    }

    public companion object {
        /** The largest power-of-two reduction that still leaves at least [scale] of the original resolution. */
        public fun sampleSizeFor(scale: Float): Int {
            var sample = 1
            while (scale * sample * 2 <= 1f && sample < 64) sample *= 2
            return sample
        }
    }
}
