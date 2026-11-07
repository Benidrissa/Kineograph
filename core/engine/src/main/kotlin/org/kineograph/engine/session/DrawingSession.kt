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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
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
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
) {
    /** A stroke can only go on a layer that is visible and unlocked. */
    val canDrawOnActiveLayer: Boolean
        get() = project.layer(layerId)?.let { it.visible && !it.locked } ?: false
}

/** Things that happened which the user should hear about. */
public sealed interface SessionEvent {
    /** Memory ran out. Caches were emptied and the app carried on. */
    public data object LowMemory : SessionEvent

    /** Not enough memory even to open this canvas. */
    public data object CannotOpen : SessionEvent
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

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    public val events: SharedFlow<SessionEvent> get() = _events

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
    private var closed = false

    private var overscan = 0
    private var surface: Bitmap? = null
    private var surfaceCanvas: Canvas? = null
    private var strokeBuffer: Bitmap? = null
    private var strokeCanvas: Canvas? = null
    private var backgroundImage: Bitmap? = null

    private val liveCels = HashMap<String, LiveCel>()
    private var loadedKey: CelKey? = null
    private var loadedFile: String? = null
    private var activeDirty = false

    /** Everything inked on the surface is inside this rectangle, in surface coordinates. It may be loose. */
    private var contentBounds = IntRect.EMPTY

    private val history = History(budget.historyMemoryBytes, budget.historyDiskBytes, MIN_UNDO_STEPS, spillDirectory)
    private var stroke: ActiveStroke? = null
    private var wet: WetDraw? = null
    private var floating: Floating? = null
    private var frameClipboard: FrameClipboard? = null
    private var previewBase: Project? = null

    private var ghosts: List<GhostDraw> = emptyList()
    private var ghostJob: Job? = null
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
            commitFloatingInternal()
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
            val before = readPixels(target, rect)
            commitPaint.alpha = (current.spec.opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            commitPaint.xfermode = if (current.spec.isEraser) eraseMode else null
            val checkpoint = targetCanvas.save()
            targetCanvas.clipRect(rect.left, rect.top, rect.right, rect.bottom)
            targetCanvas.drawBitmap(buffer, 0f, 0f, commitPaint)
            targetCanvas.restoreToCount(checkpoint)
            clearStrokeBuffer(current)
            recordPixelEdit(rect, before)
            if (!current.spec.isEraser) contentBounds = contentBounds.union(rect)
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
            commitFloatingInternal()
            val target = surface ?: return@post
            val width = project.width
            val height = project.height
            if (x !in 0 until width || y !in 0 until height) return@post

            val pixels = IntArray(width * height)
            target.getPixels(pixels, 0, width, overscan, overscan, width, height)
            val sample = if (sampleAllLayers) readFlattenedLayers(width, height) else pixels
            val seedIsPaint = (sample[y * width + x] ushr 24) >= OPAQUE_SEED_ALPHA
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
            recordPixelEdit(rect, before)
            contentBounds = contentBounds.union(rect)
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
            commitFloatingInternal()
            val target = surface ?: return@post
            val targetCanvas = surfaceCanvas ?: return@post
            if (points.size < MIN_LASSO_COORDINATES) return@post
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

    private fun commitFloatingInternal() {
        val current = floating ?: return
        val target = surface ?: return
        val targetCanvas = surfaceCanvas ?: return
        floating = null
        if (current.transform.isIdentity) {
            target.setPixels(
                current.before,
                0,
                current.cutRect.width,
                current.cutRect.left,
                current.cutRect.top,
                current.cutRect.width,
                current.cutRect.height,
            )
            return
        }
        val placed = current.transform
            .bounds()
            .translated(overscan, overscan)
            .intersect(surfaceRect())
        val union = placed.union(current.cutRect)

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
        val checkpoint = targetCanvas.save()
        targetCanvas.translate(overscan.toFloat(), overscan.toFloat())
        targetCanvas.concat(matrix)
        targetCanvas.drawBitmap(current.bitmap, source.left.toFloat(), source.top.toFloat(), filterPaint)
        targetCanvas.restoreToCount(checkpoint)

        recordPixelEdit(union, before)
        contentBounds = contentBounds.union(placed)
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
            if (floating != null) {
                cancelFloating()
            } else {
                when (val entry = history.popUndo()) {
                    is PixelEntry -> swapPixels(entry)
                    is StructureEntry -> restoreStructure(entry.before, entry.cursorBefore)
                    is BackgroundEntry -> showBackground(entry.before)
                    null -> Unit
                }
            }
            publishScene()
            publishState()
        }
    }

    public fun redo() {
        post {
            if (stroke != null || floating != null) return@post
            when (val entry = history.popRedo()) {
                is PixelEntry -> swapPixels(entry)
                is StructureEntry -> restoreStructure(entry.after, entry.cursorAfter)
                is BackgroundEntry -> showBackground(entry.after)
                null -> Unit
            }
            publishScene()
            publishState()
        }
    }

    /** Exchanges a patch with what is on the surface, so the same patch then serves the opposite direction. */
    private fun swapPixels(entry: PixelEntry) {
        moveCursor(entry.frameId, entry.layerId)
        val target = surface ?: return
        val rect = entry.rect
        val current = readPixels(target, rect)
        val stored = entry.payload.read()
        target.setPixels(stored, 0, rect.width, rect.left, rect.top, rect.width, rect.height)
        entry.payload.replace(current)
        compressInBackground(entry.payload)
        contentBounds = contentBounds.union(rect)
        activeDirty = true
        markChanged()
    }

    private fun restoreStructure(target: Project, cursor: HistoryCursor) {
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
        matchSurfacesToCanvas()
        syncLive(forceActive = true)
        scheduleGhosts()
        markChanged()
    }

    private fun recordPixelEdit(rect: IntRect, before: IntArray) {
        val payload = PatchPayload(before)
        history.push(PixelEntry(layerId, frameId, rect, payload))
        compressInBackground(payload)
        activeDirty = true
        markChanged()
    }

    private fun compressInBackground(payload: PatchPayload) {
        workers.launch {
            payload.compress()
            post { history.enforceBudgets() }
        }
    }

    // ----- Position -----

    public fun selectFrame(id: String) {
        post {
            if (id == frameId || project.frameIndex(id) < 0) return@post
            commitFloatingInternal()
            moveCursor(id, layerId)
            scheduleGhosts()
            publishScene()
            publishState()
        }
    }

    public fun selectLayer(id: String) {
        post {
            if (id == layerId || project.layer(id) == null) return@post
            commitFloatingInternal()
            moveCursor(frameId, id)
            scheduleGhosts()
            publishScene()
            publishState()
        }
    }

    private fun moveCursor(newFrameId: String, newLayerId: String) {
        if (newFrameId == frameId && newLayerId == layerId) return
        flushActive()
        frameId = newFrameId
        layerId = newLayerId
        syncLive()
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

    /** Empties a frame on every layer. */
    public fun clearFrame(id: String) {
        structureEdit { p -> ProjectEdits.clearFrame(p, id) to HistoryCursor(frameId, layerId) }
    }

    /** Empties the active layer on the current frame. */
    public fun clearActiveCel() {
        structureEdit { p -> ProjectEdits.clearFrame(p, frameId, layerId) to HistoryCursor(frameId, layerId) }
    }

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
            if (previewBase == null) previewBase = project
            project = ProjectEdits.updateLayer(project, id) { it.copy(opacity = opacity.coerceIn(0f, 1f)) }
            publishScene()
            publishState()
        }
    }

    /** Ends an opacity change as a single undo step. */
    public fun commitLayerOpacity(id: String, opacity: Float) {
        post {
            val base = previewBase ?: project
            previewBase = null
            project = base
            applyStructure { p ->
                ProjectEdits.updateLayer(p, id) { it.copy(opacity = opacity.coerceIn(0f, 1f)) } to HistoryCursor(frameId, layerId)
            }
        }
    }

    /**
     * Merges a layer into the one below it, baking in both opacities so the result looks unchanged. This
     * writes a new image for each affected frame and may take a moment, so [SessionState.busy] is set meanwhile.
     */
    public fun mergeLayerDown(id: String) {
        post {
            val index = project.layers.indexOfFirst { it.id == id }
            if (index <= 0) return@post
            busy = true
            publishState()
            try {
                applyStructure { p ->
                    val merged = mergeDown(p, index)
                    merged to HistoryCursor(frameId, p.layers[index - 1].id)
                }
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
        val image = store.read(file) ?: return
        val paint = Paint().apply { alpha = (opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt() }
        canvas.drawBitmap(image, bounds.left.toFloat(), bounds.top.toFloat(), paint)
    }

    /**
     * Applies a change worked out elsewhere, such as an import, as one undo step, and returns once it is done. Pending pixels are saved first,
     * so [edit] sees the project as it stands. [selectLayerId] becomes the active layer if the result has it.
     *
     * It either happens or it does not. Running out of memory comes back to the caller, because whoever made this change has to tell the user
     * whether it happened, and then the project, the surface and both undo stacks are as they were. That is why nothing is recorded until what can
     * run out of memory is over: pushing an undo step would already have thrown away what the user could still redo.
     */
    public suspend fun applyEdit(selectLayerId: String? = null, edit: (Project) -> Project) {
        withContext(dispatcher) {
            commitFloatingIfAny()
            flushActive()
            val before = project
            val cursorBefore = HistoryCursor(frameId, layerId)
            val after = edit(before)
            val cursorAfter = HistoryCursor(frameId, selectLayerId?.takeIf { after.layer(it) != null } ?: layerId)
            if (after == before && cursorAfter == cursorBefore) return@withContext
            project = after
            layerId = cursorAfter.layerId
            try {
                val resized = matchSurfacesToCanvas()
                syncLive(forceActive = resized)
            } catch (e: OutOfMemoryError) {
                project = before
                layerId = cursorBefore.layerId
                trimAfterOutOfMemory()
                putBackAfterFailedEdit()
                throw e
            }
            history.push(StructureEntry(before, after, cursorBefore, cursorAfter))
            scheduleGhosts()
            markChanged()
            publishAfterEdit()
        }
    }

    /** The surface only changes once its new pixels are in hand, so failing to read the old ones again leaves it showing what it showed. */
    private fun putBackAfterFailedEdit() {
        try {
            val resized = matchSurfacesToCanvas()
            syncLive(forceActive = resized)
        } catch (_: OutOfMemoryError) {
            trimAfterOutOfMemory()
        }
        publishAfterEdit()
    }

    /** By now the edit stands or has been taken back, so failing to tell the screen is no reason to say otherwise. The next change tells it. */
    private fun publishAfterEdit() {
        try {
            publishScene()
            publishState()
        } catch (_: OutOfMemoryError) {
            trimAfterOutOfMemory()
        }
    }

    /** Whatever is lifted goes down first, and the screen has to hear about it, or its selection bar would be left working on nothing. */
    private fun commitFloatingIfAny() {
        if (floating == null) return
        commitFloatingInternal()
        publishScene()
        publishState()
    }

    private fun updateLayer(id: String, change: (Layer) -> Layer) {
        structureEdit { p -> ProjectEdits.updateLayer(p, id, change) to HistoryCursor(frameId, layerId) }
    }

    private fun structureEdit(edit: (Project) -> Pair<Project, HistoryCursor>) {
        post { applyStructure(edit) }
    }

    private fun applyStructure(edit: (Project) -> Pair<Project, HistoryCursor>) {
        commitFloatingInternal()
        flushActive()
        val before = project
        val cursorBefore = HistoryCursor(frameId, layerId)
        val (after, cursorAfter) = edit(before)
        if (after == before && cursorAfter == cursorBefore) return
        history.push(StructureEntry(before, after, cursorBefore, cursorAfter))
        project = after
        frameId = cursorAfter.frameId
        layerId = cursorAfter.layerId
        val resized = matchSurfacesToCanvas()
        syncLive(forceActive = resized)
        scheduleGhosts()
        markChanged()
        publishScene()
        publishState()
    }

    /** Reallocates the surfaces after the canvas changes size. Returns whether it did. */
    private fun matchSurfacesToCanvas(): Boolean {
        val current = surface ?: return false
        val margin = budget.overscanFor(project.width, project.height)
        if (current.width == project.width + margin * 2 && current.height == project.height + margin * 2) return false
        // Every layer's position changed, so nothing live can be reused.
        liveCels.clear()
        ghostCache.clear()
        revealBitmap = null
        revealImage = null
        revealGeneration = -1
        if (!allocateSurfaces(project.width, project.height)) {
            _events.tryEmit(SessionEvent.LowMemory)
        }
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
            val image = (background as? Background.Image)?.let { store.read(it.file) }
            history.push(BackgroundEntry(before, background))
            project = project.copy(background = background)
            backgroundImage = image
            revealGeneration = -1
            markChanged()
            publishAfterEdit()
        }
    }

    private fun showBackground(background: Background) {
        val image = (background as? Background.Image)?.let { store.read(it.file) }
        project = project.copy(background = background)
        backgroundImage = image
        revealGeneration = -1
        markChanged()
    }

    /**
     * Changes the canvas size, keeping the artwork centred. Nothing is cropped: drawings keep their pixels and just
     * get new positions, so this is instant and undoable, and anything now past the edge is still there to bring back.
     */
    public fun resizeCanvas(width: Int, height: Int) {
        structureEdit { p ->
            val dx = (width - p.width) / 2
            val dy = (height - p.height) / 2
            val moved =
                p.cels.mapValues { (_, cel) ->
                    cel.copy(bounds = cel.bounds?.translated(dx, dy))
                }
            p.copy(width = width.coerceAtLeast(1), height = height.coerceAtLeast(1), cels = moved) to HistoryCursor(frameId, layerId)
        }
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
     */
    public suspend fun snapshot(commitSelection: Boolean = false): Project =
        withContext(dispatcher) {
            val wasLifted = commitSelection && floating != null
            if (commitSelection) commitFloatingInternal()
            if (floating == null) flushActive()
            if (wasLifted) {
                publishScene()
                publishState()
            }
            project
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
        val width = project.width
        val height = project.height
        if (!allocateSurfaces(width, height)) {
            _events.tryEmit(SessionEvent.CannotOpen)
            return
        }
        backgroundImage = (project.background as? Background.Image)?.let { store.read(it.file) }
        syncLive(forceActive = true)
        scheduleGhosts()
        ready = true
        publishScene()
        publishState()
    }

    /** Allocates the surface and stroke buffer. Falls back to no margin if the full size does not fit. */
    private fun allocateSurfaces(width: Int, height: Int): Boolean {
        val attempts = intArrayOf(budget.overscanFor(width, height), 0)
        for (margin in attempts) {
            try {
                val fullWidth = width + margin * 2
                val fullHeight = height + margin * 2
                val newSurface = Bitmap.createBitmap(fullWidth, fullHeight, Bitmap.Config.ARGB_8888)
                val newBuffer = Bitmap.createBitmap(fullWidth, fullHeight, Bitmap.Config.ARGB_8888)
                overscan = margin
                surface = newSurface
                surfaceCanvas = Canvas(newSurface)
                strokeBuffer = newBuffer
                strokeCanvas = Canvas(newBuffer)
                return true
            } catch (_: OutOfMemoryError) {
                trimAfterOutOfMemory()
            } catch (_: IllegalArgumentException) {
                return false
            }
        }
        return false
    }

    /** Makes the live cels match the project: reloads the surface if needed, and any other visible layer whose file changed. */
    private fun syncLive(forceActive: Boolean = false) {
        val key = CelKey(layerId, frameId)
        val activeCel = project.cels[key]
        if (forceActive || key != loadedKey || activeCel?.file != loadedFile) {
            loadSurface(key, activeCel)
        }
        val wanted = HashSet<String>()
        for (layer in project.layers) {
            if (layer.id == layerId || !layer.visible) continue
            val cel = project.cels[CelKey(layer.id, frameId)] ?: continue
            val file = cel.file ?: continue
            val bounds = cel.bounds ?: continue
            wanted += layer.id
            val live = liveCels[layer.id]
            if (live?.file != file || live.left != bounds.left || live.top != bounds.top) {
                val image = store.read(file)
                if (image != null) {
                    liveCels[layer.id] = LiveCel(image, bounds.left, bounds.top, file)
                } else {
                    liveCels.remove(layer.id)
                }
            }
        }
        liveCels.keys.retainAll(wanted)
    }

    private fun loadSurface(key: CelKey, cel: Cel?) {
        val target = surface ?: return
        val canvas = surfaceCanvas ?: return
        val file = cel?.file
        val celBounds = cel?.bounds
        // Decoded before anything is erased. If memory runs out here the surface still holds what it says it holds, while erasing first would let
        // the next stroke save itself over the cel with nothing else on it.
        val image = if (file != null && celBounds != null) store.read(file) else null
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

    /** Saves the surface's pixels if they changed. An empty result removes the cel, so empty cels are never stored. */
    private fun flushActive() {
        if (!activeDirty) return
        val key = loadedKey ?: return
        val target = surface ?: return
        activeDirty = false
        if (project.frameIndex(key.frameId) < 0 || project.layer(key.layerId) == null) return
        val tight = tightBounds(target, contentBounds)
        contentBounds = tight
        if (tight.isEmpty) {
            project = ProjectEdits.withCel(project, key, null)
            loadedFile = null
            return
        }
        val crop = Bitmap.createBitmap(target, tight.left, tight.top, tight.width, tight.height)
        val file = store.write(crop)
        val bounds = tight.translated(-overscan, -overscan)
        project = ProjectEdits.withCel(project, key, Cel(key.layerId, key.frameId, file, bounds))
        loadedFile = file
    }

    /** The smallest rectangle inside [area] containing every non-transparent pixel. */
    private fun tightBounds(image: Bitmap, area: IntRect): IntRect {
        val rect = area.intersect(IntRect(0, 0, image.width, image.height))
        if (rect.isEmpty) return IntRect.EMPTY
        val width = rect.width
        val height = rect.height
        val pixels = IntArray(width * height)
        image.getPixels(pixels, 0, width, rect.left, rect.top, width, height)
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (pixels[index++] ushr 24 != 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    maxY = y
                }
            }
        }
        if (maxX < 0) return IntRect.EMPTY
        return IntRect(rect.left + minX, rect.top + minY, rect.left + maxX + 1, rect.top + maxY + 1)
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
                if (layer.visible) active = LayerDraw(target, -overscan, -overscan, layer.opacity, layer.blendMode)
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
                    ghosts = draws
                    publishScene()
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
        ghostCache.clear()
        tips.clear()
        store.trimMemory()
        history.spillAll()
        _events.tryEmit(SessionEvent.LowMemory)
    }

    /** Queues work on the engine thread. Running out of memory shrinks the caches rather than crashing the app. */
    private fun post(block: () -> Unit) {
        if (executor.isShutdown) return
        executor.execute {
            if (closed) return@execute
            try {
                block()
            } catch (_: OutOfMemoryError) {
                trimAfterOutOfMemory()
            }
        }
    }

    private class LiveCel(val bitmap: Bitmap, val left: Int, val top: Int, val file: String)

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
        const val MIN_LASSO_COORDINATES = 6
        const val MAX_CACHED_GHOSTS = 16
        const val OPAQUE_SEED_ALPHA = 200
        const val FILL_EXPAND_PX = 2
        const val GHOST_TINT_PREVIOUS = 0xD0E53935.toInt()
        const val GHOST_TINT_NEXT = 0xD02E9E4F.toInt()
    }
}
