package org.kineograph.media.svgimport

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.kineograph.engine.session.CelStore
import org.kineograph.engine.session.DrawingSession
import org.kineograph.media.svg.SvgAnimationInfo
import org.kineograph.media.svg.SvgDocument
import org.kineograph.model.Background
import org.kineograph.model.ImportEdits
import org.kineograph.model.IntRect
import org.kineograph.model.MAX_FPS
import org.kineograph.model.MIN_FPS
import org.kineograph.model.PlacedImage
import org.kineograph.model.Project
import org.kineograph.model.newId
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How a picture sits on the canvas. It is centred in all of them. */
public enum class SvgFit {
    /** All of the picture shows, as large as the canvas allows. */
    FIT_INSIDE,

    /** The canvas is covered, and whatever overflows it is cut off. */
    FILL_CANVAS,

    /** One unit of the picture's own size is one pixel of the canvas. */
    ORIGINAL_SIZE,
}

/** Where a single image goes. An animation always goes on a new layer. */
public enum class SvgImportPlace { NEW_LAYER, CURRENT_LAYER, BACKGROUND }

/** What to bring into the project. */
public sealed interface SvgImportRequest {
    public val fit: SvgFit

    /** One sampled picture per tick, on a new layer called [layerName] that starts on the current frame. */
    public data class Animation(val durationSeconds: Double, override val fit: SvgFit, val layerName: String) : SvgImportRequest

    /** The picture as it looks at [timeSeconds]. */
    public data class SingleImage(
        val timeSeconds: Double,
        val place: SvgImportPlace,
        override val fit: SvgFit,
        val layerName: String,
    ) : SvgImportRequest
}

/** How an import ended, unless it was cancelled, which comes through as a [CancellationException]. Only [DONE] changed the project. */
public enum class SvgImportOutcome {
    DONE,

    /** The current layer is locked or hidden. */
    LAYER_NOT_DRAWABLE,

    /** The picture is completely empty at every moment asked for. */
    NOTHING_TO_IMPORT,
    OUT_OF_MEMORY,
    OUT_OF_STORAGE,

    /** Something nobody planned for went wrong. */
    FAILED,
}

/**
 * Where on the canvas a picture goes, for an import and for a preview of one. The rectangle has the picture's own shape, so whatever the file
 * asks about its aspect ratio has nothing left to do. At its original size it starts on a whole pixel, or every sharp edge would blur.
 */
public fun svgDestination(pictureWidth: Float, pictureHeight: Float, canvasWidth: Int, canvasHeight: Int, fit: SvgFit): RectF {
    val scale =
        when (fit) {
            SvgFit.FIT_INSIDE -> min(canvasWidth / pictureWidth, canvasHeight / pictureHeight)
            SvgFit.FILL_CANVAS -> max(canvasWidth / pictureWidth, canvasHeight / pictureHeight)
            SvgFit.ORIGINAL_SIZE -> 1f
        }
    val width = pictureWidth * scale
    val height = pictureHeight * scale
    var left = (canvasWidth - width) / 2f
    var top = (canvasHeight - height) / 2f
    if (fit == SvgFit.ORIGINAL_SIZE) {
        left = left.roundToInt().toFloat()
        top = top.roundToInt().toFloat()
    }
    return RectF(left, top, left + width, top + height)
}

/**
 * When an imported animation's first tick is. A loop with a delay or an intro only starts to repeat later on, and importing from there is
 * what lets its last frame run back into its first. Everything else starts at the start.
 */
public val SvgAnimationInfo.sampleStartSeconds: Double
    get() = if (loopsForever) loopStartSeconds else 0.0

/**
 * Turns a parsed picture into drawings in the open project. All the drawing happens away from the main thread and the engine thread, and the
 * project only changes in the last step, as one undo step, so cancelling or failing at any point before that leaves it exactly as it was.
 *
 * While this runs, the files it writes are not yet part of the project, so whoever calls it must keep the sweep off them. See
 * `Autosaver.holdSweeps`.
 *
 * @param saveBackground keeps a picture as the project's background, or throws [IOException].
 */
public class SvgImporter(
    private val session: DrawingSession,
    private val store: CelStore,
    private val saveBackground: suspend (Bitmap) -> Background.Image,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Runs one import. [onProgress] hears how many of the pictures are drawn. Cancelling throws [CancellationException] and changes nothing. */
    public suspend fun import(
        document: SvgDocument,
        request: SvgImportRequest,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SvgImportOutcome =
        withContext(dispatcher) {
            val written = ArrayList<String>()
            try {
                when (request) {
                    is SvgImportRequest.Animation -> importAnimation(document, request, written, onProgress)
                    is SvgImportRequest.SingleImage -> importSingleImage(document, request, written, onProgress)
                }
            } catch (e: CancellationException) {
                // What was written stays on disk for the normal sweep, which is cheaper than waiting for it now.
                throw e
            } catch (_: OutOfMemoryError) {
                session.trimMemory()
                SvgImportOutcome.OUT_OF_MEMORY
            } catch (_: IOException) {
                store.discard(written)
                SvgImportOutcome.OUT_OF_STORAGE
            } catch (_: IllegalArgumentException) {
                // A bitmap refuses a size past what the platform can address, which comes to the same thing for the person using the app.
                SvgImportOutcome.OUT_OF_MEMORY
            } catch (_: RuntimeException) {
                SvgImportOutcome.FAILED
            }
        }

    private suspend fun importAnimation(
        document: SvgDocument,
        request: SvgImportRequest.Animation,
        written: MutableList<String>,
        onProgress: (Int, Int) -> Unit,
    ): SvgImportOutcome {
        val project = session.snapshot(commitSelection = true)
        val frameId = session.state.value.frameId
        val activeLayerId = session.state.value.layerId
        val fps = project.fps.coerceIn(MIN_FPS, MAX_FPS)
        val tickCount = ImportEdits.tickCount(request.durationSeconds, fps)
        val ticks = ImportEdits.ticksToSample(project, frameId, tickCount)
        val start = document.animation.sampleStartSeconds
        val destination = destinationOn(project, document, request.fit)
        val images = HashMap<Int, PlacedImage>()
        val frames = FrameSampler(project.width, project.height)
        try {
            var last: PlacedImage? = null
            for ((index, tick) in ticks.withIndex()) {
                document.renderCancellable(frames.canvas, start + tick.toDouble() / fps, destination)
                if (!frames.sameAsPrevious()) {
                    last = frames.cropToStore()?.also { written += it.file }
                    // Checking as it goes means a full disk ends the import early, instead of piling up every picture in memory first.
                    if (last != null && written.size % WRITE_CHECK_INTERVAL == 0) store.awaitWrites()
                }
                last?.let { images[tick] = it }
                frames.next()
                onProgress(index + 1, ticks.size)
            }
        } finally {
            frames.release()
        }
        if (images.isEmpty()) return SvgImportOutcome.NOTHING_TO_IMPORT
        store.awaitWrites()
        val layerId = newId()
        applyAsOneStep(layerId) { current ->
            ImportEdits.addAnimationLayer(current, activeLayerId, frameId, request.layerName, tickCount, images, layerId)
        }
        return SvgImportOutcome.DONE
    }

    private suspend fun importSingleImage(
        document: SvgDocument,
        request: SvgImportRequest.SingleImage,
        written: MutableList<String>,
        onProgress: (Int, Int) -> Unit,
    ): SvgImportOutcome {
        val project = session.snapshot(commitSelection = true)
        val frameId = session.state.value.frameId
        val activeLayerId = session.state.value.layerId
        val active = project.layer(activeLayerId)
        if (request.place == SvgImportPlace.CURRENT_LAYER && (active == null || !active.visible || active.locked)) {
            return SvgImportOutcome.LAYER_NOT_DRAWABLE
        }
        val picture = Bitmap.createBitmap(project.width, project.height, Bitmap.Config.ARGB_8888)
        try {
            val destination = destinationOn(project, document, request.fit)
            document.renderCancellable(Canvas(picture), request.timeSeconds, destination)
            onProgress(1, 1)
            val bounds = contentBounds(picture, IntArray(picture.width))
            if (bounds.isEmpty) return SvgImportOutcome.NOTHING_TO_IMPORT
            when (request.place) {
                SvgImportPlace.NEW_LAYER -> {
                    val image = PlacedImage(store.write(crop(picture, bounds)), bounds)
                    written += image.file
                    store.awaitWrites()
                    val layerId = newId()
                    applyAsOneStep(layerId) { current ->
                        ImportEdits.addImageLayer(current, activeLayerId, frameId, request.layerName, image, layerId)
                    }
                }

                SvgImportPlace.CURRENT_LAYER -> {
                    val image = combineWithCel(project, activeLayerId, frameId, picture, bounds) ?: return SvgImportOutcome.FAILED
                    written += image.file
                    store.awaitWrites()
                    applyAsOneStep(null) { current -> ImportEdits.replaceCel(current, activeLayerId, frameId, image) }
                }

                SvgImportPlace.BACKGROUND -> {
                    val background = saveBackground(picture)
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        session.setBackground(background)
                        session.awaitIdle()
                    }
                }
            }
        } finally {
            picture.recycle()
        }
        return SvgImportOutcome.DONE
    }

    private fun destinationOn(project: Project, document: SvgDocument, fit: SvgFit): RectF =
        svgDestination(document.width, document.height, project.width, project.height, fit)

    /**
     * Draws a picture over what the layer already shows, into a new cel, since files never change. What the layer has past the edge of the canvas
     * stays. Null if the layer's own drawing could not be read, because going ahead would lose it.
     */
    private fun combineWithCel(project: Project, layerId: String, frameId: String, picture: Bitmap, bounds: IntRect): PlacedImage? {
        val cel = project.cel(layerId, frameId)
        val file = cel?.file
        val celBounds = cel?.bounds
        if (file == null || celBounds == null) return PlacedImage(store.write(crop(picture, bounds)), bounds)
        val existing = store.read(file) ?: return null
        val union = celBounds.union(bounds)
        val combined = Bitmap.createBitmap(union.width, union.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combined)
        canvas.translate(-union.left.toFloat(), -union.top.toFloat())
        canvas.drawBitmap(existing, celBounds.left.toFloat(), celBounds.top.toFloat(), null)
        canvas.drawBitmap(picture, 0f, 0f, null)
        return PlacedImage(store.write(combined), union)
    }

    /**
     * Once this starts, the import is going to count as done, so a late Cancel cannot leave the project changed while the screen says it was not.
     * Memory can still run out while the result is being shown, after the project has changed. Taking that back keeps the promise that a
     * failed import changes nothing.
     */
    private suspend fun applyAsOneStep(selectLayerId: String?, edit: (Project) -> Project) {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            var edited: Project? = null
            try {
                session.applyEdit(selectLayerId) { current -> edit(current).also { edited = it } }
            } catch (e: OutOfMemoryError) {
                if (edited != null && session.snapshot() === edited) session.undo()
                throw e
            }
        }
    }

    /**
     * Holds the pictures for an animation. It reuses two canvas-sized bitmaps for the whole import, so memory use does not grow with its length,
     * and they stay outside the Java heap, where a big canvas on a small phone would soon not fit.
     */
    private inner class FrameSampler(width: Int, height: Int) {
        private var current = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        private var previous = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        private val row = IntArray(width)
        private var hasPrevious = false

        var canvas = Canvas(current)
            private set
        private var previousCanvas = Canvas(previous)

        /** Whether the picture just drawn has exactly the pixels of the one before, which is what lets them share a file. */
        fun sameAsPrevious(): Boolean = hasPrevious && current.sameAs(previous)

        /** Stores the picture just drawn. Null if it is empty, because empty cels are not stored. */
        fun cropToStore(): PlacedImage? {
            val bounds = contentBounds(current, row)
            if (bounds.isEmpty) return null
            return PlacedImage(store.write(crop(current, bounds)), bounds)
        }

        /** Gets ready to draw the next picture, keeping this one to compare with. */
        fun next() {
            val bitmap = previous
            val bitmapCanvas = previousCanvas
            previous = current
            previousCanvas = canvas
            current = bitmap
            canvas = bitmapCanvas
            current.eraseColor(0)
            hasPrevious = true
        }

        fun release() {
            current.recycle()
            previous.recycle()
        }
    }

    private companion object {
        /** The store queues about this many images at once, so waiting this often slows nothing down. */
        const val WRITE_CHECK_INTERVAL = 8

        /** A copy, because the store keeps what it is given and the picture it came from is about to be drawn over. */
        fun crop(
            picture: Bitmap,
            bounds: IntRect,
        ): Bitmap = Bitmap.createBitmap(picture, bounds.left, bounds.top, bounds.width, bounds.height)

        /**
         * The smallest rectangle around every pixel that is not fully transparent, which is how every other cel is cropped too. It reads one row at
         * a time, so that a big canvas needs one row on the Java heap and not all of them.
         */
        fun contentBounds(picture: Bitmap, row: IntArray): IntRect {
            val width = picture.width
            var minX = width
            var maxX = -1
            var minY = -1
            var maxY = -1
            for (y in 0 until picture.height) {
                picture.getPixels(row, 0, width, 0, y, width, 1)
                var x = 0
                while (x < width && row[x] ushr ALPHA_SHIFT == 0) x++
                if (x == width) continue
                if (minY < 0) minY = y
                maxY = y
                if (x < minX) minX = x
                var end = width - 1
                while (row[end] ushr ALPHA_SHIFT == 0) end--
                if (end > maxX) maxX = end
            }
            return if (maxX < 0) IntRect.EMPTY else IntRect(minX, minY, maxX + 1, maxY + 1)
        }

        const val ALPHA_SHIFT = 24
    }
}
