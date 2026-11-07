package org.kineograph.media.svg

import android.graphics.Canvas
import android.graphics.RectF
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Far beyond any real use, about thirty years, yet small enough that dividing it by a duration still gives exact numbers. */
internal const val MAX_TIME_SECONDS: Double = 1e9

/**
 * A parsed SVG picture, ready to draw at any moment in its animation. Nothing about it depends on the app's UI, and drawing keeps no
 * state between frames: frames can be drawn in any order, and the same time always gives the same picture.
 *
 * @property animation whether the picture moves, and for how long.
 */
public class SvgDocument internal constructor(
    internal val model: SvgModel,
    public val animation: SvgAnimationInfo,
    private val parseWarnings: Set<SvgWarning>,
) {
    /** A frame stopped by [SvgLimits.maxRenderWork] says so here as well. Whether it happened changes nothing about the next frame. */
    @Volatile
    private var aFrameWasCut = false

    // Not a monitor, because waiting for one cannot be given up on. A cancelled frame must not sit behind one that is still drawing.
    private val drawing = ReentrantLock()

    /** What the file contained that was not reproduced, including whatever was only found out while drawing. */
    public val warnings: Set<SvgWarning>
        get() = if (aFrameWasCut) parseWarnings + SvgWarning.LIMIT_REACHED else parseWarnings

    /** Intrinsic width in pixels, from the file's `width` and `viewBox`. Whatever the file says, this is from 1 to [MAX_REPORTED_SIZE]. */
    public val width: Float get() = reportedSize(model.width, model.height)

    /** Intrinsic height in pixels. Whatever the file says, this is from 1 to [MAX_REPORTED_SIZE]. */
    public val height: Float get() = reportedSize(model.height, model.width)

    /** A size a caller can make a bitmap from. Too big, it comes down with its other side, so that the picture keeps its shape. */
    private fun reportedSize(own: Double, other: Double): Float {
        val a = if (own.isFinite()) own else 0.0
        val b = if (other.isFinite()) other else 0.0
        val largest = maxOf(a, b)
        val scaled = if (largest > MAX_REPORTED_SIZE) a / largest * MAX_REPORTED_SIZE else a
        return scaled.coerceIn(1.0, MAX_REPORTED_SIZE).toFloat()
    }

    /**
     * Draws the picture as it looks at [timeSeconds] into [destination], keeping its aspect ratio as the file asks.
     *
     * This never throws because of what the file contains. A file that asks for more than the device has leaves the canvas with what
     * was drawn up to then, and [warnings] then holds [SvgWarning.LIMIT_REACHED]. Safe to call from any thread, but concurrent calls on
     * one document take turns.
     *
     * @param shouldStop asked often while drawing. Returning true stops it soon after, and so does interrupting the thread. Only a single
     *   native call can delay that, and those are priced so that none can take long. See [renderCancellable] for coroutines.
     * @return true if the whole picture was drawn, false if something stopped it early.
     */
    public fun render(canvas: Canvas, timeSeconds: Double, destination: RectF, shouldStop: () -> Boolean = { false }): Boolean {
        if (destination.isEmpty) return true
        val time = if (timeSeconds.isNaN() || timeSeconds < 0.0) 0.0 else timeSeconds.coerceAtMost(MAX_TIME_SECONDS)
        if (!waitForTurn(shouldStop)) return false
        val count = canvas.save()
        try {
            val work = FrameWork(model.limits, canvasPixels(canvas, destination), shouldStop)
            draw(canvas, time, destination, work)
            // An unfinished frame is only a limit reached if nobody asked it to stop.
            if (!work.isComplete && !work.isCancelled) aFrameWasCut = true
            return work.isComplete
        } catch (_: RuntimeException) {
            aFrameWasCut = true
        } catch (_: StackOverflowError) {
            aFrameWasCut = true
        } catch (_: OutOfMemoryError) {
            aFrameWasCut = true
        } finally {
            canvas.restoreToCount(count)
            model.glyphs.trim()
            drawing.unlock()
        }
        return false
    }

    /**
     * [render] for a coroutine. It does its work on the thread it is called on, so that belongs on a background dispatcher, and it stops
     * when the coroutine is cancelled, leaving what was drawn up to then.
     */
    public suspend fun renderCancellable(canvas: Canvas, timeSeconds: Double, destination: RectF): Boolean {
        val context = currentCoroutineContext()
        val job = context[Job]
        val complete = render(canvas, timeSeconds, destination) { job?.isActive == false }
        context.ensureActive()
        return complete
    }

    /** A picture inside another shares its frame, so that its work counts against the one allowance and its depth adds to the outer one. */
    internal fun drawNested(canvas: Canvas, time: Double, destination: RectF, frame: FrameWork) {
        draw(canvas, time, destination, frame)
    }

    private fun draw(canvas: Canvas, time: Double, destination: RectF, frame: FrameWork) {
        val renderer = SvgRenderer(model, canvas, time, frame)
        try {
            renderer.render(destination)
        } finally {
            renderer.release()
        }
    }

    private fun waitForTurn(shouldStop: () -> Boolean): Boolean {
        try {
            while (!drawing.tryLock(TURN_CHECK_MILLIS, TimeUnit.MILLISECONDS)) {
                if (shouldStop()) return false
            }
            return true
        } catch (_: InterruptedException) {
            // Whoever interrupted this still needs to be able to see that it did.
            Thread.currentThread().interrupt()
            return false
        }
    }

    /** What a frame can touch at most, which is what the work on its pixels can come to. */
    private fun canvasPixels(canvas: Canvas, destination: RectF): Long {
        val wanted = destination.width().toDouble() * destination.height()
        val available = canvas.width.toDouble() * canvas.height
        val pixels = if (available > 0.0) minOf(wanted, available) else wanted
        return pixels.coerceIn(1.0, MAX_CANVAS_PIXELS).toLong()
    }

    public companion object {
        /** The largest [width] or [height] a document reports. No picture has a reason to be sized past this. */
        public const val MAX_REPORTED_SIZE: Double = 1e6

        private const val TURN_CHECK_MILLIS = 50L
        private const val MAX_CANVAS_PIXELS = 1e10
    }
}
