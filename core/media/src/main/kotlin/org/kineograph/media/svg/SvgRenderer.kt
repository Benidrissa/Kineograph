package org.kineograph.media.svg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sqrt

private val SHAPE_TAGS = setOf("rect", "circle", "ellipse", "line", "polyline", "polygon", "path")
private val MARKABLE_TAGS = setOf("line", "polyline", "polygon", "path")
private val DRAWN_TAGS = SHAPE_TAGS + setOf("g", "a", "switch", "svg", "use", "text", "image")

/** The size percentages are measured against. Each `svg` and `symbol` sets a new one. */
internal data class Viewport(val w: Double, val h: Double) {
    /** What a percentage means for a length with no direction, such as a stroke width or a radius. Defined by the specification. */
    val diagonal: Double get() = sqrt((w * w + h * h) / 2.0)
}

internal class PreserveAspect(val alignX: Int, val alignY: Int, val slice: Boolean, val none: Boolean) {
    companion object {
        val DEFAULT = PreserveAspect(1, 1, slice = false, none = false)

        private val SPACES = Regex("\\s+")
        private val ALIGNMENT = Regex("^x(Min|Mid|Max)Y(Min|Mid|Max)$")

        fun parse(text: String?): PreserveAspect {
            val parts = text
                ?.trim()
                ?.split(SPACES)
                .orEmpty()
                .filter { it != "defer" }
            val align = parts.firstOrNull() ?: return DEFAULT
            if (align == "none") return PreserveAspect(0, 0, slice = false, none = true)
            val match = ALIGNMENT.find(align) ?: return DEFAULT
            val (x, y) = match.destructured
            return PreserveAspect(axis(x), axis(y), parts.getOrNull(1) == "slice", none = false)
        }

        private fun axis(text: String): Int =
            when (text) {
                "Min" -> 0
                "Mid" -> 1
                else -> 2
            }
    }
}

internal fun viewBoxTransform(
    vbX: Double,
    vbY: Double,
    vbW: Double,
    vbH: Double,
    x: Double,
    y: Double,
    w: Double,
    h: Double,
    aspect: PreserveAspect,
): Mat2D {
    var sx = w / vbW
    var sy = h / vbH
    if (!aspect.none) {
        val scale = if (aspect.slice) maxOf(sx, sy) else minOf(sx, sy)
        sx = scale
        sy = scale
    }
    var tx = x - vbX * sx
    var ty = y - vbY * sy
    if (!aspect.none) {
        tx += (w - vbW * sx) * aspect.alignX / 2.0
        ty += (h - vbH * sy) * aspect.alignY / 2.0
    }
    return Mat2D(sx, 0.0, 0.0, sy, tx, ty)
}

/**
 * Something ready to paint, along with what tells how long that takes. [box] is what a gradient measures itself against, and [drawnBox]
 * is the part this outline covers, which for a piece of text is a lot less. [travel] is the length of its edges in user space.
 */
private open class Outline(val path: Path, val box: Box?, val segments: Int, val travel: Double, val drawnBox: Box? = box)

/** A shape ready to paint. [data] is only present for shapes that can carry markers. */
private class Geometry(path: Path, box: Box?, val data: PathData?, segments: Int, travel: Double) : Outline(path, box, segments, travel) {
    constructor(data: PathData) : this(data.path, data.bounds, data, data.commands.size, data.controlLength)
}

/**
 * The elements being drawn right now. Finding one again means a reference loop, which is cut rather than followed forever. It remembers
 * their order, so that after a failure it can go back to how it was at any earlier point.
 */
private class ActiveElements {
    private val elements = HashSet<SvgElement>()
    private val order = ArrayList<SvgElement>()

    val size: Int get() = order.size

    operator fun contains(el: SvgElement): Boolean = el in elements

    fun add(el: SvgElement): Boolean {
        if (!elements.add(el)) return false
        order += el
        return true
    }

    operator fun minusAssign(el: SvgElement) {
        if (el in elements) unwindTo(order.lastIndexOf(el))
    }

    fun unwindTo(size: Int) {
        while (order.size > size) elements -= order.removeAt(order.lastIndex)
    }
}

private class ResolvedPaint(val color: Rgba, val shader: Shader?)

/**
 * Draws a document at one moment. One instance lives for one call to render. Everything it computes is a function of the document
 * and the time, so the same time always gives the same picture, in any order of frames.
 */
internal class SvgRenderer(
    private val model: SvgModel,
    private var canvas: Canvas,
    private val time: Double,
    private val frame: FrameWork,
) {
    private val active = ActiveElements()
    private val clipStack = HashSet<SvgElement>()
    private val clipCache = HashMap<ClipKey, Outline?>()
    private val viewCache = HashMap<SvgElement, PropView>()
    private val styleCache = HashMap<SvgElement, Style>()
    private val boxCache = HashMap<BoxKey, Box?>()
    private val chainCache = HashMap<SvgElement, List<SvgElement>>()
    private val stopsCache = HashMap<SvgElement, List<GradientStop>>()

    /** Tiles kept for the rest of this frame, since every shape painted with one pattern needs the very same picture. */
    private val tiles = HashMap<TileKey, Bitmap>()
    private var tileBytes = 0L

    /** The most pixels anything on the current canvas can touch, which changes while a pattern tile is being drawn. */
    private var canvasPixels = frame.canvasPixels
    private var canvasDiagonal = sqrt(canvasPixels * 2.0)

    /** Tracked here because Canvas.getMatrix is deprecated and unreliable on hardware canvases. Only used to size pattern tiles. */
    private var ctm = Mat2D.IDENTITY

    /** How much bigger this picture is being drawn than its own size. What a non-scaling stroke keeps constant is its look at that size. */
    private var pictureScale = 1.0
    private var contextFill: PaintValue? = null
    private var contextStroke: PaintValue? = null
    private val shapePaint = Paint()

    private data class BoxKey(val el: SvgElement, val style: Style, val vp: Viewport)

    private data class ClipKey(val clip: SvgElement, val vp: Viewport, val box: Box?)

    private data class TileKey(
        val pattern: SvgElement,
        val pixelW: Int,
        val pixelH: Int,
        val tileW: Double,
        val tileH: Double,
        val contentBox: Box?,
        val vp: Viewport,
        val contextFill: PaintValue?,
        val contextStroke: PaintValue?,
    )

    /** What this frame held on to can go now, rather than whenever the collector gets to it. */
    fun release() {
        for (tile in tiles.values) tile.recycle()
        tiles.clear()
        frame.releaseRaster(tileBytes)
        tileBytes = 0L
    }

    fun render(dest: RectF) {
        val root = model.root
        val base = BASE_STYLE
        val view = viewFor(root, base)
        // The outermost element has `display` ignored on purpose. Sprite files set it to none to keep themselves out of a web page, while a file
        // opened by itself still shows what it draws, and an import is the same thing.
        val style = styleFrom(base, view)
        val viewBox = numbers(view["viewBox"]).takeIf { it.size == 4 && it[2] > 0.0 && it[3] > 0.0 }
        val vbX = viewBox?.get(0) ?: 0.0
        val vbY = viewBox?.get(1) ?: 0.0
        val vbW = viewBox?.get(2) ?: model.width
        val vbH = viewBox?.get(3) ?: model.height
        if (vbW <= 0.0 || vbH <= 0.0 || !vbW.isFinite() || !vbH.isFinite()) return
        val mapping =
            viewBoxTransform(
                vbX,
                vbY,
                vbW,
                vbH,
                dest.left.toDouble(),
                dest.top.toDouble(),
                dest.width().toDouble(),
                dest.height().toDouble(),
                aspectOf(view.keyword("preserveAspectRatio")),
            )
        // Here `overflow` on the outermost element only decides whether a window scrolls, and what is outside its box is never painted.
        canvas.clipRect(dest)
        paintBackground(view, style, dest)
        pictureScale =
            minOf(dest.width() / model.width, dest.height() / model.height).takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        concat(mapping)
        val vp = Viewport(vbW, vbH)
        // The outermost element is the one place whose default origin is its own centre, because it has a CSS box.
        val local = localTransform(root, view, style, vp, defaultOriginAtCentre = Box(vbX, vbY, vbW, vbH))
        withMatrix(local) {
            drawWithEffects(root, view, style, vp) { drawContainer(root, style, vp) }
        }
    }

    /**
     * Only the outermost element can have a background. CSS hands it over to the canvas behind the whole document, so it fills all of what the picture
     * was given, which can be more than its viewBox, and neither the element's opacity nor its transform has any say in it.
     */
    private fun paintBackground(view: PropView, style: Style, dest: RectF) {
        val color =
            when (val value = view["background-color"]) {
                is PaintValue.Solid -> value.color
                PaintValue.Current -> style.color
                else -> return
            }
        if (color.a <= 0.0) return
        frame.spend((canvasPixels / WorkCost.PIXELS_PER_UNIT_PLAIN))
        val paint = Paint()
        paint.color = color.toArgb()
        canvas.drawRect(dest, paint)
    }

    private fun numbers(value: SvgValue?): List<Double> =
        when (value) {
            is LengthListValue -> {
                frame.spend(value.items.size.toLong())
                value.items.map { it.px }
            }

            else -> {
                emptyList()
            }
        }

    /** A style, and what it takes to make: a long dash pattern is copied each time an element sets it. */
    private fun styleFrom(parent: Style, view: PropView): Style {
        (view["stroke-dasharray"] as? DashValue)?.let { frame.spend(it.items.size.toLong()) }
        return deriveStyle(parent, view)
    }

    private fun aspectOf(text: String?): PreserveAspect {
        if (text != null) frame.spend(text.length.toLong())
        return PreserveAspect.parse(text)
    }

    private fun concat(m: Mat2D) {
        if (m.isIdentity) return
        canvas.concat(m.toAndroid())
        ctm *= m
    }

    private inline fun withMatrix(m: Mat2D?, body: () -> Unit) {
        if (m == null || m.isIdentity) {
            body()
            return
        }
        val saved = ctm
        val count = canvas.save()
        concat(m)
        body()
        canvas.restoreToCount(count)
        ctm = saved
    }

    /** The properties of [el] at this moment, with animated values applied. */
    private fun viewFor(el: SvgElement, parent: Style): PropView {
        val tracks = el.tracks
        if (tracks.isEmpty()) return el.staticView
        val overlay = HashMap<String, SvgValue>(tracks.size * 2)
        // `color` goes first, because `currentColor` inside the other animated values depends on it.
        tracks["color"]?.let { applyTracks(el, "color", it, overlay, parent, parent.color) }
        val current =
            when (val color = overlay["color"] ?: el.props["color"]) {
                is PaintValue.Solid -> color.color
                else -> parent.color
            }
        for ((property, list) in tracks) {
            if (property != "color") applyTracks(el, property, list, overlay, parent, current)
        }
        return PropView(el, overlay)
    }

    private fun applyTracks(
        el: SvgElement,
        property: String,
        tracks: List<Track>,
        overlay: MutableMap<String, SvgValue>,
        parent: Style,
        current: Rgba,
    ) {
        // !important beats every animation, as the CSS cascade orders them.
        if (property in el.important) return
        var value = baseValue(el, property, parent)
        var changed = false
        // Each track that adds to a transform adds to a list, so what an element's tracks cost together can grow with their number squared.
        if (!frame.spend(tracks.size * WorkCost.SEGMENT)) return
        val ordered = if (tracks.size > 1) tracks.sortedWith(Track.stackOrder(time)) else tracks
        for (track in ordered) {
            if (!frame.spend(track.evaluationCost + sizeOf(value))) break
            val next = track.evaluate(time, value, current) ?: continue
            value = next
            changed = true
        }
        if (changed && value != null) overlay[property] = value
    }

    /** How long working with a value takes. Interpolating a list of a million numbers goes through all of them, for every use of it. */
    private fun sizeOf(value: SvgValue?): Int =
        when (value) {
            is LengthListValue -> value.items.size
            is DashValue -> value.items.size
            is PathValue -> value.data.args.size
            is TransformValue -> value.ops.sumOf { if (it is TransformOp.Blend) it.from.size + it.to.size else 1 }
            else -> 0
        }

    private fun baseValue(el: SvgElement, property: String, parent: Style): SvgValue? {
        val own = el.props[property]
        if (own != null && own !== InheritValue && own !== InitialValue) return own
        if (own !== InitialValue && PROPERTIES[property]?.inherited == true) {
            parent.inheritedValue(property)?.let { return it }
        }
        return initialValueOf(property)
    }

    private fun viewOf(el: SvgElement): PropView {
        viewCache[el]?.let { return it }
        styleOf(el)
        return viewCache.getValue(el)
    }

    /**
     * The style an element has by its place in the file. Gradients, masks, patterns and markers are drawn from wherever they are
     * referenced, but they inherit from where they are written.
     *
     * It goes down from the nearest known ancestor without recursion, because it can be asked at the bottom of an already deep drawing.
     */
    private fun styleOf(el: SvgElement): Style {
        styleCache[el]?.let { return it }
        val unknown = ArrayList<SvgElement>()
        var nearest: SvgElement? = el
        while (nearest != null && nearest !in styleCache) {
            unknown += nearest
            nearest = nearest.parent
        }
        var style = nearest?.let { styleCache[it] } ?: BASE_STYLE
        for (i in unknown.indices.reversed()) {
            val element = unknown[i]
            frame.spend(WorkCost.BOX_MEASURED)
            val view = viewFor(element, style)
            viewCache[element] = view
            style = styleFrom(style, view)
            styleCache[element] = style
        }
        return style
    }

    private fun targetOf(view: PropView): SvgElement? {
        val href = view.keyword("href")?.trim() ?: return null
        frame.spend(href.length.toLong())
        return if (href.startsWith("#")) model.byId[href.substring(1)] else null
    }

    private fun drawChildren(el: SvgElement, style: Style, vp: Viewport) {
        for (child in el.children) {
            if (frame.isStopped) return
            drawElement(child, style, vp)
        }
    }

    /**
     * One element that goes wrong costs that element and nothing else. Whatever it was in the middle of is undone, so that what comes
     * after it in the file still draws, as it would in a browser.
     */
    private fun drawElement(el: SvgElement, parent: Style, vp: Viewport) {
        if (el.tag !in DRAWN_TAGS || !el.conditionsPass) return
        if (!frame.visit() || !frame.enter()) return
        val saveCount = canvas.saveCount
        val savedCtm = ctm
        val activeCount = active.size
        val savedFill = contextFill
        val savedStroke = contextStroke
        val depth = frame.depth
        try {
            val view = viewFor(el, parent)
            if (view.keyword("display") == "none") return
            val style = styleFrom(parent, view)
            withMatrix(localTransform(el, view, style, vp, null)) {
                // Going straight to it leaves two fewer calls on the stack for each level of the picture, and nearly every level can.
                if (hasEffects(view)) {
                    drawWithEffects(el, view, style, vp) { alpha -> drawBody(el, view, style, vp, alpha) }
                } else {
                    drawBody(el, view, style, vp, 1.0)
                }
            }
        } catch (_: RuntimeException) {
            canvas.restoreToCount(saveCount)
            ctm = savedCtm
            active.unwindTo(activeCount)
            contextFill = savedFill
            contextStroke = savedStroke
            clipStack.clear()
            frame.depth = depth
            frame.noteLeftOut()
        } finally {
            frame.leave()
        }
    }

    private fun hasEffects(view: PropView): Boolean =
        view["opacity"] != null || view["clip-path"] != null || view["mask"] != null || view["filter"] != null

    private fun drawBody(el: SvgElement, view: PropView, style: Style, vp: Viewport, alpha: Double) {
        when (el.tag) {
            "g", "a" -> if (active.add(el)) {
                drawChildren(el, style, vp)
                active -= el
            }

            "switch" -> firstPassingChild(el)?.let { drawElement(it, style, vp) }

            "svg" -> drawNestedSvg(el, view, style, vp, null, null)

            "use" -> drawUse(el, view, style, vp)

            "text" -> drawText(el, view, style, vp, alpha)

            "image" -> drawImage(el, view, style, vp, alpha)

            else -> drawShape(el, view, style, vp, alpha)
        }
    }

    private fun drawContainer(el: SvgElement, style: Style, vp: Viewport) {
        if (!active.add(el)) return
        drawChildren(el, style, vp)
        active -= el
    }

    private fun firstPassingChild(el: SvgElement): SvgElement? {
        frame.spend(el.children.size.toLong())
        return el.children.firstOrNull { it.tag in DRAWN_TAGS && it.conditionsPass }
    }

    private fun localTransform(el: SvgElement, view: PropView, style: Style, vp: Viewport, defaultOriginAtCentre: Box?): Mat2D? {
        val transform = view.transform("transform")
        val translate = view.transform("translate")
        val rotate = view.transform("rotate")
        val scale = view.transform("scale")
        val motion = view.transform(MOTION_PROPERTY)
        val offset = offsetPosition(view, style)
        val hasOwn =
            !transform.isNullOrEmpty() || !translate.isNullOrEmpty() || !rotate.isNullOrEmpty() || !scale.isNullOrEmpty() || offset != null
        // A use element's x and y count as one more transform after its own. So its clip and its mask move along with them.
        val useShift =
            if (el.tag == "use") {
                Mat2D.translate(
                    view.length("x")?.resolve(vp.w, style.fontSize) ?: 0.0,
                    view.length("y")?.resolve(vp.h, style.fontSize) ?: 0.0,
                )
            } else {
                null
            }
        if (!hasOwn && motion.isNullOrEmpty() && useShift == null) return null
        var result = Mat2D.IDENTITY
        if (!motion.isNullOrEmpty()) result = combine(motion, TransformContext(vp.w, vp.h, style.fontSize))
        if (hasOwn) {
            frame.spend(((transform?.size ?: 0) + (translate?.size ?: 0) + (rotate?.size ?: 0) + (scale?.size ?: 0)) * WorkCost.SEGMENT)
            val reference =
                when (view.keyword("transform-box")) {
                    "fill-box", "content-box" -> {
                        elementBox(el, view, style, vp) ?: Box(0.0, 0.0, 0.0, 0.0)
                    }

                    "stroke-box", "border-box" -> {
                        (elementBox(el, view, style, vp) ?: Box(0.0, 0.0, 0.0, 0.0)).outset(strokeWidthOf(style, vp) / 2.0)
                    }

                    else -> {
                        defaultOriginAtCentre ?: Box(0.0, 0.0, vp.w, vp.h)
                    }
                }
            val origin = (view["transform-origin"] as? LengthListValue)?.items
            val ox: Double
            val oy: Double
            if (origin != null && origin.size >= 2) {
                ox = reference.x + origin[0].resolve(reference.w, style.fontSize)
                oy = reference.y + origin[1].resolve(reference.h, style.fontSize)
            } else if (defaultOriginAtCentre != null) {
                ox = reference.x + reference.w / 2
                oy = reference.y + reference.h / 2
            } else {
                ox = reference.x
                oy = reference.y
            }
            val context = TransformContext(reference.w, reference.h, style.fontSize)
            var own = Mat2D.translate(ox, oy)
            if (translate != null) own *= combine(translate, context)
            if (rotate != null) own *= combine(rotate, context)
            if (scale != null) own *= combine(scale, context)
            // The part of the element its origin marks goes on the path, and turns around that point.
            if (offset != null) own = own * Mat2D.translate(offset.x - ox, offset.y - oy) * Mat2D.rotate(offset.degrees)
            if (transform != null) own *= combine(transform, context)
            own *= Mat2D.translate(-ox, -oy)
            result *= own
        }
        if (useShift != null) result *= useShift
        return result
    }

    /** Where `offset-path` puts an element, and which way it faces there. Null if it does not ask for that. */
    private fun offsetPosition(view: PropView, style: Style): MotionPosition? {
        val value = view["offset-path"] as? OffsetPathValue ?: return null
        val data =
            value.data
                ?: value.reference?.let { id -> (model.byId[id]?.let(::viewOf)?.get("d") as? PathValue)?.data }
                ?: return null
        if (data.isEmpty) return null
        frame.spend(data.commands.size * WorkCost.OUTLINE_SEGMENT)
        val measure = model.motionMeasure(data)
        val along = (view.length("offset-distance") ?: Length.ZERO).resolve(measure.total, style.fontSize)
        // Round and round on a closed path, while an open one stops at its ends.
        val distance = if (data.commands.lastOrNull().let { it == 'Z' || it == 'z' } &&
            measure.total > 0.0
        ) {
            along.mod(measure.total)
        } else {
            along
        }
        val position = measure.at(distance)
        return MotionPosition(position.x, position.y, offsetAngle(view.keyword("offset-rotate"), position.degrees))
    }

    /** `auto` faces along the path, which is what it is if nothing is said, and `reverse` the other way. An angle counts on top of those, or alone. */
    private fun offsetAngle(text: String?, along: Double): Double {
        if (text == null) return along
        frame.spend(text.length.toLong())
        var degrees = 0.0
        var follows = false
        for (word in splitList(text)) {
            when (word) {
                "auto" -> {
                    degrees += along
                    follows = true
                }

                "reverse" -> {
                    degrees += along + 180.0
                    follows = true
                }

                else -> {
                    degrees += parseAngle(word) ?: return if (follows) degrees else along
                }
            }
        }
        return degrees
    }

    private fun strokeWidthOf(style: Style, vp: Viewport): Double =
        if (style.stroke === PaintValue.None) 0.0 else style.strokeWidth.resolve(vp.diagonal, style.fontSize)

    /**
     * Applies opacity, clip and mask around [body], in the order the specification asks for.
     *
     * A layer is a piece of native memory the size of the canvas, so a few hundred nested groups with some opacity can hold gigabytes, with
     * nothing on the heap to show for it. What is past the allowance simply is not drawn. It would be close to invisible anyway.
     */
    private fun drawWithEffects(el: SvgElement, view: PropView, style: Style, vp: Viewport, body: (Double) -> Unit) {
        val opacity = (view.number("opacity") ?: 1.0).coerceIn(0.0, 1.0)
        if (opacity <= 0.0) return
        val filterPlan =
            view
                .keyword("filter")
                ?.let(::urlReference)
                ?.let { model.byId[it] }
                ?.filterPlan
        if (filterPlan === FilterPlan.DrawsNothing) return
        val recolour = filterPlan as? FilterPlan.Recolour
        val clipText = view.keyword("clip-path")
        val clipId = clipText?.let(::urlReference)
        val shape =
            (view["clip-path"] as? ClipShapeValue)?.shape ?: if (clipText != null && clipId == null) model.basicShape(clipText) else null
        val maskElement = view
            .keyword("mask")
            ?.let(::urlReference)
            ?.let { model.byId[it] }
            ?.takeIf { it.tag == "mask" && it !in active }
        val clip = clipId?.let { model.byId[it] }?.takeIf { it.tag == "clipPath" }
        if (clip == null && shape == null && maskElement == null && recolour == null && opacity >= 1.0) {
            body(1.0)
            return
        }
        val count = canvas.save()
        val box by lazy(LazyThreadSafetyMode.NONE) { elementBox(el, view, style, vp) }
        if (clip != null) {
            val outline = clipPathOf(clip, vp) { box }
            if (outline != null && !clipTo(outline)) {
                canvas.restoreToCount(count)
                return
            }
        }
        if (shape != null) {
            val reference =
                when (shape.box) {
                    BasicShape.FILL_BOX -> box
                    BasicShape.VIEW_BOX -> Box(0.0, 0.0, vp.w, vp.h)
                    else -> box?.outset(strokeWidthOf(style, vp) / 2.0)
                } ?: Box(0.0, 0.0, 0.0, 0.0)
            // Nothing tells how long the edges of a CSS shape are until it is placed, so this goes by the longest they could be.
            frame.spend(shape.segments * WorkCost.SEGMENT)
            if (!clipTo(Outline(shape.path(reference, style.fontSize), reference, shape.segments, Double.POSITIVE_INFINITY))) {
                canvas.restoreToCount(count)
                return
            }
        }
        // A shape painted only once does not need a layer to composite as one, which saves a full-screen buffer per element.
        val directAlpha = maskElement == null && recolour == null && opacity < 1.0 && paintsOnce(el, style)
        val needsLayer = opacity < 1.0 && !directAlpha
        val maskRegion = if (maskElement != null) maskRegionOf(maskElement, box, vp) else null
        // What a layer can show decides how big it has to be. Files can hold thousands of shapes with opacity, or a mask each.
        val opacityBounds = if (needsLayer) layerBoundsOf(el, view, style, vp) { box } else null
        val opacityBytes = reserveLayers(if (needsLayer) 1 else 0, opacityBounds)
        val maskBytes = if (opacityBytes >= 0L) reserveLayers(if (maskElement != null) 2 else 0, maskRegion) else -1L
        val filterRegion = recolour?.let { filterRegionOf(it, box, style, vp) }
        val filterBytes = if (maskBytes >= 0L) reserveLayers(if (recolour != null) 1 else 0, filterRegion) else -1L
        if (filterBytes < 0L) {
            frame.releaseRaster(opacityBytes.coerceAtLeast(0L) + maskBytes.coerceAtLeast(0L))
            canvas.restoreToCount(count)
            return
        }
        if (maskElement != null) frame.spend(WorkCost.MASK + (onScreenArea(maskRegion) * WorkCost.MASK_UNITS_PER_PIXEL).toLong())
        // A colour filter goes through every pixel it is given, just as a mask does.
        if (recolour != null) frame.spend(WorkCost.MASK + (onScreenArea(filterRegion) * WorkCost.MASK_UNITS_PER_PIXEL).toLong())
        try {
            if (needsLayer) canvas.saveLayerAlpha(opacityBounds?.toRectF(), alphaByte(opacity))
            if (maskElement != null) canvas.saveLayer(maskRegion?.toRectF(), null)
            val beforeFilter = canvas.saveCount
            if (recolour != null) {
                filterRegion?.let { canvas.clipRect(it.toRectF()) }
                canvas.saveLayer(
                    filterRegion?.toRectF(),
                    Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(recolour.matrix)) },
                )
            }
            body(if (directAlpha) opacity else 1.0)
            canvas.restoreToCount(beforeFilter)
            if (maskElement != null) applyMask(maskElement, box, maskRegion, vp)
            canvas.restoreToCount(count)
        } finally {
            frame.releaseRaster(opacityBytes + maskBytes + filterBytes)
        }
    }

    /** In the user space of the element it is on. Null if that takes a box it does not have, and then it is the whole canvas. */
    private fun filterRegionOf(plan: FilterPlan.Recolour, box: Box?, style: Style, vp: Viewport): Box? {
        val region = plan.region
        if (region.inObjectUnits) {
            val b = box ?: return null
            return finiteOrNull(
                Box(
                    b.x + region.x.fraction() * b.w,
                    b.y + region.y.fraction() * b.h,
                    region.width.fraction() * b.w,
                    region.height.fraction() * b.h,
                ),
            )
        }
        return finiteOrNull(
            Box(
                region.x.resolve(vp.w, style.fontSize),
                region.y.resolve(vp.h, style.fontSize),
                region.width.resolve(vp.w, style.fontSize),
                region.height.resolve(vp.h, style.fontSize),
            ),
        )
    }

    private fun Box.toRectF(): RectF = RectF(x.toFloat(), y.toFloat(), right.toFloat(), bottom.toFloat())

    /**
     * Everything a lone shape paints, with a margin for how far a stroke's corners can reach, and for the smoothing of its edges. Groups
     * and text have no such limit that is simple to tell, so they get the whole canvas.
     */
    private fun layerBoundsOf(el: SvgElement, view: PropView, style: Style, vp: Viewport, box: () -> Box?): Box? {
        if (el.tag !in SHAPE_TAGS) return null
        if (el.tag in MARKABLE_TAGS && (style.markerStart != null || style.markerMid != null || style.markerEnd != null)) return null
        // A stroke that keeps its width on screen can reach any distance here, depending on the scale.
        if (view.keyword("vector-effect") == "non-scaling-stroke") return null
        val bounds = box() ?: return null
        val reach = strokeWidthOf(style, vp) / 2.0 * maxOf(style.miterLimit, MIN_STROKE_REACH)
        val margin = reach + LAYER_MARGIN_PIXELS / onScreenScale().coerceAtLeast(MIN_SCALE_FOR_MARGIN)
        return finiteOrNull(bounds.outset(margin))
    }

    private fun finiteOrNull(box: Box): Box? = box.takeIf {
        it.x.isFinite() && it.y.isFinite() && it.right.isFinite() &&
            it.bottom.isFinite()
    }

    /** Pays for layers of what they can cover, and holds their memory. A negative answer means they cannot be had. */
    private fun reserveLayers(layers: Int, bounds: Box?): Long {
        if (layers == 0) return 0L
        val pixels = onScreenArea(bounds).toLong()
        val bytes = layers * pixels * FrameWork.BYTES_PER_PIXEL
        if (!frame.spend(layers * (WorkCost.LAYER + pixels / WorkCost.PIXELS_PER_UNIT_LAYER)) || !frame.reserveRaster(bytes)) {
            frame.noteLeftOut()
            return -1L
        }
        return bytes
    }

    /**
     * Clipping scans the outline just as filling it does, in a call that cannot be stopped, but it paints nothing. Exported charts can clip
     * every one of thousands of marks, so that has to stay cheap. False means the element should not be drawn.
     */
    private fun clipTo(outline: Outline): Boolean {
        // A plain rectangle needs no scanning at all, which is most of the clip paths of a chart.
        val isPlain = outline.segments <= ROUNDED_SHAPE_SEGMENTS && outline.path.isRect(null)
        val cost = if (isPlain) {
            ROUNDED_SHAPE_SEGMENTS * WorkCost.SEGMENT
        } else {
            rasterCost(
                outline.segments,
                outline.travel,
                outline.drawnBox,
                onScreenScale(),
                paints = false,
            )
        }
        if (!canDrawInOneCall(cost)) {
            frame.noteLeftOut()
            return false
        }
        frame.spend(cost)
        canvas.clipPath(outline.path)
        return true
    }

    /** How much the current transform enlarges things, along whichever axis it does so most. */
    private fun onScreenScale(): Double {
        val scale = maxOf(hypot(ctm.a, ctm.b), hypot(ctm.c, ctm.d))
        return if (scale.isFinite()) scale else 0.0
    }

    /** The pixels a box covers on the canvas. Not knowing the box means all of them, as does anything that would come to more. */
    private fun onScreenArea(box: Box?): Double {
        if (box == null) return canvasPixels.toDouble()
        val area = box.w * box.h * abs(ctm.determinant)
        return if (area.isNaN()) canvasPixels.toDouble() else area.coerceIn(1.0, canvasPixels.toDouble())
    }

    /**
     * What scanning an outline takes. Most of the time that is little more than its number of segments and the pixels it covers. What
     * makes a path slow is many long edges crossing each other in a small area, so the length of the edges goes in squared.
     *
     * Edges mostly off the canvas are cut to it first, so one segment never counts for more than the canvas is across.
     */
    private fun rasterCost(segments: Int, travel: Double, box: Box?, scale: Double, paints: Boolean = true): Long {
        val edges = minOf(travel * scale, segments * canvasDiagonal).takeIf { it.isFinite() } ?: 0.0
        val area = onScreenArea(box)
        val crossings = edges * edges / sqrt(area) * WorkCost.CROSSING_DENSITY
        val painting = if (paints) area / WorkCost.PIXELS_PER_UNIT_PLAIN else 0.0
        val total = segments * WorkCost.SEGMENT + edges * WorkCost.EDGE_PIXEL + crossings + painting
        return if (total >= MAX_COST) MAX_COST.toLong() else total.toLong()
    }

    /** Whether one native call of this price may go ahead. Nothing can interrupt it, so it has to be small, whatever is left to spend. */
    private fun canDrawInOneCall(cost: Long): Boolean = cost <= MAX_ONE_CALL && frame.canAfford(cost)

    private fun alphaByte(opacity: Double): Int = (opacity * 255.0 + 0.5).toInt().coerceIn(0, 255)

    private fun paintsOnce(el: SvgElement, style: Style): Boolean {
        if (el.tag !in SHAPE_TAGS) return false
        if (el.tag in MARKABLE_TAGS && (style.markerStart != null || style.markerMid != null || style.markerEnd != null)) return false
        return style.fill === PaintValue.None || style.stroke === PaintValue.None
    }

    /**
     * The outline a clipPath allows, in the user space of the element it clips. Null when the reference is unusable.
     *
     * Kept for the rest of the frame, for each box it was measured against. Clip paths can refer to each other, so that a chain of a dozen
     * of them, each referring ten times to the one before, would otherwise have to be worked out a trillion times.
     */
    private fun clipPathOf(clip: SvgElement, vp: Viewport, box: () -> Box?): Outline? {
        if (clip in clipStack) return null
        val view = viewOf(clip)
        val inObjectUnits = view.keyword("clipPathUnits") == "objectBoundingBox"
        val outer = view
            .keyword("clip-path")
            ?.let(::urlReference)
            ?.let { model.byId[it] }
            ?.takeIf { it.tag == "clipPath" }
        // A clipPath that clips a clipPath takes after whatever that one needed.
        val bounds = if (inObjectUnits || outer != null) box() else null
        val key = ClipKey(clip, vp, bounds)
        if (clipCache.containsKey(key)) return clipCache[key]
        if (clipCache.size >= MAX_CACHED_CLIPS) clipCache.clear()
        if (!frame.visit(WorkCost.BOX_MEASURED) || !frame.enter()) return null
        clipStack += clip
        val result = buildClipPath(clip, view, inObjectUnits, bounds, outer, vp, box)
        clipStack -= clip
        frame.leave()
        clipCache[key] = result
        return result
    }

    private fun buildClipPath(
        clip: SvgElement,
        view: PropView,
        inObjectUnits: Boolean,
        bounds: Box?,
        outer: SvgElement?,
        vp: Viewport,
        box: () -> Box?,
    ): Outline {
        val style = styleOf(clip)
        var matrix = localTransform(clip, view, style, vp, null) ?: Mat2D.IDENTITY
        if (inObjectUnits) {
            if (bounds == null) return Outline(Path(), null, 0, 0.0)
            matrix = matrix * Mat2D.translate(bounds.x, bounds.y) * Mat2D.scale(bounds.w, bounds.h)
        }
        var out: Path? = null
        var segments = 0
        var travel = 0.0
        val childBoxes = ArrayList<Box>()
        for (child in clip.children) {
            if (frame.isStopped) break
            val outline = clipChildPath(child, style, vp) ?: continue
            val childBox = boundsOf(outline.path)
            frame.spend(childBoxes.size.toLong())
            val merged = out
            if (merged == null) {
                out = outline.path
            } else if (merged.fillType == outline.path.fillType && childBoxes.none { it.touches(childBox) }) {
                // Letters of a word or the islands of a map do not need working out, and this works however many points they have.
                merged.addPath(outline.path)
            } else if (!combinePaths(merged, outline.path, segments + outline.segments, Path.Op.UNION)) {
                // Past what can be combined properly, this is still right for shapes wound the same way, as nearly all are.
                merged.addPath(outline.path)
            }
            childBoxes += childBox
            segments += outline.segments
            travel += outline.travel
        }
        val result = out ?: Path()
        val scale = maxOf(hypot(matrix.a, matrix.b), hypot(matrix.c, matrix.d)).takeIf { it.isFinite() } ?: 0.0
        travel *= scale
        if (!matrix.isIdentity) result.transform(matrix.toAndroid())
        // A clipPath can itself be clipped, and the two then intersect.
        if (outer != null) {
            clipPathOf(outer, vp, box)?.let { other ->
                if (combinePaths(result, other.path, segments + other.segments, Path.Op.INTERSECT)) {
                    segments += other.segments
                    travel += other.travel
                }
            }
        }
        return Outline(result, boundsOf(result), segments, travel)
    }

    private fun Box.touches(other: Box): Boolean = x <= other.right && other.x <= right && y <= other.bottom && other.y <= bottom

    private fun boundsOf(path: Path): Box {
        val bounds = RectF()
        path.computeBounds(bounds, false)
        return Box(bounds.left.toDouble(), bounds.top.toDouble(), bounds.width().toDouble(), bounds.height().toDouble())
    }

    /**
     * Combines two paths. How long this takes depends on how the edges cross, and grows steeply: a thousand random segments take half a
     * minute, in a call that cannot be stopped, and real clip paths are nothing like that. So a size past which this is not tried at all
     * bounds any one call, and what the call really took is what is paid for, because no price set in advance suits both kinds of path.
     */
    private fun combinePaths(into: Path, other: Path, segments: Int, op: Path.Op): Boolean {
        if (segments > MAX_COMBINED_SEGMENTS) {
            frame.noteLeftOut()
            return false
        }
        val charged = segments * WorkCost.PATH_OP_SEGMENT
        if (!frame.spend(charged)) return false
        val started = System.nanoTime()
        into.op(other, op)
        frame.payOverrun(System.nanoTime() - started, charged)
        return true
    }

    private fun clipChildPath(child: SvgElement, parent: Style, vp: Viewport): Outline? {
        if (child.tag != "use" && child.tag != "text" && child.tag !in SHAPE_TAGS) return null
        if (!child.conditionsPass) return null
        // Nothing here is drawn, but none of it is free. Without this a pile of references gets around the allowance altogether.
        if (!frame.visit() || !frame.enter()) return null
        try {
            return buildClipChild(child, parent, vp)
        } finally {
            frame.leave()
        }
    }

    private fun buildClipChild(child: SvgElement, parent: Style, vp: Viewport): Outline? {
        val view = viewFor(child, parent)
        if (view.keyword("display") == "none") return null
        val style = styleFrom(parent, view)
        if (!style.visible) return null
        val path: Path
        var segments: Int
        var travel: Double
        when (child.tag) {
            "use" -> {
                val target = targetOf(view) ?: return null
                if (target.tag != "text" && target.tag !in SHAPE_TAGS) return null
                val outline = clipChildPath(target, style, vp) ?: return null
                path = outline.path
                segments = outline.segments
                travel = outline.travel
            }

            "text" -> {
                path = Path()
                val layout = layoutText(child, view, style, vp)
                segments = 0
                travel = 0.0
                for (run in layout.runs) {
                    run.path?.let { path.addPath(it) }
                    segments += run.text.length * SEGMENTS_PER_GLYPH
                    travel += run.text.length * run.style.fontSize * EDGE_PER_GLYPH_IN_EMS
                }
                layout.release()
            }

            else -> {
                val geometry = geometry(child, view, style, vp) ?: return null
                path = Path(geometry.path)
                segments = geometry.segments
                travel = geometry.travel
            }
        }
        frame.spend(segments * WorkCost.SEGMENT)
        if (child.tag != "use") path.fillType = if (style.clipEvenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        val local = localTransform(child, view, style, vp, null)
        if (local != null) {
            path.transform(local.toAndroid())
            val scale = maxOf(hypot(local.a, local.b), hypot(local.c, local.d)).takeIf { it.isFinite() } ?: 0.0
            travel *= scale
        }
        val outer = view
            .keyword("clip-path")
            ?.let(::urlReference)
            ?.let { model.byId[it] }
            ?.takeIf { it.tag == "clipPath" }
        if (outer != null) {
            clipPathOf(outer, vp) { elementBox(child, view, style, vp) }?.let { other ->
                if (combinePaths(path, other.path, segments + other.segments, Path.Op.INTERSECT)) {
                    segments += other.segments
                    travel += other.travel
                }
            }
        }
        return Outline(path, null, segments, travel)
    }

    /** Nothing shows outside this box, in the user space of the masked element, whatever the mask holds. Null if there is no box to go by. */
    private fun maskRegionOf(mask: SvgElement, box: Box?, vp: Viewport): Box? {
        val view = viewOf(mask)
        val fontSize = styleOf(mask).fontSize
        if (view.keyword("maskUnits") != "userSpaceOnUse") {
            val b = box ?: return null
            val x = (view.length("x") ?: Length(pct = -10.0)).fraction()
            val y = (view.length("y") ?: Length(pct = -10.0)).fraction()
            val w = (view.length("width") ?: Length(pct = 120.0)).fraction()
            val h = (view.length("height") ?: Length(pct = 120.0)).fraction()
            return finiteOrNull(Box(b.x + x * b.w, b.y + y * b.h, w * b.w, h * b.h))
        }
        return finiteOrNull(
            Box(
                (view.length("x") ?: Length(pct = -10.0)).resolve(vp.w, fontSize),
                (view.length("y") ?: Length(pct = -10.0)).resolve(vp.h, fontSize),
                (view.length("width") ?: Length(pct = 120.0)).resolve(vp.w, fontSize),
                (view.length("height") ?: Length(pct = 120.0)).resolve(vp.h, fontSize),
            ),
        )
    }

    /**
     * Multiplies what was drawn in the current layer by the mask. Drawing the mask's content over opaque black first means a part
     * that is half transparent counts as half its brightness, because a colour filter only sees straight colour and would otherwise
     * read half-transparent white as fully bright.
     */
    private fun applyMask(mask: SvgElement, box: Box?, region: Box?, vp: Viewport) {
        val view = viewOf(mask)
        val style = styleOf(mask)
        val paint = Paint()
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        val isLuminance = view.keyword("mask-type") != "alpha"
        if (isLuminance) paint.colorFilter = LUMINANCE_TO_ALPHA
        canvas.saveLayer(region?.toRectF(), paint)
        if (isLuminance) canvas.drawColor(Color.BLACK)
        val contentInObjectUnits = view.keyword("maskContentUnits") == "objectBoundingBox"
        if (region != null && region.w > 0.0 && region.h > 0.0 && (!contentInObjectUnits || box != null) && active.add(mask)) {
            val savedCtm = ctm
            val count = canvas.save()
            canvas.clipRect(region.x.toFloat(), region.y.toFloat(), region.right.toFloat(), region.bottom.toFloat())
            if (contentInObjectUnits && box != null) concat(Mat2D.translate(box.x, box.y) * Mat2D.scale(box.w, box.h))
            drawChildren(mask, style, vp)
            canvas.restoreToCount(count)
            ctm = savedCtm
            active -= mask
        }
        canvas.restore()
    }

    private fun drawNestedSvg(el: SvgElement, view: PropView, style: Style, vp: Viewport, overrideW: Length?, overrideH: Length?) {
        if (!active.add(el)) return
        drawViewportContent(el, view, style, vp, overrideW, overrideH)
        active -= el
    }

    /** Shared by nested `svg` and by `symbol`, which both open a new viewport with its own viewBox. */
    private fun drawViewportContent(el: SvgElement, view: PropView, style: Style, vp: Viewport, overrideW: Length?, overrideH: Length?) {
        val x = view.length("x")?.resolve(vp.w, style.fontSize) ?: 0.0
        val y = view.length("y")?.resolve(vp.h, style.fontSize) ?: 0.0
        val w = (overrideW ?: view.length("width") ?: Length(pct = 100.0)).resolve(vp.w, style.fontSize)
        val h = (overrideH ?: view.length("height") ?: Length(pct = 100.0)).resolve(vp.h, style.fontSize)
        if (w <= 0.0 || h <= 0.0) return
        val viewBox = numbers(view["viewBox"]).takeIf { it.size == 4 && it[2] > 0.0 && it[3] > 0.0 }
        val inner = viewBoxOr(viewBox, view, x, y, w, h)
        val savedCtm = ctm
        val count = canvas.save()
        val overflow = view.keyword("overflow")
        if (overflow != "visible" && overflow != "auto") {
            canvas.clipRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
        }
        concat(inner)
        val newViewport = if (viewBox != null) Viewport(viewBox[2], viewBox[3]) else Viewport(w, h)
        drawChildren(el, style, newViewport)
        canvas.restoreToCount(count)
        ctm = savedCtm
    }

    private fun viewBoxOr(viewBox: List<Double>?, view: PropView, x: Double, y: Double, w: Double, h: Double): Mat2D =
        if (viewBox != null) {
            viewBoxTransform(
                viewBox[0],
                viewBox[1],
                viewBox[2],
                viewBox[3],
                x,
                y,
                w,
                h,
                aspectOf(view.keyword("preserveAspectRatio")),
            )
        } else {
            Mat2D.translate(x, y)
        }

    private fun drawUse(el: SvgElement, view: PropView, style: Style, vp: Viewport) {
        val target = targetOf(view) ?: return
        // Marking the use element as well as the target is what also catches a loop that runs through use elements alone.
        if (target in active || !active.add(el)) return
        when (target.tag) {
            "symbol", "svg" -> {
                drawReferencedViewport(target, style, vp, view.length("width"), view.length("height"))
            }

            else -> {
                drawElement(target, style, vp)
            }
        }
        active -= el
    }

    private fun drawReferencedViewport(target: SvgElement, parent: Style, vp: Viewport, width: Length?, height: Length?) {
        if (!frame.visit()) return
        val view = viewFor(target, parent)
        if (view.keyword("display") == "none") return
        val style = styleFrom(parent, view)
        withMatrix(localTransform(target, view, style, vp, null)) {
            drawWithEffects(target, view, style, vp) {
                if (active.add(target)) {
                    drawViewportContent(target, view, style, vp, width, height)
                    active -= target
                }
            }
        }
    }

    private fun drawShape(el: SvgElement, view: PropView, style: Style, vp: Viewport, alpha: Double) {
        val geometry = geometry(el, view, style, vp) ?: return
        for (step in style.paintOrder) {
            when (step) {
                Style.PAINT_FILL -> {
                    if (style.visible) fillPath(geometry, style, vp, alpha)
                }

                Style.PAINT_STROKE -> {
                    if (style.visible) strokePath(geometry, view, style, vp, alpha)
                }

                Style.PAINT_MARKERS -> {
                    if (style.visible && geometry.data != null) drawMarkers(geometry.data, style, vp)
                }
            }
        }
    }

    /** What a shape costs is decided before it is drawn, because the drawing itself is one native call that nothing can stop. */
    private fun fillPath(outline: Outline, style: Style, vp: Viewport, alpha: Double) {
        val cost = rasterCost(outline.segments, outline.travel, outline.drawnBox, onScreenScale())
        if (!canDrawInOneCall(cost)) {
            frame.noteLeftOut()
            return
        }
        val paint = resolvePaint(style.fill, style, outline.box, vp) ?: return
        val opacity = style.fillOpacity.coerceIn(0.0, 1.0) * alpha
        if (opacity <= 0.0) return
        if (paint.shader != null) frame.spend((onScreenArea(outline.drawnBox) / WorkCost.SHADER_PIXELS_PER_UNIT).toLong())
        if (!frame.spend(cost)) return
        val path = outline.path
        path.fillType = if (style.fillEvenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        val p = preparePaint(paint, opacity, style)
        p.style = Paint.Style.FILL
        canvas.drawPath(path, p)
    }

    private fun strokePath(outline: Outline, view: PropView, style: Style, vp: Viewport, alpha: Double) {
        val width = style.strokeWidth.resolve(vp.diagonal, style.fontSize)
        // Android reads zero as "one device pixel, whatever the scale", where SVG means "no stroke at all".
        if (width <= 0.0) return
        val paint = resolvePaint(style.stroke, style, outline.box, vp) ?: return
        val opacity = style.strokeOpacity.coerceIn(0.0, 1.0) * alpha
        if (opacity <= 0.0) return
        val p = preparePaint(paint, opacity, style)
        p.style = Paint.Style.STROKE
        val keepsItsWidth = view.keyword("vector-effect") == "non-scaling-stroke"
        // Those pixels mean the picture's own, or chart lines would turn into hairlines the larger the canvas is, and the picture would change with it.
        p.strokeWidth = (if (keepsItsWidth) width * pictureScale else width).toFloat()
        p.strokeCap = style.lineCap
        p.strokeJoin = style.lineJoin
        p.strokeMiter = style.miterLimit.coerceAtLeast(1.0).toFloat()
        if (paint.shader != null) frame.spend((onScreenArea(outline.drawnBox) / WorkCost.SHADER_PIXELS_PER_UNIT).toLong())
        if (keepsItsWidth) {
            strokeOnScreen(outline, p, view, style, vp, paint.shader)
        } else {
            drawStroke(outline.path, outline, p, view, style, vp, onScreenScale(), width * onScreenScale(), 1.0, 1.0)
        }
    }

    /**
     * A non-scaling stroke has its width, joins and dashes on screen, whatever the transforms ask of the shape. No single scale can
     * undo a transform that stretches one way, so the path goes to screen space first, and the stroke is made there.
     */
    private fun strokeOnScreen(outline: Outline, paint: Paint, view: PropView, style: Style, vp: Viewport, shader: Shader?) {
        val inverse = ctm.inverse() ?: return
        val onScreen = Path()
        outline.path.transform(ctm.toAndroid(), onScreen)
        val count = canvas.save()
        canvas.concat(inverse.toAndroid())
        if (shader != null) {
            val local = Matrix()
            shader.getLocalMatrix(local)
            local.postConcat(ctm.toAndroid())
            shader.setLocalMatrix(local)
        }
        val scale = onScreenScale()
        drawStroke(onScreen, outline, paint, view, style, vp, scale, paint.strokeWidth.toDouble(), scale, pictureScale)
        canvas.restoreToCount(count)
    }

    /**
     * Skia gives up on dashes past a million for each path, and for that many, each stroked with its caps, it will first take a minute and
     * a gigabyte. Dashes that short are no longer visible as dashes either, so past a far lower number the line is drawn whole.
     *
     * [lengthScale] takes lengths in user space to the space the stroke is made in, whose dashes are measured there. [dashScale] is how much
     * bigger than written the dashes are in that space, which only a stroke keeping its width on screen needs.
     */
    private fun drawStroke(
        path: Path,
        outline: Outline,
        paint: Paint,
        view: PropView,
        style: Style,
        vp: Viewport,
        scale: Double,
        widthOnScreen: Double,
        lengthScale: Double,
        dashScale: Double,
    ) {
        val dash = dashEffect(path, outline, view, style, vp, lengthScale, dashScale)
        var dashes = dash?.count ?: 0.0
        var cost = strokeCost(outline, scale, widthOnScreen, dashes)
        if (dash != null && (dashes > MAX_DASHES || !canDrawInOneCall(cost))) {
            frame.noteLeftOut()
            dashes = 0.0
            cost = strokeCost(outline, scale, widthOnScreen, dashes)
        } else if (dash != null) {
            paint.pathEffect = dash.effect
        }
        if (!canDrawInOneCall(cost)) {
            frame.noteLeftOut()
            return
        }
        if (!frame.spend(cost)) return
        canvas.drawPath(path, paint)
    }

    /** A stroke is drawn by making the outline of it, with both sides and every join or dash, and scanning that as any other shape. */
    private fun strokeCost(outline: Outline, scale: Double, widthOnScreen: Double, dashes: Double): Long {
        val halfWidth = if (scale > 0.0) widthOnScreen / scale / 2.0 else 0.0
        val box = outline.drawnBox?.outset(halfWidth)
        val pieces = outline.segments + dashes
        val edges = minOf(outline.travel * scale, outline.segments * canvasDiagonal).takeIf { it.isFinite() } ?: 0.0
        val sides = 2.0 * edges + pieces * widthOnScreen.coerceAtMost(canvasDiagonal)
        val area = minOf(onScreenArea(box), (edges * widthOnScreen.coerceAtLeast(1.0)).coerceAtLeast(1.0))
        val crossings = sides * sides / sqrt(area) * WorkCost.CROSSING_DENSITY
        val total =
            pieces * WorkCost.OUTLINE_SEGMENT + dashes * WorkCost.DASH + sides * WorkCost.EDGE_PIXEL + crossings +
                area / WorkCost.PIXELS_PER_UNIT_PLAIN
        return if (total.isNaN()) {
            0L
        } else if (total >= MAX_COST) {
            MAX_COST.toLong()
        } else {
            total.toLong()
        }
    }

    private class Dashes(val effect: DashPathEffect, val count: Double)

    private fun dashEffect(
        path: Path,
        outline: Outline,
        view: PropView,
        style: Style,
        vp: Viewport,
        lengthScale: Double,
        dashScale: Double,
    ): Dashes? {
        val dashes = style.dashArray
        if (dashes.isEmpty()) return null
        frame.spend(dashes.size.toLong())
        val base = vp.diagonal
        var values = DoubleArray(dashes.size) { dashes[it].resolve(base, style.fontSize) }
        if (values.any { it < 0.0 } || values.all { it <= 0.0 }) return null
        // Android needs an even count, and SVG defines an odd list as repeated once.
        if (values.size % 2 != 0) values += values
        // pathLength says what the author measured the path as, and dashes are written in that measure.
        val declaredLength = view.number("pathLength")
        val scale = if (declaredLength != null && declaredLength > 0.0) measureLength(path) / declaredLength else 1.0
        val intervals = FloatArray(values.size) { (values[it] * scale * dashScale).toFloat() }
        val offset = style.dashOffset.resolve(base, style.fontSize) * scale * dashScale
        val repeats = outline.travel * lengthScale / intervals.sumOf { it.toDouble() }.coerceAtLeast(MIN_DASH_PATTERN)
        return Dashes(DashPathEffect(intervals, offset.toFloat()), repeats * intervals.size / 2)
    }

    private fun measureLength(path: Path): Double {
        frame.spend(WorkCost.BOX_MEASURED)
        val measure = PathMeasure(path, false)
        var total = 0.0
        do {
            total += measure.length
        } while (measure.nextContour())
        return total
    }

    private fun preparePaint(resolved: ResolvedPaint, opacity: Double, style: Style): Paint {
        val p = shapePaint
        p.reset()
        p.isAntiAlias = !style.crispEdges
        p.color = resolved.color.toArgb(opacity)
        p.shader = resolved.shader
        return p
    }

    private fun resolvePaint(value: PaintValue, style: Style, box: Box?, vp: Viewport): ResolvedPaint? =
        when (value) {
            PaintValue.None -> {
                null
            }

            PaintValue.Current -> {
                ResolvedPaint(style.color, null)
            }

            is PaintValue.Solid -> {
                ResolvedPaint(value.color, null)
            }

            PaintValue.ContextFill -> {
                contextFill?.let { resolvePaint(it, style, box, vp) }
            }

            PaintValue.ContextStroke -> {
                contextStroke?.let { resolvePaint(it, style, box, vp) }
            }

            is PaintValue.Ref -> {
                val server = value.id?.let { model.byId[it] }
                server?.let { paintServer(it, box, vp) } ?: value.fallback?.let { resolvePaint(it, style, box, vp) }
            }
        }

    private fun paintServer(server: SvgElement, box: Box?, vp: Viewport): ResolvedPaint? =
        when (server.tag) {
            "linearGradient", "radialGradient" -> gradient(server, box, vp)
            "pattern" -> pattern(server, box, vp)
            else -> null
        }

    /**
     * The element and those it inherits from through href. A loop in the chain just ends it, and so does a length no real file comes near.
     * Worked out once in each frame, since every shape painted with this needs it.
     */
    private fun referenceChain(el: SvgElement): List<SvgElement> {
        chainCache[el]?.let { return it }
        val chain = ArrayList<SvgElement>(2)
        val seen = HashSet<SvgElement>(4)
        var current: SvgElement? = el
        while (current != null && seen.add(current)) {
            if (chain.size >= model.limits.maxReferenceChain) {
                frame.noteLeftOut()
                break
            }
            chain += current
            current = targetOf(viewOf(current))
        }
        chainCache[el] = chain
        return chain
    }

    private fun attributeOf(chain: List<SvgElement>, name: String): SvgValue? {
        frame.spend(chain.size.toLong())
        for (element in chain) viewOf(element)[name]?.let { return it }
        return null
    }

    private fun keywordOf(chain: List<SvgElement>, name: String): String? = (attributeOf(chain, name) as? KeywordValue)?.text

    private fun lengthOf(chain: List<SvgElement>, name: String): Length? =
        when (val value = attributeOf(chain, name)) {
            is LengthValue -> value.length
            is LengthListValue -> value.items.firstOrNull()
            else -> null
        }

    private fun gradient(el: SvgElement, box: Box?, vp: Viewport): ResolvedPaint? {
        val chain = referenceChain(el)
        val objectUnits = keywordOf(chain, "gradientUnits") != "userSpaceOnUse"
        // Fractions of a box with no width or height have no meaning, so the specification treats the paint as unusable.
        if (objectUnits && (box == null || box.w <= 0.0 || box.h <= 0.0)) return null
        val stops = gradientStops(chain)
        if (stops.isEmpty()) return null
        if (stops.size == 1) return ResolvedPaint(stops[0].color, null)
        val fontSize = styleOf(el).fontSize
        // Android builds the colours again for every shape and every draw.
        frame.spend(stops.size * WorkCost.GRADIENT_STOP * if (styleOf(el).linearColors) LINEAR_STOP_FACTOR else 1L)
        val linearColors = styleOf(el).linearColors
        var matrix = Mat2D.IDENTITY
        if (objectUnits && box != null) matrix = Mat2D.translate(box.x, box.y) * Mat2D.scale(box.w, box.h)
        val gradientTransform = (attributeOf(chain, "gradientTransform") as? TransformValue)?.ops
        if (gradientTransform != null) matrix *= combine(gradientTransform, TransformContext(vp.w, vp.h, fontSize))
        val spread =
            when (keywordOf(chain, "spreadMethod")) {
                "reflect" -> Shader.TileMode.MIRROR
                "repeat" -> Shader.TileMode.REPEAT
                else -> Shader.TileMode.CLAMP
            }

        fun xOf(name: String, default: Length): Double =
            (lengthOf(chain, name) ?: default).let { if (objectUnits) it.fraction() else it.resolve(vp.w, fontSize) }

        fun yOf(name: String, default: Length): Double =
            (lengthOf(chain, name) ?: default).let { if (objectUnits) it.fraction() else it.resolve(vp.h, fontSize) }

        fun radiusOf(name: String, default: Length): Double =
            (lengthOf(chain, name) ?: default).let { if (objectUnits) it.fraction() else it.resolve(vp.diagonal, fontSize) }
        val half = Length(pct = 50.0)
        val shader: Shader =
            if (chain[0].tag == "linearGradient") {
                val x1 = xOf("x1", Length.ZERO)
                val y1 = yOf("y1", Length.ZERO)
                val x2 = xOf("x2", Length(pct = 100.0))
                val y2 = yOf("y2", Length.ZERO)
                // Per the specification, a gradient with no length paints with its last colour. Android only sees these as floats, and
                // what becomes infinite there gets refused, so a number too big for that is treated as having no usable length either.
                val noLength = x1.toFloat() == x2.toFloat() && y1.toFloat() == y2.toFloat()
                if (noLength || !fitsFloats(x1, y1, x2, y2)) return ResolvedPaint(stops.last().color, null)
                GradientShaders.linear(x1, y1, x2, y2, stops, spread, matrix, linearColors)
            } else {
                val cx = xOf("cx", half)
                val cy = yOf("cy", half)
                val r = radiusOf("r", half)
                val fx = lengthOf(chain, "fx")?.let { if (objectUnits) it.fraction() else it.resolve(vp.w, fontSize) } ?: cx
                val fy = lengthOf(chain, "fy")?.let { if (objectUnits) it.fraction() else it.resolve(vp.h, fontSize) } ?: cy
                val fr = radiusOf("fr", Length.ZERO).coerceAtLeast(0.0)
                // A radius can be above zero and still come out as zero in a float, which Android also refuses.
                if (r.toFloat() <= 0f || !fitsFloats(cx, cy, r, fx, fy, fr)) return ResolvedPaint(stops.last().color, null)
                if (GradientShaders.worksOutFocalByHand(cx, cy, fx, fy, fr)) frame.spend(WorkCost.FOCAL_GRADIENT_BY_HAND)
                GradientShaders.radial(cx, cy, r, fx, fy, fr, stops, spread, matrix, box, linearColors)
            }
        return ResolvedPaint(Rgba.BLACK, shader)
    }

    private fun fitsFloats(vararg values: Double): Boolean = values.all { it.toFloat().isFinite() }

    /** Worked out once in each frame. A long list of stops used by many shapes would otherwise be gone through for each of them. */
    private fun gradientStops(chain: List<SvgElement>): List<GradientStop> = stopsCache.getOrPut(chain[0]) { readStops(chain) }

    private fun readStops(chain: List<SvgElement>): List<GradientStop> {
        val owner = chain.firstOrNull { element -> element.children.any { it.tag == "stop" } } ?: return emptyList()
        val stops = ArrayList<GradientStop>()
        var previous = 0.0
        for (child in owner.children) {
            if (child.tag != "stop") continue
            if (!frame.visit(WorkCost.BOX_MEASURED)) break
            val view = viewOf(child)
            val style = styleOf(child)
            // Stops must never go backwards. One that does is moved up to the one before it, which draws a hard edge.
            val offset = (view.number("offset") ?: 0.0).coerceIn(0.0, 1.0).coerceAtLeast(previous)
            previous = offset
            val color =
                when (val value = view["stop-color"]) {
                    is PaintValue.Solid -> value.color
                    PaintValue.Current -> style.color
                    else -> Rgba.BLACK
                }
            val opacity = (view.number("stop-opacity") ?: 1.0).coerceIn(0.0, 1.0)
            stops += GradientStop(offset, color.copy(a = color.a * opacity))
        }
        val limit = model.limits.maxGradientStops
        if (stops.size <= limit) return stops
        // Thousands of colours across a shape cannot be told from fewer of them, as long as the two ends stay where they are.
        frame.noteLeftOut()
        return List(limit) { stops[(it.toLong() * (stops.size - 1) / (limit - 1)).toInt()] }
    }

    private fun pattern(el: SvgElement, box: Box?, vp: Viewport): ResolvedPaint? {
        if (!active.add(el)) return null
        try {
            val chain = referenceChain(el)
            val fontSize = styleOf(el).fontSize
            val objectUnits = keywordOf(chain, "patternUnits") != "userSpaceOnUse"
            if (objectUnits && (box == null || box.w <= 0.0 || box.h <= 0.0)) return null
            val xLength = lengthOf(chain, "x") ?: Length.ZERO
            val yLength = lengthOf(chain, "y") ?: Length.ZERO
            val wLength = lengthOf(chain, "width") ?: Length.ZERO
            val hLength = lengthOf(chain, "height") ?: Length.ZERO
            val tileX: Double
            val tileY: Double
            val tileW: Double
            val tileH: Double
            if (objectUnits && box != null) {
                tileX = box.x + xLength.fraction() * box.w
                tileY = box.y + yLength.fraction() * box.h
                tileW = wLength.fraction() * box.w
                tileH = hLength.fraction() * box.h
            } else {
                tileX = xLength.resolve(vp.w, fontSize)
                tileY = yLength.resolve(vp.h, fontSize)
                tileW = wLength.resolve(vp.w, fontSize)
                tileH = hLength.resolve(vp.h, fontSize)
            }
            if (tileW <= 0.0 || tileH <= 0.0) return null
            val owner = chain.firstOrNull { it.children.isNotEmpty() } ?: return null
            val transform =
                (attributeOf(chain, "patternTransform") as? TransformValue)?.ops?.let {
                    combine(it, TransformContext(vp.w, vp.h, fontSize))
                } ?: Mat2D.IDENTITY

            // The tile is drawn at the size it will appear, so patterns stay sharp when the picture is scaled up. Showing more than a whole
            // canvas of it at once has no use, and the tile goes on to be stretched to its size like any picture, so that much is enough.
            val onScreen = ctm * transform
            val scaleX = hypot(onScreen.a, onScreen.b).coerceAtLeast(1e-6)
            val scaleY = hypot(onScreen.c, onScreen.d).coerceAtLeast(1e-6)
            val largestSide = canvasDiagonal.toInt().coerceIn(MIN_TILE_LIMIT, MAX_TILE_SIZE)
            val pixelW = ceil(tileW * scaleX - 1e-6).toInt().coerceIn(1, largestSide)
            val pixelH = ceil(tileH * scaleY - 1e-6).toInt().coerceIn(1, largestSide)
            val contentInBox = viewBoxOf(chain) == null && keywordOf(chain, "patternContentUnits") == "objectBoundingBox" && box != null
            val key = TileKey(el, pixelW, pixelH, tileW, tileH, if (contentInBox) box else null, vp, contextFill, contextStroke)
            val bitmap = tiles[key] ?: drawTile(key, chain, owner, box, vp) ?: return null
            val shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            val local = transform * Mat2D.translate(tileX, tileY) * Mat2D.scale(tileW / pixelW, tileH / pixelH)
            shader.setLocalMatrix(local.toAndroid())
            return ResolvedPaint(Rgba.BLACK, shader)
        } catch (_: OutOfMemoryError) {
            // A tile too big for memory leaves the shape unpainted, and the rest of the picture still draws.
            frame.noteLeftOut()
            return null
        } finally {
            active -= el
        }
    }

    private fun viewBoxOf(chain: List<SvgElement>): List<Double>? = numbers(attributeOf(chain, "viewBox")).takeIf {
        it.size == 4 && it[2] > 0.0 && it[3] > 0.0
    }

    /** Draws one tile, and keeps it for the rest of the frame. Nothing is using the ones kept so far, so they can go to make room. */
    private fun drawTile(key: TileKey, chain: List<SvgElement>, owner: SvgElement, box: Box?, vp: Viewport): Bitmap? {
        val pixels = key.pixelW.toLong() * key.pixelH
        val bytes = pixels * FrameWork.BYTES_PER_PIXEL
        if (!frame.reserveRaster(bytes)) {
            release()
            if (!frame.reserveRaster(bytes)) {
                frame.noteLeftOut()
                return null
            }
        }
        var tile: Bitmap? = null
        var isKept = false
        try {
            if (!frame.spend(pixels / WorkCost.PIXELS_PER_UNIT_LAYER)) return null
            val bitmap = Bitmap.createBitmap(key.pixelW, key.pixelH, Bitmap.Config.ARGB_8888)
            tile = bitmap
            drawTileContent(bitmap, key, chain, owner, box, vp)
            tiles[key] = bitmap
            tileBytes += bytes
            isKept = true
            return bitmap
        } finally {
            if (!isKept) {
                frame.releaseRaster(bytes)
                tile?.recycle()
            }
        }
    }

    private fun drawTileContent(bitmap: Bitmap, key: TileKey, chain: List<SvgElement>, owner: SvgElement, box: Box?, vp: Viewport) {
        val toBitmap = Mat2D.scale(key.pixelW / key.tileW, key.pixelH / key.tileH)
        val viewBox = viewBoxOf(chain)
        // The content's origin sits on the tile's corner, so x and y shift the picture along with the tiles. A strict reading of the
        // specification would leave the content in place and only move the window, but every browser does this, and files expect it.
        val content =
            if (viewBox != null) {
                val aspect = aspectOf(keywordOf(chain, "preserveAspectRatio"))
                viewBoxTransform(viewBox[0], viewBox[1], viewBox[2], viewBox[3], 0.0, 0.0, key.tileW, key.tileH, aspect)
            } else if (keywordOf(chain, "patternContentUnits") == "objectBoundingBox" && box != null) {
                Mat2D.scale(box.w, box.h)
            } else {
                Mat2D.IDENTITY
            }
        val savedCanvas = canvas
        val savedCtm = ctm
        val savedFill = contextFill
        val savedStroke = contextStroke
        val savedPixels = canvasPixels
        val savedDiagonal = canvasDiagonal
        try {
            canvas = Canvas(bitmap)
            ctm = Mat2D.IDENTITY
            canvasPixels = key.pixelW.toLong() * key.pixelH
            canvasDiagonal = sqrt(canvasPixels * 2.0)
            concat(toBitmap * content)
            // A pattern is not a viewport of its own: percentages inside it still mean the picture's.
            drawChildren(owner, styleOf(owner), vp)
        } finally {
            canvas = savedCanvas
            ctm = savedCtm
            contextFill = savedFill
            contextStroke = savedStroke
            canvasPixels = savedPixels
            canvasDiagonal = savedDiagonal
        }
    }

    private fun drawMarkers(data: PathData, style: Style, vp: Viewport) {
        val start = style.markerStart?.let { model.byId[it] }?.takeIf { it.tag == "marker" }
        val mid = style.markerMid?.let { model.byId[it] }?.takeIf { it.tag == "marker" }
        val end = style.markerEnd?.let { model.byId[it] }?.takeIf { it.tag == "marker" }
        if (start == null && mid == null && end == null) return
        if (!frame.spend(data.commands.size * WorkCost.OUTLINE_SEGMENT)) return
        val vertices = markerVertices(data)
        if (vertices.isEmpty()) return
        val width = style.strokeWidth.resolve(vp.diagonal, style.fontSize)
        val savedFill = contextFill
        val savedStroke = contextStroke
        contextFill = style.fill
        contextStroke = style.stroke
        for ((index, vertex) in vertices.withIndex()) {
            if (frame.isStopped) break
            val isFirst = index == 0
            val isLast = index == vertices.lastIndex
            val marker = if (isFirst) {
                start
            } else if (isLast) {
                end
            } else {
                mid
            }
            if (marker != null) drawMarker(marker, vertex, width, isFirst, vp)
            // A single-point path gets both end markers on that one point.
            if (isFirst && isLast && end != null) drawMarker(end, vertex, width, false, vp)
        }
        contextFill = savedFill
        contextStroke = savedStroke
    }

    private fun drawMarker(marker: SvgElement, vertex: MarkerVertex, strokeWidth: Double, isStart: Boolean, vp: Viewport) {
        if (!active.add(marker)) return
        if (!frame.visit()) {
            active -= marker
            return
        }
        val view = viewOf(marker)
        val style = styleOf(marker)
        val width = (view.length("markerWidth") ?: Length(px = 3.0)).resolve(vp.w, style.fontSize)
        val height = (view.length("markerHeight") ?: Length(px = 3.0)).resolve(vp.h, style.fontSize)
        if (width > 0.0 && height > 0.0 && view.keyword("display") != "none") {
            val angle =
                when (val orient = view.keyword("orient")?.trim()) {
                    null, "" -> 0.0
                    "auto" -> vertex.angle
                    "auto-start-reverse" -> if (isStart) vertex.angle + 180.0 else vertex.angle
                    else -> parseAngle(orient.also { frame.spend(it.length.toLong()) }) ?: 0.0
                }
            val scale = if (view.keyword("markerUnits") == "userSpaceOnUse") 1.0 else strokeWidth
            val viewBox = numbers(view["viewBox"]).takeIf { it.size == 4 && it[2] > 0.0 && it[3] > 0.0 }
            val inner =
                if (viewBox != null) {
                    viewBoxTransform(
                        viewBox[0],
                        viewBox[1],
                        viewBox[2],
                        viewBox[3],
                        0.0,
                        0.0,
                        width,
                        height,
                        aspectOf(view.keyword("preserveAspectRatio")),
                    )
                } else {
                    Mat2D.IDENTITY
                }
            val refX = (view.length("refX") ?: Length.ZERO).resolve(vp.w, style.fontSize)
            val refY = (view.length("refY") ?: Length.ZERO).resolve(vp.h, style.fontSize)
            val anchorX = inner.mapX(refX, refY)
            val anchorY = inner.mapY(refX, refY)
            val savedCtm = ctm
            val count = canvas.save()
            concat(
                Mat2D.translate(vertex.x, vertex.y) * Mat2D.rotate(angle) * Mat2D.scale(scale, scale) *
                    Mat2D.translate(-anchorX, -anchorY),
            )
            drawWithEffects(marker, view, style, vp) {
                val overflow = view.keyword("overflow")
                if (overflow != "visible" && overflow != "auto") canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
                concat(inner)
                // A marker is not a viewport of its own: percentages inside it still mean the picture's.
                drawChildren(marker, style, vp)
            }
            canvas.restoreToCount(count)
            ctm = savedCtm
        }
        active -= marker
    }

    private fun geometry(el: SvgElement, view: PropView, style: Style, vp: Viewport): Geometry? {
        val fontSize = style.fontSize

        fun x(name: String): Double = view.length(name)?.resolve(vp.w, fontSize) ?: 0.0

        fun y(name: String): Double = view.length(name)?.resolve(vp.h, fontSize) ?: 0.0
        return when (el.tag) {
            "rect" -> {
                rectGeometry(view, vp, fontSize)
            }

            "circle" -> {
                val r = view.length("r")?.resolve(vp.diagonal, fontSize) ?: 0.0
                if (r <= 0.0) return null
                val cx = x("cx")
                val cy = y("cy")
                ovalGeometry(cx, cy, r, r)
            }

            "ellipse" -> {
                val rxLength = view.length("rx")
                val ryLength = view.length("ry")
                val rx = rxLength?.resolve(vp.w, fontSize)
                val ry = ryLength?.resolve(vp.h, fontSize)
                // SVG 2: one radius left out means the other is used for both.
                val finalRx = rx ?: ry ?: 0.0
                val finalRy = ry ?: rx ?: 0.0
                if (finalRx <= 0.0 || finalRy <= 0.0) return null
                ovalGeometry(x("cx"), y("cy"), finalRx, finalRy)
            }

            "line" -> {
                val data = PathData.polyline(listOf(x("x1"), y("y1"), x("x2"), y("y2")), closed = false)
                Geometry(data)
            }

            "polyline", "polygon" -> {
                val value = view["points"] as? LengthListValue ?: return null
                val closed = el.tag == "polygon"
                val data =
                    if (view.isAnimated) {
                        PathData.polyline(numbers(value), closed)
                    } else {
                        model.polyline(value, closed) { numbers(value) }
                    }
                if (data.isEmpty) return null
                frame.spend(data.commands.size * WorkCost.SEGMENT)
                Geometry(data)
            }

            "path" -> {
                val data = (view["d"] as? PathValue)?.data ?: return null
                if (data.isEmpty) return null
                frame.spend(data.commands.size * WorkCost.SEGMENT)
                Geometry(data)
            }

            else -> {
                null
            }
        }
    }

    private fun ovalGeometry(cx: Double, cy: Double, rx: Double, ry: Double): Geometry {
        val path = Path()
        // Android's oval also begins on the right and runs clockwise, as SVG asks, so dash patterns land where they should.
        path.addOval(RectF((cx - rx).toFloat(), (cy - ry).toFloat(), (cx + rx).toFloat(), (cy + ry).toFloat()), Path.Direction.CW)
        return Geometry(path, Box(cx - rx, cy - ry, 2 * rx, 2 * ry), null, ROUNDED_SHAPE_SEGMENTS, 4.0 * (rx + ry))
    }

    private fun rectGeometry(view: PropView, vp: Viewport, fontSize: Double): Geometry? {
        val w = view.length("width")?.resolve(vp.w, fontSize) ?: 0.0
        val h = view.length("height")?.resolve(vp.h, fontSize) ?: 0.0
        if (w <= 0.0 || h <= 0.0) return null
        val x = view.length("x")?.resolve(vp.w, fontSize) ?: 0.0
        val y = view.length("y")?.resolve(vp.h, fontSize) ?: 0.0
        // A negative radius is invalid, which is the same as not having one.
        val rxGiven = view.length("rx")?.resolve(vp.w, fontSize)?.takeIf { it >= 0.0 }
        val ryGiven = view.length("ry")?.resolve(vp.h, fontSize)?.takeIf { it >= 0.0 }
        val rx = (rxGiven ?: ryGiven ?: 0.0).coerceIn(0.0, w / 2)
        val ry = (ryGiven ?: rxGiven ?: 0.0).coerceIn(0.0, h / 2)
        val path = Path()
        val left = x.toFloat()
        val top = y.toFloat()
        val right = (x + w).toFloat()
        val bottom = (y + h).toFloat()
        if (rx <= 0.0 || ry <= 0.0) {
            path.moveTo(left, top)
            path.lineTo(right, top)
            path.lineTo(right, bottom)
            path.lineTo(left, bottom)
            path.close()
        } else {
            // Written out, with arcs, rather than with addRoundRect: dashes must begin where SVG says, at the top left corner's end.
            val rxf = rx.toFloat()
            val ryf = ry.toFloat()
            path.moveTo(left + rxf, top)
            path.lineTo(right - rxf, top)
            path.arcTo(RectF(right - 2 * rxf, top, right, top + 2 * ryf), -90f, 90f, false)
            path.lineTo(right, bottom - ryf)
            path.arcTo(RectF(right - 2 * rxf, bottom - 2 * ryf, right, bottom), 0f, 90f, false)
            path.lineTo(left + rxf, bottom)
            path.arcTo(RectF(left, bottom - 2 * ryf, left + 2 * rxf, bottom), 90f, 90f, false)
            path.lineTo(left, top + ryf)
            path.arcTo(RectF(left, top, left + 2 * rxf, top + 2 * ryf), 180f, 90f, false)
            path.close()
        }
        return Geometry(path, Box(x, y, w, h), null, ROUNDED_SHAPE_SEGMENTS, 2.0 * (w + h))
    }

    /** The box an element takes up in its own user space, before its own transform. Animated values make this differ per frame. */
    private fun elementBox(el: SvgElement, view: PropView, style: Style, vp: Viewport): Box? {
        val key = BoxKey(el, style, vp)
        if (boxCache.containsKey(key)) return boxCache[key]
        if (!frame.visit(WorkCost.BOX_MEASURED) || !frame.enter()) return null
        val box =
            try {
                when (el.tag) {
                    "g", "a" -> childrenBox(el, style, vp)
                    "switch" -> firstPassingChild(el)?.let { unionChild(null, it, style, vp) }
                    "svg", "symbol" -> viewportBox(el, view, style, vp, null, null)
                    "use" -> useBox(el, view, style, vp)
                    "text" -> textBox(el, view, style, vp)
                    "image" -> imageBox(el, view, style, vp)
                    else -> geometry(el, view, style, vp)?.box
                }
            } finally {
                frame.leave()
            }
        // An animated style is a new object for every use of an element, so this can hold a great many entries nobody will ever ask for.
        if (boxCache.size >= MAX_CACHED_BOXES) boxCache.clear()
        boxCache[key] = box
        return box
    }

    private fun textBox(el: SvgElement, view: PropView, style: Style, vp: Viewport): Box? {
        val layout = layoutText(el, view, style, vp)
        layout.release()
        return layout.box
    }

    private fun childrenBox(el: SvgElement, style: Style, vp: Viewport): Box? {
        if (!active.add(el)) return null
        var union: Box? = null
        for (child in el.children) {
            if (frame.isStopped) break
            if (child.tag in DRAWN_TAGS && child.conditionsPass) union = unionChild(union, child, style, vp)
        }
        active -= el
        return union
    }

    private fun unionChild(union: Box?, child: SvgElement, style: Style, vp: Viewport): Box? {
        val view = viewFor(child, style)
        if (view.keyword("display") == "none") return union
        val childStyle = styleFrom(style, view)
        val box = elementBox(child, view, childStyle, vp) ?: return union
        val local = localTransform(child, view, childStyle, vp, null)
        val mapped = if (local != null) box.transformed(local) else box
        return union?.union(mapped) ?: mapped
    }

    private fun viewportBox(el: SvgElement, view: PropView, style: Style, vp: Viewport, overrideW: Length?, overrideH: Length?): Box? {
        val x = view.length("x")?.resolve(vp.w, style.fontSize) ?: 0.0
        val y = view.length("y")?.resolve(vp.h, style.fontSize) ?: 0.0
        val w = (overrideW ?: view.length("width") ?: Length(pct = 100.0)).resolve(vp.w, style.fontSize)
        val h = (overrideH ?: view.length("height") ?: Length(pct = 100.0)).resolve(vp.h, style.fontSize)
        if (w <= 0.0 || h <= 0.0) return null
        val viewBox = numbers(view["viewBox"]).takeIf { it.size == 4 && it[2] > 0.0 && it[3] > 0.0 }
        val inner = viewBoxOr(viewBox, view, x, y, w, h)
        val newViewport = if (viewBox != null) Viewport(viewBox[2], viewBox[3]) else Viewport(w, h)
        return childrenBox(el, style, newViewport)?.transformed(inner)
    }

    private fun useBox(el: SvgElement, view: PropView, style: Style, vp: Viewport): Box? {
        val target = targetOf(view) ?: return null
        if (target in active || !active.add(el)) return null
        val targetView = viewFor(target, style)
        val targetStyle = styleFrom(style, targetView)
        val inner =
            when {
                targetView.keyword("display") == "none" -> {
                    null
                }

                target.tag == "symbol" || target.tag == "svg" -> {
                    viewportBox(target, targetView, targetStyle, vp, view.length("width"), view.length("height"))
                }

                else -> {
                    elementBox(target, targetView, targetStyle, vp)
                }
            }
        active -= el
        val box = inner ?: return null
        val local = localTransform(target, targetView, targetStyle, vp, null)
        return if (local != null) box.transformed(local) else box
    }

    private fun imageBox(el: SvgElement, view: PropView, style: Style, vp: Viewport): Box? {
        val size = imageSize(el, view, style, vp) ?: return null
        val x = view.length("x")?.resolve(vp.w, style.fontSize) ?: 0.0
        val y = view.length("y")?.resolve(vp.h, style.fontSize) ?: 0.0
        return Box(x, y, size.first, size.second)
    }

    private fun imageSize(el: SvgElement, view: PropView, style: Style, vp: Viewport): Pair<Double, Double>? {
        val image = el.image ?: return null
        val width = view.length("width")?.resolve(vp.w, style.fontSize)
        val height = view.length("height")?.resolve(vp.h, style.fontSize)
        val intrinsicW: Double
        val intrinsicH: Double
        val nested = image.nested
        if (nested != null) {
            intrinsicW = nested.width.toDouble()
            intrinsicH = nested.height.toDouble()
        } else {
            if (!model.resources.images.sizeOf(image)) return null
            intrinsicW = image.intrinsicWidth.toDouble()
            intrinsicH = image.intrinsicHeight.toDouble()
        }
        val w = width ?: if (height != null && intrinsicH > 0) height * intrinsicW / intrinsicH else intrinsicW
        val h = height ?: if (width != null && intrinsicW > 0) width * intrinsicH / intrinsicW else intrinsicH
        return if (w > 0.0 && h > 0.0) w to h else null
    }

    private fun drawImage(el: SvgElement, view: PropView, style: Style, vp: Viewport, alpha: Double) {
        if (!style.visible) return
        val image = el.image ?: return
        val (w, h) = imageSize(el, view, style, vp) ?: return
        val x = view.length("x")?.resolve(vp.w, style.fontSize) ?: 0.0
        val y = view.length("y")?.resolve(vp.h, style.fontSize) ?: 0.0
        val aspect = aspectOf(view.keyword("preserveAspectRatio"))
        val nested = image.nested
        val savedCtm = ctm
        val count = canvas.save()
        // With `slice` the image is bigger than its box on purpose, and this is what crops it.
        canvas.clipRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
        if (nested != null) {
            val nestedW = nested.width.toDouble()
            val nestedH = nested.height.toDouble()
            if (nestedW > 0.0 && nestedH > 0.0) {
                concat(viewBoxTransform(0.0, 0.0, nestedW, nestedH, x, y, w, h, aspect))
                val layerBytes = reserveLayers(if (alpha < 1.0) 1 else 0, null)
                if (layerBytes >= 0L) {
                    try {
                        if (alpha < 1.0) canvas.saveLayerAlpha(null, alphaByte(alpha))
                        // An SVG image inside another SVG plays on the clock of the outer picture, and spends from the same frame.
                        nested.drawNested(canvas, time, RectF(0f, 0f, nestedW.toFloat(), nestedH.toFloat()), frame)
                    } finally {
                        frame.releaseRaster(layerBytes)
                    }
                }
            }
        } else {
            val bitmap = model.resources.images.bitmapOf(image, frame)
            if (bitmap != null) {
                frame.spend((onScreenArea(Box(x, y, w, h)) / WorkCost.SHADER_PIXELS_PER_UNIT).toLong())
                val iw = bitmap.width.toDouble()
                val ih = bitmap.height.toDouble()
                concat(viewBoxTransform(0.0, 0.0, iw, ih, x, y, w, h, aspect))
                val paint = Paint()
                paint.alpha = alphaByte(alpha)
                paint.isFilterBitmap = !style.pixelatedImages
                paint.isAntiAlias = true
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
            }
        }
        canvas.restoreToCount(count)
        ctm = savedCtm
    }

    private fun drawText(el: SvgElement, view: PropView, style: Style, vp: Viewport, alpha: Double) {
        val layout = layoutText(el, view, style, vp)
        for (decoration in layout.decorations) {
            if (!decoration.isOverText) drawDecoration(decoration, layout.box, view, vp, alpha)
        }
        for (run in layout.runs) {
            if (frame.isStopped) break
            if (!run.style.visible) continue
            val path = run.path
            if (path == null) {
                drawGlyphsDirectly(run, alpha)
            } else {
                val glyphs = run.text.length
                val outline =
                    Outline(
                        path,
                        layout.box,
                        glyphs * SEGMENTS_PER_GLYPH,
                        glyphs * run.style.fontSize * EDGE_PER_GLYPH_IN_EMS,
                        run.cell,
                    )
                drawTextOutline(outline, view, run.style, vp, alpha)
            }
        }
        for (decoration in layout.decorations) {
            if (decoration.isOverText) drawDecoration(decoration, layout.box, view, vp, alpha)
        }
        layout.release()
    }

    private fun drawDecoration(decoration: PlacedDecoration, box: Box?, view: PropView, vp: Viewport, alpha: Double) {
        if (frame.isStopped) return
        val line = decoration.line
        drawTextOutline(
            Outline(decoration.path, box, DECORATION_SEGMENTS, 2.0 * (line.w + line.h), line),
            view,
            decoration.style,
            vp,
            alpha,
        )
    }

    private fun drawTextOutline(outline: Outline, view: PropView, style: Style, vp: Viewport, alpha: Double) {
        if (!style.visible) return
        for (step in style.paintOrder) {
            when (step) {
                Style.PAINT_FILL -> fillPath(outline, style, vp, alpha)
                Style.PAINT_STROKE -> strokePath(outline, view, style, vp, alpha)
            }
        }
    }

    /** Colour emoji have no outline, so they cannot go through the path route and are drawn as glyphs, with their own colours. */
    private fun drawGlyphsDirectly(run: PlacedRun, alpha: Double) {
        if (!frame.spend(run.text.length * WorkCost.PLACED_GLYPH)) return
        val paint = TextSupport.paintFor(run.style, run.letterSpacingEm)
        paint.color = run.style.color.toArgb(alpha)
        val scale = run.style.fontSize / TextSupport.NOMINAL_SIZE
        val count = canvas.save()
        canvas.translate(run.originX.toFloat(), run.originY.toFloat())
        if (run.isSideways) canvas.rotate(SIDEWAYS_DEGREES)
        canvas.scale((scale * run.stretch).toFloat(), scale.toFloat())
        canvas.drawText(run.text, 0f, 0f, paint)
        canvas.restoreToCount(count)
    }

    private fun layoutText(el: SvgElement, view: PropView, style: Style, vp: Viewport): TextLayout =
        TextLayouter(
            model.glyphs,
            frame,
            vp,
            { child, parent -> viewFor(child, parent) },
            { path -> textPathOf(path, vp) },
        ).layout(el, view, style)

    private fun textPathOf(view: PropView, vp: Viewport): TextOnPath? {
        val target = targetOf(view)?.takeIf { it.tag == "path" } ?: return null
        val targetView = viewOf(target)
        val data = (targetView["d"] as? PathValue)?.data?.takeUnless { it.isEmpty } ?: return null
        frame.spend(data.commands.size * WorkCost.OUTLINE_SEGMENT)
        // A transform on the path it follows counts, unlike with animateMotion.
        val matrix = localTransform(target, targetView, styleOf(target), vp, null) ?: Mat2D.IDENTITY
        return TextOnPath(model.motionMeasure(data), matrix)
    }

    private companion object {
        val BASE_STYLE = Style()
        const val SIDEWAYS_DEGREES = 90f
        const val MAX_TILE_SIZE = 4096

        /** A small canvas still has small details to get right in its tiles. */
        const val MIN_TILE_LIMIT = 256
        const val MAX_DASHES = 100_000.0

        /** A round or square end reaches further than half the stroke's width, and a corner goes by the miter limit. */
        const val MIN_STROKE_REACH = 1.5
        const val LAYER_MARGIN_PIXELS = 2.0
        const val MIN_SCALE_FOR_MARGIN = 1e-6
        const val MIN_DASH_PATTERN = 1e-30
        const val MAX_COST = 1e15

        /** About five seconds on a desktop, and much the longest any one drawing call may take. No real shape comes near it. */
        const val MAX_ONE_CALL = 500_000_000L
        const val MAX_COMBINED_SEGMENTS = 512
        const val MAX_CACHED_CLIPS = 256
        const val MAX_CACHED_BOXES = 65_536

        /** How many stops a linear light gradient turns each of its own into, near enough. */
        const val LINEAR_STOP_FACTOR = 32L
        const val ROUNDED_SHAPE_SEGMENTS = 8
        const val DECORATION_SEGMENTS = 4

        /** What one letter comes to, on average, as an outline. These only count where a whole text is being priced. */
        const val SEGMENTS_PER_GLYPH = 24
        const val EDGE_PER_GLYPH_IN_EMS = 4.0
        val LUMINANCE_TO_ALPHA =
            ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0.2125f,
                        0.7154f,
                        0.0721f,
                        0f,
                        0f,
                    ),
                ),
            )
    }
}

internal class MarkerVertex(val x: Double, val y: Double, val angle: Double)

/** Finds where markers go. Each one bisects the directions of the segments on either side of it, as the specification says. */
internal fun markerVertices(data: PathData): List<MarkerVertex> {
    val sink = MarkerSink()
    data.walk(sink)
    return sink.finish()
}

private class MarkerSink : PathSink {
    private class Vertex(val x: Double, val y: Double, var inX: Double, var inY: Double, var outX: Double, var outY: Double)

    private val vertices = ArrayList<Vertex>()
    private var x = 0.0
    private var y = 0.0
    private var startX = 0.0
    private var startY = 0.0
    private var subpathStart = 0
    private var inArc = false
    private var arcOutX = 0.0
    private var arcOutY = 0.0
    private var arcFirst = true

    override fun moveTo(x: Double, y: Double) {
        this.x = x
        this.y = y
        startX = x
        startY = y
        subpathStart = vertices.size
        vertices += Vertex(x, y, 0.0, 0.0, 0.0, 0.0)
    }

    private fun segment(outX: Double, outY: Double, inX: Double, inY: Double, endX: Double, endY: Double) {
        if (inArc) {
            if (arcFirst) {
                setOut(outX, outY)
                arcFirst = false
            }
            arcOutX = inX
            arcOutY = inY
            x = endX
            y = endY
            return
        }
        setOut(outX, outY)
        vertices += Vertex(endX, endY, inX, inY, 0.0, 0.0)
        x = endX
        y = endY
    }

    private fun setOut(outX: Double, outY: Double) {
        val last = vertices.lastOrNull() ?: return
        last.outX = outX
        last.outY = outY
    }

    override fun lineTo(x: Double, y: Double) {
        val dx = x - this.x
        val dy = y - this.y
        segment(dx, dy, dx, dy, x, y)
    }

    override fun quadTo(x1: Double, y1: Double, x: Double, y: Double) {
        val startDx = if (x1 != this.x || y1 != this.y) x1 - this.x else x - this.x
        val startDy = if (x1 != this.x || y1 != this.y) y1 - this.y else y - this.y
        val endDx = if (x != x1 || y != y1) x - x1 else x - this.x
        val endDy = if (x != x1 || y != y1) y - y1 else y - this.y
        segment(startDx, startDy, endDx, endDy, x, y)
    }

    override fun cubicTo(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double) {
        var startDx = x1 - this.x
        var startDy = y1 - this.y
        if (startDx == 0.0 && startDy == 0.0) {
            startDx = x2 - this.x
            startDy = y2 - this.y
        }
        if (startDx == 0.0 && startDy == 0.0) {
            startDx = x - this.x
            startDy = y - this.y
        }
        var endDx = x - x2
        var endDy = y - y2
        if (endDx == 0.0 && endDy == 0.0) {
            endDx = x - x1
            endDy = y - y1
        }
        if (endDx == 0.0 && endDy == 0.0) {
            endDx = x - this.x
            endDy = y - this.y
        }
        segment(startDx, startDy, endDx, endDy, x, y)
    }

    override fun close() {
        val dx = startX - x
        val dy = startY - y
        if (dx != 0.0 || dy != 0.0) {
            setOut(dx, dy)
            vertices += Vertex(startX, startY, dx, dy, 0.0, 0.0)
        }
        // A closed shape's end and start are the same point, so each takes the other's direction for its missing side.
        val first = vertices.getOrNull(subpathStart)
        val last = vertices.lastOrNull()
        if (first != null && last != null && first !== last) {
            first.inX = last.inX
            first.inY = last.inY
            last.outX = first.outX
            last.outY = first.outY
        }
        x = startX
        y = startY
    }

    override fun arcStart() {
        inArc = true
        arcFirst = true
    }

    override fun arcEnd() {
        inArc = false
        vertices += Vertex(x, y, arcOutX, arcOutY, 0.0, 0.0)
    }

    fun finish(): List<MarkerVertex> =
        vertices.map { vertex ->
            val hasIn = vertex.inX != 0.0 || vertex.inY != 0.0
            val hasOut = vertex.outX != 0.0 || vertex.outY != 0.0
            val angle =
                when {
                    hasIn && hasOut -> {
                        // Halfway between the two angles, the short way round. Doubling straight back has no short way, and goes as browsers do.
                        var angleIn = Math.toDegrees(atan2(vertex.inY, vertex.inX))
                        val angleOut = Math.toDegrees(atan2(vertex.outY, vertex.outX))
                        val turn = angleIn - angleOut
                        if (turn <= -180.0 || turn > 180.0) angleIn += 360.0
                        (angleIn + angleOut) / 2.0
                    }

                    hasOut -> {
                        Math.toDegrees(atan2(vertex.outY, vertex.outX))
                    }

                    hasIn -> {
                        Math.toDegrees(atan2(vertex.inY, vertex.inX))
                    }

                    else -> {
                        0.0
                    }
                }
            MarkerVertex(vertex.x, vertex.y, angle)
        }
}
