package org.kineograph.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kineograph.engine.brush.BrushPresets
import org.kineograph.engine.brush.PressureCurve
import org.kineograph.engine.compositor.FlattenOptions
import org.kineograph.engine.compositor.FrameFlattener
import org.kineograph.engine.session.DrawingSession
import org.kineograph.engine.session.MemoryBudget
import org.kineograph.feature.editor.canvas.CanvasListener
import org.kineograph.feature.editor.canvas.CanvasTool
import org.kineograph.feature.editor.canvas.CanvasToolConfig
import org.kineograph.feature.editor.canvas.KineographCanvasView
import org.kineograph.model.Background
import org.kineograph.model.newProject
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Sends Samsung's private pen actions through everything they cross in the app: the window's decor view, the ordinary view groups under
 * it, and Compose hosting the canvas in an AndroidView. Sending them straight to the canvas would pass whatever those did with them,
 * and what they do is the whole question. No phone is involved, so this says nothing about whether a phone sends these at all.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], qualifiers = "w800dp-h480dp-mdpi")
class SPenActionsInComposeTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var session: DrawingSession
    private lateinit var canvas: KineographCanvasView
    private lateinit var window: View
    private val store = TestStore()
    private var clock = 1000L

    @Before
    fun setUp() {
        val project = newProject("Test", SIZE_W, SIZE_H, 12, Background.Transparent, "Layer 1", now = 0)
        session = DrawingSession(project, store, MemoryBudget.forMemoryClass(256, false), temp.newFolder())
        runBlocking { session.awaitIdle() }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            AndroidView(
                factory = { context ->
                    KineographCanvasView(
                        context,
                        session,
                        lowLatency = false,
                        workspaceColor = Color.GRAY,
                        toolConfig = {
                            CanvasToolConfig(
                                tool = CanvasTool.BRUSH,
                                brush = BrushPresets.PEN.copy(sizePx = 8f, sizeCurve = PressureCurve.FLAT),
                                eraser = BrushPresets.ERASER.copy(sizePx = 30f, sizeCurve = PressureCurve.FLAT, hardness = 1f),
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
                    ).also { canvas = it }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        idle()
        window = activity.window.decorView
        window.measure(
            View.MeasureSpec.makeMeasureSpec(SIZE_W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(SIZE_H, View.MeasureSpec.EXACTLY),
        )
        window.layout(0, 0, SIZE_W, SIZE_H)
        idle()
        val surface = canvas.getChildAt(0) as SurfaceView
        val holder = shadowOf(surface).fakeSurfaceHolder
        holder.callbacks.toList().forEach { it.surfaceCreated(holder) }
        idle()
        assertEquals("the sums below take the canvas to fill the window", SIZE_W, canvas.width)
        assertEquals(SIZE_H, canvas.height)
    }

    @After
    fun tearDown() {
        session.close()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        if (::session.isInitialized) runBlocking { session.awaitIdle() }
    }

    private val zoom get() = minOf((SIZE_W - 24f) / SIZE_W, (SIZE_H - 24f) / SIZE_H)

    /** A pen event at a point on the canvas, in the window's coordinates. */
    private fun pen(action: Int, x: Float, y: Float, down: Long): MotionEvent {
        val properties =
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_STYLUS
                },
            )
        val coords =
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = SIZE_W / 2f + (x - SIZE_W / 2f) * zoom
                    this.y = SIZE_H / 2f + (y - SIZE_H / 2f) * zoom
                    pressure = 1f
                    size = 1f
                },
            )
        clock += 8
        return MotionEvent.obtain(down, clock, action, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    /**
     * How a stock framework sends an event into a window. It asks the event whether it is a touch and, if not, sends it on as a generic
     * event. It says no to these actions, so this is the path they take on any device that has not changed that.
     */
    private fun dispatchAsTheFrameworkDoes(event: MotionEvent) {
        View::class.java.getMethod("dispatchPointerEvent", MotionEvent::class.java).invoke(window, event)
        event.recycle()
        idle()
    }

    /** For a framework changed to count these actions as touches, which Samsung's may be. */
    private fun dispatchAsTouch(event: MotionEvent) {
        window.dispatchTouchEvent(event)
        event.recycle()
        idle()
    }

    private fun line(fromX: Float, toX: Float, y: Float) {
        val down = clock
        dispatchAsTheFrameworkDoes(pen(MotionEvent.ACTION_DOWN, fromX, y, down))
        var x = fromX
        while (x < toX) {
            x = minOf(x + 5f, toX)
            dispatchAsTheFrameworkDoes(pen(MotionEvent.ACTION_MOVE, x, y, down))
        }
        dispatchAsTheFrameworkDoes(pen(MotionEvent.ACTION_UP, x, y, down))
        clock += 2000
        idle()
    }

    private fun alphaAt(x: Int, y: Int): Int {
        val project = runBlocking { session.snapshot() }
        val out = Bitmap.createBitmap(project.width, project.height, Bitmap.Config.ARGB_8888)
        FrameFlattener(
            store,
        ).flatten(project, session.state.value.frameId, Canvas(out), 1f, null, FlattenOptions(includeBackground = false))
        return Color.alpha(out.getPixel(x, y))
    }

    private fun assertInk(x: Int, y: Int) = assertTrue("ink at $x,$y", alphaAt(x, y) > 128)

    private fun assertErased(x: Int, y: Int) = assertEquals("nothing at $x,$y", 0, alphaAt(x, y))

    /** A stroke that never ended would have the session ignore this, and a touch that was left open would stop the next from drawing. */
    private fun assertNextTouchWorks() {
        val frames = session.state.value.project.frames.size
        session.addFrame()
        idle()
        assertEquals("the session took a new edit", frames + 1, session.state.value.project.frames.size)
        line(100f, 300f, 300f)
        assertInk(200, 300)
    }

    @Test
    fun `the usual actions reach the canvas as they always have`() {
        line(100f, 300f, 200f)
        assertInk(200, 200)
    }

    @Test
    fun `a pen that lands with its button held erases`() {
        line(100f, 500f, 200f)
        val down = clock
        dispatchAsTheFrameworkDoes(pen(SPEN_ACTION_DOWN, 180f, 200f, down))
        dispatchAsTheFrameworkDoes(pen(SPEN_ACTION_MOVE, 220f, 200f, down))
        dispatchAsTheFrameworkDoes(pen(SPEN_ACTION_UP, 220f, 200f, down))
        clock += 2000
        idle()
        assertErased(200, 200)
        assertInk(120, 200)
        assertInk(300, 200)
        assertNextTouchWorks()
    }

    @Test
    fun `a pen whose button is pressed mid-stroke switches to erasing, and the lift ends it`() {
        line(300f, 500f, 200f)
        val down = clock
        dispatchAsTheFrameworkDoes(pen(MotionEvent.ACTION_DOWN, 100f, 200f, down))
        dispatchAsTheFrameworkDoes(pen(MotionEvent.ACTION_MOVE, 200f, 200f, down))
        var x = 200f
        while (x < 400f) {
            x += 5f
            dispatchAsTheFrameworkDoes(pen(SPEN_ACTION_MOVE, x, 200f, down))
        }
        dispatchAsTheFrameworkDoes(pen(SPEN_ACTION_UP, 400f, 200f, down))
        clock += 2000
        idle()
        assertInk(150, 200)
        assertErased(350, 200)
        assertInk(450, 200)
        assertNextTouchWorks()
    }

    @Test
    fun `a touch taken back leaves nothing and frees the session`() {
        val down = clock
        dispatchAsTheFrameworkDoes(pen(SPEN_ACTION_DOWN, 180f, 200f, down))
        dispatchAsTheFrameworkDoes(pen(SPEN_ACTION_MOVE, 220f, 200f, down))
        dispatchAsTheFrameworkDoes(pen(SPEN_ACTION_CANCEL, 220f, 200f, down))
        clock += 2000
        idle()
        assertFalse(session.state.value.canUndo)
        assertNextTouchWorks()
    }

    /**
     * Not what anyone would want, but what happens if a framework counts these as touches and left its view groups alone. A view group only
     * looks for a child to give a touch to on a real down, so the first view group in the window keeps this one, and the pen does nothing.
     */
    @Test
    fun `where they count as touches, a pen that lands with its button held does nothing, and leaves nothing stuck`() {
        line(100f, 500f, 200f)
        val down = clock
        dispatchAsTouch(pen(SPEN_ACTION_DOWN, 180f, 200f, down))
        dispatchAsTouch(pen(SPEN_ACTION_MOVE, 220f, 200f, down))
        dispatchAsTouch(pen(SPEN_ACTION_UP, 220f, 200f, down))
        clock += 2000
        idle()
        assertInk(200, 200)
        assertNextTouchWorks()
    }

    @Test
    fun `where they count as touches, a pen whose button is pressed mid-stroke still switches to erasing`() {
        line(300f, 500f, 200f)
        val down = clock
        dispatchAsTouch(pen(MotionEvent.ACTION_DOWN, 100f, 200f, down))
        dispatchAsTouch(pen(MotionEvent.ACTION_MOVE, 200f, 200f, down))
        var x = 200f
        while (x < 400f) {
            x += 5f
            dispatchAsTouch(pen(SPEN_ACTION_MOVE, x, 200f, down))
        }
        dispatchAsTouch(pen(SPEN_ACTION_UP, 400f, 200f, down))
        clock += 2000
        idle()
        assertInk(150, 200)
        assertErased(350, 200)
        assertNextTouchWorks()
    }

    private companion object {
        const val SIZE_W = 800
        const val SIZE_H = 480
        const val SPEN_ACTION_DOWN = 211
        const val SPEN_ACTION_UP = 212
        const val SPEN_ACTION_MOVE = 213
        const val SPEN_ACTION_CANCEL = 214
    }
}
