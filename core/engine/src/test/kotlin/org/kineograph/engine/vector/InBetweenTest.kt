package org.kineograph.engine.vector

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kineograph.engine.FakeCelStore
import org.kineograph.engine.brush.BrushPresets
import org.kineograph.engine.brush.BrushSpec
import org.kineograph.engine.brush.StrokeBuilder
import org.kineograph.engine.brush.StrokeOptions
import org.kineograph.engine.brush.StrokeRecording
import org.kineograph.engine.session.DrawingSession
import org.kineograph.engine.session.MemoryBudget
import org.kineograph.model.Background
import org.kineograph.model.Cel
import org.kineograph.model.CelKey
import org.kineograph.model.IntRect
import org.kineograph.model.Project
import org.kineograph.model.ProjectEdits
import org.kineograph.model.newProject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** In-betweens from real strokes drawn through a real session, on real Skia. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class InBetweenTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val store = FakeCelStore()
    private lateinit var session: DrawingSession
    private val pen = BrushPresets.PEN.copy(sizePx = 8f)
    private var seed = 11L

    @Before
    fun setUp() {
        open(newProject("Test", 200, 100, 12, Background.Transparent, "Layer 1", now = 0))
    }

    @After
    fun tearDown() {
        session.close()
    }

    private fun open(project: Project) {
        session = DrawingSession(project, store, MemoryBudget.forMemoryClass(256, isLowRam = false), temp.newFolder())
        await()
    }

    private fun await() = runBlocking { session.awaitIdle() }

    private val project: Project get() = session.state.value.project
    private val layerId: String get() = session.state.value.layerId

    private fun frame(number: Int): String = project.frames[number - 1].id

    /** A horizontal line from [fromX] to [toX] at [y], drawn as the canvas view draws, with its recording handed over before the commit. */
    private fun line(fromX: Float, toX: Float, y: Float, spec: BrushSpec = pen, color: Int = Color.BLACK) {
        val strokeSeed = seed++
        val recording = StrokeRecording(spec, strokeSeed, false, 1L shl 20)
        val builder = StrokeBuilder(spec, StrokeOptions(), strokeSeed, recording)
        var time = 0L
        builder.begin(fromX, y, fromX, y, 1f, 0f, 0f, time)
        var x = fromX
        while (x < toX) {
            x = minOf(toX, x + 4f)
            time += 8
            builder.add(x, y, x, y, 1f, 0f, 0f, time)
        }
        builder.finish()
        session.beginStroke(spec, color)
        session.addStamps(builder.stamps.snapshot())
        session.attachStrokeRecording(recording)
        session.endStroke { }
        await()
    }

    private fun make(frameNumber: Int = 1, count: Int = 1): InBetweenResult =
        runBlocking { InBetweenMaker(session, store).make(layerId, frame(frameNumber), count) }

    private fun picture(frameNumber: Int): Pair<Bitmap, IntRect>? {
        val cel = project.cel(layerId, frame(frameNumber)) ?: return null
        val file = cel.file ?: return null
        val bounds = cel.bounds ?: return null
        return (store.read(file, 1) ?: error("no image")) to bounds
    }

    /** Whether canvas pixel ([x], [y]) has ink on it in frame [frameNumber]. */
    private fun inked(frameNumber: Int, x: Int, y: Int): Boolean {
        val (image, bounds) = picture(frameNumber) ?: return false
        val px = x - bounds.left
        val py = y - bounds.top
        if (px !in 0 until image.width || py !in 0 until image.height) return false
        return Color.alpha(image.getPixel(px, py)) > INK
    }

    @Test
    fun `a line that moves down gets an in-between halfway`() {
        line(20f, 180f, 20f)
        session.addFrame()
        line(20f, 180f, 80f)

        assertEquals(InBetweenOutcome.DONE, make().outcome)

        assertEquals(3, project.frames.size)
        assertTrue("halfway", inked(2, 100, 50))
        assertTrue("not where the first was", !inked(2, 100, 20))
        assertTrue("not where the second was", !inked(2, 100, 80))
        assertTrue("the first frame is untouched", inked(1, 100, 20))
        assertTrue("the last frame is untouched", inked(3, 100, 80))
    }

    @Test
    fun `several in-betweens are spaced evenly`() {
        line(20f, 180f, 10f)
        session.addFrame()
        line(20f, 180f, 90f)

        assertEquals(InBetweenOutcome.DONE, make(count = 3).outcome)

        assertEquals(5, project.frames.size)
        assertTrue(inked(2, 100, 30))
        assertTrue(inked(3, 100, 50))
        assertTrue(inked(4, 100, 70))
    }

    @Test
    fun `an in-between can itself be a key frame`() {
        line(20f, 180f, 20f)
        session.addFrame()
        line(20f, 180f, 80f)
        assertEquals(InBetweenOutcome.DONE, make().outcome)

        assertEquals(InBetweenOutcome.DONE, make(frameNumber = 2).outcome)

        assertEquals(4, project.frames.size)
        assertTrue(inked(3, 100, 65))
    }

    @Test
    fun `one undo takes all the in-betweens back`() {
        line(20f, 180f, 20f)
        session.addFrame()
        line(20f, 180f, 80f)
        assertEquals(InBetweenOutcome.DONE, make(count = 4).outcome)

        session.undo()
        await()

        assertEquals(2, project.frames.size)
    }

    @Test
    fun `a stroke taken back with undo leaves the log`() {
        line(20f, 180f, 20f)
        line(20f, 180f, 40f)
        session.undo()
        await()
        session.addFrame()
        line(20f, 180f, 80f)

        assertEquals(InBetweenOutcome.DONE, make().outcome)
    }

    @Test
    fun `a stroke undone and done again is back in the log`() {
        line(20f, 180f, 20f)
        line(20f, 180f, 40f)
        session.undo()
        session.redo()
        await()
        session.addFrame()
        line(20f, 180f, 60f)
        line(20f, 180f, 80f)

        assertEquals(InBetweenOutcome.DONE, make().outcome)
    }

    @Test
    fun `a fill is caught by drawing the strokes again, and refused`() {
        line(20f, 180f, 20f)
        session.fill(100, 70, Color.RED, tolerance = 0, gapClose = 0, sampleAllLayers = false)
        await()
        session.addFrame()
        line(20f, 180f, 80f)

        val result = make()

        assertEquals(InBetweenOutcome.DRAWN_OTHERWISE, result.outcome)
        assertEquals(1, result.frame)
        assertEquals(2, project.frames.size)
    }

    @Test
    fun `undoing the fill makes the frame usable again`() {
        line(20f, 180f, 20f)
        session.fill(100, 70, Color.RED, tolerance = 0, gapClose = 0, sampleAllLayers = false)
        session.undo()
        await()
        session.addFrame()
        line(20f, 180f, 80f)

        assertEquals(InBetweenOutcome.DONE, make().outcome)
    }

    @Test
    fun `different numbers of strokes are refused with both counts`() {
        line(20f, 180f, 20f)
        session.addFrame()
        line(20f, 180f, 60f)
        line(20f, 180f, 80f)

        val result = make()

        assertEquals(InBetweenOutcome.STROKE_COUNTS_DIFFER, result.outcome)
        assertEquals(1 to 2, result.first to result.second)
    }

    @Test
    fun `a different brush is refused, naming the stroke`() {
        line(20f, 180f, 20f)
        session.addFrame()
        line(20f, 180f, 80f, spec = BrushPresets.PENCIL)

        val result = make()

        assertEquals(InBetweenOutcome.BRUSH_DIFFERS, result.outcome)
        assertEquals(1, result.first)
    }

    @Test
    fun `the last frame has nothing to go to`() {
        line(20f, 180f, 20f)

        assertEquals(InBetweenOutcome.NO_NEXT_FRAME, make().outcome)
    }

    @Test
    fun `two empty frames have nothing to work from`() {
        session.addFrame()

        assertEquals(InBetweenOutcome.NOTHING_DRAWN, make().outcome)
    }

    @Test
    fun `a drawing from before the project was opened is refused`() {
        val image = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val file = store.write(image)
        var start = newProject("Test", 200, 100, 12, Background.Transparent, "Layer 1", now = 0)
        val layer = start.layers.single().id
        val first = start.frames.single().id
        start = ProjectEdits.withCel(start, CelKey(layer, first), Cel(layer, first, file, IntRect(10, 10, 20, 20)))
        start = ProjectEdits.addFrameAfter(start, first)
        session.close()
        open(start)

        val result = make()

        assertEquals(InBetweenOutcome.NOT_RECORDED, result.outcome)
        assertEquals(1, result.frame)
    }

    @Test
    fun `other layers keep the first frame's drawing`() {
        line(20f, 180f, 20f)
        session.addFrame()
        line(20f, 180f, 80f)
        val drawingLayer = layerId
        session.addLayer("Background")
        await()
        session.selectFrame(frame(1))
        await()
        line(20f, 180f, 50f, color = Color.BLUE)
        session.selectLayer(drawingLayer)
        await()
        val background = project.layers.first { it.id != drawingLayer }.id

        assertEquals(InBetweenOutcome.DONE, make().outcome)

        val held = project.cel(background, frame(2))
        assertNotNull(held)
        assertEquals(project.cel(background, frame(1))?.file, held?.file)
        assertNull(project.cel(background, frame(3)))
    }

    private companion object {
        const val INK = 64
    }
}
