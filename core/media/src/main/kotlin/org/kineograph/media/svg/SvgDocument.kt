package org.kineograph.media.svg

import android.graphics.Canvas
import android.graphics.RectF

/**
 * A parsed SVG picture, ready to draw at any moment in its animation. Nothing about it depends on the app's UI, and drawing keeps no
 * state between frames: frames can be drawn in any order, and the same time always gives the same picture.
 *
 * @property width intrinsic width in pixels, from the file's `width` and `viewBox`.
 * @property height intrinsic height in pixels.
 * @property animation whether the picture moves, and for how long.
 * @property warnings what the file contained that was not reproduced.
 */
public class SvgDocument internal constructor(
    private val model: SvgModel,
    public val animation: SvgAnimationInfo,
    public val warnings: Set<SvgWarning>,
) {
    public val width: Float get() = model.width.toFloat()
    public val height: Float get() = model.height.toFloat()

    /**
     * Draws the picture as it looks at [timeSeconds] into [destination], keeping its aspect ratio as the file asks.
     *
     * This never throws because of what the file contains. A file that asks for more than the device has leaves the canvas with what
     * was drawn up to then. Safe to call from any thread, but concurrent calls on one document take turns.
     *
     * @return true if the whole picture was drawn, false if something stopped it early.
     */
    public fun render(canvas: Canvas, timeSeconds: Double, destination: RectF): Boolean {
        if (destination.isEmpty) return true
        val time = if (timeSeconds.isNaN() || timeSeconds < 0.0) 0.0 else timeSeconds.coerceAtMost(MAX_TIME_SECONDS)
        val count = canvas.save()
        return try {
            // Caches inside the model, and the fill rule of its shared paths, are written during drawing.
            synchronized(model) {
                val renderer = SvgRenderer(model, canvas, time)
                renderer.render(destination)
                !renderer.isIncomplete
            }
        } catch (_: RuntimeException) {
            false
        } catch (_: StackOverflowError) {
            false
        } catch (_: OutOfMemoryError) {
            false
        } finally {
            canvas.restoreToCount(count)
        }
    }

    private companion object {
        // About thirty years. Far beyond any real use, yet small enough that dividing it by a duration still gives exact numbers.
        const val MAX_TIME_SECONDS = 1e9
    }
}
