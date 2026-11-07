package org.kineograph.media.svg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.IdentityHashMap

internal const val SVG_NAMESPACE: String = "http://www.w3.org/2000/svg"
internal const val XLINK_NAMESPACE: String = "http://www.w3.org/1999/xlink"
internal const val XML_NAMESPACE: String = "http://www.w3.org/XML/1998/namespace"

/** One element of the picture, after parsing and styling. Plain data: drawing it at a given time is the renderer's job. */
internal class SvgElement(val tag: String, val parent: SvgElement?) {
    val children: ArrayList<SvgElement> = ArrayList(0)

    /** Text and element children in document order, kept only where the order matters: text elements and `style`. */
    var content: ArrayList<Any>? = null

    var attrs: Map<String, String> = emptyMap()
    var id: String? = null
    var classes: List<String> = emptyList()

    /** The final value of each property after the cascade, before any animation. */
    var props: Map<String, SvgValue> = emptyMap()
    var important: Set<String> = emptySet()

    private var trackTable: LinkedHashMap<String, ArrayList<Track>>? = null

    /** Animations by the property they change. */
    val tracks: Map<String, List<Track>> get() = trackTable ?: emptyMap()

    var siblingIndex: Int = 0

    /** Whether this element holds text in [content]. Only needed while the file is read, when asking the ancestors each time adds up. */
    var keepsText: Boolean = false

    /** Position among the kept children with the same tag, worked out when a stylesheet first asks. See [typeCounts]. */
    var typeIndex: Int = 0

    /** How many kept children have each tag. Filled in the first time `nth-of-type` asks about a child of this element. */
    var typeCounts: Map<String, Int>? = null

    /**
     * What `inherit` means for each property that normally does not inherit. It is settled when styles are worked out, because asking
     * every ancestor at every read adds up to a lot, in a deep file.
     */
    var inheritedFromParent: Map<String, SvgValue>? = null

    /** Position among all element children in the file, counting those not kept, such as `title`. CSS counts those too. */
    var rawIndex: Int = 0
    var rawChildCount: Int = 0

    /** True if this element sets anything its children inherit, which lets drawing reuse the parent's style object otherwise. */
    var setsInherited: Boolean = false
    var conditionsPass: Boolean = true
    var image: EmbeddedImage? = null

    /** On a `filter` element, what can be done about it. Worked out before the attributes it comes from are let go. */
    var filterPlan: FilterPlan? = null

    val staticView: PropView by lazy(LazyThreadSafetyMode.NONE) { PropView(this, null) }

    fun addTrack(track: Track) {
        val table = trackTable ?: LinkedHashMap<String, ArrayList<Track>>().also { trackTable = it }
        table.getOrPut(track.property) { ArrayList(1) } += track
    }
}

/**
 * An embedded `data:` image. A bitmap is decoded when it is first needed, since pixels cost far more memory than the text that describes
 * them, and it lives in [ImageStore]. A nested picture is read at once, and its bytes are then of no more use.
 */
internal class EmbeddedImage(val mimeType: String, var bytes: ByteArray?) {
    var nested: SvgDocument? = null
    var intrinsicWidth: Int = 0
    var intrinsicHeight: Int = 0
    var sizeKnown: Boolean = false
}

/**
 * The bitmaps of a file and all the pictures nested in it, up to a total. Which ones are in memory makes no difference to the picture,
 * only to how long it takes, so a file with many large images ends up decoding them again as they are used.
 */
internal class ImageStore(private val maxPixels: Long, private val maxImagePixels: Long) {
    private val decoded = LinkedHashMap<EmbeddedImage, Bitmap>(16, 0.75f, true)
    var pixels: Long = 0L
        private set

    fun sizeOf(image: EmbeddedImage): Boolean {
        if (image.sizeKnown) return image.intrinsicWidth > 0 && image.intrinsicHeight > 0
        image.sizeKnown = true
        val bytes = image.bytes ?: return false
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        image.intrinsicWidth = bounds.outWidth
        image.intrinsicHeight = bounds.outHeight
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    fun bitmapOf(image: EmbeddedImage, frame: FrameWork): Bitmap? {
        decoded[image]?.let { return it }
        if (!sizeOf(image)) return null
        val width = image.intrinsicWidth
        val height = image.intrinsicHeight
        var sample = 1
        while ((width / sample).toLong() * (height / sample) > maxImagePixels) sample *= 2
        // Decompressing goes through every pixel in the file, whatever is kept of them, and the call cannot be stopped.
        val cost = width.toLong() * height / WorkCost.SOURCE_PIXELS_PER_UNIT
        if (!frame.canAfford(cost)) {
            frame.noteLeftOut()
            return null
        }
        frame.spend(cost)
        val expected = (width / sample).toLong() * (height / sample)
        evictFor(expected)
        val bytes = image.bytes ?: return null
        val options = BitmapFactory.Options()
        options.inSampleSize = sample
        val bitmap =
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            } catch (_: OutOfMemoryError) {
                frame.noteLeftOut()
                null
            }
        if (bitmap == null) {
            // It will not go better next time, and trying costs time on every frame.
            image.intrinsicWidth = 0
            return null
        }
        decoded[image] = bitmap
        pixels += bitmap.width.toLong() * bitmap.height
        return bitmap
    }

    private fun evictFor(wanted: Long) {
        val entries = decoded.entries.iterator()
        while (pixels + wanted > maxPixels && entries.hasNext()) {
            val eldest = entries.next().value
            pixels -= eldest.width.toLong() * eldest.height
            entries.remove()
            // Anything already drawn holds its own reference to these pixels, so this is safe on a canvas that records too.
            eldest.recycle()
        }
    }
}

/** What all the pictures of one file share, so that the limits on them hold for the whole file. */
internal class SvgResources(limits: SvgLimits) {
    val glyphs: GlyphCache = GlyphCache(limits.maxGlyphCacheBytes)
    val images: ImageStore = ImageStore(limits.maxDecodedImagePixels, limits.maxImagePixels)
}

/** The properties of an element at one moment: what the cascade gave it, with animated values laid on top. */
internal class PropView(val el: SvgElement, private val overlay: Map<String, SvgValue>?) {
    operator fun get(name: String): SvgValue? {
        val value = overlay?.get(name) ?: el.props[name]
        if (value === InheritValue && PROPERTIES[name]?.inherited != true) return el.inheritedFromParent?.get(name)
        return value
    }

    val isAnimated: Boolean get() = !overlay.isNullOrEmpty()

    fun length(name: String): Length? =
        when (val value = this[name]) {
            is LengthValue -> value.length
            is LengthListValue -> value.items.firstOrNull()
            else -> null
        }

    fun lengths(name: String): List<Length> =
        when (val value = this[name]) {
            is LengthValue -> listOf(value.length)
            is LengthListValue -> value.items
            else -> emptyList()
        }

    fun number(name: String): Double? = (this[name] as? NumberValue)?.value

    fun keyword(name: String): String? = (this[name] as? KeywordValue)?.text

    fun transform(name: String): List<TransformOp>? = (this[name] as? TransformValue)?.ops
}

/** A document ready to draw. Built once by [SvgModelBuilder], and read-only from then on apart from drawing caches. */
internal class SvgModel(
    val root: SvgElement,
    val byId: Map<String, SvgElement>,
    val width: Double,
    val height: Double,
    val limits: SvgLimits,
    val resources: SvgResources,
) {
    /** Laid-out text, kept between frames because shaping text is far slower than drawing the outlines it produces. */
    val glyphs: GlyphCache get() = resources.glyphs

    private val basicShapes = HashMap<String, BasicShape?>()
    private val motionMeasures = HashMap<PathData, MotionMeasure>()

    // By identity, since a list that is not animated stays the same object. Comparing them by content would itself take a long time.
    private val openPolylines = IdentityHashMap<SvgValue, PathData>()
    private val closedPolylines = IdentityHashMap<SvgValue, PathData>()

    /**
     * Kept between frames, since finding positions along a path takes measuring it first. A path whose `d` is animated is a new one on
     * every frame, so this cannot be left to grow.
     */
    fun motionMeasure(data: PathData): MotionMeasure {
        if (motionMeasures.size >= MAX_MOTION_MEASURES) motionMeasures.clear()
        return motionMeasures.getOrPut(data) { MotionMeasure(data) }
    }

    fun basicShape(text: String): BasicShape? = basicShapes.getOrPut(text) { parseBasicShape(text) }

    /** A long polygon that does not change is the same path on every frame, and rebuilding it would leave a copy behind each time. */
    fun polyline(value: SvgValue, closed: Boolean, points: () -> List<Double>): PathData {
        val cache = if (closed) closedPolylines else openPolylines
        cache[value]?.let { return it }
        if (cache.size >= MAX_CACHED_POLYLINES) cache.clear()
        return PathData.polyline(points(), closed).also { cache[value] = it }
    }

    private companion object {
        const val MAX_MOTION_MEASURES = 64
        const val MAX_CACHED_POLYLINES = 4096
    }
}
