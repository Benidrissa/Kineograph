package org.kineograph.media

import java.io.ByteArrayInputStream

/**
 * A small, separate GIF decoder, so tests judge the encoder by what a decoder actually reads rather than by
 * what the encoder intended. It composes frames the way a viewer does.
 */
class TestGifDecoder(data: ByteArray) {
    class Frame(val pixels: IntArray, val delay: Int, val x: Int, val y: Int, val width: Int, val height: Int)

    var width = 0
        private set
    var height = 0
        private set

    /** Null when no loop block is present, which means play once. 0 means forever. */
    var loopCount: Int? = null
        private set
    val frames = ArrayList<Frame>()

    private val input = ByteArrayInputStream(data)

    init {
        val signature = String(ByteArray(6) { input.read().toByte() })
        check(signature == "GIF89a") { "bad signature $signature" }
        width = short()
        height = short()
        val packed = input.read()
        input.read()
        input.read()
        check(packed and 0x80 == 0) { "expected no global colour table" }
        val canvas = IntArray(width * height)
        var delay = 0
        var transparent = -1
        var disposal = 0
        var finished = false
        while (!finished) {
            when (val marker = input.read()) {
                0x21 -> {
                    when (val label = input.read()) {
                        0xF9 -> {
                            check(input.read() == 4)
                            val flags = input.read()
                            delay = short()
                            val index = input.read()
                            transparent = if (flags and 1 != 0) index else -1
                            disposal = (flags shr 2) and 7
                            check(input.read() == 0)
                        }

                        0xFF -> {
                            val size = input.read()
                            val name = String(ByteArray(size) { input.read().toByte() })
                            check(name == "NETSCAPE2.0") { "unexpected application block $name" }
                            check(input.read() == 3)
                            check(input.read() == 1)
                            loopCount = short()
                            check(input.read() == 0)
                        }

                        else -> {
                            error("unknown extension $label")
                        }
                    }
                }

                0x2C -> {
                    val frame = readImage(canvas, delay, transparent)
                    frames += frame
                    // A viewer wipes this frame's rectangle before drawing the next one when the frame asks it to.
                    if (disposal == 2) {
                        for (row in 0 until frame.height) {
                            java.util.Arrays.fill(
                                canvas,
                                (frame.y + row) * width + frame.x,
                                (frame.y + row) * width + frame.x + frame.width,
                                0,
                            )
                        }
                    }
                }

                0x3B -> {
                    finished = true
                }

                else -> {
                    error("unexpected marker $marker")
                }
            }
        }
    }

    private fun readImage(canvas: IntArray, delay: Int, transparent: Int): Frame {
        val x = short()
        val y = short()
        val w = short()
        val h = short()
        val packed = input.read()
        check(packed and 0x80 != 0) { "expected a local colour table" }
        val tableSize = 1 shl ((packed and 7) + 1)
        val table = IntArray(tableSize) { (0xFF shl 24) or (input.read() shl 16) or (input.read() shl 8) or input.read() }
        val minCodeSize = input.read()
        val compressed = java.io.ByteArrayOutputStream()
        while (true) {
            val size = input.read()
            if (size == 0) break
            check(size > 0) { "ran out of data" }
            repeat(size) { compressed.write(input.read()) }
        }
        val indexes = decodeLzw(compressed.toByteArray(), minCodeSize, w * h)
        for (row in 0 until h) {
            for (column in 0 until w) {
                val index = indexes[row * w + column].toInt() and 0xFF
                if (index != transparent) canvas[(y + row) * width + x + column] = table[index]
            }
        }
        return Frame(canvas.copyOf(), delay, x, y, w, h)
    }

    private fun decodeLzw(data: ByteArray, minCodeSize: Int, expected: Int): ByteArray {
        val out = ByteArray(expected)
        var written = 0
        val clear = 1 shl minCodeSize
        val end = clear + 1
        val prefix = IntArray(4096)
        val suffix = ByteArray(4096)
        val stack = ByteArray(4097)
        var codeSize = minCodeSize + 1
        var next = end + 1
        var previous = -1
        var firstByte = 0
        var bits = 0
        var bitCount = 0
        var position = 0
        for (i in 0 until clear) suffix[i] = i.toByte()
        while (true) {
            while (bitCount < codeSize) {
                check(position < data.size) { "LZW data ended without an end code" }
                bits = bits or ((data[position++].toInt() and 0xFF) shl bitCount)
                bitCount += 8
            }
            val code = bits and ((1 shl codeSize) - 1)
            bits = bits ushr codeSize
            bitCount -= codeSize
            if (code == end) break
            if (code == clear) {
                codeSize = minCodeSize + 1
                next = end + 1
                previous = -1
                continue
            }
            var sp = 0
            var current = code
            if (previous == -1) {
                check(code < clear) { "first code after clear must be a literal, got $code" }
                firstByte = code
                check(written < expected) { "too many pixels" }
                out[written++] = code.toByte()
                previous = code
                continue
            }
            check(code <= next) { "code $code is beyond the table ($next)" }
            if (code == next) {
                stack[sp++] = firstByte.toByte()
                current = previous
            }
            while (current >= clear) {
                stack[sp++] = suffix[current]
                current = prefix[current]
            }
            firstByte = suffix[current].toInt() and 0xFF
            stack[sp++] = firstByte.toByte()
            while (sp > 0) {
                check(written < expected) { "too many pixels" }
                out[written++] = stack[--sp]
            }
            if (next < 4096) {
                prefix[next] = previous
                suffix[next] = firstByte.toByte()
                next++
                if (next == (1 shl codeSize) && codeSize < 12) codeSize++
            }
            previous = code
        }
        check(written == expected) { "decoded $written pixels, expected $expected" }
        return out
    }

    private fun short(): Int = input.read() or (input.read() shl 8)
}
