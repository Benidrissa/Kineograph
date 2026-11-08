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

    private fun make(frameNumber: Int = 1, count: Int = 1, spacing: InBetweenSpacing = InBetweenSpacing.EVEN): InBetweenResult =
        runBlocking { InBetweenMaker(session, store).make(layerId, frame(frameNumber), count, spacing) }

    private fun prepare(frameNumber: Int = 1): InBetweenPlan = runBlocking {
        InBetweenMaker(session, store).prepare(layerId, frame(frameNumber))
    }

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

    /** Saves everything, closes the session, and opens the saved project again in a new one, as reopening the project does. */
    private fun reopen() {
        val saved = runBlocking { session.snapshot() }
        session.close()
        open(saved)
    }

    @Test
    fun `frames drawn before the project was reopened still work, from their kept strokes`() {
        line(20f, 180f, 20f)
        session.addFrame()
        line(20f, 180f, 80f)
        reopen()

        assertEquals(InBetweenOutcome.DONE, make().outcome)

        assertTrue(inked(2, 100, 50))
    }

    @Test
    fun `drawing more on a reopened frame carries on from its kept strokes`() {
        line(20f, 180f, 20f)
        session.addFrame()
        line(20f, 180f, 80f)
        reopen()
        session.selectFrame(frame(1))
        await()
        line(20f, 180f, 30f)
        session.selectFrame(frame(2))
        await()
        line(20f, 180f, 90f)

        assertEquals(InBetweenOutcome.DONE, make().outcome)
    }

    @Test
    fun `in-betweens keep their strokes too, so they work as key frames after reopening`() {
        line(20f, 180f, 20f)
        session.addFrame()
        line(20f, 180f, 80f)
        assertEquals(InBetweenOutcome.DONE, make().outcome)
        reopen()

        assertEquals(InBetweenOutcome.DONE, make(frameNumber = 2).outcome)
    }

    @Test
    fun `a damaged stroke file means the strokes are not known`() {
        line(20f, 180f, 20f)
        session.addFrame()
        line(20f, 180f, 80f)
        val saved = runBlocking { session.snapshot() }
        val file = saved.cel(layerId, saved.frames[0].id)?.file ?: error("no drawing")
        val bytes = store.kept[file] ?: error("nothing kept")
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        session.close()
        open(saved)

        val result = make()

        assertEquals(InBetweenOutcome.NOT_RECORDED, result.outcome)
        assertEquals(1, result.frame)
    }

    @Test
    fun `a slow start puts the in-betweens close to the first drawing`() {
        line(20f, 180f, 10f)
        session.addFrame()
        line(20f, 180f, 90f)

        assertEquals(InBetweenOutcome.DONE, make(count = 3, spacing = InBetweenSpacing.SLOW_START).outcome)

        // A quarter, half and three quarters of the way in time are a sixteenth, a quarter and nine sixteenths of the way in space.
        assertTrue(inked(2, 100, 15))
        assertTrue(inked(3, 100, 30))
        assertTrue(inked(4, 100, 55))
        assertTrue(!inked(4, 100, 70))
    }

    @Test
    fun `a slow end puts the in-betweens close to the second drawing`() {
        line(20f, 180f, 10f)
        session.addFrame()
        line(20f, 180f, 90f)

        assertEquals(InBetweenOutcome.DONE, make(count = 3, spacing = InBetweenSpacing.SLOW_END).outcome)

        assertTrue(inked(2, 100, 45))
        assertTrue(inked(3, 100, 70))
        assertTrue(inked(4, 100, 85))
    }

    @Test
    fun `checking finds the problem before anything is made`() {
        line(20f, 180f, 20f)
        session.addFrame()
        line(20f, 180f, 60f)
        line(20f, 180f, 80f)

        val plan = prepare()

        assertEquals(InBetweenOutcome.STROKE_COUNTS_DIFFER, plan.problem?.outcome)
        assertNull(plan.sketch)
        assertEquals(2, project.frames.size)
    }

    @Test
    fun `the sketch shows each stroke's path where the in-between will be`() {
        line(20f, 180f, 20f)
        session.addFrame()
        line(20f, 180f, 80f)

        val sketch = prepare().sketch ?: error("no sketch")

        assertEquals(1, sketch.strokeCount)
        val halfway = FloatArray(sketch.points * 2)
        sketch.line(0, 0.5f, halfway)
        for (i in 0 until sketch.points) assertEquals(50f, halfway[i * 2 + 1], 0.5f)
        assertEquals(20f, halfway[0], 1f)
        assertEquals(180f, halfway[(sketch.points - 1) * 2], 1f)
        assertTrue(sketch.area[1] < 20f && sketch.area[3] > 80f)
        assertEquals(2, project.frames.size)
    }

    @Test
    fun `a drawing changed after the check is checked again before anything is made`() {
        line(20f, 180f, 20f)
        session.addFrame()
        line(20f, 180f, 80f)
        val maker = InBetweenMaker(session, store)
        val plan = runBlocking { maker.prepare(layerId, frame(1)) }
        assertNull(plan.problem)
        session.fill(100, 40, Color.RED, tolerance = 0, gapClose = 0, sampleAllLayers = false)
        await()

        val result = runBlocking { maker.make(plan, 1) }

        assertEquals(InBetweenOutcome.DRAWN_OTHERWISE, result.outcome)
        assertEquals(2, result.frame)
        assertEquals(2, project.frames.size)
    }

    @Test
    fun `a plan whose drawings are unchanged makes its in-betweens`() {
        line(20f, 180f, 20f)
        session.addFrame()
        line(20f, 180f, 80f)
        val maker = InBetweenMaker(session, store)
        val plan = runBlocking { maker.prepare(layerId, frame(1)) }

        assertEquals(InBetweenOutcome.DONE, runBlocking { maker.make(plan, 2, InBetweenSpacing.SLOW_BOTH) }.outcome)

        assertEquals(4, project.frames.size)
    }

    private companion object {
        const val INK = 64
    }
}
