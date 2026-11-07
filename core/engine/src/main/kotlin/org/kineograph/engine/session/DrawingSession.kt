package org.kineograph.engine.session

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kineograph.engine.brush.BoundsAccumulator
import org.kineograph.engine.brush.BrushSpec
import org.kineograph.engine.brush.StampPainter
import org.kineograph.engine.brush.StampRun
import org.kineograph.engine.brush.TipCache
import org.kineograph.engine.compositor.BackgroundPainter
import org.kineograph.engine.compositor.FlattenOptions
import org.kineograph.engine.compositor.FloatingDraw
import org.kineograph.engine.compositor.FrameFlattener
import org.kineograph.engine.compositor.GhostDraw
import org.kineograph.engine.compositor.LayerDraw
import org.kineograph.engine.compositor.Scene
import org.kineograph.engine.compositor.WetDraw
import org.kineograph.engine.fill.FloodFill
import org.kineograph.engine.history.BackgroundEntry
import org.kineograph.engine.history.History
import org.kineograph.engine.history.HistoryCursor
import org.kineograph.engine.history.PatchPayload
import org.kineograph.engine.history.PixelEntry
import org.kineograph.engine.history.StructureEntry
import org.kineograph.engine.selection.SelectionTransform
import org.kineograph.model.Background
import org.kineograph.model.Cel
import org.kineograph.model.CelKey
import org.kineograph.model.FrameClipboard
import org.kineograph.model.IntRect
import org.kineograph.model.Layer
import org.kineograph.model.Project
import org.kineograph.model.ProjectEdits
import org.kineograph.model.ProjectSettings
import org.kineograph.model.newId
import org.kineograph.model.planOnionSkin
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.max
import kotlin.math.min

/** What the rest of the app can observe about an open project. */
public data class SessionState(
    val ready: Boolean = false,
    val project: Project,
    val frameId: String,
    val layerId: String,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Goes up whenever the saved form of the project would change. */
    val revision: Long = 0,
    /** Where the lifted selection is now, when there is one. */
    val selection: SelectionTransform? = null,
    val hasFrameClipboard: Boolean = false,
    val busy: Boolean = false,
    /** There was not enough memory to open this canvas, so nothing can be drawn until [DrawingSession.retryOpen] succeeds. */
    val openFailed: Boolean = false,
) {
    /** A stroke can only go on a layer that is visible and unlocked. */
    val canDrawOnActiveLayer: Boolean
        get() = project.layer(layerId)?.let { it.visible && !it.locked } ?: false
}

/** Things that happened which the user should hear about. */
public sealed interface SessionEvent {
    /** Memory ran out. Caches were emptied and the app carried on. */
    public data object LowMemory : SessionEvent

    /** The drawing at the current position could not be read, so editing it is refused rather than replacing it. */
    public data object CelNotLoaded : SessionEvent

    /** Clearing left a layer alone because it is hidden or locked. */
    public data object LayerNotEditable : SessionEvent

    /** A step saved to the cache directory has gone, so undo or redo cannot go back any further. */
    public data object UndoStepsLost : SessionEvent

    /** A layer other than the active one could not be read, so it is missing from the view. Its drawing has not changed. */
    public data object LayerNotShown : SessionEvent

    /** A change of canvas size, by a resize, undo or redo, does not fit in memory, so the canvas stays as it was. */
    public data object CanvasDoesNotFit : SessionEvent
}

/**
 * The one open project: its live pixels, its undo history and its current position.
 *
 * All state here belongs to one background thread, the engine thread. Public methods just queue work onto
 * that thread, so commands run strictly in the order they arrive: a stroke that starts right after a frame
 * change lands on the new frame. Nothing here ever blocks the UI.
 *
 * Only the active cel is a full-size mutable bitmap (the "surface", the canvas plus a margin you can draw
 * into). Other layers on this frame stay as small crops, and other frames stay on disk or in the store's
 * compressed cache.
 */
public class DrawingSession(
    initial: Project,
    private val store: CelStore,
    private val budget: MemoryBudget,
    private val spillDirectory: File,
    /** Makes the full-size surfaces. Tests replace it to run out of memory on demand. */
    private val allocateSurface: (width: Int, height: Int) -> Bitmap = { width, height ->
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    },
) {
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "kg-engine").apply { priority = Thread.NORM_PRIORITY + 1 } }

    /** The engine thread, for callers that need to wait for a result from it. */
    public val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    private val workers = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Tip images, shared with the renderer so the GPU and CPU paint with identical stamps. */
    public val tips: TipCache = TipCache()

    private val _state =
        MutableStateFlow(
            SessionState(
                project = initial,
                frameId = initial.frames.first().id,
                layerId = initial.layers.last().id,
            ),
        )
    public val state: StateFlow<SessionState> get() = _state

    private val _events = Channel<SessionEvent>(capacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Things to tell the user. They wait here until collected, so nothing that happens while opening is missed. Collect it once. */
    public val events: Flow<SessionEvent> = _events.receiveAsFlow()

    /** The latest thing to draw. Null until the project has loaded. */
    @Volatile
    public var scene: Scene? = null
        private set

    /** Called on the engine thread when the scene changes and should be redrawn. */
    @Volatile
    public var sceneListener: (() -> Unit)? = null

    /** The image an eraser stroke reveals on the front buffer: everything except the active layer. */
    @Volatile
    public var revealImage: Bitmap? = null
        private set

    /** Set by a renderer that shows eraser strokes using [revealImage]. */
    @Volatile
    public var wantsRevealImage: Boolean = false

    /** The colour around the canvas, needed when building [revealImage]. */
    @Volatile
    public var workspaceColor: Int = 0xFF808080.toInt()

    private var project: Project = initial
    private var frameId: String = _state.value.frameId
    private var layerId: String = _state.value.layerId
    private var revision = 0L
    private var ready = false
    private var busy = false
    private var openFailed = false
    private var closed = false

    private var overscan = 0
    private var surface: Bitmap? = null
    private var surfaceCanvas: Canvas? = null
    private var strokeBuffer: Bitmap? = null
    private var strokeCanvas: Canvas? = null
    private var backgroundImage: Bitmap? = null

    private val liveCels = HashMap<String, LiveCel>()

    /**
     * Which cel the surface holds, or null if it holds nothing usable because a load failed. Pixels on the surface may
     * only be edited or saved while this matches the current position: otherwise they would land on the wrong drawing.
     */
    private var loadedKey: CelKey? = null
    private var loadedFile: String? = null
    private var activeDirty = false

    /** A visible layer on this frame could not be read when the view was last built, so going to the frame again tries again. */
    private var layerLeftOut = false

    /** Everything inked on the surface is inside this rectangle, in surface coordinates. It may be loose. */
    private var contentBounds = IntRect.EMPTY

    private val history = History(budget.historyMemoryBytes, budget.historyDiskBytes, MIN_UNDO_STEPS, spillDirectory)
    private var stroke: ActiveStroke? = null
    private var wet: WetDraw? = null
    private var floating: Floating? = null
    private var frameClipboard: FrameClipboard? = null
    private var opacityPreview: OpacityPreview? = null

    private var ghosts: List<GhostDraw> = emptyList()
    private var ghostJob: Job? = null
    private var ghostGeneration = 0L
    private val ghostCache: MutableMap<String, Bitmap> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > MAX_CACHED_GHOSTS
            },
        )

    private var sceneGeneration = 0L
    private var revealGeneration = -1L
    private var revealBitmap: Bitmap? = null

    private val stampPainter = StampPainter(tips)
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val commitPaint = Paint()
    private val eraseMode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    init {
        post { open() }
    }

    // ----- Strokes -----

    /** Starts a stroke on the active layer. Stamps arrive through [addStamps]. */
    public fun beginStroke(spec: BrushSpec, colorArgb: Int) {
        post {
            commitFloatingAndPublish()
            if (!ensureSurfaceLoaded()) return@post
            val buffer = strokeBuffer ?: return@post
            stroke = ActiveStroke(spec, colorArgb)
            if (spec.isEraser && wantsRevealImage) buildRevealImage()
            wet = WetDraw(buffer, -overscan, -overscan, spec.opacity, spec.isEraser)
            publishScene(notify = false)
        }
    }

    /** Paints stamps into the stroke buffer, then runs [afterRaster] on the engine thread. */
    public fun addStamps(run: StampRun, afterRaster: (() -> Unit)? = null) {
        post {
            val current = stroke
            val canvas = strokeCanvas
            if (current != null && canvas != null) {
                val checkpoint = canvas.save()
                canvas.translate(overscan.toFloat(), overscan.toFloat())
                stampPainter.paint(canvas, current.spec, current.colorArgb, run)
                canvas.restoreToCount(checkpoint)
                current.bounds.add(run)
            }
            afterRaster?.invoke()
        }
    }

    /** Finishes the stroke, merging it into the layer as one undo step, and then runs [onCommitted]. */
    public fun endStroke(onCommitted: () -> Unit) {
        post {
            try {
                commitStroke()
            } finally {
                onCommitted()
            }
        }
    }

    /** Throws the stroke away without touching the layer. Used when a touch turns out to be a gesture or a palm. */
    public fun cancelStroke(onCancelled: (() -> Unit)? = null) {
        post {
            stroke?.let { clearStrokeBuffer(it) }
            stroke = null
            wet = null
            publishScene(notify = false)
            onCancelled?.invoke()
        }
    }

    private fun commitStroke() {
        val current = stroke ?: return
        stroke = null
        wet = null
        val target = surface
        val targetCanvas = surfaceCanvas
        val buffer = strokeBuffer
        if (target == null || targetCanvas == null || buffer == null) return
        val rect = surfaceRectOf(current.bounds)
        if (!rect.isEmpty) {
            val before = readPixelsForUndo(target, rect)
            commitPaint.alpha = (current.spec.opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            commitPaint.xfermode = if (current.spec.isEraser) eraseMode else null
            val checkpoint = targetCanvas.save()
            targetCanvas.clipRect(rect.left, rect.top, rect.right, rect.bottom)
            targetCanvas.drawBitmap(buffer, 0f, 0f, commitPaint)
            targetCanvas.restoreToCount(checkpoint)
            clearStrokeBuffer(current)
            if (!current.spec.isEraser) contentBounds = contentBounds.union(rect)
            recordPixelEdit(rect, before)
        }
        publishScene(notify = false)
        publishState()
    }

    private fun clearStrokeBuffer(finished: ActiveStroke) {
        val canvas = strokeCanvas ?: return
        val rect = surfaceRectOf(finished.bounds)
        if (rect.isEmpty) return
        canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), clearPaint)
    }

    /** Converts stroke bounds in canvas coordinates to a rectangle on the surface. */
    private fun surfaceRectOf(bounds: BoundsAccumulator): IntRect {
        if (bounds.isEmpty) return IntRect.EMPTY
        val rounded = bounds.roundedOut()
        return IntRect(rounded[0] + overscan, rounded[1] + overscan, rounded[2] + overscan, rounded[3] + overscan).intersect(surfaceRect())
    }

    private fun surfaceRect(): IntRect = surface?.let { IntRect(0, 0, it.width, it.height) } ?: IntRect.EMPTY

    // ----- Fill -----

    /**
     * Fills the region around a canvas point. [tolerance] is 0..255 and [gapClose] is the width in pixels
     * of the gaps to bridge, on each side. With [sampleAllLayers], the region is found from what all
     * visible layers look like together, while the paint still goes on the active layer.
     */
    public fun fill(x: Int, y: Int, colorArgb: Int, tolerance: Int, gapClose: Int, sampleAllLayers: Boolean) {
        post {
            commitFloatingAndPublish()
            if (!ensureSurfaceLoaded()) return@post
            val target = surface ?: return@post
            val width = project.width
            val height = project.height
            if (x !in 0 until width || y !in 0 until height) return@post

            val pixels = IntArray(width * height)
            target.getPixels(pixels, 0, width, overscan, overscan, width, height)
            val sample = if (sampleAllLayers) readFlattenedLayers(width, height) else pixels
            val seedIsPaint = FloodFill.seedIsPaint(sample, width, height, x, y)
            val mask =
                FloodFill.computeMask(
                    sample,
                    width,
                    height,
                    x,
                    y,
                    tolerance,
                    gapClose,
                    expand = if (seedIsPaint) 0 else FILL_EXPAND_PX,
                ) ?: return@post
            if (mask.bounds.isEmpty) return@post

            val rect = mask.bounds.translated(overscan, overscan)
            val before = readPixels(target, rect)
            FloodFill.apply(pixels, width, mask, colorArgb, behind = !seedIsPaint)
            val offset = mask.bounds.top * width + mask.bounds.left
            target.setPixels(pixels, offset, width, rect.left, rect.top, rect.width, rect.height)
            contentBounds = contentBounds.union(rect)
            recordPixelEdit(rect, before)
            publishScene()
            publishState()
        }
    }

    private fun readFlattenedLayers(width: Int, height: Int): IntArray {
        val merged = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(merged)
        for (layer in project.layers) {
            if (!layer.visible) continue
            val paint = Paint().apply { alpha = (layer.opacity * 255f + 0.5f).toInt() }
            if (layer.id == layerId) {
                surface?.let { canvas.drawBitmap(it, -overscan.toFloat(), -overscan.toFloat(), paint) }
            } else {
                liveCels[layer.id]?.let { canvas.drawBitmap(it.bitmap, it.left.toFloat(), it.top.toFloat(), paint) }
            }
        }
        val pixels = IntArray(width * height)
        merged.getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels
    }

    // ----- Selection -----

    /** Lifts the pixels inside a closed outline off the active layer so they can be moved. [points] holds x, y pairs in canvas coordinates. */
    public fun liftSelection(points: FloatArray) {
        post {
            commitFloatingAndPublish()
            if (points.size < MIN_LASSO_COORDINATES || !ensureSurfaceLoaded()) return@post
            val target = surface ?: return@post
            val targetCanvas = surfaceCanvas ?: return@post
            val outline = Path()
            outline.moveTo(points[0], points[1])
            var i = 2
            while (i < points.size - 1) {
                outline.lineTo(points[i], points[i + 1])
                i += 2
            }
            outline.close()
            val box = RectF()
            @Suppress("DEPRECATION")
            outline.computeBounds(box, true)
            val canvasBounds =
                IntRect(
                    box.left.toInt() - 1,
                    box.top.toInt() - 1,
                    box.right.toInt() + 2,
                    box.bottom.toInt() + 2,
                )
            val surfaceRect = canvasBounds.translated(overscan, overscan).intersect(surfaceRect())
            if (surfaceRect.isEmpty) return@post
            val source = surfaceRect.translated(-overscan, -overscan)

            val lifted = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val liftCanvas = Canvas(lifted)
            liftCanvas.translate(-source.left.toFloat(), -source.top.toFloat())
            liftCanvas.clipPath(outline)
            liftCanvas.drawBitmap(target, -overscan.toFloat(), -overscan.toFloat(), null)
            if (isFullyTransparent(lifted)) {
                publishState()
                return@post
            }

            val before = readPixels(target, surfaceRect)
            val checkpoint = targetCanvas.save()
            targetCanvas.translate(overscan.toFloat(), overscan.toFloat())
            targetCanvas.drawPath(outline, clearPaint.apply { isAntiAlias = true })
            targetCanvas.restoreToCount(checkpoint)
            floating = Floating(lifted, outline, SelectionTransform(source), surfaceRect, before)
            publishScene()
            publishState()
        }
    }

    /** Moves the lifted selection. */
    public fun setSelectionTransform(transform: SelectionTransform) {
        post {
            val current = floating ?: return@post
            current.transform = transform
            publishScene()
            publishState()
        }
    }

    /** Puts the lifted selection down where it now is, as one undo step. */
    public fun commitSelection() {
        post {
            commitFloatingInternal()
            publishScene()
            publishState()
        }
    }

    /** Puts the lifted selection back where it came from. */
    public fun cancelSelection() {
        post {
            cancelFloating()
            publishScene()
            publishState()
        }
    }

    /** Deletes the lifted selection's pixels, as one undo step. */
    public fun deleteSelection() {
        post {
            val current = floating ?: return@post
            floating = null
            recordPixelEdit(current.cutRect, current.before)
            publishScene()
            publishState()
        }
    }

    /** For commands that may stop early: the screen must not be left showing a selection that has already been put down. */
    private fun commitFloatingAndPublish() {
        if (floating == null) return
        commitFloatingInternal()
        publishScene()
        publishState()
    }

    private fun commitFloatingInternal() {
        val current = floating ?: return
        val target = surface ?: return
        val targetCanvas = surfaceCanvas ?: return
        if (current.transform.isIdentity) {
            floating = null
            target.setPixels(
                current.before,
                0,
                current.cutRect.width,
                current.cutRect.left,
                current.cutRect.top,
                current.cutRect.width,
                current.cutRect.height,
            )
            requestSaveOfHeldPixels()
            return
        }
        val placed = current.transform
            .bounds()
            .translated(overscan, overscan)
            .intersect(surfaceRect())
        val union = placed.union(current.cutRect)

        // Everything that can run out of memory comes before the selection is let go, so a failure leaves it floating.
        // The patch must show the layer as it was before the lift, over the whole changed area.
        val before = readPixels(target, union)
        val cut = current.cutRect
        val rowOffset = (cut.top - union.top) * union.width + (cut.left - union.left)
        for (row in 0 until cut.height) {
            System.arraycopy(current.before, row * cut.width, before, rowOffset + row * union.width, cut.width)
        }

        val values = FloatArray(9)
        current.transform.toAffine().toMatrixValues(values)
        val matrix = Matrix().apply { setValues(values) }
        val source = current.transform.source
        floating = null
        val checkpoint = targetCanvas.save()
        targetCanvas.translate(overscan.toFloat(), overscan.toFloat())
        targetCanvas.concat(matrix)
        targetCanvas.drawBitmap(current.bitmap, source.left.toFloat(), source.top.toFloat(), filterPaint)
        targetCanvas.restoreToCount(checkpoint)

        contentBounds = contentBounds.union(placed)
        recordPixelEdit(union, before)
    }

    private fun cancelFloating() {
        val current = floating ?: return
        floating = null
        surface?.setPixels(
            current.before,
            0,
            current.cutRect.width,
            current.cutRect.left,
            current.cutRect.top,
            current.cutRect.width,
            current.cutRect.height,
        )
        requestSaveOfHeldPixels()
    }

    /**
     * A save that runs while a selection floats leaves the surface out, because its pixels are incomplete, yet it counts
     * the revision as saved. Ending the lift without an edit changes no revision by itself, so without this nothing would
     * save those pixels until the next change.
     */
    private fun requestSaveOfHeldPixels() {
        if (activeDirty) markChanged()
    }

    private fun isFullyTransparent(image: Bitmap): Boolean {
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        return pixels.none { it ushr 24 != 0 }
    }

    // ----- Undo and redo -----

    public fun undo() {
        post {
            if (stroke != null) return@post
            settleOpacityPreview()
            if (floating != null) {
                cancelFloating()
            } else {
                when (val entry = history.peekUndo()) {
                    is PixelEntry -> {
                        applyPatch(entry, onApplied = { history.popUndo() }, onLost = history::discardUndoSteps)
                    }

                    is StructureEntry -> {
                        withSurfacesFor(entry.before.width, entry.before.height) { surfaces ->
                            restoreStructure(entry.before, entry.cursorBefore, surfaces)
                            history.popUndo()
                        }
                    }

                    is BackgroundEntry -> {
                        showBackground(entry.before)
                        history.popUndo()
                    }

                    null -> {
                        Unit
                    }
                }
            }
            publishScene()
            publishState()
        }
    }

    public fun redo() {
        post {
            if (stroke != null || floating != null) return@post
            settleOpacityPreview()
            when (val entry = history.peekRedo()) {
                is PixelEntry -> {
                    applyPatch(entry, onApplied = { history.popRedo() }, onLost = history::discardRedoSteps)
                }

                is StructureEntry -> {
                    withSurfacesFor(entry.after.width, entry.after.height) { surfaces ->
                        restoreStructure(entry.after, entry.cursorAfter, surfaces)
                        history.popRedo()
                    }
                }

                is BackgroundEntry -> {
                    showBackground(entry.after)
                    history.popRedo()
                }

                null -> {
                    Unit
                }
            }
            publishScene()
            publishState()
        }
    }

    /**
     * Applies a pixel step. Only then does [onApplied] move it to the other stack, so a failure part-way leaves it to
     * try again. If its patch has gone, [onLost] forgets the steps that depended on it. A step whose frame cannot be
     * loaded right now just stays where it is.
     */
    private fun applyPatch(entry: PixelEntry, onApplied: () -> Unit, onLost: () -> Unit) {
        when (swapPixels(entry)) {
            PatchOutcome.APPLIED -> {
                onApplied()
                compressInBackground(entry.payload)
            }

            PatchOutcome.UNREADABLE -> {
                onLost()
                _events.trySend(SessionEvent.UndoStepsLost)
            }

            PatchOutcome.NOT_LOADED -> {
                Unit
            }
        }
    }

    /**
     * Exchanges a patch with what is on the surface, so the same patch then serves the opposite direction. The patch is
     * read before anything moves, so one that has gone leaves the user where they are. A patch lying partly outside a
     * surface with a smaller margin keeps those pixels for later: only the overlap is exchanged.
     */
    private fun swapPixels(entry: PixelEntry): PatchOutcome {
        val stored = entry.payload.readOrNull() ?: return PatchOutcome.UNREADABLE
        moveCursor(entry.frameId, entry.layerId)
        if (!isSurfaceLoaded()) return PatchOutcome.NOT_LOADED
        val target = surface ?: return PatchOutcome.NOT_LOADED
        val shift = overscan - entry.margin
        val rect = entry.rect.translated(shift, shift)
        val visible = rect.intersect(surfaceRect())
        if (visible.isEmpty) return PatchOutcome.APPLIED
        val swapped = if (visible == rect) IntArray(stored.size) else stored.copyOf()
        val offset = (visible.top - rect.top) * rect.width + (visible.left - rect.left)
        target.getPixels(swapped, offset, rect.width, visible.left, visible.top, visible.width, visible.height)
        target.setPixels(stored, offset, rect.width, visible.left, visible.top, visible.width, visible.height)
        entry.payload.replace(swapped)
        contentBounds = contentBounds.union(visible)
        activeDirty = true
        markChanged()
        return PatchOutcome.APPLIED
    }

    private fun restoreStructure(target: Project, cursor: HistoryCursor, surfaces: NewSurfaces?) {
        // Settings that are not part of undo stay as they are now.
        val current = project
        project =
            target.copy(
                name = current.name,
                fps = current.fps,
                settings = current.settings,
                background = current.background,
            )
        frameId = if (project.frameIndex(cursor.frameId) >= 0) cursor.frameId else project.frames.first().id
        layerId = if (project.layer(cursor.layerId) != null) cursor.layerId else project.layers.last().id
        // Pixels on the surface already match this snapshot; anything unsaved was undone first.
        activeDirty = false
        moveToSurfaces(surfaces)
        syncLive(forceActive = true)
        scheduleGhosts()
        markChanged()
    }

    /**
     * Notes that the surface has changed, and adds an undo step if [before] holds what it replaced. The surface is marked
     * as changed first, so a failure in the history bookkeeping cannot leave the pixels unsaved.
     */
    private fun recordPixelEdit(rect: IntRect, before: IntArray?) {
        activeDirty = true
        markChanged()
        if (before == null) {
            // Older steps would apply across a change they know nothing about.
            history.clear()
            return
        }
        val payload = PatchPayload(before)
        history.push(PixelEntry(layerId, frameId, rect, payload, overscan))
        compressInBackground(payload)
    }

    /**
     * What an edit is about to replace, or null if even after freeing memory that does not fit. The edit then goes
     * ahead without an undo step, because keeping the drawing matters more than being able to undo it.
     */
    private fun readPixelsForUndo(image: Bitmap, rect: IntRect): IntArray? =
        try {
            readPixels(image, rect)
        } catch (_: OutOfMemoryError) {
            trimAfterOutOfMemory()
            try {
                readPixels(image, rect)
            } catch (_: OutOfMemoryError) {
                null
            }
        }

    private fun compressInBackground(payload: PatchPayload) {
        workers.launch {
            try {
                payload.compress()
                post { history.enforceBudgets() }
            } catch (_: OutOfMemoryError) {
                // The patch simply stays uncompressed, which is still a good patch.
                post { trimAfterOutOfMemory() }
            }
        }
    }

    // ----- Position -----

    /** Goes to a frame. Tapping the current frame again retries if any of its drawings could not be loaded. Ignored during a stroke. */
    public fun selectFrame(id: String) {
        post {
            if (stroke != null || project.frameIndex(id) < 0) return@post
            if (id == frameId && isFullyLoaded()) return@post
            commitFloatingInternal()
            moveCursor(id, layerId)
            publishScene()
            publishState()
        }
    }

    /** Goes to a layer. Tapping the current layer again retries if its drawing could not be loaded. Ignored during a stroke. */
    public fun selectLayer(id: String) {
        post {
            if (stroke != null || project.layer(id) == null) return@post
            if (id == layerId && isFullyLoaded()) return@post
            commitFloatingInternal()
            moveCursor(frameId, id)
            publishScene()
            publishState()
        }
    }

    private fun moveCursor(newFrameId: String, newLayerId: String) {
        if (newFrameId == frameId && newLayerId == layerId && isFullyLoaded()) return
        flushActive()
        frameId = newFrameId
        layerId = newLayerId
        syncLive()
        scheduleGhosts()
    }

    private fun isSurfaceLoaded(): Boolean = surface != null && loadedKey == CelKey(layerId, frameId)

    private fun isFullyLoaded(): Boolean = isSurfaceLoaded() && !layerLeftOut

    /**
     * Every edit of the surface asks this first. If an earlier load failed it tries again. If it returns false, the edit
     * must be dropped, because the surface does not hold the drawing the edit is meant for.
     */
    private fun ensureSurfaceLoaded(): Boolean {
        if (isSurfaceLoaded()) return true
        syncLive(forceActive = true)
        if (!isSurfaceLoaded()) return false
        publishScene()
        return true
    }

    // ----- Structure edits -----

    public fun addFrame() {
        structureEdit { p ->
            val id = newId()
            ProjectEdits.addFrameAfter(p, frameId, id) to HistoryCursor(id, layerId)
        }
    }

    public fun insertFrameBefore(anchorId: String) {
        structureEdit { p ->
            val id = newId()
            ProjectEdits.insertFrameBefore(p, anchorId, id) to HistoryCursor(id, layerId)
        }
    }

    public fun duplicateFrame(id: String) {
        structureEdit { p ->
            val copyId = newId()
            ProjectEdits.duplicateFrame(p, id, copyId) to HistoryCursor(copyId, layerId)
        }
    }

    public fun deleteFrame(id: String) {
        structureEdit { p ->
            // A project keeps at least one frame, so deleting the only one really clears it, and a clear respects locks.
            if (p.frames.size == 1 && p.frames.first().id == id) {
                return@structureEdit clearEditableLayers(p, id) to HistoryCursor(frameId, layerId)
            }
            val index = p.frameIndex(id)
            val next = ProjectEdits.deleteFrame(p, id)
            val focus =
                if (id == frameId && next.frames.size != p.frames.size) {
                    next.frames[(index - 1).coerceAtLeast(0)].id
                } else {
                    frameId
                }
            next to HistoryCursor(focus, layerId)
        }
    }

    /** Empties a frame, leaving out layers that are hidden or locked, and says so if that left a drawing in place. */
    public fun clearFrame(id: String) {
        structureEdit { p -> clearEditableLayers(p, id) to HistoryCursor(frameId, layerId) }
    }

    /** Empties the active layer on the current frame, unless it is hidden or locked, in which case it says so instead. */
    public fun clearActiveCel() {
        post {
            if (stroke != null) return@post
            // Decided here rather than from what the UI last saw, so a lock that is still queued counts, and a refused
            // clear does not put down a floating selection.
            if (project.layer(layerId)?.isEditable != true) {
                _events.trySend(SessionEvent.LayerNotEditable)
                return@post
            }
            settleOpacityPreview()
            applyStructure { p -> ProjectEdits.clearFrame(p, frameId, layerId) to HistoryCursor(frameId, layerId) }
        }
    }

    private fun clearEditableLayers(source: Project, id: String): Project {
        var result = source
        var refused = false
        for (layer in source.layers) {
            when {
                layer.isEditable -> result = ProjectEdits.clearFrame(result, id, layer.id)
                source.cel(layer.id, id) != null -> refused = true
            }
        }
        if (refused) _events.trySend(SessionEvent.LayerNotEditable)
        return result
    }

    private val Layer.isEditable: Boolean get() = visible && !locked

    public fun moveFrame(fromIndex: Int, toIndex: Int) {
        structureEdit { p -> ProjectEdits.moveFrame(p, fromIndex, toIndex) to HistoryCursor(frameId, layerId) }
    }

    public fun setHold(id: String, hold: Int) {
        structureEdit { p -> ProjectEdits.setHold(p, id, hold) to HistoryCursor(frameId, layerId) }
    }

    /** Copies frames to the session's clipboard. With [activeLayerOnly], only the active layer's drawings are copied. */
    public fun copyFrames(ids: Collection<String>, activeLayerOnly: Boolean = false) {
        post {
            commitFloatingInternal()
            flushActive()
            frameClipboard = ProjectEdits.copyFrames(project, ids, if (activeLayerOnly) layerId else null)
            publishScene()
            publishState()
        }
    }

    /** Inserts the clipboard's frames after the current frame. */
    public fun pasteFrames() {
        structureEdit { p ->
            val clipboard = frameClipboard ?: return@structureEdit p to HistoryCursor(frameId, layerId)
            var lastId = frameId
            val next =
                ProjectEdits.pasteFrames(p, clipboard, frameId, layerId, newIds = {
                    newId().also { lastId = it }
                })
            next to HistoryCursor(lastId, layerId)
        }
    }

    public fun addLayer(name: String) {
        structureEdit { p ->
            val id = newId()
            ProjectEdits.addLayer(p, layerId, name, id) to HistoryCursor(frameId, id)
        }
    }

    public fun deleteLayer(id: String) {
        structureEdit { p ->
            val index = p.layers.indexOfFirst { it.id == id }
            val next = ProjectEdits.deleteLayer(p, id)
            val focus =
                if (id == layerId && next.layers.size != p.layers.size) {
                    next.layers[(index - 1).coerceAtLeast(0)].id
                } else {
                    layerId
                }
            next to HistoryCursor(frameId, focus)
        }
    }

    public fun duplicateLayer(id: String, name: String) {
        structureEdit { p ->
            val copyId = newId()
            ProjectEdits.duplicateLayer(p, id, name, copyId) to HistoryCursor(frameId, copyId)
        }
    }

    /** Moves a layer. Indexes count from the bottom of the stack. */
    public fun moveLayer(fromIndex: Int, toIndex: Int) {
        structureEdit { p -> ProjectEdits.moveLayer(p, fromIndex, toIndex) to HistoryCursor(frameId, layerId) }
    }

    public fun renameLayer(id: String, name: String) {
        updateLayer(id) { it.copy(name = name) }
    }

    public fun setLayerVisible(id: String, visible: Boolean) {
        updateLayer(id) { it.copy(visible = visible) }
    }

    public fun setLayerLocked(id: String, locked: Boolean) {
        updateLayer(id) { it.copy(locked = locked) }
    }

    /** Shows a new layer opacity immediately while a slider moves. It adds no undo step until [commitLayerOpacity]. */
    public fun previewLayerOpacity(id: String, opacity: Float) {
        post {
            val layer = project.layer(id) ?: return@post
            // As in settleOpacity, a preview of another layer ends here.
            if (opacityPreview?.layerId != id) settleOpacityPreview()
            if (opacityPreview == null) opacityPreview = OpacityPreview(id, layer.opacity)
            project = ProjectEdits.updateLayer(project, id) { it.copy(opacity = opacity.coerceIn(0f, 1f)) }
            publishScene()
            publishState()
        }
    }

    /** Ends an opacity change as a single undo step. */
    public fun commitLayerOpacity(id: String, opacity: Float) {
        post { settleOpacity(id, opacity) }
    }

    /**
     * Ends a slider drag that never got to finish, because the sheet holding it closed. What the user last saw stays, as
     * one undo step. Does nothing when no drag is in progress.
     */
    public fun endLayerOpacityPreview() {
        post { settleOpacityPreview() }
    }

    /**
     * A preview keeps only the opacity it replaced, never a whole earlier project: an autosave may flush new drawings into
     * the project during a drag, and they must survive it.
     */
    private fun settleOpacity(id: String, opacity: Float) {
        // A second finger can switch layers mid-drag. What the first layer shows by then counts as finished.
        if (opacityPreview?.layerId != id) settleOpacityPreview()
        val pending = opacityPreview
        opacityPreview = null
        if (pending != null) project = ProjectEdits.updateLayer(project, pending.layerId) { it.copy(opacity = pending.original) }
        applyStructure { p ->
            ProjectEdits.updateLayer(p, id) { it.copy(opacity = opacity.coerceIn(0f, 1f)) } to HistoryCursor(frameId, layerId)
        }
        if (pending != null) {
            // Dragging back to where it started adds no undo step, but a save during the drag may hold the previewed value.
            markChanged()
            publishScene()
            publishState()
        }
    }

    /** Every other edit records the project as it stands, which must not include an opacity that is still only a preview. */
    private fun settleOpacityPreview() {
        val pending = opacityPreview ?: return
        val shown = project.layer(pending.layerId)?.opacity
        if (shown == null) opacityPreview = null else settleOpacity(pending.layerId, shown)
    }

    /**
     * Merges a layer into the one below it, baking in both opacities so the result looks unchanged. This writes a new
     * image for each affected frame and may take a moment, so [SessionState.busy] is set meanwhile. A hidden layer and a
     * visible one cannot be merged, because the result would have to show art that was hidden or hide art that was shown.
     */
    public fun mergeLayerDown(id: String) {
        post {
            if (stroke != null) return@post
            settleOpacityPreview()
            val index = project.layers.indexOfFirst { it.id == id }
            if (index <= 0 || project.layers[index].visible != project.layers[index - 1].visible) return@post
            busy = true
            publishState()
            try {
                applyStructure { p ->
                    val merged = mergeDown(p, index)
                    merged to HistoryCursor(frameId, p.layers[index - 1].id)
                }
            } catch (_: UnreadableCel) {
                _events.trySend(SessionEvent.CelNotLoaded)
            } finally {
                busy = false
                publishState()
            }
        }
    }

    private fun mergeDown(source: Project, upperIndex: Int): Project {
        val upper = source.layers[upperIndex]
        val lower = source.layers[upperIndex - 1]
        var result = source
        val reused = HashMap<Pair<String?, String?>, Pair<String, IntRect>>()
        for (slot in source.frames) {
            val lowerCel = source.cel(lower.id, slot.id)
            val upperCel = source.cel(upper.id, slot.id)
            if (upperCel == null && (lowerCel == null || lower.opacity >= 1f)) continue
            val cacheKey = lowerCel?.file to upperCel?.file
            val (file, bounds) = reused[cacheKey]
                ?: (renderMerged(lowerCel, lower, upperCel, upper) ?: continue).also { reused[cacheKey] = it }
            result = ProjectEdits.withCel(result, CelKey(lower.id, slot.id), Cel(lower.id, slot.id, file, bounds))
        }
        result = ProjectEdits.deleteLayer(result, upper.id)
        return ProjectEdits.updateLayer(result, lower.id) { it.copy(opacity = 1f) }
    }

    private fun renderMerged(lowerCel: Cel?, lower: Layer, upperCel: Cel?, upper: Layer): Pair<String, IntRect>? {
        val bounds = (lowerCel?.bounds ?: IntRect.EMPTY).union(upperCel?.bounds ?: IntRect.EMPTY)
        if (bounds.isEmpty) return null
        val merged = Bitmap.createBitmap(bounds.width, bounds.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(merged)
        canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
        drawCelInto(canvas, lowerCel, lower.opacity)
        drawCelInto(canvas, upperCel, upper.opacity)
        return store.write(merged) to bounds
    }

    private fun drawCelInto(canvas: Canvas, cel: Cel?, opacity: Float) {
        val file = cel?.file ?: return
        val bounds = cel.bounds ?: return
        // A merged cel replaces this one, so carrying on without it would throw the drawing away.
        val image = readCel(file) ?: throw UnreadableCel()
        val paint = Paint().apply { alpha = (opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt() }
        canvas.drawBitmap(image, bounds.left.toFloat(), bounds.top.toFloat(), paint)
    }

    /**
     * Applies a change worked out elsewhere, such as an import, as one undo step, and returns once it is done. Pending pixels are saved first,
     * so [edit] sees the project as it stands. [selectLayerId] becomes the active layer if the result has it.
     *
     * It either happens or it does not. Running out of memory comes back to the caller as itself, and a save of pending pixels that cannot finish as
     * an [IOException], because whoever made this change has to tell the user whether it happened. Then the project, the surface and both undo
     * stacks are as they were. Once this returns the edit stands, and the caller can count on that.
     */
    @Throws(IOException::class)
    public suspend fun applyEdit(selectLayerId: String? = null, edit: (Project) -> Project) {
        withContext(dispatcher) {
            try {
                applyEditNow(selectLayerId, edit)
            } catch (e: OutOfMemoryError) {
                trimAfterOutOfMemory()
                publishWhereThingsStand()
                throw e
            } catch (e: UnreadableCel) {
                publishWhereThingsStand()
                throw IOException("An earlier drawing could not be read while saving", e)
            }
        }
    }

    private fun applyEditNow(selectLayerId: String?, edit: (Project) -> Project) {
        // A stroke must land on the frame and layer it started on, and this may be about to change the layer.
        commitStroke()
        settleOpacityPreview()
        commitFloatingAndPublish()
        flushActive()
        val before = project
        val cursorBefore = HistoryCursor(frameId, layerId)
        val after = edit(before)
        val cursorAfter = HistoryCursor(frameId, selectLayerId?.takeIf { after.layer(it) != null } ?: layerId)
        if (after == before && cursorAfter == cursorBefore) return

        // What can run out of memory comes first, while nothing has changed. The undo step comes last, as recording it throws away what could be redone.
        val surfaces = surfacesForEdit(after)
        val key = CelKey(cursorAfter.layerId, cursorAfter.frameId)
        val cel = after.cels[key]
        val reload = surfaces != null || key != loadedKey || cel?.file != loadedFile
        val image = if (reload) readForEdit(cel) else null

        project = after
        layerId = cursorAfter.layerId
        try {
            moveToSurfaces(surfaces)
            if (reload) showOnSurface(key, cel, image)
            syncOtherLayers()
            scheduleGhosts()
            history.push(StructureEntry(before, after, cursorBefore, cursorAfter))
        } catch (_: OutOfMemoryError) {
            // The project has changed by now, and the caller would take its files back if told otherwise. So this only costs what can be rebuilt: the
            // view is loaded again by the next command, and older undo steps would apply across a change they know nothing about.
            loadedKey = null
            loadedFile = null
            layerLeftOut = true
            history.clear()
            trimAfterOutOfMemory()
        }
        markChanged()
        publishWhereThingsStand()
    }

    /** Surfaces for the size an edit leaves the canvas at, or null if that does not differ. An edit that does not fit does not happen. */
    private fun surfacesForEdit(after: Project): NewSurfaces? {
        if (surface == null || (after.width == project.width && after.height == project.height)) return null
        return makeSurfaces(after.width, after.height) ?: throw OutOfMemoryError("A canvas of this size does not fit in memory")
    }

    /**
     * What an edit is about to show on the surface. Unlike [readCel] this tries only once, since the caller can tell the user and they can try again,
     * and a file that is still there but gives nothing back counts as running out of memory, for the reasons given there.
     */
    private fun readForEdit(cel: Cel?): Bitmap? {
        val file = cel?.file ?: return null
        if (cel?.bounds == null) return null
        return store.read(file) ?: if (store.isGone(file)) null else throw OutOfMemoryError("$file could not be decoded")
    }

    private fun updateLayer(id: String, change: (Layer) -> Layer) {
        structureEdit { p -> ProjectEdits.updateLayer(p, id, change) to HistoryCursor(frameId, layerId) }
    }

    /** Runs an edit of the structure. Ignored during a stroke, which must land on the frame and layer it started on. */
    private fun structureEdit(edit: (Project) -> Pair<Project, HistoryCursor>) {
        post {
            if (stroke != null) return@post
            settleOpacityPreview()
            applyStructure(edit = edit)
        }
    }

    private fun applyStructure(surfaces: NewSurfaces? = null, edit: (Project) -> Pair<Project, HistoryCursor>) {
        val hadSelection = floating != null
        commitFloatingInternal()
        flushActive()
        val before = project
        val cursorBefore = HistoryCursor(frameId, layerId)
        val (after, cursorAfter) = edit(before)
        if (after == before && cursorAfter == cursorBefore) {
            if (hadSelection) {
                publishScene()
                publishState()
            }
            return
        }
        history.push(StructureEntry(before, after, cursorBefore, cursorAfter))
        project = after
        frameId = cursorAfter.frameId
        layerId = cursorAfter.layerId
        val resized = moveToSurfaces(surfaces)
        syncLive(forceActive = resized)
        scheduleGhosts()
        markChanged()
        publishScene()
        publishState()
    }

    /**
     * Runs [change], which gives the canvas this size, with surfaces for that size made first. If they do not fit, nothing has
     * changed, so nothing happens but telling the user. That is what keeps the project and its surfaces the same size at all
     * times: a drawing can never be loaded onto a surface too small for it, and saved back cut down. A size that does not differ,
     * or a session with no surface yet, needs no new surfaces.
     */
    private inline fun withSurfacesFor(width: Int, height: Int, change: (NewSurfaces?) -> Unit) {
        if (surface == null || (width == project.width && height == project.height)) {
            change(null)
            return
        }
        val surfaces = makeSurfaces(width, height)
        if (surfaces == null) {
            _events.trySend(SessionEvent.CanvasDoesNotFit)
        } else {
            change(surfaces)
        }
    }

    /** Goes over to [surfaces], if the size is changing. Callers have already saved the pixels. Returns whether it did. */
    private fun moveToSurfaces(surfaces: NewSurfaces?): Boolean {
        if (surfaces == null) return false
        // Every layer's position changed, so nothing live can be reused.
        liveCels.clear()
        ghostCache.clear()
        revealBitmap = null
        revealImage = null
        revealGeneration = -1
        loadedKey = null
        loadedFile = null
        install(surfaces)
        return true
    }

    // ----- Settings that are not undo steps -----

    public fun setFps(fps: Int) {
        updateProject { ProjectEdits.setFps(it, fps) }
    }

    public fun rename(name: String) {
        updateProject { it.copy(name = name) }
    }

    /** Replaces what sits behind the layers. */
    public fun setBackground(background: Background) {
        post {
            showBackground(background)
            publishScene()
            publishState()
        }
    }

    /**
     * Replaces what sits behind the layers as one undo step, for when that is not something the user set out to change on its own terms, and
     * returns once it is done. If memory runs out it comes back to the caller, with everything as it was.
     */
    public suspend fun applyBackground(background: Background) {
        withContext(dispatcher) {
            val before = project.background
            if (before == background) return@withContext
            // Reading comes first, because nothing has changed yet if it fails. It is also the only part that can.
            val image = pictureFor(background)
            history.push(BackgroundEntry(before, background))
            showBackground(background, image)
            publishWhereThingsStand()
        }
    }

    private fun showBackground(background: Background, image: Bitmap? = pictureFor(background)) {
        project = project.copy(background = background)
        backgroundImage = image
        revealGeneration = -1
        markChanged()
    }

    /**
     * The picture a background shows, or null if it shows none or its file has really gone. One that is there but will not fit stops whatever
     * asked, before anything has changed, so that a background is never recorded as showing a picture that did not appear.
     */
    private fun pictureFor(background: Background): Bitmap? {
        val file = (background as? Background.Image)?.file ?: return null
        return readCel(file) ?: if (store.isGone(file)) null else throw OutOfMemoryError("The background picture does not fit in memory")
    }

    /**
     * Changes the canvas size, keeping the artwork centred. Nothing is cropped: drawings keep their pixels and just
     * get new positions, so this is instant and undoable, and anything now past the edge is still there to bring back.
     * A size that does not fit in memory changes nothing at all, not even a lifted selection, and sends [SessionEvent.CanvasDoesNotFit].
     */
    public fun resizeCanvas(width: Int, height: Int) {
        post {
            if (stroke != null) return@post
            settleOpacityPreview()
            val newWidth = width.coerceAtLeast(1)
            val newHeight = height.coerceAtLeast(1)
            withSurfacesFor(newWidth, newHeight) { surfaces ->
                applyStructure(surfaces) { p -> resized(p, newWidth, newHeight) to HistoryCursor(frameId, layerId) }
            }
        }
    }

    private fun resized(source: Project, width: Int, height: Int): Project {
        val dx = (width - source.width) / 2
        val dy = (height - source.height) / 2
        val moved =
            source.cels.mapValues { (_, cel) ->
                cel.copy(bounds = cel.bounds?.translated(dx, dy))
            }
        return source.copy(width = width.coerceAtLeast(1), height = height.coerceAtLeast(1), cels = moved)
    }

    public fun updateSettings(change: (ProjectSettings) -> ProjectSettings) {
        post {
            project = project.copy(settings = change(project.settings))
            scheduleGhosts()
            markChanged()
            publishScene()
            publishState()
        }
    }

    private fun updateProject(change: (Project) -> Project) {
        post {
            project = change(project)
            markChanged()
            publishState()
        }
    }

    // ----- Saving -----

    /**
     * Writes pending pixels to the store and returns the project as it now stands. While the lasso holds
     * lifted pixels the surface is incomplete, so they stay unsaved unless [commitSelection] is set. Committing them is an edit like any other,
     * and is published as one.
     *
     * Throws an [IOException] when memory runs out or an earlier drawing the save has to keep cannot be read, with the
     * pixels still marked as unsaved, so callers treat it like any other failed save and the next attempt starts over.
     */
    @Throws(IOException::class)
    public suspend fun snapshot(commitSelection: Boolean = false): Project =
        withContext(dispatcher) {
            try {
                val hadSelection = floating != null
                if (commitSelection) commitFloatingInternal()
                if (floating == null) flushActive()
                if (hadSelection && floating == null) publishScene()
                // A flush changes cel files, which thumbnails watch, and the selection may just have gone.
                publishState()
                project
            } catch (e: OutOfMemoryError) {
                trimAfterOutOfMemory()
                publishWhereThingsStand()
                throw IOException("Ran out of memory while saving", e)
            } catch (e: UnreadableCel) {
                publishWhereThingsStand()
                throw IOException("An earlier drawing could not be read while saving", e)
            }
        }

    /** Every cel file that the project, undo history or clipboard might still need. */
    public suspend fun referencedFiles(): Set<String> =
        withContext(dispatcher) {
            val files = HashSet(project.referencedFiles())
            (project.background as? Background.Image)?.let { files += it.file }
            files += history.referencedFiles()
            loadedFile?.let { files += it }
            frameClipboard?.frames?.forEach { frame -> frame.cels.values.forEach { cel -> cel.file?.let { files += it } } }
            files
        }

    /** Tries again to open a canvas that did not fit in memory. */
    public fun retryOpen() {
        post { if (!ready) open() }
    }

    /**
     * Tries again to open a canvas that did not fit in memory, at a size the user chose. Drawings stay centred and nothing
     * is deleted, as with [resizeCanvas]. This is no undo step, because undo must not go back to a size that cannot open.
     */
    public fun retryOpen(width: Int, height: Int) {
        post {
            if (ready) return@post
            if (width != project.width || height != project.height) {
                project = resized(project, width, height)
                markChanged()
            }
            open()
        }
    }

    /** Waits until all earlier commands have been carried out. */
    public suspend fun awaitIdle() {
        withContext(dispatcher) { }
    }

    /** Releases what can be released, for when the system is short of memory. */
    public fun trimMemory() {
        post {
            ghostCache.clear()
            tips.clear()
            store.trimMemory()
            history.spillAll()
        }
    }

    /** Shuts the session down. The caller must save first. */
    public fun close() {
        post {
            if (closed) return@post
            closed = true
            ghostJob?.cancel()
            history.clear()
            spillDirectory.deleteRecursively()
            sceneListener = null
        }
        workers.cancel()
        executor.shutdown()
    }

    // ----- Internals -----

    private fun open() {
        try {
            val surfaces = makeSurfaces(project.width, project.height)
            if (surfaces != null) {
                install(surfaces)
                backgroundImage = readBackgroundImage()
                syncLive(forceActive = true)
                scheduleGhosts()
                ready = true
                openFailed = false
                publishScene()
            } else {
                openFailed = true
            }
        } catch (_: OutOfMemoryError) {
            trimAfterOutOfMemory()
            openFailed = !ready
        }
        publishState()
    }

    private fun readBackgroundImage(): Bitmap? = (project.background as? Background.Image)?.let { readCel(it.file) }

    /**
     * Decodes a cel, freeing memory and trying once more if it does not fit. Returns null if it cannot be read. Running out
     * of memory may not throw at all, because a decode that cannot get native memory can simply return null. So a file
     * that is still there but gives nothing back is treated the same way: nothing else can tell the two apart, and on the
     * phones this app is for, memory is the likeliest reason.
     */
    private fun readCel(file: String): Bitmap? {
        val image =
            try {
                store.read(file)
            } catch (_: OutOfMemoryError) {
                null
            }
        if (image != null || store.isGone(file)) return image
        trimAfterOutOfMemory()
        return try {
            store.read(file)
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /** Surfaces made ahead of being needed. Until they are installed, nothing has changed. */
    private class NewSurfaces(val margin: Int, val surface: Bitmap, val strokeBuffer: Bitmap)

    /**
     * Allocates the surface and stroke buffer. Falls back to no margin if the full size does not fit. A complete failure
     * is left for the caller to explain, because a general warning that the user's work is safe would not tell them why
     * they cannot draw.
     */
    private fun makeSurfaces(width: Int, height: Int): NewSurfaces? {
        val attempts = intArrayOf(budget.overscanFor(width, height), 0)
        var ranOut = false
        for (margin in attempts) {
            try {
                val fullWidth = width + margin * 2
                val fullHeight = height + margin * 2
                val newSurface = allocateSurface(fullWidth, fullHeight)
                val newBuffer =
                    try {
                        allocateSurface(fullWidth, fullHeight)
                    } catch (e: OutOfMemoryError) {
                        // Otherwise the smaller attempt has to compete with this unreachable surface until a collection.
                        newSurface.recycle()
                        throw e
                    }
                if (ranOut) warnLowMemory()
                return NewSurfaces(margin, newSurface, newBuffer)
            } catch (_: OutOfMemoryError) {
                ranOut = true
                freeMemory()
            } catch (_: IllegalArgumentException) {
                // Too large for a bitmap at all, though perhaps not without the margin.
            }
        }
        return null
    }

    private fun install(surfaces: NewSurfaces) {
        overscan = surfaces.margin
        surface = surfaces.surface
        surfaceCanvas = Canvas(surfaces.surface)
        strokeBuffer = surfaces.strokeBuffer
        strokeCanvas = Canvas(surfaces.strokeBuffer)
    }

    /** Makes the live cels match the project: reloads the surface if needed, and any other visible layer whose file changed. */
    private fun syncLive(forceActive: Boolean = false) {
        val key = CelKey(layerId, frameId)
        val activeCel = project.cels[key]
        if (forceActive || key != loadedKey || activeCel?.file != loadedFile) {
            loadSurface(key, activeCel)
        }
        syncOtherLayers()
    }

    private fun syncOtherLayers() {
        val wanted = HashSet<String>()
        var leftOut = false
        for (layer in project.layers) {
            if (layer.id == layerId || !layer.visible) continue
            val cel = project.cels[CelKey(layer.id, frameId)] ?: continue
            val file = cel.file ?: continue
            val bounds = cel.bounds ?: continue
            wanted += layer.id
            val live = liveCels[layer.id]
            if (live?.file != file || live.left != bounds.left || live.top != bounds.top) {
                // The layer stays out of the scene if it does not fit. That costs the view of it, never its drawing.
                val image = readCel(file)
                if (image != null) {
                    liveCels[layer.id] = LiveCel(image, bounds.left, bounds.top, file)
                } else {
                    liveCels.remove(layer.id)
                    leftOut = true
                }
            }
        }
        liveCels.keys.retainAll(wanted)
        layerLeftOut = leftOut
        // Otherwise a visible layer would just vanish from the canvas, with nothing to say why or what to do about it.
        if (leftOut) _events.trySend(SessionEvent.LayerNotShown)
    }

    /**
     * Puts a cel on the surface. The image is decoded before anything changes, and an unreadable one is never taken for
     * an empty cel: the surface then counts as holding nothing, so a later stroke cannot replace a drawing that was
     * only temporarily out of reach. Clearing the cel, or a load that works later, ends that state. The user is told at
     * once, because all they would otherwise see is an empty frame.
     */
    private fun loadSurface(key: CelKey, cel: Cel?) {
        if (surface == null) return
        val file = cel?.file
        showOnSurface(key, cel, if (file != null && cel?.bounds != null) readCel(file) else null)
    }

    /** Decoding comes first, because if memory runs out the surface still holds what it says it holds, while erasing first would lose it. */
    private fun showOnSurface(key: CelKey, cel: Cel?, image: Bitmap?) {
        val target = surface ?: return
        val canvas = surfaceCanvas ?: return
        val file = cel?.file
        val celBounds = cel?.bounds
        if (file != null && celBounds != null && image == null) {
            loadedKey = null
            loadedFile = null
            _events.trySend(SessionEvent.CelNotLoaded)
            return
        }
        target.eraseColor(0)
        var bounds = IntRect.EMPTY
        if (image != null && celBounds != null) {
            canvas.drawBitmap(image, (celBounds.left + overscan).toFloat(), (celBounds.top + overscan).toFloat(), null)
            bounds = celBounds.translated(overscan, overscan).intersect(surfaceRect())
        }
        contentBounds = bounds
        loadedKey = key
        loadedFile = file
        activeDirty = false
    }

    /**
     * Saves the surface's pixels if they changed. An empty result removes the cel, so empty cels are never stored. The
     * pixels stay marked as unsaved until the project has taken the new cel, so running out of memory or failing to read
     * the old cel on the way loses nothing: the next attempt simply starts over.
     */
    private fun flushActive() {
        if (!activeDirty) return
        val key = loadedKey ?: return
        val target = surface ?: return
        if (project.frameIndex(key.frameId) < 0 || project.layer(key.layerId) == null) {
            activeDirty = false
            return
        }
        val tight = tightBounds(target, contentBounds)
        val previous = project.cels[key]
        val pixels =
            if (previous != null && overhangsSurface(previous)) {
                composeWithOverhang(previous, tight, target)
            } else {
                cropOf(target, tight)
            }
        val cel = pixels?.let { Cel(key.layerId, key.frameId, store.write(it.image), it.bounds) }
        project = ProjectEdits.withCel(project, key, cel)
        contentBounds = tight
        loadedFile = cel?.file
        activeDirty = false
    }

    /** Whether part of a cel lies outside the surface, as after shrinking the canvas. The surface never held those pixels. */
    private fun overhangsSurface(cel: Cel): Boolean {
        val onSurface = cel.bounds?.translated(overscan, overscan) ?: return false
        return onSurface.intersect(surfaceRect()) != onSurface
    }

    private fun cropOf(target: Bitmap, tight: IntRect): CelPixels? =
        if (tight.isEmpty) {
            null
        } else {
            CelPixels(
                Bitmap.createBitmap(target, tight.left, tight.top, tight.width, tight.height),
                tight.translated(-overscan, -overscan),
            )
        }

    /**
     * Keeps what a cel holds beyond the surface when saving an edit, because shrinking a canvas promises nothing is
     * deleted. The surface is the truth inside its own area, so that is cleared first and erased pixels stay erased.
     * Returns null if nothing is left at all. A cel file that has really gone has nothing to keep, so the surface alone is
     * saved. One that only failed to read still holds the art past the edge, so saving a crop would delete it for good.
     * Throwing stops the save instead, leaving the old cel in place and the pixels marked as unsaved.
     */
    private fun composeWithOverhang(previous: Cel, tight: IntRect, target: Bitmap): CelPixels? {
        val file = previous.file ?: return cropOf(target, tight)
        val old = previous.bounds ?: return cropOf(target, tight)
        val image = readCel(file)
        if (image == null) {
            if (store.isGone(file)) return cropOf(target, tight)
            throw UnreadableCel()
        }
        val window = surfaceRect().translated(-overscan, -overscan)
        val area = old.union(tight.translated(-overscan, -overscan))
        val merged = Bitmap.createBitmap(area.width, area.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(merged)
        canvas.translate(-area.left.toFloat(), -area.top.toFloat())
        canvas.drawBitmap(image, old.left.toFloat(), old.top.toFloat(), null)
        canvas.drawRect(window.left.toFloat(), window.top.toFloat(), window.right.toFloat(), window.bottom.toFloat(), clearPaint)
        canvas.drawBitmap(target, -overscan.toFloat(), -overscan.toFloat(), null)
        val kept = tightBounds(merged, IntRect(0, 0, area.width, area.height))
        if (kept.isEmpty) return null
        return CelPixels(Bitmap.createBitmap(merged, kept.left, kept.top, kept.width, kept.height), kept.translated(area.left, area.top))
    }

    /**
     * The smallest rectangle inside [area] containing every non-transparent pixel. It reads one row at a time, because
     * a copy of the whole area is a large allocation at the worst moment: this runs before every save.
     */
    private fun tightBounds(image: Bitmap, area: IntRect): IntRect {
        val rect = area.intersect(IntRect(0, 0, image.width, image.height))
        if (rect.isEmpty) return IntRect.EMPTY
        val width = rect.width
        val row = IntArray(width)
        var minX = width
        var minY = -1
        var maxX = -1
        var maxY = -1
        for (y in rect.top until rect.bottom) {
            image.getPixels(row, 0, width, rect.left, y, width, 1)
            var first = 0
            while (first < width && row[first] ushr 24 == 0) first++
            if (first == width) continue
            var last = width - 1
            while (row[last] ushr 24 == 0) last--
            if (first < minX) minX = first
            if (last > maxX) maxX = last
            if (minY < 0) minY = y
            maxY = y
        }
        if (maxX < 0) return IntRect.EMPTY
        return IntRect(rect.left + minX, minY, rect.left + maxX + 1, maxY + 1)
    }

    private fun readPixels(image: Bitmap, rect: IntRect): IntArray {
        val pixels = IntArray(rect.width * rect.height)
        image.getPixels(pixels, 0, rect.width, rect.left, rect.top, rect.width, rect.height)
        return pixels
    }

    private fun markChanged() {
        revision++
    }

    private fun publishState() {
        _state.value =
            SessionState(
                ready = ready,
                project = project,
                frameId = frameId,
                layerId = layerId,
                canUndo = history.canUndo || floating != null,
                canRedo = history.canRedo,
                revision = revision,
                selection = floating?.transform,
                hasFrameClipboard = frameClipboard != null,
                busy = busy,
                openFailed = openFailed,
            )
    }

    private fun publishScene(notify: Boolean = true) {
        val target = surface ?: return
        val below = ArrayList<LayerDraw>()
        val above = ArrayList<LayerDraw>()
        var active: LayerDraw? = null
        var passedActive = false
        for (layer in project.layers) {
            if (layer.id == layerId) {
                passedActive = true
                // A surface that failed to load holds something else, so it must not be shown as this layer.
                if (layer.visible && isSurfaceLoaded()) active = LayerDraw(target, -overscan, -overscan, layer.opacity, layer.blendMode)
                continue
            }
            val live = liveCels[layer.id] ?: continue
            val draw = LayerDraw(live.bitmap, live.left, live.top, layer.opacity, layer.blendMode)
            if (passedActive) above += draw else below += draw
        }
        val currentFloating = floating
        val overflows =
            !contentBounds.isEmpty &&
                (
                    contentBounds.left < overscan || contentBounds.top < overscan ||
                        contentBounds.right > overscan + project.width || contentBounds.bottom > overscan + project.height
                )
        sceneGeneration++
        scene =
            Scene(
                canvasWidth = project.width,
                canvasHeight = project.height,
                background = project.background,
                backgroundImage = backgroundImage,
                ghosts = ghosts,
                below = below,
                active = active,
                activeOverflows = overflows,
                above = above,
                wet = wet,
                floating = currentFloating?.let { FloatingDraw(it.bitmap, it.outline, it.transform) },
                grid = project.settings.grid,
                generation = sceneGeneration,
            )
        if (notify) sceneListener?.invoke()
    }

    /** Works out the onion-skin ghosts for the current frame in the background, and shows them when ready. */
    private fun scheduleGhosts() {
        ghostJob?.cancel()
        ghostJob = null
        val generation = ++ghostGeneration
        val settings = project.settings.onionSkin
        val plan = planOnionSkin(settings, project.frames.size, project.frameIndex(frameId))
        if (plan.isEmpty()) {
            ghosts = emptyList()
            return
        }
        val snapshot = project
        val onlyLayer = if (settings.currentLayerOnly) layerId else null
        val options = FlattenOptions(includeBackground = false, onlyLayerId = onlyLayer)
        val longest = max(snapshot.width, snapshot.height)
        val scale = min(1f, budget.ghostMaxEdge.toFloat() / longest)
        val job =
            workers.launch {
                try {
                    val flattener = FrameFlattener(store)
                    val draws = ArrayList<GhostDraw>(plan.size)
                    for (ghost in plan) {
                        ensureActive()
                        val slot = snapshot.frames[ghost.frameIndex]
                        if (!flattener.hasContent(snapshot, slot.id, options)) continue
                        val cacheKey = ghostKey(snapshot, slot.id, onlyLayer)
                        val image =
                            ghostCache[cacheKey] ?: run {
                                val width = (snapshot.width * scale).toInt().coerceAtLeast(1)
                                val height = (snapshot.height * scale).toInt().coerceAtLeast(1)
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                flattener.flatten(snapshot, slot.id, Canvas(bitmap), scale, null, options)
                                ghostCache[cacheKey] = bitmap
                                bitmap
                            }
                        val tint =
                            when {
                                !settings.tint -> null
                                ghost.isPrevious -> GHOST_TINT_PREVIOUS
                                else -> GHOST_TINT_NEXT
                            }
                        draws += GhostDraw(image, ghost.opacity, tint)
                    }
                    ensureActive()
                    post {
                        // This job may have got this far just as the user moved on. Its ghosts belong to where they were.
                        if (generation == ghostGeneration) {
                            ghosts = draws
                            publishScene()
                        }
                    }
                } catch (_: OutOfMemoryError) {
                    post {
                        // Ghosts are a drawing aid, so doing without them is fine. The old ones show the wrong frame.
                        if (generation == ghostGeneration) ghosts = emptyList()
                        trimAfterOutOfMemory()
                        publishScene()
                    }
                }
            }
        ghostJob = job
    }

    private fun ghostKey(snapshot: Project, id: String, onlyLayer: String?): String =
        buildString {
            append(onlyLayer.orEmpty())
            for (layer in snapshot.layers) {
                if (!layer.visible || layer.isReference) continue
                val cel = snapshot.cel(layer.id, id) ?: continue
                append('|').append(cel.file).append('@').append(layer.opacity)
            }
        }

    /** Paints everything except the active layer, which is what an eraser stroke uncovers. */
    private fun buildRevealImage() {
        val current = scene ?: return
        if (revealGeneration == current.generation && revealBitmap != null) {
            revealImage = revealBitmap
            return
        }
        val target = surface ?: return
        val image =
            revealBitmap?.takeIf { it.width == target.width && it.height == target.height }
                ?: Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888).also { revealBitmap = it }
        val canvas = Canvas(image)
        canvas.drawColor(workspaceColor, PorterDuff.Mode.SRC)
        canvas.translate(overscan.toFloat(), overscan.toFloat())
        val checkpoint = canvas.save()
        canvas.clipRect(0, 0, project.width, project.height)
        BackgroundPainter().draw(canvas, project.background, project.width, project.height, backgroundImage, forEditor = true)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val rect = RectF(0f, 0f, project.width.toFloat(), project.height.toFloat())
        current.ghosts.forEach { ghost ->
            paint.alpha = (ghost.opacity * 255f + 0.5f).toInt()
            canvas.drawBitmap(ghost.bitmap, null, rect, paint)
        }
        (current.below + current.above).forEach { draw ->
            paint.alpha = (draw.opacity * 255f + 0.5f).toInt()
            canvas.drawBitmap(draw.bitmap, draw.left.toFloat(), draw.top.toFloat(), paint)
        }
        canvas.restoreToCount(checkpoint)
        revealGeneration = current.generation
        revealImage = image
    }

    private fun trimAfterOutOfMemory() {
        freeMemory()
        warnLowMemory()
    }

    /** Gives back what it can. This runs because memory has already run out, so it must not fail itself. */
    private fun freeMemory() {
        try {
            ghostCache.clear()
            tips.clear()
            store.trimMemory()
            history.spillAll()
        } catch (_: OutOfMemoryError) {
            // Spilling allocates too. Whatever it managed to free stands, and the user still hears about it.
        }
    }

    private fun warnLowMemory() {
        // A canvas that does not fit while opening has its own screen, so this would only be old news once that is over.
        try {
            if (ready) _events.trySend(SessionEvent.LowMemory)
        } catch (_: OutOfMemoryError) {
            // Not even room to say so. The next message will get through once memory comes back.
        }
    }

    /**
     * Brings the screen up to date, after a command that stopped early and may have got part of the way, or an edit that has already happened. Failing
     * to tell it is no reason to say otherwise about how that went, and the next successful command publishes everything anyway.
     */
    private fun publishWhereThingsStand() {
        try {
            publishScene()
            publishState()
        } catch (_: OutOfMemoryError) {
            trimAfterOutOfMemory()
        }
    }

    /** Queues work on the engine thread. Running out of memory shrinks the caches rather than crashing the app. */
    private fun post(block: () -> Unit) {
        if (executor.isShutdown) return
        try {
            executor.execute {
                if (closed) return@execute
                try {
                    block()
                } catch (_: OutOfMemoryError) {
                    trimAfterOutOfMemory()
                    publishWhereThingsStand()
                } catch (_: UnreadableCel) {
                    // A save this command needed could not finish, and readCel has already warned. As with running out
                    // of memory, the command stops and the pixels stay unsaved for the next attempt.
                    publishWhereThingsStand()
                }
            }
        } catch (_: RejectedExecutionException) {
            // The session closed just as this arrived, and nothing is left to apply it to.
        }
    }

    private class LiveCel(val bitmap: Bitmap, val left: Int, val top: Int, val file: String)

    private class CelPixels(val image: Bitmap, val bounds: IntRect)

    private class OpacityPreview(val layerId: String, val original: Float)

    private class UnreadableCel : Exception()

    private enum class PatchOutcome { APPLIED, UNREADABLE, NOT_LOADED }

    private class ActiveStroke(val spec: BrushSpec, val colorArgb: Int) {
        val bounds = BoundsAccumulator()
    }

    private class Floating(
        val bitmap: Bitmap,
        val outline: Path,
        var transform: SelectionTransform,
        val cutRect: IntRect,
        val before: IntArray,
    )

    private companion object {
        const val MIN_UNDO_STEPS = 100
        const val EVENT_BUFFER = 16
        const val MIN_LASSO_COORDINATES = 6
        const val MAX_CACHED_GHOSTS = 16
        const val FILL_EXPAND_PX = 2
        const val GHOST_TINT_PREVIOUS = 0xD0E53935.toInt()
        const val GHOST_TINT_NEXT = 0xD02E9E4F.toInt()
    }
}
