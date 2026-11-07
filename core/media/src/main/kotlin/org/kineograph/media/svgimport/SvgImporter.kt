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

    /**
     * One sampled picture per tick, on a new layer called [layerName] that starts on the current frame.
     *
     * @property withIntro for a file that plays something once before it settles into a loop, whether that goes in too. See [SvgSampling].
     */
    public data class Animation(
        val durationSeconds: Double,
        override val fit: SvgFit,
        val layerName: String,
        val withIntro: Boolean = true,
    ) : SvgImportRequest

    /** The picture as it looks at [timeSeconds]. */
    public data class SingleImage(
        val timeSeconds: Double,
        val place: SvgImportPlace,
        override val fit: SvgFit,
        val layerName: String,
    ) : SvgImportRequest
}

/**
 * How an import ended, unless it was cancelled in time, which comes through as a [CancellationException]. Only [DONE] changed the project. A Cancel
 * that comes too late to stop the change gets [DONE] as well, because what the screen says has to match what happened.
 */
public enum class SvgImportOutcome {
    DONE,

    /** The current layer is locked or hidden. */
    LAYER_NOT_DRAWABLE,

    /** The picture is completely empty at every moment asked for. */
    NOTHING_TO_IMPORT,

    /** More frames than this device can keep track of, which is decided before anything is drawn. See [maxImportFrames]. */
    TOO_MANY_FRAMES,
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
 * When the part of a picture that repeats starts. A loop with a delay or an intro only starts to repeat later on, and importing from there is
 * what lets its last frame run back into its first. Everything else starts at the start.
 */
public val SvgAnimationInfo.sampleStartSeconds: Double
    get() = if (loopsForever) loopStartSeconds else 0.0

/** How long an import of this picture should be, given whether it includes the intro of a file that has one. */
public fun SvgAnimationInfo.suggestedSeconds(withIntro: Boolean): Double =
    if (hasIntro && withIntro) loopStartSeconds + durationSeconds else durationSeconds

/**
 * The moments an import takes its pictures at, tick by tick. A preview shows the same ones. There are three ways it goes:
 *
 *  - **Something that ends** samples every tick up to and including its last moment. The last frame is what stays on screen, so a fade has to get
 *    to where it was going. That makes one tick more than the duration alone.
 *  - **A loop** samples from where it starts to repeat, and leaves out the end, which is the same moment as the start. So that the step from the
 *    last frame back to the first is the same as all the others, the ticks divide the duration evenly, even if that does not come to exactly
 *    one tick of the file's own time. A loop always gets two, as one would show none of its motion.
 *  - **A loop with an intro, kept in,** runs from the very start at the project's rate. Its end runs into its loop and not into its intro, so
 *    there is no seam to look after.
 */
public class SvgSampling private constructor(
    public val tickCount: Int,
    private val startSeconds: Double,
    private val stepSeconds: Double,
    private val lastSeconds: Double,
) {
    public fun secondsAt(tick: Int): Double = minOf(startSeconds + tick * stepSeconds, lastSeconds)

    public companion object {
        public fun of(animation: SvgAnimationInfo, durationSeconds: Double, fps: Int, withIntro: Boolean = true): SvgSampling {
            val rate = fps.coerceIn(MIN_FPS, MAX_FPS)
            val oneTick = 1.0 / rate
            return when {
                !animation.loopsForever -> {
                    SvgSampling(ImportEdits.tickCountThroughEnd(durationSeconds, rate), 0.0, oneTick, durationSeconds)
                }

                animation.hasIntro && withIntro -> {
                    SvgSampling(ImportEdits.tickCount(durationSeconds, rate), 0.0, oneTick, durationSeconds)
                }

                else -> {
                    val ticks = ImportEdits.tickCount(durationSeconds, rate).coerceAtLeast(MIN_LOOP_TICKS)
                    val start = animation.loopStartSeconds
                    SvgSampling(ticks, start, durationSeconds / ticks, start + durationSeconds)
                }
            }
        }

        private const val MIN_LOOP_TICKS = 2
    }
}

/**
 * The most frames a project on this device can have and still be saved. Each frame and its drawing cost the Java heap a little for as long as the project is open, and
 * most of all while every autosave encodes the whole manifest: about five kilobytes at the peak, as measured. Past this, the import itself would go fine and then no save
 * of that project would ever work again. Half of [heapBytes], which are the device's own, is left for everything else the app holds, and a normal phone still gets half an
 * hour of animation at twelve frames per second.
 */
public fun maxImportFrames(heapBytes: Long = Runtime.getRuntime().maxMemory()): Int =
    (heapBytes / HEAP_PER_FRAME).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

/** How many more frames can come into [project]. What it already has takes up the same room, one way or another. */
public fun roomForImportedFrames(project: Project, heapBytes: Long = Runtime.getRuntime().maxMemory()): Int =
    (maxImportFrames(heapBytes) - max(project.frames.size, project.cels.size)).coerceAtLeast(0)

private const val HEAP_PER_FRAME = 12L * 1024

/**
 * Turns a parsed picture into drawings in the open project. All the drawing happens away from the main thread and the engine thread, and the
 * project only changes in the last step, as one undo step, so cancelling or failing at any point before that leaves it exactly as it was, and
 * takes back what was written on the way.
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
    /** What one import has done so far, which is what it may have to take back. */
    private class Run {
        val written = ArrayList<String>()

        /** Once this is set, the project has changed, so nothing that comes after can make this import anything but done. */
        @Volatile
        var applied = false
    }

    /**
     * Runs one import. [onProgress] hears how many of the pictures are drawn. Cancelling throws [CancellationException] and changes nothing, unless
     * it comes after the project has already changed, and then this returns [SvgImportOutcome.DONE] as usual.
     */
    public suspend fun import(
        document: SvgDocument,
        request: SvgImportRequest,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SvgImportOutcome {
        val run = Run()
        return try {
            withContext(dispatcher) {
                try {
                    when (request) {
                        is SvgImportRequest.Animation -> importAnimation(document, request, run, onProgress)
                        is SvgImportRequest.SingleImage -> importSingleImage(document, request, run, onProgress)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: OutOfMemoryError) {
                    session.trimMemory()
                    giveUp(run)
                    SvgImportOutcome.OUT_OF_MEMORY
                } catch (e: IOException) {
                    giveUp(run)
                    // A snapshot reports running out of memory like any failed save, but what the user can do about it is not the same. Crossing
                    // between threads can wrap an exception again, so the reason need not be its direct cause.
                    if (generateSequence<Throwable>(e) { it.cause }.any { it is OutOfMemoryError }) {
                        SvgImportOutcome.OUT_OF_MEMORY
                    } else {
                        SvgImportOutcome.OUT_OF_STORAGE
                    }
                } catch (_: IllegalArgumentException) {
                    // A bitmap refuses a size past what the platform can address, which comes to the same thing for the person using the app.
                    giveUp(run)
                    SvgImportOutcome.OUT_OF_MEMORY
                } catch (_: RuntimeException) {
                    giveUp(run)
                    SvgImportOutcome.FAILED
                }
            }
        } catch (e: CancellationException) {
            // A cancelled coroutine loses whatever withContext would have returned, including the news that the project has changed.
            if (run.applied) return SvgImportOutcome.DONE
            giveUp(run)
            throw e
        }
    }

    /**
     * Gives back everything an import wrote. Nothing will refer to it, and the sweep may not come for a long while, or at all on a nearly full disk,
     * where a save has to succeed before one is due. A write that keeps failing would also make every later save fail along with it. A Cancel gets here
     * on the thread of whoever called [import], which may be the main one, and this is a file operation for each frame, so it goes back to [dispatcher].
     */
    private suspend fun giveUp(run: Run) {
        if (run.written.isEmpty()) return
        withContext(dispatcher + NonCancellable) { store.discard(run.written) }
    }

    private suspend fun importAnimation(
        document: SvgDocument,
        request: SvgImportRequest.Animation,
        run: Run,
        onProgress: (Int, Int) -> Unit,
    ): SvgImportOutcome {
        // Lifted pixels stay lifted until the last step, because cancelling should not have touched them. See DrawingSession.applyEdit.
        val project = session.snapshot()
        val frameId = session.state.value.frameId
        val activeLayerId = session.state.value.layerId
        val sampling = SvgSampling.of(document.animation, request.durationSeconds, project.fps, request.withIntro)
        val tickCount = sampling.tickCount
        // Asked before the ticks are listed, which is the first thing here that takes up room for each one of them.
        if (tickCount > roomForImportedFrames(project)) return SvgImportOutcome.TOO_MANY_FRAMES
        val ticks = ImportEdits.ticksToSample(project, frameId, tickCount)
        val destination = destinationOn(project, document, request.fit)
        val images = HashMap<Int, PlacedImage>()
        val frames = FrameSampler(project.width, project.height)
        try {
            var last: PlacedImage? = null
            for ((index, tick) in ticks.withIndex()) {
                document.renderCancellable(frames.canvas, sampling.secondsAt(tick), destination)
                if (!frames.sameAsPrevious()) {
                    last = frames.cropToStore()?.also { run.written += it.file }
                    // Checking as it goes means a full disk ends the import early, instead of piling up every picture in memory first.
                    if (last != null && run.written.size % WRITE_CHECK_INTERVAL == 0) store.awaitWrites()
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
        applyAsOneStep(run, layerId) { current ->
            ImportEdits.addAnimationLayer(current, activeLayerId, frameId, request.layerName, tickCount, images, layerId)
        }
        return SvgImportOutcome.DONE
    }

    private suspend fun importSingleImage(
        document: SvgDocument,
        request: SvgImportRequest.SingleImage,
        run: Run,
        onProgress: (Int, Int) -> Unit,
    ): SvgImportOutcome {
        val project = session.snapshot()
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
                    run.written += image.file
                    store.awaitWrites()
                    val layerId = newId()
                    applyAsOneStep(run, layerId) { current ->
                        ImportEdits.addImageLayer(current, activeLayerId, frameId, request.layerName, image, layerId)
                    }
                }

                SvgImportPlace.CURRENT_LAYER -> {
                    // The picture goes over what the layer really holds, and that includes anything lifted. That stays lifted until the last step, as failing or
                    // cancelling between here and there should not have touched it.
                    val lifted = session.activeCelWithSelectionDown()
                    val image =
                        if (lifted != null) {
                            combine(lifted.image, lifted.bounds, picture, bounds).also { lifted.image?.recycle() }
                        } else {
                            combineWithCel(project, activeLayerId, frameId, picture, bounds) ?: return SvgImportOutcome.FAILED
                        }
                    run.written += image.file
                    store.awaitWrites()
                    applyAsOneStep(run, null) { current -> ImportEdits.replaceCel(current, activeLayerId, frameId, image) }
                }

                SvgImportPlace.BACKGROUND -> {
                    val background = saveBackground(picture)
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        session.applyBackground(background)
                        run.applied = true
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
        val existing = if (file == null) null else store.read(file) ?: return null
        return combine(existing, cel?.bounds, picture, bounds)
    }

    private fun combine(existing: Bitmap?, celBounds: IntRect?, picture: Bitmap, bounds: IntRect): PlacedImage {
        if (existing == null || celBounds == null) return PlacedImage(store.write(crop(picture, bounds)), bounds)
        val union = celBounds.union(bounds)
        val combined = Bitmap.createBitmap(union.width, union.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combined)
        canvas.translate(-union.left.toFloat(), -union.top.toFloat())
        canvas.drawBitmap(existing, celBounds.left.toFloat(), celBounds.top.toFloat(), null)
        canvas.drawBitmap(picture, 0f, 0f, null)
        return PlacedImage(store.write(combined), union)
    }

    /**
     * Once this starts, the import is going to count as done or as failed, and a Cancel can no longer stop it. What it comes to is noted down, and
     * [import] goes by that note, because a Cancel can still arrive in time to lose the result on the way back.
     */
    private suspend fun applyAsOneStep(run: Run, selectLayerId: String?, edit: (Project) -> Project) {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            session.applyEdit(selectLayerId, edit)
            run.applied = true
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
