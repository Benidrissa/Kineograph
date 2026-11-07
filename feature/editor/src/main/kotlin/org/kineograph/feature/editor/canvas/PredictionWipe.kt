package org.kineograph.feature.editor.canvas

import org.kineograph.engine.selection.Affine

/**
 * The area to clear when a predicted stroke goes away. The clear is an upright rectangle on screen, so on a rotated canvas its
 * corners reach well past the prediction itself. Real stamps must come back everywhere it reaches, which is why this also gives
 * the rectangle's extent in canvas space, and not just the prediction's.
 */
internal class PredictionWipe(
    val viewLeft: Float,
    val viewTop: Float,
    val viewRight: Float,
    val viewBottom: Float,
    val canvasLeft: Float,
    val canvasTop: Float,
    val canvasRight: Float,
    val canvasBottom: Float,
)

/** Works out the [PredictionWipe] for a prediction with the given canvas-space bounds, under the canvas-to-view transform [view]. */
internal fun predictionWipe(view: Affine, left: Float, top: Float, right: Float, bottom: Float, paddingPx: Float): PredictionWipe {
    val onScreen = FloatArray(4)
    mappedBounds(view, left, top, right, bottom, onScreen)
    onScreen[0] -= paddingPx
    onScreen[1] -= paddingPx
    onScreen[2] += paddingPx
    onScreen[3] += paddingPx
    val onCanvas = FloatArray(4)
    val inverse = view.inverse()
    if (inverse != null) {
        mappedBounds(inverse, onScreen[0], onScreen[1], onScreen[2], onScreen[3], onCanvas)
    } else {
        // A view this collapsed shows nothing, so bringing everything back is harmless.
        onCanvas[0] = Float.NEGATIVE_INFINITY
        onCanvas[1] = Float.NEGATIVE_INFINITY
        onCanvas[2] = Float.POSITIVE_INFINITY
        onCanvas[3] = Float.POSITIVE_INFINITY
    }
    return PredictionWipe(onScreen[0], onScreen[1], onScreen[2], onScreen[3], onCanvas[0], onCanvas[1], onCanvas[2], onCanvas[3])
}

/** The upright bounds of a rectangle once [transform] has moved its corners. */
private fun mappedBounds(transform: Affine, left: Float, top: Float, right: Float, bottom: Float, out: FloatArray) {
    out[0] = Float.POSITIVE_INFINITY
    out[1] = Float.POSITIVE_INFINITY
    out[2] = Float.NEGATIVE_INFINITY
    out[3] = Float.NEGATIVE_INFINITY
    for (corner in 0 until 4) {
        val x = if (corner and 1 == 0) left else right
        val y = if (corner and 2 == 0) top else bottom
        val mappedX = transform.mapX(x, y)
        val mappedY = transform.mapY(x, y)
        if (mappedX < out[0]) out[0] = mappedX
        if (mappedY < out[1]) out[1] = mappedY
        if (mappedX > out[2]) out[2] = mappedX
        if (mappedY > out[3]) out[3] = mappedY
    }
}
