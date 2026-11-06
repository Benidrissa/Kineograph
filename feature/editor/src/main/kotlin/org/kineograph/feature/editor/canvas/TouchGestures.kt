package org.kineograph.feature.editor.canvas

/** What a finished multi-finger touch turned out to be. */
internal enum class TapGesture { NONE, TWO_FINGER_TAP, THREE_FINGER_TAP }

/**
 * Tells quick multi-finger taps from pans and pinches. It only looks at numbers, not at MotionEvents, so its rules
 * can be unit-tested. A tap must be quick and must not wander.
 */
internal class TapClassifier(private val slopPx: Float, private val maxDurationMs: Long = 280L) {
    private var startMs = 0L
    private var maxPointers = 0
    private var moved = false

    fun begin(timeMs: Long, pointers: Int) {
        startMs = timeMs
        maxPointers = pointers
        moved = false
    }

    fun onPointerCount(pointers: Int) {
        if (pointers > maxPointers) maxPointers = pointers
    }

    /** Reports how far the fingers have moved in total from where they started. */
    fun onMoved(distancePx: Float) {
        if (distancePx > slopPx) moved = true
    }

    fun end(timeMs: Long): TapGesture {
        if (moved || timeMs - startMs > maxDurationMs) return TapGesture.NONE
        return when {
            maxPointers == 2 -> TapGesture.TWO_FINGER_TAP
            maxPointers >= 3 -> TapGesture.THREE_FINGER_TAP
            else -> TapGesture.NONE
        }
    }
}

/**
 * A two-finger tap means undo and a quick double one means fit to screen. To tell them apart, the first tap has to wait
 * a moment before it counts as undo. This keeps that logic free of timers so it can be tested.
 */
internal class DoubleTapResolver(private val windowMs: Long = 260L) {
    private var lastTapMs = Long.MIN_VALUE

    /** Records a two-finger tap. Returns true if it completes a double tap. */
    fun onTwoFingerTap(timeMs: Long): Boolean {
        val isDouble = lastTapMs != Long.MIN_VALUE && timeMs - lastTapMs <= windowMs
        lastTapMs = if (isDouble) Long.MIN_VALUE else timeMs
        return isDouble
    }

    /** How long to hold back the undo of a first tap. */
    val delayMs: Long get() = windowMs
}

/**
 * Decides whether a second finger landing during a stroke means "this is a pinch, cancel the stroke", or "that is
 * a resting palm, ignore it". Drawing has to begin the moment a finger lands, so this can only be decided afterwards.
 */
internal fun secondFingerStartsGesture(elapsedMs: Long, travelledPx: Float, windowMs: Long, maxTravelPx: Float): Boolean =
    elapsedMs <= windowMs && travelledPx <= maxTravelPx
