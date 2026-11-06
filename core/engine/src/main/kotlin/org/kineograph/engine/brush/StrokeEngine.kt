package org.kineograph.engine.brush

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Turns a sequence of input points into evenly spaced stamps along a smooth curve.
 *
 * The curve is a chain of quadratic Béziers through the midpoints of the input points, which is both
 * smooth and cheap. It means the last half-segment is not final until the next point arrives, so
 * [predict] draws that tail plus any predicted points on a scratch copy of the state. Final stamps
 * are therefore never affected by predicted ones.
 *
 * Randomness comes from a seeded generator, which keeps a stroke's jitter stable and makes tests
 * deterministic.
 */
public class StrokeEngine(private val spec: BrushSpec, seed: Long) {
    private val main = Walker(spec, seed)
    private val scratch = Walker(spec, seed)

    private var midX = 0f
    private var midY = 0f
    private var midPressure = 0f
    private var midTilt = 0f
    private var midOrientation = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastPressure = 0f
    private var lastTilt = 0f
    private var lastOrientation = 0f

    /** Starts a stroke, placing the first stamp so that a tap leaves a dot. */
    public fun begin(x: Float, y: Float, pressure: Float, tilt: Float, orientation: Float, out: StampBuffer) {
        midX = x
        midY = y
        midPressure = pressure
        midTilt = tilt
        midOrientation = orientation
        lastX = x
        lastY = y
        lastPressure = pressure
        lastTilt = tilt
        lastOrientation = orientation
        main.start(x, y, pressure, tilt, orientation, out)
    }

    /** Adds a real input point and appends any stamps it finalizes to [out]. */
    public fun add(x: Float, y: Float, pressure: Float, tilt: Float, orientation: Float, out: StampBuffer) {
        if (x == lastX && y == lastY) {
            lastPressure = pressure
            return
        }
        val newMidX = (lastX + x) * 0.5f
        val newMidY = (lastY + y) * 0.5f
        val newMidPressure = (lastPressure + pressure) * 0.5f
        val newMidTilt = (lastTilt + tilt) * 0.5f
        val newMidOrientation = (lastOrientation + orientation) * 0.5f
        main.quadTo(lastX, lastY, newMidX, newMidY, newMidPressure, newMidTilt, newMidOrientation, out)
        midX = newMidX
        midY = newMidY
        midPressure = newMidPressure
        midTilt = newMidTilt
        midOrientation = newMidOrientation
        lastX = x
        lastY = y
        lastPressure = pressure
        lastTilt = tilt
        lastOrientation = orientation
    }

    /**
     * Writes stamps for the unfinished tail, followed by [predicted] points, into [out]. The stroke's
     * real state does not change. [predicted] holds [predictedCount] entries of x, y, pressure, tilt,
     * orientation.
     */
    public fun predict(predicted: FloatArray, predictedCount: Int, out: StampBuffer) {
        scratch.copyFrom(main)
        var controlX = lastX
        var controlY = lastY
        var controlPressure = lastPressure
        var controlTilt = lastTilt
        var controlOrientation = lastOrientation
        for (i in 0 until predictedCount) {
            val offset = i * PREDICTED_STRIDE
            val x = predicted[offset]
            val y = predicted[offset + 1]
            val pressure = predicted[offset + 2]
            val tilt = predicted[offset + 3]
            val orientation = predicted[offset + 4]
            scratch.quadTo(
                controlX,
                controlY,
                (controlX + x) * 0.5f,
                (controlY + y) * 0.5f,
                (controlPressure + pressure) * 0.5f,
                (controlTilt + tilt) * 0.5f,
                (controlOrientation + orientation) * 0.5f,
                out,
            )
            controlX = x
            controlY = y
            controlPressure = pressure
            controlTilt = tilt
            controlOrientation = orientation
        }
        scratch.lineTo(controlX, controlY, controlPressure, controlTilt, controlOrientation, out)
    }

    /** Ends the stroke, drawing the remaining tail up to the last real point. */
    public fun finish(out: StampBuffer) {
        main.lineTo(lastX, lastY, lastPressure, lastTilt, lastOrientation, out)
    }

    public companion object {
        /** Floats per predicted point passed to [predict]. */
        public const val PREDICTED_STRIDE: Int = 5
    }

    /** Walks along the path, dropping a stamp each time it has covered the spacing distance. */
    private class Walker(private val spec: BrushSpec, seed: Long) {
        private var rngState = if (seed == 0L) DEFAULT_SEED else seed
        private var x = 0f
        private var y = 0f
        private var pressure = 0f
        private var tilt = 0f
        private var orientation = 0f
        private var distanceToNext = 0f

        fun copyFrom(other: Walker) {
            rngState = other.rngState
            x = other.x
            y = other.y
            pressure = other.pressure
            tilt = other.tilt
            orientation = other.orientation
            distanceToNext = other.distanceToNext
        }

        fun start(startX: Float, startY: Float, startPressure: Float, startTilt: Float, startOrientation: Float, out: StampBuffer) {
            x = startX
            y = startY
            pressure = startPressure
            tilt = startTilt
            orientation = startOrientation
            distanceToNext = place(startX, startY, startPressure, startTilt, startOrientation, out)
        }

        /** Follows a quadratic curve from the current point by cutting it into short straight lines. */
        fun quadTo(
            cx: Float,
            cy: Float,
            endX: Float,
            endY: Float,
            endPressure: Float,
            endTilt: Float,
            endOrientation: Float,
            out: StampBuffer,
        ) {
            val startX = x
            val startY = y
            val startPressure = pressure
            val startTilt = tilt
            val startOrientation = orientation
            val approxLength = hypot(cx - startX, cy - startY) + hypot(endX - cx, endY - cy)
            if (approxLength <= 0f) return
            val steps = ceil(approxLength / FLATTEN_STEP_PX).toInt().coerceIn(1, MAX_FLATTEN_STEPS)
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val inv = 1f - t
                val px = inv * inv * startX + 2f * inv * t * cx + t * t * endX
                val py = inv * inv * startY + 2f * inv * t * cy + t * t * endY
                lineTo(
                    px,
                    py,
                    startPressure + (endPressure - startPressure) * t,
                    startTilt + (endTilt - startTilt) * t,
                    startOrientation + (endOrientation - startOrientation) * t,
                    out,
                )
            }
        }

        fun lineTo(endX: Float, endY: Float, endPressure: Float, endTilt: Float, endOrientation: Float, out: StampBuffer) {
            val length = hypot(endX - x, endY - y)
            if (length <= 0f) {
                pressure = endPressure
                tilt = endTilt
                orientation = endOrientation
                return
            }
            var travelled = 0f
            val startX = x
            val startY = y
            val startPressure = pressure
            val startTilt = tilt
            val startOrientation = orientation
            while (travelled + distanceToNext <= length) {
                travelled += distanceToNext
                val t = travelled / length
                distanceToNext =
                    place(
                        startX + (endX - startX) * t,
                        startY + (endY - startY) * t,
                        startPressure + (endPressure - startPressure) * t,
                        startTilt + (endTilt - startTilt) * t,
                        startOrientation + (endOrientation - startOrientation) * t,
                        out,
                    )
            }
            distanceToNext -= length - travelled
            x = endX
            y = endY
            pressure = endPressure
            tilt = endTilt
            orientation = endOrientation
        }

        /** Places one stamp and returns the distance to the next. */
        private fun place(px: Float, py: Float, p: Float, stampTilt: Float, stampOrientation: Float, out: StampBuffer): Float {
            val tiltAmount = if (spec.tiltShading > 0f) abs(sin(stampTilt)) * spec.tiltShading else 0f
            var diameter = spec.sizePx * spec.sizeCurve.evaluate(p) * (1f + tiltAmount * TILT_WIDEN)
            val baseDiameter = diameter
            if (spec.sizeJitter > 0f) diameter *= 1f + spec.sizeJitter * nextSigned()
            diameter = maxOf(diameter, MIN_DIAMETER)

            var alpha = (spec.flow * spec.flowCurve.evaluate(p) * (1f - tiltAmount * TILT_LIGHTEN)).coerceIn(0f, 1f)
            if (diameter < 1f) alpha *= diameter

            var angle = 0f
            if (!spec.isRotationInvariant) {
                angle = spec.tipAngleDegrees * DEGREES_TO_RADIANS
                if (spec.tiltShading > 0f) angle += stampOrientation
                if (spec.angleJitterDegrees > 0f) angle += spec.angleJitterDegrees * DEGREES_TO_RADIANS * nextSigned()
            }

            var stampX = px
            var stampY = py
            if (spec.scatter > 0f) {
                val reach = spec.scatter * baseDiameter
                stampX += reach * nextSigned()
                stampY += reach * nextSigned()
            }
            if (!spec.antiAlias) {
                stampX = kotlin.math.floor(stampX) + 0.5f
                stampY = kotlin.math.floor(stampY) + 0.5f
            }
            out.add(stampX, stampY, diameter, alpha, angle)
            return if (spec.antiAlias) maxOf(MIN_SPACING_PX, spec.spacing * baseDiameter) else 1f
        }

        /** A random value in -1..1 from a xorshift generator. */
        private fun nextSigned(): Float {
            var s = rngState
            s = s xor (s shl 13)
            s = s xor (s ushr 7)
            s = s xor (s shl 17)
            rngState = s
            val unit = ((s ushr 40).toInt() and 0xFFFFFF) / 16777215f
            return unit * 2f - 1f
        }

        private companion object {
            const val DEFAULT_SEED = 0x2545F4914F6CDD1DL
            const val FLATTEN_STEP_PX = 2f
            const val MAX_FLATTEN_STEPS = 256
            const val MIN_SPACING_PX = 0.35f
            const val MIN_DIAMETER = 0.5f
            const val TILT_WIDEN = 2.2f
            const val TILT_LIGHTEN = 0.6f
            const val DEGREES_TO_RADIANS = (PI / 180.0).toFloat()
        }
    }
}
