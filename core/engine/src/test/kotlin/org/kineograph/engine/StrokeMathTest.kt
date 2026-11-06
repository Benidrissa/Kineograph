package org.kineograph.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kineograph.engine.brush.BrushPresets
import org.kineograph.engine.brush.BrushSpec
import org.kineograph.engine.brush.CurvePoint
import org.kineograph.engine.brush.FingerPressure
import org.kineograph.engine.brush.PressureCurve
import org.kineograph.engine.brush.PressureSmoother
import org.kineograph.engine.brush.StampBuffer
import org.kineograph.engine.brush.StrokeBuilder
import org.kineograph.engine.brush.StrokeEngine
import org.kineograph.engine.brush.StrokeOptions
import org.kineograph.engine.brush.StrokeStabilizer
import org.kineograph.engine.brush.TipFactory
import org.kineograph.engine.brush.TipShape
import kotlin.math.abs
import kotlin.math.hypot

class StrokeMathTest {
    private val flatPen = BrushSpec(id = "t", sizePx = 10f, spacing = 0.5f, sizeCurve = PressureCurve.FLAT)

    private fun straightLine(spec: BrushSpec, length: Float, steps: Int = 20): StampBuffer {
        val engine = StrokeEngine(spec, 1L)
        val out = StampBuffer()
        engine.begin(0f, 0f, 1f, 0f, 0f, out)
        for (i in 1..steps) engine.add(length * i / steps, 0f, 1f, 0f, 0f, out)
        engine.finish(out)
        return out
    }

    // ---- Pressure curves ----

    @Test
    fun `linear curve returns its input`() {
        assertEquals(0.25f, PressureCurve.LINEAR.evaluate(0.25f), 0.001f)
        assertEquals(0.8f, PressureCurve.LINEAR.evaluate(0.8f), 0.001f)
    }

    @Test
    fun `curve clamps out of range input`() {
        assertEquals(0f, PressureCurve.LINEAR.evaluate(-3f), 0f)
        assertEquals(1f, PressureCurve.LINEAR.evaluate(9f), 0f)
    }

    @Test
    fun `ramp starts at the low value`() {
        val curve = PressureCurve.ramp(0.4f)
        assertEquals(0.4f, curve.evaluate(0f), 0.001f)
        assertEquals(1f, curve.evaluate(1f), 0.001f)
        assertEquals(0.7f, curve.evaluate(0.5f), 0.002f)
    }

    @Test
    fun `curve follows control points including unsorted ones`() {
        val curve = PressureCurve(listOf(CurvePoint(1f, 1f), CurvePoint(0f, 0f), CurvePoint(0.5f, 0.9f)))
        assertEquals(0.9f, curve.evaluate(0.5f), 0.002f)
        assertEquals(0.45f, curve.evaluate(0.25f), 0.002f)
    }

    @Test
    fun `flat curve ignores pressure`() {
        assertEquals(1f, PressureCurve.FLAT.evaluate(0f), 0f)
        assertEquals(1f, PressureCurve.FLAT.evaluate(0.5f), 0f)
    }

    // ---- Stamp spacing ----

    @Test
    fun `stamps are spaced by a fraction of the diameter`() {
        val out = straightLine(flatPen, 100f)
        // Diameter 10, spacing 0.5: a stamp every 5 pixels over 100 pixels, plus the first.
        assertEquals(21, out.count)
        for (i in 1 until out.count) {
            assertEquals(5f, out.x(i) - out.x(i - 1), 0.05f)
            assertEquals(0f, out.y(i), 0.001f)
        }
    }

    @Test
    fun `spacing does not depend on how many input points there are`() {
        val few = straightLine(flatPen, 100f, steps = 3)
        val many = straightLine(flatPen, 100f, steps = 97)
        assertTrue(abs(few.count - many.count) <= 1)
    }

    @Test
    fun `a tap leaves one stamp`() {
        val engine = StrokeEngine(flatPen, 1L)
        val out = StampBuffer()
        engine.begin(5f, 7f, 1f, 0f, 0f, out)
        engine.finish(out)
        assertEquals(1, out.count)
        assertEquals(5f, out.x(0), 0f)
        assertEquals(7f, out.y(0), 0f)
    }

    @Test
    fun `lighter pressure makes a smaller stamp and tighter spacing`() {
        val spec = BrushSpec(id = "t", sizePx = 20f, spacing = 0.5f, sizeCurve = PressureCurve.LINEAR)
        val engine = StrokeEngine(spec, 1L)
        val out = StampBuffer()
        engine.begin(0f, 0f, 0.5f, 0f, 0f, out)
        for (i in 1..50) engine.add(i * 2f, 0f, 0.5f, 0f, 0f, out)
        engine.finish(out)
        assertEquals(10f, out.diameter(1), 0.01f)
        assertEquals(5f, out.x(2) - out.x(1), 0.1f)
    }

    @Test
    fun `the stroke ends exactly where the input ended`() {
        val out = straightLine(BrushSpec(id = "t", sizePx = 2f, spacing = 0.1f, sizeCurve = PressureCurve.FLAT), 50f)
        // Within one stamp spacing, which has a floor of 0.35 px.
        assertEquals(50f, out.x(out.count - 1), 0.36f)
    }

    @Test
    fun `curved strokes are smooth with no wide gaps`() {
        val engine = StrokeEngine(flatPen, 1L)
        val out = StampBuffer()
        engine.begin(0f, 0f, 1f, 0f, 0f, out)
        // Sharp zig-zag input.
        for (i in 1..40) engine.add(i * 10f, if (i % 2 == 0) 0f else 30f, 1f, 0f, 0f, out)
        engine.finish(out)
        for (i in 1 until out.count) {
            val gap = hypot(out.x(i) - out.x(i - 1), out.y(i) - out.y(i - 1))
            assertTrue("gap $gap at stamp $i", gap <= 5.5f)
        }
    }

    @Test
    fun `prediction does not change the final stroke`() {
        val plain = StrokeEngine(flatPen, 42L)
        val predicting = StrokeEngine(flatPen, 42L)
        val plainOut = StampBuffer()
        val predictingOut = StampBuffer()
        val scratch = StampBuffer()
        plain.begin(0f, 0f, 1f, 0f, 0f, plainOut)
        predicting.begin(0f, 0f, 1f, 0f, 0f, predictingOut)
        for (i in 1..30) {
            val x = i * 7f
            val y = i * i * 0.1f
            plain.add(x, y, 1f, 0f, 0f, plainOut)
            predicting.add(x, y, 1f, 0f, 0f, predictingOut)
            scratch.clear()
            predicting.predict(floatArrayOf(x + 20f, y + 40f, 1f, 0f, 0f), 1, scratch)
        }
        plain.finish(plainOut)
        predicting.finish(predictingOut)
        assertEquals(plainOut.count, predictingOut.count)
        for (i in 0 until plainOut.count) {
            assertEquals(plainOut.x(i), predictingOut.x(i), 0f)
            assertEquals(plainOut.y(i), predictingOut.y(i), 0f)
        }
    }

    @Test
    fun `prediction reaches the last real point even with no predicted points`() {
        val spec = BrushSpec(id = "t", sizePx = 2f, spacing = 0.1f, sizeCurve = PressureCurve.FLAT)
        val engine = StrokeEngine(spec, 1L)
        val finalStamps = StampBuffer()
        val predicted = StampBuffer()
        engine.begin(0f, 0f, 1f, 0f, 0f, finalStamps)
        engine.add(100f, 0f, 1f, 0f, 0f, finalStamps)
        // The midpoint curve only finalizes up to x = 50; the tail must still be drawn so nothing lags.
        assertTrue(finalStamps.x(finalStamps.count - 1) <= 50.5f)
        engine.predict(FloatArray(0), 0, predicted)
        assertEquals(100f, predicted.x(predicted.count - 1), 0.5f)
    }

    @Test
    fun `jitter is repeatable for the same seed`() {
        val spec = BrushSpec(id = "t", sizePx = 20f, spacing = 0.2f, sizeJitter = 0.5f, scatter = 0.5f, sizeCurve = PressureCurve.FLAT)
        val a = straightLine(spec, 100f)
        val b = straightLine(spec, 100f)
        assertEquals(a.count, b.count)
        for (i in 0 until a.count) assertEquals(a.y(i), b.y(i), 0f)
        assertTrue((0 until a.count).any { a.y(it) != 0f })
    }

    @Test
    fun `aliased brushes place stamps on pixel centres one pixel apart`() {
        val spec = BrushSpec(id = "t", tip = TipShape.SQUARE, sizePx = 1f, antiAlias = false, sizeCurve = PressureCurve.FLAT)
        val out = straightLine(spec, 10f)
        for (i in 0 until out.count) assertEquals(0.5f, out.x(i) - kotlin.math.floor(out.x(i)), 0f)
    }

    @Test
    fun `pressure reduces flow when the flow curve says so`() {
        val spec = BrushSpec(id = "t", sizePx = 10f, flow = 1f, flowCurve = PressureCurve.LINEAR, sizeCurve = PressureCurve.FLAT)
        val engine = StrokeEngine(spec, 1L)
        val out = StampBuffer()
        engine.begin(0f, 0f, 0.25f, 0f, 0f, out)
        assertEquals(0.25f, out.alpha(0), 0.01f)
    }

    @Test
    fun `tilt shading widens and lightens the stamp`() {
        val upright = StampBuffer()
        val tilted = StampBuffer()
        val spec = BrushPresets.PENCIL.copy(sizeJitter = 0f, scatter = 0f)
        StrokeEngine(spec, 1L).begin(0f, 0f, 1f, 0f, 0f, upright)
        StrokeEngine(spec, 1L).begin(0f, 0f, 1f, 1.2f, 0f, tilted)
        assertTrue(tilted.diameter(0) > upright.diameter(0))
        assertTrue(tilted.alpha(0) < upright.alpha(0))
    }

    @Test
    fun `the stamp buffer grows and tracks bounds`() {
        val buffer = StampBuffer(1)
        repeat(100) { buffer.add(it.toFloat(), 0f, 2f, 1f, 0f) }
        val run = buffer.snapshot()
        assertEquals(100, run.count)
        assertTrue(run.minX < 0f)
        assertTrue(run.maxX > 99f)
        assertTrue(run.intersects(50f, -1f, 51f, 1f))
        assertTrue(!run.intersects(500f, 500f, 501f, 501f))
    }

    // ---- Stabilizer ----

    @Test
    fun `zero smoothing passes input through`() {
        val stabilizer = StrokeStabilizer(0, 20f)
        stabilizer.reset(0f, 0f)
        stabilizer.push(10f, 5f)
        assertEquals(10f, stabilizer.x, 0f)
        assertEquals(5f, stabilizer.y, 0f)
    }

    @Test
    fun `small wobbles do not move a strongly stabilized brush`() {
        val stabilizer = StrokeStabilizer(100, 20f)
        stabilizer.reset(0f, 0f)
        repeat(20) { i ->
            stabilizer.push(if (i % 2 == 0) 3f else -3f, 0f)
            assertEquals(0f, stabilizer.x, 0.001f)
        }
    }

    @Test
    fun `a long pull drags the brush along and it stays a string length behind`() {
        val stabilizer = StrokeStabilizer(100, 20f)
        stabilizer.reset(0f, 0f)
        repeat(500) { stabilizer.push(1000f, 0f) }
        assertEquals(980f, stabilizer.x, 0.5f)
    }

    @Test
    fun `more smoothing reduces jitter`() {
        fun roughness(amount: Int): Float {
            val stabilizer = StrokeStabilizer(amount, 8f)
            stabilizer.reset(0f, 0f)
            var total = 0f
            var last = 0f
            for (i in 1..200) {
                stabilizer.push(i * 2f, if (i % 2 == 0) 6f else -6f)
                total += abs(stabilizer.y - last)
                last = stabilizer.y
            }
            return total
        }
        assertTrue(roughness(80) < roughness(30))
        assertTrue(roughness(30) < roughness(0))
    }

    @Test
    fun `a stabilized stroke still finishes at the pen's real position`() {
        val builder = StrokeBuilder(flatPen, StrokeOptions(smoothing = 100, stringLengthPx = 30f), 1L)
        builder.begin(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0L)
        for (i in 1..50) builder.add(i * 4f, 0f, i * 4f, 0f, 1f, 0f, 0f, i * 8L)
        builder.finish()
        val last = builder.stamps.count - 1
        assertTrue(abs(builder.stamps.x(last) - 200f) <= 5.1f)
    }

    @Test
    fun `pressure smoother eases between values`() {
        val smoother = PressureSmoother(100)
        assertEquals(0.2f, smoother.next(0.2f), 0f)
        val next = smoother.next(1f)
        assertTrue(next > 0.2f && next < 1f)
    }

    // ---- Finger pressure ----

    @Test
    fun `slow finger movement ends up heavier than fast movement`() {
        fun settle(pxPerSample: Float): Float {
            val finger = FingerPressure(fastPxPerMs = 2f)
            finger.reset(0f, 0f, 0L)
            var pressure = 0f
            for (i in 1..120) pressure = finger.next(i * pxPerSample, 0f, i * 8L)
            return pressure
        }
        val slow = settle(0.5f)
        val fast = settle(40f)
        assertTrue("slow=$slow fast=$fast", slow > 0.9f)
        assertTrue("slow=$slow fast=$fast", fast < 0.4f)
    }

    @Test
    fun `simulated pressure changes smoothly with no jumps`() {
        val finger = FingerPressure(fastPxPerMs = 2f)
        finger.reset(0f, 0f, 0L)
        var last = finger.start()
        var x = 0f
        for (i in 1..120) {
            x += if (i < 60) 1f else 50f
            val p = finger.next(x, 0f, i * 8L)
            assertTrue(abs(p - last) < 0.25f)
            last = p
        }
    }

    @Test
    fun `identical timestamps do not break simulated pressure`() {
        val finger = FingerPressure(fastPxPerMs = 2f)
        finger.reset(0f, 0f, 100L)
        val p = finger.next(10f, 0f, 100L)
        assertTrue(p in 0f..1f)
    }

    @Test
    fun `the builder uses simulated pressure only when asked`() {
        val spec = BrushSpec(id = "t", sizePx = 10f, sizeCurve = PressureCurve.LINEAR)
        val simulated = StrokeBuilder(spec, StrokeOptions(simulatePressure = true), 1L)
        simulated.begin(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0L)
        assertTrue(simulated.stamps.diameter(0) < 10f)
        val real = StrokeBuilder(spec, StrokeOptions(), 1L)
        real.begin(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0L)
        assertEquals(10f, real.stamps.diameter(0), 0.01f)
    }

    // ---- Tips ----

    @Test
    fun `hard round tip is solid in the middle and empty in the corners`() {
        val mask = TipFactory.alphaMask(TipShape.ROUND, 32, 30f, 1f, 1f, 0f)
        assertEquals(255, mask[16 * 32 + 16].toInt() and 0xFF)
        assertEquals(0, mask[0].toInt() and 0xFF)
    }

    @Test
    fun `soft tip fades from the centre`() {
        val mask = TipFactory.alphaMask(TipShape.ROUND, 64, 62f, 0f, 1f, 0f)
        val center = mask[32 * 64 + 32].toInt() and 0xFF
        val halfway = mask[32 * 64 + 48].toInt() and 0xFF
        val edge = mask[32 * 64 + 62].toInt() and 0xFF
        assertTrue(center > halfway)
        assertTrue(halfway > edge)
    }

    @Test
    fun `chisel tip is narrower in one direction`() {
        val mask = TipFactory.alphaMask(TipShape.ROUND, 32, 30f, 1f, 0.3f, 0f)
        assertEquals(255, mask[16 * 32 + 28].toInt() and 0xFF)
        assertEquals(0, mask[28 * 32 + 16].toInt() and 0xFF)
    }

    @Test
    fun `grain breaks up a tip`() {
        val mask = TipFactory.alphaMask(TipShape.GRAIN, 32, 30f, 1f, 1f, 1f)
        val values = (10..20).flatMap { y -> (10..20).map { x -> mask[y * 32 + x].toInt() and 0xFF } }.toSet()
        assertTrue(values.size > 5)
    }

    @Test
    fun `square tips are entirely solid`() {
        val mask = TipFactory.alphaMask(TipShape.SQUARE, 4, 4f, 1f, 1f, 0f)
        assertTrue(mask.all { it.toInt() and 0xFF == 255 })
    }
}
