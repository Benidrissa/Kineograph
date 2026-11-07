package org.kineograph.feature.editor.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kineograph.engine.ViewTransform
import org.kineograph.engine.brush.StampRun
import org.kineograph.engine.selection.Affine

class PredictionWipeTest {
    private fun runAt(x: Float, y: Float, radius: Float = 2f) = StampRun(FloatArray(0), 1, x - radius, y - radius, x + radius, y + radius)

    private fun StampRun.isRestoredBy(wipe: PredictionWipe) = intersects(
        wipe.canvasLeft,
        wipe.canvasTop,
        wipe.canvasRight,
        wipe.canvasBottom,
    )

    @Test
    fun `on an upright canvas, the wipe is just the prediction and its padding`() {
        val view = ViewTransform(panX = 100f, panY = 50f, zoom = 2f).toAffine(200, 100)
        val wipe = predictionWipe(view, 50f, 50f, 60f, 60f, 2f)
        assertEquals(view.mapX(50f, 50f) - 2f, wipe.viewLeft, 0.01f)
        assertEquals(view.mapY(60f, 60f) + 2f, wipe.viewBottom, 0.01f)
        assertEquals(49f, wipe.canvasLeft, 0.01f)
        assertEquals(61f, wipe.canvasBottom, 0.01f)
    }

    @Test
    fun `on a rotated canvas, stamps in the corners of the wiped box come back too`() {
        val view = ViewTransform(panX = 400f, panY = 400f, zoom = 1f, rotationDegrees = 45f).toAffine(800, 800)
        // A long thin prediction along the canvas diagonal: its box on screen is far larger than it is.
        val left = 300f
        val top = 300f
        val right = 400f
        val bottom = 310f
        val wipe = predictionWipe(view, left, top, right, bottom, 2f)

        // A stamp beside the prediction, away from its canvas rectangle, whose screen position is inside the wiped box.
        val corner = runAt(350f, 255f)
        val screenX = view.mapX(350f, 255f)
        val screenY = view.mapY(350f, 255f)
        assertTrue(screenX > wipe.viewLeft && screenX < wipe.viewRight && screenY > wipe.viewTop && screenY < wipe.viewBottom)
        assertFalse("the old test missed this one, which left a hole", corner.intersects(left, top, right, bottom))
        assertTrue(corner.isRestoredBy(wipe))
    }

    @Test
    fun `everything inside the wiped box lies inside the canvas bounds, whatever the view`() {
        for (degrees in listOf(0f, 13f, 45f, 90f, 137f, 200f, 311f)) {
            for (zoom in listOf(0.25f, 1f, 3.5f)) {
                val view = ViewTransform(panX = 321f, panY = 240f, zoom = zoom, rotationDegrees = degrees).toAffine(640, 480)
                val inverse = view.inverse()!!
                val wipe = predictionWipe(view, 100f, 200f, 180f, 220f, 2f)
                for (i in 0..20) {
                    for (j in 0..20) {
                        val x = wipe.viewLeft + (wipe.viewRight - wipe.viewLeft) * i / 20f
                        val y = wipe.viewTop + (wipe.viewBottom - wipe.viewTop) * j / 20f
                        val canvasX = inverse.mapX(x, y)
                        val canvasY = inverse.mapY(x, y)
                        assertTrue(canvasX >= wipe.canvasLeft - 0.01f && canvasX <= wipe.canvasRight + 0.01f)
                        assertTrue(canvasY >= wipe.canvasTop - 0.01f && canvasY <= wipe.canvasBottom + 0.01f)
                    }
                }
            }
        }
    }

    @Test
    fun `a view with no inverse brings everything back`() {
        val wipe = predictionWipe(Affine(0f, 0f, 0f, 0f, 0f, 0f), 0f, 0f, 1f, 1f, 2f)
        assertTrue(runAt(5000f, -5000f).isRestoredBy(wipe))
    }
}
