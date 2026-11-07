package org.kineograph.media.svg

/**
 * What a file contained that we did not reproduce. These are enum values rather than text so that the screen showing them
 * can pick a translated sentence; nothing in this package is ever shown to a person directly.
 */
public enum class SvgWarning {
    /** A script or an `on...` handler was skipped. If the file moves through JavaScript, it imports as its static picture. */
    SCRIPT_IGNORED,

    /** Something pointed outside the file (a web address or another file). The app is offline, so it is never fetched. */
    EXTERNAL_REFERENCE_IGNORED,

    /** A `foreignObject` holds HTML, which this renderer does not draw. */
    FOREIGN_OBJECT_IGNORED,

    /**
     * A filter effect was skipped, so that element draws without it, or not at all if all the filter made was a shadow of it. A colour matrix is
     * applied and says so too if its mid tones could come out slightly different.
     */
    FILTER_IGNORED,

    /** An animation waits for a click or another event. Nobody can click during an import, so it never starts. */
    EVENT_TRIGGER_IGNORED,

    /** A recognised SVG or CSS feature that this renderer does not implement, such as blend modes, or a turn for each letter of a text. */
    UNSUPPORTED_FEATURE_IGNORED,

    /** A value was malformed. The rest of the file still draws, following the SVG rule of using what came before the error. */
    INVALID_CONTENT_IGNORED,

    /**
     * The file asked for more than this device can do, so part of it is missing or plainer than it should be. This can also appear
     * after [SvgDocument.render], when a frame turned out to need more than it was allowed.
     */
    LIMIT_REACHED,
}

/**
 * The timing of a picture.
 *
 * Whatever a file says, these are finite and no more than about thirty years. A loop is also never shorter than a millisecond, so a
 * caller can count frames from them without any special cases, though it still has to decide how many frames it is willing to make.
 *
 * @property isAnimated true if the file has animation at all, even animation that changes nothing visible or never starts.
 * @property durationSeconds for a picture that plays once, when it ends. For a loop, the shortest time after which the whole
 *   picture repeats, so that importing that many seconds gives a seamless loop.
 * @property loopsForever true if something in the file repeats without end.
 * @property loopStartSeconds when the repeating part begins. Animations with a delay or an intro only start to repeat after it,
 *   so a seamless loop samples from here to here plus [durationSeconds]. Zero for most files.
 * @property hasIntro true if something plays once and ends before the picture settles into its loop, as a logo does that draws itself and
 *   then blinks. Whoever samples only the loop loses that, which may well be the point of the file, so it is worth asking.
 * @property repeatsExactly false if what [durationSeconds] says is only the longest cycle, because the parts of the picture would take too long
 *   to come back into step. Sampling that much then leaves a jump where the last frame runs into the first. Always true unless it loops.
 */
public data class SvgAnimationInfo(
    public val isAnimated: Boolean,
    public val durationSeconds: Double,
    public val loopsForever: Boolean,
    public val loopStartSeconds: Double,
    public val hasIntro: Boolean = false,
    public val repeatsExactly: Boolean = true,
) {
    public companion object {
        public val STATIC: SvgAnimationInfo = SvgAnimationInfo(false, 0.0, false, 0.0)
    }
}

/**
 * Everything that stops a file from asking more of the phone than it has. All of these together are the one mechanism: sizes, depths and
 * counts are kept here, every reference is checked for loops where it is followed, and whatever can take long pays from a work allowance
 * (see [maxParseWork] and [maxRenderWork]). A file past one of them loses what does not fit, with a warning, or is refused with a reason
 * when nothing can be shown. Normal drawings stay far below every one of them.
 *
 * They come from the device, not from a policy about how big a drawing may be: use [forDevice] unless a test needs otherwise.
 *
 * @property maxInputBytes largest file, and largest size after decompression, that is held in memory.
 * @property maxElements elements kept from the file, counting those of pictures nested inside it; the rest are dropped with
 *   [SvgWarning.LIMIT_REACHED].
 * @property maxDepth nesting kept from the file, and how deep drawing may go through references, since it recurses and a stack is small.
 * @property maxRenderedNodes elements drawn in one render, counting every `use` expansion, so references cannot multiply forever.
 * @property maxImagePixels decoded size of an embedded image, above which it is decoded at a lower resolution.
 * @property maxNestedImages how many levels of SVG inside an embedded SVG image are followed.
 * @property maxSeamlessLoopSeconds longest loop worth suggesting. Past it, the longest single cycle is suggested instead.
 * @property maxAttributesPerElement attributes on one element. The XML parser slows down sharply with more and cannot be interrupted, so
 *   a file past this is refused before the parser gets there.
 * @property maxSelectorParts parts of one CSS selector. A selector with more is ignored.
 * @property maxStylesheetNesting how deeply `@media` and `@supports` blocks may sit inside each other.
 * @property maxReferenceChain how many gradients or patterns one may inherit from through `href`.
 * @property maxGradientStops colours in one gradient. Past this some are left out, far beyond what could be seen.
 * @property maxDecodedImagePixels decoded images kept in memory at once, in all. Others are decoded again when they are next needed.
 * @property maxGlyphCacheBytes text outlines kept between frames. These are outside the heap, so no error would warn of them running out.
 * @property maxTextOutlineBytes text outlines one text element may hold while it is being drawn.
 * @property maxLiveRasterBytes layers and pattern tiles that may be alive at once in a frame, also outside the heap.
 * @property maxParseWork how much work reading one file may take, nested pictures included. See [WorkCost] for the unit, which is very
 *   roughly ten nanoseconds. Past it, stylesheets and animations stop being applied, and the file keeps what it has with a warning.
 * @property maxRenderWork how much work one frame may take. Past it, drawing stops and leaves what was drawn. The size of the canvas adds
 *   to this, since a bigger canvas takes longer to fill.
 * @property maxParseSeconds a backstop on the clock, for reading.
 * @property maxRenderSeconds a backstop on the clock, for one frame.
 */
public data class SvgLimits(
    public val maxInputBytes: Long,
    public val maxElements: Int,
    public val maxDepth: Int,
    public val maxRenderedNodes: Int,
    public val maxImagePixels: Long,
    public val maxNestedImages: Int,
    public val maxSeamlessLoopSeconds: Double,
    public val maxAttributesPerElement: Int,
    public val maxSelectorParts: Int,
    public val maxStylesheetNesting: Int,
    public val maxReferenceChain: Int,
    public val maxGradientStops: Int,
    public val maxDecodedImagePixels: Long,
    public val maxGlyphCacheBytes: Long,
    public val maxTextOutlineBytes: Long,
    public val maxLiveRasterBytes: Long,
    public val maxParseWork: Long,
    public val maxRenderWork: Long,
    public val maxParseSeconds: Double,
    public val maxRenderSeconds: Double,
) {
    public companion object {
        /** Sizes the limits from the heap this process may use, so that a big phone can open what a small phone cannot. */
        public fun forDevice(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): SvgLimits {
            val heap = maxHeapBytes.coerceAtLeast(MINIMUM_HEAP_BYTES)
            return SvgLimits(
                // Reading takes the bytes, then about as much again as text, then what the values parsed from it take.
                maxInputBytes = (heap / 4).coerceAtMost(MAX_ARRAY_BYTES),
                // An element with its parsed styles costs about two kilobytes.
                maxElements = (heap / 2048).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                maxDepth = 512,
                maxRenderedNodes = (heap / 64).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                // A 16 megapixel photo on a phone with a 512 MB heap. Bigger ones lose detail that would not show here anyway.
                maxImagePixels = heap / 32,
                maxNestedImages = 4,
                maxSeamlessLoopSeconds = 60.0,
                // No file has a reason to come near any of these five, and each stays quick to work with at that size.
                maxAttributesPerElement = 16_384,
                maxSelectorParts = 256,
                maxStylesheetNesting = 32,
                maxReferenceChain = 256,
                maxGradientStops = 4096,
                // At four bytes a pixel, these three and the one below are each a share of what a process of this size can expect to hold.
                maxDecodedImagePixels = heap / 16,
                maxGlyphCacheBytes = heap / 8,
                maxTextOutlineBytes = heap / 2,
                maxLiveRasterBytes = heap / 2,
                // About ten seconds on a desktop, and half a minute or so on a cheap phone. The largest real files take a small part of it.
                maxParseWork = 1_000_000_000L,
                maxRenderWork = 1_000_000_000L,
                maxParseSeconds = 60.0,
                maxRenderSeconds = 30.0,
            )
        }

        private const val MINIMUM_HEAP_BYTES = 16L * 1024 * 1024

        /** A file is held in one array, however much memory there is. */
        private const val MAX_ARRAY_BYTES = Int.MAX_VALUE - 8L
    }
}

/** The file could not be read as a picture at all. [reason] lets the screen showing it pick a translated sentence. */
public class SvgParseException(public val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    public enum class Reason {
        /** The input holds nothing. */
        EMPTY,

        /** The input could not be read from its source. */
        READ_FAILED,

        /** The input is larger than this device can hold in memory. */
        TOO_LARGE,

        /** The input has more in one place than can be read in reasonable time, which only a file made to cause trouble does. */
        TOO_COMPLEX,

        /** It looks compressed, but the compressed data is damaged. */
        CORRUPT_COMPRESSION,

        /** The XML is broken, or the text is not XML. */
        MALFORMED,

        /** It is valid XML, but its root element is not `svg`. */
        NOT_SVG,
    }
}
