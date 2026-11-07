package org.kineograph.media.svg

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/** Works out how long a picture plays, and for how long an import needs to sample it. */
internal object AnimationSummary {
    /**
     * How close two times must be to count as the same moment. Files write a third of a second as `.333s`, and three of those
     * should line up with a one-second cycle: the 1 ms difference cannot be seen in a frame-by-frame import.
     */
    private const val CYCLE_TOLERANCE_SECONDS = 0.002

    /**
     * Longer cycles get more room for the same reason, as `1.33s` is how four thirds gets written. A jump of a hundredth of a cycle at the join does
     * not show, and neither does one of a sixtieth of a second, whatever the cycle.
     */
    private const val CYCLE_TOLERANCE_SHARE = 0.01
    private const val CYCLE_TOLERANCE_MAX_SECONDS = 1.0 / 60.0

    /** No frame of an import could show a loop faster than this, and it is the same as not moving. */
    private const val MIN_LOOP_SECONDS = 0.001

    fun of(
        hasOwnAnimation: Boolean,
        smil: List<SmilTiming>,
        css: List<CssTiming>,
        embedded: List<SvgAnimationInfo>,
        limits: SvgLimits,
    ): SvgAnimationInfo {
        // An animated picture inside an image plays on the same clock as the outer one, so its timing counts as the outer one's too.
        if (!hasOwnAnimation && embedded.none { it.isAnimated }) return SvgAnimationInfo.STATIC
        val cycles = ArrayList<Double>()
        var loopStart = 0.0
        var lastEnd = 0.0
        var nestedIntro = false
        var nestedRepeatsExactly = true
        for (timing in smil) {
            val count = timing.intervalCount
            if (count == 0) continue
            val lastBegin = timing.beginOf(count - 1)
            val lastStop = timing.endOf(count - 1)
            when {
                timing.period > 0.0 -> {
                    cycles += timing.period
                    loopStart = max(loopStart, timing.periodStart)
                }

                timing.inLoop && timing.capped -> {
                    // Intervals never stop coming, yet show no pattern. Their average spacing is the best guess available.
                    cycles += (lastBegin - timing.beginOf(0)) / max(1, count - 1)
                    loopStart = max(loopStart, timing.beginOf(0))
                }

                !lastStop.isFinite() -> {
                    if (timing.hasIndefiniteRepeat) {
                        cycles += timing.duration ?: 0.0
                        loopStart = max(loopStart, lastBegin)
                    } else {
                        // Holds a value for good, like a plain `set`: the picture stops changing the moment this begins.
                        lastEnd = max(lastEnd, lastBegin)
                    }
                }

                else -> {
                    lastEnd = max(lastEnd, lastStop)
                }
            }
        }
        for (timing in css) {
            if (timing.paused) continue
            if (timing.duration <= 0.0) {
                lastEnd = max(lastEnd, timing.delay)
            } else if (timing.iterations == Double.POSITIVE_INFINITY) {
                cycles += timing.cycle
                loopStart = max(loopStart, timing.delay)
            } else {
                lastEnd = max(lastEnd, timing.delay + timing.duration * timing.iterations)
            }
        }
        for (info in embedded) {
            if (info.loopsForever) {
                cycles += info.durationSeconds
                loopStart = max(loopStart, info.loopStartSeconds)
                nestedIntro = nestedIntro || info.hasIntro
                nestedRepeatsExactly = nestedRepeatsExactly && info.repeatsExactly
            } else {
                lastEnd = max(lastEnd, info.durationSeconds)
            }
        }
        val positive = cycles.filter { it > 0.0 && it.isFinite() }
        if (positive.isEmpty()) return SvgAnimationInfo(true, usableTime(lastEnd), false, 0.0)
        val loop = commonCycle(positive, limits.maxSeamlessLoopSeconds)
        return SvgAnimationInfo(
            isAnimated = true,
            durationSeconds = usableTime(loop ?: positive.max()).coerceAtLeast(MIN_LOOP_SECONDS),
            loopsForever = true,
            loopStartSeconds = usableTime(max(loopStart, lastEnd)),
            // Something that plays and ends is an intro, while a loop that only waits to start has nothing to lose by being cut at its start.
            hasIntro = lastEnd > 0.0 || nestedIntro,
            repeatsExactly = loop != null && nestedRepeatsExactly,
        )
    }

    /** This goes to whoever imports the picture, who may count frames with it. A file can write anything at all in these places. */
    private fun usableTime(seconds: Double): Double = if (seconds.isNaN()) 0.0 else seconds.coerceIn(0.0, MAX_TIME_SECONDS)

    /** The least common multiple of the cycles when that is short enough to be worth importing, or else the longest cycle. */
    fun seamlessLoop(cycles: List<Double>, maxSeconds: Double): Double = commonCycle(cycles, maxSeconds) ?: cycles.max()

    /** The least common multiple of the cycles. Null when that is too long to be worth importing, which leaves nothing that would repeat exactly. */
    private fun commonCycle(cycles: List<Double>, maxSeconds: Double): Double? {
        val longest = cycles.max()
        val distinct = ArrayList<Double>()
        for (cycle in cycles.sorted()) {
            if (distinct.isEmpty() || cycle - distinct.last() > CYCLE_TOLERANCE_SECONDS) distinct += cycle
        }
        var multiple = longest
        var k = 1
        while (multiple <= maxSeconds) {
            if (distinct.all { isWholeMultiple(multiple, it) }) return multiple
            k++
            multiple = longest * k
        }
        return null
    }

    private fun isWholeMultiple(total: Double, cycle: Double): Boolean {
        val ratio = total / cycle
        val tolerance = (cycle * CYCLE_TOLERANCE_SHARE).coerceIn(CYCLE_TOLERANCE_SECONDS, CYCLE_TOLERANCE_MAX_SECONDS)
        return abs(ratio - floor(ratio + 0.5)) * cycle <= tolerance
    }
}
