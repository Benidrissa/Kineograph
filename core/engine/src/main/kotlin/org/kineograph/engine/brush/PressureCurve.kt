package org.kineograph.engine.brush

/** One control point of a [PressureCurve]. Both values are in 0..1. */
public data class CurvePoint(val input: Float, val output: Float)

/**
 * Maps raw pressure (0..1) to a brush response. Points are joined with straight lines; inputs outside
 * the first and last point clamp to those points' outputs. Evaluation uses a lookup table, because
 * it runs once per stamp.
 */
public class PressureCurve(public val points: List<CurvePoint>) {
    private val table = FloatArray(TABLE_SIZE + 1)

    init {
        require(points.isNotEmpty()) { "A curve needs at least one point" }
        val sorted = points.sortedBy { it.input }
        for (i in 0..TABLE_SIZE) {
            table[i] = interpolate(sorted, i.toFloat() / TABLE_SIZE)
        }
    }

    /** The response to [pressure], with linear interpolation between table entries. */
    public fun evaluate(pressure: Float): Float {
        val scaled = pressure.coerceIn(0f, 1f) * TABLE_SIZE
        val index = scaled.toInt().coerceAtMost(TABLE_SIZE - 1)
        val fraction = scaled - index
        return table[index] + (table[index + 1] - table[index]) * fraction
    }

    override fun equals(other: Any?): Boolean = other is PressureCurve && other.points == points

    override fun hashCode(): Int = points.hashCode()

    override fun toString(): String = "PressureCurve($points)"

    public companion object {
        private const val TABLE_SIZE = 64

        /** Output equals input. */
        public val LINEAR: PressureCurve = PressureCurve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)))

        /** Pressure has no effect. */
        public val FLAT: PressureCurve = PressureCurve(listOf(CurvePoint(0f, 1f), CurvePoint(1f, 1f)))

        /** A straight line from (0, [low]) to (1, [high]). */
        public fun ramp(low: Float, high: Float = 1f): PressureCurve = PressureCurve(listOf(CurvePoint(0f, low), CurvePoint(1f, high)))

        private fun interpolate(sorted: List<CurvePoint>, x: Float): Float {
            if (x <= sorted.first().input) return sorted.first().output
            if (x >= sorted.last().input) return sorted.last().output
            for (i in 1 until sorted.size) {
                val a = sorted[i - 1]
                val b = sorted[i]
                if (x <= b.input) {
                    val span = b.input - a.input
                    return if (span <= 0f) b.output else a.output + (b.output - a.output) * (x - a.input) / span
                }
            }
            return sorted.last().output
        }
    }
}
