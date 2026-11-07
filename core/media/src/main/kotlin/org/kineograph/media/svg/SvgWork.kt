package org.kineograph.media.svg

import kotlin.math.max

/**
 * What things cost, in units of very roughly ten nanoseconds on a desktop core. The numbers only need to be right to within a few
 * times: they keep a file from running away, and a normal drawing stays far below the allowance whatever its exact price.
 */
internal object WorkCost {
    /** One unit a byte reads and inflates a file. A nested image that holds a huge comment costs what it really costs. */
    const val BYTE_READ: Long = 1L
    const val ELEMENT_READ: Long = 100L
    const val ELEMENT_STYLED: Long = 200L
    const val STYLE_RULE_READ: Long = 256L
    const val SELECTOR_STEP: Long = 8L
    const val DECLARATION_APPLIED: Long = 48L
    const val VALUE_CHAR_READ: Long = 6L
    const val VAR_REPLACED: Long = 64L
    const val KEYFRAME_VALUE: Long = 64L
    const val ANIMATION_ENTRY: Long = 256L
    const val TIME_VALUE: Long = 4L

    /** A shape that is visited, styled and drawn without touching many pixels, as measured on a picture of a million tiny rectangles. */
    const val ELEMENT_DRAWN: Long = 500L
    const val BOX_MEASURED: Long = 64L
    const val TRACK_EVALUATED: Long = 64L

    /** A clip shape's point has two lengths, each with three sums and something new to hold it, and then the path goes through it again. */
    const val SHAPE_POINT_MIXED: Long = 40L

    /** Reading a shape's text, a length at a time, and keeping the result takes far longer than going through its characters. */
    const val SHAPE_CHAR_READ: Long = 4L
    const val SEGMENT: Long = 2L
    const val OUTLINE_SEGMENT: Long = 8L

    /** Filling takes time for every pixel of height that the edges of a shape cross. */
    const val EDGE_PIXEL: Long = 6L

    /**
     * Thousands of edges crossing on one row have to be sorted again on every row, which is what makes a scribble so much slower than a
     * map with the same number of points. The time goes up with the length of the edges squared, over the height of the area they share.
     */
    const val CROSSING_DENSITY: Double = 3e-4

    /** Painting a plain colour takes about 5 ms for a million pixels, and a layer two or three times that, with allocating and blending. */
    const val PIXELS_PER_UNIT_PLAIN: Long = 12L
    const val PIXELS_PER_UNIT_LAYER: Long = 4L
    const val SHADER_PIXELS_PER_UNIT: Long = 8L

    /** A mask goes through a colour filter, pixel by pixel, which takes a good many times what any other layer does. */
    const val MASK_UNITS_PER_PIXEL: Long = 4L

    /** A layer takes this long however small it is, and a mask takes all this again, with the objects it needs to go through. */
    const val LAYER: Long = 5000L
    const val MASK: Long = 15000L
    const val DASH: Long = 300L
    const val GRADIENT_STOP: Long = 150L

    /** On older phones, a gradient with a focal point gets worked out for each pixel of a small picture. */
    const val FOCAL_GRADIENT_BY_HAND: Long = 200_000L
    const val PATH_OP_SEGMENT: Long = 64L
    const val PLACED_GLYPH: Long = 3000L
    const val SHAPED_CHARACTER: Long = 150L
    const val LAID_OUT_CHARACTER: Long = 20L
    const val PLACED_RUN: Long = 400L

    /** Mostly the cost of decompressing, which depends on the size in the file and not on how much of it is kept. */
    const val SOURCE_PIXELS_PER_UNIT: Long = 2L

    /** Every pixel of the canvas buys this much work, since a big canvas really does take longer. It pays for hundreds of layers. */
    const val ALLOWANCE_PER_CANVAS_PIXEL: Long = 100L
}

/**
 * The one way this package keeps a file from taking over the phone. Whatever could run for long pays here first, and once the allowance
 * has gone, the code stops doing more of it and leaves what it has.
 *
 * It counts work rather than time so that a frame stays a function of the file and the moment alone: the same file stops in the same
 * place on any phone. The clock is only a backstop for work that was priced badly, which a real drawing would not get near.
 */
internal open class WorkMeter(limit: Long, maxSeconds: Double, private val shouldStop: (() -> Boolean)?) {
    private var remaining = limit
    private var untilPoll = POLL_EVERY
    private var pollsUntilHeapCheck = POLLS_PER_HEAP_CHECK
    private val deadline = System.nanoTime() + (maxSeconds * NANOS_PER_SECOND).toLong()

    var isExhausted: Boolean = false
        private set
    var isCancelled: Boolean = false
        private set

    val isStopped: Boolean get() = isExhausted || isCancelled

    /** Pays for work that is about to be done. False means stop now, and do not do it. */
    fun spend(units: Long): Boolean {
        remaining -= units
        untilPoll -= units
        if (remaining < 0L) isExhausted = true
        if (untilPoll <= 0L) poll()
        return !isStopped
    }

    /** For a single piece of native work that cannot be stopped once it has started. Asking does not pay for anything. */
    fun canAfford(units: Long): Boolean = !isStopped && units <= remaining

    /** Pays for what a call turned out to cost beyond what it was charged, for the one call whose price cannot be told beforehand. */
    fun payOverrun(nanos: Long, alreadyCharged: Long) {
        val units = nanos / NANOS_PER_UNIT
        if (units > alreadyCharged) spend(units - alreadyCharged)
    }

    fun exhaust() {
        isExhausted = true
    }

    private fun poll() {
        // One large payment is as much work as many small ones, and as likely to have filled the heap.
        pollsUntilHeapCheck -= (-untilPoll / POLL_EVERY).coerceAtMost(POLLS_PER_HEAP_CHECK.toLong()).toInt()
        untilPoll = POLL_EVERY
        // Being interrupted is how WorkManager and plain threads ask for a stop. Coroutines come through shouldStop.
        if (shouldStop != null && (shouldStop.invoke() || Thread.currentThread().isInterrupted)) isCancelled = true
        if (System.nanoTime() - deadline > 0L) isExhausted = true
        if (--pollsUntilHeapCheck <= 0) {
            pollsUntilHeapCheck = POLLS_PER_HEAP_CHECK
            if (HeapWatch.isTight()) isExhausted = true
        }
    }

    private companion object {
        // A stop should be seen within a fraction of a millisecond, and looking costs next to nothing.
        const val POLL_EVERY = 4096L
        const val POLLS_PER_HEAP_CHECK = 64
        const val NANOS_PER_UNIT = 10L
        const val NANOS_PER_SECOND = 1e9
    }
}

/**
 * Catching OutOfMemoryError does not protect the app: by then any other thread may be the one that gets it, and on the UI thread that
 * is a crash. So work stops while there is still room, for everyone else to carry on with.
 */
internal object HeapWatch {
    private const val TIGHT_SHARE = 0.85
    private const val BIG_TEXT_CHARS = 1 shl 20

    /** A quick guess at how fast a busy thread can fill the heap with things it keeps. A phone does not reach this. */
    private const val FILLING_BYTES_PER_SECOND = 256.0 * 1024 * 1024
    private const val MIN_SECONDS_BETWEEN_COLLECTIONS = 0.05
    private const val MAX_SECONDS_BETWEEN_COLLECTIONS = 5.0

    @Volatile
    private var nextCollection = System.nanoTime()

    /**
     * Used memory includes garbage, so this only says yes after a collection has been given the chance to clear that out. Collections are
     * not free, so the next one waits for as long as it would take to fill what the last one found free.
     */
    fun isTight(): Boolean {
        if (!isAboveLine()) return false
        if (System.nanoTime() - nextCollection < 0L) return false
        val runtime = Runtime.getRuntime()
        runtime.gc()
        val line = runtime.maxMemory() * TIGHT_SHARE
        val room = line - (runtime.totalMemory() - runtime.freeMemory())
        val wait = (room / FILLING_BYTES_PER_SECOND).coerceIn(MIN_SECONDS_BETWEEN_COLLECTIONS, MAX_SECONDS_BETWEEN_COLLECTIONS)
        nextCollection = System.nanoTime() + (wait * 1e9).toLong()
        return room < 0.0
    }

    private fun isAboveLine(): Boolean {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory() > (runtime.maxMemory() * TIGHT_SHARE).toLong()
    }

    /**
     * Whether a very long piece of text can be read into values that take [bytesPerChar] for each of its characters. One long list of
     * small numbers makes millions of small objects in a single loop, and those would fill the heap before anything could look.
     */
    fun hasRoomForText(chars: Int, bytesPerChar: Int): Boolean {
        if (chars < BIG_TEXT_CHARS) return true
        val needed = chars.toLong() * bytesPerChar
        if (needed <= free()) return true
        Runtime.getRuntime().gc()
        return needed <= free()
    }

    private fun free(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())) / 4 * 3
    }
}

/**
 * The allowance of one parse, which a picture nested inside this one spends from too. Giving each nested picture its own would let a
 * small file multiply its work at every level.
 */
internal class ParseWork(val limits: SvgLimits, shouldStop: (() -> Boolean)? = null) :
    WorkMeter(limits.maxParseWork, limits.maxParseSeconds, shouldStop) {
    var elementsKept: Int = 0

    /** Twice the largest file, which leaves room for every byte of a file to also be an embedded picture, as a real file might be. */
    var bytesLeft: Long = limits.maxInputBytes.coerceAtMost(Long.MAX_VALUE / 2) * 2
        private set

    /** What all the pictures of one file share, so that their limits hold in total and not for each of them. */
    val resources: SvgResources = SvgResources(limits)

    fun takeBytes(count: Int): Boolean {
        bytesLeft -= count
        spend(count * WorkCost.BYTE_READ)
        return bytesLeft >= 0L
    }
}

/**
 * The allowance of one frame, along with the things that have to add up across all of it, such as how deep the drawing has gone and how
 * much memory its layers hold. A nested picture draws on the same allowance as the picture around it.
 */
internal class FrameWork(val limits: SvgLimits, val canvasPixels: Long, shouldStop: () -> Boolean) :
    WorkMeter(limits.maxRenderWork + canvasPixels * WorkCost.ALLOWANCE_PER_CANVAS_PIXEL, limits.maxRenderSeconds, shouldStop) {
    private var nodesLeft = limits.maxRenderedNodes
    private var rasterBytes = 0L
    private var textBytesLeft = 0L

    // However small the phone, a picture may always have a few nested layers the size of its canvas.
    private val maxRasterBytes = max(limits.maxLiveRasterBytes, canvasPixels * BYTES_PER_PIXEL * ALWAYS_ALLOWED_LAYERS)

    /** How many levels down drawing is now. Set directly only to go back after a failure. */
    var depth: Int = 0

    /** Something was left out, or drawn more plainly than asked, even though drawing carried on. */
    var leftSomethingOut: Boolean = false
        private set

    val isComplete: Boolean get() = !isStopped && !leftSomethingOut

    fun noteLeftOut() {
        leftSomethingOut = true
    }

    /** Pays for visiting one element. This also holds the count of [SvgLimits.maxRenderedNodes]. */
    fun visit(cost: Long = WorkCost.ELEMENT_DRAWN): Boolean {
        if (--nodesLeft < 0) exhaust()
        return spend(cost)
    }

    /**
     * Drawing recurses, and references can take it far deeper than the nesting of the file. The stack has to keep enough room for the
     * native drawing code below it, since running out there cannot be caught and would take the app down. So this stops well before.
     */
    fun enter(): Boolean {
        if (depth >= limits.maxDepth) {
            noteLeftOut()
            return false
        }
        depth++
        return true
    }

    fun leave() {
        depth--
    }

    /** Holds native memory for pixels that stay alive while drawing goes on. These do not show on the heap, so nothing else would see. */
    fun reserveRaster(bytes: Long): Boolean {
        if (rasterBytes + bytes > maxRasterBytes) return false
        rasterBytes += bytes
        return true
    }

    fun releaseRaster(bytes: Long) {
        rasterBytes -= bytes
    }

    /** Each text element gets its own allowance of outlines, which go again as soon as it has been drawn. */
    fun beginText() {
        textBytesLeft = limits.maxTextOutlineBytes
    }

    fun reserveText(bytes: Long): Boolean {
        if (bytes > textBytesLeft) return false
        textBytesLeft -= bytes
        return true
    }

    val textBytesAvailable: Long get() = textBytesLeft

    companion object {
        const val BYTES_PER_PIXEL: Long = 4L
        private const val ALWAYS_ALLOWED_LAYERS = 8L
    }
}
