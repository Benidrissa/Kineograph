package org.kineograph.engine.vector

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kineograph.engine.FakeCelStore
import org.kineograph.engine.brush.BoundsAccumulator
import org.kineograph.engine.brush.BrushPresets
import org.kineograph.engine.brush.BrushSpec
import org.kineograph.engine.brush.StrokeBuilder
import org.kineograph.engine.brush.StrokeMerge
import org.kineograph.engine.brush.StrokeRecording
import org.kineograph.engine.session.DrawingSession
import org.kineograph.engine.session.MemoryBudget
import org.kineograph.model.Background
import org.kineograph.model.IntRect
import org.kineograph.model.newProject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.math.round

/**
 * Whether a layer can be worked out again from what is saved about its strokes. Strokes go through a real session, the way a view feeds
 * one, and their cels are compared with what comes of saving, reading and replaying them, on real Skia through Robolectric's native
 * graphics. That Skia is a desktop one, and no phone has been tried.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StrokeReplayTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val sheets = ArrayList<Sheet>()

    @After
    fun tearDown() {
        sheets.forEach { it.close() }
    }

    private val presets = listOf(BrushPresets.PEN, BrushPresets.PENCIL, BrushPresets.MARKER, BrushPresets.ERASER)

    private class Drawn(val recording: StrokeRecording, val colorArgb: Int, val bounds: IntRect, val clip: IntRect?)

    /** One canvas, one session, and what was drawn on it. */
    private inner class Sheet(val width: Int = 480, val height: Int = 320) {
        val store = FakeCelStore()
        val budget = MemoryBudget.forMemoryClass(256, isLowRam = false)
        val margin = budget.overscanFor(width, height)
        val session =
            DrawingSession(
                newProject("Test", width, height, 12, Background.Transparent, "Layer 1", now = 0),
                store,
                budget,
                temp.newFolder(),
            )
        val surface get() = IntRect(-margin, -margin, currentWidth + margin, currentHeight + margin)
        var currentWidth = width
        var currentHeight = height
        val drawn = ArrayList<Drawn>()

        init {
            sheets += this
            await()
        }

        fun await() = runBlocking { session.awaitIdle() }

        fun draw(script: PenScript, spec: BrushSpec, color: Int = Color.BLACK, seed: Long = 0x1234567L) {
            val recording = StrokeRecording(spec, seed, script.isFinger, Long.MAX_VALUE)
            val builder = StrokeBuilder(spec, script.options, seed, recording)
            val bounds = BoundsAccumulator()
            session.beginStroke(spec, color)
            script.play(builder) { run ->
                bounds.add(run)
                session.addStamps(run)
            }
            session.endStroke { }
            await()
            val rounded = bounds.roundedOut()
            val reach = IntRect(rounded[0], rounded[1], rounded[2], rounded[3])
            drawn += Drawn(recording, color, reach, if (reach.intersect(surface) != reach) surface else null)
        }

        fun cel(): Pair<Bitmap, IntRect> {
            val project = runBlocking { session.snapshot() }
            val cel = project.cels.values.singleOrNull()
            // No cel at all is an empty drawing, which is what erasing everything comes to.
            if (cel == null) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) to IntRect(0, 0, 1, 1)
            val image = checkNotNull(store.images[cel.file]) { "the cel's image has gone" }
            return image to checkNotNull(cel.bounds)
        }

        fun fileFor(bounds: IntRect, strokes: List<Drawn> = drawn): ByteArray {
            val out = ByteArrayOutputStream()
            val encoder = VectorFileEncoder(out, bounds)
            strokes.forEachIndexed { i, d -> assertTrue(encoder.addStroke(i + 1L, d.recording, d.colorArgb, d.bounds, d.clip)) }
            encoder.finish()
            return out.toByteArray()
        }

        /**
         * Saves the strokes, reads them back as the cel is today, and replays them. [extra] makes the layer larger than the surface was.
         */
        fun replayed(bytes: ByteArray, celNow: IntRect?, extra: Int): StrokeReplay {
            val replay = StrokeReplay(surface.inflated(extra))
            VectorCodec.decodeFile(
                bytes,
                celNow,
                object : VectorVisitor {
                    override fun onStroke(stroke: VectorStroke) = replay.replay(stroke)

                    override fun onGap(gap: VectorGap) = error("Only strokes were saved")
                },
            )
            return replay
        }

        fun difference(extra: Int = 0, image: Bitmap? = null): ReplayDifference {
            val (cel, bounds) = cel()
            val replay = replayed(fileFor(bounds), bounds, extra)
            val result = ReplayDifference.between(
                image ?: cel,
                bounds.left,
                bounds.top,
                replay.layer,
                replay.region.left,
                replay.region.top,
            )
            replay.release()
            return result
        }

        fun close() {
            session.close()
        }
    }

    /** A layer already covered in paint, so that an eraser has something to erase. */
    private fun Sheet.paintForEraser() {
        draw(PenScripts.scribble(41L), BrushPresets.MARKER.copy(sizePx = 80f, opacity = 1f))
    }

    private fun Sheet.drawWith(script: PenScript, spec: BrushSpec) {
        if (spec.isEraser) paintForEraser()
        draw(script, spec)
    }

    // ---- Replay against pixels ----

    @Test
    fun `every preset replays to its pixels at every size`() {
        var mean = 0.0
        var p999 = 0
        var max = 0
        var combinations = 0
        for (preset in presets) {
            var presetMean = 0.0
            var presetP999 = 0
            var presetMax = 0
            for (factor in listOf(0.34f, 1f, 3f)) {
                for (script in PenScripts.all()) {
                    if (script.name == "long" && factor != 1f) continue
                    val sheet = Sheet()
                    sheet.drawWith(script, preset.copy(sizePx = maxOf(1f, preset.sizePx * factor)))
                    val onSurface = sheet.difference()
                    val onLarger = sheet.difference(extra = 40)
                    val what = "${script.name} with ${preset.id} at $factor"
                    assertEquals("$what, replayed on a layer placed as the surface was, is pixel for pixel: $onSurface", 0, onSurface.max)
                    assertTrue("$what: $onLarger", onLarger.isWithinTolerance)
                    presetMean = maxOf(presetMean, onLarger.mean)
                    presetP999 = maxOf(presetP999, onLarger.p999)
                    presetMax = maxOf(presetMax, onLarger.max)
                    combinations++
                    sheet.close()
                    sheets.remove(sheet)
                }
            }
            println("REPLAY ${preset.id}: mean %.3f, p99.9 %d, max %d".format(presetMean, presetP999, presetMax))
            mean = maxOf(mean, presetMean)
            p999 = maxOf(p999, presetP999)
            max = maxOf(max, presetMax)
        }
        println("REPLAY worst of $combinations: mean %.3f, p99.9 %d, max %d".format(mean, p999, max))
        assertTrue("mean under 1/255, was $mean", mean < 1.0)
        assertTrue("99.9th percentile under 24/255, was $p999", p999 < 24)
        assertTrue("no pixel over 64/255, was $max", max <= 64)
    }

    @Test
    fun `the smallest and largest sizes replay to their pixels`() {
        for (preset in presets) {
            for (size in listOf(1f, 400f)) {
                for (script in listOf(PenScripts.tap(), PenScripts.flick(), PenScripts.stylusDefaults())) {
                    val sheet = Sheet()
                    sheet.drawWith(script, preset.copy(sizePx = size))
                    val found = sheet.difference()
                    assertEquals("${script.name} with ${preset.id} at $size: $found", 0, found.max)
                    sheet.close()
                    sheets.remove(sheet)
                }
            }
        }
    }

    @Test
    fun `a drawing of many strokes replays to its pixels`() {
        val sheet = Sheet()
        val brown = 0xFF7A4B21.toInt()
        val translucent = 0x80206090.toInt()
        val brushes =
            listOf(
                BrushPresets.PEN,
                BrushPresets.PENCIL,
                BrushPresets.MARKER,
                BrushPresets.PEN.copy(sizePx = 2.5f, opacity = 0.6f),
                BrushPresets.PENCIL.copy(sizePx = 14f),
                BrushPresets.MARKER.copy(sizePx = 40f, opacity = 0.3f),
            )
        repeat(40) { i ->
            val seed = 1000L + i
            val script =
                when (i % 6) {
                    0 -> PenScripts.curve(seed)
                    1 -> PenScripts.flick(seed)
                    2 -> PenScripts.stylusDefaults(seed)
                    3 -> PenScripts.hold(seed)
                    4 -> PenScripts.finger(seed)
                    else -> PenScripts.scribble(seed)
                }
            if (i % 7 == 6) {
                sheet.draw(script, BrushPresets.ERASER.copy(sizePx = 8f + i), seed = seed)
            } else {
                sheet.draw(script, brushes[i % brushes.size], if (i % 3 == 0) translucent else brown, seed)
            }
        }
        val onSurface = sheet.difference()
        val onLarger = sheet.difference(extra = 40)
        println("REPLAY 40 strokes: on the surface $onSurface, on a larger layer $onLarger")
        assertEquals("$onSurface", 0, onSurface.max)
        assertTrue("$onLarger", onLarger.isWithinTolerance)

        val (cel, bounds) = sheet.cel()
        val points = sheet.drawn.sumOf { it.recording.count }
        val saved = sheet.fileFor(bounds).size
        val png = ByteArrayOutputStream().also { cel.compress(Bitmap.CompressFormat.PNG, 100, it) }.size()
        val webp = ByteArrayOutputStream().also { cel.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 20, it) }.size()
        println(
            "REPLAY 40 strokes of $points samples save as $saved bytes, beside an image of ${cel.width} by ${cel.height}: $png bytes of PNG, $webp of WebP",
        )
    }

    @Test
    fun `a stroke past the surface stops where the pixels stopped`() {
        val sheet = Sheet()
        val offEdge = PenScripts.hand("off the edge", 5L, hz = 240, seconds = 1.5) { t -> floatArrayOf(240f + 700f * t, 160f - 500f * t) }
        sheet.draw(offEdge, BrushPresets.PEN.copy(sizePx = 12f))
        val drawn = sheet.drawn.single()
        assertEquals("the script should run off the surface", sheet.surface, drawn.clip)

        val (cel, bounds) = sheet.cel()
        val replay = sheet.replayed(sheet.fileFor(bounds), bounds, 300)
        val difference = ReplayDifference.between(cel, bounds.left, bounds.top, replay.layer, replay.region.left, replay.region.top)
        println("REPLAY past the surface: $difference")
        assertTrue("$difference", difference.isWithinTolerance)

        val outside = IntArray(replay.layer.width * replay.layer.height)
        replay.layer.getPixels(outside, 0, replay.layer.width, 0, 0, replay.layer.width, replay.layer.height)
        for (y in 0 until replay.layer.height) {
            for (x in 0 until replay.layer.width) {
                if (!sheet.surface.contains(x + replay.region.left, y + replay.region.top)) {
                    assertEquals("ink at ($x, $y), which the surface never held", 0, outside[y * replay.layer.width + x])
                }
            }
        }
        replay.release()
    }

    @Test
    fun `strokes stay on their pixels when the canvas is resized`() {
        for ((newWidth, newHeight) in listOf(641 to 407, 300 to 200)) {
            val sheet = Sheet()
            sheet.draw(PenScripts.curve(), BrushPresets.PENCIL)
            sheet.draw(PenScripts.flick(), BrushPresets.MARKER)
            sheet.draw(PenScripts.scribble(), BrushPresets.ERASER.copy(sizePx = 9f))
            val before = sheet.cel().second
            val bytes = sheet.fileFor(before)

            sheet.session.resizeCanvas(newWidth, newHeight)
            sheet.await()
            val (cel, after) = sheet.cel()
            assertNotEquals("the cel should have moved", before, after)
            assertEquals(before.width, after.width)

            // The layer covers what the cel does, because a shrink leaves some of it off the surface.
            val region = after.union(IntRect(0, 0, newWidth, newHeight)).inflated(sheet.margin + 40)
            val replay = StrokeReplay(region)
            VectorCodec.decodeFile(
                bytes,
                after,
                object : VectorVisitor {
                    override fun onStroke(stroke: VectorStroke) {
                        assertEquals(after.left - before.left, stroke.offsetX)
                        assertEquals(after.top - before.top, stroke.offsetY)
                        replay.replay(stroke)
                    }

                    override fun onGap(gap: VectorGap) = error("Only strokes were saved")
                },
            )
            val difference = ReplayDifference.between(cel, after.left, after.top, replay.layer, region.left, region.top)
            println(
                "REPLAY resized to $newWidth by $newHeight, moved by ${after.left - before.left}, ${after.top - before.top}: $difference",
            )
            assertTrue("$difference", difference.isWithinTolerance)
            replay.release()
        }
    }

    @Test
    fun `replaying after the cel has been through a file stays within the tolerance`() {
        val formats = listOf(Bitmap.CompressFormat.PNG to 100, Bitmap.CompressFormat.WEBP_LOSSLESS to 20)
        for ((format, quality) in formats) {
            var worst = ReplayDifference(0, 0.0, 0, 0)
            for (preset in presets) {
                for (script in listOf(PenScripts.curve(), PenScripts.scribble(), PenScripts.finger(), PenScripts.stylusDefaults())) {
                    val sheet = Sheet()
                    sheet.drawWith(script, preset)
                    val cel = sheet.cel().first
                    val bytes = ByteArrayOutputStream().also { assertTrue(cel.compress(format, quality, it)) }.toByteArray()
                    val decoded = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "$format did not decode" }
                    val difference = sheet.difference(extra = 40, image = decoded)
                    assertTrue("$format, ${script.name} with ${preset.id}: $difference", difference.isWithinTolerance)
                    if (difference.max > worst.max || difference.mean > worst.mean) worst = difference
                    sheet.close()
                    sheets.remove(sheet)
                }
            }
            println("REPLAY through $format: worst $worst")
        }
    }

    // ---- Whether the tolerance means anything ----

    /** A cel with a good many strokes, where one thing going missing has plenty to hide among. */
    private fun busySheet(): Sheet {
        val sheet = Sheet()
        repeat(12) { i ->
            val seed = 2000L + i
            val script = if (i % 2 == 0) PenScripts.curve(seed) else PenScripts.scribble(seed)
            sheet.draw(script, if (i % 3 == 0) BrushPresets.PENCIL else BrushPresets.PEN, seed = seed)
        }
        return sheet
    }

    private fun assertNoticed(
        what: String,
        sheet: Sheet,
        strokes: List<Drawn>,
        replayStrokes: (StrokeReplay, VectorStroke) -> Unit = {
            r,
            s,
            ->
            r
                .replay(
                    s,
                )
        },
    ) {
        val (cel, bounds) = sheet.cel()
        val replay = StrokeReplay(sheet.surface.inflated(40))
        VectorCodec.decodeFile(
            sheet.fileFor(bounds, strokes),
            bounds,
            object : VectorVisitor {
                override fun onStroke(stroke: VectorStroke) = replayStrokes(replay, stroke)

                override fun onGap(gap: VectorGap) = error("Only strokes were saved")
            },
        )
        val difference = ReplayDifference.between(cel, bounds.left, bounds.top, replay.layer, replay.region.left, replay.region.top)
        println("NOTICED $what: $difference")
        assertFalse("$what went unnoticed: $difference", difference.isWithinTolerance)
        replay.release()
    }

    @Test
    fun `the tolerance notices a tap that went missing`() {
        val sheet = busySheet()
        sheet.draw(PenScripts.tap(), BrushPresets.PEN.copy(sizePx = 1.5f), color = Color.BLUE)
        assertNoticed("a tap of a thin pen left out", sheet, sheet.drawn.dropLast(1))
    }

    @Test
    fun `the tolerance notices a faint tap that went missing`() {
        val sheet = busySheet()
        sheet.draw(PenScripts.tap(), BrushPresets.PEN.copy(sizePx = 12f, opacity = 0.05f), color = Color.RED)
        assertNoticed("a tap at 5% opacity left out", sheet, sheet.drawn.dropLast(1))
    }

    @Test
    fun `the tolerance notices an eraser that went missing`() {
        val sheet = busySheet()
        sheet.draw(PenScripts.flick(), BrushPresets.ERASER.copy(sizePx = 3f))
        assertNoticed("a thin eraser left out", sheet, sheet.drawn.dropLast(1))
    }

    @Test
    fun `the tolerance notices a faint stroke that went missing`() {
        val sheet = busySheet()
        sheet.draw(PenScripts.curve(77L), BrushPresets.PEN.copy(opacity = 0.05f), color = Color.RED)
        assertNoticed("a stroke at 5% opacity left out", sheet, sheet.drawn.dropLast(1))
    }

    @Test
    fun `the tolerance notices the end of a stroke that went missing`() {
        val sheet = Sheet()
        val whole = PenScripts.curve()
        sheet.draw(whole, BrushPresets.PEN)
        val short = StrokeRecording(BrushPresets.PEN, 0x1234567L, false, Long.MAX_VALUE)
        PenScript("short", whole.samples.dropLast(3), whole.options).play(StrokeBuilder(BrushPresets.PEN, whole.options, 0x1234567L, short))
        val only = sheet.drawn.single()
        assertNoticed("the last three samples left out", sheet, listOf(Drawn(short, only.colorArgb, only.bounds, only.clip)))
    }

    @Test
    fun `the tolerance notices a stroke a pixel away`() {
        val sheet = busySheet()
        assertNoticed("everything a pixel to the right", sheet, sheet.drawn) { replay, stroke ->
            replay.replay(stroke.spec, stroke.seed, stroke.colorArgb, stroke.samples, stroke.count, stroke.stride, stroke.clip, offsetX = 1)
        }
    }

    @Test
    fun `the tolerance notices a stroke a fraction of a pixel away`() {
        val sheet = Sheet()
        sheet.draw(PenScripts.curve(), BrushPresets.PEN)
        assertNoticed("a stroke a quarter of a pixel to the right", sheet, sheet.drawn) { replay, stroke ->
            val moved = stroke.samples.copyOf()
            for (i in 0 until stroke.count) moved[i * stroke.stride] += 0.25f
            replay.replay(stroke.spec, stroke.seed, stroke.colorArgb, moved, stroke.count, stroke.stride, stroke.clip)
        }
    }

    @Test
    fun `the tolerance notices samples rounded the way the plan first had them`() {
        val sheet = Sheet()
        sheet.draw(PenScripts.curve(), BrushPresets.PEN)
        assertNoticed("samples rounded to 1/64 px and 10 bits of pressure", sheet, sheet.drawn) { replay, stroke ->
            val rounded = rounded(stroke.samples, stroke.stride, doubleArrayOf(64.0, 1023.0, 256.0))
            replay.replay(stroke.spec, stroke.seed, stroke.colorArgb, rounded, stroke.count, stroke.stride, stroke.clip)
        }
    }

    @Test
    fun `the tolerance notices a wrong seed`() {
        val sheet = Sheet()
        sheet.draw(PenScripts.curve(), BrushPresets.PENCIL)
        assertNoticed("a pencil with another seed", sheet, sheet.drawn) { replay, stroke ->
            replay.replay(stroke.spec, stroke.seed + 1, stroke.colorArgb, stroke.samples, stroke.count, stroke.stride, stroke.clip)
        }
    }

    @Test
    fun `the tolerance notices a colour slightly off`() {
        val sheet = Sheet()
        sheet.draw(PenScripts.curve(), BrushPresets.MARKER, color = 0xFF808080.toInt())
        assertNoticed("a grey sixteen steps off", sheet, sheet.drawn) { replay, stroke ->
            replay.replay(stroke.spec, stroke.seed, 0xFF909090.toInt(), stroke.samples, stroke.count, stroke.stride, stroke.clip)
        }
    }

    @Test
    fun `the tolerance notices a brush slightly off`() {
        val sheet = Sheet()
        sheet.draw(PenScripts.curve(), BrushPresets.MARKER)
        assertNoticed("a marker a little wider", sheet, sheet.drawn) { replay, stroke ->
            val wider = stroke.spec.copy(sizePx = stroke.spec.sizePx * 1.05f)
            replay.replay(wider, stroke.seed, stroke.colorArgb, stroke.samples, stroke.count, stroke.stride, stroke.clip)
        }
    }

    // ---- The merge both share ----

    @Test
    fun `a merge lands a stroke at its opacity and only inside its rectangle`() {
        val buffer = bitmapOf(4, 4, Color.BLACK)
        val layer = bitmapOf(4, 4)
        StrokeMerge().merge(Canvas(layer), buffer, BrushPresets.PEN.copy(opacity = 0.5f), IntRect(0, 0, 2, 4))
        assertEquals(128f, Color.alpha(layer.getPixel(1, 1)).toFloat(), 1f)
        assertEquals(0, Color.alpha(layer.getPixel(2, 1)))
    }

    @Test
    fun `a merged eraser cuts paint away at its opacity`() {
        val buffer = bitmapOf(4, 4, Color.BLACK)
        val layer = bitmapOf(4, 4, Color.BLUE)
        StrokeMerge().merge(Canvas(layer), buffer, BrushPresets.ERASER.copy(opacity = 0.75f), IntRect(0, 0, 4, 4))
        assertEquals(64f, Color.alpha(layer.getPixel(1, 1)).toFloat(), 1f)
    }

    // ---- How the difference is measured ----

    private fun bitmapOf(width: Int, height: Int, fill: Int = Color.TRANSPARENT): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(fill) }

    @Test
    fun `no difference between a picture and itself`() {
        val image = bitmapOf(10, 10, Color.RED)
        val difference = ReplayDifference.between(image, 3, 4, image, 3, 4)
        assertEquals(100L, difference.pixels)
        assertEquals(0.0, difference.mean, 0.0)
        assertEquals(0, difference.max)
    }

    @Test
    fun `empty pixels do not dilute a difference`() {
        val a = bitmapOf(100, 100)
        val b = bitmapOf(100, 100)
        a.setPixel(5, 5, Color.BLACK)
        b.setPixel(5, 5, 0xFFF00000.toInt())
        a.setPixel(6, 5, Color.BLACK)
        b.setPixel(6, 5, Color.BLACK)
        val difference = ReplayDifference.between(a, 0, 0, b, 0, 0)
        assertEquals(2L, difference.pixels)
        assertEquals(120.0, difference.mean, 0.0)
        assertEquals(240, difference.max)
    }

    @Test
    fun `pictures are lined up by where they are on the canvas`() {
        val cel = bitmapOf(4, 4, Color.BLACK)
        val layer = bitmapOf(20, 20)
        Canvas(layer).drawBitmap(cel, 7f, 5f, null)
        assertEquals(0, ReplayDifference.between(cel, 2, 3, layer, -5, -2).max)
        assertEquals(255, ReplayDifference.between(cel, 3, 3, layer, -5, -2).max)
    }

    @Test
    fun `ink outside the other picture counts in full`() {
        val cel = bitmapOf(4, 4)
        val layer = bitmapOf(2, 2, Color.BLACK)
        val difference = ReplayDifference.between(cel, 0, 0, layer, 10, 10)
        assertEquals(4L, difference.pixels)
        assertEquals(255, difference.max)
    }

    @Test
    fun `the colour under a clear pixel does not count`() {
        val a = bitmapOf(1, 1, 0x01FF0000)
        val b = bitmapOf(1, 1, 0x010000FF)
        assertTrue(ReplayDifference.between(a, 0, 0, b, 0, 0).max <= 1)
    }

    @Test
    fun `the percentile skips the worst thousandth and no more`() {
        val a = bitmapOf(100, 100, Color.BLACK)
        val b = bitmapOf(100, 100, Color.BLACK)
        repeat(10) { b.setPixel(it, 0, 0xFF640000.toInt()) }
        val tenInTenThousand = ReplayDifference.between(a, 0, 0, b, 0, 0)
        assertEquals(0, tenInTenThousand.p999)
        assertEquals(100, tenInTenThousand.max)
        b.setPixel(10, 0, 0xFF0A0000.toInt())
        assertEquals(10, ReplayDifference.between(a, 0, 0, b, 0, 0).p999)
    }

    // ---- What rounding would cost ----

    /**
     * What the numbers in docs/decisions.md came from. It tries each way of rounding samples that the format could have used, and takes
     * minutes, so it only runs when asked:
     *
     *     KINEOGRAPH_MEASURE_REPLAY=1 ./gradlew :core:engine:testDebugUnitTest --tests '*StrokeReplayTest*rounding*'
     */
    @Test
    fun `what rounding samples would cost`() {
        assumeTrue(System.getenv("KINEOGRAPH_MEASURE_REPLAY") != null)
        val rows =
            listOf(
                doubleArrayOf(64.0, 1023.0, 256.0),
                doubleArrayOf(64.0, 4095.0, 256.0),
                doubleArrayOf(64.0, 65535.0, 256.0),
                doubleArrayOf(256.0, 65535.0, 1024.0),
                doubleArrayOf(1024.0, 65535.0, 1024.0),
                doubleArrayOf(4096.0, 1048575.0, 65536.0),
            )
        val worst = rows.map { DoubleArray(6) }
        var combinations = 0
        val plain = VectorBuffer(1024)
        var exactPlainBytes = 0L
        var exactPlainPoints = 0L
        var exactTiltBytes = 0L
        var exactTiltPoints = 0L
        val roundedBytes = rows.map { LongArray(4) }
        for (seedOffset in 0L until 6L) {
            for (factor in listOf(0.34f, 1f, 3f)) {
                val scripts =
                    listOf(
                        PenScripts.curve(100 + seedOffset),
                        PenScripts.slow(200 + seedOffset),
                        PenScripts.flick(300 + seedOffset),
                        PenScripts.scribble(400 + seedOffset),
                        PenScripts.hold(500 + seedOffset),
                        PenScripts.zoomedIn(600 + seedOffset),
                        PenScripts.finger(700 + seedOffset),
                        PenScripts.stylusDefaults(800 + seedOffset),
                    ) + if (seedOffset < 2 && factor == 1f) listOf(PenScripts.long(900 + seedOffset)) else emptyList()
                for (script in scripts) {
                    for (preset in presets) {
                        val sheet = Sheet()
                        sheet.drawWith(script, preset.copy(sizePx = maxOf(1f, preset.sizePx * factor)))
                        val (cel, bounds) = sheet.cel()
                        rows.forEachIndexed { row, precision ->
                            val replay = StrokeReplay(sheet.surface)
                            for (drawn in sheet.drawn) {
                                val r = drawn.recording
                                val samples = rounded(r.copySamples(), r.stride, precision)
                                replay.replay(r.spec, r.seed, drawn.colorArgb, samples, r.count, r.stride, drawn.clip)
                            }
                            val d = ReplayDifference.between(
                                cel,
                                bounds.left,
                                bounds.top,
                                replay.layer,
                                replay.region.left,
                                replay.region.top,
                            )
                            replay.release()
                            worst[row][0] = maxOf(worst[row][0], d.mean)
                            worst[row][1] = maxOf(worst[row][1], d.p999.toDouble())
                            worst[row][2] = maxOf(worst[row][2], d.max.toDouble())
                            if (d.mean >= 1.0) worst[row][3]++
                            if (d.p999 >= 24) worst[row][4]++
                            if (d.max > 64) worst[row][5]++
                        }
                        val last = sheet.drawn.last().recording
                        encodeSamples(last, plain)
                        val isTilt = last.stride == StrokeRecording.STRIDE_WITH_TILT
                        if (isTilt) {
                            exactTiltBytes += plain.size
                            exactTiltPoints += last.count
                        } else {
                            exactPlainBytes += plain.size
                            exactPlainPoints += last.count
                        }
                        rows.forEachIndexed { row, precision ->
                            val bytes = roundedCost(last.copySamples(), last.stride, precision)
                            roundedBytes[row][if (isTilt) 2 else 0] += bytes
                            roundedBytes[row][if (isTilt) 3 else 1] += last.count.toLong()
                        }
                        combinations++
                        sheet.close()
                        sheets.remove(sheet)
                    }
                }
            }
        }
        rows.forEachIndexed { row, precision ->
            println(
                (
                    "ROUNDING 1/%.0f px, %.0f levels of pressure, 1/%.0f rad: worst mean %.3f, p99.9 %.0f, max %.0f; of $combinations, " +
                        "%.0f over on mean, %.0f on p99.9, %.0f on max; bytes a point %.2f, with tilt %.2f"
                ).format(
                    precision[0],
                    precision[1] + 1,
                    precision[2],
                    worst[row][0],
                    worst[row][1],
                    worst[row][2],
                    worst[row][3],
                    worst[row][4],
                    worst[row][5],
                    roundedBytes[row][0].toDouble() / roundedBytes[row][1],
                    roundedBytes[row][2].toDouble() / roundedBytes[row][3],
                ),
            )
        }
        println(
            "ROUNDING exact floats: bytes a point %.2f, with tilt %.2f".format(
                exactPlainBytes.toDouble() / exactPlainPoints,
                exactTiltBytes.toDouble() / exactTiltPoints,
            ),
        )
    }

    private fun rounded(samples: FloatArray, stride: Int, precision: DoubleArray): FloatArray {
        val out = samples.copyOf()
        var at = 0
        while (at < out.size) {
            out[at] = (round(out[at] * precision[0]) / precision[0]).toFloat()
            out[at + 1] = (round(out[at + 1] * precision[0]) / precision[0]).toFloat()
            out[at + 2] = (round(out[at + 2] * precision[1]) / precision[1]).toFloat()
            if (stride == StrokeRecording.STRIDE_WITH_TILT) {
                out[at + 3] = (round(out[at + 3] * precision[2]) / precision[2]).toFloat()
                out[at + 4] = (round(out[at + 4] * precision[2]) / precision[2]).toFloat()
            }
            at += stride
        }
        return out
    }

    /** What rounded samples would take as the plan laid them out: second differences of x and y, and first differences of the rest. */
    private fun roundedCost(samples: FloatArray, stride: Int, precision: DoubleArray): Long {
        val buffer = VectorBuffer(1024)
        val last = LongArray(stride)
        var stepX = 0L
        var stepY = 0L
        var at = 0
        while (at < samples.size) {
            for (field in 0 until stride) {
                val scale = precision[
                    if (field < 2) {
                        0
                    } else if (field == 2) {
                        1
                    } else {
                        2
                    },
                ]
                val value = round(samples[at + field].toDouble() * scale).toLong()
                val step = value - last[field]
                when (field) {
                    0 -> {
                        buffer.zigzag(step - stepX)
                        stepX = step
                    }

                    1 -> {
                        buffer.zigzag(step - stepY)
                        stepY = step
                    }

                    else -> {
                        buffer.zigzag(step)
                    }
                }
                last[field] = value
            }
            at += stride
        }
        return buffer.size.toLong()
    }
}
