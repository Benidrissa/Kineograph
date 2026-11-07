package org.kineograph.engine.vector

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kineograph.engine.brush.BrushPresets
import org.kineograph.engine.brush.BrushSpec
import org.kineograph.engine.brush.CurvePoint
import org.kineograph.engine.brush.PressureCurve
import org.kineograph.engine.brush.StrokeBlend
import org.kineograph.engine.brush.StrokeBuilder
import org.kineograph.engine.brush.StrokeEngine
import org.kineograph.engine.brush.StrokeRecording
import org.kineograph.engine.brush.TipShape
import org.kineograph.engine.selection.SelectionTransform
import org.kineograph.model.IntRect
import java.io.ByteArrayOutputStream
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.zip.CRC32

class VectorCodecTest {
    private val cel = IntRect(10, 20, 110, 220)
    private val plenty = Long.MAX_VALUE

    private class Collected : VectorVisitor {
        val strokes = ArrayList<VectorStroke>()
        val gaps = ArrayList<VectorGap>()
        val order = ArrayList<Long>()

        override fun onStroke(stroke: VectorStroke) {
            strokes += stroke
            order += stroke.id
        }

        override fun onGap(gap: VectorGap) {
            gaps += gap
            order += gap.id
        }
    }

    private fun file(bounds: IntRect = cel, write: VectorFileEncoder.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        val encoder = VectorFileEncoder(out, bounds)
        encoder.write()
        encoder.finish()
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray, bounds: IntRect? = cel): Collected = Collected().also { VectorCodec.decodeFile(bytes, bounds, it) }

    private fun recordingOf(
        spec: BrushSpec = BrushPresets.PEN,
        seed: Long = 7L,
        isFinger: Boolean = false,
        script: PenScript = PenScripts
            .curve(),
    ) =
        StrokeRecording(spec, seed, isFinger, plenty).also { script.play(StrokeBuilder(spec, script.options, seed, it)) }

    private fun assertSameBits(expected: FloatArray, actual: FloatArray) {
        assertEquals("number of values", expected.size, actual.size)
        for (i in expected.indices) assertEquals("value $i", expected[i].toRawBits(), actual[i].toRawBits())
    }

    /** Rewrites the checksums after a change, as someone who means harm would, so that what is behind them gets tested. */
    private fun resigned(bytes: ByteArray): ByteArray {
        val out = bytes.copyOf()
        val footer = out.size - VectorCodec.FOOTER_BYTES
        val crc = CRC32()
        crc.update(out, 0, footer)
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(footer + 40, crc.value.toInt())
        crc.reset()
        crc.update(out, footer, 44)
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(footer + 44, crc.value.toInt())
        return out
    }

    private fun assertRefused(why: VectorDamage, bytes: ByteArray, bounds: IntRect? = cel) {
        val thrown = assertThrows(VectorFormatException::class.java) { read(bytes, bounds) }
        assertEquals(why, thrown.why)
    }

    // ---- Strokes ----

    @Test
    fun `every field of a stroke comes back`() {
        val spec = BrushPresets.PENCIL
        val recording = recordingOf(spec, seed = -0x7766554433221100L, isFinger = true)
        val bounds = IntRect(-30, 5, 400, 300)
        val clip = IntRect(-20, -20, 500, 340)
        val bytes = file { assertTrue(addStroke(0x0102030405060708L, recording, 0x80FF7F01.toInt(), bounds, clip)) }
        val stroke = read(bytes, bounds = IntRect(10, 20, 110, 220)).strokes.single()
        assertEquals(0x0102030405060708L, stroke.id)
        assertEquals(-0x7766554433221100L, stroke.seed)
        assertEquals(spec, stroke.spec)
        assertEquals(0x80FF7F01.toInt(), stroke.colorArgb)
        assertEquals(StrokeEngine.REVISION, stroke.engineRevision)
        assertTrue(stroke.isFinger)
        assertEquals(recording.durationMs, stroke.durationMs)
        assertEquals(bounds, stroke.bounds)
        assertEquals(clip, stroke.clip)
        assertEquals(recording.count, stroke.count)
        assertEquals(StrokeRecording.STRIDE_WITH_TILT, stroke.stride)
        assertSameBits(recording.copySamples(), stroke.samples)
        assertEquals(0, stroke.offsetX)
        assertEquals(0, stroke.offsetY)
    }

    @Test
    fun `every preset comes back the same at every size`() {
        val presets = BrushPresets.DRAWING + BrushPresets.ERASER
        for (preset in presets) {
            for (size in listOf(1f, preset.sizePx, 37.5f, 400f)) {
                for (opacity in listOf(0.01f, preset.opacity, 1f)) {
                    val spec = preset.copy(sizePx = size, opacity = opacity)
                    val recording = recordingOf(spec, script = PenScripts.flick())
                    val stroke = read(file { assertTrue(addStroke(1L, recording, -1, IntRect(0, 0, 1, 1), null)) }).strokes.single()
                    assertEquals(spec, stroke.spec)
                    assertSameBits(recording.copySamples(), stroke.samples)
                }
            }
        }
    }

    @Test
    fun `no brush field is left behind`() {
        val everything =
            BrushSpec(
                id = "é-brush",
                tip = TipShape.SQUARE,
                sizePx = 1024f,
                spacing = 16f,
                opacity = 0.1f,
                flow = 0.2f,
                hardness = 0.3f,
                roundness = 0.4f,
                tipAngleDegrees = -360f,
                sizeCurve = PressureCurve(List(16) { CurvePoint(it / 15f, 1f - it / 15f) }),
                flowCurve = PressureCurve(listOf(CurvePoint(0.5f, 0.25f))),
                tiltShading = 0.5f,
                sizeJitter = 4f,
                angleJitterDegrees = 360f,
                scatter = 8f,
                grain = 0.6f,
                antiAlias = false,
                blend = StrokeBlend.ERASE,
            )
        val recording = StrokeRecording(everything, 1L, false, plenty)
        recording.add(1f, 2f, 0.5f, 0.1f, 0.2f, 0L)
        val stroke = read(file { assertTrue(addStroke(1L, recording, 0, IntRect(0, 0, 9, 9), null)) }).strokes.single()
        assertEquals(everything, stroke.spec)
        // A field added to BrushSpec has to be added to the format as well, and here is where that gets noticed.
        val fields = BrushSpec::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertEquals(18, fields.size)
    }

    @Test
    fun `samples come back as the very same floats`() {
        for (script in PenScripts.all()) {
            for (spec in listOf(BrushPresets.PEN, BrushPresets.PENCIL)) {
                val recording = recordingOf(spec, script = script)
                val stroke = read(file { addStroke(1L, recording, 0, IntRect(0, 0, 9, 9), null) }).strokes.single()
                assertSameBits(recording.copySamples(), stroke.samples)
            }
        }
    }

    @Test
    fun `floats that are awkward to difference come back`() {
        val recording = StrokeRecording(BrushPresets.PENCIL, 1L, false, plenty)
        val awkward =
            floatArrayOf(
                0f,
                -0f,
                Float.MIN_VALUE,
                -Float.MIN_VALUE,
                1e-30f,
                -1e-30f,
                8388608f,
                -8388608f,
                0.1f,
                0.2f,
                -0.1f,
                1f,
                2f,
                4f,
                8f,
            )
        var i = 0
        for (a in awkward) {
            for (b in awkward) {
                // Other values go through the whole float range, which is the most any difference can be.
                recording.add(a, b, Float.MAX_VALUE, -Float.MAX_VALUE, if (i % 2 == 0) Float.MAX_VALUE else -Float.MAX_VALUE, i.toLong())
                i++
            }
        }
        val stroke = read(file { assertTrue(addStroke(1L, recording, 0, IntRect(0, 0, 9, 9), null)) }).strokes.single()
        assertSameBits(recording.copySamples(), stroke.samples)
    }

    @Test
    fun `the first and last samples of every block survive`() {
        val recording = StrokeRecording(BrushPresets.PEN, 1L, false, plenty)
        for (i in 0 until 9000) recording.add(i * 0.25f, i * 0.5f, i / 9000f, 0f, 0f, i.toLong())
        val stroke = read(file { addStroke(1L, recording, 0, IntRect(0, 0, 9, 9), null) }).strokes.single()
        assertSameBits(recording.copySamples(), stroke.samples)
    }

    @Test
    fun `length is the path through the samples`() {
        val recording = StrokeRecording(BrushPresets.PEN, 1L, false, plenty)
        recording.add(0f, 0f, 0.5f, 0f, 0f, 0L)
        recording.add(3f, 4f, 0.5f, 0f, 0f, 1L)
        recording.add(3f, 14f, 0.5f, 0f, 0f, 2L)
        val stroke = read(file { addStroke(1L, recording, 0, IntRect(0, 0, 9, 9), null) }).strokes.single()
        assertEquals(15f, stroke.lengthPx, 0f)
    }

    @Test
    fun `a tap is one sample and has no length`() {
        val recording = recordingOf(script = PenScripts.tap())
        val stroke = read(file { addStroke(1L, recording, 0, IntRect(0, 0, 9, 9), null) }).strokes.single()
        assertEquals(1, stroke.count)
        assertEquals(0f, stroke.lengthPx, 0f)
    }

    @Test
    fun `a stroke with no clip says so`() {
        val recording = recordingOf(script = PenScripts.tap())
        assertNull(read(file { addStroke(1L, recording, 0, IntRect(0, 0, 9, 9), null) }).strokes.single().clip)
    }

    @Test
    fun `a stroke holding what cannot be stored becomes a gap`() {
        val cases =
            listOf(
                Float.NaN to "x",
                Float.POSITIVE_INFINITY to "x",
                Float.NEGATIVE_INFINITY to "x",
                2e7f to "x",
                -2e7f to "x",
            )
        for ((value, _) in cases) {
            for (field in 0..4) {
                val recording = StrokeRecording(BrushPresets.PENCIL, 1L, false, plenty)
                recording.add(1f, 1f, 0.5f, 0f, 0f, 0L)
                val fields = floatArrayOf(2f, 2f, 0.5f, 0f, 0f)
                fields[field] = value
                recording.add(fields[0], fields[1], fields[2], fields[3], fields[4], 1L)
                val isFiniteLargeValue = value.isFinite() && field >= 2
                val bytes = file { assertEquals(isFiniteLargeValue, addStroke(5L, recording, 0, IntRect(1, 2, 3, 4), null)) }
                val read = read(bytes)
                if (isFiniteLargeValue) {
                    assertEquals(1, read.strokes.size)
                } else {
                    val gap = read.gaps.single() as VectorLost
                    assertEquals(5L, gap.id)
                    assertEquals(LostReason.BAD_INPUT, gap.reason)
                    assertEquals(IntRect(1, 2, 3, 4), gap.rect)
                }
            }
        }
    }

    @Test
    fun `a recording that gave up or holds nothing becomes a gap`() {
        val gaveUp = StrokeRecording(BrushPresets.PEN, 1L, false, 0L)
        gaveUp.add(1f, 1f, 0.5f, 0f, 0f, 0L)
        val empty = StrokeRecording(BrushPresets.PEN, 1L, false, plenty)
        val read =
            read(
                file {
                    assertFalse(addStroke(1L, gaveUp, 0, IntRect(0, 0, 1, 1), null))
                    assertFalse(addStroke(2L, empty, 0, IntRect(0, 0, 1, 1), null))
                },
            )
        assertEquals(LostReason.TOO_LONG, (read.gaps[0] as VectorLost).reason)
        assertEquals(LostReason.UNRECORDED, (read.gaps[1] as VectorLost).reason)
    }

    @Test
    fun `a brush the format cannot hold becomes a gap`() {
        val bad =
            listOf(
                BrushPresets.PEN.copy(id = "x".repeat(33)),
                BrushPresets.PEN.copy(id = "é".repeat(17)),
                BrushPresets.PEN.copy(sizePx = 0f),
                BrushPresets.PEN.copy(sizePx = 1025f),
                BrushPresets.PEN.copy(sizePx = Float.NaN),
                BrushPresets.PEN.copy(spacing = -1f),
                BrushPresets.PEN.copy(opacity = 1.5f),
                BrushPresets.PEN.copy(flow = Float.NaN),
                BrushPresets.PEN.copy(hardness = -0.5f),
                BrushPresets.PEN.copy(roundness = 2f),
                BrushPresets.PEN.copy(tipAngleDegrees = 361f),
                BrushPresets.PEN.copy(tiltShading = 2f),
                BrushPresets.PEN.copy(sizeJitter = 5f),
                BrushPresets.PEN.copy(angleJitterDegrees = 400f),
                BrushPresets.PEN.copy(scatter = 9f),
                BrushPresets.PEN.copy(grain = 1.5f),
                BrushPresets.PEN.copy(sizeCurve = PressureCurve(List(17) { CurvePoint(it / 16f, 1f) })),
                BrushPresets.PEN.copy(flowCurve = PressureCurve(listOf(CurvePoint(0f, 2f)))),
            )
        for (spec in bad) {
            val recording = StrokeRecording(spec, 1L, false, plenty)
            recording.add(1f, 1f, 0.5f, 0f, 0f, 0L)
            val read = read(file { assertFalse("$spec", addStroke(5L, recording, 0, IntRect(0, 0, 1, 1), null)) })
            assertEquals(LostReason.BAD_INPUT, (read.gaps.single() as VectorLost).reason)
        }
    }

    @Test
    fun `a stroke whose bounds are nowhere near a canvas becomes a gap`() {
        val recording = recordingOf(script = PenScripts.tap())
        val far = 1 shl 24
        val read = read(file { assertFalse(addStroke(5L, recording, 0, IntRect(-far, 0, far, 1), null)) })
        val gap = read.gaps.single() as VectorLost
        assertEquals(LostReason.BAD_INPUT, gap.reason)
        assertEquals("the rectangle is kept within what a reader takes", IntRect(-(1 shl 23), 0, 1 shl 23, 1), gap.rect)
    }

    // ---- Gaps ----

    @Test
    fun `a fill comes back`() {
        val fill = VectorFill(9L, 30, -40, 0x11223344, 255, 12, 2, sampleAllLayers = true, behind = false, IntRect(1, 2, 300, 400))
        val back = read(file { addFill(fill) }).gaps.single() as VectorFill
        assertEquals(9L, back.id)
        assertEquals(30, back.x)
        assertEquals(-40, back.y)
        assertEquals(0x11223344, back.colorArgb)
        assertEquals(255, back.tolerance)
        assertEquals(12, back.gapClose)
        assertEquals(2, back.expand)
        assertTrue(back.sampleAllLayers)
        assertFalse(back.behind)
        assertEquals(IntRect(1, 2, 300, 400), back.rect)
        val other = VectorFill(9L, 0, 0, 0, 0, 0, 0, sampleAllLayers = false, behind = true, IntRect(0, 0, 1, 1))
        val backOther = read(file { addFill(other) }).gaps.single() as VectorFill
        assertFalse(backOther.sampleAllLayers)
        assertTrue(backOther.behind)
    }

    @Test
    fun `a lift comes back with its outline`() {
        val transform = SelectionTransform(IntRect(5, 6, 50, 60), 1.5f, -2.5f, 1.25f, 0.75f)
        val points = floatArrayOf(5.25f, 6.5f, 50f, 6f, 49.75f, 60f, 5f, 59.015625f)
        val lift = VectorLift(3L, deleted = false, transform, points, 4)
        val back = read(file { addLift(lift) }).gaps.single() as VectorLift
        assertEquals(3L, back.id)
        assertFalse(back.deleted)
        assertEquals(transform, back.transform)
        assertEquals(4, back.pointCount)
        assertArrayEquals(points, back.points, 0f)
    }

    @Test
    fun `an outline is kept to within half a storage step`() {
        val random = Random(4L)
        val points = FloatArray(200) { random.nextFloat() * 500f - 100f }
        val lift = VectorLift(3L, deleted = true, SelectionTransform(IntRect(0, 0, 9, 9)), points, 100)
        val back = read(file { addLift(lift) }).gaps.single() as VectorLift
        assertTrue(back.deleted)
        for (i in points.indices) {
            assertEquals(
                "point value $i",
                points[i],
                back.points[i],
                (0.5 / VectorCodec.POSITION_SCALE).toFloat() + 1e-4f,
            )
        }
    }

    @Test
    fun `an outline too long to keep is dropped and the lift is not`() {
        val tooMany = VectorCodec.MAX_OUTLINE_POINTS + 1
        val lift = VectorLift(3L, deleted = false, SelectionTransform(IntRect(0, 0, 9, 9)), FloatArray(tooMany * 2), tooMany)
        val back = read(file { addLift(lift) }).gaps.single() as VectorLift
        assertEquals(0, back.pointCount)
        assertEquals(IntRect(0, 0, 9, 9), back.transform.source)
    }

    @Test
    fun `an outline of the longest length is kept`() {
        val most = VectorCodec.MAX_OUTLINE_POINTS
        val lift = VectorLift(3L, deleted = false, SelectionTransform(IntRect(0, 0, 9, 9)), FloatArray(most * 2) { it * 0.5f }, most)
        assertEquals(most, (read(file { addLift(lift) }).gaps.single() as VectorLift).pointCount)
    }

    @Test
    fun `a lift or a fill that cannot be stored becomes a gap`() {
        val badLift = VectorLift(3L, deleted = false, SelectionTransform(IntRect(0, 0, 9, 9), Float.NaN), FloatArray(0), 0)
        val badFill = VectorFill(4L, 1 shl 24, 0, 0, 0, 0, 0, sampleAllLayers = false, behind = false, IntRect(0, 0, 1, 1))
        val negative = VectorFill(5L, 0, 0, 0, -1, 0, 0, sampleAllLayers = false, behind = false, IntRect(0, 0, 1, 1))
        val read =
            read(
                file {
                    addLift(badLift)
                    addFill(badFill)
                    addFill(negative)
                },
            )
        read.gaps.forEach { assertEquals(LostReason.BAD_INPUT, (it as VectorLost).reason) }
        assertEquals(3, read.gaps.size)
    }

    @Test
    fun `an outline point that is not a number loses the outline and keeps the lift`() {
        val lift = VectorLift(3L, deleted = false, SelectionTransform(IntRect(0, 0, 9, 9)), floatArrayOf(1f, Float.NaN), 1)
        assertEquals(0, (read(file { addLift(lift) }).gaps.single() as VectorLift).pointCount)
    }

    @Test
    fun `every reason for a lost record comes back`() {
        for (reason in LostReason.entries) {
            val back = read(file { addLost(VectorLost(8L, reason, IntRect(-5, -6, 7, 8))) }).gaps.single() as VectorLost
            assertEquals(reason, back.reason)
            assertEquals(IntRect(-5, -6, 7, 8), back.rect)
        }
    }

    // ---- The file ----

    @Test
    fun `the header and footer are laid out as documented`() {
        val recording = recordingOf(script = PenScripts.tap())
        val bytes =
            file(IntRect(-1, -2, 300, 400)) {
                addStroke(1L, recording, 0, IntRect(0, 0, 9, 9), null)
                addFill(VectorFill(2L, 0, 0, 0, 0, 0, 0, sampleAllLayers = false, behind = false, IntRect(0, 0, 1, 1)))
                addLost(VectorLost(3L, LostReason.TOO_LONG, IntRect(0, 0, 1, 1)))
            }
        assertEquals("KGVS", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(1, bytes[4].toInt())
        assertEquals(0, bytes[5].toInt())
        assertEquals(0, bytes[6].toInt())
        assertEquals(0, bytes[7].toInt())
        val footer = bytes.size - 48
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("KGVE", String(bytes, footer, 4, Charsets.US_ASCII))
        assertEquals(bytes.size - 8 - 48, buffer.getInt(footer + 4))
        assertEquals(3, buffer.getInt(footer + 8))
        assertEquals(2, buffer.getInt(footer + 12))
        assertEquals(VectorCodec.GAP_FILL or VectorCodec.GAP_TOO_LONG, buffer.getInt(footer + 16))
        assertEquals(-1, buffer.getInt(footer + 20))
        assertEquals(-2, buffer.getInt(footer + 24))
        assertEquals(300, buffer.getInt(footer + 28))
        assertEquals(400, buffer.getInt(footer + 32))
        assertEquals(0, buffer.getInt(footer + 36))
        val body = CRC32().apply { update(bytes, 0, footer) }.value.toInt()
        assertEquals(body, buffer.getInt(footer + 40))
        val whole = CRC32().apply { update(bytes, footer, 44) }.value.toInt()
        assertEquals(whole, buffer.getInt(footer + 44))
    }

    @Test
    fun `a record is laid out as documented`() {
        val bytes = file { addLost(VectorLost(0x0102030405060708L, LostReason.BAD_INPUT, IntRect(-3, 5, -1, 6))) }
        val record = bytes.copyOfRange(8, bytes.size - 48)
        val expected =
            byteArrayOf(
                VectorCodec.KIND_LOST.toByte(),
                0,
                8,
                7,
                6,
                5,
                4,
                3,
                2,
                1,
                // Anchor -3, 5 as zigzags, then a payload of five bytes.
                5,
                10,
                5,
                3,
                0,
                0,
                4,
                2,
            )
        assertArrayEquals(expected, record)
    }

    @Test
    fun `records come back in the order they went in`() {
        val recording = recordingOf(script = PenScripts.tap())
        val read =
            read(
                file {
                    addStroke(1L, recording, 0, IntRect(0, 0, 1, 1), null)
                    addLost(VectorLost(2L, LostReason.UNRECORDED, IntRect(0, 0, 1, 1)))
                    addStroke(3L, recording, 0, IntRect(0, 0, 1, 1), null)
                    addFill(VectorFill(4L, 0, 0, 0, 0, 0, 0, sampleAllLayers = false, behind = false, IntRect(0, 0, 1, 1)))
                },
            )
        assertEquals(listOf(1L, 2L, 3L, 4L), read.order)
    }

    @Test
    fun `the footer counts only what is not a stroke`() {
        val recording = recordingOf(script = PenScripts.tap())
        val footer =
            VectorCodec.decodeFile(
                file {
                    addStroke(1L, recording, 0, IntRect(0, 0, 1, 1), null)
                    addLost(VectorLost(2L, LostReason.TOO_LONG, IntRect(0, 0, 1, 1)))
                    addLift(VectorLift(3L, deleted = true, SelectionTransform(IntRect(0, 0, 1, 1)), FloatArray(0), 0))
                },
                cel,
                null,
            )
        assertEquals(3L, footer.recordCount)
        assertEquals(2L, footer.gapCount)
        assertEquals(VectorCodec.GAP_TOO_LONG or VectorCodec.GAP_LIFT, footer.gapReasons)
    }

    @Test
    fun `a file with nothing but strokes has no gaps`() {
        val recording = recordingOf(script = PenScripts.tap())
        val footer = VectorCodec.decodeFile(file { addStroke(1L, recording, 0, IntRect(0, 0, 1, 1), null) }, cel, null)
        assertEquals(0L, footer.gapCount)
        assertEquals(0, footer.gapReasons)
    }

    // ---- Where things are ----

    @Test
    fun `a cel that has moved moves everything in its file`() {
        val recording = recordingOf(script = PenScripts.tap())
        val fill = VectorFill(2L, 30, 40, 0, 0, 0, 0, sampleAllLayers = false, behind = false, IntRect(1, 2, 3, 4))
        val lift = VectorLift(3L, deleted = false, SelectionTransform(IntRect(5, 6, 7, 8)), floatArrayOf(5f, 6f), 1)
        val bytes =
            file(IntRect(10, 20, 110, 220)) {
                addStroke(1L, recording, 0, IntRect(100, 110, 120, 130), IntRect(90, 91, 92, 93))
                addFill(fill)
                addLift(lift)
                addLost(VectorLost(4L, LostReason.UNRECORDED, IntRect(9, 10, 11, 12)))
            }
        val read = read(bytes, IntRect(13, 18, 113, 218))
        val stroke = read.strokes.single()
        assertEquals(3, stroke.offsetX)
        assertEquals(-2, stroke.offsetY)
        assertEquals(IntRect(103, 108, 123, 128), stroke.bounds)
        assertEquals(IntRect(93, 89, 95, 91), stroke.clip)
        assertSameBits(recording.copySamples(), stroke.samples)
        val backFill = read.gaps[0] as VectorFill
        assertEquals(33, backFill.x)
        assertEquals(38, backFill.y)
        assertEquals(IntRect(4, 0, 6, 2), backFill.rect)
        val backLift = read.gaps[1] as VectorLift
        assertEquals(IntRect(8, 4, 10, 6), backLift.transform.source)
        assertArrayEquals(floatArrayOf(8f, 4f), backLift.points, 0f)
        assertEquals(IntRect(12, 8, 14, 10), (read.gaps[2] as VectorLost).rect)
    }

    @Test
    fun `reading with no cel leaves things where they were written`() {
        val recording = recordingOf(script = PenScripts.tap())
        val stroke = read(file { addStroke(1L, recording, 0, IntRect(100, 110, 120, 130), null) }, null).strokes.single()
        assertEquals(0, stroke.offsetX)
        assertEquals(IntRect(100, 110, 120, 130), stroke.bounds)
    }

    @Test
    fun `an anchor moves a record without touching its payload`() {
        val recording = recordingOf(script = PenScripts.tap())
        val bytes = file { addStroke(1L, recording, 0, IntRect(100, 110, 120, 130), IntRect(0, 0, 5, 5)) }
        val head = 8
        // Kind, flags, id, and then two anchors of one byte each, with the length after them.
        assertEquals(0, bytes[head + 10].toInt())
        assertEquals(0, bytes[head + 11].toInt())
        val moved = bytes.copyOf()
        moved[head + 10] = 14
        moved[head + 11] = 6
        val stroke = read(resigned(moved), cel).strokes.single()
        assertEquals(7, stroke.offsetX)
        assertEquals(3, stroke.offsetY)
        assertEquals(IntRect(107, 113, 127, 133), stroke.bounds)
        assertEquals(IntRect(7, 3, 12, 8), stroke.clip)
        assertSameBits(recording.copySamples(), stroke.samples)
    }

    @Test
    fun `a cel of another size is not this file's cel`() {
        val recording = recordingOf(script = PenScripts.tap())
        val bytes = file(IntRect(0, 0, 100, 200)) { addStroke(1L, recording, 0, IntRect(0, 0, 1, 1), null) }
        assertRefused(VectorDamage.MISMATCH, bytes, IntRect(0, 0, 100, 201))
        assertRefused(VectorDamage.MISMATCH, bytes, IntRect(0, 0, 99, 200))
        read(bytes, IntRect(500, 500, 600, 700))
    }

    // ---- What it does with files it should not trust ----

    @Test
    fun `a kind from a later version is skipped by its length and counted as a gap`() {
        val recording = recordingOf(script = PenScripts.tap())
        val unknown = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        val bytes =
            file {
                addStroke(1L, recording, 0, IntRect(0, 0, 1, 1), null)
                addRaw(99, 0x7F, 2L, 11, 22, unknown, 0, unknown.size, VectorCodec.GAP_UNKNOWN)
                addStroke(3L, recording, 0, IntRect(0, 0, 1, 1), null)
            }
        val read = read(bytes)
        assertEquals(listOf(1L, 2L, 3L), read.order)
        val gap = read.gaps.single() as VectorUnknown
        assertEquals(99, gap.kind)
        val footer = VectorCodec.decodeFile(bytes, cel, null)
        assertEquals(1L, footer.gapCount)
        assertEquals(VectorCodec.GAP_UNKNOWN, footer.gapReasons)
    }

    @Test
    fun `copying records as bytes gives the same file`() {
        val recording = recordingOf(script = PenScripts.flick())
        val original = file { addStroke(1L, recording, 5, IntRect(0, 0, 1, 1), null) }
        // Kind, flags, id, two one-byte anchors, and a length of however many bytes, read here the way a composer would.
        var at = 8 + 10 + 2
        var length = 0
        var shift = 0
        while (true) {
            val byte = original[at++].toInt() and 0xFF
            length = length or ((byte and 0x7F) shl shift)
            shift += 7
            if (byte and 0x80 == 0) break
        }
        val copied =
            file {
                addRaw(original[8].toInt(), original[9].toInt(), 1L, 0, 0, original, at, length, 0)
            }
        assertArrayEquals(original, copied)
    }

    @Test
    fun `a newer version is left alone`() {
        val bytes = file { addLost(VectorLost(1L, LostReason.UNRECORDED, IntRect(0, 0, 1, 1))) }
        val newer = bytes.copyOf()
        newer[4] = 2
        assertRefused(VectorDamage.NEWER, newer)
        assertRefused(VectorDamage.NEWER, resigned(newer))
    }

    @Test
    fun `a newer version is known from its header alone`() {
        // A later format may lay out everything after the version differently.
        val newer = byteArrayOf('K'.code.toByte(), 'G'.code.toByte(), 'V'.code.toByte(), 'S'.code.toByte(), 9, 0, 0, 0)
        assertRefused(VectorDamage.NEWER, newer)
    }

    @Test
    fun `version zero never existed`() {
        val bytes = file { addLost(VectorLost(1L, LostReason.UNRECORDED, IntRect(0, 0, 1, 1))) }
        val older = bytes.copyOf()
        older[4] = 0
        assertRefused(VectorDamage.DAMAGED, resigned(older))
    }

    @Test
    fun `a file larger than the cap is not read`() {
        assertRefused(VectorDamage.TOO_LARGE, ByteArray((VectorCodec.MAX_FILE_BYTES + 1).toInt()))
    }

    @Test
    fun `every truncation is refused`() {
        val recording = recordingOf(BrushPresets.PENCIL, script = PenScripts.flick())
        val bytes =
            file {
                addStroke(1L, recording, 5, IntRect(0, 0, 1, 1), IntRect(0, 0, 5, 5))
                addLost(VectorLost(2L, LostReason.UNRECORDED, IntRect(0, 0, 1, 1)))
            }
        for (length in 0 until bytes.size) {
            assertThrows("cut to $length bytes", VectorFormatException::class.java) { read(bytes.copyOf(length)) }
        }
    }

    @Test
    fun `every flipped byte is refused`() {
        val recording = recordingOf(BrushPresets.PENCIL, script = PenScripts.flick())
        val bytes =
            file {
                addStroke(1L, recording, 5, IntRect(0, 0, 1, 1), IntRect(0, 0, 5, 5))
                addFill(VectorFill(2L, 0, 0, 0, 0, 0, 0, sampleAllLayers = false, behind = false, IntRect(0, 0, 1, 1)))
            }
        for (i in bytes.indices) {
            val flipped = bytes.copyOf()
            flipped[i] = (flipped[i].toInt() xor 0x20).toByte()
            val visited = Collected()
            assertThrows("byte $i", VectorFormatException::class.java) { VectorCodec.decodeFile(flipped, cel, visited) }
            assertTrue("the reader saw records from a damaged file at byte $i", visited.order.isEmpty())
        }
    }

    @Test
    fun `even with good checksums nothing but a refusal or a good read comes out`() {
        val recording = recordingOf(BrushPresets.PENCIL, script = PenScripts.flick())
        val original =
            file {
                addStroke(1L, recording, 5, IntRect(0, 0, 1, 1), IntRect(0, 0, 5, 5))
                addFill(VectorFill(2L, 0, 0, 0, 0, 0, 0, sampleAllLayers = false, behind = false, IntRect(0, 0, 1, 1)))
                addLift(VectorLift(3L, deleted = false, SelectionTransform(IntRect(0, 0, 9, 9)), floatArrayOf(1f, 1f, 2f, 2f), 2))
                addLost(VectorLost(4L, LostReason.UNRECORDED, IntRect(0, 0, 1, 1)))
            }
        val random = Random(11L)
        var refused = 0
        var read = 0
        repeat(20_000) {
            val bytes = original.copyOf()
            repeat(1 + random.nextInt(4)) { bytes[8 + random.nextInt(bytes.size - 8 - 48)] = random.nextInt(256).toByte() }
            try {
                read(resigned(bytes))
                read++
            } catch (_: VectorFormatException) {
                refused++
            }
        }
        assertEquals(20_000, read + refused)
        assertTrue("almost everything should be refused, but $read were read", refused > 100)
    }

    @Test
    fun `lying counts are refused before anything is made`() {
        val recording = recordingOf(script = PenScripts.tap())
        val bytes = file { addStroke(1L, recording, 0, IntRect(0, 0, 1, 1), null) }
        val footer = bytes.size - 48
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val tooLong = bytes.copyOf()
        ByteBuffer.wrap(tooLong).order(ByteOrder.LITTLE_ENDIAN).putInt(footer + 4, buffer.getInt(footer + 4) + 1)
        assertRefused(VectorDamage.DAMAGED, resigned(tooLong))

        val tooMany = bytes.copyOf()
        ByteBuffer.wrap(tooMany).order(ByteOrder.LITTLE_ENDIAN).putInt(footer + 8, 2_000_000_000)
        assertRefused(VectorDamage.DAMAGED, resigned(tooMany))

        val moreGaps = bytes.copyOf()
        ByteBuffer.wrap(moreGaps).order(ByteOrder.LITTLE_ENDIAN).putInt(footer + 12, 1)
        assertRefused(VectorDamage.DAMAGED, resigned(moreGaps))

        val otherReasons = bytes.copyOf()
        ByteBuffer.wrap(otherReasons).order(ByteOrder.LITTLE_ENDIAN).putInt(footer + 16, 1)
        assertRefused(VectorDamage.DAMAGED, resigned(otherReasons))
    }

    @Test
    fun `a record with no id is refused`() {
        val bytes = file { addLost(VectorLost(0L, LostReason.UNRECORDED, IntRect(0, 0, 1, 1))) }
        assertRefused(VectorDamage.DAMAGED, bytes)
    }

    @Test
    fun `a number written the long way round is refused`() {
        val input = VectorInput(byteArrayOf(0x80.toByte(), 0x00), 0, 2)
        assertThrows(VectorFormatException::class.java) { input.varint() }
        val tooLong = VectorInput(byteArrayOf(0x81.toByte(), 0x81.toByte(), 0x81.toByte(), 0x81.toByte(), 0x81.toByte(), 1), 0, 6)
        assertThrows(VectorFormatException::class.java) { tooLong.varint() }
        val past64 = VectorInput(ByteArray(10) { if (it == 9) 2 else 0x81.toByte() }, 0, 10)
        assertThrows(VectorFormatException::class.java) { past64.varint(10) }
        val fits = VectorBuffer(16)
        fits.varint(Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, VectorInput(fits.bytes, 0, fits.size).varint(10))
    }

    @Test
    fun `numbers come back through every length`() {
        val buffer = VectorBuffer(4)
        val values = longArrayOf(0, 1, -1, 63, -64, 64, -65, 8191, -8192, 8192, 1_000_000, -1_000_000, 1L shl 33, -(1L shl 33))
        for (value in values) buffer.zigzag(value)
        val input = VectorInput(buffer.bytes, 0, buffer.size)
        for (value in values) assertEquals(value, input.zigzag())
        assertEquals(0, input.remaining)
    }

    // ---- Bytes ----

    @Test
    fun `what a point and a stroke really cost`() {
        var plainBytes = 0L
        var plainPoints = 0L
        var tiltBytes = 0L
        var tiltPoints = 0L
        val samplesOnly = VectorBuffer(1024)
        for (script in PenScripts.all()) {
            for (spec in listOf(BrushPresets.PEN, BrushPresets.PENCIL)) {
                val recording = recordingOf(spec, script = script)
                assertNotEquals(null, encodeSamples(recording, samplesOnly))
                val perPoint = samplesOnly.size.toDouble() / recording.count
                println("BYTES ${script.name} ${spec.id}: ${recording.count} samples, %.2f bytes each".format(perPoint))
                if (recording.stride == StrokeRecording.STRIDE_WITH_TILT) {
                    tiltBytes += samplesOnly.size
                    tiltPoints += recording.count
                } else {
                    plainBytes += samplesOnly.size
                    plainPoints += recording.count
                }
                // Holding every float whole would cost 4 bytes each, and this should never cost much more than that.
                assertTrue("${script.name} ${spec.id} took $perPoint bytes a point", perPoint <= recording.stride * 4 + 6)
            }
        }
        val plain = plainBytes.toDouble() / plainPoints
        val tilt = tiltBytes.toDouble() / tiltPoints
        println("BYTES all: %.2f bytes a point, %.2f with tilt".format(plain, tilt))
        assertTrue("$plain bytes a point", plain < 12.0)
        assertTrue("$tilt bytes a point with tilt", tilt < 20.0)

        val tap = recordingOf(script = PenScripts.tap())
        val tapBytes = file { addStroke(1L, tap, 0, IntRect(220, 130, 245, 155), null) }
        encodeSamples(tap, samplesOnly)
        val overhead = tapBytes.size - VectorCodec.HEADER_BYTES - VectorCodec.FOOTER_BYTES - samplesOnly.size
        println("BYTES a stroke costs $overhead bytes before its samples")
        assertTrue("$overhead bytes a stroke", overhead in 100..170)
    }

    @Test
    fun `the largest stroke fits a record whatever its samples hold`() {
        val recording = StrokeRecording(BrushPresets.PENCIL, 1L, false, plenty)
        val random = Random(9L)
        repeat(StrokeRecording.MAX_SAMPLES) { i ->
            val sign = if (i % 2 == 0) 1f else -1f
            recording.add(
                sign * (random.nextFloat() * 8_000_000f),
                -sign * (random.nextFloat() * 8_000_000f),
                sign * Float.MAX_VALUE,
                -sign * Float.MAX_VALUE,
                sign * Float.MAX_VALUE,
                i.toLong(),
            )
        }
        val bytes = file { assertTrue(addStroke(1L, recording, 0, IntRect(0, 0, 1, 1), null)) }
        assertTrue("${bytes.size} bytes", bytes.size < VectorCodec.MAX_PAYLOAD_BYTES)
        assertSameBits(recording.copySamples(), read(bytes).strokes.single().samples)
    }
}
