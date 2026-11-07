package org.kineograph.media.svg

import kotlin.math.abs
import kotlin.math.floor

/** Maps progress through a segment to eased progress. Shared by CSS timing functions and SMIL keySplines. */
internal fun interface Easing {
    fun ease(p: Double): Double

    companion object {
        val LINEAR: Easing = Easing { it }
        val EASE: Easing = CubicBezier(0.25, 0.1, 0.25, 1.0)
        val EASE_IN: Easing = CubicBezier(0.42, 0.0, 1.0, 1.0)
        val EASE_OUT: Easing = CubicBezier(0.0, 0.0, 0.58, 1.0)
        val EASE_IN_OUT: Easing = CubicBezier(0.42, 0.0, 0.58, 1.0)
    }
}

internal class CubicBezier(private val x1: Double, private val y1: Double, private val x2: Double, private val y2: Double) : Easing {
    override fun ease(p: Double): Double {
        if (p <= 0.0) return if (x1 > 0.0) p * y1 / x1 else 0.0
        if (p >= 1.0) return if (x2 < 1.0) 1.0 + (p - 1.0) * (1.0 - y2) / (1.0 - x2) else 1.0
        return sample(y1, y2, solveForX(p))
    }

    /** Newton's method is fast but can overshoot on steep curves, so bisection takes over when it does not converge. */
    private fun solveForX(x: Double): Double {
        var t = x
        repeat(8) {
            val error = sample(x1, x2, t) - x
            if (abs(error) < PRECISION) return t
            val slope = derivative(x1, x2, t)
            if (abs(slope) < 1e-6) return@repeat
            t -= error / slope
        }
        if (t in 0.0..1.0 && abs(sample(x1, x2, t) - x) < PRECISION) return t
        var low = 0.0
        var high = 1.0
        t = x
        repeat(64) {
            val value = sample(x1, x2, t)
            if (abs(value - x) < PRECISION) return t
            if (value < x) low = t else high = t
            t = (low + high) / 2
        }
        return t
    }

    private fun sample(a: Double, b: Double, t: Double): Double {
        val u = 1 - t
        return 3 * u * u * t * a + 3 * u * t * t * b + t * t * t
    }

    private fun derivative(a: Double, b: Double, t: Double): Double {
        val u = 1 - t
        return 3 * u * u * a + 6 * u * t * (b - a) + 3 * t * t * (1 - b)
    }

    private companion object {
        const val PRECISION = 1e-9
    }
}

internal enum class StepPosition { JUMP_START, JUMP_END, JUMP_NONE, JUMP_BOTH }

internal class Steps(private val count: Int, private val position: StepPosition) : Easing {
    override fun ease(p: Double): Double {
        var step = floor(p * count)
        if (position == StepPosition.JUMP_START || position == StepPosition.JUMP_BOTH) step += 1
        val jumps =
            when (position) {
                StepPosition.JUMP_NONE -> count - 1
                StepPosition.JUMP_BOTH -> count + 1
                else -> count
            }
        if (p >= 0 && step < 0) step = 0.0
        if (p <= 1 && step > jumps) step = jumps.toDouble()
        return step / jumps
    }
}

/** Reads a CSS timing function. Returns null when malformed, which the caller reads as "this declaration is invalid". */
internal fun parseEasing(raw: String): Easing? {
    val text = raw.trim().lowercase()
    when (text) {
        "linear" -> return Easing.LINEAR
        "ease" -> return Easing.EASE
        "ease-in" -> return Easing.EASE_IN
        "ease-out" -> return Easing.EASE_OUT
        "ease-in-out" -> return Easing.EASE_IN_OUT
        "step-start" -> return Steps(1, StepPosition.JUMP_START)
        "step-end" -> return Steps(1, StepPosition.JUMP_END)
    }
    val open = text.indexOf('(')
    if (open < 0 || !text.endsWith(")")) return null
    val inner = text.substring(open + 1, text.length - 1)
    val args = splitList(inner)
    return when (text.substring(0, open).trim()) {
        "cubic-bezier" -> parseCubicBezier(args)
        "steps" -> parseSteps(args)
        "linear" -> parseLinearFunction(inner)
        else -> null
    }
}

/** `linear(0, 0.25 75%, 1)`: straight lines through a list of points, used to approximate any curve. */
internal class LinearPoints(private val inputs: DoubleArray, private val outputs: DoubleArray) : Easing {
    override fun ease(p: Double): Double {
        // Past the ends, the line through the two nearest points carries on, except where two points share one input.
        var i = inputs.lastIndex - 1
        for (k in 0 until inputs.lastIndex) {
            if (p < inputs[k + 1]) {
                i = k
                break
            }
        }
        val span = inputs[i + 1] - inputs[i]
        if (span <= 0.0) return outputs[i + 1]
        return outputs[i] + (outputs[i + 1] - outputs[i]) * (p - inputs[i]) / span
    }
}

private fun parseLinearFunction(inner: String): Easing? {
    val outputs = ArrayList<Double>()
    val inputs = ArrayList<Double?>()
    for (part in splitTopLevel(inner, ',')) {
        val tokens = splitList(part)
        val percents = tokens.filter { it.endsWith("%") }
        val numbers = tokens.filter { !it.endsWith("%") }
        if (numbers.size != 1 || percents.size > 2) return null
        val output = parseNumberOrNull(numbers[0]) ?: return null
        if (percents.isEmpty()) {
            outputs += output
            inputs += null
        }
        for (percent in percents) {
            outputs += output
            inputs += parseFraction(percent) ?: return null
        }
    }
    if (outputs.size < 2) return null
    // The rules for points with no position: the ends go to 0 and 1, positions never go backwards, and gaps are shared out evenly.
    if (inputs[0] == null) inputs[0] = 0.0
    if (inputs[inputs.lastIndex] == null) inputs[inputs.lastIndex] = 1.0
    var largest = Double.NEGATIVE_INFINITY
    for (i in inputs.indices) {
        val value = inputs[i] ?: continue
        largest = maxOf(largest, value)
        inputs[i] = largest
    }
    var start = 0
    while (start < inputs.lastIndex) {
        var end = start + 1
        while (inputs[end] == null) end++
        val from = inputs[start] ?: 0.0
        val to = inputs[end] ?: 1.0
        for (k in start + 1 until end) inputs[k] = from + (to - from) * (k - start) / (end - start)
        start = end
    }
    return LinearPoints(DoubleArray(inputs.size) { inputs[it] ?: 0.0 }, outputs.toDoubleArray())
}

internal fun parseCubicBezier(args: List<String>): Easing? {
    if (args.size != 4) return null
    val values = args.map { parseNumberOrNull(it) ?: return null }
    // Overshoot is fine on the y axis, but on the x axis the curve would stop being a function of time.
    if (values[0] !in 0.0..1.0 || values[2] !in 0.0..1.0) return null
    return CubicBezier(values[0], values[1], values[2], values[3])
}

private fun parseSteps(args: List<String>): Easing? {
    val count = args.getOrNull(0)?.let(::parseNumberOrNull)?.toInt() ?: return null
    val position =
        when (args.getOrNull(1)) {
            null, "end", "jump-end" -> StepPosition.JUMP_END
            "start", "jump-start" -> StepPosition.JUMP_START
            "jump-none" -> StepPosition.JUMP_NONE
            "jump-both" -> StepPosition.JUMP_BOTH
            else -> return null
        }
    if (count < 1 || (position == StepPosition.JUMP_NONE && count < 2)) return null
    return Steps(count, position)
}
