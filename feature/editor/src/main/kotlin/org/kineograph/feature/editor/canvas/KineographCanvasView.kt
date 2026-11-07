package org.kineograph.feature.editor.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
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
import org.kineograph.engine.selection.Affine
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

/** Builds the low-latency renderer. A separate step so that tests can stand in for the library, which needs a real device. */
internal fun interface LowLatencyFactory {
    fun create(surfaceView: SurfaceView, view: Affine, onFailure: () -> Unit): CanvasRenderer
}

/**
 * The surface the canvas draws on. The low-latency library signs up for surface events through [getHolder], and would then rebuild
 * its buffers by itself when the size changes. A failure there could not be caught, a commit under way would never complete and
 * so stop all later ones, and it would draw frames of its own during a stroke, which hides the stroke. Instead, the view replaces
 * the whole renderer on those events, so the holder the library gets ignores its sign-ups. Everything else about it is real.
 */
private class CanvasSurfaceView(context: Context) : SurfaceView(context) {
    /** The holder for the view itself, which really does hear about surface events. */
    val eventsHolder: SurfaceHolder = super.getHolder()

    private val libraryHolder =
        object : SurfaceHolder by eventsHolder {
            override fun addCallback(callback: SurfaceHolder.Callback) = Unit

            override fun removeCallback(callback: SurfaceHolder.Callback) = Unit
        }

    override fun getHolder(): SurfaceHolder = libraryHolder
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
    workspaceColor: Int,
    private val toolConfig: () -> CanvasToolConfig,
    private val listener: CanvasListener,
    private val lowLatencyFactory: LowLatencyFactory =
        LowLatencyFactory { surfaceView, view, onFailure ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw UnsupportedOperationException("Low latency needs Android 10")
            FrontBufferRenderer(surfaceView, session, workspaceColor, view, onFailure)
        },
) : FrameLayout(context), PlaybackDisplay {
    private var workspaceColor = workspaceColor
    private val surfaceView = CanvasSurfaceView(context)
    private val overlay = LassoOverlay(context)
    private val density = resources.displayMetrics.density
    private val tapSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val tapClassifier = TapClassifier(tapSlop * 1.5f)
    private val doubleTap = DoubleTapResolver()
    private var pendingUndo: Runnable? = null

    /** The renderer, or null while there is no surface. Read on the engine thread too. Readable for tests. */
    @Volatile
    internal var renderer: CanvasRenderer? = null
        private set
    private var rendererIsLowLatency = false
    private var rendererGeneration = 0
    private var lowLatencyFailed = false
    private var hasSurface = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceRotation = Surface.ROTATION_0

    /** The renderer should be replaced, but a stroke is on it. That can wait for the stroke to end. */
    private var rendererOutOfDate = false

    /** The canvas changed size during a stroke, which keeps the view it started with until it ends. */
    private var canvasSizeOutOfDate = false
    private var playbackFrame: Bitmap? = null
    private var predictor: MotionEventPredictor? = null

    private var transform = ViewTransform()
    private var userMovedCanvas = false

    /**
     * The canvas-to-view transform the renderer has. Touches always go through this one, so that they land where the user sees the
     * canvas, even in the moment after the project changes size and before the view has caught up.
     */
    private var publishedView = Affine.IDENTITY
    private var publishedInverse: Affine? = null

    private enum class Mode { IDLE, STROKE, PEN_WAITING, LASSO, FILL_TAP, SELECTION_DRAG, GESTURE, IGNORED }

    private var mode = Mode.IDLE
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var downTimeMs = 0L
    private var downX = 0f
    private var downY = 0f
    private var strokeTravel = 0f

    private var stroke: StrokeBuilder? = null
    private var strokeErases = false
    private val coordinates = FloatArray(2)

    /** Whether the pen asked to erase, by its button or by being its eraser end, as of the last event of this touch. */
    private var penErasing = false

    /** The event being handled came with one of Samsung's own actions, which mean the pen's button is held. */
    private var sPenActionHeld = false

    // The latest sample of this touch. Every stroke starts from here, which is what joins one up with the stroke it carries on from.
    private var sampleX = 0f
    private var sampleY = 0f
    private var samplePressure = 0f
    private var sampleTilt = 0f
    private var sampleOrientation = 0f
    private var sampleTimeMs = 0L

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

    /**
     * True while playback is running, when touches only serve to stop it. From then on a touch under way would never get
     * its ending, and a stroke that never ends leaves the session ignoring everything else, so it is called off here.
     * Playback is a preview of what will be exported, so it shows the whole canvas, upright. Where the user had placed the canvas
     * is kept, not overwritten, so stopping goes back to it, and it goes on following changes of window size meanwhile.
     */
    internal var isPlaying: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                onCancel()
                tappingPlayback = false
            }
            publishView()
            renderer?.requestRedraw()
        }

    /** Only a touch that began during playback is a tap on it. A finger that was already drawing is just lifting. */
    private var tappingPlayback = false

    private val surfaceEvents =
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                hasSurface = true
                surfaceWidth = surfaceView.width
                surfaceHeight = surfaceView.height
                surfaceRotation = currentRotation()
                buildRenderer()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                val rotation = currentRotation()
                if (width == surfaceWidth && height == surfaceHeight && rotation == surfaceRotation) return
                surfaceWidth = width
                surfaceHeight = height
                surfaceRotation = rotation
                replaceRenderer()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                hasSurface = false
                // A touch that never got its ending would leave the session waiting for the stroke, ignoring everything else.
                onCancel()
                releaseRenderer()
            }
        }

    init {
        // Set once, here, because changing it later recreates the surface. Only the software path uses the surface's own buffers.
        surfaceView.eventsHolder.setFormat(PixelFormat.RGBA_8888)
        surfaceView.eventsHolder.addCallback(surfaceEvents)
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        tag = "canvas"
        contentDescription = context.getString(org.kineograph.feature.editor.R.string.canvas_description)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        predictor = createPredictor()
        session.sceneListener = { renderer?.requestRedraw() }
        publishView()
    }

    override fun onDetachedFromWindow() {
        hasSurface = false
        // A touch that never got its ending would leave the session waiting for the stroke, ignoring everything else.
        onCancel()
        session.sceneListener = null
        releaseRenderer()
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

    private fun currentRotation(): Int = surfaceView.display?.rotation ?: Surface.ROTATION_0

    /** Bitmaps over 100 MB cannot go through the hardware canvas at all, so giant canvases draw in software whatever the device. */
    private fun wantsLowLatency(): Boolean {
        val project = session.state.value.project
        return lowLatency &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !lowLatencyFailed &&
            fitsHardwareCanvas(project.width, project.height)
    }

    /** Puts a fresh renderer on the surface, if there is one, showing what the old one showed. Never call during a stroke. */
    private fun buildRenderer() {
        rendererOutOfDate = false
        releaseRenderer()
        if (!hasSurface) return
        val next = createRenderer()
        next.setView(publishedView)
        next.setWorkspaceColor(workspaceColor)
        // In place before the first redraw, so that no later change to the scene can miss both.
        renderer = next
        playbackFrame?.let(next::setPlaybackFrame)
        next.requestRedraw()
    }

    /** Any failure setting up low latency falls back to software, which works everywhere, and stays there. */
    private fun createRenderer(): CanvasRenderer {
        val generation = ++rendererGeneration
        if (wantsLowLatency()) {
            try {
                val created = lowLatencyFactory.create(surfaceView, publishedView) { post { onRendererFailed(generation) } }
                rendererIsLowLatency = true
                return created
            } catch (_: RuntimeException) {
                lowLatencyFailed = true
            } catch (_: LinkageError) {
                // The library's native code could not be loaded on this device.
                lowLatencyFailed = true
            } catch (_: OutOfMemoryError) {
                lowLatencyFailed = true
            }
        }
        rendererIsLowLatency = false
        return SoftwareRenderer(surfaceView, session, workspaceColor)
    }

    private fun releaseRenderer() {
        val old = renderer ?: return
        renderer = null
        old.release()
    }

    /** Follows the theme, which can change with the app open. */
    internal fun setWorkspaceColor(color: Int) {
        if (color == workspaceColor) return
        workspaceColor = color
        renderer?.setWorkspaceColor(color)
    }

    /** Failures on the render thread land here. One from a renderer that has since been replaced no longer matters. */
    private fun onRendererFailed(generation: Int) {
        if (generation != rendererGeneration || !rendererIsLowLatency) return
        lowLatencyFailed = true
        replaceRenderer()
    }

    private fun replaceRenderer() {
        if (mode == Mode.STROKE) rendererOutOfDate = true else buildRenderer()
    }

    /**
     * Call when the project's canvas size has changed, by project settings, undo or redo. The renderer may no longer suit it, and
     * its view is out of date. If the user has placed the canvas, it stays where it is, as the artwork stays centred on the canvas.
     */
    internal fun onCanvasSizeChanged() {
        if (mode == Mode.STROKE) {
            canvasSizeOutOfDate = true
            return
        }
        if (userMovedCanvas) {
            publishView()
            renderer?.requestRedraw()
        } else {
            fitToScreen()
        }
        if (hasSurface && wantsLowLatency() != rendererIsLowLatency) buildRenderer()
    }

    /** Catches up on what had to wait for a stroke to end. */
    private fun afterStroke() {
        if (canvasSizeOutOfDate) {
            canvasSizeOutOfDate = false
            onCanvasSizeChanged()
        }
        if (rendererOutOfDate) buildRenderer()
    }

    override val supportsHardwareBitmaps: Boolean get() = renderer?.supportsHardwareBitmaps == true

    /** Renderers come and go during playback, for example with the surface, so the frame is kept here for the next one. */
    override fun setPlaybackFrame(frame: Bitmap?) {
        playbackFrame = frame
        renderer?.setPlaybackFrame(frame)
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

    /**
     * The scale to decode frames at for playback, for the size the canvas will have by then. The system bars go, so that is nearly the
     * whole window. Slightly too large only costs a little memory, where too small would leave every frame soft.
     */
    internal fun fitScale(): Float {
        val project = session.state.value.project
        val window = rootView
        val availableWidth = maxOf(width, window.width).coerceAtLeast(1)
        val availableHeight = maxOf(height, window.height).coerceAtLeast(1)
        return ViewTransform.fit(availableWidth, availableHeight, project.width, project.height, 0f).zoom
    }

    private fun publishView() {
        val project = session.state.value.project
        val shown =
            if (isPlaying) {
                ViewTransform.fit(width.coerceAtLeast(1), height.coerceAtLeast(1), project.width, project.height, 0f)
            } else {
                transform
            }
        val view = shown.toAffine(project.width, project.height)
        publishedView = view
        publishedInverse = view.inverse()
        renderer?.setView(view)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        handleTouch(event)
        return true
    }

    /**
     * Samsung's private pen actions arrive here and never as touches, because the AndroidView hosting this view only passes the usual
     * actions on as touches. Stock Android does not count them as touches in the first place.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val standardAction =
            standardActionOfSPen(event.actionMasked, event.pointerCount, mode != Mode.IDLE) ?: return super.onGenericMotionEvent(event)
        // A copy, so that everything from here on, the predictor included, only ever sees the usual actions.
        val standard = MotionEvent.obtain(event)
        standard.action = standardAction or (event.action and MotionEvent.ACTION_POINTER_INDEX_MASK)
        sPenActionHeld = true
        handleTouch(standard)
        sPenActionHeld = false
        standard.recycle()
        return true
    }

    private fun handleTouch(event: MotionEvent) {
        if (isPlaying) {
            onPlaybackTouch(event)
            return
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
    }

    /**
     * Only a tap stops playback. With the system bars hidden, the swipe in from the edge that brings them back for a moment reaches this
     * view too, and is about the system, not the animation.
     */
    private fun onPlaybackTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tappingPlayback = true
                downX = event.x
                downY = event.y
            }

            MotionEvent.ACTION_MOVE -> {
                if (movedPastTap(event)) tappingPlayback = false
            }

            MotionEvent.ACTION_UP -> {
                // A quick flick can lift without ever having sent a move.
                if (tappingPlayback && !movedPastTap(event)) listener.onPlaybackTapped()
                tappingPlayback = false
            }

            MotionEvent.ACTION_CANCEL -> {
                tappingPlayback = false
            }
        }
    }

    private fun movedPastTap(event: MotionEvent, pointerIndex: Int = 0): Boolean =
        hypot(event.getX(pointerIndex) - downX, event.getY(pointerIndex) - downY) > tapSlop * 2f

    private fun onDown(event: MotionEvent) {
        requestUnbufferedDispatch(event)
        parent?.requestDisallowInterceptTouchEvent(true)
        activePointerId = event.getPointerId(0)
        downTimeMs = event.eventTime
        downX = event.x
        downY = event.y
        strokeTravel = 0f
        rememberSample(event, 0)
        penErasing = penErases(event, 0)
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

        val tool = if (penErasing) CanvasTool.ERASER else config.tool
        when (tool) {
            CanvasTool.BRUSH, CanvasTool.ERASER -> {
                if (state.canDrawOnActiveLayer) {
                    val spec = if (tool == CanvasTool.ERASER) config.eraser else config.brush
                    startStroke(config, spec, isFinger = event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER)
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

    /** Starts a stroke at the latest sample, which is where the pen landed, or where it had got to when its button changed. */
    private fun startStroke(config: CanvasToolConfig, spec: BrushSpec, isFinger: Boolean) {
        val currentRenderer = renderer ?: return
        val options =
            StrokeOptions(
                smoothing = config.smoothing,
                stringLengthPx = STRING_LENGTH_DP * density / transform.zoom,
                pressureSmoothing = if (isFinger) 0 else STYLUS_PRESSURE_SMOOTHING,
                simulatePressure = isFinger && config.simulateFingerPressure,
                fastSpeedPxPerMs = FAST_SPEED_DP_PER_MS * density,
            )
        val builder = StrokeBuilder(spec, options, sampleTimeMs)
        stroke = builder
        strokeErases = spec.isEraser
        mode = Mode.STROKE
        downX = sampleX
        downY = sampleY
        strokeTravel = 0f
        toCanvas(sampleX, sampleY)
        builder.begin(coordinates[0], coordinates[1], sampleX, sampleY, samplePressure, sampleTilt, sampleOrientation, sampleTimeMs)
        val state = session.state.value
        val opacity = state.project.layer(state.layerId)?.opacity ?: 1f
        currentRenderer.beginWet(spec, config.colorArgb, opacity)
        flushStroke(builder, null)
    }

    private fun rememberSample(event: MotionEvent, pointerIndex: Int) {
        sampleX = event.getX(pointerIndex)
        sampleY = event.getY(pointerIndex)
        samplePressure = pressureOf(event, pointerIndex, event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_FINGER)
        sampleTilt = event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex)
        sampleOrientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex)
        sampleTimeMs = event.eventTime
    }

    private fun penErases(event: MotionEvent, pointerIndex: Int): Boolean {
        val buttons = if (sPenActionHeld) event.buttonState or MotionEvent.BUTTON_STYLUS_PRIMARY else event.buttonState
        return penAsksToErase(event.getToolType(pointerIndex), buttons)
    }

    /**
     * Keeps up with the pen while it is on the screen. People press the button after touching, because on Samsung phones pressing it
     * during a hover can open Air Command. An event has one button state for all of its samples, so the change counts from where
     * the last event left off, and all of this event goes to the new mode.
     */
    private fun followPenButton(event: MotionEvent, pointerIndex: Int) {
        val erasing = penErases(event, pointerIndex)
        if (erasing == penErasing) return
        penErasing = erasing
        if (erasing) startPenErasing() else stopPenErasing()
    }

    private fun startPenErasing() {
        when (mode) {
            Mode.STROKE -> {
                if (strokeErases) return
                // A touch that has not left its spot yet is the start of erasing, not a drawing, so it leaves no dot and no undo step.
                endPenStroke(keep = strokeTravel > tapSlop)
            }

            Mode.LASSO -> {
                overlay.clear()
            }

            Mode.FILL_TAP, Mode.PEN_WAITING -> {
                Unit
            }

            // A selection's handles and a pinch keep the touch they already have, and a refused touch has had its message.
            Mode.IDLE, Mode.SELECTION_DRAG, Mode.GESTURE, Mode.IGNORED -> {
                return
            }
        }
        val config = toolConfig()
        startPenStroke(config, config.eraser)
    }

    private fun stopPenErasing() {
        if (mode == Mode.STROKE && toolConfig().tool != CanvasTool.ERASER) endPenStroke(keep = true)
    }

    /** Ends the part of the touch that was drawing. The renderer is free from here on, so anything that waited for it can go ahead. */
    private fun endPenStroke(keep: Boolean) {
        val builder = stroke ?: return
        stroke = null
        if (keep) commitStroke(builder) else renderer?.cancelWet()
        mode = Mode.PEN_WAITING
        afterStroke()
    }

    private fun startPenStroke(config: CanvasToolConfig, spec: BrushSpec) {
        if (session.state.value.canDrawOnActiveLayer) {
            startStroke(config, spec, isFinger = false)
        } else {
            listener.onLayerNotDrawable()
            mode = Mode.IGNORED
        }
    }

    /**
     * The button has been let go, and drawing only comes back once the pen goes somewhere, starting from where erasing stopped. Letting
     * go a moment before lifting would otherwise leave a dot of ink at the end of what was just erased. The lasso and fill take a whole
     * touch from landing to lifting, and what is left of this one is not that, so they wait for the next.
     */
    private fun resumeDrawing(event: MotionEvent, pointerIndex: Int) {
        if (hypot(event.getX(pointerIndex) - sampleX, event.getY(pointerIndex) - sampleY) <= tapSlop) return
        val config = toolConfig()
        if (config.tool == CanvasTool.BRUSH) {
            startPenStroke(config, config.brush)
            if (mode == Mode.STROKE) moveStroke(event)
        } else {
            // Nothing is left to join up with, and pressing the button again has to erase from where the pen has got to.
            rememberSample(event, pointerIndex)
        }
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
        // A stylus never turns into a gesture, whatever it is doing: the other touch is a resting hand, and the stylus carries on.
        val isFinger = event.getToolType(event.findPointerIndex(activePointerId).coerceAtLeast(0)) == MotionEvent.TOOL_TYPE_FINGER
        when (mode) {
            Mode.STROKE -> {
                if (isFinger && secondFingerStartsGesture(elapsed, strokeTravel, GESTURE_WINDOW_MS, GESTURE_MAX_TRAVEL_DP * density)) {
                    renderer?.cancelWet()
                    stroke = null
                    beginGesture(event)
                    afterStroke()
                }
            }

            Mode.LASSO, Mode.FILL_TAP, Mode.IGNORED -> {
                if (isFinger) {
                    overlay.clear()
                    beginGesture(event)
                }
            }

            Mode.GESTURE -> {
                tapClassifier.onPointerCount(event.pointerCount)
                anchorGesture(event, -1)
                rebaselineGesture()
            }

            Mode.SELECTION_DRAG -> {
                if (isFinger) {
                    session.setSelectionTransform(selectionTransform ?: return)
                    selectionHandle = null
                    beginGesture(event)
                }
            }

            Mode.IDLE -> {
                beginGesture(event)
            }

            // Only a stylus gets to this mode.
            Mode.PEN_WAITING -> {
                Unit
            }
        }
    }

    private fun beginGesture(event: MotionEvent) {
        mode = Mode.GESTURE
        tapClassifier.begin(event.eventTime, event.pointerCount)
        anchorGesture(event, -1)
        rebaselineGesture()
    }

    /** A finger landing or lifting moves the centroid by itself, which must not count as the fingers having moved. */
    private fun rebaselineGesture() {
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
        val index = event.findPointerIndex(activePointerId)
        if (index >= 0) followPenButton(event, index)
        when (mode) {
            Mode.STROKE -> {
                moveStroke(event)
            }

            Mode.PEN_WAITING -> {
                if (index >= 0) resumeDrawing(event, index)
            }

            Mode.LASSO -> {
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
        // While the pen waits, this stays where erasing stopped, which is where drawing has to join up.
        if (index >= 0 && (mode == Mode.STROKE || mode == Mode.LASSO || mode == Mode.FILL_TAP)) rememberSample(event, index)
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
        val lowestZoom =
            ViewTransform.lowestZoom(
                width.coerceAtLeast(1),
                height.coerceAtLeast(1),
                project.width,
                project.height,
                FIT_PADDING_DP * density,
            )
        transform =
            transform.afterGesture(
                project.width,
                project.height,
                oldX,
                oldY,
                gestureCentroidX,
                gestureCentroidY,
                scale,
                rotation,
                lowestZoom,
            )
        userMovedCanvas = true
        publishView()
        renderer?.requestRedraw()
    }

    private fun onPointerUp(event: MotionEvent) {
        if (mode != Mode.GESTURE) {
            // Whatever the touch was doing ends when its own finger or pen lifts, even with another still down.
            if (event.getPointerId(event.actionIndex) == activePointerId) finishTouch(event, event.actionIndex)
            return
        }
        anchorGesture(event, event.actionIndex)
        rebaselineGesture()
    }

    private fun onUp(event: MotionEvent) {
        finishTouch(event, 0)
        activePointerId = MotionEvent.INVALID_POINTER_ID
    }

    private fun finishTouch(event: MotionEvent, pointerIndex: Int) {
        when (mode) {
            Mode.STROKE -> finishStroke(event, pointerIndex)
            Mode.LASSO -> finishLasso()
            Mode.FILL_TAP -> finishFill(event, pointerIndex)
            Mode.SELECTION_DRAG -> selectionHandle = null
            Mode.GESTURE -> finishGesture(event)
            Mode.PEN_WAITING, Mode.IGNORED, Mode.IDLE -> Unit
        }
        mode = Mode.IDLE
    }

    private fun finishStroke(event: MotionEvent, pointerIndex: Int) {
        val builder = stroke ?: return
        stroke = null
        val cancelled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (event.flags and MotionEvent.FLAG_CANCELED) != 0
        if (cancelled) {
            // The system decided this touch was accidental, such as a palm.
            renderer?.cancelWet()
            mode = Mode.IDLE
            afterStroke()
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
        commitStroke(builder)
        mode = Mode.IDLE
        afterStroke()
    }

    /** Takes the stroke up to its last sample. The final stamps replace what was predicted, so no guess outlives the stroke. */
    private fun commitStroke(builder: StrokeBuilder) {
        builder.finish()
        val real = if (builder.stamps.count == 0) StampRun.EMPTY else builder.stamps.snapshot()
        builder.stamps.clear()
        renderer?.drawWet(real, StampRun.EMPTY)
        renderer?.commitWet()
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

    private fun finishFill(event: MotionEvent, pointerIndex: Int) {
        if (movedPastTap(event, pointerIndex)) return
        val state = session.state.value
        if (!state.canDrawOnActiveLayer) {
            listener.onLayerNotDrawable()
            return
        }
        val config = toolConfig()
        toCanvas(event.getX(pointerIndex), event.getY(pointerIndex))
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
        afterStroke()
    }

    private fun toCanvas(viewX: Float, viewY: Float) {
        val inverse = publishedInverse
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
