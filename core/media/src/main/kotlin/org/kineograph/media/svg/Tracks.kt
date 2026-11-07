package org.kineograph.media.svg

import android.graphics.Path
import android.graphics.PathMeasure
import kotlin.math.atan2
import kotlin.math.hypot

/** One animation of one property on one element. */
internal abstract class Track(val property: String, val documentOrder: Int) {
    open val isCss: Boolean get() = false

    /** What one evaluation comes to. A long list of values is gone through again on every frame. */
    open val evaluationCost: Long get() = WorkCost.TRACK_EVALUATED

    open fun beginAt(time: Double): Double = 0.0

    /** The value at [time], given what lies beneath it in the stack. Null means no effect at this moment. */
    abstract fun evaluate(time: Double, underlying: SvgValue?, current: Rgba): SvgValue?

    /**
     * Stacks tracks in priority order: later starts win, and CSS animations win over all of SMIL. SMIL's text would also put an animation
     * above one it syncs to, even when they start together. Browsers do not, so what is later in the file wins there too.
     */
    companion object {
        fun stackOrder(time: Double): Comparator<Track> =
            compareBy<Track>({ it.isCss }, { if (it.isCss) 0.0 else it.beginAt(time) }, { it.documentOrder })
    }
}

internal enum class CalcMode { DISCRETE, LINEAR, PACED, SPLINE }

/** `animate`, `set` and `animateTransform`: all of them are lists of values walked through over time. */
internal class SmilValueTrack(
    property: String,
    documentOrder: Int,
    private val timing: SmilTiming,
    /** A null entry stands for the underlying value. That is how `to` animations start from wherever the property already is. */
    private val values: List<SvgValue?>,
    private val mode: CalcMode,
    private val keyTimes: DoubleArray?,
    private val splines: List<Easing>?,
    private val additive: Boolean,
    private val accumulate: Boolean,
    private val isToAnimation: Boolean,
) : Track(property, documentOrder) {
    override fun beginAt(time: Double): Double = timing.currentBegin(time)

    override val evaluationCost: Long get() = WorkCost.TRACK_EVALUATED + values.size

    /** Segment boundaries for paced mode, worked out once from the distances between the values. */
    private val pacedTimes: DoubleArray? by lazy(LazyThreadSafetyMode.NONE) { computePacedTimes() }

    private fun computePacedTimes(): DoubleArray? {
        if (mode != CalcMode.PACED || values.size < 2) return null
        val distances = DoubleArray(values.size - 1)
        var total = 0.0
        for (i in distances.indices) {
            val d = ValueMath.distance(values[i], values[i + 1], Rgba.BLACK) ?: return null
            distances[i] = d
            total += d
        }
        if (total <= 0.0) return null
        val times = DoubleArray(values.size)
        var sum = 0.0
        for (i in distances.indices) {
            sum += distances[i]
            times[i + 1] = sum / total
        }
        times[times.lastIndex] = 1.0
        return times
    }

    override fun evaluate(time: Double, underlying: SvgValue?, current: Rgba): SvgValue? {
        val state = timing.stateAt(time) ?: return null
        var result = valueAt(state.progress, underlying, current) ?: return null
        if (accumulate && !isToAnimation && state.iteration > 0) {
            val last = valueAt(1.0, underlying, current)
            val built = ValueMath.scale(last, state.iteration.toDouble(), current)
            ValueMath.addNumeric(result, built, current)?.let { result = it }
        }
        if (additive && !isToAnimation) ValueMath.add(underlying, result, current)?.let { result = it }
        return result
    }

    private fun valueAt(progress: Double, underlying: SvgValue?, current: Rgba): SvgValue? {
        val n = values.size
        if (n == 0) return null
        if (n == 1) return values[0] ?: underlying
        if (mode == CalcMode.DISCRETE) {
            val index = discreteIndex(progress, n)
            return values[index] ?: underlying
        }
        val times = if (mode == CalcMode.PACED) pacedTimes else keyTimes
        val segment: Int
        val local: Double
        if (progress >= 1.0) {
            return values[n - 1] ?: underlying
        } else if (times != null) {
            var i = 0
            while (i < n - 2 && progress >= times[i + 1]) i++
            segment = i
            val span = times[i + 1] - times[i]
            local = if (span > 0.0) ((progress - times[i]) / span).coerceIn(0.0, 1.0) else 1.0
        } else {
            val scaled = progress * (n - 1)
            segment = scaled.toInt().coerceAtMost(n - 2)
            local = scaled - segment
        }
        val eased = splines?.getOrNull(segment)?.ease(local) ?: local
        val from = values[segment] ?: underlying
        val to = values[segment + 1] ?: underlying
        return ValueMath.interpolate(from, to, eased, current, smil = true) ?: if (eased < 0.5) from ?: to else to ?: from
    }

    private fun discreteIndex(progress: Double, n: Int): Int {
        if (progress >= 1.0) return n - 1
        val times = keyTimes ?: return (progress * n).toInt().coerceAtMost(n - 1)
        var index = 0
        while (index < n - 1 && progress >= times[index + 1]) index++
        return index
    }
}

/** How `rotate` on animateMotion turns the element as it travels. */
internal sealed interface MotionRotate {
    data object Auto : MotionRotate

    data object AutoReverse : MotionRotate

    data class Fixed(val degrees: Double) : MotionRotate
}

/** `animateMotion`: a position along a path, stored as an extra transform that sits outside the element's own. */
internal class SmilMotionTrack(
    documentOrder: Int,
    private val timing: SmilTiming,
    private val path: PathData,
    private val mode: CalcMode,
    private val keyPoints: DoubleArray?,
    private val keyTimes: DoubleArray?,
    private val splines: List<Easing>?,
    private val rotate: MotionRotate,
    private val additive: Boolean,
    private val accumulate: Boolean,
) : Track(MOTION_PROPERTY, documentOrder) {
    override fun beginAt(time: Double): Double = timing.currentBegin(time)

    private val lazyMeasure = lazy(LazyThreadSafetyMode.NONE) { MotionMeasure(path) }
    private val measure: MotionMeasure by lazyMeasure

    /** Measuring the path comes once, and takes as long as the path is long. */
    override val evaluationCost: Long
        get() {
            val toMeasure = if (lazyMeasure.isInitialized()) 0L else path.commands.size * WorkCost.OUTLINE_SEGMENT
            return WorkCost.TRACK_EVALUATED + (keyPoints?.size ?: 0) + toMeasure
        }

    override fun evaluate(time: Double, underlying: SvgValue?, current: Rgba): SvgValue? {
        val state = timing.stateAt(time) ?: return null
        val distance = distanceAt(state.progress)
        val position = measure.at(distance)
        var m = Mat2D.translate(position.x, position.y)
        if (accumulate && state.iteration > 0) {
            val end = measure.at(measure.total)
            m = Mat2D.translate(end.x * state.iteration, end.y * state.iteration) * m
        }
        val degrees =
            when (rotate) {
                MotionRotate.Auto -> position.degrees
                MotionRotate.AutoReverse -> position.degrees + 180.0
                is MotionRotate.Fixed -> rotate.degrees
            }
        if (degrees != 0.0) m *= Mat2D.rotate(degrees)
        val op = TransformOp.Raw(m)
        val base = (underlying as? TransformValue)?.ops
        return TransformValue(if (additive && !base.isNullOrEmpty()) base + op else listOf(op))
    }

    private fun distanceAt(progress: Double): Double {
        val total = measure.total
        val points = keyPoints
        if (points != null && points.size >= 2) {
            // keyPoints are fractions along the path, which turn the whole path into a list of values to walk through.
            return total * pointFraction(points, progress)
        }
        return when (mode) {
            CalcMode.PACED -> total * progress
            CalcMode.DISCRETE -> measure.vertexDistance(discreteVertex(progress))
            else -> vertexWalk(progress)
        }
    }

    private fun pointFraction(points: DoubleArray, progress: Double): Double {
        val times = keyTimes
        if (mode == CalcMode.DISCRETE) {
            if (progress >= 1.0) return points.last()
            if (times == null) return points[(progress * points.size).toInt().coerceAtMost(points.lastIndex)]
            var index = 0
            while (index < points.lastIndex && progress >= times[index + 1]) index++
            return points[index]
        }
        if (progress >= 1.0) return points.last()
        val segment: Int
        val local: Double
        if (times != null) {
            var i = 0
            while (i < points.size - 2 && progress >= times[i + 1]) i++
            segment = i
            val span = times[i + 1] - times[i]
            local = if (span > 0.0) ((progress - times[i]) / span).coerceIn(0.0, 1.0) else 1.0
        } else {
            val scaled = progress * (points.size - 1)
            segment = scaled.toInt().coerceAtMost(points.size - 2)
            local = scaled - segment
        }
        val eased = splines?.getOrNull(segment)?.ease(local) ?: local
        return points[segment] + (points[segment + 1] - points[segment]) * eased
    }

    private fun discreteVertex(progress: Double): Int {
        val count = measure.vertexCount
        if (progress >= 1.0) return count - 1
        val times = keyTimes ?: return (progress * count).toInt().coerceAtMost(count - 1)
        var index = 0
        while (index < count - 1 && index + 1 < times.size && progress >= times[index + 1]) index++
        return index
    }

    /** Linear and spline modes give each path segment the same share of time, or the share keyTimes asks for. */
    private fun vertexWalk(progress: Double): Double {
        val count = measure.vertexCount
        if (count < 2) return 0.0
        if (progress >= 1.0) return measure.total
        val times = keyTimes
        val segment: Int
        val local: Double
        if (times != null && times.size == count) {
            var i = 0
            while (i < count - 2 && progress >= times[i + 1]) i++
            segment = i
            val span = times[i + 1] - times[i]
            local = if (span > 0.0) ((progress - times[i]) / span).coerceIn(0.0, 1.0) else 1.0
        } else {
            val scaled = progress * (count - 1)
            segment = scaled.toInt().coerceAtMost(count - 2)
            local = scaled - segment
        }
        val eased = splines?.getOrNull(segment)?.ease(local) ?: local
        val from = measure.vertexDistance(segment)
        return from + (measure.vertexDistance(segment + 1) - from) * eased
    }
}

internal class MotionPosition(val x: Double, val y: Double, val degrees: Double)

/** Positions along a path by distance. A path may have several separate parts, which PathMeasure walks one at a time. */
internal class MotionMeasure(private val data: PathData) {
    private val parts = ArrayList<PathMeasure>()
    private val partLengths = ArrayList<Double>()
    private val single = FloatArray(2)
    private val tangent = FloatArray(2)
    private var fallbackX = 0.0
    private var fallbackY = 0.0

    val total: Double

    init {
        val whole = PathMeasure(data.path, false)
        var sum = 0.0
        do {
            val length = whole.length
            if (length > 0f) {
                val part = Path()
                whole.getSegment(0f, length, part, true)
                parts += PathMeasure(part, false)
                partLengths += length.toDouble()
                sum += length
            }
        } while (whole.nextContour())
        total = sum
        // A path of a single point has no length, and the element should simply sit on that point.
        if (data.args.size >= 2) {
            fallbackX = data.args[0]
            fallbackY = data.args[1]
        }
    }

    fun at(distance: Double): MotionPosition {
        if (parts.isEmpty()) return MotionPosition(fallbackX, fallbackY, 0.0)
        var remaining = distance.coerceIn(0.0, total)
        for (i in parts.indices) {
            val length = partLengths[i]
            if (remaining <= length || i == parts.lastIndex) {
                parts[i].getPosTan(remaining.coerceAtMost(length).toFloat(), single, tangent)
                val degrees = Math.toDegrees(atan2(tangent[1].toDouble(), tangent[0].toDouble()))
                return MotionPosition(single[0].toDouble(), single[1].toDouble(), degrees)
            }
            remaining -= length
        }
        return MotionPosition(fallbackX, fallbackY, 0.0)
    }

    private val vertexDistances: DoubleArray by lazy(LazyThreadSafetyMode.NONE) { computeVertexDistances() }

    val vertexCount: Int get() = vertexDistances.size

    fun vertexDistance(index: Int): Double = vertexDistances[index.coerceIn(0, vertexDistances.lastIndex)]

    private fun computeVertexDistances(): DoubleArray {
        val sink = VertexLengthSink()
        data.walk(sink)
        return sink.distances.toDoubleArray()
    }

    private class VertexLengthSink : PathSink {
        val distances = arrayListOf(0.0)
        private var x = 0.0
        private var y = 0.0
        private var startX = 0.0
        private var startY = 0.0
        private var inArc = false
        private var arcLength = 0.0

        private fun segment(length: Double, endX: Double, endY: Double) {
            if (inArc) {
                arcLength += length
            } else {
                distances += distances.last() + length
            }
            x = endX
            y = endY
        }

        override fun moveTo(x: Double, y: Double) {
            this.x = x
            this.y = y
            startX = x
            startY = y
        }

        override fun lineTo(x: Double, y: Double) = segment(hypot(x - this.x, y - this.y), x, y)

        override fun quadTo(x1: Double, y1: Double, x: Double, y: Double) {
            val path = Path()
            path.moveTo(this.x.toFloat(), this.y.toFloat())
            path.quadTo(x1.toFloat(), y1.toFloat(), x.toFloat(), y.toFloat())
            segment(PathMeasure(path, false).length.toDouble(), x, y)
        }

        override fun cubicTo(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double) {
            val path = Path()
            path.moveTo(this.x.toFloat(), this.y.toFloat())
            path.cubicTo(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), x.toFloat(), y.toFloat())
            segment(PathMeasure(path, false).length.toDouble(), x, y)
        }

        override fun close() = segment(hypot(startX - x, startY - y), startX, startY)

        override fun arcStart() {
            inArc = true
            arcLength = 0.0
        }

        override fun arcEnd() {
            inArc = false
            distances += distances.last() + arcLength
        }
    }
}

internal class CssFrame(val offset: Double, val value: SvgValue?, val easing: Easing)

/** One property of one CSS keyframes animation. */
internal class CssTrack(
    property: String,
    documentOrder: Int,
    private val timing: CssTiming,
    private val frames: List<CssFrame>,
    private val shapes: BasicShapeTable,
) : Track(property, documentOrder) {
    override val isCss: Boolean get() = true

    /** Mixing two shapes makes a new one with as many points, for every track on every frame. Only shapes of the same length mix, so the longest here is as long as that can get. */
    private val longestShape: Int =
        if (property == "clip-path") frames.maxOf { frame -> shapeOf(frame.value)?.segments ?: 0 } else 0

    override val evaluationCost: Long get() = WorkCost.TRACK_EVALUATED + frames.size + longestShape * WorkCost.SHAPE_POINT_MIXED

    override fun evaluate(time: Double, underlying: SvgValue?, current: Rgba): SvgValue? {
        val progress = timing.progressAt(time) ?: return null
        var index = 0
        while (index < frames.size - 2 && progress >= frames[index + 1].offset) index++
        val from = frames[index]
        val to = frames[index + 1]
        val span = to.offset - from.offset
        val local = if (span > 0.0) ((progress - from.offset) / span).coerceIn(0.0, 1.0) else 1.0
        val eased = from.easing.ease(local)
        val a = from.value ?: underlying
        val b = to.value ?: underlying
        if (property == "visibility") return interpolateVisibility(a, b, eased)
        if (property == "display") return interpolateDisplay(a, b, eased)
        if (property == "clip-path") return interpolateClip(a, b, eased)
        return ValueMath.interpolate(a, b, eased, current) ?: if (eased < 0.5) a else b
    }

    /** Whatever a frame or an element says was read while the file was, so this never holds up drawing. */
    private fun shapeOf(value: SvgValue?): BasicShape? = (value as? KeywordValue)?.let { shapes.known(it.text) }

    /** Anything else, such as a `url()` or `none`, has no in-between and flips half way, as it does in browsers. */
    private fun interpolateClip(a: SvgValue?, b: SvgValue?, p: Double): SvgValue? {
        val from = shapeOf(a)
        val to = shapeOf(b)
        val mixed = if (from != null && to != null) from.interpolate(to, p) else null
        return mixed?.let(::ClipShapeValue) ?: if (p < 0.5) a else b
    }

    /** The same idea for `display`: `none` only takes hold at the very end of the animation, so the element can still be seen fading. */
    private fun interpolateDisplay(a: SvgValue?, b: SvgValue?, p: Double): SvgValue? {
        if (p <= 0.0) return a
        if (p >= 1.0) return b
        val none = KeywordValue("none")
        return if (a == none && b != none) {
            b
        } else if (b == none && a != none) {
            a
        } else if (p < 0.5) {
            a
        } else {
            b
        }
    }

    /** CSS keeps `visibility` visible for the whole of a transition that touches `visible`, instead of flipping half way. */
    private fun interpolateVisibility(a: SvgValue?, b: SvgValue?, p: Double): SvgValue? {
        if (a == b) return a
        if (p <= 0.0) return a
        if (p >= 1.0) return b
        val visible = KeywordValue("visible")
        return if (a == visible || b == visible) {
            visible
        } else if (p < 0.5) {
            a
        } else {
            b
        }
    }
}
