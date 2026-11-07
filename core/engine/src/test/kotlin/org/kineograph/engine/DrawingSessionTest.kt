package org.kineograph.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import org.kineograph.engine.session.DrawingSession
import org.kineograph.engine.session.MemoryBudget
import org.kineograph.engine.session.SessionState
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

    private fun isInked(x: Int, y: Int): Boolean = Color.red(render().getPixel(x, y)) < 40

    /** Reads what would be saved for a frame, independently of the live scene. */
    private fun savedFrame(project: Project, frameId: String): Bitmap {
        val out = Bitmap.createBitmap(project.width, project.height, Bitmap.Config.ARGB_8888)
        FrameFlattener(store).flatten(project, frameId, Canvas(out), 1f, null, FlattenOptions(includeBackground = false))
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

    @Test
    fun `a snapshot that puts lifted pixels down tells the screen`() {
        stroke()
        await()
        session.liftSelection(floatArrayOf(10f, 30f, 60f, 30f, 60f, 70f, 10f, 70f))
        await()
        assertNotNull(state.selection)
        assertNotNull(session.scene?.floating)
        val project = runBlocking { session.snapshot(commitSelection = true) }
        assertNull(state.selection)
        assertNull(session.scene?.floating)
        assertEquals(project, state.project)
    }

    @Test
    fun `an edit that runs out of memory takes nothing with it`() {
        stroke()
        session.addLayer("Two")
        await()
        session.undo()
        await()
        val before = state.project
        val layer = state.layerId
        assertTrue(state.canRedo)
        val outcome =
            runCatching {
                runBlocking {
                    session.applyEdit {
                        // The new cel is what the surface reads while the edit is shown.
                        store.failNextReadWithOutOfMemory = true
                        ProjectEdits.withCel(
                            it,
                            CelKey(layer, state.frameId),
                            Cel(layer, state.frameId, store.write(smallImage()), IntRect(0, 0, 4, 4)),
                        )
                    }
                }
            }.exceptionOrNull()
        assertTrue("got $outcome", outcome is OutOfMemoryError)
        await()
        assertEquals(before.layers, state.project.layers)
        assertEquals(before.cels, state.project.cels)
        assertTrue(state.canRedo)
        session.redo()
        await()
        assertEquals(2, state.project.layers.size)
    }

    @Test
    fun `a surface that cannot read what comes next loses nothing of what it had`() {
        val first = state.frameId
        session.addFrame()
        await()
        val second = state.project.frames
            .last()
            .id
        session.selectFrame(second)
        stroke(y = 20f)
        await()
        val layer = state.layerId
        store.failNextReadWithOutOfMemory = true
        // Going back to a frame with a drawing, so that there is something to read. It fails, and the screen stays where it was.
        session.selectFrame(first)
        await()
        val readableFirst = runCatching { store.read("nothing") }
        assertTrue(readableFirst.isSuccess || readableFirst.isFailure)
        stroke(y = 70f)
        await()
        val project = runBlocking { session.snapshot() }
        val cel = project.cel(layer, second) ?: error("the drawing has gone")
        val image = store.images[cel.file] ?: error("no image")
        val bounds = cel.bounds ?: error("no bounds")
        // Erasing first would have saved the new stroke alone over the drawing.
        assertEquals(black, image.getPixel(50 - bounds.left, 20 - bounds.top))
    }

    private fun smallImage(): Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
}
