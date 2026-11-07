package org.kineograph.feature.editor.canvas

import android.view.MotionEvent

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

/**
 * Whether a pointer is a pen asking to erase: its eraser end, or its button held. Button state belongs to the whole event and not to
 * one pointer, so a finger sharing an event with a pen sees it too, and has to be told apart here.
 */
internal fun penAsksToErase(toolType: Int, buttonState: Int): Boolean =
    when (toolType) {
        MotionEvent.TOOL_TYPE_ERASER -> true
        MotionEvent.TOOL_TYPE_STYLUS -> buttonState and PEN_ERASE_BUTTONS != 0
        else -> false
    }

/**
 * The usual action for one of the private ones Samsung has used for an S Pen with its button held, or null for any other action.
 * Without this, a pen that lands with the button held would never start, and one pressed mid-stroke would never hear that it lifted.
 *
 * These have no pointer-down or pointer-up of their own, so another pointer being there decides which of those is meant. A down during
 * a touch has to carry that touch on: starting over would leave the open stroke unfinished, and the low-latency path would then hold
 * back every stroke after it.
 */
internal fun standardActionOfSPen(actionMasked: Int, pointerCount: Int, touchUnderWay: Boolean): Int? =
    when (actionMasked) {
        SPEN_ACTION_DOWN -> {
            when {
                pointerCount > 1 -> MotionEvent.ACTION_POINTER_DOWN
                touchUnderWay -> MotionEvent.ACTION_MOVE
                else -> MotionEvent.ACTION_DOWN
            }
        }

        SPEN_ACTION_UP -> {
            if (pointerCount > 1) MotionEvent.ACTION_POINTER_UP else MotionEvent.ACTION_UP
        }

        SPEN_ACTION_MOVE -> {
            MotionEvent.ACTION_MOVE
        }

        SPEN_ACTION_CANCEL -> {
            MotionEvent.ACTION_CANCEL
        }

        else -> {
            null
        }
    }

// Android 5 and earlier reported a pen's button as the secondary one, and some devices still do.
private const val PEN_ERASE_BUTTONS =
    MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY or MotionEvent.BUTTON_SECONDARY
private const val SPEN_ACTION_DOWN = 211
private const val SPEN_ACTION_UP = 212
private const val SPEN_ACTION_MOVE = 213
private const val SPEN_ACTION_CANCEL = 214
