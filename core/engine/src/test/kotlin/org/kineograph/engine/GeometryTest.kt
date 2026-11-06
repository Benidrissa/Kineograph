package org.kineograph.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kineograph.engine.selection.Affine
import org.kineograph.engine.selection.SelectionHandle
import org.kineograph.engine.selection.SelectionTransform
import org.kineograph.engine.session.MemoryBudget
import org.kineograph.model.IntRect
import kotlin.math.PI

class GeometryTest {
    private fun assertNear(expected: Float, actual: Float, delta: Float = 0.01f) = assertEquals(expected, actual, delta)

    @Test
    fun `compose applies the inner transform first`() {
        val m = Affine.translation(10f, 0f).compose(Affine.scale(2f, 2f))
        assertNear(12f, m.mapX(1f, 1f))
        assertNear(2f, m.mapY(1f, 1f))
    }

    @Test
    fun `rotation turns clockwise on screen`() {
        val m = Affine.rotation((PI / 2).toFloat())
        assertNear(0f, m.mapX(1f, 0f))
        assertNear(1f, m.mapY(1f, 0f))
    }

    @Test
    fun `inverse undoes a transform`() {
        val m = Affine.translation(5f, -3f).compose(Affine.rotation(0.7f)).compose(Affine.scale(2f, 0.5f))
        val inverse = m.inverse()
        assertNotNull(inverse)
        val i = inverse ?: return
        val x = m.mapX(11f, 4f)
        val y = m.mapY(11f, 4f)
        assertNear(11f, i.mapX(x, y))
        assertNear(4f, i.mapY(x, y))
    }

    @Test
    fun `a collapsed transform has no inverse`() {
        assertNull(Affine.scale(0f, 1f).inverse())
    }

    @Test
    fun `matrix values follow Android's layout`() {
        val values = FloatArray(9)
        Affine(1f, 2f, 3f, 4f, 5f, 6f).toMatrixValues(values)
        assertEquals(listOf(1f, 3f, 5f, 2f, 4f, 6f, 0f, 0f, 1f), values.toList())
    }

    @Test
    fun `fit centres the canvas at the largest size that fits`() {
        val t = ViewTransform.fit(1000, 500, 200, 100, 0f)
        assertNear(5f, t.zoom)
        val m = t.toAffine(200, 100)
        assertNear(0f, m.mapX(0f, 0f))
        assertNear(0f, m.mapY(0f, 0f))
        assertNear(1000f, m.mapX(200f, 100f))
        assertNear(500f, m.mapY(200f, 100f))
    }

    @Test
    fun `fit leaves room for the padding`() {
        val t = ViewTransform.fit(1000, 1000, 100, 100, 50f)
        assertNear(9f, t.zoom)
    }

    @Test
    fun `panning moves the canvas by exactly the finger movement`() {
        val start = ViewTransform.fit(1000, 1000, 100, 100, 0f)
        val moved = start.afterGesture(100, 100, 500f, 500f, 530f, 480f, 1f, 0f)
        val before = start.toAffine(100, 100)
        val after = moved.toAffine(100, 100)
        assertNear(before.mapX(10f, 10f) + 30f, after.mapX(10f, 10f))
        assertNear(before.mapY(10f, 10f) - 20f, after.mapY(10f, 10f))
    }

    @Test
    fun `pinching keeps the point under the fingers in place`() {
        val start = ViewTransform.fit(1000, 1000, 100, 100, 0f)
        val inverse = start.toAffine(100, 100).inverse() ?: error("no inverse")
        val anchorX = inverse.mapX(300f, 700f)
        val anchorY = inverse.mapY(300f, 700f)
        val zoomed = start.afterGesture(100, 100, 300f, 700f, 300f, 700f, 2f, 25f)
        val after = zoomed.toAffine(100, 100)
        assertNear(300f, after.mapX(anchorX, anchorY), 0.1f)
        assertNear(700f, after.mapY(anchorX, anchorY), 0.1f)
        assertNear(start.zoom * 2f, zoomed.zoom)
        assertNear(25f, zoomed.rotationDegrees)
    }

    @Test
    fun `zoom stays within limits`() {
        val t = ViewTransform().afterGesture(100, 100, 0f, 0f, 0f, 0f, 1e9f, 0f)
        assertEquals(ViewTransform.MAX_ZOOM, t.zoom, 0f)
    }

    @Test
    fun `small rotations snap back to upright`() {
        assertEquals(0f, ViewTransform(rotationDegrees = 2f).snappedRotation().rotationDegrees, 0f)
        assertEquals(30f, ViewTransform(rotationDegrees = 30f).snappedRotation().rotationDegrees, 0f)
    }

    // ---- Selection transforms ----

    private val source = IntRect(100, 100, 200, 200)

    @Test
    fun `identity leaves pixels where they are`() {
        val t = SelectionTransform(source)
        assertTrue(t.isIdentity)
        val m = t.toAffine()
        assertNear(120f, m.mapX(120f, 130f))
        assertNear(130f, m.mapY(120f, 130f))
    }

    @Test
    fun `moving translates`() {
        val t = SelectionTransform(source).dragged(SelectionHandle.MOVE, 150f, 150f, 170f, 140f)
        assertNear(20f, t.translateX)
        assertNear(-10f, t.translateY)
        assertNear(120f, t.toAffine().mapX(100f, 100f))
    }

    @Test
    fun `dragging a corner outward scales about the centre`() {
        val t = SelectionTransform(source).dragged(SelectionHandle.BOTTOM_RIGHT, 200f, 200f, 250f, 250f)
        assertNear(2f, t.scale)
        val m = t.toAffine()
        assertNear(150f, m.mapX(150f, 150f))
        assertNear(250f, m.mapX(200f, 200f))
        assertNear(50f, m.mapX(100f, 100f))
    }

    @Test
    fun `dragging the knob rotates about the centre`() {
        val t = SelectionTransform(source).dragged(SelectionHandle.ROTATE, 150f, 50f, 250f, 150f)
        assertNear((PI / 2).toFloat(), t.rotation)
        val m = t.toAffine()
        assertNear(150f, m.mapX(150f, 150f))
        assertNear(150f, m.mapY(150f, 150f))
        // The top-middle point rotates round to the right-middle.
        assertNear(200f, m.mapX(150f, 100f))
        assertNear(150f, m.mapY(150f, 100f))
    }

    @Test
    fun `scale stays within limits and a tiny drag near the centre is ignored`() {
        val shrunk = SelectionTransform(source).dragged(SelectionHandle.TOP_LEFT, 100f, 100f, 149.99f, 149.99f)
        assertTrue(shrunk.scale >= SelectionTransform.MIN_SCALE)
        val unchanged = SelectionTransform(source).dragged(SelectionHandle.TOP_LEFT, 150f, 150f, 300f, 300f)
        assertEquals(1f, unchanged.scale, 0f)
    }

    @Test
    fun `hit test finds handles first, then the body, then nothing`() {
        val t = SelectionTransform(source)
        assertEquals(SelectionHandle.TOP_LEFT, t.hitTest(102f, 98f, 10f, 40f))
        assertEquals(SelectionHandle.BOTTOM_RIGHT, t.hitTest(198f, 202f, 10f, 40f))
        assertEquals(SelectionHandle.ROTATE, t.hitTest(150f, 60f, 10f, 40f))
        assertEquals(SelectionHandle.MOVE, t.hitTest(150f, 150f, 10f, 40f))
        assertNull(t.hitTest(500f, 500f, 10f, 40f))
    }

    @Test
    fun `hit test follows the transform`() {
        val t = SelectionTransform(source, translateX = 300f)
        assertEquals(SelectionHandle.MOVE, t.hitTest(450f, 150f, 10f, 40f))
        assertNull(t.hitTest(150f, 150f, 10f, 40f))
    }

    @Test
    fun `bounds contain every corner`() {
        val t = SelectionTransform(source, rotation = 0.5f, scale = 1.5f, translateX = 7f)
        val bounds = t.bounds()
        val c = t.corners()
        for (i in 0 until 4) assertTrue(bounds.contains(c[i * 2].toInt(), c[i * 2 + 1].toInt()))
    }

    // ---- Memory budget ----

    @Test
    fun `budgets scale with the device`() {
        val small = MemoryBudget.forMemoryClass(128, isLowRam = true)
        val large = MemoryBudget.forMemoryClass(512, isLowRam = false)
        assertTrue(small.compressedCelCacheBytes < large.compressedCelCacheBytes)
        assertTrue(small.playbackCacheBytes < large.playbackCacheBytes)
        assertTrue(small.ghostMaxEdge < large.ghostMaxEdge)
    }

    @Test
    fun `the drawable margin scales with the canvas`() {
        val budget = MemoryBudget.forMemoryClass(256, isLowRam = false)
        assertEquals(135, budget.overscanFor(1920, 1080))
        assertEquals(8, budget.overscanFor(64, 64))
    }
}
