package org.kineograph.media.gif

import java.io.OutputStream

/**
 * Writes an animated GIF, one frame at a time, so that only one frame needs to be in memory.
 *
 * To keep files small, each frame stores only the rectangle that changed since the previous frame, and pixels
 * inside it that did not change are written as transparent, which LZW compresses to almost nothing. Comparing
 * source pixels rather than quantized ones means a colour that stays still never flickers, even though every
 * frame has its own palette.
 *
 * Pixels in the input are ARGB. A pixel with alpha below 128 counts as transparent.
 */
public class GifEncoder(
    private val output: OutputStream,
    private val width: Int,
    private val height: Int,
    /** 0 repeats forever; otherwise how many times to play in total. */
    private val playCount: Int = 0,
    private val maxColors: Int = 256,
    private val dither: Boolean = true,
) {
    private val quantizer = MedianCutQuantizer(maxColors.coerceIn(2, MAX_PALETTE) - 1)
    private var previous: IntArray? = null
    private var wroteHeader = false
    private val lzw = LzwEncoder()

    /** Becomes true once any pixel has gone from solid to transparent. See [addFrame]. */
    private var clearingEachFrame = false

    /** The newest frame waits here, because its delay can still grow, and its disposal can still change, when the next frame arrives. */
    private var pendingFrame: EncodedFrame? = null
    private var pendingDelay = 0

    private var scanFoundCleared = false

    /**
     * Adds a frame shown for [delayCentiseconds] hundredths of a second. [pixels] may be reused after this returns.
     *
     * A frame can only paint over what is already showing, so a pixel cannot go from solid to transparent by itself.
     * The format's answer is that a frame can ask to be wiped once it has been shown. That has to be said on the frame
     * *before*, and it only wipes that frame's rectangle. So the first time it is needed, the waiting frame is redone to
     * cover the whole canvas, and from then on each frame is drawn from clean and wiped afterwards.
     */
    public fun addFrame(pixels: IntArray, delayCentiseconds: Int) {
        require(pixels.size >= width * height) { "Frame too small" }
        val delay = delayCentiseconds.coerceIn(0, MAX_DELAY)
        if (!wroteHeader) {
            writeHeader()
            wroteHeader = true
        }
        val last = previous
        val changed = if (last == null) fullFrame() else changedBox(pixels, last)
        if (changed == null) {
            // Nothing changed: stretch the previous frame instead of storing another.
            growPendingDelay(delay)
            return
        }
        if (last != null && !clearingEachFrame && scanFoundCleared) {
            clearingEachFrame = true
            pendingFrame = encodeFrame(last, null, fullFrame(), DISPOSE_TO_BACKGROUND)
        }
        flushPending()
        pendingFrame =
            if (clearingEachFrame) {
                encodeFrame(pixels, null, contentBox(pixels), DISPOSE_TO_BACKGROUND)
            } else {
                encodeFrame(pixels, last, changed, DISPOSE_LEAVE)
            }
        pendingDelay = delay
        previous = (last ?: IntArray(width * height)).also { pixels.copyInto(it, 0, 0, width * height) }
    }

    /** Writes the end of the file. The stream stays open. */
    public fun finish() {
        if (!wroteHeader) {
            writeHeader()
            wroteHeader = true
        }
        flushPending()
        output.write(TRAILER)
        output.flush()
    }

    private fun growPendingDelay(delay: Int) {
        val combined = pendingDelay + delay
        if (combined <= MAX_DELAY) {
            pendingDelay = combined
            return
        }
        // The delay field has run out of room: show the same frame again for the remainder.
        val frame = pendingFrame ?: return
        writeFrame(frame, pendingDelay)
        pendingDelay = delay
    }

    private fun flushPending() {
        val frame = pendingFrame ?: return
        writeFrame(frame, pendingDelay)
        pendingFrame = null
    }

    private fun fullFrame() = Box(0, 0, width, height)

    /** The smallest rectangle containing every changed pixel, or null when nothing changed. Also notes whether any pixel was cleared. */
    private fun changedBox(current: IntArray, last: IntArray): Box? {
        var minX = width
        var maxX = -1
        var minY = height
        var maxY = -1
        var cleared = false
        var index = 0
        for (y in 0 until height) {
            var rowChanged = false
            for (x in 0 until width) {
                val now = current[index]
                val before = last[index]
                if (now != before) {
                    val nowClear = now ushr 24 < ALPHA_THRESHOLD
                    val beforeClear = before ushr 24 < ALPHA_THRESHOLD
                    // Two different invisible pixels look the same.
                    if (!nowClear || !beforeClear) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        rowChanged = true
                        if (nowClear) cleared = true
                    }
                }
                index++
            }
            if (rowChanged) {
                if (y < minY) minY = y
                maxY = y
            }
        }
        scanFoundCleared = cleared
        return if (maxX < 0) null else Box(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    /** The rectangle around the frame's visible pixels. A completely clear frame still needs one pixel to exist. */
    private fun contentBox(pixels: IntArray): Box {
        var minX = width
        var maxX = -1
        var minY = height
        var maxY = -1
        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (pixels[index++] ushr 24 >= ALPHA_THRESHOLD) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    maxY = y
                }
            }
        }
        return if (maxX < 0) Box(0, 0, 1, 1) else Box(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    private fun encodeFrame(pixels: IntArray, last: IntArray?, box: Box, disposal: Int): EncodedFrame {
        quantizer.reset()
        for (y in box.y until box.y + box.height) {
            var index = y * width + box.x
            for (x in 0 until box.width) {
                val color = pixels[index]
                // A pixel kept from the previous frame is drawn as transparent, so it needs no colour here.
                if (color ushr 24 >= ALPHA_THRESHOLD && (last == null || last[index] != color)) quantizer.add(color)
                index++
            }
        }
        val palette = quantizer.buildPalette()
        val transparentIndex = palette.size
        val indexBits = maxOf(2, bitsFor(palette.size + 1))
        val indexed = ByteArray(box.width * box.height)
        mapToPalette(pixels, last, box, palette, transparentIndex, indexed)
        return EncodedFrame(box, palette, transparentIndex, indexBits, lzw.encode(indexed, indexBits), disposal)
    }

    /** Assigns each pixel a palette index, optionally spreading the rounding error to its neighbours. */
    private fun mapToPalette(pixels: IntArray, last: IntArray?, box: Box, palette: IntArray, transparent: Int, out: ByteArray) {
        val lookup = NearestColor(palette)
        val diffuse = dither && palette.size > 2
        // Errors for this row and the next, in units of 1/16 of a level, with a guard cell at each end.
        var errorThis = if (diffuse) IntArray((box.width + 2) * 3) else EMPTY
        var errorNext = if (diffuse) IntArray((box.width + 2) * 3) else EMPTY
        var written = 0
        for (y in box.y until box.y + box.height) {
            var index = y * width + box.x
            for (x in 0 until box.width) {
                val color = pixels[index]
                val keep = last != null && last[index] == color
                if (color ushr 24 < ALPHA_THRESHOLD || keep) {
                    out[written++] = transparent.toByte()
                    index++
                    continue
                }
                var r = (color shr 16) and 0xFF
                var g = (color shr 8) and 0xFF
                var b = color and 0xFF
                if (diffuse) {
                    val cell = (x + 1) * 3
                    r = (r + (errorThis[cell] shr 4)).coerceIn(0, 255)
                    g = (g + (errorThis[cell + 1] shr 4)).coerceIn(0, 255)
                    b = (b + (errorThis[cell + 2] shr 4)).coerceIn(0, 255)
                }
                val chosen = lookup.nearest(r, g, b)
                out[written++] = chosen.toByte()
                if (diffuse) {
                    val p = palette[chosen]
                    val cell = (x + 1) * 3
                    spread(errorThis, errorNext, cell, r - ((p shr 16) and 0xFF))
                    spread(errorThis, errorNext, cell + 1, g - ((p shr 8) and 0xFF))
                    spread(errorThis, errorNext, cell + 2, b - (p and 0xFF))
                }
                index++
            }
            if (diffuse) {
                val swap = errorThis
                errorThis = errorNext
                errorNext = swap
                errorNext.fill(0)
            }
        }
    }

    /** Floyd–Steinberg's weights: 7/16 right, 3/16 below left, 5/16 below, 1/16 below right. */
    private fun spread(current: IntArray, next: IntArray, cell: Int, error: Int) {
        current[cell + 3] += error * 7
        next[cell - 3] += error * 3
        next[cell] += error * 5
        next[cell + 3] += error
    }

    private fun writeHeader() {
        writeAscii("GIF89a")
        writeShort(width)
        writeShort(height)
        // No global colour table: every frame carries its own.
        output.write(0)
        output.write(0)
        output.write(0)
        // The extension that says how many times to loop. "1" here would mean one extra play, so playing
        // once means leaving the block out altogether.
        if (playCount != 1) {
            output.write(EXTENSION)
            output.write(APPLICATION_LABEL)
            output.write(11)
            writeAscii("NETSCAPE2.0")
            output.write(3)
            output.write(1)
            writeShort(if (playCount <= 0) 0 else playCount - 1)
            output.write(0)
        }
    }

    private fun writeFrame(frame: EncodedFrame, delay: Int) {
        output.write(EXTENSION)
        output.write(GRAPHIC_CONTROL_LABEL)
        output.write(4)
        output.write((frame.disposal shl 2) or 1)
        writeShort(delay)
        output.write(frame.transparentIndex)
        output.write(0)

        output.write(IMAGE_SEPARATOR)
        writeShort(frame.box.x)
        writeShort(frame.box.y)
        writeShort(frame.box.width)
        writeShort(frame.box.height)
        val tableBits = frame.indexBits
        output.write(0x80 or (tableBits - 1))
        val tableSize = 1 shl tableBits
        for (i in 0 until tableSize) {
            val color = if (i < frame.palette.size) frame.palette[i] else 0
            output.write((color shr 16) and 0xFF)
            output.write((color shr 8) and 0xFF)
            output.write(color and 0xFF)
        }
        output.write(tableBits)
        output.write(frame.compressed)
    }

    private fun writeShort(value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
    }

    private fun writeAscii(text: String) {
        text.forEach { output.write(it.code) }
    }

    private fun bitsFor(count: Int): Int {
        var bits = 1
        while ((1 shl bits) < count) bits++
        return bits
    }

    private class Box(val x: Int, val y: Int, val width: Int, val height: Int)

    private class EncodedFrame(
        val box: Box,
        val palette: IntArray,
        val transparentIndex: Int,
        val indexBits: Int,
        val compressed: ByteArray,
        val disposal: Int,
    )

    private companion object {
        const val MAX_PALETTE = 256
        const val MAX_DELAY = 65535
        const val ALPHA_THRESHOLD = 128
        const val EXTENSION = 0x21
        const val APPLICATION_LABEL = 0xFF
        const val GRAPHIC_CONTROL_LABEL = 0xF9
        const val IMAGE_SEPARATOR = 0x2C
        const val TRAILER = 0x3B
        const val DISPOSE_LEAVE = 1
        const val DISPOSE_TO_BACKGROUND = 2
        val EMPTY = IntArray(0)
    }
}

/** Finds the closest palette colour, remembering answers per 15-bit colour so repeated colours cost one array read. */
internal class NearestColor(private val palette: IntArray) {
    private val cache = ShortArray(1 shl 15) { -1 }

    fun nearest(r: Int, g: Int, b: Int): Int {
        val key = ((r shl 7) and 0x7C00) or ((g shl 2) and 0x03E0) or (b shr 3)
        val cached = cache[key].toInt()
        if (cached >= 0) return cached
        var best = 0
        var bestDistance = Int.MAX_VALUE
        for (i in palette.indices) {
            val color = palette[i]
            val dr = r - ((color shr 16) and 0xFF)
            val dg = g - ((color shr 8) and 0xFF)
            val db = b - (color and 0xFF)
            val distance = dr * dr * 3 + dg * dg * 4 + db * db * 2
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        cache[key] = best.toShort()
        return best
    }
}
