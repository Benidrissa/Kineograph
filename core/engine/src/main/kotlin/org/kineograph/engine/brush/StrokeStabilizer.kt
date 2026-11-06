package org.kineograph.engine.brush

import kotlin.math.hypot

/**
 * Steadies a shaky hand. Two techniques run together, both controlled by one 0..100 amount:
 *
 *  - exponential smoothing, which filters out jitter;
 *  - a "pulled string": the brush trails the finger on a string of fixed length and only moves when
 *    the string is taut, which removes small wobbles completely.
 *
 * At 0 the input passes through untouched. Call [position] after each [push] for the steadied point.
 */
public class StrokeStabilizer(amount: Int, private val stringLengthAtMax: Float) {
    private val strength = amount.coerceIn(0, 100) / 100f

    /** Fraction of the remaining distance covered per update; 1 means no smoothing. */
    private val follow = 1f - strength * MAX_SMOOTHING
    private val stringLength = stringLengthAtMax * strength

    public var x: Float = 0f
        private set
    public var y: Float = 0f
        private set
    private var rawX = 0f
    private var rawY = 0f
    private var smoothedX = 0f
    private var smoothedY = 0f

    public fun reset(startX: Float, startY: Float) {
        x = startX
        y = startY
        rawX = startX
        rawY = startY
        smoothedX = startX
        smoothedY = startY
    }

    /** Feeds a new input point, updating [x] and [y]. */
    public fun push(inputX: Float, inputY: Float) {
        rawX = inputX
        rawY = inputY
        if (strength <= 0f) {
            x = inputX
            y = inputY
            return
        }
        smoothedX += (inputX - smoothedX) * follow
        smoothedY += (inputY - smoothedY) * follow
        val dx = smoothedX - x
        val dy = smoothedY - y
        val distance = hypot(dx, dy)
        if (distance > stringLength) {
            val pull = (distance - stringLength) / distance
            x += dx * pull
            y += dy * pull
        }
    }

    /** The last raw input, which the end of a stroke catches up to so that strokes finish where the pen lifted. */
    public val lastInputX: Float get() = rawX
    public val lastInputY: Float get() = rawY

    private companion object {
        const val MAX_SMOOTHING = 0.88f
    }
}

/** Smooths pressure over time, which steadies the jittery readings of inexpensive styluses. */
public class PressureSmoother(amount: Int) {
    private val follow = 1f - amount.coerceIn(0, 100) / 100f * 0.85f
    private var value = -1f

    public fun reset() {
        value = -1f
    }

    public fun next(pressure: Float): Float {
        value = if (value < 0f) pressure else value + (pressure - value) * follow
        return value
    }
}
