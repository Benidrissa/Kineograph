package org.kineograph.feature.editor

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
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
import org.kineograph.model.Project
import org.kineograph.model.newProject
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

/** Sends real touch events through the real view into a real session and looks at the pixels that come out. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], qualifiers = "mdpi")
class CanvasInputTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var session: DrawingSession
    private lateinit var view: KineographCanvasView
    private val store = TestStore()
    private var tool = CanvasTool.BRUSH
    private var blockedCount = 0
    private var playbackTaps = 0
    private var spec = BrushPresets.PEN.copy(sizePx = 8f, sizeCurve = PressureCurve.FLAT)
    private var eraser = BrushPresets.ERASER.copy(sizePx = 30f, sizeCurve = PressureCurve.FLAT, hardness = 1f)
    private var smoothing = 0

    @Before
    fun setUp() {
        val project = newProject("Test", VIEW_W, VIEW_H, 12, Background.Transparent, "Layer 1", now = 0)
        session = DrawingSession(project, store, MemoryBudget.forMemoryClass(256, false), temp.newFolder())
        await()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        view =
            KineographCanvasView(
                activity,
                session,
                lowLatency = false,
                workspaceColor = Color.GRAY,
                toolConfig = {
                    CanvasToolConfig(
                        tool = tool,
                        brush = spec,
                        eraser = eraser,
                        colorArgb = Color.BLACK,
                        smoothing = smoothing,
                        simulateFingerPressure = false,
                        fillColorArgb = Color.RED,
                        fillTolerance = 32,
                        fillGapClose = 0,
                        fillSampleAllLayers = false,
                    )
                },
                listener =
                    object : CanvasListener {
                        override fun onLayerNotDrawable() {
                            blockedCount++
                        }

                        override fun onPlaybackTapped() {
                            playbackTaps++
                        }
                    },
            )
        activity.setContentView(view, FrameLayout.LayoutParams(VIEW_W, VIEW_H))
        // The padding from fitting is 12 dp a side, so the mapping below accounts for it.
        view.measure(
            View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEW_H, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, VIEW_W, VIEW_H)
        val surface = view.getChildAt(0) as SurfaceView
        val holder = shadowOf(surface).fakeSurfaceHolder
        holder.callbacks.toList().forEach { it.surfaceCreated(holder) }
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

    private fun idleFor(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
        await()
    }

    /** Canvas coordinates to view coordinates under the fitted transform, with its 12 px padding on the longer side. */
    private fun toView(canvas: Float): Float = VIEW_W / 2f + (canvas - VIEW_W / 2f) * zoom

    private val zoom: Float get() = minOf((VIEW_W - 24f) / VIEW_W, (VIEW_H - 24f) / VIEW_H)

    private fun toViewY(canvas: Float): Float = VIEW_H / 2f + (canvas - VIEW_H / 2f) * zoom

    private var clock = 1000L

    private fun touch(
        action: Int,
        vararg points: Pair<Float, Float>,
        toolType: Int = MotionEvent.TOOL_TYPE_FINGER,
        downTime: Long = clock,
        actionIndex: Int = 0,
        flags: Int = 0,
        at: Long = clock,
        buttonState: Int = 0,
        pressure: Float = 1f,
        otherToolType: Int = toolType,
        firstPointerId: Int = 0,
    ): MotionEvent {
        val properties =
            Array(points.size) { i ->
                MotionEvent.PointerProperties().apply {
                    id = firstPointerId + i
                    this.toolType = if (i == 0) toolType else otherToolType
                }
            }
        val coords =
            Array(points.size) { i ->
                MotionEvent.PointerCoords().apply {
                    x = points[i].first
                    y = points[i].second
                    this.pressure = pressure
                    size = 1f
                }
            }
        val fullAction = action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        return MotionEvent.obtain(
            downTime,
            at,
            fullAction,
            points.size,
            properties,
            coords,
            0,
            buttonState,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            flags,
        )
    }

    private fun send(event: MotionEvent) {
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    /** Samsung's private actions come as generic events. SPenActionsInComposeTest follows them all the way from the window. */
    private fun sendSPen(event: MotionEvent) {
        view.dispatchGenericMotionEvent(event)
        event.recycle()
    }

    private fun drawLine(fromX: Float, toX: Float, y: Float, toolType: Int = MotionEvent.TOOL_TYPE_FINGER) {
        val down = clock
        val vy = toViewY(y)
        send(touch(MotionEvent.ACTION_DOWN, toView(fromX) to vy, toolType = toolType, downTime = down))
        var x = fromX
        while (x < toX) {
            x += 5f
            clock += 8
            send(touch(MotionEvent.ACTION_MOVE, toView(x) to vy, toolType = toolType, downTime = down))
        }
        clock += 8
        send(touch(MotionEvent.ACTION_UP, toView(x) to vy, toolType = toolType, downTime = down))
        clock += 2000
        idle()
    }

    private fun tap(x: Float, y: Float) {
        val down = clock
        send(touch(MotionEvent.ACTION_DOWN, toView(x) to toViewY(y), downTime = down))
        clock += 20
        send(touch(MotionEvent.ACTION_UP, toView(x) to toViewY(y), downTime = down))
        clock += 2000
        idle()
    }

    private fun snapshot(): Project = runBlocking { session.snapshot() }

    private fun saved(): Bitmap {
        val project = snapshot()
        val out = Bitmap.createBitmap(project.width, project.height, Bitmap.Config.ARGB_8888)
        FrameFlattener(
            store,
        ).flatten(project, session.state.value.frameId, Canvas(out), 1f, null, FlattenOptions(includeBackground = false))
        return out
    }

    private fun inkAt(x: Int, y: Int): Boolean = Color.alpha(saved().getPixel(x, y)) > 128

    @Test
    fun `a finger stroke draws where it was touched`() {
        drawLine(100f, 300f, 200f)
        assertTrue(inkAt(200, 200))
        assertFalse(inkAt(200, 100))
        assertTrue(session.state.value.canUndo)
    }

    @Test
    fun `a stylus stroke draws too`() {
        drawLine(100f, 300f, 200f, toolType = MotionEvent.TOOL_TYPE_STYLUS)
        assertTrue(inkAt(200, 200))
    }

    @Test
    fun `a tap leaves a dot`() {
        tap(150f, 150f)
        assertTrue(inkAt(150, 150))
    }

    @Test
    fun `strokes keep their shape through historical samples`() {
        val down = clock
        val vy = toViewY(200f)
        send(touch(MotionEvent.ACTION_DOWN, toView(100f) to vy, downTime = down))
        // One event carrying a long batch of earlier positions, as Android delivers during fast strokes.
        val batch = touch(MotionEvent.ACTION_MOVE, toView(120f) to vy, downTime = down, at = down + 8)
        for (i in 1..20) {
            val coords = MotionEvent.PointerCoords().apply {
                x = toView(120f + i * 10f)
                y = vy
                pressure = 1f
                size = 1f
            }
            batch.addBatch(down + 8 + i * 8, arrayOf(coords), 0)
        }
        send(batch)
        send(touch(MotionEvent.ACTION_UP, toView(320f) to vy, downTime = down, at = down + 200))
        clock += 2000
        idle()
        assertTrue("start of the batch", inkAt(150, 200))
        assertTrue("middle of the batch", inkAt(220, 200))
        assertTrue("end of the batch", inkAt(310, 200))
    }

    @Test
    fun `the eraser tool erases`() {
        drawLine(100f, 300f, 200f)
        tool = CanvasTool.ERASER
        drawLine(180f, 220f, 200f)
        assertFalse(inkAt(200, 200))
        assertTrue(inkAt(120, 200))
    }

    @Test
    fun `the stylus's eraser end erases without changing tool`() {
        drawLine(100f, 300f, 200f)
        drawLine(180f, 220f, 200f, toolType = MotionEvent.TOOL_TYPE_ERASER)
        assertFalse(inkAt(200, 200))
        assertEquals(CanvasTool.BRUSH, tool)
    }

    @Test
    fun `a cancelled touch leaves nothing`() {
        val down = clock
        send(touch(MotionEvent.ACTION_DOWN, toView(100f) to toViewY(200f), downTime = down))
        clock += 8
        send(touch(MotionEvent.ACTION_MOVE, toView(200f) to toViewY(200f), downTime = down))
        send(touch(MotionEvent.ACTION_CANCEL, toView(200f) to toViewY(200f), downTime = down))
        idle()
        assertTrue(snapshot().cels.isEmpty())
        assertFalse(session.state.value.canUndo)
    }

    @Test
    fun `a touch the system flags as accidental leaves nothing`() {
        val down = clock
        send(touch(MotionEvent.ACTION_DOWN, toView(100f) to toViewY(200f), downTime = down))
        clock += 8
        send(touch(MotionEvent.ACTION_MOVE, toView(200f) to toViewY(200f), downTime = down))
        send(touch(MotionEvent.ACTION_UP, toView(200f) to toViewY(200f), downTime = down, flags = MotionEvent.FLAG_CANCELED))
        idle()
        assertTrue(snapshot().cels.isEmpty())
    }

    @Test
    fun `a second finger soon after the first cancels the stroke and starts a gesture`() {
        val down = clock
        send(touch(MotionEvent.ACTION_DOWN, toView(100f) to toViewY(200f), downTime = down))
        clock += 50
        send(
            touch(
                MotionEvent.ACTION_POINTER_DOWN,
                toView(100f) to toViewY(200f),
                toView(300f) to toViewY(200f),
                downTime = down,
                actionIndex = 1,
            ),
        )
        clock += 400
        send(touch(MotionEvent.ACTION_MOVE, toView(50f) to toViewY(200f), toView(350f) to toViewY(200f), downTime = down))
        send(
            touch(
                MotionEvent.ACTION_POINTER_UP,
                toView(50f) to toViewY(200f),
                toView(350f) to toViewY(200f),
                downTime = down,
                actionIndex = 1,
            ),
        )
        send(touch(MotionEvent.ACTION_UP, toView(50f) to toViewY(200f), downTime = down))
        clock += 2000
        idle()
        assertTrue("the wet stroke must vanish", snapshot().cels.isEmpty())
        assertFalse(session.state.value.canUndo)
    }

    @Test
    fun `a second finger long after drawing began does not disturb the stroke`() {
        val down = clock
        send(touch(MotionEvent.ACTION_DOWN, toView(100f) to toViewY(200f), downTime = down))
        for (x in 110..300 step 10) {
            clock += 16
            send(touch(MotionEvent.ACTION_MOVE, toView(x.toFloat()) to toViewY(200f), downTime = down))
        }
        clock += 16
        send(
            touch(
                MotionEvent.ACTION_POINTER_DOWN,
                toView(300f) to toViewY(200f),
                toView(500f) to toViewY(350f),
                downTime = down,
                actionIndex = 1,
            ),
        )
        clock += 16
        send(touch(MotionEvent.ACTION_MOVE, toView(350f) to toViewY(200f), toView(500f) to toViewY(350f), downTime = down))
        send(
            touch(
                MotionEvent.ACTION_POINTER_UP,
                toView(350f) to toViewY(200f),
                toView(500f) to toViewY(350f),
                downTime = down,
                actionIndex = 1,
            ),
        )
        send(touch(MotionEvent.ACTION_UP, toView(350f) to toViewY(200f), downTime = down))
        clock += 2000
        idle()
        assertTrue(inkAt(200, 200))
        assertTrue(inkAt(340, 200))
        assertFalse("the palm must not draw", inkAt(500, 350))
    }

    private fun twoFingerTap() {
        val down = clock
        val a = 200f to 200f
        val b = 400f to 200f
        send(touch(MotionEvent.ACTION_DOWN, a, downTime = down))
        clock += 10
        send(touch(MotionEvent.ACTION_POINTER_DOWN, a, b, downTime = down, actionIndex = 1))
        clock += 40
        send(touch(MotionEvent.ACTION_POINTER_UP, a, b, downTime = down, actionIndex = 1))
        clock += 10
        send(touch(MotionEvent.ACTION_UP, a, downTime = down))
        clock += 50
    }

    @Test
    fun `a two-finger tap undoes, after waiting to see if a double tap follows`() {
        drawLine(100f, 300f, 200f)
        twoFingerTap()
        idle()
        assertTrue("undo waits for a possible second tap", session.state.value.canUndo)
        idleFor(400)
        assertFalse(session.state.value.canUndo)
        assertTrue(snapshot().cels.isEmpty())
    }

    @Test
    fun `a two-finger tap does not itself draw anything`() {
        twoFingerTap()
        idleFor(400)
        assertTrue(snapshot().cels.isEmpty())
    }

    @Test
    fun `a double two-finger tap fits the screen instead of undoing`() {
        drawLine(100f, 300f, 200f)
        twoFingerTap()
        twoFingerTap()
        idleFor(400)
        assertTrue("the drawing must stay", session.state.value.canUndo)
    }

    @Test
    fun `a three-finger tap redoes`() {
        drawLine(100f, 300f, 200f)
        session.undo()
        idle()
        assertTrue(session.state.value.canRedo)
        val down = clock
        val a = 200f to 200f
        val b = 300f to 200f
        val c = 400f to 200f
        send(touch(MotionEvent.ACTION_DOWN, a, downTime = down))
        clock += 5
        send(touch(MotionEvent.ACTION_POINTER_DOWN, a, b, downTime = down, actionIndex = 1))
        clock += 5
        send(touch(MotionEvent.ACTION_POINTER_DOWN, a, b, c, downTime = down, actionIndex = 2))
        clock += 40
        send(touch(MotionEvent.ACTION_POINTER_UP, a, b, c, downTime = down, actionIndex = 2))
        send(touch(MotionEvent.ACTION_POINTER_UP, a, b, downTime = down, actionIndex = 1))
        send(touch(MotionEvent.ACTION_UP, a, downTime = down))
        idle()
        assertFalse(session.state.value.canRedo)
        assertTrue(inkAt(200, 200))
    }

    @Test
    fun `a two-finger tap still counts when a move arrives between the fingers lifting`() {
        drawLine(100f, 300f, 200f)
        val down = clock
        val a = 200f to 200f
        val b = 400f to 200f
        send(touch(MotionEvent.ACTION_DOWN, a, downTime = down))
        clock += 10
        send(touch(MotionEvent.ACTION_POINTER_DOWN, a, b, downTime = down, actionIndex = 1))
        clock += 40
        send(touch(MotionEvent.ACTION_POINTER_UP, a, b, downTime = down, actionIndex = 1))
        clock += 10
        // The remaining finger reports a move. It has not gone anywhere, but it is far from the old two-finger centre.
        send(touch(MotionEvent.ACTION_MOVE, a, downTime = down))
        send(touch(MotionEvent.ACTION_UP, a, downTime = down))
        clock += 2000
        idleFor(400)
        assertFalse("undo should have fired", session.state.value.canUndo)
    }

    @Test
    fun `a three-finger tap still counts when a move arrives after the third finger lands`() {
        drawLine(100f, 300f, 200f)
        session.undo()
        idle()
        val down = clock
        val a = 200f to 200f
        val b = 300f to 200f
        val c = 700f to 200f
        send(touch(MotionEvent.ACTION_DOWN, a, downTime = down))
        clock += 5
        send(touch(MotionEvent.ACTION_POINTER_DOWN, a, b, downTime = down, actionIndex = 1))
        clock += 5
        send(touch(MotionEvent.ACTION_POINTER_DOWN, a, b, c, downTime = down, actionIndex = 2))
        clock += 5
        send(touch(MotionEvent.ACTION_MOVE, a, b, c, downTime = down))
        clock += 30
        send(touch(MotionEvent.ACTION_POINTER_UP, a, b, c, downTime = down, actionIndex = 2))
        send(touch(MotionEvent.ACTION_POINTER_UP, a, b, downTime = down, actionIndex = 1))
        send(touch(MotionEvent.ACTION_UP, a, downTime = down))
        clock += 2000
        idle()
        assertFalse("redo should have fired", session.state.value.canRedo)
    }

    @Test
    fun `after a pan, strokes land where the finger is on the moved canvas`() {
        val down = clock
        send(touch(MotionEvent.ACTION_DOWN, 300f to 300f, downTime = down))
        clock += 30
        send(touch(MotionEvent.ACTION_POINTER_DOWN, 300f to 300f, 400f to 300f, downTime = down, actionIndex = 1))
        clock += 200
        // Slide both fingers 100 pixels to the right.
        send(touch(MotionEvent.ACTION_MOVE, 400f to 300f, 500f to 300f, downTime = down))
        send(touch(MotionEvent.ACTION_POINTER_UP, 400f to 300f, 500f to 300f, downTime = down, actionIndex = 1))
        send(touch(MotionEvent.ACTION_UP, 400f to 300f, downTime = down))
        clock += 2000
        idle()
        // The canvas moved right by 100, so touching what used to be canvas x=100 now lands 100 further left of it.
        val vy = toViewY(200f)
        val vx = toView(200f)
        val strokeDown = clock
        send(touch(MotionEvent.ACTION_DOWN, vx to vy, downTime = strokeDown))
        clock += 20
        send(touch(MotionEvent.ACTION_UP, vx to vy, downTime = strokeDown))
        clock += 2000
        idle()
        val expected = (200f - 100f / zoom).toInt()
        assertTrue("x near $expected", inkAt(expected, 200))
        assertFalse(inkAt(200, 200))
    }

    @Test
    fun `after the canvas is resized, strokes land where the finger is on the refitted canvas`() {
        session.resizeCanvas(VIEW_W * 2, VIEW_H * 2)
        idle()
        view.onCanvasSizeChanged()
        idle()
        // Fitted again, so the middle of the view is the middle of the new canvas.
        val down = clock
        send(touch(MotionEvent.ACTION_DOWN, VIEW_W / 2f to VIEW_H / 2f, downTime = down))
        clock += 20
        send(touch(MotionEvent.ACTION_UP, VIEW_W / 2f to VIEW_H / 2f, downTime = down))
        clock += 2000
        idle()
        assertTrue(inkAt(VIEW_W, VIEW_H))
    }

    @Test
    fun `after undoing a resize, strokes land where the finger is again`() {
        session.resizeCanvas(VIEW_W * 2, VIEW_H * 2)
        idle()
        view.onCanvasSizeChanged()
        idle()
        session.undo()
        idle()
        view.onCanvasSizeChanged()
        idle()
        tap(200f, 150f)
        assertTrue(inkAt(200, 150))
    }

    @Test
    fun `in the moment before the view catches up with a resize, a touch still lands where the canvas is shown`() {
        session.resizeCanvas(VIEW_W * 2, VIEW_H * 2)
        idle()
        // The renderer is still using the old view, so that is what the user sees and aims at.
        tap(100f, 80f)
        assertTrue(inkAt(100, 80))
    }

    @Test
    fun `a canvas the user has placed stays where it is through a resize, and strokes land under the finger`() {
        val down = clock
        send(touch(MotionEvent.ACTION_DOWN, 300f to 300f, downTime = down))
        clock += 30
        send(touch(MotionEvent.ACTION_POINTER_DOWN, 300f to 300f, 400f to 300f, downTime = down, actionIndex = 1))
        clock += 200
        send(touch(MotionEvent.ACTION_MOVE, 400f to 300f, 500f to 300f, downTime = down))
        send(touch(MotionEvent.ACTION_POINTER_UP, 400f to 300f, 500f to 300f, downTime = down, actionIndex = 1))
        send(touch(MotionEvent.ACTION_UP, 400f to 300f, downTime = down))
        clock += 2000
        idle()

        val grownBy = 200
        session.resizeCanvas(VIEW_W + grownBy, VIEW_H + grownBy)
        idle()
        view.onCanvasSizeChanged()
        idle()
        // The artwork keeps its place on screen, and in the larger canvas every point has moved by half the growth.
        val vy = toViewY(200f)
        val vx = toView(200f)
        val strokeDown = clock
        send(touch(MotionEvent.ACTION_DOWN, vx to vy, downTime = strokeDown))
        clock += 20
        send(touch(MotionEvent.ACTION_UP, vx to vy, downTime = strokeDown))
        clock += 2000
        idle()
        val expectedX = (200f - 100f / zoom).toInt() + grownBy / 2
        assertTrue("x near $expectedX", inkAt(expectedX, 200 + grownBy / 2))
    }

    @Test
    fun `a locked layer refuses a stroke and says so`() {
        session.setLayerLocked(session.state.value.layerId, true)
        idle()
        drawLine(100f, 300f, 200f)
        assertEquals(1, blockedCount)
        assertTrue(snapshot().cels.isEmpty())
    }

    @Test
    fun `a hidden layer refuses a stroke`() {
        session.setLayerVisible(session.state.value.layerId, false)
        idle()
        drawLine(100f, 300f, 200f)
        assertEquals(1, blockedCount)
    }

    @Test
    fun `a gesture still works on a locked layer`() {
        session.setLayerLocked(session.state.value.layerId, true)
        idle()
        val before = blockedCount
        twoFingerTap()
        assertEquals(before + 1, blockedCount)
    }

    @Test
    fun `fill paints on a tap`() {
        tool = CanvasTool.FILL
        tap(300f, 200f)
        assertEquals(Color.RED, saved().getPixel(300, 200))
    }

    @Test
    fun `dragging with the fill tool does not fill`() {
        tool = CanvasTool.FILL
        val down = clock
        send(touch(MotionEvent.ACTION_DOWN, toView(100f) to toViewY(200f), downTime = down))
        clock += 100
        send(touch(MotionEvent.ACTION_UP, toView(400f) to toViewY(200f), downTime = down))
        idle()
        assertTrue(snapshot().cels.isEmpty())
    }

    @Test
    fun `the lasso lifts what it surrounds, and a drag moves it`() {
        drawLine(100f, 300f, 200f)
        tool = CanvasTool.LASSO
        val down = clock
        val corners = listOf(50f to 150f, 350f to 150f, 350f to 250f, 50f to 250f, 50f to 150f)
        send(touch(MotionEvent.ACTION_DOWN, toView(corners[0].first) to toViewY(corners[0].second), downTime = down))
        for (corner in corners.drop(1)) {
            clock += 8
            send(touch(MotionEvent.ACTION_MOVE, toView(corner.first) to toViewY(corner.second), downTime = down))
        }
        clock += 8
        send(touch(MotionEvent.ACTION_UP, toView(corners.last().first) to toViewY(corners.last().second), downTime = down))
        clock += 2000
        idle()
        assertNotNull(session.state.value.selection)

        // Drag from inside the selection.
        val dragDown = clock
        send(touch(MotionEvent.ACTION_DOWN, toView(200f) to toViewY(200f), downTime = dragDown))
        clock += 16
        send(touch(MotionEvent.ACTION_MOVE, toView(450f) to toViewY(350f), downTime = dragDown))
        clock += 16
        send(touch(MotionEvent.ACTION_UP, toView(450f) to toViewY(350f), downTime = dragDown))
        clock += 2000
        idle()
        session.commitSelection()
        idle()
        assertNull(session.state.value.selection)
        val image = saved()
        assertTrue(Color.alpha(image.getPixel(450, 350)) > 128)
        assertEquals(0, Color.alpha(image.getPixel(200, 200)))
        session.undo()
        idle()
        assertTrue(inkAt(200, 200))
    }

    @Test
    fun `touching outside a selection puts it down`() {
        drawLine(100f, 300f, 200f)
        tool = CanvasTool.LASSO
        val down = clock
        val corners = listOf(50f to 150f, 350f to 150f, 350f to 250f, 50f to 250f)
        send(touch(MotionEvent.ACTION_DOWN, toView(corners[0].first) to toViewY(corners[0].second), downTime = down))
        for (corner in corners.drop(1)) {
            clock += 8
            send(touch(MotionEvent.ACTION_MOVE, toView(corner.first) to toViewY(corner.second), downTime = down))
        }
        send(touch(MotionEvent.ACTION_UP, toView(corners.last().first) to toViewY(corners.last().second), downTime = down))
        clock += 2000
        idle()
        assertNotNull(session.state.value.selection)
        tool = CanvasTool.BRUSH
        drawLine(500f, 600f, 400f)
        assertNull(session.state.value.selection)
        assertTrue(inkAt(200, 200))
        assertTrue(inkAt(550, 400))
    }

    @Test
    fun `during playback a tap only reports itself`() {
        view.isPlaying = true
        tap(200f, 200f)
        assertEquals(1, playbackTaps)
        assertTrue(snapshot().cels.isEmpty())
    }

    /** With the bars hidden, the swipe that brings them back for a moment goes to the app as well as to the system. */
    @Test
    fun `a swipe in from the edge during playback does not stop it, and a tap afterwards does`() {
        view.isPlaying = true
        val down = clock
        send(touch(MotionEvent.ACTION_DOWN, 200f to 0f, downTime = down))
        clock += 8
        send(touch(MotionEvent.ACTION_MOVE, 200f to 60f, downTime = down))
        clock += 8
        send(touch(MotionEvent.ACTION_MOVE, 200f to 120f, downTime = down))
        clock += 8
        send(touch(MotionEvent.ACTION_UP, 200f to 120f, downTime = down))
        idle()
        assertEquals(0, playbackTaps)

        tap(200f, 200f)
        assertEquals(1, playbackTaps)
    }

    @Test
    fun `a flick too quick to send a move during playback is not a tap either`() {
        view.isPlaying = true
        val down = clock
        send(touch(MotionEvent.ACTION_DOWN, 200f to 0f, downTime = down))
        clock += 8
        send(touch(MotionEvent.ACTION_UP, 200f to 120f, downTime = down))
        idle()
        assertEquals(0, playbackTaps)
    }

    @Test
    fun `a swipe that comes back to where it began during playback is not a tap`() {
        view.isPlaying = true
        val down = clock
        send(touch(MotionEvent.ACTION_DOWN, 200f to 100f, downTime = down))
        clock += 8
        send(touch(MotionEvent.ACTION_MOVE, 200f to 200f, downTime = down))
        clock += 8
        send(touch(MotionEvent.ACTION_MOVE, 200f to 100f, downTime = down))
        clock += 8
        send(touch(MotionEvent.ACTION_UP, 200f to 100f, downTime = down))
        idle()
        assertEquals(0, playbackTaps)
    }

    @Test
    fun `a finger that wobbles a little during playback is still a tap`() {
        view.isPlaying = true
        val down = clock
        send(touch(MotionEvent.ACTION_DOWN, 200f to 100f, downTime = down))
        clock += 8
        send(touch(MotionEvent.ACTION_MOVE, 203f to 102f, downTime = down))
        clock += 8
        send(touch(MotionEvent.ACTION_UP, 203f to 102f, downTime = down))
        idle()
        assertEquals(1, playbackTaps)
    }

    @Test
    fun `a touch the system takes back during playback is not a tap`() {
        view.isPlaying = true
        val down = clock
        send(touch(MotionEvent.ACTION_DOWN, 200f to 100f, downTime = down))
        clock += 8
        send(touch(MotionEvent.ACTION_CANCEL, 200f to 100f, downTime = down))
        idle()
        assertEquals(0, playbackTaps)
    }

    @Test
    fun `a stroke under way when playback starts is dropped, and the editor does not wait for it`() {
        val down = clock
        send(touch(MotionEvent.ACTION_DOWN, toView(100f) to toViewY(100f), downTime = down))
        clock += 8
        send(touch(MotionEvent.ACTION_MOVE, toView(200f) to toViewY(100f), downTime = down))
        idle()
        view.isPlaying = true
        clock += 8
        send(touch(MotionEvent.ACTION_UP, toView(200f) to toViewY(100f), downTime = down))
        idle()
        assertEquals("the drawing finger lifting is not a tap on the playback", 0, playbackTaps)

        val first = session.state.value.frameId
        session.addFrame()
        idle()
        assertEquals("a stroke that never ended would have the session ignore this", 2, session.state.value.project.frames.size)
        session.selectFrame(first)
        idle()
        assertEquals(first, session.state.value.frameId)
        assertTrue("the stroke was called off, not saved", snapshot().cels.isEmpty())
    }

    @Test
    fun `a stroke called off by playback does not stop the next touch from working`() {
        val down = clock
        send(touch(MotionEvent.ACTION_DOWN, toView(100f) to toViewY(100f), downTime = down))
        idle()
        view.isPlaying = true
        tap(200f, 200f)
        assertEquals(1, playbackTaps)
        view.isPlaying = false
        drawLine(100f, 300f, 100f)
        assertTrue(inkAt(200, 100))
    }

    @Test
    fun `strokes on separate frames stay separate`() {
        drawLine(100f, 300f, 100f)
        session.addFrame()
        idle()
        assertFalse(inkAt(200, 100))
        drawLine(100f, 300f, 300f)
        assertTrue(inkAt(200, 300))
        assertNotEquals(
            session.state.value.project.frames
                .first()
                .id,
            session.state.value.frameId,
        )
    }

    // ----- The pen's button -----

    /** A pen event at a point on the canvas. */
    private fun pen(
        action: Int,
        x: Float,
        y: Float,
        down: Long,
        buttons: Int = 0,
        toolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
        pressure: Float = 1f,
    ): MotionEvent = touch(
        action,
        toView(x) to toViewY(y),
        toolType = toolType,
        downTime = down,
        buttonState = buttons,
        pressure = pressure,
    )

    private fun penDown(x: Float, y: Float, buttons: Int = 0, toolType: Int = MotionEvent.TOOL_TYPE_STYLUS): Long {
        val down = clock
        send(pen(MotionEvent.ACTION_DOWN, x, y, down, buttons, toolType))
        return down
    }

    /** Moves along a line in steps of 5 canvas pixels, as several events. */
    private fun penMove(
        fromX: Float,
        toX: Float,
        y: Float,
        down: Long,
        buttons: Int = 0,
        toolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
        pressure: Float = 1f,
    ) {
        var x = fromX
        while (x < toX) {
            x = minOf(x + 5f, toX)
            clock += 8
            send(pen(MotionEvent.ACTION_MOVE, x, y, down, buttons, toolType, pressure))
        }
    }

    private fun penUp(x: Float, y: Float, down: Long, buttons: Int = 0, toolType: Int = MotionEvent.TOOL_TYPE_STYLUS) {
        clock += 8
        send(pen(MotionEvent.ACTION_UP, x, y, down, buttons, toolType))
        clock += 2000
        idle()
    }

    private fun penLine(
        fromX: Float,
        toX: Float,
        y: Float,
        buttons: Int = 0,
        toolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
        pressure: Float = 1f,
    ) {
        val down = clock
        send(pen(MotionEvent.ACTION_DOWN, fromX, y, down, buttons, toolType, pressure))
        penMove(fromX, toX, y, down, buttons, toolType, pressure)
        penUp(toX, y, down, buttons, toolType)
    }

    private fun undo() {
        session.undo()
        idle()
    }

    /** Undoes everything and says how many steps that took, because "nothing left after three" would also pass with one. */
    private fun undoStepsLeft(): Int {
        var steps = 0
        while (session.state.value.canUndo && steps < 50) {
            undo()
            steps++
        }
        return steps
    }

    private fun lassoAround(left: Float, top: Float, right: Float, bottom: Float) {
        tool = CanvasTool.LASSO
        val down = clock
        send(touch(MotionEvent.ACTION_DOWN, toView(left) to toViewY(top), downTime = down))
        for (corner in listOf(right to top, right to bottom, left to bottom, left to top)) {
            clock += 8
            send(touch(MotionEvent.ACTION_MOVE, toView(corner.first) to toViewY(corner.second), downTime = down))
        }
        clock += 8
        send(touch(MotionEvent.ACTION_UP, toView(left) to toViewY(top), downTime = down))
        clock += 2000
        idle()
        assertNotNull(session.state.value.selection)
    }

    /** Whether the lasso's outline is on screen, found by drawing the view that shows it. */
    private fun outlineShows(): Boolean {
        val out = Bitmap.createBitmap(VIEW_W, VIEW_H, Bitmap.Config.ARGB_8888)
        view.getChildAt(1).draw(Canvas(out))
        val pixels = IntArray(VIEW_W * VIEW_H)
        out.getPixels(pixels, 0, VIEW_W, 0, 0, VIEW_W, VIEW_H)
        return pixels.any { Color.alpha(it) != 0 }
    }

    /** A stroke that never ended would have the session ignore this. */
    private fun assertSessionIsFree() {
        val frames = session.state.value.project.frames.size
        session.addFrame()
        idle()
        assertEquals("the session took a new edit", frames + 1, session.state.value.project.frames.size)
    }

    private val restingHand get() = toView(700f) to toViewY(400f)

    /** An event with the pen at a point on the canvas and a hand resting elsewhere. The pen landed first, so it comes first. */
    private fun penBesideHand(action: Int, x: Float, y: Float, down: Long, buttons: Int = 0, actionIndex: Int = 0): MotionEvent = touch(
        action,
        toView(x) to toViewY(y),
        restingHand,
        toolType = MotionEvent.TOOL_TYPE_STYLUS,
        otherToolType = MotionEvent.TOOL_TYPE_FINGER,
        downTime = down,
        actionIndex = actionIndex,
        buttonState = buttons,
    )

    /** What is left once the pen has lifted and the hand has not: the hand alone, and then the hand lifting. */
    private fun handLiftsLast(down: Long) {
        val hand = MotionEvent.TOOL_TYPE_FINGER
        clock += 8
        send(touch(MotionEvent.ACTION_MOVE, toView(650f) to toViewY(350f), toolType = hand, downTime = down, firstPointerId = 1))
        clock += 8
        send(touch(MotionEvent.ACTION_UP, toView(650f) to toViewY(350f), toolType = hand, downTime = down, firstPointerId = 1))
        clock += 2000
        idle()
    }

    @Test
    fun `holding the stylus button while touching erases, whichever way the device reports it`() {
        val buttons = listOf(MotionEvent.BUTTON_STYLUS_PRIMARY, MotionEvent.BUTTON_STYLUS_SECONDARY, MotionEvent.BUTTON_SECONDARY)
        buttons.forEachIndexed { i, button ->
            val y = 100f + i * 100f
            drawLine(100f, 300f, y)
            penLine(180f, 220f, y, button)
            assertFalse("button $button", inkAt(200, y.toInt()))
            assertTrue("button $button", inkAt(120, y.toInt()))
        }
    }

    @Test
    fun `the selected tool comes back after the button without anyone choosing it again`() {
        penLine(100f, 200f, 100f, BUTTON)
        assertFalse("the first stroke only erased", inkAt(150, 100))
        penLine(100f, 200f, 100f)
        assertTrue(inkAt(150, 100))
        assertEquals(CanvasTool.BRUSH, tool)
    }

    @Test
    fun `the button erases with the pen's pressure, the same as the eraser end`() {
        eraser = BrushPresets.ERASER.copy(sizePx = 30f, sizeCurve = PressureCurve.ramp(0.1f), hardness = 1f)
        drawLine(100f, 300f, 200f)
        // The line is 8 pixels thick and this passes 12 pixels below its middle, which only a wide stamp reaches.
        penLine(120f, 180f, 212f, BUTTON, pressure = 0.05f)
        assertTrue("a light touch makes a narrow stamp", inkAt(150, 200))
        penLine(220f, 280f, 212f, BUTTON, pressure = 1f)
        assertFalse("a firm one makes a wide stamp", inkAt(250, 200))
    }

    @Test
    fun `pressing the button mid-stroke ends the ink there and erases from that point, as a separate undo step`() {
        drawLine(400f, 700f, 200f)
        val down = penDown(100f, 200f)
        penMove(100f, 300f, 200f, down)
        penMove(300f, 550f, 200f, down, BUTTON)
        penUp(550f, 200f, down, BUTTON)

        val image = saved()
        assertTrue("the ink stays", Color.alpha(image.getPixel(150, 200)) > 128)
        assertTrue("right up to the eraser's edge", Color.alpha(image.getPixel(284, 200)) > 128)
        assertEquals("the eraser starts where the ink stopped, and not a sample later", 0, Color.alpha(image.getPixel(287, 200)))
        assertEquals("erased all the way along", 0, Color.alpha(image.getPixel(450, 200)))
        assertTrue("and no further", Color.alpha(image.getPixel(650, 200)) > 128)

        undo()
        assertTrue("undo takes back the erasing alone", inkAt(450, 200))
        assertTrue(inkAt(150, 200))
        undo()
        assertFalse("then the ink", inkAt(150, 200))
        assertTrue(inkAt(450, 200))
        undo()
        assertFalse("and that was all", session.state.value.canUndo)
    }

    @Test
    fun `letting go of the button mid-stroke goes back to drawing from where erasing stopped, with no gap`() {
        drawLine(100f, 300f, 200f)
        val down = penDown(120f, 200f, BUTTON)
        penMove(120f, 280f, 200f, down, BUTTON)
        penMove(280f, 500f, 200f, down)
        penUp(500f, 200f, down)

        val image = saved()
        assertEquals("the erasing happened", 0, Color.alpha(image.getPixel(200, 200)))
        for (x in 280..495) assertTrue("a gap at $x", Color.alpha(image.getPixel(x, 200)) > 128)
        assertEquals("and only from there on", 0, Color.alpha(image.getPixel(270, 200)))

        undo()
        assertFalse("undo takes back the new ink alone", inkAt(450, 200))
        assertFalse("the erasing is still there", inkAt(200, 200))
        undo()
        assertTrue("then the erasing", inkAt(200, 200))
        undo()
        assertFalse("and that was all", session.state.value.canUndo)
    }

    @Test
    fun `the pen can go back and forth on one touch, each part its own undo step`() {
        val down = penDown(100f, 200f)
        penMove(100f, 200f, 200f, down)
        penMove(200f, 230f, 200f, down, BUTTON)
        penMove(230f, 400f, 200f, down)
        penUp(400f, 200f, down)
        val image = saved()
        assertTrue(Color.alpha(image.getPixel(150, 200)) > 128)
        assertEquals(0, Color.alpha(image.getPixel(215, 200)))
        assertTrue(Color.alpha(image.getPixel(300, 200)) > 128)
        assertEquals(3, undoStepsLeft())
    }

    /** A smoothed stroke trails behind the pen, so it only meets the next part if it catches up as it ends. */
    @Test
    fun `with smoothing on, the parts still meet at the point the button changed`() {
        smoothing = 80
        drawLine(320f, 400f, 300f)
        val down = penDown(100f, 300f)
        penMove(100f, 300f, 300f, down)
        penMove(300f, 500f, 300f, down, BUTTON)
        penMove(500f, 700f, 300f, down)
        penUp(700f, 300f, down)
        val image = saved()
        assertTrue("the ink reaches the eraser's edge", Color.alpha(image.getPixel(284, 300)) > 128)
        assertEquals("the eraser starts at the pen, not where the ink had trailed to", 0, Color.alpha(image.getPixel(287, 300)))
        assertEquals("the line it passed over", 0, Color.alpha(image.getPixel(360, 300)))
        for (x in 500..695) assertTrue("a gap at $x", Color.alpha(image.getPixel(x, 300)) > 128)
    }

    @Test
    fun `letting go in a fast batched event starts drawing at once and keeps every sample`() {
        val down = penDown(100f, 200f, BUTTON)
        penMove(100f, 200f, 200f, down, BUTTON)
        val vy = toViewY(200f)
        clock += 8
        val batch = pen(MotionEvent.ACTION_MOVE, 220f, 200f, down)
        for (i in 1..30) {
            val coords = MotionEvent.PointerCoords().apply {
                x = toView(220f + i * 10f)
                y = vy
                pressure = 1f
                size = 1f
            }
            batch.addBatch(clock + i * 2, arrayOf(coords), 0)
        }
        send(batch)
        clock += 100
        penUp(520f, 200f, down)
        val image = saved()
        for (x in 200..515) assertTrue("a gap at $x", Color.alpha(image.getPixel(x, 200)) > 128)
    }

    @Test
    fun `a finger is never affected by the button state`() {
        val down = clock
        val finger = MotionEvent.TOOL_TYPE_FINGER
        send(pen(MotionEvent.ACTION_DOWN, 100f, 200f, down, BUTTON, finger))
        penMove(100f, 200f, 200f, down, BUTTON, finger)
        penMove(200f, 300f, 200f, down, 0, finger)
        penMove(300f, 400f, 200f, down, BUTTON, finger)
        penUp(400f, 200f, down, BUTTON, finger)
        val image = saved()
        for (x in 110..390 step 10) assertTrue("ink at $x", Color.alpha(image.getPixel(x, 200)) > 128)
        undo()
        assertFalse("one stroke, one undo step", session.state.value.canUndo)
    }

    @Test
    fun `a finger drawing beside a pen with its button held goes on drawing`() {
        val down = clock
        val finger = MotionEvent.TOOL_TYPE_FINGER
        val stylus = MotionEvent.TOOL_TYPE_STYLUS
        val other = toView(600f) to toViewY(400f)
        send(pen(MotionEvent.ACTION_DOWN, 100f, 200f, down, 0, finger))
        penMove(100f, 200f, 200f, down, 0, finger)
        clock += 8
        send(
            touch(
                MotionEvent.ACTION_POINTER_DOWN,
                toView(200f) to toViewY(200f),
                other,
                toolType = finger,
                otherToolType = stylus,
                downTime = down,
                actionIndex = 1,
                buttonState = BUTTON,
            ),
        )
        clock += 8
        send(
            touch(
                MotionEvent.ACTION_MOVE,
                toView(300f) to toViewY(200f),
                other,
                toolType = finger,
                otherToolType = stylus,
                downTime = down,
                buttonState = BUTTON,
            ),
        )
        clock += 8
        send(
            touch(
                MotionEvent.ACTION_POINTER_UP,
                toView(300f) to toViewY(200f),
                other,
                toolType = finger,
                otherToolType = stylus,
                downTime = down,
                actionIndex = 1,
                buttonState = BUTTON,
            ),
        )
        penUp(300f, 200f, down, 0, finger)
        assertTrue(inkAt(250, 200))
        undo()
        assertFalse(session.state.value.canUndo)
    }

    /** The pen is down and under way with the chosen tool when the hand lands, and only then is the button pressed. */
    private fun eraseWithHandLandingFirst(chosen: CanvasTool) {
        drawLine(300f, 500f, 200f)
        tool = chosen
        val down = penDown(100f, 200f)
        penMove(100f, 200f, 200f, down)
        clock += 8
        send(penBesideHand(MotionEvent.ACTION_POINTER_DOWN, 200f, 200f, down, actionIndex = 1))
        var x = 200f
        while (x < 400f) {
            x += 5f
            clock += 8
            send(penBesideHand(MotionEvent.ACTION_MOVE, x, 200f, down, BUTTON))
        }
        clock += 8
        send(penBesideHand(MotionEvent.ACTION_POINTER_UP, 400f, 200f, down, BUTTON, actionIndex = 1))
        penUp(400f, 200f, down, BUTTON)
        assertEquals(0, Color.alpha(saved().getPixel(350, 200)))
        assertTrue(inkAt(450, 200))
        assertFalse("the palm must not draw", inkAt(700, 400))
    }

    @Test
    fun `a hand resting beside the pen does not stop the button working`() {
        eraseWithHandLandingFirst(CanvasTool.BRUSH)
    }

    @Test
    fun `a hand resting beside the pen does not stop the button working with the lasso tool`() {
        eraseWithHandLandingFirst(CanvasTool.LASSO)
        assertNull(session.state.value.selection)
    }

    @Test
    fun `a hand resting beside the pen does not stop the button working with the fill tool`() {
        eraseWithHandLandingFirst(CanvasTool.FILL)
        assertEquals("nothing was filled", 0, Color.alpha(saved().getPixel(500, 100)))
    }

    @Test
    fun `a hand landing during a lasso trace leaves it going, and the pen lifting first still selects`() {
        drawLine(100f, 300f, 200f)
        tool = CanvasTool.LASSO
        val down = penDown(50f, 150f)
        clock += 8
        send(pen(MotionEvent.ACTION_MOVE, 350f, 150f, down))
        clock += 8
        send(penBesideHand(MotionEvent.ACTION_POINTER_DOWN, 350f, 150f, down, actionIndex = 1))
        assertTrue("the outline is still there", outlineShows())
        for (corner in listOf(350f to 250f, 50f to 250f, 50f to 150f)) {
            clock += 8
            send(penBesideHand(MotionEvent.ACTION_MOVE, corner.first, corner.second, down))
        }
        clock += 8
        send(penBesideHand(MotionEvent.ACTION_POINTER_UP, 50f, 150f, down, actionIndex = 0))
        idle()
        assertNotNull("the whole trace counted", session.state.value.selection)
        assertFalse("and its outline is done with", outlineShows())
        handLiftsLast(down)
        session.commitSelection()
        idle()
        assertTrue("the canvas did not move under the pen", inkAt(200, 200))
    }

    @Test
    fun `a hand lifting during a lasso trace leaves it going`() {
        drawLine(100f, 300f, 200f)
        tool = CanvasTool.LASSO
        val down = penDown(50f, 150f)
        clock += 8
        send(pen(MotionEvent.ACTION_MOVE, 350f, 150f, down))
        clock += 8
        send(penBesideHand(MotionEvent.ACTION_POINTER_DOWN, 350f, 150f, down, actionIndex = 1))
        clock += 8
        send(penBesideHand(MotionEvent.ACTION_POINTER_UP, 350f, 150f, down, actionIndex = 1))
        for (corner in listOf(350f to 250f, 50f to 250f)) {
            clock += 8
            send(pen(MotionEvent.ACTION_MOVE, corner.first, corner.second, down))
        }
        penUp(50f, 150f, down)
        assertNotNull(session.state.value.selection)
    }

    @Test
    fun `a pen tapping with the fill tool beside a resting hand fills, even lifting first and not being the first pointer`() {
        // Pen and hand on either side of a line, so the fill shows which of them it started from.
        drawLine(0f, 800f, 250f)
        tool = CanvasTool.FILL
        val at = toView(500f) to toViewY(100f)
        val stylus = MotionEvent.TOOL_TYPE_STYLUS
        val hand = MotionEvent.TOOL_TYPE_FINGER
        val down = clock
        send(touch(MotionEvent.ACTION_DOWN, at, toolType = stylus, downTime = down, firstPointerId = 1))
        clock += 8
        send(
            touch(
                MotionEvent.ACTION_POINTER_DOWN,
                restingHand,
                at,
                toolType = hand,
                otherToolType = stylus,
                downTime = down,
                actionIndex = 0,
            ),
        )
        clock += 8
        send(
            touch(
                MotionEvent.ACTION_POINTER_UP,
                restingHand,
                at,
                toolType = hand,
                otherToolType = stylus,
                downTime = down,
                actionIndex = 1,
            ),
        )
        clock += 8
        send(touch(MotionEvent.ACTION_UP, restingHand, toolType = hand, downTime = down))
        clock += 2000
        idle()
        val image = saved()
        assertEquals(Color.RED, image.getPixel(500, 100))
        assertEquals("nothing on the hand's side", 0, Color.alpha(image.getPixel(700, 400)))
    }

    @Test
    fun `a pen dragged with the fill tool beside a resting hand does not fill, wherever the hand is`() {
        tool = CanvasTool.FILL
        // The hand is right where the pen landed, so looking at the wrong pointer would make this look like a tap.
        val landed = toView(500f) to toViewY(100f)
        val lifted = toView(100f) to toViewY(400f)
        val stylus = MotionEvent.TOOL_TYPE_STYLUS
        val hand = MotionEvent.TOOL_TYPE_FINGER
        val down = clock
        send(touch(MotionEvent.ACTION_DOWN, landed, toolType = stylus, downTime = down, firstPointerId = 1))
        clock += 8
        send(
            touch(
                MotionEvent.ACTION_POINTER_DOWN,
                landed,
                landed,
                toolType = hand,
                otherToolType = stylus,
                downTime = down,
                actionIndex = 0,
            ),
        )
        clock += 8
        send(touch(MotionEvent.ACTION_MOVE, landed, lifted, toolType = hand, otherToolType = stylus, downTime = down))
        clock += 8
        send(
            touch(MotionEvent.ACTION_POINTER_UP, landed, lifted, toolType = hand, otherToolType = stylus, downTime = down, actionIndex = 1),
        )
        clock += 8
        send(touch(MotionEvent.ACTION_UP, landed, toolType = hand, downTime = down))
        clock += 2000
        idle()
        assertEquals("nothing was filled", 0, Color.alpha(saved().getPixel(500, 100)))
    }

    @Test
    fun `a pen refused on a locked layer stays refused beside a resting hand, and does not move the canvas`() {
        val layer = session.state.value.layerId
        session.setLayerLocked(layer, true)
        idle()
        val down = penDown(100f, 200f)
        clock += 8
        send(penBesideHand(MotionEvent.ACTION_POINTER_DOWN, 100f, 200f, down, actionIndex = 1))
        clock += 8
        send(penBesideHand(MotionEvent.ACTION_MOVE, 300f, 250f, down))
        clock += 8
        send(penBesideHand(MotionEvent.ACTION_POINTER_UP, 300f, 250f, down, actionIndex = 0))
        handLiftsLast(down)
        assertEquals("told once", 1, blockedCount)
        session.setLayerLocked(layer, false)
        idle()
        drawLine(100f, 300f, 200f)
        assertTrue("a stroke still lands under the pen", inkAt(200, 200))
    }

    @Test
    fun `a pen dragging a selection keeps it beside a resting hand, and the pen lifting first lets it go`() {
        drawLine(100f, 300f, 200f)
        lassoAround(50f, 150f, 350f, 250f)
        val down = penDown(200f, 200f)
        clock += 16
        send(pen(MotionEvent.ACTION_MOVE, 220f, 220f, down))
        clock += 16
        send(penBesideHand(MotionEvent.ACTION_POINTER_DOWN, 220f, 220f, down, actionIndex = 1))
        clock += 16
        send(penBesideHand(MotionEvent.ACTION_MOVE, 300f, 350f, down))
        clock += 16
        send(penBesideHand(MotionEvent.ACTION_POINTER_UP, 300f, 350f, down, actionIndex = 0))
        handLiftsLast(down)
        assertNotNull("still lifted", session.state.value.selection)
        session.commitSelection()
        idle()
        assertTrue("it went all the way to where the pen lifted", inkAt(300, 350))
        assertFalse(inkAt(200, 200))
    }

    @Test
    fun `a finger dragging a selection stops where it was when a second finger lands, and the two move the canvas`() {
        drawLine(100f, 300f, 200f)
        lassoAround(50f, 150f, 350f, 250f)
        tool = CanvasTool.BRUSH
        val down = clock
        val moved = toView(220f) to toViewY(220f)
        send(touch(MotionEvent.ACTION_DOWN, toView(200f) to toViewY(200f), downTime = down))
        clock += 16
        send(touch(MotionEvent.ACTION_MOVE, moved, downTime = down))
        clock += 16
        val second = moved.first + 100f to moved.second
        send(touch(MotionEvent.ACTION_POINTER_DOWN, moved, second, downTime = down, actionIndex = 1))
        clock += 200
        val movedOn = moved.first + 100f to moved.second
        send(touch(MotionEvent.ACTION_MOVE, movedOn, second.first + 100f to second.second, downTime = down))
        send(touch(MotionEvent.ACTION_POINTER_UP, movedOn, second.first + 100f to second.second, downTime = down, actionIndex = 1))
        send(touch(MotionEvent.ACTION_UP, movedOn, downTime = down))
        clock += 2000
        idle()
        session.commitSelection()
        idle()
        assertTrue("the drag stopped at the second finger", inkAt(220, 220))
        assertFalse(inkAt(220, 200))
    }

    /** Two fingers slide 100 pixels to the right, so a dot at the same spot on the screen lands that much further left on the canvas. */
    private fun assertFingersMoveTheCanvas(chosen: CanvasTool) {
        tool = chosen
        val down = clock
        send(touch(MotionEvent.ACTION_DOWN, 300f to 300f, downTime = down))
        clock += 30
        send(touch(MotionEvent.ACTION_POINTER_DOWN, 300f to 300f, 400f to 300f, downTime = down, actionIndex = 1))
        clock += 200
        send(touch(MotionEvent.ACTION_MOVE, 400f to 300f, 500f to 300f, downTime = down))
        send(touch(MotionEvent.ACTION_POINTER_UP, 400f to 300f, 500f to 300f, downTime = down, actionIndex = 1))
        send(touch(MotionEvent.ACTION_UP, 400f to 300f, downTime = down))
        clock += 2000
        idle()
        tool = CanvasTool.BRUSH
        tap(200f, 200f)
        assertTrue(inkAt((200f - 100f / zoom).toInt(), 200))
        assertFalse(inkAt(200, 200))
    }

    @Test
    fun `two fingers still move the canvas with the lasso tool`() {
        assertFingersMoveTheCanvas(CanvasTool.LASSO)
    }

    @Test
    fun `two fingers still move the canvas with the fill tool`() {
        assertFingersMoveTheCanvas(CanvasTool.FILL)
    }

    @Test
    fun `touching first and pressing at once leaves no dot and no undo step for it`() {
        // Wider than the eraser, so that a dot would show around where the eraser went.
        spec = spec.copy(sizePx = 60f)
        val down = penDown(200f, 200f)
        clock += 40
        send(pen(MotionEvent.ACTION_MOVE, 201f, 200f, down))
        penMove(201f, 300f, 200f, down, BUTTON)
        penUp(300f, 200f, down, BUTTON)
        assertFalse("nothing was drawn before the button went down", inkAt(200, 225))
        undo()
        assertFalse("the only step is the erasing", session.state.value.canUndo)
    }

    @Test
    fun `letting go of the button just before lifting leaves no dot and no undo step for it`() {
        val down = penDown(100f, 200f, BUTTON)
        penMove(100f, 300f, 200f, down, BUTTON)
        clock += 8
        send(pen(MotionEvent.ACTION_MOVE, 301f, 200f, down))
        penUp(302f, 200f, down)
        assertFalse(inkAt(300, 200))
        undo()
        assertFalse("the only step is the erasing", session.state.value.canUndo)
    }

    @Test
    fun `a pen that lifts while waiting and lands again beside a resting hand does not join up with where it stopped`() {
        val stylus = MotionEvent.TOOL_TYPE_STYLUS
        val finger = MotionEvent.TOOL_TYPE_FINGER
        val down = penDown(100f, 200f, BUTTON)
        penMove(100f, 200f, 200f, down, BUTTON)
        clock += 8
        val stopped = toView(201f) to toViewY(200f)
        send(pen(MotionEvent.ACTION_MOVE, 201f, 200f, down))
        val palm = toView(700f) to toViewY(400f)
        clock += 8
        send(
            touch(
                MotionEvent.ACTION_POINTER_DOWN,
                stopped,
                palm,
                toolType = stylus,
                otherToolType = finger,
                downTime = down,
                actionIndex = 1,
            ),
        )
        clock += 8
        send(
            touch(
                MotionEvent.ACTION_POINTER_UP,
                stopped,
                palm,
                toolType = stylus,
                otherToolType = finger,
                downTime = down,
                actionIndex = 0,
            ),
        )
        clock += 8
        send(touch(MotionEvent.ACTION_MOVE, palm, toolType = finger, downTime = down, firstPointerId = 1))
        clock += 8
        val landed = toView(400f) to toViewY(100f)
        send(
            touch(
                MotionEvent.ACTION_POINTER_DOWN,
                landed,
                palm,
                toolType = stylus,
                otherToolType = finger,
                downTime = down,
                actionIndex = 0,
            ),
        )
        clock += 8
        val moved = toView(500f) to toViewY(100f)
        send(touch(MotionEvent.ACTION_MOVE, moved, palm, toolType = stylus, otherToolType = finger, downTime = down))
        clock += 8
        send(touch(MotionEvent.ACTION_POINTER_UP, moved, palm, toolType = stylus, otherToolType = finger, downTime = down, actionIndex = 0))
        clock += 8
        send(touch(MotionEvent.ACTION_UP, palm, toolType = finger, downTime = down, firstPointerId = 1))
        clock += 2000
        idle()
        assertTrue("no ink anywhere", snapshot().cels.isEmpty())
    }

    @Test
    fun `lifting with the button changing on the very same event just ends the stroke`() {
        drawLine(100f, 300f, 200f)
        val down = penDown(120f, 200f, BUTTON)
        penMove(120f, 200f, 200f, down, BUTTON)
        penUp(205f, 200f, down, 0)
        assertFalse(inkAt(160, 200))
        undo()
        assertTrue(inkAt(160, 200))
        undo()
        assertFalse(session.state.value.canUndo)
    }

    @Test
    fun `with the eraser selected the button changes nothing, so there is one undo step`() {
        drawLine(100f, 500f, 200f)
        tool = CanvasTool.ERASER
        val down = penDown(120f, 200f)
        penMove(120f, 200f, 200f, down)
        penMove(200f, 300f, 200f, down, BUTTON)
        penMove(300f, 400f, 200f, down)
        penUp(400f, 200f, down)
        val image = saved()
        for (x in 130..390 step 10) assertEquals("x $x", 0, Color.alpha(image.getPixel(x, 200)))
        undo()
        val restored = saved()
        assertTrue("one undo brings all of it back", Color.alpha(restored.getPixel(200, 200)) > 128)
        assertTrue(Color.alpha(restored.getPixel(350, 200)) > 128)
    }

    @Test
    fun `a pen's eraser end with its button pressed mid-stroke is one stroke of erasing`() {
        drawLine(100f, 500f, 200f)
        val eraserEnd = MotionEvent.TOOL_TYPE_ERASER
        val down = penDown(120f, 200f, toolType = eraserEnd)
        penMove(120f, 200f, 200f, down, 0, eraserEnd)
        penMove(200f, 300f, 200f, down, BUTTON, eraserEnd)
        penMove(300f, 400f, 200f, down, 0, eraserEnd)
        penUp(400f, 200f, down, 0, eraserEnd)
        assertFalse(inkAt(350, 200))
        undo()
        assertTrue(inkAt(350, 200))
        assertTrue(inkAt(150, 200))
    }

    @Test
    fun `the button keeps every sample of a batched event, and they all go to the eraser`() {
        drawLine(300f, 700f, 200f)
        val down = penDown(100f, 200f)
        penMove(100f, 200f, 200f, down)
        val vy = toViewY(200f)
        clock += 8
        val batch = pen(MotionEvent.ACTION_MOVE, 310f, 200f, down, BUTTON)
        for (i in 1..30) {
            val coords = MotionEvent.PointerCoords().apply {
                x = toView(310f + i * 10f)
                y = vy
                pressure = 1f
                size = 1f
            }
            batch.addBatch(clock + i * 2, arrayOf(coords), 0)
        }
        send(batch)
        clock += 100
        penUp(610f, 200f, down, BUTTON)
        val image = saved()
        for (x in 330..600 step 10) assertEquals("a sample was missed near $x", 0, Color.alpha(image.getPixel(x, 200)))
        assertTrue("past the last sample", Color.alpha(image.getPixel(650, 200)) > 128)
    }

    @Test
    fun `a touch taken back after the button changed loses only the part still under way`() {
        val down = penDown(100f, 200f)
        penMove(100f, 200f, 200f, down)
        penMove(200f, 300f, 200f, down, BUTTON)
        send(pen(MotionEvent.ACTION_CANCEL, 300f, 200f, down, BUTTON))
        idle()
        assertTrue("the ink had already ended", inkAt(150, 200))
        undo()
        assertFalse(session.state.value.canUndo)
    }

    @Test
    fun `playback starting while the button is held drops the stroke, and the next touch works`() {
        val down = penDown(100f, 100f, BUTTON)
        penMove(100f, 200f, 100f, down, BUTTON)
        idle()
        view.isPlaying = true
        idle()
        view.isPlaying = false
        drawLine(100f, 300f, 100f)
        assertTrue(inkAt(200, 100))
    }

    @Test
    fun `during playback the button does nothing but tap`() {
        view.isPlaying = true
        penLine(200f, 200f, 200f, BUTTON)
        assertEquals(1, playbackTaps)
        assertTrue(snapshot().cels.isEmpty())
    }

    @Test
    fun `a locked or hidden layer refuses the button once, and nothing changes`() {
        drawLine(100f, 300f, 200f)
        val layer = session.state.value.layerId
        session.setLayerLocked(layer, true)
        idle()
        penLine(150f, 250f, 200f, BUTTON)
        assertEquals("touching with the button held", 1, blockedCount)
        val down = penDown(150f, 200f)
        assertEquals("touching first", 2, blockedCount)
        penMove(150f, 250f, 200f, down, BUTTON)
        penUp(250f, 200f, down, BUTTON)
        assertEquals("pressing afterwards is the same refused touch", 2, blockedCount)
        session.setLayerLocked(layer, false)
        session.setLayerVisible(layer, false)
        idle()
        penLine(150f, 250f, 200f, BUTTON)
        assertEquals(3, blockedCount)
        session.setLayerVisible(layer, true)
        idle()
        assertTrue(inkAt(200, 200))
    }

    @Test
    fun `with the fill tool on a locked layer, pressing the button refuses once and does not fill`() {
        tool = CanvasTool.FILL
        session.setLayerLocked(session.state.value.layerId, true)
        idle()
        val down = penDown(300f, 200f)
        assertEquals("a fill only asks when it lifts", 0, blockedCount)
        penMove(300f, 310f, 200f, down, BUTTON)
        penUp(310f, 200f, down, BUTTON)
        assertEquals(1, blockedCount)
        assertTrue(snapshot().cels.isEmpty())
    }

    @Test
    fun `with the fill tool, the button erases instead of filling`() {
        drawLine(100f, 300f, 200f)
        tool = CanvasTool.FILL
        penLine(195f, 205f, 200f, BUTTON)
        val image = saved()
        assertEquals(0, Color.alpha(image.getPixel(200, 200)))
        assertEquals("nothing was filled", 0, Color.alpha(image.getPixel(500, 100)))
    }

    @Test
    fun `with the fill tool, pressing the button during a touch erases, and letting go does not fill afterwards`() {
        drawLine(100f, 300f, 200f)
        tool = CanvasTool.FILL
        val down = penDown(200f, 200f)
        penMove(200f, 205f, 200f, down, BUTTON)
        // Back near where it landed, where a fill would count this as a tap.
        clock += 8
        send(pen(MotionEvent.ACTION_MOVE, 200f, 200f, down))
        penUp(200f, 200f, down)
        val image = saved()
        assertEquals(0, Color.alpha(image.getPixel(200, 200)))
        assertEquals("nothing was filled", 0, Color.alpha(image.getPixel(500, 100)))
        tap(500f, 100f)
        assertEquals("the next touch fills as usual", Color.RED, saved().getPixel(500, 100))
    }

    @Test
    fun `with the lasso tool, the button erases and selects nothing`() {
        drawLine(100f, 300f, 200f)
        tool = CanvasTool.LASSO
        val down = penDown(150f, 150f, BUTTON)
        penMove(150f, 250f, 150f, down, BUTTON)
        clock += 8
        send(pen(MotionEvent.ACTION_MOVE, 250f, 250f, down, BUTTON))
        clock += 8
        send(pen(MotionEvent.ACTION_MOVE, 150f, 250f, down, BUTTON))
        penUp(150f, 150f, down, BUTTON)
        assertNull(session.state.value.selection)
        assertFalse("erased where the pen crossed the line", inkAt(250, 200))
        assertTrue("and the inside of the loop was not lifted", inkAt(200, 200))
    }

    @Test
    fun `pressing the button during a lasso trace abandons the trace and erases, and letting go leaves no selection`() {
        drawLine(100f, 300f, 200f)
        tool = CanvasTool.LASSO
        val down = penDown(50f, 150f)
        penMove(50f, 195f, 150f, down)
        assertTrue("the outline is up", outlineShows())
        clock += 8
        send(pen(MotionEvent.ACTION_MOVE, 200f, 200f, down, BUTTON))
        assertFalse("and goes as soon as the button does", outlineShows())
        penMove(200f, 250f, 200f, down, BUTTON)
        // Enough movement afterwards for a new outline, if one were started by mistake.
        clock += 8
        send(pen(MotionEvent.ACTION_MOVE, 250f, 350f, down))
        clock += 8
        send(pen(MotionEvent.ACTION_MOVE, 50f, 350f, down))
        assertFalse(outlineShows())
        penUp(50f, 150f, down)
        assertNull(session.state.value.selection)
        assertFalse(inkAt(225, 200))
        assertTrue("nothing was lifted", inkAt(120, 200))
        assertTrue("erasing began where the pen had got to, not in a straight line from where it landed", inkAt(170, 200))
        assertFalse("and nothing drawn", inkAt(150, 350))
    }

    @Test
    fun `with the fill tool, pressing the button after moving erases from where the pen has got to, not from where it landed`() {
        drawLine(100f, 300f, 200f)
        tool = CanvasTool.FILL
        val down = penDown(50f, 150f)
        penMove(50f, 195f, 150f, down)
        clock += 8
        send(pen(MotionEvent.ACTION_MOVE, 200f, 200f, down, BUTTON))
        penUp(200f, 200f, down, BUTTON)
        assertFalse(inkAt(200, 200))
        assertTrue(inkAt(170, 200))
    }

    @Test
    fun `the button can come back during the same touch with the lasso tool, and erases again`() {
        drawLine(100f, 500f, 200f)
        tool = CanvasTool.LASSO
        val down = penDown(100f, 200f, BUTTON)
        penMove(100f, 150f, 200f, down, BUTTON)
        penMove(150f, 300f, 200f, down)
        penMove(300f, 350f, 200f, down, BUTTON)
        penUp(350f, 200f, down, BUTTON)
        val image = saved()
        assertEquals(0, Color.alpha(image.getPixel(120, 200)))
        assertTrue("nothing between", Color.alpha(image.getPixel(225, 200)) > 128)
        assertEquals(0, Color.alpha(image.getPixel(330, 200)))
    }

    @Test
    fun `a pen's eraser end erases with the fill and lasso tools too`() {
        drawLine(100f, 300f, 200f)
        tool = CanvasTool.FILL
        penLine(120f, 140f, 200f, toolType = MotionEvent.TOOL_TYPE_ERASER)
        tool = CanvasTool.LASSO
        penLine(260f, 280f, 200f, toolType = MotionEvent.TOOL_TYPE_ERASER)
        val image = saved()
        assertEquals(0, Color.alpha(image.getPixel(130, 200)))
        assertEquals(0, Color.alpha(image.getPixel(270, 200)))
        assertEquals(0, Color.alpha(image.getPixel(500, 100)))
        assertNull(session.state.value.selection)
    }

    @Test
    fun `a selection's handles keep the touch, with the button held or pressed`() {
        drawLine(100f, 300f, 200f)
        lassoAround(50f, 150f, 350f, 250f)
        // Dragging from inside with the button held from the start, then pressing and releasing on the way.
        val down = penDown(200f, 200f, BUTTON)
        clock += 16
        send(pen(MotionEvent.ACTION_MOVE, 300f, 250f, down, BUTTON))
        clock += 16
        send(pen(MotionEvent.ACTION_MOVE, 350f, 300f, down))
        clock += 16
        send(pen(MotionEvent.ACTION_MOVE, 400f, 350f, down, BUTTON))
        penUp(400f, 350f, down, BUTTON)
        assertNotNull("still lifted", session.state.value.selection)
        session.commitSelection()
        idle()
        assertTrue("it moved, and was not erased", inkAt(350, 350))
        assertFalse(inkAt(200, 200))
    }

    @Test
    fun `touching outside a selection with the button held puts it down and erases`() {
        drawLine(100f, 300f, 200f)
        drawLine(500f, 700f, 400f)
        lassoAround(50f, 150f, 350f, 250f)
        penLine(590f, 610f, 400f, BUTTON)
        assertNull(session.state.value.selection)
        assertTrue("the selection is back in the layer", inkAt(200, 200))
        val image = saved()
        assertEquals(0, Color.alpha(image.getPixel(600, 400)))
        assertTrue(Color.alpha(image.getPixel(520, 400)) > 128)
    }

    @Test
    fun `Samsung's own actions for a pen with its button held erase like any other`() {
        drawLine(100f, 500f, 200f)
        val down = clock
        sendSPen(pen(SPEN_ACTION_DOWN, 180f, 200f, down))
        clock += 8
        sendSPen(pen(SPEN_ACTION_MOVE, 220f, 200f, down))
        clock += 8
        sendSPen(pen(SPEN_ACTION_UP, 220f, 200f, down))
        clock += 2000
        idle()
        assertFalse(inkAt(200, 200))
        assertTrue(inkAt(120, 200))
    }

    @Test
    fun `Samsung's own actions partway through a stroke switch to erasing, and the lift still ends it`() {
        drawLine(300f, 500f, 200f)
        val down = penDown(100f, 200f)
        penMove(100f, 200f, 200f, down)
        var x = 200f
        while (x < 400f) {
            x += 5f
            clock += 8
            sendSPen(pen(SPEN_ACTION_MOVE, x, 200f, down))
        }
        clock += 8
        sendSPen(pen(SPEN_ACTION_UP, 400f, 200f, down))
        clock += 2000
        idle()
        assertTrue(inkAt(150, 200))
        assertFalse(inkAt(350, 200))
        assertSessionIsFree()
    }

    @Test
    fun `Samsung's own down partway through a stroke switches to erasing, and does not start the touch over`() {
        drawLine(300f, 500f, 200f)
        val down = penDown(100f, 200f)
        penMove(100f, 200f, 200f, down)
        clock += 8
        sendSPen(pen(SPEN_ACTION_DOWN, 205f, 200f, down))
        var x = 205f
        while (x < 400f) {
            x += 5f
            clock += 8
            sendSPen(pen(SPEN_ACTION_MOVE, x, 200f, down))
        }
        clock += 8
        sendSPen(pen(SPEN_ACTION_UP, 400f, 200f, down))
        clock += 2000
        idle()
        assertTrue("an open stroke overwritten by a new one would lose this", inkAt(150, 200))
        assertFalse(inkAt(350, 200))
        assertEquals("the first line, the ink and the erasing", 3, undoStepsLeft())
    }

    @Test
    fun `Samsung's own up for another pointer leaves the pen's stroke going`() {
        val stylus = MotionEvent.TOOL_TYPE_STYLUS
        val finger = MotionEvent.TOOL_TYPE_FINGER
        val down = penDown(100f, 200f)
        penMove(100f, 200f, 200f, down)
        val at = toView(200f) to toViewY(200f)
        val palm = toView(700f) to toViewY(400f)
        clock += 8
        send(touch(MotionEvent.ACTION_POINTER_DOWN, at, palm, toolType = stylus, otherToolType = finger, downTime = down, actionIndex = 1))
        clock += 8
        sendSPen(touch(SPEN_ACTION_UP, at, palm, toolType = stylus, otherToolType = finger, downTime = down, actionIndex = 1))
        penMove(200f, 400f, 200f, down, BUTTON)
        penUp(400f, 200f, down, BUTTON)
        val image = saved()
        assertTrue(Color.alpha(image.getPixel(150, 200)) > 128)
        assertEquals("the ink and the erasing, so the stroke did carry on past that event", 2, undoStepsLeft())
    }

    @Test
    fun `Samsung's own action being taken back leaves nothing, and frees the session`() {
        val down = clock
        sendSPen(pen(SPEN_ACTION_DOWN, 180f, 200f, down))
        clock += 8
        sendSPen(pen(SPEN_ACTION_MOVE, 220f, 200f, down))
        sendSPen(pen(SPEN_ACTION_CANCEL, 220f, 200f, down))
        idle()
        assertFalse(session.state.value.canUndo)
        assertSessionIsFree()
    }

    @Test
    fun `any other generic event is left alone, and does not count as touching the canvas`() {
        val scroll =
            MotionEvent.obtain(clock, clock, MotionEvent.ACTION_SCROLL, toView(200f), toViewY(200f), 0).apply {
                source = InputDevice.SOURCE_MOUSE
            }
        assertFalse("not ours, so whoever is above can use it", view.dispatchGenericMotionEvent(scroll))
        scroll.recycle()
        idle()
        assertTrue(snapshot().cels.isEmpty())
    }

    private companion object {
        const val VIEW_W = 800
        const val VIEW_H = 480
        const val BUTTON = MotionEvent.BUTTON_STYLUS_PRIMARY
        const val SPEN_ACTION_DOWN = 211
        const val SPEN_ACTION_UP = 212
        const val SPEN_ACTION_MOVE = 213
        const val SPEN_ACTION_CANCEL = 214
    }
}
