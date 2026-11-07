package org.kineograph.feature.editor.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kineograph.engine.ViewTransform
import org.kineograph.engine.brush.BrushPresets
import org.kineograph.engine.brush.PressureCurve
import org.kineograph.engine.brush.StampBuffer
import org.kineograph.engine.brush.StampRun
import org.kineograph.engine.selection.Affine
import org.kineograph.engine.session.DrawingSession
import org.kineograph.engine.session.MemoryBudget
import org.kineograph.feature.editor.TestStore
import org.kineograph.model.Background
import org.kineograph.model.newProject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** Paints with real Skia, the way the front layer does: on a canvas whose contents stay from one paint to the next. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WetStrokePainterTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var session: DrawingSession
    private lateinit var painter: WetStrokePainter
    private val pen = BrushPresets.PEN.copy(sizePx = 12f, sizeCurve = PressureCurve.FLAT, hardness = 1f)
    private val eraser = BrushPresets.ERASER.copy(sizePx = 30f, sizeCurve = PressureCurve.FLAT, hardness = 1f)

    @Before
    fun setUp() {
        session =
            DrawingSession(
                newProject("Test", SIZE, SIZE, 12, Background.Color(Color.RED), "Layer 1", now = 0),
                TestStore(),
                MemoryBudget.forMemoryClass(256, false),
                temp.newFolder(),
            )
        runBlocking { session.awaitIdle() }
        painter = WetStrokePainter(session)
    }

    @After
    fun tearDown() {
        session.close()
    }

    private fun run(vararg points: Pair<Float, Float>, diameter: Float = pen.sizePx): StampRun =
        StampBuffer()
            .apply { points.forEach { (x, y) -> add(x, y, diameter, 1f, 0f) } }
            .snapshot()

    private fun line(fromX: Float, toX: Float, y: Float): StampRun =
        run(
            *generateSequence(fromX) { it + 3f }
                .takeWhile { it <= toX }
                .map { it to y }
                .toList()
                .toTypedArray(),
        )

    private fun stroke(color: Int = Color.BLACK) = WetStroke(1, pen, color, 0.5f)

    private fun bitmap() = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)

    private fun pixels(image: Bitmap) = IntArray(SIZE * SIZE).also { image.getPixels(it, 0, SIZE, 0, 0, SIZE, SIZE) }

    private fun assertSamePixels(expected: Bitmap, actual: Bitmap, what: String) {
        val ours = pixels(expected)
        val theirs = pixels(actual)
        val different = ours.indices.count { abs(Color.alpha(ours[it]) - Color.alpha(theirs[it])) > 40 }
        assertEquals(what, 0, different)
    }

    private fun count(image: Bitmap, predicate: (Int) -> Boolean): Int = pixels(image).count(predicate)

    /** A real stroke, then a prediction that goes away: the result must be as if the prediction had never been. */
    @Test
    fun `a prediction leaves no trace on an upright canvas`() {
        val view = ViewTransform(panX = 200f, panY = 200f).toAffine(SIZE, SIZE)
        val real = line(100f, 300f, 200f)
        val prediction = line(150f, 250f, 205f)
        val current = stroke()

        val withPrediction = bitmap()
        val canvas = Canvas(withPrediction)
        painter.paint(canvas, WetCommand(current, real, prediction), view)
        painter.paint(canvas, WetCommand(current, StampRun.EMPTY, StampRun.EMPTY), view)

        val without = bitmap()
        WetStrokePainter(session).paint(Canvas(without), WetCommand(current, real, StampRun.EMPTY), view)
        assertTrue("the stroke painted something", count(without) { Color.alpha(it) > 0 } > 0)
        assertSamePixels(without, withPrediction, "pixels differ from the same stroke without a prediction")
    }

    /**
     * Each touch event brings its own run of stamps. The clear is an upright box on screen, so on a rotated canvas its corners cover
     * runs far from the prediction on the canvas. Those are the ones that would stay wiped, so they are put there on purpose.
     */
    @Test
    fun `a prediction leaves no holes on a rotated canvas`() {
        for (degrees in listOf(30f, 45f, 60f, 135f)) {
            val view = ViewTransform(panX = 200f, panY = 200f, rotationDegrees = degrees).toAffine(SIZE, SIZE)
            val inverse = checkNotNull(view.inverse())
            val prediction = line(100f, 300f, 205f)
            val box = predictionWipe(view, prediction.minX, prediction.minY, prediction.maxX, prediction.maxY, 0f)
            val runs =
                listOf(
                    box.viewLeft + CORNER_INSET to box.viewTop + CORNER_INSET,
                    box.viewRight - CORNER_INSET to box.viewTop + CORNER_INSET,
                    box.viewLeft + CORNER_INSET to box.viewBottom - CORNER_INSET,
                    box.viewRight - CORNER_INSET to box.viewBottom - CORNER_INSET,
                ).map { (x, y) -> run(inverse.mapX(x, y) to inverse.mapY(x, y)) }
            assertTrue(
                "at $degrees degrees some run must lie away from the prediction on the canvas",
                runs.any { !it.intersects(prediction.minX, prediction.minY, prediction.maxX, prediction.maxY) },
            )

            val current = stroke()
            val image = bitmap()
            val canvas = Canvas(image)
            val painter = WetStrokePainter(session)
            runs.forEach { painter.paint(canvas, WetCommand(current, it, StampRun.EMPTY), view) }
            painter.paint(canvas, WetCommand(current, StampRun.EMPTY, prediction), view)
            painter.paint(canvas, WetCommand(current, StampRun.EMPTY, StampRun.EMPTY), view)

            val without = bitmap()
            val plain = WetStrokePainter(session)
            runs.forEach { plain.paint(Canvas(without), WetCommand(current, it, StampRun.EMPTY), view) }
            assertTrue(count(without) { Color.alpha(it) > 0 } > 0)
            assertSamePixels(without, image, "at $degrees degrees, pixels differ from the same stroke without a prediction")
        }
    }

    @Test
    fun `a new stroke starts clean, without bringing back the last one in its colour`() {
        val view = ViewTransform(panX = 200f, panY = 200f).toAffine(SIZE, SIZE)
        val old = line(100f, 300f, 100f)
        painter.paint(Canvas(bitmap()), WetCommand(WetStroke(1, pen, Color.BLACK, 1f), old, StampRun.EMPTY), view)

        val image = bitmap()
        val canvas = Canvas(image)
        val next = WetStroke(2, pen, Color.BLUE, 1f)
        // The prediction lies over the old stroke, so that wiping it would bring the old stroke back if it were still kept.
        painter.paint(canvas, WetCommand(next, line(100f, 120f, 300f), run(200f to 100f, diameter = 40f)), view)
        painter.paint(canvas, WetCommand(next, StampRun.EMPTY, StampRun.EMPTY), view)
        assertEquals("nothing of the old stroke may show", 0, Color.alpha(image.getPixel(200, 100)))
        assertTrue(Color.alpha(image.getPixel(110, 300)) > 0)
    }

    @Test
    fun `the layer takes each stroke's own opacity`() {
        val view = ViewTransform(panX = 200f, panY = 200f).toAffine(SIZE, SIZE)
        val canvas = Canvas(bitmap())
        painter.paint(canvas, WetCommand(WetStroke(1, pen, Color.BLACK, 0.25f), line(10f, 20f, 10f), StampRun.EMPTY), view)
        assertEquals(0.25f, painter.layerAlpha, 0f)
        painter.paint(canvas, WetCommand(WetStroke(2, pen, Color.BLACK, 0.75f), line(10f, 20f, 10f), StampRun.EMPTY), view)
        assertEquals(0.75f, painter.layerAlpha, 0f)
    }

    @Test
    fun `the eraser paints with the image of what lies beneath the active layer, and leaves no holes either`() {
        session.wantsRevealImage = true
        session.workspaceColor = Color.GRAY
        session.beginStroke(eraser, Color.BLACK)
        runBlocking { session.awaitIdle() }

        val view = ViewTransform(panX = 200f, panY = 200f, rotationDegrees = 45f).toAffine(SIZE, SIZE)
        val current = WetStroke(1, eraser, Color.BLACK, 1f)
        val real = run(100f to 200f, 150f to 200f, 200f to 200f, 250f to 200f, 300f to 200f, diameter = 30f)
        val prediction = run(200f to 120f, 200f to 180f, 200f to 240f, 200f to 280f, diameter = 30f)
        val image = bitmap()
        val canvas = Canvas(image)
        painter.paint(canvas, WetCommand(current, real, prediction), view)
        painter.paint(canvas, WetCommand(current, StampRun.EMPTY, StampRun.EMPTY), view)
        session.cancelStroke()

        val without = bitmap()
        WetStrokePainter(session).paint(Canvas(without), WetCommand(current, real, StampRun.EMPTY), view)
        assertSamePixels(without, image, "the prediction left a mark")
        assertTrue("the background shows through what is erased", count(image) { it == Color.RED } > 100)
    }

    private companion object {
        const val SIZE = 400
        const val CORNER_INSET = 12f
    }
}
