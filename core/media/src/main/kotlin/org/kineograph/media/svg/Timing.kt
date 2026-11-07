package org.kineograph.media.svg

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

internal enum class SyncKind { BEGIN, END, REPEAT }

/** One entry of a `begin` or `end` list that can actually happen during an import. */
internal sealed interface TimeSpec {
    data class Offset(val seconds: Double) : TimeSpec

    data class Sync(val refId: String, val kind: SyncKind, val repeat: Int, val offset: Double) : TimeSpec
}

internal enum class Restart { ALWAYS, WHEN_NOT_ACTIVE, NEVER }

internal class SmilState(val progress: Double, val iteration: Int, val begin: Double)

/**
 * When one animation element runs. Its intervals may depend on other elements (`begin="a.end+0.5s"`), so [SmilTimeline] computes
 * them all together, once, while parsing. Drawing then only looks times up, which is what makes a frame depend on nothing but
 * its time: no state carries over from the frame before it.
 */
internal class SmilTiming(
    val id: String?,
    val begins: List<TimeSpec>,
    val ends: List<TimeSpec>,
    /** True if the list of ends is empty, or holds an end that can never happen. Then an interval can run on to its natural end. */
    val endOpen: Boolean,
    val duration: Double?,
    val repeatCount: Double?,
    val repeatDuration: Double?,
    val minDuration: Double,
    val maxDuration: Double,
    val restart: Restart,
    val freeze: Boolean,
) {
    private var starts = DoubleArray(0)
    private var stops = DoubleArray(0)

    /** Where the repeating part of each interval runs out. Past this, while `min` keeps the element active, only its fill shows. */
    private var naturalStops = DoubleArray(0)

    /** Length of the repeating tail, when the intervals never stop coming and settle into a pattern. Zero otherwise. */
    var period: Double = 0.0
        private set
    var periodStart: Double = 0.0
        private set
    private var foldFrom = Double.POSITIVE_INFINITY

    /** Whether this element leads, through its references, to a loop, so that its intervals never stop coming. */
    var inLoop: Boolean = false

    /** Hit the limit on how many intervals are computed. For an element in a loop of restarts, that is the normal case. */
    var capped: Boolean = false
        private set

    val intervalCount: Int get() = starts.size

    fun beginOf(index: Int): Double = starts[index]

    fun endOf(index: Int): Double = stops[index]

    val hasIndefiniteRepeat: Boolean
        get() = duration != null && (repeatCount == Double.POSITIVE_INFINITY || repeatDuration == Double.POSITIVE_INFINITY)

    private fun repeatingDuration(): Double {
        val d = duration
        return when {
            repeatCount == null && repeatDuration == null -> {
                d ?: Double.POSITIVE_INFINITY
            }

            else -> {
                val byCount =
                    if (repeatCount == null) {
                        Double.POSITIVE_INFINITY
                    } else if (d == null) {
                        Double.POSITIVE_INFINITY
                    } else {
                        d * repeatCount
                    }
                min(byCount, repeatDuration ?: Double.POSITIVE_INFINITY)
            }
        }
    }

    /** Builds the intervals from the current instance times, following the rules for restarting from SMIL. */
    fun computeIntervals(beginTimes: DoubleArray, endTimes: DoubleArray, maxIntervals: Int): Boolean {
        val outStarts = ArrayList<Double>()
        val outStops = ArrayList<Double>()
        val outNatural = ArrayList<Double>()
        var index = 0
        var endCursor = 0
        while (index < beginTimes.size && outStarts.size < maxIntervals) {
            val begin = beginTimes[index]
            // Both lists are sorted, so the end to look at only ever moves forward. Going through them all for each begin is quadratic.
            while (endCursor < endTimes.size && endTimes[endCursor] <= begin) endCursor++
            val hasEnd = endCursor < endTimes.size
            val endLimit = if (hasEnd) endTimes[endCursor] else Double.POSITIVE_INFINITY
            // Every end is already in the past and none can still come: SMIL says this and all later intervals never happen.
            if (!hasEnd && !endOpen) break
            val repeating = repeatingDuration()
            val stretched = min(repeating, endLimit - begin).coerceIn(min(minDuration, maxDuration), maxDuration)
            var stop = begin + stretched
            var next = index + 1
            when (restart) {
                Restart.ALWAYS -> if (next < beginTimes.size && beginTimes[next] < stop) stop = beginTimes[next]
                Restart.WHEN_NOT_ACTIVE -> while (next < beginTimes.size && beginTimes[next] < stop) next++
                Restart.NEVER -> next = beginTimes.size
            }
            outStarts += begin
            outStops += stop
            outNatural += begin + repeating
            index = next
        }
        val newStarts = outStarts.toDoubleArray()
        val newStops = outStops.toDoubleArray()
        // Reaching the cap exactly also counts: the partner element in a loop gets one interval for each of ours, and no more.
        capped = outStarts.size >= maxIntervals
        val changed = !newStarts.contentEquals(starts) || !newStops.contentEquals(stops)
        starts = newStarts
        stops = newStops
        naturalStops = outNatural.toDoubleArray()
        return changed
    }

    /**
     * A chain of elements that restart each other produces intervals without end, so only the first few hundred are computed.
     * Those settle into a repeating pattern. Finding it lets any later time be mapped back onto the part that was computed, so a
     * frame at one hour costs the same as a frame at one second, and comes out exactly the same every time.
     */
    fun findPeriod() {
        if (!capped) return
        // The last few may be missing an end instance from the chain they belong to, so they are not evidence.
        val usable = starts.size - GUARD_INTERVALS
        if (usable < 8) return
        for (k in 1..usable / 3) {
            val candidate = starts[usable - 1] - starts[usable - 1 - k]
            if (candidate <= 1e-9) continue
            var first = usable - 1 - k
            while (first >= 0 && matches(first, k, candidate)) first--
            first++
            if (usable - 1 - k - first >= usable / 3) {
                period = candidate
                // Before its first run an element has no effect, just as in the gap between runs. So unless it freezes, which
                // fills that gap with something else, the pattern already holds from the moment that gap would have started.
                periodStart = if (first == 0 && !freeze) stops[k - 1] - candidate else starts[first]
                foldFrom = starts[usable - 1]
                return
            }
        }
    }

    private fun matches(i: Int, k: Int, candidate: Double): Boolean =
        abs(starts[i + k] - starts[i] - candidate) < 1e-6 && abs(stops[i + k] - stops[i] - candidate) < 1e-6

    /** The state at [time], or null if this element has no effect then: not started yet, or ended without freezing. */
    fun stateAt(time: Double): SmilState? {
        var t = time
        if (period > 0.0 && t > foldFrom) t -= ceil((t - foldFrom) / period) * period
        val index = lastStartAtOrBefore(t)
        if (index < 0) return null
        val begin = starts[index]
        val repeating = naturalStops[index] - begin
        if (t < stops[index] && t - begin < repeating) return activeState(t - begin, begin)
        if (!freeze) return null
        return frozenState(min(repeating, stops[index] - begin), begin)
    }

    /** Begin time of the interval in effect, used to stack animations of one property in SMIL's priority order. */
    fun currentBegin(time: Double): Double {
        var t = time
        if (period > 0.0 && t > foldFrom) t -= ceil((t - foldFrom) / period) * period
        val index = lastStartAtOrBefore(t)
        return if (index < 0) Double.NEGATIVE_INFINITY else starts[index]
    }

    private fun lastStartAtOrBefore(t: Double): Int {
        var low = 0
        var high = starts.size - 1
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= t) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    private fun activeState(activeTime: Double, begin: Double): SmilState {
        val d = duration ?: return SmilState(0.0, 0, begin)
        val q = activeTime / d
        val iteration = floor(q)
        return SmilState((q - iteration).coerceIn(0.0, 1.0), iteration.toInt(), begin)
    }

    /** Freezing right on a repeat boundary means the end of the last repeat, not the start of one that never ran. */
    private fun frozenState(activeTime: Double, begin: Double): SmilState {
        val d = duration ?: return SmilState(0.0, 0, begin)
        if (activeTime <= 0.0) return SmilState(0.0, 0, begin)
        val q = activeTime / d
        val iteration = floor(q + 1e-9)
        val fraction = q - iteration
        return if (abs(fraction) < 1e-9) {
            SmilState(1.0, iteration.toInt() - 1, begin)
        } else {
            SmilState(fraction.coerceIn(0.0, 1.0), iteration.toInt(), begin)
        }
    }

    private companion object {
        const val GUARD_INTERVALS = 8
    }
}

/** Solves the intervals of every element in the file together, since each may depend on the others. */
internal class SmilTimeline(
    private val timings: List<SmilTiming>,
    private val work: WorkMeter,
    /** Elements that keep moving each other earlier never settle, and on their own would use up all there is for reading the file. */
    private val maxWork: Long,
) {
    private var spent = 0L

    private fun pay(units: Long): Boolean {
        spent += units
        return work.spend(units) && spent <= maxWork
    }

    private val byId = HashMap<String, SmilTiming>()

    /** Elements that lead, through their references, to a loop. Only those can produce intervals without end. */
    private val inLoop = HashSet<SmilTiming>()

    /** True if the timing could not be worked out in full, which takes a file written to spin the solver, not to show a picture. */
    var exhausted: Boolean = false
        private set

    fun solve() {
        for (timing in timings) timing.id?.let { byId.putIfAbsent(it, timing) }
        val dependents = HashMap<SmilTiming, LinkedHashSet<SmilTiming>>()
        val dependencies = HashMap<SmilTiming, HashSet<SmilTiming>>()
        for (timing in timings) {
            for (spec in timing.begins + timing.ends) {
                if (spec is TimeSpec.Sync) {
                    val ref = byId[spec.refId] ?: continue
                    dependents.getOrPut(ref) { LinkedHashSet(2) } += timing
                    dependencies.getOrPut(timing) { HashSet(2) } += ref
                }
            }
        }
        orderDependencies(dependents, dependencies)
        // Each pass re-derives only what changed. Going through everything over and over would be quadratic for long chains.
        val queue = ArrayDeque(timings)
        val queued = HashSet(timings)
        while (queue.isNotEmpty()) {
            val timing = queue.removeFirst()
            queued -= timing
            val looping = timing in inLoop
            val instanceLimit = if (looping) LOOP_INSTANCES else FINITE_INSTANCES
            val beginTimes = instances(timing.begins, instanceLimit)
            val endTimes = instances(timing.ends, instanceLimit)
            if (work.isStopped || spent > maxWork) {
                exhausted = true
                break
            }
            if (timing.computeIntervals(beginTimes, endTimes, if (looping) LOOP_INTERVALS else FINITE_INSTANCES)) {
                dependents[timing]?.forEach { dependent -> if (queued.add(dependent)) queue.addLast(dependent) }
            }
        }
        for (timing in inLoop) {
            timing.inLoop = true
            timing.findPeriod()
        }
        // Stopping at a cap with no pattern to continue from means the picture stops changing early, so part of it is missing.
        if (timings.any { it.capped && it.period == 0.0 }) exhausted = true
    }

    /**
     * Peels off elements whose references are all settled, over and over (Kahn's algorithm). Whatever cannot be peeled off leads to a
     * loop. It runs without recursion and sees each reference once, because a file can have long chains and many paths through them.
     */
    private fun orderDependencies(
        dependents: Map<SmilTiming, Set<SmilTiming>>,
        dependencies: Map<SmilTiming, Set<SmilTiming>>,
    ) {
        val remaining = HashMap<SmilTiming, Int>()
        val ready = ArrayDeque<SmilTiming>()
        for (timing in timings) {
            val count = dependencies[timing]?.size ?: 0
            remaining[timing] = count
            if (count == 0) ready.addLast(timing)
        }
        val settled = HashSet<SmilTiming>()
        while (ready.isNotEmpty()) {
            val timing = ready.removeFirst()
            settled += timing
            dependents[timing]?.forEach { dependent ->
                val left = (remaining[dependent] ?: 0) - 1
                remaining[dependent] = left
                if (left == 0) ready.addLast(dependent)
            }
        }
        for (timing in timings) if (timing !in settled) inLoop += timing
    }

    /** The times these specs give, from the earliest, and no more than [limit]. Everything looked at is paid for, also what is dropped. */
    private fun instances(specs: List<TimeSpec>, limit: Int): DoubleArray {
        var times = DoubleArray(minOf(specs.size + 8, limit))
        var count = 0

        fun add(value: Double) {
            if (count == times.size) {
                // Only the earliest stay, so once there are plenty, the rest can go. A long list of references to a long list stays small.
                if (count >= limit * 2) {
                    count = sortedDistinct(times, count, limit)
                } else {
                    times = times.copyOf(minOf(times.size * 2, limit * 2))
                }
            }
            times[count++] = value
        }
        for (spec in specs) {
            if (!pay(WorkCost.TIME_VALUE)) break
            when (spec) {
                is TimeSpec.Offset -> {
                    add(spec.seconds)
                }

                is TimeSpec.Sync -> {
                    val ref = byId[spec.refId] ?: continue
                    if (!pay(ref.intervalCount * WorkCost.TIME_VALUE)) break
                    for (i in 0 until ref.intervalCount) {
                        val value =
                            when (spec.kind) {
                                SyncKind.BEGIN -> ref.beginOf(i)
                                SyncKind.END -> ref.endOf(i)
                                SyncKind.REPEAT -> repeatTime(ref, i, spec.repeat)
                            } + spec.offset
                        if (value.isFinite()) add(value)
                    }
                }
            }
        }
        pay(count * WorkCost.TIME_VALUE)
        return times.copyOf(sortedDistinct(times, count, limit))
    }

    /** Sorts the first [count] in place, with duplicates taken out, and says how many of them to keep. */
    private fun sortedDistinct(times: DoubleArray, count: Int, limit: Int): Int {
        times.sort(0, count)
        var kept = 0
        for (i in 0 until count) {
            if (kept == 0 || times[i] != times[kept - 1]) times[kept++] = times[i]
        }
        return minOf(kept, limit)
    }

    private fun repeatTime(ref: SmilTiming, index: Int, repeat: Int): Double {
        val d = ref.duration ?: return Double.NaN
        val at = ref.beginOf(index) + d * repeat
        return if (at < ref.endOf(index)) at else Double.NaN
    }

    private companion object {
        // Enough for the repeating pattern of a loop to show, and each extra one costs time at every step around that loop.
        const val LOOP_INTERVALS = 96
        const val LOOP_INSTANCES = 192

        // No loop is involved here, so these are all real times from the file, and this only stops them multiplying out of control.
        const val FINITE_INSTANCES = 65_536
    }
}

/** The timing of one entry of a CSS `animation` list, following the Web Animations model of phases and iterations. */
internal class CssTiming(
    val duration: Double,
    val delay: Double,
    val iterations: Double,
    val direction: String,
    val fillMode: String,
    val paused: Boolean,
) {
    private val fillsBackwards = fillMode == "backwards" || fillMode == "both"
    private val fillsForwards = fillMode == "forwards" || fillMode == "both"

    /** Progress through the keyframes at [time], from 0 to 1 and with direction applied, or null when the animation has no effect. */
    fun progressAt(time: Double): Double? {
        // A paused animation sits at its first moment for good, whatever its delay says about that moment.
        val local = (if (paused) 0.0 else time) - delay
        val activeDuration = if (duration <= 0.0) 0.0 else duration * iterations
        return when {
            local < 0.0 -> {
                if (fillsBackwards) directed(0.0, 0.0) else null
            }

            local >= activeDuration -> {
                if (fillsForwards) endState() else null
            }

            else -> {
                val q = local / duration
                val iteration = floor(q)
                directed(q - iteration, iteration)
            }
        }
    }

    private fun endState(): Double {
        if (iterations <= 0.0) return directed(0.0, 0.0)
        if (duration <= 0.0) return directed(1.0, 0.0)
        val iteration = floor(iterations)
        val fraction = iterations - iteration
        return if (fraction == 0.0) directed(1.0, iteration - 1.0) else directed(fraction, iteration)
    }

    /** The iteration stays a double because a fast loop at a late time runs past what an Int holds, which would lose its parity. */
    private fun directed(fraction: Double, iteration: Double): Double {
        val odd = iteration % 2.0 != 0.0
        val reversed =
            when (direction) {
                "reverse" -> true
                "alternate" -> odd
                "alternate-reverse" -> !odd
                else -> false
            }
        return if (reversed) 1.0 - fraction else fraction
    }

    /** How long until the picture repeats exactly. Alternating takes two runs to come back to where it began. */
    val cycle: Double get() = if (direction.startsWith("alternate")) duration * 2 else duration
}
