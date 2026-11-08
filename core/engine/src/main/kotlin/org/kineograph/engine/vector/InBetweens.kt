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

/** How in-betweens are spread out between the two drawings. */
public enum class InBetweenSpacing {
    /** The same distance from each frame to the next. */
    EVEN,

    /** Close together near the first drawing, so the movement starts slowly and speeds up. */
    SLOW_START,

    /** Close together near the second drawing, so the movement slows down as it arrives. */
    SLOW_END,

    /** Close together near both drawings, so the movement starts and stops gently. */
    SLOW_BOTH,
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

    /** Where the [index]th, from 1, of [count] in-betweens sits between the two drawings, from 0 to 1. */
    public fun position(index: Int, count: Int, spacing: InBetweenSpacing = InBetweenSpacing.EVEN): Float {
        val u = index.toFloat() / (count + 1)
        return when (spacing) {
            InBetweenSpacing.EVEN -> u
            InBetweenSpacing.SLOW_START -> u * u
            InBetweenSpacing.SLOW_END -> 1f - (1f - u) * (1f - u)
            InBetweenSpacing.SLOW_BOTH -> u * u * (3f - 2f * u)
        }
    }

    /** The middle lines of [pairs], [points] points each, for showing in-betweens before they are made. */
    public fun sketch(pairs: List<Pair<RecordedStroke, RecordedStroke>>, points: Int = SKETCH_POINTS): InBetweenSketch {
        val first = ArrayList<FloatArray>(pairs.size)
        val second = ArrayList<FloatArray>(pairs.size)
        val sizes = FloatArray(pairs.size * 2)
        for ((i, pair) in pairs.withIndex()) {
            val (a, b) = pair
            first += resample(a, points, 2, reverse = false)
            second += resample(b, points, 2, reverse = isReversed(a, b))
            sizes[i * 2] = a.spec.sizePx
            sizes[i * 2 + 1] = b.spec.sizePx
        }
        return InBetweenSketch(first, second, sizes, points)
    }

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

    private fun distance(a: RecordedStroke, i: Int, b: RecordedStroke, j: Int): Float = hypot(
        a.at(i, 0) - b.at(j, 0),
        a.at(i, 1) - b.at(j, 1),
    )

    /** Channel [channel] of sample [i], with positions where they land on today's canvas. */
    private fun RecordedStroke.at(i: Int, channel: Int): Float {
        val value = samples[i * stride + channel]
        return when (channel) {
            0 -> value + offsetX
            1 -> value + offsetY
            else -> value
        }
    }

    /** [count] samples evenly spaced along [stroke], [stride] floats each. */
    private fun resample(stroke: RecordedStroke, count: Int, stride: Int, reverse: Boolean): FloatArray {
        val n = stroke.count
        val out = FloatArray(count * stride)
        fun sample(i: Int, channel: Int): Float {
            val index = if (reverse) n - 1 - i else i
            return stroke.at(index, channel)
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

    private const val SKETCH_POINTS = 24
    private const val STRIDE_PLAIN = 3
    private const val ORIENTATION = 4
    private const val TWO_PI = (2 * PI).toFloat()

    /** Turning a stroke round has to be clearly better, so that a closed loop is left as it was drawn. */
    private const val REVERSE_MARGIN = 0.7f
}

/** Paired strokes' middle lines, light enough to work out again each time the number of in-betweens or their spacing changes. */
public class InBetweenSketch internal constructor(
    private val first: List<FloatArray>,
    private val second: List<FloatArray>,
    private val sizes: FloatArray,
    /** How many points each line has. */
    public val points: Int,
) {
    public val strokeCount: Int get() = first.size

    /** The area every line falls in, with room for the brush, in canvas pixels, as left, top, right and bottom. */
    public val area: FloatArray =
        floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE).also { area ->
            for (stroke in first.indices) {
                val room = max(sizes[stroke * 2], sizes[stroke * 2 + 1]) / 2
                for (line in listOf(first[stroke], second[stroke])) {
                    for (p in 0 until points) {
                        area[0] = minOf(area[0], line[p * 2] - room)
                        area[1] = minOf(area[1], line[p * 2 + 1] - room)
                        area[2] = max(area[2], line[p * 2] + room)
                        area[3] = max(area[3], line[p * 2 + 1] + room)
                    }
                }
            }
            if (first.isEmpty()) area.fill(0f)
        }

    /** Stroke [stroke]'s middle line [t] of the way from the first drawing to the second, as x and y pairs, into [out]. */
    public fun line(stroke: Int, t: Float, out: FloatArray) {
        val a = first[stroke]
        val b = second[stroke]
        for (i in 0 until points * 2) out[i] = a[i] + (b[i] - a[i]) * t
    }

    /** Stroke [stroke]'s brush size [t] of the way, in canvas pixels. */
    public fun size(stroke: Int, t: Float): Float = sizes[stroke * 2] + (sizes[stroke * 2 + 1] - sizes[stroke * 2]) * t
}
