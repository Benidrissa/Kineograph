package org.kineograph.engine.brush

/** Why a [StrokeRecording] stopped keeping samples. The stroke itself carries on drawing either way. */
public enum class RecordingGaveUp {
    /** It reached its limit on samples. */
    TOO_LONG,

    /** There was no memory for the next block. */
    OUT_OF_MEMORY,
}

/**
 * What one stroke fed its [StrokeEngine], as plain floats. Replaying these through a new engine with the same [seed] places the same
 * stamps, which makes this the smallest description of a stroke that still draws it. They are the samples after pressure processing and
 * stabilizing, and predicted points never come here.
 *
 * It runs on the input thread, for every sample, so [add] only stores floats. Samples live in blocks that double from [FIRST_BLOCK_SAMPLES]
 * to [LARGEST_BLOCK_SAMPLES] and are never copied, because copying a long stroke's samples to grow an array could be noticed under the pen.
 * If memory runs out or the stroke goes past its limit, the blocks are freed and the stroke draws on with nothing recorded.
 *
 * Only tilt-shaded brushes read tilt and orientation, so nothing else stores them. Samples are x, y, pressure, and then tilt and
 * orientation when [stride] is [STRIDE_WITH_TILT]. Used by one thread until the stroke is handed over, and only read from then on.
 */
public class StrokeRecording internal constructor(
    public val spec: BrushSpec,
    public val seed: Long,
    public val isFinger: Boolean,
    maxBytes: Long,
    private val allocate: (Int) -> FloatArray,
) {
    /** Keeps at most [maxBytes] of samples, or [MAX_SAMPLES] of them if that comes first. */
    public constructor(spec: BrushSpec, seed: Long, isFinger: Boolean, maxBytes: Long) :
        this(spec, seed, isFinger, maxBytes, { size -> FloatArray(size) })

    /** Floats per sample. */
    public val stride: Int = if (spec.tiltShading > 0f) STRIDE_WITH_TILT else STRIDE_PLAIN

    /** How many samples this keeps before it gives up. */
    public val maxSamples: Int =
        (maxBytes.coerceAtLeast(0L) / (stride * Float.SIZE_BYTES)).coerceAtMost(MAX_SAMPLES.toLong()).toInt()

    private val blocks = arrayOfNulls<FloatArray>(MAX_BLOCKS)
    private var blockCount = 0
    private var current = NO_SAMPLES
    private var used = 0
    private var lastX = 0f
    private var lastY = 0f
    private var lastWasStationary = false

    /** The number of samples held. */
    public var count: Int = 0
        private set

    /** The time of the first sample. */
    public var firstTimeMs: Long = 0L
        private set

    /** The time of the latest sample. */
    public var lastTimeMs: Long = 0L
        private set

    /** Null until this gives up, and from then on it holds nothing. */
    public var gaveUp: RecordingGaveUp? = null
        private set

    /** How long the stroke took. */
    public val durationMs: Long get() = (lastTimeMs - firstTimeMs).coerceAtLeast(0L)

    /**
     * Stores one sample. A pen held still adds at most one: [StrokeEngine.add] only takes the new pressure from a sample that has not
     * moved, so all but the last of them say nothing. That first still sample is a new one, because overwriting the sample before it, which
     * did move, would change the pressure at that sample's midpoint. This never throws, whatever the numbers are.
     */
    public fun add(x: Float, y: Float, pressure: Float, tilt: Float, orientation: Float, timeMs: Long) {
        if (gaveUp != null) return
        val isStationary = count > 0 && x == lastX && y == lastY
        if (count == 0) firstTimeMs = timeMs
        lastTimeMs = timeMs
        if (isStationary && lastWasStationary) {
            current[used - stride + PRESSURE_OFFSET] = pressure
            return
        }
        if (used == current.size && !grow()) return
        val block = current
        val at = used
        block[at] = x
        block[at + 1] = y
        block[at + PRESSURE_OFFSET] = pressure
        if (stride == STRIDE_WITH_TILT) {
            block[at + 3] = tilt
            block[at + 4] = orientation
        }
        used = at + stride
        count++
        lastX = x
        lastY = y
        lastWasStationary = isStationary
    }

    /** All the samples in one new array. It is sized by [count], so a very long stroke is best walked through [blockAt] instead. */
    public fun copySamples(): FloatArray {
        val out = FloatArray(count * stride)
        var written = 0
        for (index in 0 until blockCount) {
            val block = blockAt(index)
            val floats = minOf(block.size, out.size - written)
            block.copyInto(out, written, 0, floats)
            written += floats
        }
        return out
    }

    /** One block of samples. The last one in use may be only partly full, which [count] tells. */
    internal fun blockAt(index: Int): FloatArray = blocks.getOrNull(index) ?: NO_SAMPLES

    private fun grow(): Boolean {
        if (count >= maxSamples) {
            giveUp(RecordingGaveUp.TOO_LONG)
            return false
        }
        val samples = minOf(blockSamples(blockCount), maxSamples - count)
        val block =
            try {
                allocate(samples * stride)
            } catch (_: OutOfMemoryError) {
                giveUp(RecordingGaveUp.OUT_OF_MEMORY)
                return false
            }
        blocks[blockCount++] = block
        current = block
        used = 0
        return true
    }

    private fun giveUp(reason: RecordingGaveUp) {
        gaveUp = reason
        blocks.fill(null)
        blockCount = 0
        current = NO_SAMPLES
        used = 0
        count = 0
    }

    public companion object {
        /** Floats per sample on a brush that ignores tilt: x, y, pressure. */
        public const val STRIDE_PLAIN: Int = 3

        /** Floats per sample on a tilt-shaded brush: x, y, pressure, tilt, orientation. */
        public const val STRIDE_WITH_TILT: Int = 5

        /** The most samples any stroke can keep, and the most one saved stroke may hold. About four and a half minutes of ink at 240 Hz. */
        public const val MAX_SAMPLES: Int = 65_536

        internal const val FIRST_BLOCK_SAMPLES = 64
        internal const val LARGEST_BLOCK_SAMPLES = 4096

        /** 64 up to 4096 is 7 blocks and 8128 samples, and the rest of [MAX_SAMPLES] takes 15 more at 4096. */
        internal const val MAX_BLOCKS = 22
        private const val PRESSURE_OFFSET = 2
        private val NO_SAMPLES = FloatArray(0)

        internal fun blockSamples(index: Int): Int =
            if (index >= DOUBLINGS) LARGEST_BLOCK_SAMPLES else FIRST_BLOCK_SAMPLES shl index

        private const val DOUBLINGS = 6
    }
}
