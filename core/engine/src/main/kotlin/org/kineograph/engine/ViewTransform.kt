package org.kineograph.engine

import org.kineograph.engine.selection.Affine
import kotlin.math.abs
import kotlin.math.min

/**
 * How the canvas sits in the viewport: pan, zoom and rotation. Canvas coordinates map to view
 * coordinates by rotating and scaling about the canvas centre, then moving by the pan.
 */
public data class ViewTransform(
    val panX: Float = 0f,
    val panY: Float = 0f,
    val zoom: Float = 1f,
    val rotationDegrees: Float = 0f,
) {
    /** Canvas-to-view transform for a canvas of the given size. */
    public fun toAffine(canvasWidth: Int, canvasHeight: Int): Affine =
        Affine
            .translation(panX, panY)
            .compose(Affine.rotation(Math.toRadians(rotationDegrees.toDouble()).toFloat()))
            .compose(Affine.scale(zoom, zoom))
            .compose(Affine.translation(-canvasWidth * 0.5f, -canvasHeight * 0.5f))

    /**
     * The transform after a two-finger move: the canvas point under the old centroid ends up under
     * the new one, and zoom and rotation pivot about it.
     */
    public fun afterGesture(
        canvasWidth: Int,
        canvasHeight: Int,
        oldCentroidX: Float,
        oldCentroidY: Float,
        newCentroidX: Float,
        newCentroidY: Float,
        scaleBy: Float,
        rotateByDegrees: Float,
    ): ViewTransform {
        val inverse = toAffine(canvasWidth, canvasHeight).inverse() ?: return this
        val anchorX = inverse.mapX(oldCentroidX, oldCentroidY)
        val anchorY = inverse.mapY(oldCentroidX, oldCentroidY)
        val newZoom = (zoom * scaleBy).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val moved = copy(zoom = newZoom, rotationDegrees = wrapDegrees(rotationDegrees + rotateByDegrees))
        val mapped = moved.toAffine(canvasWidth, canvasHeight)
        return moved.copy(
            panX = moved.panX + newCentroidX - mapped.mapX(anchorX, anchorY),
            panY = moved.panY + newCentroidY - mapped.mapY(anchorX, anchorY),
        )
    }

    /** Straightens the canvas if a gesture left it within a few degrees of upright. */
    public fun snappedRotation(): ViewTransform = if (abs(rotationDegrees) < ROTATION_SNAP_DEGREES) copy(rotationDegrees = 0f) else this

    public companion object {
        public const val MIN_ZOOM: Float = 0.05f
        public const val MAX_ZOOM: Float = 64f
        private const val ROTATION_SNAP_DEGREES = 3f

        /** Centres the whole canvas in the viewport at the largest size that fits, upright. */
        public fun fit(viewWidth: Int, viewHeight: Int, canvasWidth: Int, canvasHeight: Int, paddingPx: Float): ViewTransform {
            val availableWidth = (viewWidth - paddingPx * 2).coerceAtLeast(1f)
            val availableHeight = (viewHeight - paddingPx * 2).coerceAtLeast(1f)
            val zoom = min(availableWidth / canvasWidth, availableHeight / canvasHeight).coerceIn(MIN_ZOOM, MAX_ZOOM)
            return ViewTransform(viewWidth * 0.5f, viewHeight * 0.5f, zoom, 0f)
        }

        private fun wrapDegrees(degrees: Float): Float {
            var d = degrees % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }
    }
}
