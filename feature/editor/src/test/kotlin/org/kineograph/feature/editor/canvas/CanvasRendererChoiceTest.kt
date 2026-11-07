package org.kineograph.feature.editor.canvas

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kineograph.engine.ViewTransform
import org.kineograph.engine.brush.BrushPresets
import org.kineograph.engine.brush.BrushSpec
import org.kineograph.engine.brush.PressureCurve
import org.kineograph.engine.brush.StampBuffer
import org.kineograph.engine.brush.StampRun
import org.kineograph.engine.selection.Affine
import org.kineograph.engine.session.DrawingSession
import org.kineograph.engine.session.MemoryBudget
import org.kineograph.feature.editor.TestStore
import org.kineograph.model.Background
import org.kineograph.model.newProject
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

/** How the view looks after its renderer, with a stand-in for the low-latency one, whose library needs a real device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], qualifiers = "mdpi")
class CanvasRendererChoiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private class FakeLowLatency(private val session: DrawingSession, val initialView: Affine, val onFailure: () -> Unit) : CanvasRenderer {
        var shownView: Affine? = null
        var shownFrame: Bitmap? = null
        var redraws = 0
        var released = false
        var commits = 0
        var workspaceColor: Int? = null

        override val supportsHardwareBitmaps: Boolean get() = true

        override fun setWorkspaceColor(color: Int) {
            workspaceColor = color
        }

        override fun setView(view: Affine) {
            shownView = view
        }

        override fun setPlaybackFrame(frame: Bitmap?) {
            shownFrame = frame
        }

        override fun requestRedraw() {
            redraws++
        }

        override fun beginWet(spec: BrushSpec, colorArgb: Int, layerOpacity: Float) {
            assertFalse(released)
            session.beginStroke(spec, colorArgb)
        }

        override fun drawWet(real: StampRun, predicted: StampRun) {
            assertFalse(released)
            session.addStamps(real)
        }

        override fun commitWet() {
            assertFalse(released)
            commits++
            session.endStroke { }
        }

        override fun cancelWet() {
            assertFalse(released)
            session.cancelStroke()
        }

        override fun release() {
            assertFalse("released twice", released)
            released = true
        }
    }

    private lateinit var session: DrawingSession
    private lateinit var view: KineographCanvasView
    private lateinit var surface: SurfaceView
    private val store = TestStore()
    private val built = ArrayList<FakeLowLatency>()
    private var failure: Throwable? = null
    private var clock = 1000L

    @Before
    fun setUp() {
        session =
            DrawingSession(
                newProject("Test", VIEW_W, VIEW_H, 12, Background.Transparent, "Layer 1", now = 0),
                store,
                MemoryBudget.forMemoryClass(256, false),
                temp.newFolder(),
            )
        await()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        view =
            KineographCanvasView(
                activity,
                session,
                lowLatency = true,
                workspaceColor = Color.GRAY,
                toolConfig = {
                    CanvasToolConfig(
                        tool = CanvasTool.BRUSH,
                        brush = BrushPresets.PEN.copy(sizePx = 8f, sizeCurve = PressureCurve.FLAT),
                        eraser = BrushPresets.ERASER,
                        colorArgb = Color.BLACK,
                        smoothing = 0,
                        simulateFingerPressure = false,
                        fillColorArgb = Color.RED,
                        fillTolerance = 32,
                        fillGapClose = 0,
                        fillSampleAllLayers = false,
                    )
                },
                listener =
                    object : CanvasListener {
                        override fun onLayerNotDrawable() = Unit

                        override fun onPlaybackTapped() = Unit
                    },
                lowLatencyFactory = { _, initialView, onFailure ->
                    failure?.let { throw it }
                    FakeLowLatency(session, initialView, onFailure).also { built += it }
                },
            )
        activity.setContentView(view, FrameLayout.LayoutParams(VIEW_W, VIEW_H))
        view.measure(
            View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEW_H, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, VIEW_W, VIEW_H)
        surface = view.getChildAt(0) as SurfaceView
        idle()
    }

    @After
    fun tearDown() {
        session.close()
    }

    private fun await() = runBlocking { session.awaitIdle() }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        await()
    }

    private val holder get() = shadowOf(surface).fakeSurfaceHolder

    private fun surfaceEvents(): List<SurfaceHolder.Callback> = holder.callbacks.toList()

    private fun surfaceCreated() = surfaceEvents().forEach { it.surfaceCreated(holder) }

    private fun surfaceChanged(width: Int, height: Int) = surfaceEvents().forEach { it.surfaceChanged(holder, 1, width, height) }

    private fun surfaceDestroyed() = surfaceEvents().forEach { it.surfaceDestroyed(holder) }

    private fun touch(action: Int, x: Float, y: Float, downTime: Long): MotionEvent =
        MotionEvent.obtain(downTime, clock, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }

    private fun send(event: MotionEvent) {
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun penDown(): Long {
        val down = clock
        send(touch(MotionEvent.ACTION_DOWN, 100f, 100f, down))
        clock += 8
        send(touch(MotionEvent.ACTION_MOVE, 150f, 100f, down))
        return down
    }

    private fun penUp(down: Long) {
        clock += 8
        send(touch(MotionEvent.ACTION_UP, 150f, 100f, down))
        clock += 2000
        idle()
    }

    private fun resize(width: Int, height: Int) {
        session.resizeCanvas(width, height)
        idle()
        view.onCanvasSizeChanged()
    }

    @Test
    fun `the renderer lives as long as the surface`() {
        assertNull("there is nothing to draw on yet", view.renderer)
        surfaceCreated()
        val first = built.single()
        assertSame(first, view.renderer)
        assertTrue(first.redraws > 0)

        surfaceDestroyed()
        assertTrue(first.released)
        assertNull(view.renderer)

        surfaceCreated()
        assertEquals("each surface gets a fresh renderer", 2, built.size)
        assertFalse(built[1].released)
    }

    @Test
    fun `a new renderer starts with the view, so its first frame is already right`() {
        surfaceCreated()
        val fake = built.single()
        assertEquals(fake.initialView, fake.shownView)
        assertNotEquals(Affine.IDENTITY, fake.initialView)
    }

    @Test
    fun `the library's sign-ups for surface events are ignored, and only the view hears them`() {
        val before = surfaceEvents()
        val library =
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = Unit

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            }
        surface.holder.addCallback(library)
        assertEquals(before, surfaceEvents())
        surface.holder.removeCallback(before.single())
        assertEquals(before, surfaceEvents())
        assertEquals(1, before.size)
    }

    @Test
    fun `failing to set up low latency falls back to software, and it is not tried again`() {
        for (problem in listOf(RuntimeException("no buffers"), UnsatisfiedLinkError("no library"), OutOfMemoryError())) {
            setUpAgain()
            failure = problem
            surfaceCreated()
            assertTrue("after $problem", view.renderer is SoftwareRenderer)
            failure = null
            surfaceDestroyed()
            surfaceCreated()
            assertTrue("after $problem", view.renderer is SoftwareRenderer)
            assertTrue(built.isEmpty())
            surfaceDestroyed()
        }
    }

    private fun setUpAgain() {
        session.close()
        built.clear()
        setUp()
    }

    @Test
    fun `a failure while drawing moves to software at once`() {
        surfaceCreated()
        val fake = built.single()
        fake.onFailure()
        idle()
        assertTrue(fake.released)
        assertTrue(view.renderer is SoftwareRenderer)
    }

    @Test
    fun `a failure while drawing waits for the stroke under way, which still finishes`() {
        surfaceCreated()
        val fake = built.single()
        val down = penDown()
        fake.onFailure()
        idle()
        assertSame("a stroke must end on the renderer it began on", fake, view.renderer)
        penUp(down)
        assertEquals(1, fake.commits)
        assertTrue(fake.released)
        assertTrue(view.renderer is SoftwareRenderer)
    }

    @Test
    fun `a late failure from a renderer already replaced changes nothing`() {
        surfaceCreated()
        val old = built.single()
        surfaceDestroyed()
        surfaceCreated()
        old.onFailure()
        idle()
        assertSame(built[1], view.renderer)
    }

    @Test
    fun `growing the canvas past the hardware limit moves to software, and shrinking it moves back`() {
        surfaceCreated()
        val fake = built.single()
        resize(OVER_LIMIT_W, OVER_LIMIT_H)
        assertTrue(fake.released)
        assertTrue(view.renderer is SoftwareRenderer)

        session.undo()
        idle()
        view.onCanvasSizeChanged()
        assertEquals(2, built.size)
        assertSame(built[1], view.renderer)
    }

    @Test
    fun `a size change that stays under the limit keeps the renderer`() {
        surfaceCreated()
        val fake = built.single()
        resize(VIEW_W * 2, VIEW_H * 2)
        assertSame(fake, view.renderer)
        assertEquals("it still needs to hear about the new view", 1, built.size)
        assertTrue(fake.redraws > 1)
    }

    @Test
    fun `crossing the limit with no surface is sorted out when the surface comes`() {
        resize(OVER_LIMIT_W, OVER_LIMIT_H)
        assertNull(view.renderer)
        surfaceCreated()
        assertTrue(view.renderer is SoftwareRenderer)
        assertTrue(built.isEmpty())
    }

    @Test
    fun `a canvas longer than a GPU can be relied on for draws in software, as far as the memory goes`() {
        assertTrue(fitsHardwareCanvas(8000, 100))
        assertFalse("100 MB is nowhere near, but this is too long", fitsHardwareCanvas(9000, 100))
        assertFalse("the margin counts: its surface is over the limit", fitsHardwareCanvas(8192, 1000))
        resize(9000, 100)
        surfaceCreated()
        assertTrue(view.renderer is SoftwareRenderer)
        assertTrue(built.isEmpty())
    }

    @Test
    fun `the workspace colour follows the theme, on the renderer there is and any that comes after`() {
        surfaceCreated()
        val first = built.single()
        assertEquals(Color.GRAY, first.workspaceColor)
        view.setWorkspaceColor(Color.BLACK)
        assertEquals(Color.BLACK, first.workspaceColor)

        surfaceDestroyed()
        surfaceCreated()
        assertEquals("not back to the colour the view started with", Color.BLACK, built[1].workspaceColor)
    }

    @Test
    fun `setting the workspace colour it already has does nothing`() {
        surfaceCreated()
        val fake = built.single()
        val redraws = fake.redraws
        fake.workspaceColor = null
        view.setWorkspaceColor(Color.GRAY)
        assertNull(fake.workspaceColor)
        assertEquals(redraws, fake.redraws)
    }

    /** Pinches the canvas to twice the size, and moves it, which is what happens before someone presses Play. */
    private fun zoomAndPan() = twoFingers(200f, 300f)

    /** Two fingers go down at x = 100 and x = [from], and the second one moves to x = [to]. */
    private fun twoFingers(from: Float, to: Float) {
        val down = clock
        fun pointers(action: Int, first: Float, second: Float?): MotionEvent {
            val xs = listOfNotNull(first, second)
            val properties =
                Array(xs.size) { i ->
                    MotionEvent.PointerProperties().apply {
                        id = i
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    }
                }
            val coords = Array(xs.size) { i ->
                MotionEvent.PointerCoords().apply {
                    x = xs[i]
                    y = 100f
                    pressure = 1f
                    size = 1f
                }
            }
            return MotionEvent.obtain(
                down,
                clock,
                action,
                xs.size,
                properties,
                coords,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
        }
        send(pointers(MotionEvent.ACTION_DOWN, 100f, null))
        clock += 30
        send(pointers(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f, from))
        clock += 200
        send(pointers(MotionEvent.ACTION_MOVE, 100f, to))
        send(pointers(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f, to))
        send(pointers(MotionEvent.ACTION_UP, 100f, null))
        clock += 2000
        idle()
    }

    private fun wholeCanvasFit(width: Int = VIEW_W, height: Int = VIEW_H): Affine {
        val project = session.state.value.project
        return ViewTransform.fit(width, height, project.width, project.height, 0f).toAffine(project.width, project.height)
    }

    @Test
    fun `playback shows the whole canvas, and stopping goes back to where the user had it`() {
        surfaceCreated()
        val fake = built.single()
        zoomAndPan()
        val placed = checkNotNull(fake.shownView)
        assertNotEquals("the premise: the canvas was moved", wholeCanvasFit(), placed)

        val redraws = fake.redraws
        view.isPlaying = true
        assertEquals(wholeCanvasFit(), fake.shownView)
        assertTrue("it has to show", fake.redraws > redraws)

        view.isPlaying = false
        assertEquals(placed, fake.shownView)
    }

    @Test
    fun `a renderer that comes along during playback shows the whole canvas too`() {
        surfaceCreated()
        zoomAndPan()
        view.isPlaying = true
        surfaceDestroyed()
        surfaceCreated()
        assertEquals(wholeCanvasFit(), built[1].shownView)
    }

    @Test
    fun `the bars going away during playback refit it, and do not move where the user had the canvas`() {
        surfaceCreated()
        val fake = built.single()
        zoomAndPan()
        val placed = checkNotNull(fake.shownView)
        view.isPlaying = true
        view.layout(0, 0, VIEW_W, VIEW_H + 100)
        assertEquals(wholeCanvasFit(VIEW_W, VIEW_H + 100), fake.shownView)
        view.layout(0, 0, VIEW_W, VIEW_H)
        view.isPlaying = false
        assertEquals("one change of size and back again leaves it where it was", placed, fake.shownView)
    }

    private fun zoomOf(fake: FakeLowLatency): Float = hypot(checkNotNull(fake.shownView).a, checkNotNull(fake.shownView).b)

    private fun assertCanvasAllInView(shown: Affine?) {
        val project = session.state.value.project
        val view = checkNotNull(shown)
        val left = view.mapX(0f, 0f)
        val right = view.mapX(project.width.toFloat(), project.height.toFloat())
        assertTrue("from $left to $right, in a view $VIEW_W wide", left >= -0.5f && right <= VIEW_W + 0.5f)
    }

    @Test
    fun `a canvas too long for the usual zoom limit is all there, in the editor and in playback`() {
        surfaceCreated()
        val fake = built.single()
        resize(LONG_W, LONG_H)
        assertCanvasAllInView(fake.shownView)

        view.isPlaying = true
        assertCanvasAllInView(fake.shownView)
        assertEquals(wholeCanvasFit(), fake.shownView)
        view.isPlaying = false

        zoomAndPan()
        view.fitToScreen()
        assertCanvasAllInView(fake.shownView)
    }

    @Test
    fun `playback decodes such a canvas at the size it shows it`() {
        surfaceCreated()
        resize(LONG_W, LONG_H)
        assertEquals(VIEW_W.toFloat() / LONG_W, view.fitScale(), 0.0001f)
    }

    @Test
    fun `a pinch on such a canvas neither jumps nor stops short of showing all of it`() {
        surfaceCreated()
        val fake = built.single()
        resize(LONG_W, LONG_H)
        val fitted = zoomOf(fake)
        assertTrue("the premise", fitted < ViewTransform.MIN_ZOOM)

        // A little way in, still below the limit, which it must not jump up to.
        twoFingers(200f, 202f)
        assertEquals(fitted * 1.02f, zoomOf(fake), fitted * 0.001f)

        twoFingers(300f, 101f)
        assertEquals("all of it, and no further", fitted, zoomOf(fake), fitted * 0.001f)
    }

    @Test
    fun `a canvas already too large never tries the low-latency path`() {
        session.resizeCanvas(OVER_LIMIT_W, OVER_LIMIT_H)
        idle()
        surfaceCreated()
        assertTrue(view.renderer is SoftwareRenderer)
        assertTrue(built.isEmpty())
    }

    @Test
    fun `crossing the limit during a stroke waits for the stroke to end`() {
        surfaceCreated()
        val fake = built.single()
        // The session ignores a resize during a stroke, so this is one that got in just before the pen landed.
        session.resizeCanvas(OVER_LIMIT_W, OVER_LIMIT_H)
        idle()
        val down = penDown()
        view.onCanvasSizeChanged()
        assertSame(fake, view.renderer)
        assertFalse(fake.released)
        penUp(down)
        assertEquals(1, fake.commits)
        assertTrue(fake.released)
        assertTrue(view.renderer is SoftwareRenderer)
    }

    @Test
    fun `a surface of a new shape gets a new renderer, and one the same shape does not`() {
        surfaceCreated()
        val first = built.single()
        surfaceChanged(VIEW_W, VIEW_H)
        assertSame("the event that always follows the surface being created", first, view.renderer)
        surfaceChanged(VIEW_W, VIEW_H / 2)
        assertTrue(first.released)
        assertEquals(2, built.size)
        assertSame(built[1], view.renderer)
    }

    @Test
    fun `a new shape during a stroke waits for the stroke to end`() {
        surfaceCreated()
        val first = built.single()
        val down = penDown()
        surfaceChanged(VIEW_W, VIEW_H / 2)
        assertSame(first, view.renderer)
        penUp(down)
        assertEquals(1, first.commits)
        assertTrue(first.released)
        assertSame(built[1], view.renderer)
    }

    @Test
    fun `losing the surface during a stroke calls the stroke off, and the session is free again`() {
        surfaceCreated()
        val first = built.single()
        val down = penDown()
        idle()
        surfaceDestroyed()
        assertEquals(0, first.commits)
        assertTrue(first.released)

        session.addFrame()
        idle()
        assertEquals("a stroke that never ended would have the session ignore this", 2, session.state.value.project.frames.size)
        assertTrue(runBlocking { session.snapshot() }.cels.isEmpty())

        // The rest of the touch arrives after the surface has gone, with nothing to draw on, and does no harm.
        penUp(down)
        assertNull(view.renderer)
    }

    @Test
    fun `playback carries on through a new renderer`() {
        val frame = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        view.setPlaybackFrame(frame)
        surfaceCreated()
        assertSame("the frame was set while there was no surface", frame, built[0].shownFrame)

        surfaceChanged(VIEW_W, VIEW_H / 2)
        assertSame(frame, built[1].shownFrame)
        assertTrue(view.supportsHardwareBitmaps)

        view.setPlaybackFrame(null)
        assertNull(built[1].shownFrame)
        surfaceChanged(VIEW_W, VIEW_H)
        assertNull(built[2].shownFrame)
    }

    @Test
    fun `a stroke merging after the software renderer has gone shows up on the next one`() {
        val software = SoftwareRenderer(surface, session, Color.GRAY)
        val heardFromEngine = CountDownLatch(1)
        val holdEngine = CountDownLatch(1)
        session.sceneListener = { heardFromEngine.countDown() }
        session.addStamps(StampRun.EMPTY) { holdEngine.await() }
        software.beginWet(BrushPresets.PEN, Color.BLACK, 1f)
        software.commitWet()
        software.release()
        holdEngine.countDown()
        await()
        assertTrue(heardFromEngine.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `releasing the software renderer does not leave anything to wait for`() {
        val software = SoftwareRenderer(surface, session, Color.GRAY)
        software.requestRedraw()
        val start = System.nanoTime()
        software.release()
        software.requestRedraw()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $elapsedMs ms", elapsedMs < 400)
    }

    /** With no real surface the library draws nothing, but everything up to it still runs, and strokes must still reach the layer. */
    @Test
    fun `strokes go through the real low-latency renderer into the layer, and the session is left free`() {
        val renderer = FrontBufferRenderer(surface, session, Color.GRAY, Affine.IDENTITY) { }
        assertTrue(session.wantsRevealImage)
        val stamps =
            StampBuffer().apply {
                add(50f, 50f, 10f, 1f, 0f)
                add(60f, 50f, 10f, 1f, 0f)
            }
        val pen = BrushPresets.PEN.copy(sizePx = 10f, sizeCurve = PressureCurve.FLAT)

        renderer.requestRedraw()
        renderer.beginWet(pen, Color.BLACK, 1f)
        renderer.drawWet(stamps.snapshot(), StampRun.EMPTY)
        renderer.requestRedraw()
        renderer.commitWet()
        // The next stroke arrives before the engine has finished with the last one, and is called off, as a palm would be.
        renderer.beginWet(BrushPresets.ERASER, Color.BLACK, 1f)
        renderer.drawWet(stamps.snapshot(), StampRun.EMPTY)
        renderer.cancelWet()
        await()
        val project = runBlocking { session.snapshot() }
        assertEquals("the first stroke is in the layer", 1, project.cels.size)
        assertTrue(session.state.value.canUndo)

        session.addFrame()
        idle()
        assertEquals("a stroke that never ended would have the session ignore this", 2, session.state.value.project.frames.size)

        renderer.release()
        assertFalse(session.wantsRevealImage)
        // What was still on its way when the renderer went does no harm.
        renderer.requestRedraw()
        renderer.setPlaybackFrame(null)
    }

    @Test
    fun `a stroke merging after the low-latency renderer has gone shows up on the next one`() {
        val renderer = FrontBufferRenderer(surface, session, Color.GRAY, Affine.IDENTITY) { }
        val heardFromEngine = CountDownLatch(1)
        val holdEngine = CountDownLatch(1)
        session.sceneListener = { heardFromEngine.countDown() }
        session.addStamps(StampRun.EMPTY) { holdEngine.await() }
        renderer.beginWet(BrushPresets.PEN, Color.BLACK, 1f)
        renderer.commitWet()
        renderer.release()
        holdEngine.countDown()
        await()
        assertTrue(heardFromEngine.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `the hardware limit allows for the margin the surface really has`() {
        assertTrue(fitsHardwareCanvas(1920, 1080))
        assertTrue(fitsHardwareCanvas(3840, 2160))
        assertFalse(fitsHardwareCanvas(5000, 5000))
        assertFalse(fitsHardwareCanvas(OVER_LIMIT_W, OVER_LIMIT_H))
        val budget = MemoryBudget.forMemoryClass(256, false)
        for ((width, height) in listOf(3619 to 3619, 7000 to 1870, 7900 to 1000)) {
            assertTrue(fitsHardwareCanvas(width, height))
            val margin = budget.overscanFor(width, height)
            val surfaceBytes = (width + margin * 2L) * (height + margin * 2L) * 4
            assertTrue("$width x $height", surfaceBytes < 100L * 1024 * 1024)
        }
    }

    private companion object {
        const val VIEW_W = 400
        const val VIEW_H = 300

        // Just over the limit, in as few pixels as the rule allows, to keep the test's bitmaps small.
        const val OVER_LIMIT_W = 13200
        const val OVER_LIMIT_H = 1000

        // Too long for the usual lower limit on zoom to show all of it in this view, and still within what the low-latency path takes.
        const val LONG_W = 8180
        const val LONG_H = 10
    }
}
