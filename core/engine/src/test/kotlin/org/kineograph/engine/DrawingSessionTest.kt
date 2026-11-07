package org.kineograph.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kineograph.engine.brush.BrushPresets
import org.kineograph.engine.brush.BrushSpec
import org.kineograph.engine.brush.PressureCurve
import org.kineograph.engine.brush.StampBuffer
import org.kineograph.engine.brush.StrokeEngine
import org.kineograph.engine.compositor.FlattenOptions
import org.kineograph.engine.compositor.FrameFlattener
import org.kineograph.engine.compositor.SceneRenderer
import org.kineograph.engine.selection.Affine
import org.kineograph.engine.selection.SelectionHandle
import org.kineograph.engine.session.CelStore
import org.kineograph.engine.session.DrawingSession
import org.kineograph.engine.session.MemoryBudget
import org.kineograph.engine.session.SessionEvent
import org.kineograph.engine.session.SessionState
import org.kineograph.model.Background
import org.kineograph.model.Project
import org.kineograph.model.newProject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

/** Runs the whole session against real Skia drawing, through Robolectric's native graphics mode. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DrawingSessionTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val store = FakeCelStore()
    private lateinit var session: DrawingSession
    private val pen = BrushSpec(id = "test", sizePx = 10f, spacing = 0.1f, sizeCurve = PressureCurve.FLAT)
    private val black = Color.BLACK

    @Before
    fun setUp() {
        val project = newProject("Test", 200, 100, 12, Background.Transparent, "Layer 1", now = 0)
        session = DrawingSession(project, store, MemoryBudget.forMemoryClass(256, isLowRam = false), temp.newFolder())
        await()
    }

    @After
    fun tearDown() {
        session.close()
    }

    private fun await() = runBlocking { session.awaitIdle() }

    private val state: SessionState get() = session.state.value

    private fun stroke(spec: BrushSpec = pen, color: Int = black, fromX: Float = 20f, toX: Float = 80f, y: Float = 50f) {
        val engine = StrokeEngine(spec, 7L)
        val out = StampBuffer()
        engine.begin(fromX, y, 1f, 0f, 0f, out)
        var x = fromX
        while (x < toX) {
            x += 5f
            engine.add(x, y, 1f, 0f, 0f, out)
        }
        engine.finish(out)
        session.beginStroke(spec, color)
        session.addStamps(out.snapshot())
        session.endStroke { }
        await()
    }

    /** The composite of the live scene, transparent background and all, read back as pixels. */
    private fun render(): Bitmap {
        val scene = session.scene ?: error("no scene")
        val out = Bitmap.createBitmap(scene.canvasWidth, scene.canvasHeight, Bitmap.Config.ARGB_8888)
        SceneRenderer().draw(Canvas(out), scene, Affine.IDENTITY, Color.GRAY)
        return out
    }

    /** Whether a pixel shows ink over the grey workspace. Translucent layers need a higher [tolerance]. */
    private fun isInked(x: Int, y: Int, tolerance: Int = 40): Boolean = Color.red(render().getPixel(x, y)) < tolerance

    /** Reads what would be saved for a frame, independently of the live scene. */
    private fun savedFrame(project: Project, frameId: String, from: CelStore = store): Bitmap {
        val out = Bitmap.createBitmap(project.width, project.height, Bitmap.Config.ARGB_8888)
        FrameFlattener(from).flatten(project, frameId, Canvas(out), 1f, null, FlattenOptions(includeBackground = false))
        return out
    }

    private fun snapshot(): Project = runBlocking { session.snapshot() }

    private fun noCel(project: Project = snapshot()) = project.cels.isEmpty()

    @Test
    fun `a new session is ready with an empty frame`() {
        assertTrue(state.ready)
        assertTrue(noCel())
        assertFalse(state.canUndo)
        assertFalse(isInked(50, 50))
    }

    @Test
    fun `a stroke paints the layer`() {
        stroke()
        assertTrue(isInked(50, 50))
        assertFalse(isInked(50, 20))
        assertTrue(state.canUndo)
    }

    @Test
    fun `undo removes a stroke and redo restores it`() {
        stroke()
        session.undo()
        await()
        assertFalse(isInked(50, 50))
        assertTrue(state.canRedo)
        session.redo()
        await()
        assertTrue(isInked(50, 50))
        assertFalse(state.canRedo)
    }

    @Test
    fun `one hundred strokes can all be undone and redone`() {
        repeat(100) { i ->
            stroke(fromX = (i % 190).toFloat(), toX = (i % 190 + 6).toFloat(), y = (i % 90 + 5).toFloat())
        }
        repeat(100) {
            assertTrue(state.canUndo)
            session.undo()
            await()
        }
        assertFalse(state.canUndo)
        assertTrue(snapshot().cels.isEmpty())
        repeat(100) {
            assertTrue(state.canRedo)
            session.redo()
            await()
        }
        assertFalse(state.canRedo)
        assertTrue(snapshot().cels.isNotEmpty())
    }

    @Test
    fun `a stroke's opacity caps the whole stroke even where it overlaps itself`() {
        val translucent = pen.copy(opacity = 0.5f)
        val engine = StrokeEngine(translucent, 7L)
        val out = StampBuffer()
        engine.begin(20f, 50f, 1f, 0f, 0f, out)
        // There and back over the same place.
        for (x in 25..80 step 5) engine.add(x.toFloat(), 50f, 1f, 0f, 0f, out)
        for (x in 75 downTo 20 step 5) engine.add(x.toFloat(), 50f, 1f, 0f, 0f, out)
        engine.finish(out)
        session.beginStroke(translucent, black)
        session.addStamps(out.snapshot())
        session.endStroke { }
        await()
        val saved = savedFrame(snapshot(), state.frameId)
        val alpha = Color.alpha(saved.getPixel(50, 50))
        assertTrue("alpha was $alpha", alpha in 120..135)
    }

    @Test
    fun `the eraser removes paint and is undoable`() {
        stroke()
        stroke(spec = BrushPresets.ERASER.copy(sizeCurve = PressureCurve.FLAT, hardness = 1f, sizePx = 30f), fromX = 40f, toX = 60f)
        assertFalse(isInked(50, 50))
        assertTrue("the eraser's reach ends before x=22", isInked(22, 50))
        session.undo()
        await()
        assertTrue(isInked(50, 50))
    }

    @Test
    fun `erasing everything leaves no cel on the frame`() {
        stroke()
        assertFalse(noCel())
        stroke(spec = BrushPresets.ERASER.copy(sizeCurve = PressureCurve.FLAT, hardness = 1f, sizePx = 60f), fromX = 5f, toX = 100f)
        assertTrue(noCel())
    }

    @Test
    fun `saved cels are cropped to their content`() {
        stroke(fromX = 20f, toX = 80f, y = 50f)
        val project = snapshot()
        val bounds = project.cels.values
            .single()
            .bounds ?: error("no bounds")
        assertTrue(bounds.left in 10..20)
        assertTrue(bounds.right in 80..90)
        assertTrue(bounds.top in 40..50)
        assertTrue(bounds.bottom in 50..60)
        val image = store.read(
            project.cels.values
                .single()
                .file ?: "",
        ) ?: error("no image")
        assertEquals(bounds.width, image.width)
        assertEquals(bounds.height, image.height)
    }

    @Test
    fun `drawing past the canvas edge is kept but never exported`() {
        stroke(fromX = 170f, toX = 215f, y = 50f)
        val project = snapshot()
        val bounds = project.cels.values
            .single()
            .bounds ?: error("no bounds")
        assertTrue("right edge ${bounds.right} should pass the canvas", bounds.right > 200)
        val exported = savedFrame(project, state.frameId)
        assertEquals(200, exported.width)
        assertTrue(Color.alpha(exported.getPixel(195, 50)) > 200)
    }

    @Test
    fun `a stroke started outside the canvas still draws inside it`() {
        stroke(fromX = -8f, toX = 30f, y = 50f)
        assertTrue(isInked(15, 50))
    }

    @Test
    fun `frames keep their own drawings`() {
        stroke(y = 30f)
        val first = state.frameId
        session.addFrame()
        await()
        assertNotEquals(first, state.frameId)
        assertFalse(isInked(50, 30))
        stroke(y = 70f)
        session.selectFrame(first)
        await()
        assertTrue(isInked(50, 30))
        assertFalse(isInked(50, 70))
    }

    @Test
    fun `undo returns to the frame where the edit happened`() {
        stroke(y = 30f)
        val first = state.frameId
        session.addFrame()
        await()
        val second = state.frameId
        stroke(y = 70f)
        session.selectFrame(first)
        await()
        session.undo()
        await()
        assertEquals(second, state.frameId)
        assertFalse(isInked(50, 70))
    }

    @Test
    fun `frame structure edits can be undone`() {
        stroke()
        val first = state.frameId
        session.addFrame()
        await()
        assertEquals(2, state.project.frames.size)
        session.undo()
        await()
        assertEquals(1, state.project.frames.size)
        assertEquals(first, state.frameId)
        assertTrue(isInked(50, 50))
        session.redo()
        await()
        assertEquals(2, state.project.frames.size)
    }

    @Test
    fun `deleting a frame can be undone with its drawing intact`() {
        session.addFrame()
        await()
        val second = state.frameId
        stroke()
        session.deleteFrame(second)
        await()
        assertEquals(1, state.project.frames.size)
        assertFalse(isInked(50, 50))
        session.undo()
        await()
        assertEquals(2, state.project.frames.size)
        assertEquals(second, state.frameId)
        assertTrue(isInked(50, 50))
    }

    @Test
    fun `duplicating a frame copies the drawing and editing the copy leaves the original`() {
        stroke(y = 30f)
        val first = state.frameId
        session.duplicateFrame(first)
        await()
        assertTrue(isInked(50, 30))
        stroke(y = 70f)
        assertTrue(isInked(50, 70))
        session.selectFrame(first)
        await()
        assertTrue(isInked(50, 30))
        assertFalse(isInked(50, 70))
    }

    @Test
    fun `copy and paste frames`() {
        stroke(y = 30f)
        session.copyFrames(listOf(state.frameId))
        await()
        assertTrue(state.hasFrameClipboard)
        session.pasteFrames()
        await()
        assertEquals(2, state.project.frames.size)
        assertEquals(state.project.frames[1].id, state.frameId)
        assertTrue(isInked(50, 30))
    }

    @Test
    fun `clearing a cel can be undone`() {
        stroke()
        session.clearActiveCel()
        await()
        assertFalse(isInked(50, 50))
        session.undo()
        await()
        assertTrue(isInked(50, 50))
    }

    @Test
    fun `an active layer sits between the layers below and above`() {
        stroke(color = Color.RED, y = 50f)
        session.addLayer("Top")
        await()
        stroke(color = Color.BLUE, y = 50f)
        assertEquals(Color.BLUE, render().getPixel(50, 50))
        val top = state.layerId
        val bottom = state.project.layers
            .first()
            .id
        session.selectLayer(bottom)
        await()
        assertEquals(Color.BLUE, render().getPixel(50, 50))
        session.setLayerVisible(top, false)
        await()
        assertEquals(Color.RED, render().getPixel(50, 50))
        session.moveLayer(1, 0)
        await()
        session.setLayerVisible(top, true)
        await()
        assertEquals(Color.RED, render().getPixel(50, 50))
    }

    @Test
    fun `hidden and locked layers refuse drawing`() {
        val id = state.layerId
        assertTrue(state.canDrawOnActiveLayer)
        session.setLayerVisible(id, false)
        await()
        assertFalse(state.canDrawOnActiveLayer)
        session.setLayerVisible(id, true)
        session.setLayerLocked(id, true)
        await()
        assertFalse(state.canDrawOnActiveLayer)
    }

    @Test
    fun `layers can be added far beyond what other apps allow`() {
        repeat(50) { session.addLayer("L$it") }
        await()
        assertEquals(51, state.project.layers.size)
    }

    @Test
    fun `layer opacity preview is one undo step`() {
        stroke()
        val id = state.layerId
        val depthProbe = state.canUndo
        assertTrue(depthProbe)
        session.previewLayerOpacity(id, 0.8f)
        session.previewLayerOpacity(id, 0.5f)
        session.commitLayerOpacity(id, 0.5f)
        await()
        assertEquals(0.5f, state.project.layer(id)?.opacity ?: 0f, 0f)
        session.undo()
        await()
        assertEquals(1f, state.project.layer(id)?.opacity ?: 0f, 0f)
        assertTrue(isInked(50, 50))
    }

    @Test
    fun `merge down combines two layers and keeps how they looked`() {
        stroke(color = Color.RED, y = 30f)
        session.addLayer("Top")
        await()
        stroke(color = Color.BLUE, y = 70f)
        val before = render()
        session.mergeLayerDown(state.layerId)
        await()
        assertEquals(1, state.project.layers.size)
        val after = render()
        assertEquals(before.getPixel(50, 30), after.getPixel(50, 30))
        assertEquals(before.getPixel(50, 70), after.getPixel(50, 70))
        session.undo()
        await()
        assertEquals(2, state.project.layers.size)
    }

    @Test
    fun `merge down bakes in layer opacity`() {
        session.previewLayerOpacity(state.layerId, 0.5f)
        session.commitLayerOpacity(state.layerId, 0.5f)
        stroke(color = Color.BLACK, y = 50f)
        val bottom = state.project.layers
            .first()
            .id
        session.addLayer("Top")
        await()
        session.mergeLayerDown(state.layerId)
        await()
        val layer = state.project.layer(bottom) ?: error("layer gone")
        assertEquals(1f, layer.opacity, 0f)
        val saved = savedFrame(snapshot(), state.frameId)
        assertTrue(Color.alpha(saved.getPixel(50, 50)) in 110..140)
    }

    @Test
    fun `the bottom layer cannot be merged down`() {
        session.mergeLayerDown(state.layerId)
        await()
        assertEquals(1, state.project.layers.size)
    }

    @Test
    fun `fill paints an enclosed shape and stops at its lines`() {
        stroke(y = 20f, fromX = 40f, toX = 160f)
        stroke(y = 80f, fromX = 40f, toX = 160f)
        val tall = pen
        val left = StampBuffer()
        StrokeEngine(tall, 1L).run {
            begin(40f, 20f, 1f, 0f, 0f, left)
            add(40f, 50f, 1f, 0f, 0f, left)
            add(40f, 80f, 1f, 0f, 0f, left)
            finish(left)
        }
        session.beginStroke(tall, black)
        session.addStamps(left.snapshot())
        session.endStroke { }
        val right = StampBuffer()
        StrokeEngine(tall, 1L).run {
            begin(160f, 20f, 1f, 0f, 0f, right)
            add(160f, 50f, 1f, 0f, 0f, right)
            add(160f, 80f, 1f, 0f, 0f, right)
            finish(right)
        }
        session.beginStroke(tall, black)
        session.addStamps(right.snapshot())
        session.endStroke { }
        await()

        session.fill(100, 50, Color.RED, 32, 0, sampleAllLayers = false)
        await()
        val image = render()
        assertEquals(Color.RED, image.getPixel(100, 50))
        assertEquals(black, image.getPixel(100, 20))
        assertNotEquals(Color.RED, image.getPixel(10, 10))
        session.undo()
        await()
        assertNotEquals(Color.RED, render().getPixel(100, 50))
    }

    @Test
    fun `fill can use all layers to find the shape while painting the active layer`() {
        stroke(y = 50f, fromX = 0f, toX = 200f)
        session.addLayer("Paint")
        await()
        session.fill(100, 80, Color.RED, 32, 0, sampleAllLayers = true)
        await()
        val saved = savedFrame(snapshot(), state.frameId)
        assertEquals(Color.RED, saved.getPixel(100, 80))
        assertNotEquals(Color.RED, saved.getPixel(100, 20))
    }

    @Test
    fun `fill outside the canvas does nothing`() {
        session.fill(-5, 500, Color.RED, 32, 0, sampleAllLayers = false)
        await()
        assertFalse(state.canUndo)
    }

    private val box = floatArrayOf(10f, 30f, 90f, 30f, 90f, 70f, 10f, 70f)

    @Test
    fun `lasso lifts and moves pixels as one undo step`() {
        stroke(fromX = 20f, toX = 80f, y = 50f)
        session.liftSelection(box)
        await()
        val selection = state.selection ?: error("nothing lifted")
        session.setSelectionTransform(selection.dragged(SelectionHandle.MOVE, 50f, 50f, 150f, 50f))
        await()
        session.commitSelection()
        await()
        assertNull(state.selection)
        val moved = render()
        assertTrue(Color.red(moved.getPixel(150, 50)) < 40)
        assertTrue(Color.red(moved.getPixel(50, 50)) > 100)
        session.undo()
        await()
        val restored = render()
        assertTrue(Color.red(restored.getPixel(50, 50)) < 40)
        assertTrue(Color.red(restored.getPixel(150, 50)) > 100)
    }

    @Test
    fun `lasso shows the pixels while they float`() {
        stroke(fromX = 20f, toX = 80f, y = 50f)
        session.liftSelection(box)
        await()
        val selection = state.selection ?: error("nothing lifted")
        session.setSelectionTransform(selection.copy(translateX = 100f))
        await()
        assertTrue(isInked(150, 50))
    }

    @Test
    fun `cancelling a selection puts everything back and adds no undo step`() {
        stroke(fromX = 20f, toX = 80f, y = 50f)
        session.undo()
        await()
        session.redo()
        await()
        session.liftSelection(box)
        await()
        session.cancelSelection()
        await()
        assertNull(state.selection)
        assertTrue(isInked(50, 50))
    }

    @Test
    fun `committing an unmoved selection changes nothing`() {
        stroke(fromX = 20f, toX = 80f, y = 50f)
        session.liftSelection(box)
        await()
        session.commitSelection()
        await()
        assertTrue(isInked(50, 50))
        session.undo()
        await()
        assertFalse(isInked(50, 50))
    }

    @Test
    fun `undo while floating cancels the selection first`() {
        stroke(fromX = 20f, toX = 80f, y = 50f)
        session.liftSelection(box)
        await()
        session.undo()
        await()
        assertNull(state.selection)
        assertTrue(isInked(50, 50))
    }

    @Test
    fun `lassoing empty space lifts nothing`() {
        session.liftSelection(box)
        await()
        assertNull(state.selection)
    }

    @Test
    fun `deleting a selection is undoable`() {
        stroke(fromX = 20f, toX = 80f, y = 50f)
        session.liftSelection(box)
        await()
        session.deleteSelection()
        await()
        assertFalse(isInked(50, 50))
        session.undo()
        await()
        assertTrue(isInked(50, 50))
    }

    @Test
    fun `switching frames with a floating selection puts it down first`() {
        stroke(fromX = 20f, toX = 80f, y = 50f)
        val first = state.frameId
        session.liftSelection(box)
        await()
        session.setSelectionTransform((state.selection ?: error("none")).copy(translateX = 100f))
        session.addFrame()
        await()
        assertNull(state.selection)
        session.selectFrame(first)
        await()
        assertTrue(isInked(150, 50))
    }

    @Test
    fun `saving while a selection floats keeps the last good pixels`() {
        stroke(fromX = 20f, toX = 80f, y = 50f)
        val saved = snapshot()
        session.liftSelection(box)
        await()
        val during = snapshot()
        assertEquals(saved.cels, during.cels)
        session.cancelSelection()
        await()
    }

    @Test
    fun `a cancelled stroke leaves no trace`() {
        val out = StampBuffer()
        StrokeEngine(pen, 1L).begin(50f, 50f, 1f, 0f, 0f, out)
        session.beginStroke(pen, black)
        session.addStamps(out.snapshot())
        session.cancelStroke()
        await()
        assertFalse(isInked(50, 50))
        assertFalse(state.canUndo)
    }

    @Test
    fun `the wet stroke shows in the scene before it is committed`() {
        val out = StampBuffer()
        val engine = StrokeEngine(pen, 1L)
        engine.begin(50f, 50f, 1f, 0f, 0f, out)
        engine.finish(out)
        session.beginStroke(pen, black)
        session.addStamps(out.snapshot())
        await()
        assertNotNull(session.scene?.wet)
        assertTrue(isInked(50, 50))
        session.endStroke { }
        await()
        assertNull(session.scene?.wet)
        assertTrue(isInked(50, 50))
    }

    @Test
    fun `a wet eraser stroke previews correctly on the software path`() {
        stroke()
        val eraser = BrushPresets.ERASER.copy(sizeCurve = PressureCurve.FLAT, hardness = 1f, sizePx = 30f)
        val out = StampBuffer()
        val engine = StrokeEngine(eraser, 1L)
        engine.begin(50f, 50f, 1f, 0f, 0f, out)
        engine.finish(out)
        session.beginStroke(eraser, black)
        session.addStamps(out.snapshot())
        await()
        assertFalse("should look erased while wet", isInked(50, 50))
        assertTrue(isInked(30, 50))
        session.cancelStroke()
        await()
        assertTrue(isInked(50, 50))
    }

    @Test
    fun `saving does not disturb undo`() {
        stroke(y = 30f)
        snapshot()
        stroke(y = 70f)
        snapshot()
        session.undo()
        await()
        snapshot()
        assertTrue(isInked(50, 30))
        assertFalse(isInked(50, 70))
        session.undo()
        await()
        assertFalse(isInked(50, 30))
        session.redo()
        session.redo()
        await()
        assertTrue(isInked(50, 30))
        assertTrue(isInked(50, 70))
    }

    @Test
    fun `undoing past a structure edit restores cel files saved along the way`() {
        stroke(y = 30f)
        val first = state.frameId
        session.addFrame()
        await()
        session.selectFrame(first)
        await()
        stroke(y = 70f)
        snapshot()
        session.undo()
        await()
        assertFalse(isInked(50, 70))
        session.undo() // Selecting a frame is not a step: this undoes "add frame".
        await()
        assertEquals(1, state.project.frames.size)
        assertTrue(isInked(50, 30))
        assertFalse(isInked(50, 70))
    }

    @Test
    fun `referenced files include what undo could bring back`() {
        stroke(y = 30f)
        val firstFile = snapshot()
            .cels.values
            .single()
            .file ?: error("no file")
        session.clearActiveCel()
        await()
        assertTrue(snapshot().cels.isEmpty())
        val referenced = runBlocking { session.referencedFiles() }
        assertTrue(firstFile in referenced)
    }

    @Test
    fun `settings changes survive undo`() {
        stroke()
        session.addFrame()
        await()
        session.setFps(24)
        session.rename("Renamed")
        await()
        session.undo()
        await()
        assertEquals(24, state.project.fps)
        assertEquals("Renamed", state.project.name)
        assertEquals(1, state.project.frames.size)
    }

    @Test
    fun `resizing the canvas keeps the drawing centred and can be undone`() {
        stroke(fromX = 90f, toX = 110f, y = 50f)
        session.resizeCanvas(400, 200)
        await()
        assertEquals(400, state.project.width)
        assertEquals(400, session.scene?.canvasWidth)
        assertTrue(isInked(200, 100))
        session.undo()
        await()
        assertEquals(200, state.project.width)
        assertTrue(isInked(100, 50))
        session.redo()
        await()
        assertEquals(400, state.project.width)
        assertTrue(isInked(200, 100))
    }

    @Test
    fun `shrinking the canvas keeps what falls outside so it can come back`() {
        stroke(fromX = 5f, toX = 15f, y = 50f)
        session.resizeCanvas(100, 100)
        await()
        session.resizeCanvas(200, 100)
        await()
        assertTrue(isInked(10, 50))
    }

    @Test
    fun `the background can be changed`() {
        session.setBackground(Background.Color(Color.RED))
        await()
        assertEquals(Color.RED, render().getPixel(5, 5))
    }

    @Test
    fun `onion skin ghosts appear for the previous frame`() {
        stroke(y = 30f)
        session.updateSettings { it.copy(onionSkin = it.onionSkin.copy(enabled = true, opacity = 1f, tint = false)) }
        session.addFrame()
        await()
        waitForGhosts()
        assertEquals(1, session.scene?.ghosts?.size)
        assertTrue(isInked(50, 30))
        val saved = savedFrame(snapshot(), state.frameId)
        assertEquals(0, Color.alpha(saved.getPixel(50, 30)))
    }

    @Test
    fun `ghosts are tinted when asked`() {
        stroke(y = 30f)
        session.updateSettings { it.copy(onionSkin = it.onionSkin.copy(enabled = true, opacity = 1f, tint = true)) }
        session.addFrame()
        await()
        waitForGhosts()
        assertNotNull(
            session.scene
                ?.ghosts
                ?.single()
                ?.tint,
        )
    }

    @Test
    fun `turning onion skin off removes the ghosts`() {
        stroke(y = 30f)
        session.updateSettings { it.copy(onionSkin = it.onionSkin.copy(enabled = true)) }
        session.addFrame()
        await()
        waitForGhosts()
        session.updateSettings { it.copy(onionSkin = it.onionSkin.copy(enabled = false)) }
        await()
        assertTrue(session.scene?.ghosts?.isEmpty() == true)
    }

    private fun waitForGhosts() {
        val deadline = System.currentTimeMillis() + 5000
        while (session.scene?.ghosts.isNullOrEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            await()
        }
    }

    // ----- Opacity previews must never bring back an older project -----

    @Test
    fun `a save during an opacity drag keeps the stroke, and undo takes the two steps apart`() {
        stroke()
        val id = state.layerId
        session.previewLayerOpacity(id, 0.8f)
        await()
        val saved = snapshot()
        session.previewLayerOpacity(id, 0.5f)
        session.commitLayerOpacity(id, 0.5f)
        await()
        assertTrue("the stroke must survive the slider's release", isInked(50, 50, tolerance = 140))
        assertEquals(saved.cels, state.project.cels)
        assertEquals(0.5f, state.project.layer(id)?.opacity ?: 0f, 0f)
        session.undo()
        await()
        assertEquals(1f, state.project.layer(id)?.opacity ?: 0f, 0f)
        assertTrue(isInked(50, 50))
        session.undo()
        await()
        assertFalse(isInked(50, 50))
    }

    @Test
    fun `dragging an opacity back to where it started keeps a stroke saved during the drag`() {
        stroke()
        val id = state.layerId
        session.previewLayerOpacity(id, 0.3f)
        await()
        val saved = snapshot()
        session.commitLayerOpacity(id, 1f)
        await()
        assertEquals(saved.cels, state.project.cels)
        assertEquals(1f, state.project.layer(id)?.opacity ?: 0f, 0f)
        assertEquals(1f, session.scene?.active?.opacity ?: 0f, 0f)
        assertTrue(isInked(50, 50))
    }

    @Test
    fun `a drag abandoned when its sheet closed cannot revert work done later`() {
        val id = state.layerId
        session.previewLayerOpacity(id, 0.5f)
        session.endLayerOpacityPreview()
        session.addFrame()
        await()
        stroke()
        session.previewLayerOpacity(id, 0.8f)
        session.commitLayerOpacity(id, 0.8f)
        await()
        assertEquals(2, state.project.frames.size)
        assertTrue(isInked(50, 50, tolerance = 80))
    }

    @Test
    fun `ending a drag that was never started changes nothing`() {
        stroke()
        val before = state.revision
        session.endLayerOpacityPreview()
        await()
        assertEquals(before, state.revision)
    }

    @Test
    fun `an edit during a drag does not record the previewed opacity as the original`() {
        val id = state.layerId
        session.previewLayerOpacity(id, 0.2f)
        session.addFrame()
        session.commitLayerOpacity(id, 0.2f)
        await()
        assertEquals(0.2f, state.project.layer(id)?.opacity ?: 0f, 0f)
        session.undo() // The frame.
        await()
        assertEquals(1, state.project.frames.size)
        session.undo() // The drag, which ended when the frame was added.
        await()
        assertEquals(1f, state.project.layer(id)?.opacity ?: 0f, 0f)
    }

    @Test
    fun `switching layers in mid drag leaves each layer with the right opacity`() {
        val lower = state.layerId
        session.addLayer("Top")
        await()
        val upper = state.layerId
        session.previewLayerOpacity(upper, 0.4f)
        session.previewLayerOpacity(lower, 0.6f)
        session.commitLayerOpacity(lower, 0.6f)
        await()
        assertEquals(0.4f, state.project.layer(upper)?.opacity ?: 0f, 0f)
        assertEquals(0.6f, state.project.layer(lower)?.opacity ?: 0f, 0f)
        session.undo()
        session.undo()
        await()
        assertEquals(1f, state.project.layer(upper)?.opacity ?: 0f, 0f)
        assertEquals(1f, state.project.layer(lower)?.opacity ?: 0f, 0f)
    }

    @Test
    fun `a drag ending on another layer keeps what the first layer shows`() {
        val lower = state.layerId
        session.addLayer("Top")
        await()
        val upper = state.layerId
        session.previewLayerOpacity(upper, 0.4f)
        session.commitLayerOpacity(lower, 1f)
        await()
        assertEquals(0.4f, state.project.layer(upper)?.opacity ?: 0f, 0f)
        assertEquals(1f, state.project.layer(lower)?.opacity ?: 0f, 0f)
    }

    // ----- Saving keeps the UI up to date -----

    @Test
    fun `a save publishes the new cel so thumbnails can follow`() {
        stroke()
        assertTrue(state.project.cels.isEmpty())
        val saved = snapshot()
        assertEquals(saved.cels, state.project.cels)
        assertEquals(1, state.project.cels.size)
    }

    @Test
    fun `a save that puts down a moved selection leaves no selection on screen`() {
        stroke(fromX = 20f, toX = 80f, y = 50f)
        session.liftSelection(box)
        await()
        val selection = state.selection ?: error("nothing lifted")
        session.setSelectionTransform(selection.dragged(SelectionHandle.MOVE, 50f, 50f, 120f, 50f))
        await()
        runBlocking { session.snapshot(commitSelection = true) }
        assertNull(state.selection)
        assertNull(session.scene?.floating)
        assertFalse(state.project.cels.isEmpty())
    }

    @Test
    fun `a save that puts down an unmoved selection leaves no selection on screen`() {
        stroke(fromX = 20f, toX = 80f, y = 50f)
        session.liftSelection(box)
        await()
        assertNotNull(state.selection)
        runBlocking { session.snapshot(commitSelection = true) }
        assertNull(state.selection)
        assertNull(session.scene?.floating)
        assertTrue(isInked(50, 50))
    }

    @Test
    fun `copying frames while a selection floats also clears it from the canvas`() {
        stroke(fromX = 20f, toX = 80f, y = 50f)
        session.liftSelection(box)
        await()
        session.copyFrames(listOf(state.frameId))
        await()
        assertNull(state.selection)
        assertNull(session.scene?.floating)
    }

    @Test
    fun `a refused edit does not leave a dead selection on the canvas`() {
        stroke(fromX = 20f, toX = 80f, y = 50f)
        session.liftSelection(box)
        await()
        session.fill(-5, -5, Color.RED, 0, 0, false)
        await()
        assertNull(state.selection)
        assertNull(session.scene?.floating)
    }

    @Test
    fun `ending a lift without an edit asks for another save when pixels are waiting`() {
        stroke(fromX = 20f, toX = 80f, y = 50f)
        val atStroke = state.revision
        session.liftSelection(box)
        await()
        assertEquals(atStroke, state.revision)
        session.cancelSelection()
        await()
        assertTrue(state.revision > atStroke)
    }

    @Test
    fun `ending a lift with nothing waiting does not ask for a save`() {
        stroke(fromX = 20f, toX = 80f, y = 50f)
        snapshot()
        val saved = state.revision
        session.liftSelection(box)
        session.cancelSelection()
        await()
        assertEquals(saved, state.revision)
    }

    // ----- Shrinking the canvas must never crop a drawing -----

    /** A stroke that shrinking to 100 wide puts outside the surface, then a shrink, leaving the art at x -45..-35. */
    private fun drawThenShrink() {
        stroke(fromX = 5f, toX = 15f, y = 50f)
        session.resizeCanvas(100, 100)
        await()
    }

    private fun growBack() {
        session.resizeCanvas(200, 100)
        await()
    }

    @Test
    fun `an edit after shrinking keeps what lies outside the surface`() {
        drawThenShrink()
        stroke(fromX = 40f, toX = 60f, y = 50f)
        snapshot()
        growBack()
        assertTrue("the art past the edge must survive", isInked(10, 50))
        assertTrue("the new stroke must survive", isInked(100, 50))
    }

    @Test
    fun `erasing everything visible after shrinking keeps what lies outside the surface`() {
        drawThenShrink()
        stroke(spec = BrushPresets.ERASER, fromX = 40f, toX = 60f, y = 50f)
        snapshot()
        assertEquals("the off-surface art still needs its cel", 1, state.project.cels.size)
        growBack()
        assertTrue(isInked(10, 50))
    }

    @Test
    fun `undoing a stroke after shrinking keeps what lies outside the surface`() {
        drawThenShrink()
        stroke(fromX = 40f, toX = 60f, y = 50f)
        snapshot()
        session.undo()
        await()
        snapshot()
        growBack()
        assertTrue(isInked(10, 50))
        assertFalse(isInked(100, 50))
    }

    @Test
    fun `erasing the on-surface part of a straddling drawing stays erased`() {
        // After the shrink this lies at canvas x -50..50, and the surface starts at -12.
        stroke(fromX = 0f, toX = 100f, y = 50f, spec = pen)
        session.resizeCanvas(100, 100)
        await()
        val big = BrushPresets.ERASER.copy(sizePx = 60f, sizeCurve = PressureCurve.FLAT, opacity = 1f, flow = 1f, hardness = 1f)
        stroke(spec = big, fromX = -20f, toX = 120f, y = 50f)
        snapshot()
        growBack()
        assertTrue("the part past the surface survives", isInked(10, 50))
        assertFalse("the erased part stays erased", isInked(70, 50))
    }

    @Test
    fun `a cel with no overhang is still cropped to the surface content`() {
        stroke(fromX = 20f, toX = 40f, y = 50f)
        val bounds = snapshot()
            .cels.values
            .single()
            .bounds ?: error("no bounds")
        assertTrue(bounds.width < 50)
    }

    @Test
    fun `an overhanging cel is cropped tightly after being merged`() {
        drawThenShrink()
        stroke(fromX = 40f, toX = 60f, y = 50f)
        val bounds = snapshot()
            .cels.values
            .single()
            .bounds ?: error("no bounds")
        assertTrue("left edge is the old art at about -50", bounds.left in -55..-40)
        assertTrue("right edge is the new stroke", bounds.right in 55..75)
        assertTrue("height is tight, not the whole canvas", bounds.height < 30)
    }

    // ----- Running out of memory must never lose a drawing -----

    private lateinit var flaky: FlakyCelStore

    /** A session whose store runs out of memory on request, with a drawing on its first frame and an empty second frame. */
    private fun flakySessionWithTwoFrames(): Pair<String, String> {
        session.close()
        flaky = FlakyCelStore()
        val project = newProject("Test", 200, 100, 12, Background.Transparent, "Layer 1", now = 0)
        session = DrawingSession(project, flaky, MemoryBudget.forMemoryClass(256, isLowRam = false), temp.newFolder())
        await()
        val first = state.frameId
        stroke(y = 30f)
        session.addFrame()
        await()
        stroke(y = 70f)
        snapshot()
        val second = state.frameId
        session.selectFrame(first)
        await()
        return first to second
    }

    @Test
    fun `a failed frame load refuses strokes rather than overwriting either frame`() {
        val (first, second) = flakySessionWithTwoFrames()
        val cels = state.project.cels
        flaky.readsOutOfMemory.set(LOTS)
        session.selectFrame(second)
        await()
        assertEquals(second, state.frameId)
        assertNull("a surface that failed to load must not be shown", session.scene?.active)
        stroke(y = 50f)
        assertEquals("neither cel may change", cels, snapshot().cels)
        assertEquals(first, state.project.frames[0].id)
        flaky.readsOutOfMemory.set(0)
        session.selectFrame(second)
        await()
        assertTrue("the drawing is still there once it can be read", isInked(50, 70))
        assertFalse("the refused stroke left nothing behind", isInked(50, 50))
    }

    @Test
    fun `a stroke refused after a failed load says why`() {
        val (_, second) = flakySessionWithTwoFrames()
        flaky.readsOutOfMemory.set(LOTS)
        session.selectFrame(second)
        await()
        stroke(y = 50f)
        assertEventuallySent(SessionEvent.CelNotLoaded)
        flaky.readsOutOfMemory.set(0)
    }

    @Test
    fun `a frame that cannot be loaded is mentioned right away, even before a stroke is tried`() {
        val (_, second) = flakySessionWithTwoFrames()
        flaky.readsReturningNull.set(LOTS)
        session.selectFrame(second)
        await()
        assertEventuallySent(SessionEvent.CelNotLoaded)
        flaky.readsReturningNull.set(0)
    }

    @Test
    fun `tapping the frame again after a failed load brings it back`() {
        val (_, second) = flakySessionWithTwoFrames()
        flaky.readsOutOfMemory.set(2)
        session.selectFrame(second)
        await()
        session.selectFrame(second)
        await()
        assertNotNull(session.scene?.active)
        assertTrue(isInked(50, 70))
        stroke(y = 50f)
        assertTrue(isInked(50, 50))
        assertTrue(isInked(50, 70))
    }

    @Test
    fun `the next stroke itself retries a failed load`() {
        val (_, second) = flakySessionWithTwoFrames()
        flaky.readsOutOfMemory.set(2)
        session.selectFrame(second)
        await()
        stroke(y = 50f)
        assertTrue(isInked(50, 50))
        assertTrue(isInked(50, 70))
    }

    @Test
    fun `a single out-of-memory on load is retried at once`() {
        val (_, second) = flakySessionWithTwoFrames()
        flaky.readsOutOfMemory.set(1)
        session.selectFrame(second)
        await()
        assertTrue(isInked(50, 70))
    }

    @Test
    fun `a drawing that reads as nothing is not mistaken for an empty cel`() {
        val (_, second) = flakySessionWithTwoFrames()
        val cels = state.project.cels
        flaky.readsReturningNull.set(2)
        session.selectFrame(second)
        await()
        flaky.readsReturningNull.set(LOTS)
        stroke(y = 50f)
        assertEquals(cels, snapshot().cels)
        flaky.readsReturningNull.set(0)
    }

    @Test
    fun `clearing a cel that cannot be read makes it usable again`() {
        val (_, second) = flakySessionWithTwoFrames()
        flaky.readsReturningNull.set(2)
        session.selectFrame(second)
        await()
        session.clearActiveCel()
        await()
        stroke(y = 50f)
        assertTrue(isInked(50, 50))
        assertFalse(isInked(50, 70))
    }

    @Test
    fun `undo into a frame that cannot be loaded keeps the step for later`() {
        val (first, second) = flakySessionWithTwoFrames()
        session.selectFrame(second)
        await()
        session.selectFrame(first)
        await()
        flaky.readsOutOfMemory.set(LOTS)
        session.undo() // The stroke on the second frame.
        await()
        assertEquals(second, state.frameId)
        assertFalse("the step did not happen", state.canRedo)
        flaky.readsOutOfMemory.set(0)
        session.undo()
        await()
        assertEquals(second, state.frameId)
        assertFalse("the step applies once the frame loads", isInked(50, 70))
        assertTrue(state.canRedo)
    }

    @Test
    fun `running out of memory while saving keeps the stroke for the next try`() {
        val (_, second) = flakySessionWithTwoFrames()
        session.selectFrame(second)
        await()
        stroke(y = 50f)
        flaky.writesOutOfMemory.set(1)
        assertThrows(IOException::class.java) { snapshot() }
        val saved = savedFrame(snapshot(), second, flaky)
        assertEquals("the retry must write the stroke", 255, Color.alpha(saved.getPixel(50, 50)))
        assertEquals(255, Color.alpha(saved.getPixel(50, 70)))
    }

    @Test
    fun `running out of memory while leaving a frame stays on it with the stroke intact`() {
        val (first, second) = flakySessionWithTwoFrames()
        stroke(y = 80f)
        flaky.writesOutOfMemory.set(1)
        session.selectFrame(second)
        await()
        assertEquals("the move must not happen", first, state.frameId)
        assertTrue(isInked(50, 80))
        session.selectFrame(second)
        await()
        assertEquals(second, state.frameId)
        val saved = savedFrame(snapshot(), first, flaky)
        assertEquals(255, Color.alpha(saved.getPixel(50, 80)))
    }

    @Test
    fun `lifted pixels are not lost when saving them runs out of memory`() {
        session.close()
        flaky = FlakyCelStore()
        val project = newProject("Test", 200, 100, 12, Background.Transparent, "Layer 1", now = 0)
        session = DrawingSession(project, flaky, MemoryBudget.forMemoryClass(256, false), temp.newFolder())
        await()
        stroke(fromX = 20f, toX = 80f, y = 50f)
        session.liftSelection(box)
        await()
        flaky.writesOutOfMemory.set(1)
        assertThrows(IOException::class.java) { runBlocking { session.snapshot(commitSelection = true) } }
        val saved = savedFrame(runBlocking { session.snapshot(commitSelection = true) }, state.frameId, flaky)
        assertEquals("the lifted pixels are back in the saved drawing", 255, Color.alpha(saved.getPixel(50, 50)))
    }

    @Test
    fun `other layers that do not fit in memory only drop out of the view`() {
        val (_, _) = flakySessionWithTwoFrames()
        session.addLayer("Top")
        await()
        val top = state.layerId
        val bottom = state.project.layers
            .first()
            .id
        flaky.readsOutOfMemory.set(2)
        session.selectLayer(top)
        session.selectLayer(bottom)
        await()
        session.selectLayer(top)
        await()
        assertEquals(top, state.layerId)
        assertTrue(state.project.cels.isNotEmpty())
    }

    private fun assertEventuallySent(expected: SessionEvent) {
        val found = runBlocking { withTimeout(5000) { session.events.first { it == expected } } }
        assertEquals(expected, found)
    }

    // ----- A margin that fell back to nothing -----

    private fun marginlessSession(failing: () -> Boolean): DrawingSession {
        session.close()
        val project = newProject("Test", 200, 100, 12, Background.Transparent, "Layer 1", now = 0)
        session = DrawingSession(project, store, MemoryBudget.forMemoryClass(256, isLowRam = false), temp.newFolder()) { width, height ->
            // The canvas alone always fits. Anything more only fits when the test says it does.
            if ((width > 200 || height > 100) && failing()) throw OutOfMemoryError("Test")
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        await()
        return session
    }

    @Test
    fun `a session that fell back to no margin does not reallocate on every edit`() {
        var allocationsAllowed = false
        marginlessSession { !allocationsAllowed }
        val surface = session.scene?.active?.bitmap
        assertEquals(200, surface?.width)
        stroke()
        allocationsAllowed = true
        session.addFrame()
        await()
        session.undo()
        await()
        assertEquals(
            200,
            session.scene
                ?.active
                ?.bitmap
                ?.width,
        )
        session.undo()
        await()
        assertFalse("undo must put the patch back where it came from", isInked(50, 50))
    }

    @Test
    fun `a patch recorded with no margin still lands correctly on a surface with one`() {
        var allocationsAllowed = false
        marginlessSession { !allocationsAllowed }
        stroke()
        allocationsAllowed = true
        session.resizeCanvas(400, 200)
        await()
        session.undo() // The resize. The surface is allocated afresh, this time with its margin.
        await()
        assertTrue(
            "the surface should have a margin now",
            (
                session.scene
                    ?.active
                    ?.bitmap
                    ?.width ?: 0
            ) > 200,
        )
        assertTrue(isInked(50, 50))
        session.undo()
        await()
        assertFalse("the stroke must come off exactly", isInked(50, 50))
        session.redo()
        await()
        assertTrue(isInked(50, 50))
    }

    @Test
    fun `a canvas too large for memory after a resize refuses edits until it fits`() {
        var allocationsAllowed = true
        marginlessSession { !allocationsAllowed }
        stroke()
        allocationsAllowed = false
        session.resizeCanvas(400, 200)
        await()
        val cels = snapshot().cels
        assertEquals(400, state.project.width)
        stroke(y = 20f)
        session.fill(5, 5, Color.RED, 0, 0, false)
        await()
        assertEquals("nothing may be painted on a surface of the wrong size", cels, snapshot().cels)
        allocationsAllowed = true
        stroke(fromX = 200f, toX = 260f, y = 20f)
        assertTrue("the old art came back, moved with the resize", isInked(150, 100))
        assertTrue(isInked(230, 20))
    }

    // ----- Not enough memory to open at all -----

    private fun sessionThatCannotOpen(fits: () -> Boolean): DrawingSession {
        session.close()
        val project = newProject("Test", 200, 100, 12, Background.Transparent, "Layer 1", now = 0)
        session = DrawingSession(project, store, MemoryBudget.forMemoryClass(256, isLowRam = false), temp.newFolder()) { width, height ->
            if (!fits()) throw OutOfMemoryError("Test")
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        await()
        return session
    }

    @Test
    fun `a canvas that does not fit reports it as state that cannot be missed`() {
        sessionThatCannotOpen { false }
        assertTrue(state.openFailed)
        assertFalse(state.ready)
    }

    @Test
    fun `a normal open never reports a failure`() {
        assertFalse(state.openFailed)
        assertTrue(state.ready)
    }

    @Test
    fun `trying again succeeds once there is room`() {
        var fits = false
        sessionThatCannotOpen { fits }
        session.retryOpen()
        await()
        assertTrue("still no room", state.openFailed)
        fits = true
        session.retryOpen()
        await()
        assertFalse(state.openFailed)
        assertTrue(state.ready)
        assertEquals(200, state.project.width)
        stroke()
        assertTrue(isInked(50, 50))
    }

    @Test
    fun `opening at a smaller size keeps drawings centred and adds no undo step`() {
        var fits = false
        session.close()
        val base = newProject("Test", 200, 100, 12, Background.Transparent, "Layer 1", now = 0)
        val key = org.kineograph.model.CelKey(base.layers.single().id, base.frames.single().id)
        val image = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(black) }
        val file = store.write(image)
        val cel = org.kineograph.model.Cel(key.layerId, key.frameId, file, org.kineograph.model.IntRect(95, 45, 105, 55))
        session =
            DrawingSession(base.copy(cels = mapOf(key to cel)), store, MemoryBudget.forMemoryClass(256, false), temp.newFolder()) { w, h ->
                if (!fits) throw OutOfMemoryError("Test")
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            }
        await()
        fits = true
        val revision = state.revision
        session.retryOpen(100, 50)
        await()
        assertTrue(state.ready)
        assertEquals(100, state.project.width)
        assertEquals(50, state.project.height)
        assertFalse("undo must not go back to a size that cannot open", state.canUndo)
        assertTrue("the new size has to be saved", state.revision > revision)
        assertTrue(isInked(50, 25))
    }

    // ----- Strokes stay where they started -----

    private fun beginStrokeAt(x: Float, y: Float): StampBuffer {
        val engine = StrokeEngine(pen, 7L)
        val out = StampBuffer()
        engine.begin(x, y, 1f, 0f, 0f, out)
        engine.add(x + 30f, y, 1f, 0f, 0f, out)
        engine.finish(out)
        session.beginStroke(pen, black)
        return out
    }

    @Test
    fun `a second finger on the frame strip cannot move a stroke to another frame`() {
        session.addFrame()
        await()
        val second = state.frameId
        val first = state.project.frames
            .first()
            .id
        val out = beginStrokeAt(20f, 50f)
        session.selectFrame(first)
        session.addStamps(out.snapshot())
        session.endStroke { }
        await()
        assertEquals(second, state.frameId)
        val project = snapshot()
        assertNotNull(project.cel(state.layerId, second))
        assertNull(project.cel(state.layerId, first))
    }

    @Test
    fun `structure edits and layer changes are ignored while a stroke is under way`() {
        session.addLayer("Top")
        await()
        val frames = state.project.frames.size
        val layers = state.project.layers.size
        val active = state.layerId
        val out = beginStrokeAt(20f, 50f)
        session.addFrame()
        session.selectLayer(
            state.project.layers
                .first()
                .id,
        )
        session.deleteLayer(active)
        session.mergeLayerDown(active)
        session.resizeCanvas(50, 50)
        session.clearActiveCel()
        session.addStamps(out.snapshot())
        session.endStroke { }
        await()
        assertEquals(frames, state.project.frames.size)
        assertEquals(layers, state.project.layers.size)
        assertEquals(active, state.layerId)
        assertEquals(200, state.project.width)
        assertTrue(isInked(35, 50))
    }

    @Test
    fun `an opacity drag can still finish during a stroke`() {
        val id = state.layerId
        session.previewLayerOpacity(id, 0.5f)
        val out = beginStrokeAt(20f, 50f)
        session.commitLayerOpacity(id, 0.5f)
        session.addStamps(out.snapshot())
        session.endStroke { }
        await()
        assertEquals(0.5f, state.project.layer(id)?.opacity ?: 0f, 0f)
        session.undo()
        await()
        assertEquals(0.5f, state.project.layer(id)?.opacity ?: 0f, 0f)
        session.undo()
        await()
        assertEquals(1f, state.project.layer(id)?.opacity ?: 0f, 0f)
    }

    // ----- Clearing respects locks -----

    @Test
    fun `clearing a locked or hidden active layer does nothing but say so`() {
        stroke()
        val before = snapshot().cels
        session.setLayerLocked(state.layerId, true)
        await()
        val revision = state.revision
        session.clearActiveCel()
        await()
        assertEquals(before, snapshot().cels)
        assertEquals("no undo step was added", revision, state.revision)
        assertEventuallySent(SessionEvent.LayerNotEditable)

        session.setLayerLocked(state.layerId, false)
        session.setLayerVisible(state.layerId, false)
        await()
        session.clearActiveCel()
        await()
        assertEquals(before, snapshot().cels)
        assertEventuallySent(SessionEvent.LayerNotEditable)
    }

    @Test
    fun `a selection put down by locking its layer is safe from a clear`() {
        stroke(fromX = 20f, toX = 80f, y = 50f)
        session.liftSelection(box)
        await()
        // Locking is itself an edit, so it puts the selection down first, and the art must then be safe from the clear.
        assertNotNull(state.selection)
        session.setLayerLocked(state.layerId, true)
        await()
        assertNull(state.selection)
        session.clearActiveCel()
        await()
        assertTrue(isInked(50, 50))
    }

    @Test
    fun `clearing a frame keeps locked and hidden layers and clears the rest`() {
        val bottom = state.layerId
        stroke(y = 20f)
        session.addLayer("Middle")
        await()
        val middle = state.layerId
        stroke(y = 50f)
        session.addLayer("Top")
        await()
        val top = state.layerId
        stroke(y = 80f)
        session.setLayerLocked(bottom, true)
        session.setLayerVisible(top, false)
        session.selectLayer(middle)
        await()
        session.clearFrame(state.frameId)
        await()
        val project = snapshot()
        assertNotNull(project.cel(bottom, state.frameId))
        assertNull(project.cel(middle, state.frameId))
        assertNotNull(project.cel(top, state.frameId))
        assertEventuallySent(SessionEvent.LayerNotEditable)
    }

    @Test
    fun `deleting the only frame clears it and so respects locks too`() {
        stroke()
        session.setLayerLocked(state.layerId, true)
        await()
        session.deleteFrame(state.frameId)
        await()
        assertEquals(1, snapshot().cels.size)
        assertEquals(1, state.project.frames.size)
    }

    // ----- Merging keeps how things look -----

    @Test
    fun `layers of different visibility cannot be merged`() {
        stroke(y = 30f)
        session.addLayer("Top")
        await()
        stroke(y = 70f)
        val top = state.layerId
        val bottom = state.project.layers
            .first()
            .id
        session.setLayerVisible(top, false)
        session.mergeLayerDown(top)
        await()
        assertEquals("hidden over visible", 2, state.project.layers.size)
        session.setLayerVisible(top, true)
        session.setLayerVisible(bottom, false)
        session.mergeLayerDown(top)
        await()
        assertEquals("visible over hidden", 2, state.project.layers.size)
        assertFalse(isInked(50, 30))
        assertTrue(isInked(50, 70))
    }

    @Test
    fun `two hidden layers can still be merged`() {
        stroke(y = 30f)
        session.addLayer("Top")
        await()
        stroke(y = 70f)
        val top = state.layerId
        val bottom = state.project.layers
            .first()
            .id
        session.setLayerVisible(top, false)
        session.setLayerVisible(bottom, false)
        session.mergeLayerDown(top)
        await()
        assertEquals(1, state.project.layers.size)
    }

    // ----- Undo and redo across frames, and patches the system deleted -----

    @Test
    fun `undo that jumps to another frame replaces the ghosts it showed`() {
        val first = state.frameId
        stroke(y = 30f)
        session.addFrame()
        session.updateSettings { it.copy(onionSkin = it.onionSkin.copy(enabled = true, opacity = 1f, tint = false)) }
        await()
        waitForGhosts()
        assertEquals("the first frame shows as a ghost", 1, session.scene?.ghosts?.size)
        val structure = state.frameId
        session.selectFrame(first)
        await()
        session.selectFrame(structure)
        await()
        waitForGhosts()
        session.undo() // Adding the frame.
        await()
        assertEquals(first, state.frameId)
        waitForNoGhosts()
        assertTrue("the first frame has no frame before it", session.scene?.ghosts?.isEmpty() == true)
    }

    @Test
    fun `pixel undo that jumps to another frame replaces the ghosts it showed`() {
        session.addFrame()
        await()
        val second = state.frameId
        session.selectFrame(
            state.project.frames
                .first()
                .id,
        )
        await()
        val first = state.frameId
        stroke(y = 30f)
        session.selectFrame(second)
        session.updateSettings { it.copy(onionSkin = it.onionSkin.copy(enabled = true, opacity = 1f, tint = false)) }
        await()
        waitForGhosts()
        assertEquals(1, session.scene?.ghosts?.size)
        session.undo()
        await()
        assertEquals(first, state.frameId)
        waitForNoGhosts()
        assertTrue(session.scene?.ghosts?.isEmpty() == true)
        session.redo()
        await()
        assertEquals(first, state.frameId)
        assertTrue(isInked(50, 30))
    }

    private fun waitForNoGhosts() {
        val deadline = System.currentTimeMillis() + 5000
        while (session.scene?.ghosts?.isNotEmpty() == true && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            await()
        }
    }

    private fun sessionWithSpillFolder(): java.io.File {
        session.close()
        val spill = temp.newFolder()
        val project = newProject("Test", 200, 100, 12, Background.Transparent, "Layer 1", now = 0)
        session = DrawingSession(project, store, MemoryBudget.forMemoryClass(256, false), spill)
        await()
        return spill
    }

    @Test
    fun `undo copes with the system having deleted spilled patches`() {
        val spill = sessionWithSpillFolder()
        stroke(y = 30f)
        stroke(y = 70f)
        session.trimMemory()
        await()
        assertTrue("patches must have been spilled", spill.listFiles().orEmpty().isNotEmpty())
        spill.deleteRecursively()
        session.undo()
        await()
        assertFalse("nothing is left to undo", state.canUndo)
        assertFalse(state.canRedo)
        assertTrue("the drawing stays as it was", isInked(50, 30))
        assertTrue(isInked(50, 70))
        assertEventuallySent(SessionEvent.UndoStepsLost)
        stroke(y = 50f)
        assertTrue("the session carries on", isInked(50, 50))
        session.undo()
        await()
        assertFalse(isInked(50, 50))
    }

    @Test
    fun `redo copes with the system having deleted spilled patches`() {
        val spill = sessionWithSpillFolder()
        stroke(y = 30f)
        stroke(y = 70f)
        session.undo()
        await()
        session.trimMemory()
        await()
        spill.deleteRecursively()
        session.redo()
        await()
        assertFalse(state.canRedo)
        assertFalse(isInked(50, 70))
        assertTrue(isInked(50, 30))
        assertEventuallySent(SessionEvent.UndoStepsLost)
    }

    @Test
    fun `messages from before anyone is listening still arrive`() {
        stroke()
        session.setLayerLocked(state.layerId, true)
        session.clearActiveCel()
        await()
        // Only now does anything collect.
        assertEventuallySent(SessionEvent.LayerNotEditable)
    }

    // ----- A lifted selection looks as it will once put down -----

    @Test
    fun `a selection on a translucent layer is previewed at that layer's opacity`() {
        val id = state.layerId
        session.commitLayerOpacity(id, 0.3f)
        stroke(fromX = 20f, toX = 80f, y = 50f)
        session.liftSelection(box)
        await()
        val selection = state.selection ?: error("nothing lifted")
        session.setSelectionTransform(selection.dragged(SelectionHandle.MOVE, 50f, 50f, 120f, 50f))
        await()
        val floating = render().getPixel(120, 50)
        session.commitSelection()
        await()
        val placed = render().getPixel(120, 50)
        assertColorsClose(placed, floating)
    }

    @Test
    fun `a selection under a layer above stays under it`() {
        stroke(fromX = 20f, toX = 80f, y = 50f, color = Color.RED)
        val lower = state.layerId
        session.addLayer("Top")
        await()
        stroke(fromX = 20f, toX = 80f, y = 50f, color = Color.BLUE)
        session.selectLayer(lower)
        await()
        session.liftSelection(box)
        await()
        val floating = render().getPixel(50, 50)
        assertEquals("the layer above covers it", Color.BLUE, floating)
        session.commitSelection()
        await()
        assertEquals(floating, render().getPixel(50, 50))
    }

    @Test
    fun `a selection past the canvas edge shows faintly like everything else there`() {
        session.resizeCanvas(100, 100)
        await()
        stroke(fromX = 20f, toX = 80f, y = 50f)
        session.liftSelection(floatArrayOf(10f, 30f, 90f, 30f, 90f, 70f, 10f, 70f))
        await()
        val selection = state.selection ?: error("nothing lifted")
        session.setSelectionTransform(selection.dragged(SelectionHandle.MOVE, 50f, 50f, 130f, 50f))
        await()
        val scene = session.scene ?: error("no scene")
        val out = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        SceneRenderer().draw(Canvas(out), scene, Affine.IDENTITY, Color.WHITE)
        val outside = out.getPixel(130, 50)
        assertTrue("visible past the edge, ${Integer.toHexString(outside)}", Color.red(outside) in 120..250)
    }

    private fun assertColorsClose(expected: Int, actual: Int) {
        val message = "${Integer.toHexString(expected)} vs ${Integer.toHexString(actual)}"
        assertTrue(message, kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= 3)
        assertTrue(message, kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= 3)
        assertTrue(message, kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= 3)
    }

    @Test
    fun `the listener hears about scene changes`() {
        var calls = 0
        session.sceneListener = { calls++ }
        session.addFrame()
        await()
        assertTrue(calls > 0)
    }

    @Test
    fun `the reveal image shows everything except the active layer`() {
        stroke(color = Color.RED, y = 50f)
        session.addLayer("Top")
        await()
        stroke(color = Color.BLUE, y = 50f)
        session.wantsRevealImage = true
        session.workspaceColor = Color.GRAY
        val eraser = BrushPresets.ERASER
        session.beginStroke(eraser, black)
        await()
        val reveal = session.revealImage ?: error("no reveal image")
        val margin = (reveal.width - 200) / 2
        assertEquals(Color.RED, reveal.getPixel(50 + margin, 50 + margin))
        session.cancelStroke()
        await()
    }

    private companion object {
        /** More failures in a row than any test needs, for "this keeps failing until the test says otherwise". */
        const val LOTS = 1000
    }
}
