package org.kineograph.engine.vector

import org.kineograph.engine.brush.StrokeRecording
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Why two drawings' strokes cannot be paired. */
public enum class PairingProblem {
    /** Neither drawing has a stroke. */
    NOTHING_DRAWN,

    /** The drawings have different numbers of strokes. */
    STROKE_COUNTS_DIFFER,

    /** A stroke in one is drawn with a different brush, or one erases where the other paints. */
    BRUSH_DIFFERS,
}

/** Strokes of two drawings matched one to one, or why they could not be. */
public sealed interface StrokePairing {
    public class Paired(public val pairs: List<Pair<RecordedStroke, RecordedStroke>>) : StrokePairing

    /** [first] and [second] are the stroke counts, or for [PairingProblem.BRUSH_DIFFERS] the stroke's number, from 1, in [first]. */
    public class Refused(public val problem: PairingProblem, public val first: Int, public val second: Int) : StrokePairing
}

/**
 * Works out drawings between two others, stroke by stroke, with no model. Strokes pair up in the order they were drawn, so the two drawings
 * need the same number of strokes, drawn in the same order with the same brushes. Each pair becomes a stroke part of the way from one to the
 * other, which the same engine then draws: a pencil keeps its grain, because every in-between draws with the first drawing's seed.
 */
public object InBetweens {
    public fun pair(first: List<RecordedStroke>, second: List<RecordedStroke>): StrokePairing {
        if (first.isEmpty() && second.isEmpty()) return StrokePairing.Refused(PairingProblem.NOTHING_DRAWN, 0, 0)
        if (first.size != second.size) return StrokePairing.Refused(PairingProblem.STROKE_COUNTS_DIFFER, first.size, second.size)
        for (i in first.indices) {
            val a = first[i].spec
            val b = second[i].spec
            if (a.id != b.id || a.isEraser != b.isEraser) return StrokePairing.Refused(PairingProblem.BRUSH_DIFFERS, i + 1, 0)
        }
        return StrokePairing.Paired(first.zip(second))
    }

    /** Where the [index]th of [count] in-betweens sits between the two drawings, from 0 to 1, evenly spaced. */
    public fun position(index: Int, count: Int): Float = index.toFloat() / (count + 1)

    /**
     * A stroke [t] of the way from [a] to [b]. Both are walked by distance along them, so a stroke drawn slowly in one place and quickly in
     * another still lines up. A stroke drawn the other way round is turned first, so the two ends meet the ends nearest them.
     */
    public fun mix(a: RecordedStroke, b: RecordedStroke, t: Float): RecordedStroke {
        val stride = if (a.stride == StrokeRecording.STRIDE_WITH_TILT &&
            b.stride == StrokeRecording.STRIDE_WITH_TILT
        ) {
            a.stride
        } else {
            STRIDE_PLAIN
        }
        val count = max(a.count, b.count).coerceAtLeast(1)
        val first = resample(a, count, stride, reverse = false)
        val second = resample(b, count, stride, reverse = isReversed(a, b))
        val samples = FloatArray(count * stride)
        for (i in samples.indices) {
            val channel = i % stride
            samples[i] =
                if (channel == ORIENTATION) {
                    mixAngle(first[i], second[i], t)
                } else {
                    first[i] + (second[i] - first[i]) * t
                }
        }
        val spec = a.spec.copy(sizePx = mixFloat(a.spec.sizePx, b.spec.sizePx, t), opacity = mixFloat(a.spec.opacity, b.spec.opacity, t))
        return RecordedStroke(spec, a.seed, mixColor(a.colorArgb, b.colorArgb, t), samples, count, stride, a.clip.union(b.clip))
    }

    /** Whether [b] runs the other way from [a], judged by which pairing of their ends is closer. */
    private fun isReversed(a: RecordedStroke, b: RecordedStroke): Boolean {
        if (a.count < 2 || b.count < 2) return false
        val sameWay = distance(a, 0, b, 0) + distance(a, a.count - 1, b, b.count - 1)
        val otherWay = distance(a, 0, b, b.count - 1) + distance(a, a.count - 1, b, 0)
        return otherWay < sameWay * REVERSE_MARGIN
    }

    private fun distance(a: RecordedStroke, i: Int, b: RecordedStroke, j: Int): Float =
        hypot(a.samples[i * a.stride] - b.samples[j * b.stride], a.samples[i * a.stride + 1] - b.samples[j * b.stride + 1])

    /** [count] samples evenly spaced along [stroke], [stride] floats each. */
    private fun resample(stroke: RecordedStroke, count: Int, stride: Int, reverse: Boolean): FloatArray {
        val n = stroke.count
        val out = FloatArray(count * stride)
        fun sample(i: Int, channel: Int): Float {
            val index = if (reverse) n - 1 - i else i
            return stroke.samples[index * stroke.stride + channel]
        }
        if (n == 1 || count == 1) {
            for (k in 0 until count) for (c in 0 until stride) out[k * stride + c] = sample(0, c)
            return out
        }
        val along = FloatArray(n)
        for (i in 1 until n) {
            along[i] = along[i - 1] + hypot(sample(i, 0) - sample(i - 1, 0), sample(i, 1) - sample(i - 1, 1))
        }
        val total = along[n - 1]
        var segment = 0
        for (k in 0 until count) {
            val u = k.toFloat() / (count - 1)
            if (total <= 0f) {
                // A stroke that never moved: walk its samples by number instead.
                val at = u * (n - 1)
                val i = at.toInt().coerceAtMost(n - 2)
                val f = at - i
                for (c in 0 until stride) out[k * stride + c] = blend(sample(i, c), sample(i + 1, c), f, c)
                continue
            }
            val target = u * total
            while (segment < n - 2 && along[segment + 1] < target) segment++
            val span = along[segment + 1] - along[segment]
            val f = if (span <= 0f) 0f else ((target - along[segment]) / span).coerceIn(0f, 1f)
            for (c in 0 until stride) out[k * stride + c] = blend(sample(segment, c), sample(segment + 1, c), f, c)
        }
        return out
    }

    private fun blend(from: Float, to: Float, f: Float, channel: Int): Float = if (channel ==
        ORIENTATION
    ) {
        mixAngle(from, to, f)
    } else {
        from + (to - from) * f
    }

    /** Orientation goes round, so the short way between two angles is the one to take. */
    private fun mixAngle(from: Float, to: Float, t: Float): Float {
        var delta = (to - from) % TWO_PI
        if (delta > PI) delta -= TWO_PI
        if (delta < -PI) delta += TWO_PI
        return from + delta * t
    }

    private fun mixFloat(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    private fun mixColor(from: Int, to: Int, t: Float): Int {
        var result = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            result = result or ((a + (b - a) * t).roundToInt().coerceIn(0, 255) shl shift)
        }
        return result
    }

    private const val STRIDE_PLAIN = 3
    private const val ORIENTATION = 4
    private const val TWO_PI = (2 * PI).toFloat()

    /** Turning a stroke round has to be clearly better, so that a closed loop is left as it was drawn. */
    private const val REVERSE_MARGIN = 0.7f
}
