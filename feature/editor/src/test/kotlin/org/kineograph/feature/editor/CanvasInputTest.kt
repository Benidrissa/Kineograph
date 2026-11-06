package org.kineograph.feature.editor

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
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
    private val spec = BrushPresets.PEN.copy(sizePx = 8f, sizeCurve = PressureCurve.FLAT)

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
    ): MotionEvent {
        val properties =
            Array(points.size) { i ->
                MotionEvent.PointerProperties().apply {
                    id = i
                    this.toolType = toolType
                }
            }
        val coords =
            Array(points.size) { i ->
                MotionEvent.PointerCoords().apply {
                    x = points[i].first
                    y = points[i].second
                    pressure = 1f
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
            0,
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

    private companion object {
        const val VIEW_W = 800
        const val VIEW_H = 480
    }
}
