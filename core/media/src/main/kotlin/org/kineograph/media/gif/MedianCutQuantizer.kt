package org.kineograph.media.gif

/**
 * Picks a palette for an image by median cut: start with one box holding every colour, then keep splitting the
 * box with the widest spread at its median until there are enough boxes. Each box's average colour joins
 * the palette.
 *
 * It works on a histogram of 15-bit colours, so the cost depends on how many distinct colours there are
 * rather than on the size of the image. Line art with few colours gets those colours exactly.
 */
internal class MedianCutQuantizer(private val maxColors: Int) {
    private val histogram = IntArray(HISTOGRAM_SIZE)
    private val exact = IntArray(maxColors + 1)

    /** Colours seen so far in this image. Beyond [maxColors] it stops tracking exact colours. */
    private var exactCount = 0
    private var exactOverflowed = false

    fun reset() {
        histogram.fill(0)
        exactCount = 0
        exactOverflowed = false
    }

    /** Counts one opaque pixel. */
    fun add(rgb: Int) {
        histogram[toBucket(rgb)]++
        if (!exactOverflowed) {
            val color = rgb and 0xFFFFFF
            for (i in 0 until exactCount) if (exact[i] == color) return
            if (exactCount == maxColors) {
                exactOverflowed = true
            } else {
                exact[exactCount++] = color
            }
        }
    }

    /** Returns the palette as packed 0xRRGGBB colours. */
    fun buildPalette(): IntArray {
        // With few enough colours there is no need to approximate at all.
        if (!exactOverflowed) return exact.copyOf(exactCount)

        val colors = histogram.indices.filter { histogram[it] > 0 }.toIntArray()
        if (colors.isEmpty()) return IntArray(0)
        val boxes = ArrayList<Box>(maxColors)
        boxes += Box(colors, 0, colors.size, histogram)
        while (boxes.size < maxColors) {
            var widest = -1
            var widestScore = 0L
            for (i in boxes.indices) {
                val box = boxes[i]
                if (box.size < 2) continue
                val score = box.spread().toLong() * box.population
                if (score > widestScore) {
                    widestScore = score
                    widest = i
                }
            }
            if (widest < 0) break
            boxes += boxes[widest].split(histogram)
        }
        return IntArray(boxes.size) { boxes[it].averageColor(histogram) }
    }

    /** A run of histogram buckets in [items], from [start] up to but not including [end]. */
    private class Box(private val items: IntArray, private val start: Int, private var end: Int, histogram: IntArray) {
        var population: Long = 0
            private set
        private var minR = 0
        private var maxR = 0
        private var minG = 0
        private var maxG = 0
        private var minB = 0
        private var maxB = 0

        val size: Int get() = end - start

        init {
            measure(histogram)
        }

        private fun measure(histogram: IntArray) {
            population = 0
            minR = 31
            maxR = 0
            minG = 31
            maxG = 0
            minB = 31
            maxB = 0
            for (i in start until end) {
                val bucket = items[i]
                population += histogram[bucket]
                val r = bucket shr 10
                val g = (bucket shr 5) and 31
                val b = bucket and 31
                if (r < minR) minR = r
                if (r > maxR) maxR = r
                if (g < minG) minG = g
                if (g > maxG) maxG = g
                if (b < minB) minB = b
                if (b > maxB) maxB = b
            }
        }

        fun spread(): Int = maxOf(maxR - minR, maxG - minG, maxB - minB)

        /** Splits at the population median along the widest axis. This box keeps the lower half and the upper half is returned. */
        fun split(histogram: IntArray): Box {
            val rangeR = maxR - minR
            val rangeG = maxG - minG
            val rangeB = maxB - minB
            val shift =
                when {
                    rangeR >= rangeG && rangeR >= rangeB -> 10
                    rangeG >= rangeB -> 5
                    else -> 0
                }
            sortByChannel(shift)
            var running = 0L
            val half = population / 2
            var cut = end - 1
            for (i in start until end - 1) {
                running += histogram[items[i]]
                if (running >= half) {
                    cut = i + 1
                    break
                }
            }
            val upper = Box(items, cut, end, histogram)
            end = cut
            measure(histogram)
            return upper
        }

        fun averageColor(histogram: IntArray): Int {
            var totalR = 0L
            var totalG = 0L
            var totalB = 0L
            var total = 0L
            for (i in start until end) {
                val bucket = items[i]
                val count = histogram[bucket].toLong()
                total += count
                totalR += ((bucket shr 10) * 255L / 31L) * count
                totalG += (((bucket shr 5) and 31) * 255L / 31L) * count
                totalB += ((bucket and 31) * 255L / 31L) * count
            }
            if (total == 0L) return 0
            return ((totalR / total).toInt() shl 16) or ((totalG / total).toInt() shl 8) or (totalB / total).toInt()
        }

        private fun sortByChannel(shift: Int) {
            val keyed =
                LongArray(end - start) { i ->
                    val bucket = items[start + i]
                    (((bucket shr shift) and 31).toLong() shl 32) or bucket.toLong()
                }
            keyed.sort()
            for (i in keyed.indices) items[start + i] = keyed[i].toInt()
        }
    }

    companion object {
        private const val HISTOGRAM_SIZE = 1 shl 15

        fun toBucket(rgb: Int): Int = ((rgb shr 9) and 0x7C00) or ((rgb shr 6) and 0x03E0) or ((rgb shr 3) and 0x001F)
    }
}
