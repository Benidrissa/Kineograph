package org.kineograph.media.svg

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The arithmetic animation needs on property values. Every function returns null when its values cannot be combined, such as
 * a colour and a gradient, or two paths with different commands. The callers then fall back to a discrete flip.
 */
internal object ValueMath {
    fun interpolate(a: SvgValue?, b: SvgValue?, p: Double, current: Rgba): SvgValue? {
        if (a == null || b == null) return null
        if (a is NumberValue && b is NumberValue) return NumberValue(a.value + (b.value - a.value) * p)
        if (a is LengthValue && b is LengthValue) return LengthValue(a.length + (b.length - a.length) * p)
        if ((a is LengthValue || a is LengthListValue) && (b is LengthValue || b is LengthListValue)) {
            val x = lengthsOf(a)
            val y = lengthsOf(b)
            if (x.size != y.size) return null
            return LengthListValue(List(x.size) { x[it] + (y[it] - x[it]) * p })
        }
        if (a is DashValue && b is DashValue) return interpolateDash(a, b, p)
        if (a is PaintValue && b is PaintValue) {
            val x = solid(a, current) ?: return null
            val y = solid(b, current) ?: return null
            return PaintValue.Solid(x.mix(y, p))
        }
        if (a is TransformValue && b is TransformValue) return TransformValue(interpolateTransforms(a.ops, b.ops, p))
        if (a is PathValue && b is PathValue) {
            if (!a.data.hasSameCommands(b.data)) return null
            val args = DoubleArray(a.data.args.size) { a.data.args[it] + (b.data.args[it] - a.data.args[it]) * p }
            return PathValue(PathData(a.data.commands, args))
        }
        return null
    }

    /** What `additive="sum"` means: for numbers a sum, and for transforms the new one applied after the base. */
    fun add(base: SvgValue?, value: SvgValue?, current: Rgba): SvgValue? {
        if (base is TransformValue && value is TransformValue) return TransformValue(base.ops + value.ops)
        return addNumeric(base, value, current)
    }

    fun addNumeric(a: SvgValue?, b: SvgValue?, current: Rgba): SvgValue? {
        if (a == null || b == null) return null
        if (a is NumberValue && b is NumberValue) return NumberValue(a.value + b.value)
        if (a is LengthValue && b is LengthValue) return LengthValue(a.length + b.length)
        if ((a is LengthValue || a is LengthListValue) && (b is LengthValue || b is LengthListValue)) {
            val x = lengthsOf(a)
            val y = lengthsOf(b)
            if (x.size != y.size) return null
            return LengthListValue(List(x.size) { x[it] + y[it] })
        }
        if (a is DashValue && b is DashValue) {
            if (a.items.size != b.items.size) return null
            return DashValue(List(a.items.size) { a.items[it] + b.items[it] })
        }
        if (a is PaintValue && b is PaintValue) {
            val x = solid(a, current) ?: return null
            val y = solid(b, current) ?: return null
            return PaintValue.Solid(
                Rgba(
                    (x.r + y.r).coerceIn(0.0, 255.0),
                    (x.g + y.g).coerceIn(0.0, 255.0),
                    (x.b + y.b).coerceIn(0.0, 255.0),
                    (x.a + y.a).coerceIn(0.0, 1.0),
                ),
            )
        }
        if (a is TransformValue && b is TransformValue) return addTransformsNumerically(a.ops, b.ops)?.let(::TransformValue)
        if (a is PathValue && b is PathValue) {
            if (!a.data.hasSameCommands(b.data)) return null
            return PathValue(PathData(a.data.commands, DoubleArray(a.data.args.size) { a.data.args[it] + b.data.args[it] }))
        }
        return null
    }

    fun scale(a: SvgValue?, k: Double, current: Rgba): SvgValue? =
        when (a) {
            is NumberValue -> {
                NumberValue(a.value * k)
            }

            is LengthValue -> {
                LengthValue(a.length * k)
            }

            is LengthListValue -> {
                LengthListValue(a.items.map { it * k })
            }

            is DashValue -> {
                DashValue(a.items.map { it * k })
            }

            is PaintValue -> {
                solid(a, current)?.let {
                    PaintValue.Solid(
                        Rgba(
                            (it.r * k).coerceIn(0.0, 255.0),
                            (it.g * k).coerceIn(0.0, 255.0),
                            (it.b * k).coerceIn(0.0, 255.0),
                            (it.a * k).coerceIn(0.0, 1.0),
                        ),
                    )
                }
            }

            is TransformValue -> {
                scaleTransformsNumerically(a.ops, k)?.let(::TransformValue)
            }

            is PathValue -> {
                PathValue(PathData(a.data.commands, DoubleArray(a.data.args.size) { a.data.args[it] * k }))
            }

            else -> {
                null
            }
        }

    /** The value `by` counts up from when nothing else says where to start. */
    fun zeroOf(a: SvgValue?): SvgValue? =
        when (a) {
            is NumberValue -> NumberValue(0.0)
            is LengthValue -> LengthValue(Length.ZERO)
            is LengthListValue -> LengthListValue(a.items.map { Length.ZERO })
            is DashValue -> DashValue(a.items.map { Length.ZERO })
            is PaintValue -> PaintValue.Solid(Rgba(0.0, 0.0, 0.0, 0.0))
            is TransformValue -> TransformValue(a.ops.map(::zeroLike))
            is PathValue -> PathValue(PathData(a.data.commands, DoubleArray(a.data.args.size)))
            else -> null
        }

    /** The distance paced animation divides its time by: constant speed means equal time per unit of distance. */
    fun distance(a: SvgValue?, b: SvgValue?, current: Rgba): Double? {
        if (a == null || b == null) return null
        if (a is NumberValue && b is NumberValue) return abs(a.value - b.value)
        if (a is LengthValue && b is LengthValue) return lengthDistance(a.length, b.length)
        if ((a is LengthValue || a is LengthListValue) && (b is LengthValue || b is LengthListValue)) {
            val x = lengthsOf(a)
            val y = lengthsOf(b)
            if (x.size != y.size) return null
            return sqrt(x.indices.sumOf { i -> lengthDistance(x[i], y[i]).let { it * it } })
        }
        if (a is PaintValue && b is PaintValue) {
            val x = solid(a, current) ?: return null
            val y = solid(b, current) ?: return null
            return sqrt((x.r - y.r) * (x.r - y.r) + (x.g - y.g) * (x.g - y.g) + (x.b - y.b) * (x.b - y.b))
        }
        if (a is TransformValue && b is TransformValue && a.ops.size == 1 && b.ops.size == 1) {
            val x = a.ops[0]
            val y = b.ops[0]
            return when {
                x is TransformOp.Translate && y is TransformOp.Translate -> {
                    hypot(lengthDistance(x.x, y.x), lengthDistance(x.y, y.y))
                }

                x is TransformOp.Scale && y is TransformOp.Scale -> {
                    hypot(x.x - y.x, x.y - y.y)
                }

                x is TransformOp.Rotate && y is TransformOp.Rotate -> {
                    abs(x.degrees - y.degrees)
                }

                x is TransformOp.Skew && y is TransformOp.Skew -> {
                    hypot(x.xDegrees - y.xDegrees, x.yDegrees - y.yDegrees)
                }

                else -> {
                    null
                }
            }
        }
        return null
    }

    private fun lengthDistance(a: Length, b: Length): Double = abs(a.px - b.px) + abs(a.pct - b.pct) + abs(a.em - b.em)

    private fun lengthsOf(value: SvgValue): List<Length> =
        when (value) {
            is LengthValue -> listOf(value.length)
            is LengthListValue -> value.items
            else -> emptyList()
        }

    private fun solid(value: PaintValue, current: Rgba): Rgba? =
        when (value) {
            is PaintValue.Solid -> value.color
            PaintValue.Current -> current
            else -> null
        }

    /** Dash patterns of different lengths repeat until they line up, which is how browsers animate `5 5` to `2 4 6`. */
    private fun interpolateDash(a: DashValue, b: DashValue, p: Double): DashValue? {
        if (a.items.isEmpty() || b.items.isEmpty()) return null
        val x = if (a.items.size % 2 == 0) a.items else a.items + a.items
        val y = if (b.items.size % 2 == 0) b.items else b.items + b.items
        val size = leastCommonMultiple(x.size, y.size)
        // A pathological pattern must not allocate without bound. Beyond this a flip looks no worse.
        if (size > 4096) return null
        return DashValue(List(size) { i -> x[i % x.size] + (y[i % y.size] - x[i % x.size]) * p })
    }

    private fun leastCommonMultiple(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val r = x % y
            x = y
            y = r
        }
        return a / x * b
    }
}
