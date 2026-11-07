package org.kineograph.engine.vector

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kineograph.engine.brush.BrushPresets
import org.kineograph.engine.brush.BrushSpec
import org.kineograph.engine.brush.RecordingGaveUp
import org.kineograph.engine.brush.StampBuffer
import org.kineograph.engine.brush.StrokeBuilder
import org.kineograph.engine.brush.StrokeEngine
import org.kineograph.engine.brush.StrokeOptions
import org.kineograph.engine.brush.StrokeRecording
import java.lang.management.ManagementFactory

class StrokeRecordingTest {
    private val pen = BrushPresets.PEN
    private val pencil = BrushPresets.PENCIL
    private val plenty = Long.MAX_VALUE

    private fun recording(spec: BrushSpec = pen, maxBytes: Long = plenty) = StrokeRecording(spec, 7L, false, maxBytes)

    /** The stamps these samples give through a new engine, which is all a replay has to go on. */
    private fun stampsFrom(recording: StrokeRecording): StampBuffer {
        val samples = recording.copySamples()
        val stride = recording.stride
        val engine = StrokeEngine(recording.spec, recording.seed)
        val out = StampBuffer()
        for (i in 0 until recording.count) {
            val at = i * stride
            val tilt = if (stride == 5) samples[at + 3] else 0f
            val orientation = if (stride == 5) samples[at + 4] else 0f
            if (i == 0) {
                engine.begin(samples[at], samples[at + 1], samples[at + 2], tilt, orientation, out)
            } else {
                engine.add(samples[at], samples[at + 1], samples[at + 2], tilt, orientation, out)
            }
        }
        engine.finish(out)
        return out
    }

    private fun stampsOf(script: PenScript, spec: BrushSpec, recording: StrokeRecording?): StampBuffer {
        val builder = StrokeBuilder(spec, script.options, 7L, recording)
        val all = StampBuffer()
        script.play(builder) { run ->
            for (k in 0 until run.count) {
                val at = k * StampBuffer.STRIDE
                all.add(run.data[at], run.data[at + 1], run.data[at + 2], run.data[at + 3], run.data[at + 4])
            }
        }
        return all
    }

    private fun assertSameStamps(what: String, expected: StampBuffer, actual: StampBuffer) {
        assertEquals("$what: number of stamps", expected.count, actual.count)
        for (k in 0 until expected.count) {
            assertEquals("$what: stamp $k x", expected.x(k).toRawBits(), actual.x(k).toRawBits())
            assertEquals("$what: stamp $k y", expected.y(k).toRawBits(), actual.y(k).toRawBits())
            assertEquals("$what: stamp $k diameter", expected.diameter(k).toRawBits(), actual.diameter(k).toRawBits())
            assertEquals("$what: stamp $k alpha", expected.alpha(k).toRawBits(), actual.alpha(k).toRawBits())
            assertEquals("$what: stamp $k angle", expected.angle(k).toRawBits(), actual.angle(k).toRawBits())
        }
    }

    @Test
    fun `every script gives the same stamps again through a new engine`() {
        for (script in PenScripts.all()) {
            for (spec in listOf(pen, pencil, BrushPresets.MARKER, BrushPresets.ERASER)) {
                val recording = StrokeRecording(spec, 7L, script.isFinger, plenty)
                val drawn = stampsOf(script, spec, recording)
                assertNull(recording.gaveUp)
                assertSameStamps("${script.name} with ${spec.id}", drawn, stampsFrom(recording))
            }
        }
    }

    @Test
    fun `recording changes no stamp`() {
        for (script in PenScripts.all()) {
            for (spec in listOf(pen, pencil)) {
                val with = stampsOf(script, spec, StrokeRecording(spec, 7L, script.isFinger, plenty))
                val without = stampsOf(script, spec, null)
                assertSameStamps("${script.name} with ${spec.id}", without, with)
            }
        }
    }

    @Test
    fun `it stores what the engine is given and nothing else`() {
        val recording = recording()
        val builder = StrokeBuilder(pen, StrokeOptions(), 7L, recording)
        builder.begin(1f, 2f, 100f, 200f, 0.25f, 0.5f, 0.75f, 1000L)
        builder.add(3f, 4f, 300f, 400f, 0.5f, 0.5f, 0.75f, 1004L)
        builder.finish()
        assertArrayEquals(floatArrayOf(1f, 2f, 0.25f, 3f, 4f, 0.5f), recording.copySamples(), 0f)
        assertEquals(1000L, recording.firstTimeMs)
        assertEquals(1004L, recording.lastTimeMs)
        assertEquals(4L, recording.durationMs)
    }

    @Test
    fun `tilt is kept only for a brush that reads it`() {
        assertEquals(StrokeRecording.STRIDE_PLAIN, recording(pen).stride)
        assertEquals(StrokeRecording.STRIDE_WITH_TILT, recording(pencil).stride)
        val tilted = recording(pencil)
        tilted.add(1f, 2f, 0.25f, 0.5f, 0.75f, 0L)
        assertArrayEquals(floatArrayOf(1f, 2f, 0.25f, 0.5f, 0.75f), tilted.copySamples(), 0f)
    }

    @Test
    fun `it stores the pressure after smoothing and the place after stabilizing`() {
        val options = StrokeOptions(smoothing = 100, stringLengthPx = 50f, pressureSmoothing = 50)
        val recording = recording()
        val builder = StrokeBuilder(pen, options, 7L, recording)
        builder.begin(10f, 10f, 10f, 10f, 0.2f, 0f, 0f, 0L)
        builder.add(12f, 10f, 12f, 10f, 0.8f, 0f, 0f, 4L)
        val samples = recording.copySamples()
        // Within the stabilizer's string the brush has not moved, and the pressure has only come part of the way.
        assertEquals(10f, samples[3], 0f)
        assertEquals(10f, samples[4], 0f)
        assertTrue("smoothed pressure was ${samples[5]}", samples[5] > 0.2f && samples[5] < 0.8f)
    }

    @Test
    fun `the catch-up at the end of a stabilized stroke is stored`() {
        val options = StrokeOptions(smoothing = 100, stringLengthPx = 50f)
        val recording = recording()
        val builder = StrokeBuilder(pen, options, 7L, recording)
        builder.begin(10f, 10f, 10f, 10f, 0.5f, 0f, 0f, 0L)
        builder.add(40f, 10f, 40f, 10f, 0.6f, 0f, 0f, 4L)
        val before = recording.count
        builder.finish()
        assertEquals(before + 1, recording.count)
        val samples = recording.copySamples()
        assertEquals(40f, samples[samples.size - 3], 0f)
        assertEquals(10f, samples[samples.size - 2], 0f)
        assertEquals(0.6f, samples[samples.size - 1], 0f)
        assertEquals(4L, recording.lastTimeMs)
    }

    @Test
    fun `nothing is stored at the end of a stroke with no stabilizer`() {
        val recording = recording()
        val builder = StrokeBuilder(pen, StrokeOptions(), 7L, recording)
        builder.begin(10f, 10f, 10f, 10f, 0.5f, 0f, 0f, 0L)
        builder.add(40f, 10f, 40f, 10f, 0.6f, 0f, 0f, 4L)
        builder.finish()
        assertEquals(2, recording.count)
    }

    @Test
    fun `predicted points are never stored`() {
        val recording = recording()
        val builder = StrokeBuilder(pen, StrokeOptions(), 7L, recording)
        builder.begin(10f, 10f, 10f, 10f, 0.5f, 0f, 0f, 0L)
        builder.add(20f, 10f, 20f, 10f, 0.5f, 0f, 0f, 4L)
        builder.clearPrediction()
        builder.addPredicted(30f, 10f)
        builder.addPredicted(40f, 10f)
        builder.buildPrediction()
        builder.finish()
        assertEquals(2, recording.count)
        assertArrayEquals(floatArrayOf(10f, 10f, 0.5f, 20f, 10f, 0.5f), recording.copySamples(), 0f)
    }

    @Test
    fun `a pen held still adds one sample however long it stays`() {
        val recording = recording()
        recording.add(10f, 10f, 0.2f, 0f, 0f, 0L)
        recording.add(20f, 10f, 0.3f, 0f, 0f, 4L)
        repeat(1000) { i -> recording.add(20f, 10f, 0.3f + i * 0.0005f, 0f, 0f, 8L + i) }
        assertEquals(3, recording.count)
        val samples = recording.copySamples()
        assertEquals("the sample that moved keeps its pressure", 0.3f, samples[5], 0f)
        assertEquals("the last pressure is what counts", 0.3f + 999 * 0.0005f, samples[8], 0f)
        assertEquals(1007L, recording.lastTimeMs)
    }

    @Test
    fun `a held pen that moves on starts counting again`() {
        val recording = recording()
        recording.add(10f, 10f, 0.2f, 0f, 0f, 0L)
        recording.add(10f, 10f, 0.3f, 0f, 0f, 4L)
        recording.add(10f, 10f, 0.4f, 0f, 0f, 8L)
        recording.add(20f, 10f, 0.5f, 0f, 0f, 12L)
        recording.add(20f, 10f, 0.6f, 0f, 0f, 16L)
        recording.add(20f, 10f, 0.7f, 0f, 0f, 20L)
        assertArrayEquals(
            floatArrayOf(10f, 10f, 0.2f, 10f, 10f, 0.4f, 20f, 10f, 0.5f, 20f, 10f, 0.7f),
            recording.copySamples(),
            0f,
        )
    }

    @Test
    fun `a pen held still replays to the same stamps as all its samples did`() {
        val script = PenScripts.hold()
        val recording = StrokeRecording(pen, 7L, false, plenty)
        val drawn = stampsOf(script, pen, recording)
        assertTrue(
            "the hold should have saved samples, kept ${recording.count} of ${script.samples.size}",
            recording.count < script.samples.size - 200,
        )
        assertSameStamps("hold", drawn, stampsFrom(recording))
    }

    @Test
    fun `blocks grow without copying`() {
        val allocated = ArrayList<FloatArray>()
        val recording =
            StrokeRecording(pen, 7L, false, plenty) { size ->
                FloatArray(size).also { allocated += it }
            }
        var n = 0
        repeat(10_000) { recording.add(n.toFloat(), 0f, 0.5f, 0f, 0f, 0L).also { n++ } }
        assertEquals(10_000, recording.count)
        val sizes = allocated.map { it.size / recording.stride }
        assertEquals(listOf(64, 128, 256, 512, 1024, 2048, 4096, 4096), sizes)
        allocated.forEachIndexed {
            index,
            block,
            ->
            assertSame("block $index should still be the one first made", block, recording.blockAt(index))
        }
        assertEquals("the first sample is where it was first put", 0f, allocated[0][0], 0f)
        assertEquals("the last sample of the first block", 63f, allocated[0][63 * 3], 0f)
        assertEquals("the first sample of the second block", 64f, allocated[1][0], 0f)
        val samples = recording.copySamples()
        for (i in 0 until 10_000) assertEquals("sample $i", i.toFloat(), samples[i * 3], 0f)
    }

    @Test
    fun `a first block is what the plan priced`() {
        val allocated = ArrayList<Int>()
        StrokeRecording(pen, 7L, false, plenty) { size -> FloatArray(size).also { allocated += it.size } }.add(0f, 0f, 0f, 0f, 0f, 0L)
        StrokeRecording(pencil, 7L, false, plenty) { size -> FloatArray(size).also { allocated += it.size } }.add(0f, 0f, 0f, 0f, 0f, 0L)
        assertEquals(listOf(64 * 3, 64 * 5), allocated)
    }

    @Test
    fun `nothing is made until there is something to store`() {
        var made = 0
        StrokeRecording(pen, 7L, false, plenty) { size ->
            made++
            FloatArray(size)
        }
        assertEquals(0, made)
    }

    @Test
    fun `the largest stroke fits its block table exactly`() {
        val recording = recording()
        repeat(StrokeRecording.MAX_SAMPLES) { i -> recording.add(i.toFloat(), 0f, 0.5f, 0f, 0f, 0L) }
        assertNull(recording.gaveUp)
        assertEquals(StrokeRecording.MAX_SAMPLES, recording.count)
        val samples = recording.copySamples()
        assertEquals((StrokeRecording.MAX_SAMPLES - 1).toFloat(), samples[samples.size - 3], 0f)
        recording.add(1f, 1f, 0.5f, 0f, 0f, 0L)
        assertEquals(RecordingGaveUp.TOO_LONG, recording.gaveUp)
    }

    @Test
    fun `the limit comes from the bytes it is given`() {
        assertEquals(100, StrokeRecording(pen, 7L, false, 1200L).maxSamples)
        assertEquals(60, StrokeRecording(pencil, 7L, false, 1200L).maxSamples)
        assertEquals(StrokeRecording.MAX_SAMPLES, StrokeRecording(pen, 7L, false, plenty).maxSamples)
        assertEquals(0, StrokeRecording(pen, 7L, false, -5L).maxSamples)
        assertEquals(52_428, StrokeRecording(pencil, 7L, false, 1024L * 1024L).maxSamples)
    }

    @Test
    fun `past its limit it frees everything and the stroke draws on`() {
        val recording = recording(maxBytes = 1200L)
        val builder = StrokeBuilder(pen, StrokeOptions(), 7L, recording)
        val free = StrokeBuilder(pen, StrokeOptions(), 7L, null)
        fun both(block: StrokeBuilder.() -> Unit) {
            builder.block()
            free.block()
        }
        both { begin(0f, 0f, 0f, 0f, 0.5f, 0f, 0f, 0L) }
        for (i in 1 until 100) both { add(i * 2f, 0f, i * 2f, 0f, 0.5f, 0f, 0f, i.toLong()) }
        assertEquals(100, recording.count)
        assertNull(recording.gaveUp)
        both { add(1000f, 0f, 1000f, 0f, 0.5f, 0f, 0f, 200L) }
        assertEquals(RecordingGaveUp.TOO_LONG, recording.gaveUp)
        assertEquals(0, recording.count)
        assertEquals(0, recording.copySamples().size)
        assertEquals(0, recording.blockAt(0).size)
        both { add(1100f, 0f, 1100f, 0f, 0.5f, 0f, 0f, 201L) }
        both { finish() }
        assertEquals(0, recording.count)
        assertSameStamps("the stroke after giving up", free.stamps, builder.stamps)
    }

    @Test
    fun `a limit of nothing gives up at once and does not throw`() {
        val recording = recording(maxBytes = 0L)
        recording.add(1f, 2f, 0.5f, 0f, 0f, 0L)
        assertEquals(RecordingGaveUp.TOO_LONG, recording.gaveUp)
    }

    @Test
    fun `running out of memory gives up and does not throw`() {
        var blocks = 0
        val recording =
            StrokeRecording(pen, 7L, false, plenty) { size ->
                if (++blocks == 3) throw OutOfMemoryError("Test")
                FloatArray(size)
            }
        val builder = StrokeBuilder(pen, StrokeOptions(), 7L, recording)
        val free = StrokeBuilder(pen, StrokeOptions(), 7L, null)
        builder.begin(0f, 0f, 0f, 0f, 0.5f, 0f, 0f, 0L)
        free.begin(0f, 0f, 0f, 0f, 0.5f, 0f, 0f, 0L)
        for (i in 1..1000) {
            builder.add(i * 2f, 0f, 0f, 0f, 0.5f, 0f, 0f, i.toLong())
            free.add(i * 2f, 0f, 0f, 0f, 0.5f, 0f, 0f, i.toLong())
        }
        builder.finish()
        free.finish()
        assertEquals(RecordingGaveUp.OUT_OF_MEMORY, recording.gaveUp)
        assertEquals(0, recording.count)
        assertEquals("nothing is asked for again after giving up", 3, blocks)
        assertSameStamps("the stroke after running out", free.stamps, builder.stamps)
    }

    @Test
    fun `it takes numbers that are not numbers without complaint`() {
        val recording = recording(pencil)
        val strange = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE, -0f, Float.MIN_VALUE)
        for (a in strange) for (b in strange) recording.add(a, b, b, a, b, 0L)
        assertNull(recording.gaveUp)
        assertTrue(recording.count > 0)
    }

    @Test
    fun `a sample that is not a number never counts as holding still`() {
        val recording = recording()
        recording.add(Float.NaN, 0f, 0.1f, 0f, 0f, 0L)
        recording.add(Float.NaN, 0f, 0.2f, 0f, 0f, 0L)
        recording.add(Float.NaN, 0f, 0.3f, 0f, 0f, 0L)
        assertEquals(3, recording.count)
    }

    @Test
    fun `a sample allocates nothing between block boundaries`() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val me = Thread.currentThread().id
        val spec = pencil
        // Everything up to a large block, and a good many rounds so that nothing is left to load or compile first.
        val warmUp = StrokeRecording(spec, 7L, false, plenty)
        repeat(50_000) { i -> warmUp.add(i.toFloat(), 0f, 0.5f, 0f, 0f, 0L) }
        val recording = StrokeRecording(spec, 7L, false, plenty)
        repeat(4032) { i -> recording.add(i.toFloat(), 1f, 0.5f, 0.1f, 0.2f, i.toLong()) }
        repeat(10) { i -> recording.add(i + 5000f, 2f, 0.5f, 0.1f, 0.2f, i.toLong()) }
        val empty = threads.getThreadAllocatedBytes(me)
        val calibration = threads.getThreadAllocatedBytes(me) - empty
        val before = threads.getThreadAllocatedBytes(me)
        for (i in 0 until 3000) {
            // Moving, held still and overwritten, in turn, so all three paths are measured.
            val x = 6000f + i / 3
            recording.add(x, 2f, 0.5f, 0.1f, 0.2f, 9000L + i)
        }
        val after = threads.getThreadAllocatedBytes(me)
        assertEquals("bytes allocated by 3000 samples", 0L, after - before - calibration)
        assertEquals(StrokeRecording.FIRST_BLOCK_SAMPLES, 64)
    }

    @Test
    fun `giving up keeps the first and last times usable`() {
        val recording = recording(maxBytes = 12L)
        recording.add(0f, 0f, 0f, 0f, 0f, 5L)
        recording.add(1f, 0f, 0f, 0f, 0f, 9L)
        assertEquals(RecordingGaveUp.TOO_LONG, recording.gaveUp)
        assertEquals(4L, recording.durationMs)
        assertNotSame(recording.blockAt(0), null)
    }
}
