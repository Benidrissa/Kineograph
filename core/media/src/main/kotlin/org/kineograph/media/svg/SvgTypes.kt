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

    /** A filter effect was skipped, so that element draws without it. */
    FILTER_IGNORED,

    /** An animation waits for a click or another event. Nobody can click during an import, so it never starts. */
    EVENT_TRIGGER_IGNORED,

    /** A recognised SVG or CSS feature that this renderer does not implement, such as blend modes, or a turn for each letter of a text. */
    UNSUPPORTED_FEATURE_IGNORED,

    /** A value was malformed. The rest of the file still draws, following the SVG rule of using what came before the error. */
    INVALID_CONTENT_IGNORED,

    /** The file was bigger or more deeply nested than this device can handle, so part of it is missing. */
    LIMIT_REACHED,
}

/**
 * The timing of a picture.
 *
 * @property isAnimated true if the file has animation at all, even animation that changes nothing visible or never starts.
 * @property durationSeconds for a picture that plays once, when it ends. For a loop, the shortest time after which the whole
 *   picture repeats, so that importing that many seconds gives a seamless loop.
 * @property loopsForever true if something in the file repeats without end.
 * @property loopStartSeconds when the repeating part begins. Animations with a delay or an intro only start to repeat after it,
 *   so a seamless loop samples from here to here plus [durationSeconds]. Zero for most files.
 */
public data class SvgAnimationInfo(
    public val isAnimated: Boolean,
    public val durationSeconds: Double,
    public val loopsForever: Boolean,
    public val loopStartSeconds: Double,
) {
    public companion object {
        public val STATIC: SvgAnimationInfo = SvgAnimationInfo(false, 0.0, false, 0.0)
    }
}

/**
 * The limits protect against files built to exhaust the device, such as compression bombs and references that multiply. They
 * come from the device itself, not from a policy about how big a drawing may be: use [forDevice] unless a test needs otherwise.
 *
 * @property maxInputBytes largest file, and largest size after decompression, that is held in memory.
 * @property maxElements elements kept from the file; the rest are dropped with [SvgWarning.LIMIT_REACHED].
 * @property maxDepth nesting kept from the file, since drawing recurses and a thread's stack is small.
 * @property maxRenderedNodes elements drawn in one render, counting every `use` expansion, so references cannot multiply forever.
 * @property maxImagePixels decoded size of an embedded image, above which it is decoded at a lower resolution.
 * @property maxNestedImages how many levels of SVG inside an embedded SVG image are followed.
 * @property maxSeamlessLoopSeconds longest loop worth suggesting. Past it, the longest single cycle is suggested instead.
 */
public data class SvgLimits(
    public val maxInputBytes: Long,
    public val maxElements: Int,
    public val maxDepth: Int,
    public val maxRenderedNodes: Int,
    public val maxImagePixels: Long,
    public val maxNestedImages: Int,
    public val maxSeamlessLoopSeconds: Double,
) {
    public companion object {
        /** Sizes the limits from the heap this process may use, so that a big phone can open what a small phone cannot. */
        public fun forDevice(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): SvgLimits {
            val heap = maxHeapBytes.coerceAtLeast(MINIMUM_HEAP_BYTES)
            return SvgLimits(
                maxInputBytes = heap / 2,
                // An element with its parsed styles costs about two kilobytes.
                maxElements = (heap / 2048).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                maxDepth = 512,
                maxRenderedNodes = (heap / 64).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                maxImagePixels = heap / 8,
                maxNestedImages = 4,
                maxSeamlessLoopSeconds = 60.0,
            )
        }

        private const val MINIMUM_HEAP_BYTES = 16L * 1024 * 1024
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

        /** It looks compressed, but the compressed data is damaged. */
        CORRUPT_COMPRESSION,

        /** The XML is broken, or the text is not XML. */
        MALFORMED,

        /** It is valid XML, but its root element is not `svg`. */
        NOT_SVG,
    }
}
