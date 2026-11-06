package org.kineograph.feature.editor.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.input.motionprediction.MotionEventPredictor
import org.kineograph.engine.ViewTransform
import org.kineograph.engine.brush.BrushSpec
import org.kineograph.engine.brush.StampRun
import org.kineograph.engine.brush.StrokeBuilder
import org.kineograph.engine.brush.StrokeOptions
import org.kineograph.engine.compositor.SceneRenderer
import org.kineograph.engine.selection.SelectionHandle
import org.kineograph.engine.selection.SelectionTransform
import org.kineograph.engine.session.DrawingSession
import kotlin.math.atan2
import kotlin.math.hypot

/** The drawing tools, as far as touch input is concerned. */
internal enum class CanvasTool { BRUSH, ERASER, LASSO, FILL }

/** What the canvas needs to know from the tool settings when a touch begins. */
internal class CanvasToolConfig(
    val tool: CanvasTool,
    val brush: BrushSpec,
    val eraser: BrushSpec,
    val colorArgb: Int,
    val smoothing: Int,
    val simulateFingerPressure: Boolean,
    val fillColorArgb: Int,
    val fillTolerance: Int,
    val fillGapClose: Int,
    val fillSampleAllLayers: Boolean,
)

/** What the canvas tells the rest of the editor. */
internal interface CanvasListener {
    fun onLayerNotDrawable()

    fun onPlaybackTapped()
}

/**
 * The drawing surface and everything that touches it. Compose only hosts this view; live drawing never goes through
 * Compose, so a recomposition can never delay a stroke.
 *
 * It reads every touch, including all the samples the system batches between frames, and turns them into stamps
 * straight away on the main thread, which is cheap because no pixels are involved. Rendering and raster work happen
 * elsewhere.
 */
@SuppressLint("ViewConstructor")
internal class KineographCanvasView(
    context: Context,
    private val session: DrawingSession,
    private val lowLatency: Boolean,
    private val workspaceColor: Int,
    private val toolConfig: () -> CanvasToolConfig,
    private val listener: CanvasListener,
) : FrameLayout(context) {
    private val surfaceView = SurfaceView(context)
    private val overlay = LassoOverlay(context)
    private val density = resources.displayMetrics.density
    private val tapSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val tapClassifier = TapClassifier(tapSlop * 1.5f)
    private val doubleTap = DoubleTapResolver()
    private var pendingUndo: Runnable? = null

    /** The renderer, or null before the view attaches and after it detaches. Readable for tests and for playback. */
    internal var renderer: CanvasRenderer? = null
        private set
    private var predictor: MotionEventPredictor? = null

    private var transform = ViewTransform()
    private var userMovedCanvas = false

    private enum class Mode { IDLE, STROKE, LASSO, FILL_TAP, SELECTION_DRAG, GESTURE, IGNORED }

    private var mode = Mode.IDLE
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var downTimeMs = 0L
    private var downX = 0f
    private var downY = 0f
    private var strokeTravel = 0f

    private var stroke: StrokeBuilder? = null
    private val coordinates = FloatArray(2)

    private var selectionHandle: SelectionHandle? = null
    private var selectionTransform: SelectionTransform? = null
    private var lastCanvasX = 0f
    private var lastCanvasY = 0f

    private var gestureCentroidX = 0f
    private var gestureCentroidY = 0f
    private var gestureSpan = 0f
    private var gestureAngle = 0f
    private var gestureStartCentroidX = 0f
    private var gestureStartCentroidY = 0f
    private var gestureStartSpan = 0f

    /** True while playback is running, when touches only serve to stop it. */
    internal var isPlaying: Boolean = false

    init {
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        tag = "canvas"
        contentDescription = context.getString(org.kineograph.feature.editor.R.string.canvas_description)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderer = createRenderer()
        predictor = createPredictor()
        session.sceneListener = { renderer?.requestRedraw() }
        publishView()
        renderer?.requestRedraw()
    }

    override fun onDetachedFromWindow() {
        session.sceneListener = null
        renderer?.release()
        renderer = null
        predictor = null
        super.onDetachedFromWindow()
    }

    /** Prediction only trims latency. If a device cannot set it up, drawing simply carries on without it. */
    private fun createPredictor(): MotionEventPredictor? =
        try {
            MotionEventPredictor.newInstance(this)
        } catch (_: RuntimeException) {
            null
        }

    /**
     * Picks the renderer. Bitmaps over 100 MB cannot go through the hardware canvas at all, so giant canvases draw
     * in software whatever the device supports, and any failure setting up low latency also falls back to software.
     */
    private fun createRenderer(): CanvasRenderer {
        val project = session.state.value.project
        val bytes = project.width.toLong() * project.height * BYTES_PER_PIXEL * OVERSCAN_ALLOWANCE
        if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && bytes < HARDWARE_BITMAP_LIMIT) {
            try {
                return FrontBufferRenderer(surfaceView, session, workspaceColor)
            } catch (_: RuntimeException) {
                // Some devices lack the required buffer support. The software path works everywhere.
            }
        }
        return SoftwareRenderer(surfaceView, session, workspaceColor)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        if (!userMovedCanvas || oldw == 0) {
            fitToScreen()
        } else {
            // Keep the same part of the canvas in the middle when the window changes shape.
            transform = transform.copy(panX = transform.panX + (w - oldw) / 2f, panY = transform.panY + (h - oldh) / 2f)
            publishView()
            renderer?.requestRedraw()
        }
    }

    /** Shows the whole canvas, upright and centred. */
    internal fun fitToScreen() {
        val project = session.state.value.project
        transform =
            ViewTransform.fit(width.coerceAtLeast(1), height.coerceAtLeast(1), project.width, project.height, FIT_PADDING_DP * density)
        userMovedCanvas = false
        publishView()
        renderer?.requestRedraw()
    }

    /** The scale a full frame is displayed at when fitted to the screen. */
    internal fun fitScale(): Float {
        val project = session.state.value.project
        return ViewTransform.fit(width.coerceAtLeast(1), height.coerceAtLeast(1), project.width, project.height, 0f).zoom
    }

    private fun publishView() {
        val project = session.state.value.project
        renderer?.setView(transform.toAffine(project.width, project.height))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isPlaying) {
            if (event.actionMasked == MotionEvent.ACTION_UP) listener.onPlaybackTapped()
            return true
        }
        predictor?.record(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(event)
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(event)
            MotionEvent.ACTION_UP -> onUp(event)
            MotionEvent.ACTION_CANCEL -> onCancel()
        }
        return true
    }

    private fun onDown(event: MotionEvent) {
        requestUnbufferedDispatch(event)
        parent?.requestDisallowInterceptTouchEvent(true)
        activePointerId = event.getPointerId(0)
        downTimeMs = event.eventTime
        downX = event.x
        downY = event.y
        strokeTravel = 0f
        val config = toolConfig()
        toCanvas(event.x, event.y)
        lastCanvasX = coordinates[0]
        lastCanvasY = coordinates[1]

        val state = session.state.value
        val selection = state.selection
        if (selection != null) {
            val onePixel = 1f / transform.zoom
            val handle =
                selection.hitTest(
                    coordinates[0],
                    coordinates[1],
                    TOUCH_RADIUS_DP * density * onePixel,
                    SceneRenderer.KNOB_OFFSET * onePixel,
                )
            if (handle != null) {
                selectionHandle = handle
                selectionTransform = selection
                mode = Mode.SELECTION_DRAG
                return
            }
            session.commitSelection()
        }

        val toolType = event.getToolType(0)
        when (config.tool) {
            CanvasTool.BRUSH, CanvasTool.ERASER -> {
                if (state.canDrawOnActiveLayer) {
                    startStroke(event, config, toolType)
                } else {
                    listener.onLayerNotDrawable()
                    mode = Mode.IGNORED
                }
            }

            CanvasTool.LASSO -> {
                if (state.canDrawOnActiveLayer) {
                    mode = Mode.LASSO
                    overlay.begin(event.x, event.y)
                } else {
                    listener.onLayerNotDrawable()
                    mode = Mode.IGNORED
                }
            }

            CanvasTool.FILL -> {
                mode = Mode.FILL_TAP
            }
        }
    }

    private fun startStroke(event: MotionEvent, config: CanvasToolConfig, toolType: Int) {
        val currentRenderer = renderer ?: return
        val invertedStylus = toolType == MotionEvent.TOOL_TYPE_ERASER
        val spec = if (config.tool == CanvasTool.ERASER || invertedStylus) config.eraser else config.brush
        val isFinger = toolType == MotionEvent.TOOL_TYPE_FINGER
        val options =
            StrokeOptions(
                smoothing = config.smoothing,
                stringLengthPx = STRING_LENGTH_DP * density / transform.zoom,
                pressureSmoothing = if (isFinger) 0 else STYLUS_PRESSURE_SMOOTHING,
                simulatePressure = isFinger && config.simulateFingerPressure,
                fastSpeedPxPerMs = FAST_SPEED_DP_PER_MS * density,
            )
        val builder = StrokeBuilder(spec, options, event.eventTime)
        stroke = builder
        mode = Mode.STROKE
        toCanvas(event.x, event.y)
        builder.begin(
            coordinates[0],
            coordinates[1],
            event.x,
            event.y,
            pressureOf(event, 0, isFinger),
            event.getAxisValue(MotionEvent.AXIS_TILT, 0),
            event.getAxisValue(MotionEvent.AXIS_ORIENTATION, 0),
            event.eventTime,
        )
        val state = session.state.value
        val opacity = state.project.layer(state.layerId)?.opacity ?: 1f
        currentRenderer.beginWet(spec, config.colorArgb, opacity)
        flushStroke(builder, null)
    }

    private fun pressureOf(event: MotionEvent, pointerIndex: Int, isFinger: Boolean): Float = if (isFinger) {
        1f
    } else {
        event
            .getPressure(
                pointerIndex,
            ).coerceIn(0f, 1f)
    }

    private fun onPointerDown(event: MotionEvent) {
        val elapsed = event.eventTime - downTimeMs
        when (mode) {
            Mode.STROKE -> {
                // A stylus never turns into a gesture: the other touch is a resting hand.
                val isFinger = event.getToolType(event.findPointerIndex(activePointerId).coerceAtLeast(0)) == MotionEvent.TOOL_TYPE_FINGER
                if (isFinger && secondFingerStartsGesture(elapsed, strokeTravel, GESTURE_WINDOW_MS, GESTURE_MAX_TRAVEL_DP * density)) {
                    renderer?.cancelWet()
                    stroke = null
                    beginGesture(event)
                }
            }

            Mode.LASSO, Mode.FILL_TAP, Mode.IGNORED -> {
                overlay.clear()
                beginGesture(event)
            }

            Mode.GESTURE -> {
                tapClassifier.onPointerCount(event.pointerCount)
                anchorGesture(event, -1)
            }

            Mode.SELECTION_DRAG -> {
                session.setSelectionTransform(selectionTransform ?: return)
                selectionHandle = null
                beginGesture(event)
            }

            Mode.IDLE -> {
                beginGesture(event)
            }
        }
    }

    private fun beginGesture(event: MotionEvent) {
        mode = Mode.GESTURE
        tapClassifier.begin(event.eventTime, event.pointerCount)
        anchorGesture(event, -1)
        gestureStartCentroidX = gestureCentroidX
        gestureStartCentroidY = gestureCentroidY
        gestureStartSpan = gestureSpan
    }

    /** Takes the fingers' current position as the new reference, leaving out the one at [skipIndex], which is lifting. */
    private fun anchorGesture(event: MotionEvent, skipIndex: Int) {
        var sumX = 0f
        var sumY = 0f
        var count = 0
        var firstX = 0f
        var firstY = 0f
        var secondX = 0f
        var secondY = 0f
        for (i in 0 until event.pointerCount) {
            if (i == skipIndex) continue
            val x = event.getX(i)
            val y = event.getY(i)
            sumX += x
            sumY += y
            if (count == 0) {
                firstX = x
                firstY = y
            } else if (count == 1) {
                secondX = x
                secondY = y
            }
            count++
        }
        if (count == 0) return
        gestureCentroidX = sumX / count
        gestureCentroidY = sumY / count
        if (count >= 2) {
            gestureSpan = hypot(secondX - firstX, secondY - firstY)
            gestureAngle = atan2(secondY - firstY, secondX - firstX)
        } else {
            gestureSpan = 0f
        }
    }

    private fun onMove(event: MotionEvent) {
        when (mode) {
            Mode.STROKE -> {
                moveStroke(event)
            }

            Mode.LASSO -> {
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return
                for (h in 0 until event.historySize) overlay.add(event.getHistoricalX(index, h), event.getHistoricalY(index, h))
                overlay.add(event.getX(index), event.getY(index))
            }

            Mode.SELECTION_DRAG -> {
                moveSelection(event)
            }

            Mode.GESTURE -> {
                moveGesture(event)
            }

            Mode.FILL_TAP, Mode.IGNORED, Mode.IDLE -> {
                Unit
            }
        }
    }

    private fun moveStroke(event: MotionEvent) {
        val builder = stroke ?: return
        val index = event.findPointerIndex(activePointerId)
        if (index < 0) return
        val isFinger = event.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER
        // Fast strokes arrive as several samples in one event; using them all is what keeps curves round.
        for (h in 0 until event.historySize) {
            val x = event.getHistoricalX(index, h)
            val y = event.getHistoricalY(index, h)
            toCanvas(x, y)
            builder.add(
                coordinates[0],
                coordinates[1],
                x,
                y,
                if (isFinger) 1f else event.getHistoricalPressure(index, h).coerceIn(0f, 1f),
                event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, index, h),
                event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, index, h),
                event.getHistoricalEventTime(h),
            )
        }
        val x = event.getX(index)
        val y = event.getY(index)
        toCanvas(x, y)
        builder.add(
            coordinates[0],
            coordinates[1],
            x,
            y,
            pressureOf(event, index, isFinger),
            event.getAxisValue(MotionEvent.AXIS_TILT, index),
            event.getAxisValue(MotionEvent.AXIS_ORIENTATION, index),
            event.eventTime,
        )
        strokeTravel = maxOf(strokeTravel, hypot(x - downX, y - downY))
        flushStroke(builder, predictor?.predict())
    }

    /** Hands finished stamps to the renderer, along with a throw-away guess at where the pen is going. */
    private fun flushStroke(builder: StrokeBuilder, predicted: MotionEvent?) {
        builder.clearPrediction()
        if (predicted != null) {
            val index = predicted.findPointerIndex(activePointerId)
            if (index >= 0) {
                for (h in 0 until predicted.historySize) {
                    toCanvas(predicted.getHistoricalX(index, h), predicted.getHistoricalY(index, h))
                    builder.addPredicted(coordinates[0], coordinates[1])
                }
                toCanvas(predicted.getX(index), predicted.getY(index))
                builder.addPredicted(coordinates[0], coordinates[1])
            }
            predicted.recycle()
        }
        builder.buildPrediction()
        val real = if (builder.stamps.count == 0) StampRun.EMPTY else builder.stamps.snapshot()
        builder.stamps.clear()
        val guess = if (builder.predictedStamps.count == 0) StampRun.EMPTY else builder.predictedStamps.snapshot()
        renderer?.drawWet(real, guess)
    }

    private fun moveSelection(event: MotionEvent) {
        val handle = selectionHandle ?: return
        val current = selectionTransform ?: return
        val index = event.findPointerIndex(activePointerId)
        if (index < 0) return
        toCanvas(event.getX(index), event.getY(index))
        val next = current.dragged(handle, lastCanvasX, lastCanvasY, coordinates[0], coordinates[1])
        lastCanvasX = coordinates[0]
        lastCanvasY = coordinates[1]
        selectionTransform = next
        session.setSelectionTransform(next)
    }

    private fun moveGesture(event: MotionEvent) {
        val oldX = gestureCentroidX
        val oldY = gestureCentroidY
        val oldSpan = gestureSpan
        val oldAngle = gestureAngle
        anchorGesture(event, -1)
        val travel = hypot(gestureCentroidX - gestureStartCentroidX, gestureCentroidY - gestureStartCentroidY)
        tapClassifier.onMoved(maxOf(travel, kotlin.math.abs(gestureSpan - gestureStartSpan)))
        if (event.pointerCount < 2 || oldSpan <= 0f || gestureSpan <= 0f) return
        val scale = gestureSpan / oldSpan
        val rotation = Math.toDegrees((gestureAngle - oldAngle).toDouble()).toFloat()
        val project = session.state.value.project
        transform = transform.afterGesture(project.width, project.height, oldX, oldY, gestureCentroidX, gestureCentroidY, scale, rotation)
        userMovedCanvas = true
        publishView()
        renderer?.requestRedraw()
    }

    private fun onPointerUp(event: MotionEvent) {
        if (mode != Mode.GESTURE) {
            // In a stroke, the drawing finger lifting while another stays down still ends the stroke.
            if (event.getPointerId(event.actionIndex) == activePointerId && mode == Mode.STROKE) finishStroke(event, event.actionIndex)
            return
        }
        anchorGesture(event, event.actionIndex)
    }

    private fun onUp(event: MotionEvent) {
        when (mode) {
            Mode.STROKE -> finishStroke(event, 0)
            Mode.LASSO -> finishLasso()
            Mode.FILL_TAP -> finishFill(event)
            Mode.SELECTION_DRAG -> selectionHandle = null
            Mode.GESTURE -> finishGesture(event)
            Mode.IGNORED, Mode.IDLE -> Unit
        }
        mode = Mode.IDLE
        activePointerId = MotionEvent.INVALID_POINTER_ID
    }

    private fun finishStroke(event: MotionEvent, pointerIndex: Int) {
        val builder = stroke ?: return
        stroke = null
        val cancelled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (event.flags and MotionEvent.FLAG_CANCELED) != 0
        if (cancelled) {
            // The system decided this touch was accidental, such as a palm.
            renderer?.cancelWet()
            mode = Mode.IDLE
            return
        }
        val isFinger = event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_FINGER
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        toCanvas(x, y)
        builder.add(
            coordinates[0],
            coordinates[1],
            x,
            y,
            pressureOf(event, pointerIndex, isFinger),
            event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex),
            event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex),
            event.eventTime,
        )
        builder.finish()
        val real = if (builder.stamps.count == 0) StampRun.EMPTY else builder.stamps.snapshot()
        builder.stamps.clear()
        renderer?.drawWet(real, StampRun.EMPTY)
        renderer?.commitWet()
        mode = Mode.IDLE
    }

    private fun finishLasso() {
        val points = overlay.finish()
        if (points.size < MIN_LASSO_COORDINATES) return
        val canvasPoints = FloatArray(points.size)
        var i = 0
        while (i < points.size) {
            toCanvas(points[i], points[i + 1])
            canvasPoints[i] = coordinates[0]
            canvasPoints[i + 1] = coordinates[1]
            i += 2
        }
        session.liftSelection(canvasPoints)
    }

    private fun finishFill(event: MotionEvent) {
        if (hypot(event.x - downX, event.y - downY) > tapSlop * 2f) return
        val state = session.state.value
        if (!state.canDrawOnActiveLayer) {
            listener.onLayerNotDrawable()
            return
        }
        val config = toolConfig()
        toCanvas(event.x, event.y)
        session.fill(
            coordinates[0].toInt(),
            coordinates[1].toInt(),
            config.fillColorArgb,
            config.fillTolerance,
            config.fillGapClose,
            config.fillSampleAllLayers,
        )
    }

    private fun finishGesture(event: MotionEvent) {
        if (userMovedCanvas) {
            transform = transform.snappedRotation()
            publishView()
            renderer?.requestRedraw()
        }
        when (tapClassifier.end(event.eventTime)) {
            TapGesture.TWO_FINGER_TAP -> {
                if (doubleTap.onTwoFingerTap(event.eventTime)) {
                    pendingUndo?.let(::removeCallbacks)
                    pendingUndo = null
                    fitToScreen()
                } else {
                    val undo =
                        Runnable {
                            pendingUndo = null
                            session.undo()
                        }
                    pendingUndo = undo
                    postDelayed(undo, doubleTap.delayMs)
                }
            }

            TapGesture.THREE_FINGER_TAP -> {
                session.redo()
            }

            TapGesture.NONE -> {
                Unit
            }
        }
    }

    private fun onCancel() {
        when (mode) {
            Mode.STROKE -> {
                stroke = null
                renderer?.cancelWet()
            }

            Mode.LASSO -> {
                overlay.clear()
            }

            else -> {
                Unit
            }
        }
        selectionHandle = null
        mode = Mode.IDLE
        activePointerId = MotionEvent.INVALID_POINTER_ID
    }

    private fun toCanvas(viewX: Float, viewY: Float) {
        val project = session.state.value.project
        val inverse = transform.toAffine(project.width, project.height).inverse()
        coordinates[0] = inverse?.mapX(viewX, viewY) ?: viewX
        coordinates[1] = inverse?.mapY(viewX, viewY) ?: viewY
    }

    /** Draws the lasso outline as it is being traced, in view coordinates. A plain view is the cheapest way to do this. */
    private class LassoOverlay(context: Context) : View(context) {
        private val path = Path()
        private val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f * context.resources.displayMetrics.density
            }
        private val dash = 6f * context.resources.displayMetrics.density
        private val effect = DashPathEffect(floatArrayOf(dash, dash), 0f)
        private var points = FloatArray(256)
        private var size = 0

        fun begin(x: Float, y: Float) {
            size = 0
            path.reset()
            path.moveTo(x, y)
            store(x, y)
            invalidate()
        }

        fun add(x: Float, y: Float) {
            path.lineTo(x, y)
            store(x, y)
            invalidate()
        }

        fun finish(): FloatArray {
            val result = points.copyOf(size)
            clear()
            return result
        }

        fun clear() {
            size = 0
            path.reset()
            invalidate()
        }

        private fun store(x: Float, y: Float) {
            if (size + 2 > points.size) points = points.copyOf(points.size * 2)
            points[size++] = x
            points[size++] = y
        }

        override fun onDraw(canvas: Canvas) {
            if (size == 0) return
            paint.pathEffect = null
            paint.color = Color.WHITE
            canvas.drawPath(path, paint)
            paint.pathEffect = effect
            paint.color = LASSO_COLOR
            canvas.drawPath(path, paint)
        }
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4L
        const val OVERSCAN_ALLOWANCE = 2L
        const val HARDWARE_BITMAP_LIMIT = 100L * 1024 * 1024
        const val FIT_PADDING_DP = 12f
        const val TOUCH_RADIUS_DP = 28f
        const val STRING_LENGTH_DP = 28f
        const val STYLUS_PRESSURE_SMOOTHING = 20
        const val FAST_SPEED_DP_PER_MS = 1.6f
        const val GESTURE_WINDOW_MS = 160L
        const val GESTURE_MAX_TRAVEL_DP = 24f
        const val MIN_LASSO_COORDINATES = 6
        const val LASSO_COLOR = 0xFF0F6E6E.toInt()
    }
}
