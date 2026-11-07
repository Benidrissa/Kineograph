package org.kineograph.media.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.kineograph.engine.brush.BoundsAccumulator
import org.kineograph.engine.brush.BrushPresets
import org.kineograph.engine.brush.BrushSpec
import org.kineograph.engine.brush.PressureCurve
import org.kineograph.engine.brush.StampBuffer
import org.kineograph.engine.brush.StampPainter
import org.kineograph.engine.brush.StrokeBuilder
import org.kineograph.engine.brush.StrokeEngine
import org.kineograph.engine.brush.StrokeMerge
import org.kineograph.engine.brush.StrokeOptions
import org.kineograph.engine.brush.StrokeRecording
import org.kineograph.engine.brush.TipCache
import org.kineograph.engine.vector.ReplayDifference
import org.kineograph.engine.vector.StrokeReplay
import org.kineograph.engine.vector.VectorCodec
import org.kineograph.engine.vector.VectorFileEncoder
import org.kineograph.engine.vector.VectorGap
import org.kineograph.engine.vector.VectorStroke
import org.kineograph.engine.vector.VectorVisitor
import org.kineograph.media.svg.PathSink
import org.kineograph.media.svg.SvgDocument
import org.kineograph.media.svg.SvgLimits
import org.kineograph.media.svg.SvgParser
import org.kineograph.media.svg.SvgWarning
import org.kineograph.media.svg.parsePathData
import org.kineograph.model.IntRect
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Whether what is saved about a stroke has the shape the later steps need: replay, in-betweens and SVG. The helpers here are only a proof
 * that the data is enough. The real ones belong to those steps. Everything runs on real Skia through Robolectric, which is a desktop one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StrokeShapeTest {
    private val width = 400
    private val height = 300
    private val region = IntRect(0, 0, width, height)

    /** Feeds a builder, and paints and merges what comes out, the way the view and DrawingSession.commitStroke do. */
    private class Drawing(val width: Int, val height: Int) {
        val layer: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        private val buffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        private val layerCanvas = Canvas(layer)
        private val bufferCanvas = Canvas(buffer)
        private val painter = StampPainter(TipCache())
        private val merge = StrokeMerge()
        private val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val strokes = ArrayList<VectorStroke>()

        fun draw(spec: BrushSpec, color: Int, seed: Long, input: List<FloatArray>, options: StrokeOptions = StrokeOptions()): VectorStroke {
            val recording = StrokeRecording(spec, seed, false, Long.MAX_VALUE)
            val builder = StrokeBuilder(spec, options, seed, recording)
            val bounds = BoundsAccumulator()
            fun drain() {
                if (builder.stamps.count == 0) return
                val run = builder.stamps.snapshot()
                builder.stamps.clear()
                painter.paint(bufferCanvas, spec, color, run)
                bounds.add(run)
            }
            input.forEachIndexed { i, s ->
                if (i == 0) {
                    builder.begin(s[0], s[1], s[0], s[1], s[2], s[3], s[4], i.toLong())
                } else {
                    builder.add(s[0], s[1], s[0], s[1], s[2], s[3], s[4], i.toLong())
                }
                drain()
            }
            builder.finish()
            drain()
            val rounded = bounds.roundedOut()
            val rect = IntRect(rounded[0], rounded[1], rounded[2], rounded[3]).intersect(IntRect(0, 0, width, height))
            merge.merge(layerCanvas, buffer, spec, rect)
            bufferCanvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), clear)
            val stroke = saveAndRead(recording, color, IntRect(rounded[0], rounded[1], rounded[2], rounded[3]))
            strokes += stroke
            return stroke
        }

        private fun saveAndRead(recording: StrokeRecording, color: Int, bounds: IntRect): VectorStroke {
            val out = ByteArrayOutputStream()
            val encoder = VectorFileEncoder(out, IntRect(0, 0, width, height))
            assertTrue(encoder.addStroke(1L, recording, color, bounds, null))
            encoder.finish()
            var read: VectorStroke? = null
            VectorCodec.decodeFile(
                out.toByteArray(),
                null,
                object : VectorVisitor {
                    override fun onStroke(stroke: VectorStroke) {
                        read = stroke
                    }

                    override fun onGap(gap: VectorGap) = error("Only a stroke was saved")
                },
            )
            return checkNotNull(read)
        }
    }

    /**
     * A hand-like path through [path], with tremor and noise, at 240 Hz. [pressure] and tilt are held, unless [varyPressure] says
     * otherwise.
     */
    private fun scribed(
        count: Int,
        seed: Long,
        pressure: Float = 0.8f,
        varyPressure: Boolean = false,
        path: (Float) -> Pair<Float, Float>,
    ): List<FloatArray> {
        val random = Random(seed)
        return List(count) { i ->
            val t = i.toFloat() / (count - 1).coerceAtLeast(1)
            val (x, y) = path(t)
            val p = if (varyPressure) (0.5f + 0.4f * sin(2f * PI.toFloat() * t)) else pressure
            floatArrayOf(
                x + (random.nextGaussian() * 0.06).toFloat() + 0.2f * sin(0.25f * i + seed),
                y + (random.nextGaussian() * 0.06).toFloat() + 0.2f * cos(0.31f * i + seed),
                p,
                0.6f + (random.nextGaussian() * 0.002).toFloat(),
                0.9f + (random.nextGaussian() * 0.002).toFloat(),
            )
        }
    }

    private val wave: (Float) -> Pair<Float, Float> = { t -> (40f + 320f * t) to (150f + 90f * sin(2f * PI.toFloat() * 1.2f * t)) }

    private fun replayOf(strokes: List<VectorStroke>): StrokeReplay = StrokeReplay(region).also { replay ->
        strokes.forEach {
            replay.replay(it)
        }
    }

    private fun difference(a: Bitmap, b: Bitmap): ReplayDifference = ReplayDifference.between(a, 0, 0, b, 0, 0)

    // ---- 1 and 2: pixels, then bytes, then pixels again ----

    @Test
    fun `saved and read back each preset gives the pixels it drew`() {
        val input = scribed(400, 3L, varyPressure = true, path = wave)
        for (spec in listOf(BrushPresets.PEN, BrushPresets.PENCIL, BrushPresets.MARKER)) {
            val drawing = Drawing(width, height)
            drawing.draw(spec, Color.BLACK, 77L, input)
            val b = replayOf(drawing.strokes)
            val found = difference(drawing.layer, b.layer)
            println("SHAPE ${spec.id} against its replay: $found")
            assertEquals("${spec.id}: $found", 0, found.max)
            b.release()
        }
    }

    @Test
    fun `saved and read back an eraser gives the pixels it left`() {
        val drawing = Drawing(width, height)
        drawing.draw(BrushPresets.MARKER.copy(sizePx = 60f), Color.BLACK, 1L, scribed(300, 4L, path = wave))
        drawing.draw(
            BrushPresets.ERASER,
            Color.BLACK,
            2L,
            scribed(300, 5L, varyPressure = true) { t ->
                (40f + 320f * t) to
                    (150f + 40f * cos(6f * t))
            },
        )
        val b = replayOf(drawing.strokes)
        val found = difference(drawing.layer, b.layer)
        println("SHAPE eraser against its replay: $found")
        assertEquals("$found", 0, found.max)
        b.release()
    }

    // ---- 3: two strokes paired and interpolated ----

    /** A stroke between two others, sample by sample, for strokes of the same length. */
    private fun between(a: VectorStroke, b: VectorStroke, t: Float): FloatArray {
        assertEquals(a.count, b.count)
        assertEquals(a.stride, b.stride)
        return FloatArray(a.samples.size) { i -> a.samples[i] + (b.samples[i] - a.samples[i]) * t }
    }

    private fun stampsOf(spec: BrushSpec, seed: Long, samples: FloatArray, count: Int, stride: Int): StampBuffer {
        val engine = StrokeEngine(spec, seed)
        val out = StampBuffer()
        for (i in 0 until count) {
            val at = i * stride
            val tilt = if (stride == StrokeRecording.STRIDE_WITH_TILT) samples[at + 3] else 0f
            val orientation = if (stride == StrokeRecording.STRIDE_WITH_TILT) samples[at + 4] else 0f
            if (i == 0) {
                engine.begin(samples[at], samples[at + 1], samples[at + 2], tilt, orientation, out)
            } else {
                engine.add(samples[at], samples[at + 1], samples[at + 2], tilt, orientation, out)
            }
        }
        engine.finish(out)
        return out
    }

    private fun moved(input: List<FloatArray>, dx: Float, dy: Float): List<FloatArray> =
        input.map { s -> floatArrayOf(s[0] + dx, s[1] + dy, s[2], s[3], s[4]) }

    @Test
    fun `an in-between at the start is the first stroke and at the end is the second`() {
        val spec = BrushPresets.PENCIL
        val input = scribed(300, 6L, varyPressure = true, path = wave)
        val first = Drawing(width, height).also { it.draw(spec, Color.BLACK, 11L, input) }
        val second = Drawing(width, height).also { it.draw(spec, Color.BLACK, 11L, moved(input, 12f, -9f)) }
        val a = first.strokes.single()
        val b = second.strokes.single()

        val atStart = StrokeReplay(region).also { it.replay(spec, a.seed, a.colorArgb, between(a, b, 0f), a.count, a.stride) }
        val found = difference(first.layer, atStart.layer)
        println("SHAPE an in-between at t = 0 against the first stroke: $found")
        assertEquals("$found", 0, found.max)

        val atEnd = StrokeReplay(region).also { it.replay(spec, a.seed, a.colorArgb, between(a, b, 1f), a.count, a.stride) }
        val foundEnd = difference(second.layer, atEnd.layer)
        println("SHAPE an in-between at t = 1 against the second stroke: $foundEnd")
        assertTrue("$foundEnd", foundEnd.isWithinTolerance)
        atStart.release()
        atEnd.release()
    }

    @Test
    fun `pencil jitter stays put across an in-between with one seed`() {
        val spec = BrushPresets.PENCIL
        val input = scribed(300, 6L, varyPressure = true, path = wave)
        val dx = 24f
        val dy = -16f
        val a = Drawing(width, height).also { it.draw(spec, Color.BLACK, 11L, input) }.strokes.single()
        val b = Drawing(width, height).also { it.draw(spec, Color.BLACK, 99L, moved(input, dx, dy)) }.strokes.single()

        val start = stampsOf(spec, a.seed, a.samples, a.count, a.stride)
        for (t in listOf(0.25f, 0.5f, 0.75f)) {
            val mixed = stampsOf(spec, a.seed, between(a, b, t), a.count, a.stride)
            assertEquals("the same number of stamps at $t", start.count, mixed.count)
            var worst = 0f
            for (k in 0 until start.count) {
                worst = maxOf(worst, hypot(mixed.x(k) - start.x(k) - dx * t, mixed.y(k) - start.y(k) - dy * t))
                assertEquals("stamp $k changed size at $t", start.diameter(k), mixed.diameter(k), 0.01f)
            }
            println("SHAPE pencil at t = $t: the furthest a stamp strays from where it belongs is $worst px")
            assertTrue("stamps strayed by $worst at $t", worst < 0.02f)
        }

        // What the seed buys: another one moves every stamp's jitter, which is what would flicker from frame to frame.
        val other = stampsOf(spec, b.seed, between(a, b, 0.5f), a.count, a.stride)
        var strayed = 0f
        for (k in 0 until minOf(start.count, other.count)) {
            strayed = maxOf(strayed, hypot(other.x(k) - start.x(k) - dx * 0.5f, other.y(k) - start.y(k) - dy * 0.5f))
        }
        println("SHAPE pencil at t = 0.5 with the other seed: stamps stray by $strayed px")
        assertTrue("another seed should move the jitter, but only by $strayed", strayed > 0.1f)
    }

    @Test
    fun `pencil grain moves with the stroke and does not flicker`() {
        val spec = BrushPresets.PENCIL
        val input = scribed(300, 6L, varyPressure = true, path = wave)
        val first = Drawing(width, height).also { it.draw(spec, Color.BLACK, 11L, input) }
        val a = first.strokes.single()
        val b = Drawing(width, height).also { it.draw(spec, Color.BLACK, 99L, moved(input, 24f, -16f)) }.strokes.single()

        val halfway = StrokeReplay(region).also { it.replay(spec, a.seed, a.colorArgb, between(a, b, 0.5f), a.count, a.stride) }
        val sameSeed = ReplayDifference.between(first.layer, 12, -8, halfway.layer, 0, 0)
        println("SHAPE pencil halfway with the first seed against the first frame moved along: $sameSeed")

        val otherSeed = StrokeReplay(region).also { it.replay(spec, b.seed, a.colorArgb, between(a, b, 0.5f), a.count, a.stride) }
        val flicker = ReplayDifference.between(first.layer, 12, -8, otherSeed.layer, 0, 0)
        println("SHAPE pencil halfway with the other seed against the first frame moved along: $flicker")
        assertTrue("one seed should stay far closer than two: $sameSeed against $flicker", sameSeed.mean < flicker.mean / 4)
        halfway.release()
        otherSeed.release()
    }

    @Test
    fun `turning a stroke by its placement keeps its length`() {
        val spec = BrushPresets.PEN.copy(sizeCurve = PressureCurve.FLAT)
        val pivotX = 200f
        val pivotY = 200f
        val line = List(101) { i -> floatArrayOf(pivotX + i * 2f, pivotY, 0.8f, 0f, 0f) }
        val turned = line.map { s -> floatArrayOf(pivotX - (s[1] - pivotY), pivotY + (s[0] - pivotX), s[2], s[3], s[4]) }
        val a = Drawing(width, height).also { it.draw(spec, Color.BLACK, 1L, line) }.strokes.single()
        val b = Drawing(width, height).also { it.draw(spec, Color.BLACK, 1L, turned) }.strokes.single()

        fun reach(samples: FloatArray): Float {
            val stamps = stampsOf(spec, a.seed, samples, a.count, a.stride)
            var path = 0f
            for (k in 1 until stamps.count) path += hypot(stamps.x(k) - stamps.x(k - 1), stamps.y(k) - stamps.y(k - 1))
            return path
        }

        // Halfway by placement: the samples of the first stroke, turned by half the angle about the pivot.
        val angle = PI.toFloat() / 4f
        val placed = a.samples.copyOf()
        for (i in 0 until a.count) {
            val at = i * a.stride
            val x = a.samples[at] - pivotX
            val y = a.samples[at + 1] - pivotY
            placed[at] = pivotX + x * cos(angle) - y * sin(angle)
            placed[at + 1] = pivotY + x * sin(angle) + y * cos(angle)
        }
        val whole = reach(a.samples)
        val byPlacement = reach(placed)
        val byPoints = reach(between(a, b, 0.5f))
        println("SHAPE a stroke of $whole px, turned 90 degrees: halfway by placement $byPlacement px, halfway by points $byPoints px")
        assertEquals("placement keeps the length", whole, byPlacement, 1f)
        assertEquals("mixing points is what shrinks it", whole * sin(angle), byPoints, 1f)
    }

    // ---- 4: the stroke as an SVG path ----

    private fun n(value: Float): String = String.format(Locale.ROOT, "%.3f", value)

    /** The curve StrokeEngine walks: a straight first piece, curves through the midpoints, and a line to the last point. */
    private fun pathOf(stroke: VectorStroke): String {
        val s = stroke.samples
        val step = stroke.stride
        val text = StringBuilder("M${n(s[0])} ${n(s[1])}")
        var lastX = s[0]
        var lastY = s[1]
        for (i in 1 until stroke.count) {
            val x = s[i * step]
            val y = s[i * step + 1]
            if (x == lastX && y == lastY) continue
            text.append(" Q${n(lastX)} ${n(lastY)} ${n((lastX + x) * 0.5f)} ${n((lastY + y) * 0.5f)}")
            lastX = x
            lastY = y
        }
        text.append(" L${n(lastX)} ${n(lastY)}")
        return text.toString()
    }

    /** The width of a stroke with a held pressure. A varying width needs an outline, which step 3 has to build. */
    private fun widthOf(stroke: VectorStroke): Float = stroke.spec.sizePx * stroke.spec.sizeCurve.evaluate(stroke.samples[2])

    private fun hex(color: Int): String = String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)

    private fun strokeElement(stroke: VectorStroke, color: String = hex(stroke.colorArgb)): String =
        """<path d="${pathOf(stroke)}" fill="none" stroke="$color" stroke-width="${n(widthOf(stroke))}" """ +
            """stroke-opacity="${n(stroke.spec.opacity)}" stroke-linecap="round" stroke-linejoin="round"/>"""

    private fun document(body: String, w: Int = width, h: Int = height): String =
        """<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" viewBox="0 0 $w $h">$body</svg>"""

    private fun rendered(document: SvgDocument): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        assertTrue("the picture should finish", document.render(Canvas(out), 0.0, RectF(0f, 0f, width.toFloat(), height.toFloat())))
        return out
    }

    private fun rendered(svg: String): Bitmap = rendered(SvgParser.parse(svg))

    /** How much of what either picture inks both of them ink, from 0 to 1. */
    private fun overlap(a: Bitmap, b: Bitmap, threshold: Int = 128): Double {
        var both = 0
        var either = 0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                val inA = Color.alpha(a.getPixel(x, y)) >= threshold
                val inB = Color.alpha(b.getPixel(x, y)) >= threshold
                if (inA && inB) both++
                if (inA || inB) either++
            }
        }
        return if (either == 0) 0.0 else both.toDouble() / either
    }

    private class Flattened : PathSink {
        val points = ArrayList<Pair<Double, Double>>()
        private var x = 0.0
        private var y = 0.0

        override fun moveTo(x: Double, y: Double) {
            points += x to y
            this.x = x
            this.y = y
        }

        override fun lineTo(x: Double, y: Double) {
            for (step in 1..32) points += (this.x + (x - this.x) * step / 32.0) to (this.y + (y - this.y) * step / 32.0)
            this.x = x
            this.y = y
        }

        override fun quadTo(x1: Double, y1: Double, x: Double, y: Double) {
            for (step in 1..32) {
                val t = step / 32.0
                val u = 1 - t
                points += (u * u * this.x + 2 * u * t * x1 + t * t * x) to (u * u * this.y + 2 * u * t * y1 + t * t * y)
            }
            this.x = x
            this.y = y
        }

        override fun cubicTo(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double) = error("The path has no cubics")

        override fun close() = error("The path is never closed")
    }

    /** How far a point is from a line through [points]. */
    private fun distanceTo(points: List<Pair<Double, Double>>, x: Double, y: Double): Double {
        var nearest = Double.MAX_VALUE
        for (i in 1 until points.size) {
            val (ax, ay) = points[i - 1]
            val (bx, by) = points[i]
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val along = if (lengthSquared == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
            nearest = minOf(nearest, hypot(x - (ax + dx * along), y - (ay + dy * along)))
        }
        return nearest
    }

    @Test
    fun `the path is the curve the engine walks`() {
        val spec = BrushPresets.PEN.copy(sizePx = 8f, sizeCurve = PressureCurve.FLAT, spacing = 0.05f)
        // Samples far apart, so that how they are joined matters.
        val stroke = Drawing(width, height).also { it.draw(spec, Color.BLACK, 1L, scribed(40, 8L, path = wave)) }.strokes.single()
        val sink = Flattened()
        parsePathData(pathOf(stroke)).data.walk(sink)
        val stamps = stampsOf(spec, stroke.seed, stroke.samples, stroke.count, stroke.stride)
        val straight = List(stroke.count) { i ->
            stroke.samples[i * stroke.stride].toDouble() to
                stroke.samples[i * stroke.stride + 1].toDouble()
        }
        var worst = 0.0
        var worstStraight = 0.0
        for (k in 0 until stamps.count) {
            worst = maxOf(worst, distanceTo(sink.points, stamps.x(k).toDouble(), stamps.y(k).toDouble()))
            worstStraight = maxOf(worstStraight, distanceTo(straight, stamps.x(k).toDouble(), stamps.y(k).toDouble()))
        }
        println(
            "SHAPE the furthest a stamp is from the path: %.4f px, and from straight lines through the samples: %.4f px, over ${stamps.count} stamps"
                .format(
                    worst,
                    worstStraight,
                ),
        )
        assertTrue("a stamp was $worst px off the path", worst < 0.06)
        assertTrue("straight lines should sit further off, but were only $worstStraight", worstStraight > worst * 3)
    }

    @Test
    fun `a stroke of held pressure as a path covers what it drew`() {
        for ((spec, smallest, threshold) in listOf(
            Triple(BrushPresets.PEN.copy(sizePx = 10f), 0.88, 128),
            Triple(BrushPresets.PENCIL.copy(sizePx = 10f), 0.5, 32),
            Triple(BrushPresets.MARKER, 0.7, 96),
        )) {
            val drawing = Drawing(width, height)
            val stroke = drawing.draw(spec, Color.BLACK, 3L, scribed(400, 9L, path = wave))
            val found = overlap(drawing.layer, rendered(document(strokeElement(stroke))), threshold)
            println("SHAPE ${spec.id} as a path of one width: overlap %.3f".format(found))
            assertTrue("${spec.id} overlapped $found", found >= smallest)
        }
    }

    @Test
    fun `a path with no width of its own falls short of a stroke that varies`() {
        val spec = BrushPresets.PEN.copy(sizePx = 16f, sizeCurve = PressureCurve.LINEAR)
        val drawing = Drawing(width, height)
        val stroke = drawing.draw(spec, Color.BLACK, 3L, scribed(400, 10L, varyPressure = true, path = wave))
        val found = overlap(drawing.layer, rendered(document(strokeElement(stroke))))
        println("SHAPE a stroke with varying width as a path of one width: overlap %.3f".format(found))
        assertTrue("step 3 needs an outline for these, since one width overlapped $found", found < 0.9)
    }

    @Test
    fun `a tap as a path leaves a dot where it should`() {
        val spec = BrushPresets.PEN.copy(sizePx = 12f, sizeCurve = PressureCurve.FLAT)
        val drawing = Drawing(width, height)
        val stroke = drawing.draw(spec, Color.BLACK, 3L, listOf(floatArrayOf(100.25f, 80.5f, 1f, 0f, 0f)))
        assertEquals(1, stroke.count)
        val path = pathOf(stroke)
        assertEquals("M100.250 80.500 L100.250 80.500", path)
        val dot = rendered(document(strokeElement(stroke)))
        val found = overlap(drawing.layer, dot)
        println("SHAPE a tap as a path with a round cap: overlap %.3f".format(found))
        assertEquals("the middle of the dot", 255, Color.alpha(dot.getPixel(100, 80)))
        assertTrue("a tap overlapped $found", found >= 0.85)
    }

    @Test
    fun `without a round cap a tap leaves nothing`() {
        val spec = BrushPresets.PEN.copy(sizePx = 12f, sizeCurve = PressureCurve.FLAT)
        val stroke = Drawing(width, height)
            .also {
                it.draw(spec, Color.BLACK, 3L, listOf(floatArrayOf(100.25f, 80.5f, 1f, 0f, 0f)))
            }.strokes
            .single()
        val element = strokeElement(stroke).replace(""" stroke-linecap="round"""", "")
        assertEquals(0, Color.alpha(rendered(document(element)).getPixel(100, 80)))
    }

    private fun eraserMask(id: String, erasers: List<VectorStroke>, w: Int = width, h: Int = height): String =
        """<mask id="$id" maskUnits="userSpaceOnUse" x="0" y="0" width="$w" height="$h">""" +
            """<rect width="$w" height="$h" fill="white"/>""" +
            erasers.joinToString("") { strokeElement(it, "black") } +
            "</mask>"

    @Test
    fun `an eraser as a mask takes away what it took away`() {
        val drawing = Drawing(width, height)
        val ink = drawing.draw(
            BrushPresets.PEN.copy(sizePx = 30f, sizeCurve = PressureCurve.FLAT),
            Color.BLACK,
            1L,
            scribed(300, 11L, path = wave),
        )
        val eraserInput = scribed(120, 12L) { t -> (200f + 20f * t) to (40f + 220f * t) }
        val eraser = drawing.draw(BrushPresets.ERASER.copy(sizePx = 24f, sizeCurve = PressureCurve.FLAT), Color.BLACK, 2L, eraserInput)
        val svg = document(eraserMask("e1", listOf(eraser)) + """<g mask="url(#e1)">${strokeElement(ink)}</g>""")
        val pathWithMask = rendered(svg)
        val found = overlap(drawing.layer, pathWithMask)
        println("SHAPE ink with an eraser as a mask: overlap %.3f".format(found))
        assertTrue("the masked ink overlapped $found", found >= 0.9)

        val unmasked = rendered(document(strokeElement(ink)))
        val withoutMask = overlap(drawing.layer, unmasked)
        assertTrue("leaving out the mask should show: $withoutMask against $found", withoutMask < found)
    }

    // ---- What eraser masks cost ----

    /** Ink then an eraser, again and again, with each run of erasers masking everything drawn before it. */
    private fun nested(runs: List<Pair<VectorStroke, VectorStroke>>, w: Int, h: Int): String {
        val masks = StringBuilder()
        var body = ""
        runs.forEachIndexed { i, (ink, eraser) ->
            masks.append(eraserMask("e$i", listOf(eraser), w, h))
            body = """<g mask="url(#e$i)">$body${strokeElement(ink)}</g>"""
        }
        return document(masks.toString() + body, w, h)
    }

    /** The same picture with nothing nested: each piece of ink has one mask, holding every eraser that came after it. */
    private fun flat(runs: List<Pair<VectorStroke, VectorStroke>>, w: Int, h: Int): String {
        val masks = StringBuilder()
        val body = StringBuilder()
        runs.forEachIndexed { i, (ink, _) ->
            masks.append(eraserMask("e$i", runs.drop(i).map { it.second }, w, h))
            body.append("""<g mask="url(#e$i)">${strokeElement(ink)}</g>""")
        }
        return document(masks.toString() + body.toString(), w, h)
    }

    private fun someRuns(count: Int, canvasWidth: Int, canvasHeight: Int): Pair<Drawing, List<Pair<VectorStroke, VectorStroke>>> {
        val drawing = Drawing(canvasWidth, canvasHeight)
        val inkBrush = BrushPresets.PEN.copy(sizePx = 8f, sizeCurve = PressureCurve.FLAT)
        val eraserBrush = BrushPresets.ERASER.copy(sizePx = 8f, sizeCurve = PressureCurve.FLAT)
        val runs =
            List(count) { i ->
                val y = 20f + i * (canvasHeight - 40f) / count
                val ink = drawing.draw(inkBrush, Color.BLACK, 1L + i, scribed(60, 20L + i) { t -> (20f + (canvasWidth - 40f) * t) to y })
                val x = 40f + i * (canvasWidth - 80f) / count
                val eraser = drawing.draw(eraserBrush, Color.BLACK, 2L + i, scribed(30, 40L + i) { t -> x to (y - 12f + 24f * t) })
                ink to eraser
            }
        return drawing to runs
    }

    /** Draws a document the way a small phone would be allowed to, and reports on it. */
    private fun tryDocument(form: String, svg: String, drawing: Drawing, limits: SvgLimits, count: Int): Pair<Boolean, Double> {
        val document = SvgParser.parse(svg, limits)
        val out = Bitmap.createBitmap(drawing.width, drawing.height, Bitmap.Config.ARGB_8888)
        val started = System.nanoTime()
        val finished = document.render(Canvas(out), 0.0, RectF(0f, 0f, drawing.width.toFloat(), drawing.height.toFloat()))
        val millis = (System.nanoTime() - started) / 1_000_000
        val found = overlap(drawing.layer, out)
        val limited = SvgWarning.LIMIT_REACHED in document.warnings
        println(
            "SHAPE $count eraser runs $form at ${drawing.width} by ${drawing.height}: finished $finished, limit reached $limited, $millis ms here, overlap %.3f"
                .format(
                    found,
                ),
        )
        out.recycle()
        return (finished && !limited) to found
    }

    @Test
    fun `eraser masks in depth run out on a small phone and side by side they do not`() {
        // A phone with a 128 MB heap, which is what a 2 GB phone is assumed to have, and a canvas the size of its screen.
        val limits = SvgLimits.forDevice(128L * 1024 * 1024)
        val w = 720
        val h = 1280
        val layerBytes = w.toLong() * h * 4
        val fits = (limits.maxLiveRasterBytes / (layerBytes * 2)).toInt()
        println(
            "SHAPE a layer takes ${layerBytes / 1024} KB, a mask takes two, and a 128 MB heap allows ${limits.maxLiveRasterBytes / 1024} KB",
        )

        val (few, fewRuns) = someRuns(fits, w, h)
        val fitting = tryDocument("nested", nested(fewRuns, w, h), few, limits, fits)
        assertTrue("$fits runs in depth should just fit", fitting.first)
        assertTrue("$fits runs in depth overlapped ${fitting.second}", fitting.second >= 0.8)

        val (many, manyRuns) = someRuns(32, w, h)
        assertFalse(
            "32 runs in depth need more layers than a small phone allows",
            tryDocument("nested", nested(manyRuns, w, h), many, limits, 32).first,
        )

        val sideBySide = tryDocument("side by side", flat(manyRuns, w, h), many, limits, 32)
        assertTrue("32 runs side by side should finish", sideBySide.first)
        assertTrue("32 runs side by side overlapped ${sideBySide.second}", sideBySide.second >= 0.8)
    }

    @Test
    fun `a few eraser masks in depth are fine on a small canvas`() {
        val limits = SvgLimits.forDevice(128L * 1024 * 1024)
        val (drawing, runs) = someRuns(8, width, height)
        val found = tryDocument("nested", nested(runs, width, height), drawing, limits, 8)
        assertTrue("8 runs in depth on a small canvas should finish", found.first)
        assertTrue("8 runs overlapped ${found.second}", found.second >= 0.8)
    }
}
