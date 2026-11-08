package org.kineograph.engine.vector

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.kineograph.engine.session.CelStore
import org.kineograph.engine.session.DrawingSession
import org.kineograph.model.Cel
import org.kineograph.model.CelKey
import org.kineograph.model.IntRect
import org.kineograph.model.Project
import org.kineograph.model.ProjectEdits
import org.kineograph.model.newId
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** How making in-betweens went. */
public enum class InBetweenOutcome {
    DONE,

    /** The frame is the last one, so there is nothing to make in-betweens towards. */
    NO_NEXT_FRAME,

    /** A drawing's strokes are not known: it was drawn before the project was opened, or came from a paste, a merge or an import. */
    NOT_RECORDED,

    /** A drawing's strokes do not give its pixels, so something else changed it, such as a fill or the lasso. */
    DRAWN_OTHERWISE,
    NOTHING_DRAWN,
    STROKE_COUNTS_DIFFER,
    BRUSH_DIFFERS,
    OUT_OF_MEMORY,
    OUT_OF_STORAGE,
    FAILED,
}

/**
 * [frame] is the frame number, from 1, that the outcome is about, where there is one. [first] and [second] are the stroke counts for
 * [InBetweenOutcome.STROKE_COUNTS_DIFFER], and [first] is the stroke's number for [InBetweenOutcome.BRUSH_DIFFERS].
 */
public data class InBetweenResult(val outcome: InBetweenOutcome, val frame: Int = 0, val first: Int = 0, val second: Int = 0)

/**
 * Makes drawings between a frame and the next one on one layer, as new frames between them, from the strokes both were drawn with. Each new
 * frame keeps the first frame's drawings on every other layer.
 *
 * Both drawings are drawn again from their strokes and compared with what is saved first, so a drawing changed some other way is refused rather
 * than given wrong in-betweens. The project only changes in the last step, as one undo step, so failing or cancelling before that changes
 * nothing and takes back what was written. While this runs, the files it writes are not yet part of the project, so whoever calls it must keep
 * the sweep off them. See `Autosaver.holdSweeps`.
 */
public class InBetweenMaker(
    private val session: DrawingSession,
    private val store: CelStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private class Run {
        val written = ArrayList<String>()

        @Volatile
        var applied = false
    }

    public suspend fun make(
        layerId: String,
        frameId: String,
        count: Int,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): InBetweenResult {
        val run = Run()
        return try {
            withContext(dispatcher) {
                try {
                    makeNow(layerId, frameId, count.coerceAtLeast(1), run, onProgress)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: OutOfMemoryError) {
                    session.trimMemory()
                    giveUp(run)
                    InBetweenResult(InBetweenOutcome.OUT_OF_MEMORY)
                } catch (e: IOException) {
                    giveUp(run)
                    val memory = generateSequence<Throwable>(e) { it.cause }.any { it is OutOfMemoryError }
                    InBetweenResult(if (memory) InBetweenOutcome.OUT_OF_MEMORY else InBetweenOutcome.OUT_OF_STORAGE)
                } catch (_: IllegalArgumentException) {
                    // A bitmap refuses a size past what the platform can address, which comes to the same thing for the person using the app.
                    giveUp(run)
                    InBetweenResult(InBetweenOutcome.OUT_OF_MEMORY)
                } catch (_: RuntimeException) {
                    giveUp(run)
                    InBetweenResult(InBetweenOutcome.FAILED)
                }
            }
        } catch (e: CancellationException) {
            if (run.applied) return InBetweenResult(InBetweenOutcome.DONE)
            giveUp(run)
            throw e
        }
    }

    private suspend fun giveUp(run: Run) {
        if (run.written.isEmpty()) return
        withContext(dispatcher + NonCancellable) { store.discard(run.written) }
    }

    private suspend fun makeNow(
        layerId: String,
        frameId: String,
        count: Int,
        run: Run,
        onProgress: (Int, Int) -> Unit,
    ): InBetweenResult {
        val source = session.strokesFor(layerId, frameId)
        val project = source.project
        val index = project.frameIndex(frameId)
        if (source.cels.size < 2 || index < 0) return InBetweenResult(InBetweenOutcome.NO_NEXT_FRAME)
        val numbers = intArrayOf(index + 1, index + 2)
        val strokes = ArrayList<List<RecordedStroke>>(2)
        for (side in 0..1) {
            val log = source.logs[side] ?: return InBetweenResult(InBetweenOutcome.NOT_RECORDED, numbers[side])
            val list = log.toList()
            if (!matches(source.cels[side], list)) return InBetweenResult(InBetweenOutcome.DRAWN_OTHERWISE, numbers[side])
            strokes += list
        }
        val pairs =
            when (val pairing = InBetweens.pair(strokes[0], strokes[1])) {
                is StrokePairing.Refused -> return refusal(pairing, numbers)
                is StrokePairing.Paired -> pairing.pairs
            }
        onProgress(0, count)

        val images = ArrayList<PlacedFrame>(count)
        val region = pairs.fold(IntRect.EMPTY) { area, (a, b) -> area.union(a.clip).union(b.clip) }
        for (k in 1..count) {
            coroutineContext.ensureActive()
            val t = InBetweens.position(k, count)
            val mixed = pairs.map { (a, b) -> InBetweens.mix(a, b, t) }
            images += draw(mixed, region, run)
            if (k % WRITE_CHECK_INTERVAL == 0) store.awaitWrites()
            onProgress(k, count)
        }
        store.awaitWrites()
        coroutineContext.ensureActive()
        val logs = images.mapNotNull { frame -> frame.file?.let { it to frame.log } }.toMap()
        session.applyEdit(strokes = logs) { current -> insertAfter(current, frameId, layerId, images) }
        run.applied = true
        return InBetweenResult(InBetweenOutcome.DONE)
    }

    private fun refusal(pairing: StrokePairing.Refused, numbers: IntArray): InBetweenResult =
        when (pairing.problem) {
            PairingProblem.NOTHING_DRAWN -> InBetweenResult(InBetweenOutcome.NOTHING_DRAWN)

            PairingProblem.STROKE_COUNTS_DIFFER -> InBetweenResult(
                InBetweenOutcome.STROKE_COUNTS_DIFFER,
                numbers[0],
                pairing.first,
                pairing.second,
            )

            PairingProblem.BRUSH_DIFFERS -> InBetweenResult(InBetweenOutcome.BRUSH_DIFFERS, numbers[0], pairing.first)
        }

    /** Whether [strokes], drawn again, give [cel]'s pixels. */
    private fun matches(cel: Cel?, strokes: List<RecordedStroke>): Boolean {
        val file = cel?.file
        val bounds = cel?.bounds
        if (file == null || bounds == null) return strokes.isEmpty()
        if (strokes.isEmpty()) return false
        val image = store.read(file) ?: throw IOException("The drawing could not be read")
        val region = strokes.fold(bounds) { area, stroke -> area.union(stroke.clip) }
        val replay = StrokeReplay(region)
        try {
            strokes.forEach { stroke ->
                replay.replay(
                    stroke.spec,
                    stroke.seed,
                    stroke.colorArgb,
                    stroke.samples,
                    stroke.count,
                    stroke.stride,
                    stroke.clip,
                    stroke.offsetX,
                    stroke.offsetY,
                )
            }
            return ReplayDifference.between(image, bounds.left, bounds.top, replay.layer, region.left, region.top).isWithinTolerance
        } finally {
            replay.release()
        }
    }

    /** Draws one in-between and writes it, cropped to what it covers. */
    private fun draw(strokes: List<RecordedStroke>, region: IntRect, run: Run): PlacedFrame {
        val replay = StrokeReplay(region)
        try {
            strokes.forEach { stroke ->
                replay.replay(
                    stroke.spec,
                    stroke.seed,
                    stroke.colorArgb,
                    stroke.samples,
                    stroke.count,
                    stroke.stride,
                    stroke.clip,
                    stroke.offsetX,
                    stroke.offsetY,
                )
            }
            val covered = coveredArea(replay.layer) ?: return PlacedFrame(null, null, StrokeLog.of(strokes))
            val crop = Bitmap.createBitmap(replay.layer, covered.left, covered.top, covered.width, covered.height)
            val file = store.write(crop)
            run.written += file
            val bounds = covered.translated(region.left, region.top)
            store.keepStrokes(file, bounds, strokes)
            return PlacedFrame(file, bounds, StrokeLog.of(strokes))
        } finally {
            replay.release()
        }
    }

    /** The smallest rectangle holding every pixel that is not fully clear, or null if there is none. */
    private fun coveredArea(image: Bitmap): IntRect? {
        val row = IntArray(image.width)
        var left = image.width
        var top = image.height
        var right = 0
        var bottom = 0
        for (y in 0 until image.height) {
            image.getPixels(row, 0, image.width, 0, y, image.width, 1)
            var first = -1
            var last = -1
            for (x in row.indices) {
                if (row[x] ushr ALPHA_SHIFT != 0) {
                    if (first < 0) first = x
                    last = x
                }
            }
            if (first < 0) continue
            if (first < left) left = first
            if (last + 1 > right) right = last + 1
            if (y < top) top = y
            bottom = y + 1
        }
        return if (right <= left || bottom <= top) null else IntRect(left, top, right, bottom)
    }

    private class PlacedFrame(val file: String?, val bounds: IntRect?, val log: StrokeLog)

    private companion object {
        const val WRITE_CHECK_INTERVAL = 4
        const val ALPHA_SHIFT = 24

        /** New frames go straight after [frameId], in order. On every other layer each holds what [frameId] holds. */
        fun insertAfter(project: Project, frameId: String, layerId: String, images: List<PlacedFrame>): Project {
            if (project.frameIndex(frameId) < 0) return project
            var result = project
            var anchor = frameId
            for (image in images) {
                val id = newId()
                result = ProjectEdits.addFrameAfter(result, anchor, id)
                for (layer in result.layers) {
                    val cel =
                        if (layer.id == layerId) {
                            if (image.file != null && image.bounds != null) Cel(layer.id, id, image.file, image.bounds) else null
                        } else {
                            result.cel(layer.id, frameId)?.copy(frameId = id)
                        }
                    if (cel != null) result = ProjectEdits.withCel(result, CelKey(layer.id, id), cel)
                }
                anchor = id
            }
            return result
        }
    }
}
