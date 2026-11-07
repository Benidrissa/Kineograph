package org.kineograph.engine.fill

import org.kineograph.model.IntRect
import java.lang.Long.numberOfLeadingZeros
import java.lang.Long.numberOfTrailingZeros
import kotlin.math.abs

/** The pixels a fill should change, stored as one bit per pixel. */
public class FillMask internal constructor(
    internal val grid: BitGrid,
    public val width: Int,
    public val height: Int,
    public val bounds: IntRect,
) {
    public fun isSet(x: Int, y: Int): Boolean = grid.get(x, y)

    public companion object {
        /** Builds a mask from a predicate. Slow, for tests and simple callers. */
        public fun of(width: Int, height: Int, isSet: (x: Int, y: Int) -> Boolean): FillMask {
            val grid = BitGrid(width, height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (isSet(x, y)) grid.set(x, y)
                }
            }
            return FillMask(grid, width, height, grid.bounds())
        }
    }
}

/** A rectangle of bits, packed into longs. Bit `k` of a word is the pixel `k` places to the right of the word's first pixel. */
internal class BitGrid(val width: Int, val height: Int) {
    val wordsPerRow: Int = (width + 63) ushr 6
    val words = LongArray(wordsPerRow * height)

    /** Bits past the right edge of the image must stay clear, or shifts would carry them back in. */
    val lastWordMask: Long = if (width and 63 == 0) -1L else (1L shl (width and 63)) - 1

    fun get(x: Int, y: Int): Boolean = (words[y * wordsPerRow + (x ushr 6)] ushr (x and 63)) and 1L != 0L

    fun set(x: Int, y: Int) {
        val index = y * wordsPerRow + (x ushr 6)
        words[index] = words[index] or (1L shl (x and 63))
    }

    fun clear(x: Int, y: Int) {
        val index = y * wordsPerRow + (x ushr 6)
        words[index] = words[index] and (1L shl (x and 63)).inv()
    }

    /** Sets every bit from [from] to [to], both inclusive, on row [rowStart] (a word offset). */
    fun setRange(rowStart: Int, from: Int, to: Int) {
        val firstWord = from ushr 6
        val lastWord = to ushr 6
        val firstMask = -1L shl (from and 63)
        val lastMask = -1L ushr (63 - (to and 63))
        if (firstWord == lastWord) {
            words[rowStart + firstWord] = words[rowStart + firstWord] or (firstMask and lastMask)
            return
        }
        words[rowStart + firstWord] = words[rowStart + firstWord] or firstMask
        for (w in firstWord + 1 until lastWord) words[rowStart + w] = -1L
        words[rowStart + lastWord] = words[rowStart + lastWord] or lastMask
    }

    /** The smallest rectangle containing every set bit. */
    fun bounds(): IntRect {
        var minX = Int.MAX_VALUE
        var maxX = -1
        var minY = -1
        var maxY = -1
        for (y in 0 until height) {
            val row = y * wordsPerRow
            for (w in 0 until wordsPerRow) {
                val word = words[row + w]
                if (word == 0L) continue
                if (minY < 0) minY = y
                maxY = y
                val first = (w shl 6) + numberOfTrailingZeros(word)
                val last = (w shl 6) + 63 - numberOfLeadingZeros(word)
                if (first < minX) minX = first
                if (last > maxX) maxX = last
            }
        }
        return if (minY < 0) IntRect.EMPTY else IntRect(minX, minY, maxX + 1, maxY + 1)
    }
}

/**
 * Flood fill on a raw pixel array. It takes pixels rather than a Bitmap so that it can run off the main
 * thread and be tested without a device. Pixels are unpremultiplied ARGB, as Bitmap.getPixels returns.
 *
 * Two extras go beyond plain flood fill:
 *
 *  - **Gap closing.** Hand-drawn outlines rarely close perfectly. With `gapClose = r`, the walls are first
 *    thickened by r pixels, so the fill cannot pass through an opening narrower than 2r + 1 pixels. Once
 *    the region is known it grows back by r pixels, up to the real lines.
 *  - **Expansion.** After the region is found it grows `expand` more pixels. Painted behind the line art,
 *    this hides the pale fringe that otherwise appears along anti-aliased lines.
 *
 * This has to finish a full HD image quickly on a slow phone, so everything works on bitmaps of 64 pixels
 * per word: finding the end of a span is a single instruction, and thickening a wall is a shift.
 */
public object FloodFill {
    /** Finds the region connected to ([seedX], [seedY]), or null when the seed is outside the image. */
    public fun computeMask(
        pixels: IntArray,
        width: Int,
        height: Int,
        seedX: Int,
        seedY: Int,
        tolerance: Int,
        gapClose: Int = 0,
        expand: Int = 0,
    ): FillMask? {
        if (seedX !in 0 until width || seedY !in 0 until height) return null
        require(pixels.size >= width * height) { "Pixel array too small" }
        val open = buildOpenMap(pixels, width, height, pixels[seedY * width + seedX], tolerance.coerceIn(0, 255))
        val mask = BitGrid(width, height)
        val radius = gapClose.coerceIn(0, MAX_GAP)
        if (radius == 0) {
            spanFill(open, mask, seedX, seedY)
        } else {
            fillWithGapClosing(open, mask, seedX, seedY, radius)
        }
        if (expand > 0) grow(mask, null, expand)
        return FillMask(mask, width, height, mask.bounds())
    }

    /**
     * Paints [mask] onto [target]. With [behind], the fill goes under whatever is already there, so line
     * art keeps its soft edges. Without it, the fill goes over it: an opaque colour replaces what is there, and a translucent one
     * tints it, where replacing would make a see-through hole in the paint.
     */
    public fun apply(target: IntArray, width: Int, mask: FillMask, colorArgb: Int, behind: Boolean) {
        val grid = mask.grid
        val bounds = mask.bounds
        if (bounds.isEmpty) return
        val colorAlpha = colorArgb ushr 24
        for (y in bounds.top until bounds.bottom) {
            val row = y * grid.wordsPerRow
            val pixelRow = y * width
            for (w in 0 until grid.wordsPerRow) {
                var word = grid.words[row + w]
                while (word != 0L) {
                    val index = pixelRow + (w shl 6) + numberOfTrailingZeros(word)
                    word = word and (word - 1)
                    val dst = target[index]
                    val dstAlpha = dst ushr 24
                    target[index] =
                        when {
                            !behind -> if (colorAlpha == 255) colorArgb else compose(colorArgb, colorAlpha, dst, dstAlpha)
                            dstAlpha == 0 -> colorArgb
                            dstAlpha == 255 -> dst
                            else -> compose(dst, dstAlpha, colorArgb, colorAlpha)
                        }
                }
            }
        }
    }

    /** The result of [top] drawn over [under], in unpremultiplied ARGB. */
    private fun compose(top: Int, topAlpha: Int, under: Int, underFullAlpha: Int): Int {
        val underAlpha = underFullAlpha * (255 - topAlpha) / 255
        val outAlpha = topAlpha + underAlpha
        if (outAlpha == 0) return 0
        val r = (((top shr 16) and 0xFF) * topAlpha + ((under shr 16) and 0xFF) * underAlpha) / outAlpha
        val g = (((top shr 8) and 0xFF) * topAlpha + ((under shr 8) and 0xFF) * underAlpha) / outAlpha
        val b = ((top and 0xFF) * topAlpha + (under and 0xFF) * underAlpha) / outAlpha
        return (outAlpha shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Marks which pixels count as the seed's colour. Colours are compared premultiplied, so all fully
     * transparent pixels are equal whatever hidden RGB they carry, and a faint pixel is close to an empty one.
     */
    private fun buildOpenMap(pixels: IntArray, width: Int, height: Int, seed: Int, tolerance: Int): BitGrid {
        val open = BitGrid(width, height)
        val words = open.words
        val seedA = seed ushr 24
        val seedR = premultiply((seed shr 16) and 0xFF, seedA)
        val seedG = premultiply((seed shr 8) and 0xFF, seedA)
        val seedB = premultiply(seed and 0xFF, seedA)
        // Artwork has long runs of one colour, so most pixels repeat the answer just worked out.
        var previous = seed
        var previousOpen = 1L
        var pixel = 0
        var wordIndex = 0
        for (y in 0 until height) {
            var x = 0
            while (x < width) {
                val stop = minOf(x + 64, width)
                var word = 0L
                var bit = 0
                while (x < stop) {
                    val color = pixels[pixel++]
                    if (color != previous) {
                        previous = color
                        previousOpen = if (colorsMatch(color, seedA, seedR, seedG, seedB, tolerance)) 1L else 0L
                    }
                    word = word or (previousOpen shl bit)
                    bit++
                    x++
                }
                words[wordIndex++] = word
            }
        }
        return open
    }

    private fun colorsMatch(color: Int, seedA: Int, seedR: Int, seedG: Int, seedB: Int, tolerance: Int): Boolean {
        val a = color ushr 24
        if (abs(a - seedA) > tolerance) return false
        return abs(premultiply((color shr 16) and 0xFF, a) - seedR) <= tolerance &&
            abs(premultiply((color shr 8) and 0xFF, a) - seedG) <= tolerance &&
            abs(premultiply(color and 0xFF, a) - seedB) <= tolerance
    }

    /** channel * alpha / 255, rounded, using a multiply and shift: integer division is slow on low-end chips. */
    private fun premultiply(channel: Int, alpha: Int): Int = ((channel * alpha + 128) * 257) ushr 16

    private fun fillWithGapClosing(open: BitGrid, mask: BitGrid, seedX: Int, seedY: Int, radius: Int) {
        val clear = erodeOpenArea(open, radius)
        val start = findClearSeed(open, clear, seedX, seedY, radius)
        if (start == null) {
            // The seed is in a space too narrow to survive the thickening, so nothing can leak out of it anyway.
            spanFill(open, mask, seedX, seedY)
            return
        }
        spanFill(clear, mask, start.first, start.second)
        grow(mask, open, radius)
        fillCornerPockets(open, clear, mask, radius)
    }

    /**
     * Growing back by the radius cannot reach the tip of a sharp corner, because the thickened walls close it off well before
     * the point. What it leaves is a small pocket of open pixels touching the region, so those are filled too.
     *
     * Two limits keep this from leaking through the gap the thickening was there to close. A pocket holding a clear
     * pixel is open space beyond a gap, and a large one is a thin space beyond a gap, or too long a channel to be a corner.
     * Pockets are followed with 4-connectivity, because diagonal steps would cross a one-pixel diagonal line.
     */
    private fun fillCornerPockets(open: BitGrid, clear: BitGrid, mask: BitGrid, radius: Int) {
        val bounds = mask.bounds()
        if (bounds.isEmpty) return
        val perRow = mask.wordsPerRow
        val width = mask.width
        val height = mask.height
        val words = mask.words
        // Rejected pockets stay marked, so a large one is only ever walked once, however long its border with the region is.
        val rejected = BitGrid(width, height)
        val seen = BitGrid(width, height)
        val pocket = IntArray(POCKET_AREA_PER_RADIUS_SQUARED * radius * radius)
        for (y in maxOf(0, bounds.top - 1)..minOf(height - 1, bounds.bottom)) {
            val row = y * perRow
            for (w in 0 until perRow) {
                val here = words[row + w]
                val fromLeft = if (w > 0) words[row + w - 1] ushr 63 else 0L
                val fromRight = if (w < perRow - 1) words[row + w + 1] shl 63 else 0L
                val fromAbove = if (y > 0) words[row - perRow + w] else 0L
                val fromBelow = if (y < height - 1) words[row + perRow + w] else 0L
                val touching = (here shl 1) or (here ushr 1) or fromLeft or fromRight or fromAbove or fromBelow
                var candidates = open.words[row + w] and here.inv() and rejected.words[row + w].inv() and touching
                while (candidates != 0L) {
                    val x = (w shl 6) + numberOfTrailingZeros(candidates)
                    candidates = candidates and (candidates - 1)
                    if (mask.get(x, y) || rejected.get(x, y)) continue
                    val size = walkPocket(open, clear, mask, rejected, seen, x, y, pocket)
                    for (i in 0 until size) mask.set(pocket[i] % width, pocket[i] / width)
                }
            }
        }
    }

    /**
     * Collects the pocket around a pixel into [pocket], as `y * width + x`, and returns how many pixels it has. Returns
     * 0 as soon as it finds the pocket must not be filled, and marks everything it visited as [rejected].
     */
    private fun walkPocket(
        open: BitGrid,
        clear: BitGrid,
        mask: BitGrid,
        rejected: BitGrid,
        seen: BitGrid,
        startX: Int,
        startY: Int,
        pocket: IntArray,
    ): Int {
        val width = open.width
        val height = open.height
        pocket[0] = startY * width + startX
        seen.set(startX, startY)
        var size = 1
        var next = 0
        var refused = clear.get(startX, startY)
        while (next < size && !refused) {
            val index = pocket[next++]
            val x = index % width
            val y = index / width
            for (direction in POCKET_STEPS_X.indices) {
                val nx = x + POCKET_STEPS_X[direction]
                val ny = y + POCKET_STEPS_Y[direction]
                if (nx !in 0 until width || ny !in 0 until height) continue
                if (!open.get(nx, ny) || mask.get(nx, ny) || seen.get(nx, ny)) continue
                if (rejected.get(nx, ny) || clear.get(nx, ny) || size == pocket.size) {
                    refused = true
                    break
                }
                seen.set(nx, ny)
                pocket[size++] = ny * width + nx
            }
        }
        for (i in 0 until size) {
            val x = pocket[i] % width
            val y = pocket[i] / width
            seen.clear(x, y)
            if (refused) rejected.set(x, y)
        }
        return if (refused) 0 else size
    }

    /** Returns the open pixels that are more than [radius] steps from any wall, counting diagonals as one step. */
    private fun erodeOpenArea(open: BitGrid, radius: Int): BitGrid {
        val perRow = open.wordsPerRow
        val height = open.height
        // Start from the walls: everything inside the image that is not open.
        val thick = BitGrid(open.width, height)
        val src = open.words
        val dst = thick.words
        for (i in dst.indices) dst[i] = src[i].inv()
        clearPastEdge(thick)

        // Thicken along rows, doubling the reach each pass.
        var remaining = radius
        var step = 1
        while (remaining > 0) {
            val shift = minOf(step, remaining)
            for (y in 0 until height) shiftOrRight(dst, y * perRow, perRow, shift)
            for (y in 0 until height) shiftOrLeft(dst, y * perRow, perRow, shift)
            remaining -= shift
            step *= 2
        }
        clearPastEdge(thick)

        // Thicken across rows, then invert to get what remains clear.
        val clear = BitGrid(open.width, height)
        val out = clear.words
        for (y in 0 until height) {
            val row = y * perRow
            val from = maxOf(0, y - radius)
            val to = minOf(height - 1, y + radius)
            for (source in from..to) {
                val sourceRow = source * perRow
                for (w in 0 until perRow) out[row + w] = out[row + w] or dst[sourceRow + w]
            }
            for (w in 0 until perRow) out[row + w] = out[row + w].inv()
            out[row + perRow - 1] = out[row + perRow - 1] and clear.lastWordMask
        }
        return clear
    }

    private fun clearPastEdge(grid: BitGrid) {
        for (y in 0 until grid.height) {
            val last = y * grid.wordsPerRow + grid.wordsPerRow - 1
            grid.words[last] = grid.words[last] and grid.lastWordMask
        }
    }

    /** ORs a row with itself moved [shift] pixels to the right (towards higher x). [shift] is in 1..63. */
    private fun shiftOrRight(words: LongArray, row: Int, perRow: Int, shift: Int) {
        for (w in perRow - 1 downTo 1) {
            val i = row + w
            words[i] = words[i] or (words[i] shl shift) or (words[i - 1] ushr (64 - shift))
        }
        words[row] = words[row] or (words[row] shl shift)
    }

    /** ORs a row with itself moved [shift] pixels to the left (towards lower x). [shift] is in 1..63. */
    private fun shiftOrLeft(words: LongArray, row: Int, perRow: Int, shift: Int) {
        for (w in 0 until perRow - 1) {
            val i = row + w
            words[i] = words[i] or (words[i] ushr shift) or (words[i + 1] shl (64 - shift))
        }
        val last = row + perRow - 1
        words[last] = words[last] or (words[last] ushr shift)
    }

    /**
     * The seed itself if it is clear of walls, otherwise the nearest clear pixel reachable from it without
     * crossing a line, or null. Searching only along open pixels matters: a straight-line search would hop
     * over a thin wall and fill the wrong region.
     */
    private fun findClearSeed(open: BitGrid, clear: BitGrid, seedX: Int, seedY: Int, radius: Int): Pair<Int, Int>? {
        if (clear.get(seedX, seedY)) return seedX to seedY
        if (!open.get(seedX, seedY)) return null
        val width = open.width
        val height = open.height
        val reach = radius * 2 + 1
        val side = reach * 2 + 1
        val seen = BooleanArray(side * side)
        var queue = IntArray(side * 4)
        var head = 0
        var tail = 0
        seen[reach * side + reach] = true
        queue[tail++] = seedX
        queue[tail++] = seedY
        while (head < tail) {
            val x = queue[head++]
            val y = queue[head++]
            for (dy in -1..1) {
                val ny = y + dy
                if (ny !in 0 until height || abs(ny - seedY) > reach) continue
                for (dx in -1..1) {
                    val nx = x + dx
                    if (nx !in 0 until width || abs(nx - seedX) > reach) continue
                    val window = (ny - seedY + reach) * side + (nx - seedX + reach)
                    if (seen[window]) continue
                    seen[window] = true
                    if (!open.get(nx, ny)) continue
                    if (clear.get(nx, ny)) return nx to ny
                    if (tail + 2 > queue.size) queue = queue.copyOf(queue.size * 2)
                    queue[tail++] = nx
                    queue[tail++] = ny
                }
            }
        }
        return null
    }

    /** The first pixel from [from] rightward that cannot be filled, or the image width. */
    private fun firstBlockedFrom(walkable: LongArray, mask: LongArray, row: Int, perRow: Int, from: Int, width: Int): Int {
        var w = from ushr 6
        var blocked = (walkable[row + w] and mask[row + w].inv()).inv() and (-1L shl (from and 63))
        while (blocked == 0L) {
            w++
            if (w >= perRow) return width
            blocked = (walkable[row + w] and mask[row + w].inv()).inv()
        }
        return minOf((w shl 6) + numberOfTrailingZeros(blocked), width)
    }

    /** The first pixel from [from] rightward that can be filled, or a value past [limit] if none is. */
    private fun firstFillableFrom(walkable: LongArray, mask: LongArray, row: Int, perRow: Int, from: Int, limit: Int): Int {
        if (from > limit) return from
        var w = from ushr 6
        var fillable = (walkable[row + w] and mask[row + w].inv()) and (-1L shl (from and 63))
        val lastWord = minOf(limit ushr 6, perRow - 1)
        while (fillable == 0L) {
            w++
            if (w > lastWord) return limit + 1
            fillable = walkable[row + w] and mask[row + w].inv()
        }
        return (w shl 6) + numberOfTrailingZeros(fillable)
    }

    /** The last pixel from [from] leftward that cannot be filled, or -1. */
    private fun lastBlockedUpTo(walkable: LongArray, mask: LongArray, row: Int, from: Int): Int {
        var w = from ushr 6
        var blocked = (walkable[row + w] and mask[row + w].inv()).inv() and (-1L ushr (63 - (from and 63)))
        while (blocked == 0L) {
            w--
            if (w < 0) return -1
            blocked = (walkable[row + w] and mask[row + w].inv()).inv()
        }
        return (w shl 6) + 63 - numberOfLeadingZeros(blocked)
    }

    /**
     * Heckbert's seed fill. Each stack entry says: this span was just filled, so look at the row next to it
     * in the given direction. Only overhangs need a look back the other way, so little is ever read twice.
     */
    private fun spanFill(walkable: BitGrid, mask: BitGrid, seedX: Int, seedY: Int) {
        if (!walkable.get(seedX, seedY)) return
        val width = walkable.width
        val height = walkable.height
        val perRow = walkable.wordsPerRow
        val open = walkable.words
        val filled = mask.words
        val stack = SpanStack(height)
        stack.push(seedY, seedX, seedX, 1)
        stack.push(seedY + 1, seedX, seedX, -1)

        while (stack.isNotEmpty) {
            val direction = stack.pop()
            val spanRight = stack.pop()
            val spanLeft = stack.pop()
            val y = stack.pop() + direction
            val row = y * perRow

            var x = firstFillableFrom(open, filled, row, perRow, spanLeft, spanRight)
            if (x > spanRight) continue
            var left = x
            if (x == spanLeft) {
                // The run may extend left past the parent span, which needs a look back the other way.
                left = lastBlockedUpTo(open, filled, row, spanLeft) + 1
                if (left < spanLeft) stack.push(y, left, spanLeft - 1, -direction)
            }
            while (x <= spanRight) {
                val end = firstBlockedFrom(open, filled, row, perRow, x, width) - 1
                mask.setRange(row, left, end)
                stack.push(y, left, end, direction)
                if (end > spanRight) stack.push(y, spanRight + 1, end, -direction)
                x = firstFillableFrom(open, filled, row, perRow, end + 2, spanRight)
                left = x
            }
        }
    }

    private class SpanStack(private val height: Int) {
        private var items = IntArray(4096)
        private var top = 0

        val isNotEmpty: Boolean get() = top > 0

        fun pop(): Int = items[--top]

        fun push(y: Int, left: Int, right: Int, direction: Int) {
            val next = y + direction
            if (next < 0 || next >= height) return
            if (top + 4 > items.size) items = items.copyOf(items.size * 2)
            items[top++] = y
            items[top++] = left
            items[top++] = right
            items[top++] = direction
        }
    }

    /**
     * Grows the region by [steps] pixels, including diagonally, into pixels allowed by [limit], or into
     * anything when [limit] is null.
     */
    private fun grow(mask: BitGrid, limit: BitGrid?, steps: Int) {
        val perRow = mask.wordsPerRow
        val height = mask.height
        val words = mask.words
        val allowed = limit?.words
        // Each row smeared one pixel sideways. Keeping just the rows around the current one is enough.
        var above = LongArray(perRow)
        var current = LongArray(perRow)
        var below = LongArray(perRow)
        repeat(steps) {
            val bounds = mask.bounds()
            if (bounds.isEmpty) return
            val firstRow = maxOf(0, bounds.top - 1)
            val lastRow = minOf(height - 1, bounds.bottom)
            if (firstRow > 0) smear(words, firstRow - 1, perRow, above) else above.fill(0L)
            smear(words, firstRow, perRow, current)
            for (y in firstRow..lastRow) {
                if (y < height - 1) smear(words, y + 1, perRow, below) else below.fill(0L)
                val row = y * perRow
                for (w in 0 until perRow) {
                    var grown = above[w] or current[w] or below[w]
                    if (allowed != null) grown = grown and allowed[row + w]
                    words[row + w] = words[row + w] or grown
                }
                // The smears for the next row come from before this row was changed, so the reach is exactly one step.
                val recycled = above
                above = current
                current = below
                below = recycled
            }
        }
        clearPastEdge(mask)
    }

    /** Writes row [y] of [words] into [out], extended by one pixel in each direction. */
    private fun smear(words: LongArray, y: Int, perRow: Int, out: LongArray) {
        val row = y * perRow
        var carry = 0L
        for (w in 0 until perRow) {
            val word = words[row + w]
            val next = if (w + 1 < perRow) words[row + w + 1] else 0L
            out[w] = word or (word shl 1) or carry or (word ushr 1) or (next shl 63)
            carry = word ushr 63
        }
    }

    private const val MAX_GAP = 100

    /** The largest corner pocket is this many times the gap radius squared. A 25 degree tip fits, and so does anything wider. */
    private const val POCKET_AREA_PER_RADIUS_SQUARED = 8
    private val POCKET_STEPS_X = intArrayOf(-1, 1, 0, 0)
    private val POCKET_STEPS_Y = intArrayOf(0, 0, -1, 1)
}
