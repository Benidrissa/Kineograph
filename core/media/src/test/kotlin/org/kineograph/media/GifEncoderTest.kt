package org.kineograph.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kineograph.media.gif.GifEncoder
import org.kineograph.media.gif.GifTiming
import java.io.ByteArrayOutputStream
import java.util.Random
import kotlin.math.abs

class GifEncoderTest {
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private fun encode(
        width: Int,
        height: Int,
        frames: List<IntArray>,
        delay: Int = 10,
        playCount: Int = 0,
        dither: Boolean = false,
        maxColors: Int = 256,
    ): TestGifDecoder {
        val out = ByteArrayOutputStream()
        val encoder = GifEncoder(out, width, height, playCount, maxColors, dither)
        frames.forEach { encoder.addFrame(it, delay) }
        encoder.finish()
        return TestGifDecoder(out.toByteArray())
    }

    private fun assertSameColors(expected: IntArray, actual: IntArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals("pixel $i", expected[i], actual[i])
    }

    @Test
    fun `a single flat frame decodes exactly`() {
        val decoded = encode(4, 3, listOf(IntArray(12) { red }))
        assertEquals(4, decoded.width)
        assertEquals(3, decoded.height)
        assertEquals(1, decoded.frames.size)
        assertSameColors(IntArray(12) { red }, decoded.frames[0].pixels)
    }

    @Test
    fun `images with few colours keep them exactly`() {
        val random = Random(1)
        val palette = intArrayOf(red, green, blue, white)
        val pixels = IntArray(40 * 30) { palette[random.nextInt(4)] }
        assertSameColors(pixels, encode(40, 30, listOf(pixels)).frames[0].pixels)
    }

    @Test
    fun `compression round trips across dictionary resets`() {
        // Random data with a big image forces the dictionary to fill up and reset several times.
        val random = Random(2)
        val colors = IntArray(200) { 0xFF000000.toInt() or (it * 1234567 and 0xFFFFFF) }
        val pixels = IntArray(400 * 300) { colors[random.nextInt(colors.size)] }
        assertSameColors(pixels, encode(400, 300, listOf(pixels)).frames[0].pixels)
    }

    @Test
    fun `every image size decodes correctly including the very small`() {
        for ((w, h) in listOf(1 to 1, 2 to 1, 1 to 2, 3 to 3, 255 to 2, 257 to 3)) {
            val pixels = IntArray(w * h) { if (it % 2 == 0) red else blue }
            assertSameColors(pixels, encode(w, h, listOf(pixels)).frames[0].pixels)
        }
    }

    @Test
    fun `a photo-like image with too many colours stays close`() {
        val width = 128
        val height = 128
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            0xFF000000.toInt() or (x * 2 shl 16) or (y * 2 shl 8) or ((x + y) and 0xFF)
        }
        val decoded = encode(width, height, listOf(pixels)).frames[0].pixels
        var totalError = 0L
        for (i in pixels.indices) {
            totalError += abs(((pixels[i] shr 16) and 0xFF) - ((decoded[i] shr 16) and 0xFF))
            totalError += abs(((pixels[i] shr 8) and 0xFF) - ((decoded[i] shr 8) and 0xFF))
            totalError += abs((pixels[i] and 0xFF) - (decoded[i] and 0xFF))
        }
        val averagePerChannel = totalError.toDouble() / (pixels.size * 3)
        assertTrue("average error $averagePerChannel", averagePerChannel < 8.0)
    }

    @Test
    fun `dithering gives a gradient more distinct mixes than plain mapping`() {
        val width = 256
        val height = 16
        val pixels = IntArray(width * height) { i ->
            val v = i % width
            0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        fun transitions(colors: IntArray): Int {
            var count = 0
            for (i in 1 until width) if (colors[i] != colors[i - 1]) count++
            return count
        }
        val plain = encode(width, height, listOf(pixels), dither = false, maxColors = 4).frames[0].pixels
        val dithered = encode(width, height, listOf(pixels), dither = true, maxColors = 4).frames[0].pixels
        assertTrue(transitions(dithered) > transitions(plain))
    }

    @Test
    fun `the palette never exceeds the requested colour count`() {
        val random = Random(3)
        val pixels = IntArray(64 * 64) { 0xFF000000.toInt() or random.nextInt(0xFFFFFF) }
        val decoded = encode(64, 64, listOf(pixels), maxColors = 16).frames[0].pixels
        assertTrue(decoded.toSet().size <= 16)
    }

    @Test
    fun `frames show in order with their delays`() {
        val decoded = encode(2, 2, listOf(IntArray(4) { red }, IntArray(4) { green }, IntArray(4) { blue }), delay = 7)
        assertEquals(3, decoded.frames.size)
        assertEquals(listOf(red, green, blue), decoded.frames.map { it.pixels[0] })
        assertEquals(listOf(7, 7, 7), decoded.frames.map { it.delay })
    }

    @Test
    fun `later frames only store what changed but still compose correctly`() {
        val width = 100
        val height = 100
        val first = IntArray(width * height) { white }
        val second = first.copyOf()
        for (y in 40 until 45) for (x in 60 until 70) second[y * width + x] = red
        val decoded = encode(width, height, listOf(first, second))
        val frame = decoded.frames[1]
        assertEquals(60, frame.x)
        assertEquals(40, frame.y)
        assertEquals(10, frame.width)
        assertEquals(5, frame.height)
        assertSameColors(second, frame.pixels)
    }

    @Test
    fun `an identical frame extends the previous frame's delay rather than adding a frame`() {
        val frame = IntArray(4) { red }
        val decoded = encode(2, 2, listOf(frame, frame.copyOf(), frame.copyOf()), delay = 5)
        assertEquals(1, decoded.frames.size)
        assertEquals(15, decoded.frames[0].delay)
    }

    @Test
    fun `reusing the same pixel array between frames works`() {
        val out = ByteArrayOutputStream()
        val encoder = GifEncoder(out, 2, 1, 0, 256, false)
        val buffer = intArrayOf(red, red)
        encoder.addFrame(buffer, 5)
        buffer[0] = blue
        encoder.addFrame(buffer, 5)
        encoder.finish()
        val decoded = TestGifDecoder(out.toByteArray())
        assertEquals(2, decoded.frames.size)
        assertEquals(red, decoded.frames[0].pixels[0])
        assertEquals(blue, decoded.frames[1].pixels[0])
        assertEquals(red, decoded.frames[1].pixels[1])
    }

    @Test
    fun `a pixel that does not change keeps its colour exactly through palette changes`() {
        val width = 64
        val height = 64
        val random = Random(4)
        val first = IntArray(width * height) { 0xFF000000.toInt() or random.nextInt(0xFFFFFF) }
        val second = first.copyOf()
        for (i in 0 until 500) second[random.nextInt(second.size)] = 0xFF000000.toInt() or random.nextInt(0xFFFFFF)
        val decoded = encode(width, height, listOf(first, second), maxColors = 32)
        val a = decoded.frames[0].pixels
        val b = decoded.frames[1].pixels
        for (i in first.indices) if (first[i] == second[i]) assertEquals("pixel $i", a[i], b[i])
    }

    @Test
    fun `transparency is kept`() {
        val pixels = intArrayOf(0, red, 0, blue)
        val decoded = encode(2, 2, listOf(pixels))
        assertEquals(0, decoded.frames[0].pixels[0])
        assertEquals(red, decoded.frames[0].pixels[1])
    }

    @Test
    fun `a pixel that becomes transparent is really cleared`() {
        val first = intArrayOf(red, red, blue, blue)
        val second = intArrayOf(0, red, blue, blue)
        val decoded = encode(2, 2, listOf(first, second))
        assertEquals(2, decoded.frames.size)
        assertEquals("must not keep showing the old colour", 0, decoded.frames[1].pixels[0])
    }

    @Test
    fun `clearing works even for pixels last drawn several frames back, outside the previous frame's rectangle`() {
        val width = 20
        val height = 20
        val first = IntArray(width * height) { red }
        // The second frame changes only one far corner, so its rectangle does not cover the top left.
        val second = first.copyOf().also { it[19 * width + 19] = blue }
        val third = second.copyOf().also { it[0] = 0 }
        val decoded = encode(width, height, listOf(first, second, third))
        assertEquals(3, decoded.frames.size)
        assertSameColors(first, decoded.frames[0].pixels)
        assertSameColors(second, decoded.frames[1].pixels)
        assertSameColors(third, decoded.frames[2].pixels)
    }

    @Test
    fun `an object moving on a transparent background leaves no trail`() {
        val width = 40
        val height = 10
        fun frame(position: Int) =
            IntArray(width * height).also { pixels ->
                for (y in 3 until 7) for (x in position until position + 4) pixels[y * width + x] = red
            }
        val frames = List(8) { frame(it * 4) }
        val decoded = encode(width, height, frames)
        assertEquals(8, decoded.frames.size)
        frames.forEachIndexed { i, expected -> assertSameColors(expected, decoded.frames[i].pixels) }
    }

    @Test
    fun `a frame can go completely clear and then come back`() {
        val solid = IntArray(9) { red }
        val clear = IntArray(9)
        val decoded = encode(3, 3, listOf(solid, clear, solid.copyOf()))
        assertSameColors(solid, decoded.frames[0].pixels)
        assertSameColors(clear, decoded.frames[1].pixels)
        assertSameColors(solid, decoded.frames[2].pixels)
    }

    @Test
    fun `a very long hold is carried by repeating the frame, not by blanking it`() {
        val frame = IntArray(4) { red }
        val out = ByteArrayOutputStream()
        val encoder = GifEncoder(out, 2, 2)
        repeat(3) { encoder.addFrame(frame, 40000) }
        encoder.finish()
        val decoded = TestGifDecoder(out.toByteArray())
        assertEquals(120000, decoded.frames.sumOf { it.delay })
        decoded.frames.forEach { assertSameColors(frame, it.pixels) }
    }

    @Test
    fun `a looping GIF says so and a single play has no loop block`() {
        assertEquals(0, encode(1, 1, listOf(intArrayOf(red)), playCount = 0).loopCount)
        assertEquals(2, encode(1, 1, listOf(intArrayOf(red)), playCount = 3).loopCount)
        assertNull(encode(1, 1, listOf(intArrayOf(red)), playCount = 1).loopCount)
    }

    @Test
    fun `there is no watermark or anything other than the supplied pixels`() {
        val pixels = IntArray(50 * 50) { white }
        val decoded = encode(50, 50, listOf(pixels))
        assertTrue(decoded.frames[0].pixels.all { it == white })
    }

    @Test
    fun `finish with no frames still makes a readable file`() {
        val out = ByteArrayOutputStream()
        GifEncoder(out, 4, 4).finish()
        assertEquals(0, TestGifDecoder(out.toByteArray()).frames.size)
    }

    @Test
    fun `a simple animation is much smaller than its raw frames`() {
        val width = 320
        val height = 240
        val frames =
            List(12) { n ->
                IntArray(width * height) { white }.also { pixels ->
                    for (y in 100 until 130) for (x in (n * 20) until (n * 20 + 30)) pixels[y * width + x] = red
                }
            }
        val out = ByteArrayOutputStream()
        val encoder = GifEncoder(out, width, height)
        frames.forEach { encoder.addFrame(it, 8) }
        encoder.finish()
        assertTrue("size ${out.size()}", out.size() < 12 * width * height / 100)
    }

    // ----- Timing -----

    @Test
    fun `twelve fps stays exactly on time over a second`() {
        val timing = GifTiming(12)
        val delays = List(12) { timing.nextDelay() }
        assertEquals(100, delays.sum())
        assertTrue(delays.all { it in 8..9 })
    }

    @Test
    fun `timing does not drift over long animations`() {
        val timing = GifTiming(24)
        var total = 0L
        repeat(24 * 600) { total += timing.nextDelay() }
        assertEquals(60_000L, total)
    }

    @Test
    fun `sixty fps merges ticks because viewers cannot show less than two hundredths`() {
        val timing = GifTiming(60)
        val delays = List(60) { timing.nextDelay() }
        assertTrue(delays.all { it == 0 || it >= 2 })
        assertTrue(delays.count { it > 0 } < 60)
        assertEquals(100, delays.sum())
    }

    @Test
    fun `slow frame rates give long delays`() {
        assertEquals(100, GifTiming(1).nextDelay())
    }
}
