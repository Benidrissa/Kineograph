package org.kineograph.engine

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kineograph.engine.brush.BrushSpec
import org.kineograph.engine.brush.PressureCurve
import org.kineograph.engine.brush.StampBuffer
import org.kineograph.engine.brush.StrokeEngine
import org.kineograph.engine.session.DrawingSession
import org.kineograph.engine.session.MemoryBudget
import org.kineograph.engine.session.SessionState
import org.kineograph.model.Background
import org.kineograph.model.ImportEdits
import org.kineograph.model.IntRect
import org.kineograph.model.PlacedImage
import org.kineograph.model.Project
import org.kineograph.model.ProjectEdits
import org.kineograph.model.newProject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ApplyEditTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val store = FakeCelStore()
    private lateinit var session: DrawingSession

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

    private fun stroke() {
        val spec = BrushSpec(id = "test", sizePx = 10f, spacing = 0.1f, sizeCurve = PressureCurve.FLAT)
        val engine = StrokeEngine(spec, 7L)
        val out = StampBuffer()
        engine.begin(20f, 50f, 1f, 0f, 0f, out)
        var x = 20f
        while (x < 80f) {
            x += 5f
            engine.add(x, 50f, 1f, 0f, 0f, out)
        }
        engine.finish(out)
        session.beginStroke(spec, Color.BLACK)
        session.addStamps(out.snapshot())
        session.endStroke { }
        await()
    }

    private fun addTwoFrames(project: Project): Project = ProjectEdits.addFrameAfter(
        ProjectEdits.addFrameAfter(project, null, "X"),
        null,
        "Y",
    )

    @Test
    fun `an edit is one undo step, and redo brings it back`() {
        val before = state.project
        runBlocking { session.applyEdit { addTwoFrames(it) } }
        assertEquals(3, state.project.frames.size)
        assertTrue(state.canUndo)
        session.undo()
        await()
        assertEquals(before.frames, state.project.frames)
        assertFalse(state.canUndo)
        session.redo()
        await()
        assertEquals(3, state.project.frames.size)
    }

    @Test
    fun `pending pixels are saved before the edit sees the project`() {
        stroke()
        var seen: Project? = null
        runBlocking { session.applyEdit { project -> project.also { seen = it } } }
        val project = seen ?: error("the edit never ran")
        assertNotNull(project.cel(state.layerId, state.frameId))
    }

    @Test
    fun `undoing an edit made after a stroke keeps the stroke`() {
        stroke()
        val layer = state.layerId
        val frame = state.frameId
        runBlocking { session.applyEdit { addTwoFrames(it) } }
        session.undo()
        await()
        assertEquals(1, state.project.frames.size)
        val stored = runBlocking { session.snapshot() }.cel(layer, frame)
        assertNotNull(stored)
    }

    @Test
    fun `an edit can make a layer it just added the active one`() {
        val first = state.layerId
        runBlocking {
            session.applyEdit(selectLayerId = "NEW") { ImportEdits.addImageLayer(it, first, state.frameId, "New", image(), "NEW") }
        }
        assertEquals("NEW", state.layerId)
        session.undo()
        await()
        assertEquals(first, state.layerId)
    }

    @Test
    fun `a layer that is not in the result stays unselected`() {
        val first = state.layerId
        runBlocking { session.applyEdit(selectLayerId = "missing") { addTwoFrames(it) } }
        assertEquals(first, state.layerId)
    }

    @Test
    fun `a changed active cel is loaded back on to the surface`() {
        val frame = state.frameId
        val layer = state.layerId
        val picture = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        picture.eraseColor(Color.RED)
        val file = store.write(picture)
        runBlocking { session.applyEdit { ImportEdits.replaceCel(it, layer, frame, PlacedImage(file, IntRect(5, 5, 15, 15))) } }
        val active = session.scene?.active ?: error("nothing on the surface")
        assertEquals(Color.RED, active.bitmap.getPixel(10 - active.left, 10 - active.top))
    }

    @Test
    fun `whatever an edit throws goes back to the caller and changes nothing`() {
        val before = state.project
        runBlocking {
            try {
                session.applyEdit { throw OutOfMemoryError("too much") }
                fail("the error was swallowed")
            } catch (_: OutOfMemoryError) {
                // This is what the caller needs to hear.
            }
        }
        assertEquals(before, state.project)
        assertFalse(state.canUndo)
    }

    private fun image() = PlacedImage("cels/x.webp", IntRect(0, 0, 1, 1))
}
