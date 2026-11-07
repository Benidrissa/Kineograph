package org.kineograph.media.svg

import android.graphics.Bitmap

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

    /** Position among all element children in the file, counting those not kept, such as `title`. CSS counts those too. */
    var rawIndex: Int = 0
    var rawChildCount: Int = 0

    /** True if this element sets anything its children inherit, which lets drawing reuse the parent's style object otherwise. */
    var setsInherited: Boolean = false
    var conditionsPass: Boolean = true
    var image: EmbeddedImage? = null

    val staticView: PropView by lazy(LazyThreadSafetyMode.NONE) { PropView(this, null) }

    fun addTrack(track: Track) {
        val table = trackTable ?: LinkedHashMap<String, ArrayList<Track>>().also { trackTable = it }
        table.getOrPut(track.property) { ArrayList(1) } += track
    }
}

/** An embedded `data:` image: decoded on first use, since pixels cost far more memory than the text that describes them. */
internal class EmbeddedImage(val mimeType: String, val bytes: ByteArray) {
    var nested: SvgDocument? = null
    var bitmap: Bitmap? = null
    var decoded: Boolean = false
}

/** The properties of an element at one moment: what the cascade gave it, with animated values laid on top. */
internal class PropView(val el: SvgElement, private val overlay: Map<String, SvgValue>?) {
    operator fun get(name: String): SvgValue? {
        val value = overlay?.get(name) ?: el.props[name]
        if (value === InheritValue && PROPERTIES[name]?.inherited != true) return el.parent?.staticView?.get(name)
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
) {
    /** Laid-out text, kept between frames because shaping text is far slower than drawing the outlines it produces. */
    val glyphs: GlyphCache = GlyphCache()

    private val basicShapes = HashMap<String, BasicShape?>()

    fun basicShape(text: String): BasicShape? = basicShapes.getOrPut(text) { parseBasicShape(text) }
}
