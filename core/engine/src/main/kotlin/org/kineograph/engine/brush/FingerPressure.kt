package org.kineograph.engine.brush

import kotlin.math.hypot

/**
 * Most phones have no stylus and report a constant pressure for fingers. This fakes pressure from
 * speed, the way a real pen behaves: slow strokes come out heavy and fast strokes come out thin, and
 * every stroke eases in rather than starting at full weight.
 *
 * Positions are in screen pixels so the feel does not change with zoom; [fastPxPerMs] is the speed at
 * which pressure bottoms out.
 */
public class FingerPressure(private val fastPxPerMs: Float) {
    private var lastX = 0f
    private var lastY = 0f
    private var lastTimeMs = 0L
    private var speed = 0f
    private var pressure = START_PRESSURE

    public fun reset(x: Float, y: Float, timeMs: Long) {
        lastX = x
        lastY = y
        lastTimeMs = timeMs
        speed = 0f
        pressure = START_PRESSURE
    }

    /** The first pressure of a stroke. */
    public fun start(): Float = START_PRESSURE

    public fun next(x: Float, y: Float, timeMs: Long): Float {
        val dt = (timeMs - lastTimeMs).coerceAtLeast(1L)
        val instant = hypot(x - lastX, y - lastY) / dt
        speed += (instant - speed) * SPEED_FOLLOW
        lastX = x
        lastY = y
        lastTimeMs = timeMs
        val normalized = (speed / fastPxPerMs).coerceIn(0f, 1f)
        val eased = normalized * normalized * (3f - 2f * normalized)
        val target = SLOW_PRESSURE + (FAST_PRESSURE - SLOW_PRESSURE) * eased
        pressure += (target - pressure) * PRESSURE_FOLLOW
        return pressure
    }

    private companion object {
        const val START_PRESSURE = 0.45f
        const val SLOW_PRESSURE = 1f
        const val FAST_PRESSURE = 0.3f
        const val SPEED_FOLLOW = 0.35f
        const val PRESSURE_FOLLOW = 0.3f
    }
}
