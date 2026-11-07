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

/** A shape ready to paint. [data] is only present for shapes that can carry markers. */
private class Geometry(val path: Path, val box: Box?, val data: PathData?)

private class ResolvedPaint(val color: Rgba, val shader: Shader?)

/**
 * Draws a document at one moment. One instance lives for one call to render. Everything it computes is a function of the document
 * and the time, so the same time always gives the same picture, in any order of frames.
 */
internal class SvgRenderer(private val model: SvgModel, private var canvas: Canvas, private val time: Double) {
    private var budget = model.limits.maxRenderedNodes

    /** True if this render stopped early, so the caller can tell the picture may be incomplete. */
    val isIncomplete: Boolean get() = budget < 0

    /** The elements being drawn right now. Finding one again means a reference loop, which is cut rather than followed forever. */
    private val active = HashSet<SvgElement>()
    private val clipStack = HashSet<SvgElement>()
    private val viewCache = HashMap<SvgElement, PropView>()
    private val styleCache = HashMap<SvgElement, Style>()
    private val boxCache = HashMap<BoxKey, Box?>()

    /** Tracked here because Canvas.getMatrix is deprecated and unreliable on hardware canvases. Only used to size pattern tiles. */
    private var ctm = Mat2D.IDENTITY
    private var contextFill: PaintValue? = null
    private var contextStroke: PaintValue? = null
    private val shapePaint = Paint()

    private data class BoxKey(val el: SvgElement, val style: Style, val vp: Viewport)

    fun render(dest: RectF) {
        val root = model.root
        val base = BASE_STYLE
        val view = viewFor(root, base)
        if (view.keyword("display") == "none") return
        val style = deriveStyle(base, view)
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
                PreserveAspect.parse(view.keyword("preserveAspectRatio")),
            )
        // Here `overflow` on the outermost element only decides whether a window scrolls, and what is outside its box is never painted.
        canvas.clipRect(dest)
        concat(mapping)
        val vp = Viewport(vbW, vbH)
        // The outermost element is the one place whose default origin is its own centre, because it has a CSS box.
        val local = localTransform(root, view, style, vp, defaultOriginAtCentre = Box(vbX, vbY, vbW, vbH))
        withMatrix(local) {
            drawWithEffects(root, view, style, vp) { drawContainer(root, style, vp) }
        }
    }

    private fun numbers(value: SvgValue?): List<Double> =
        when (value) {
            is LengthListValue -> value.items.map { it.px }
            else -> emptyList()
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
        val ordered = if (tracks.size > 1) tracks.sortedWith(Track.stackOrder(time)) else tracks
        for (track in ordered) {
            val next = track.evaluate(time, value, current) ?: continue
            value = next
            changed = true
        }
        if (changed && value != null) overlay[property] = value
    }

    private fun baseValue(el: SvgElement, property: String, parent: Style): SvgValue? {
        val own = el.props[property]
        if (own != null && own !== InheritValue && own !== InitialValue) return own
        if (own !== InitialValue && PROPERTIES[property]?.inherited == true) {
            parent.inheritedValue(property)?.let { return it }
        }
        return initialValueOf(property)
    }

    private fun viewOf(el: SvgElement): PropView = viewCache.getOrPut(el) { viewFor(el, el.parent?.let(::styleOf) ?: BASE_STYLE) }

    /**
     * The style an element has by its place in the file. Gradients, masks, patterns and markers are drawn from wherever they are
     * referenced, but they inherit from where they are written.
     */
    private fun styleOf(el: SvgElement): Style =
        styleCache.getOrPut(el) { deriveStyle(el.parent?.let(::styleOf) ?: BASE_STYLE, viewOf(el)) }

    private fun targetOf(view: PropView): SvgElement? {
        val href = view.keyword("href")?.trim() ?: return null
        return if (href.startsWith("#")) model.byId[href.substring(1)] else null
    }

    private fun drawChildren(el: SvgElement, style: Style, vp: Viewport) {
        for (child in el.children) drawElement(child, style, vp)
    }

    private fun drawElement(el: SvgElement, parent: Style, vp: Viewport) {
        if (el.tag !in DRAWN_TAGS || !el.conditionsPass) return
        if (--budget < 0) return
        val view = viewFor(el, parent)
        if (view.keyword("display") == "none") return
        val style = deriveStyle(parent, view)
        withMatrix(localTransform(el, view, style, vp, null)) {
            drawWithEffects(el, view, style, vp) { alpha -> drawBody(el, view, style, vp, alpha) }
        }
    }

    private fun drawBody(el: SvgElement, view: PropView, style: Style, vp: Viewport, alpha: Double) {
        when (el.tag) {
            "g", "a" -> drawContainer(el, style, vp)
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

    private fun firstPassingChild(el: SvgElement): SvgElement? = el.children.firstOrNull { it.tag in DRAWN_TAGS && it.conditionsPass }

    private fun localTransform(el: SvgElement, view: PropView, style: Style, vp: Viewport, defaultOriginAtCentre: Box?): Mat2D? {
        val transform = view.transform("transform")
        val translate = view.transform("translate")
        val rotate = view.transform("rotate")
        val scale = view.transform("scale")
        val motion = view.transform(MOTION_PROPERTY)
        val hasOwn = !transform.isNullOrEmpty() || !translate.isNullOrEmpty() || !rotate.isNullOrEmpty() || !scale.isNullOrEmpty()
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
            if (transform != null) own *= combine(transform, context)
            own *= Mat2D.translate(-ox, -oy)
            result *= own
        }
        if (useShift != null) result *= useShift
        return result
    }

    private fun strokeWidthOf(style: Style, vp: Viewport): Double =
        if (style.stroke === PaintValue.None) 0.0 else style.strokeWidth.resolve(vp.diagonal, style.fontSize)

    /** Applies opacity, clip and mask around [body], in the order the specification asks for. */
    private fun drawWithEffects(el: SvgElement, view: PropView, style: Style, vp: Viewport, body: (Double) -> Unit) {
        val opacity = (view.number("opacity") ?: 1.0).coerceIn(0.0, 1.0)
        if (opacity <= 0.0) return
        val clipText = view.keyword("clip-path")
        val clipId = clipText?.let(::urlReference)
        val shape = if (clipText != null && clipId == null) model.basicShape(clipText) else null
        val maskElement = view
            .keyword("mask")
            ?.let(::urlReference)
            ?.let { model.byId[it] }
            ?.takeIf { it.tag == "mask" && it !in active }
        val clip = clipId?.let { model.byId[it] }?.takeIf { it.tag == "clipPath" }
        if (clip == null && shape == null && maskElement == null && opacity >= 1.0) {
            body(1.0)
            return
        }
        val count = canvas.save()
        val box by lazy(LazyThreadSafetyMode.NONE) { elementBox(el, view, style, vp) }
        if (clip != null) {
            val path = clipPathOf(clip, vp) { box }
            if (path != null) canvas.clipPath(path)
        }
        if (shape != null) {
            val reference =
                when (shape.box) {
                    BasicShape.FILL_BOX -> box
                    BasicShape.VIEW_BOX -> Box(0.0, 0.0, vp.w, vp.h)
                    else -> box?.outset(strokeWidthOf(style, vp) / 2.0)
                } ?: Box(0.0, 0.0, 0.0, 0.0)
            canvas.clipPath(shape.path(reference, style.fontSize))
        }
        // A shape painted only once does not need a layer to composite as one, which saves a full-screen buffer per element.
        val directAlpha = maskElement == null && opacity < 1.0 && paintsOnce(el, style)
        if (opacity < 1.0 && !directAlpha) canvas.saveLayerAlpha(null, alphaByte(opacity))
        if (maskElement != null) canvas.saveLayer(null, null)
        body(if (directAlpha) opacity else 1.0)
        if (maskElement != null) applyMask(maskElement, box, vp)
        canvas.restoreToCount(count)
    }

    private fun alphaByte(opacity: Double): Int = (opacity * 255.0 + 0.5).toInt().coerceIn(0, 255)

    private fun paintsOnce(el: SvgElement, style: Style): Boolean {
        if (el.tag !in SHAPE_TAGS) return false
        if (el.tag in MARKABLE_TAGS && (style.markerStart != null || style.markerMid != null || style.markerEnd != null)) return false
        return style.fill === PaintValue.None || style.stroke === PaintValue.None
    }

    /** The outline a clipPath allows, in the user space of the element it clips. Null when the reference is unusable. */
    private fun clipPathOf(clip: SvgElement, vp: Viewport, box: () -> Box?): Path? {
        if (!clipStack.add(clip)) return null
        val view = viewOf(clip)
        val style = styleOf(clip)
        var matrix = localTransform(clip, view, style, vp, null) ?: Mat2D.IDENTITY
        if (view.keyword("clipPathUnits") == "objectBoundingBox") {
            val bounds = box()
            if (bounds == null) {
                clipStack -= clip
                return Path()
            }
            matrix = matrix * Mat2D.translate(bounds.x, bounds.y) * Mat2D.scale(bounds.w, bounds.h)
        }
        var out: Path? = null
        for (child in clip.children) {
            val path = clipChildPath(child, style, vp) ?: continue
            val merged = out
            if (merged == null) {
                out = path
            } else {
                merged.op(path, Path.Op.UNION)
            }
        }
        val result = out ?: Path()
        if (!matrix.isIdentity) result.transform(matrix.toAndroid())
        // A clipPath can itself be clipped, and the two then intersect.
        val outer = view
            .keyword("clip-path")
            ?.let(::urlReference)
            ?.let { model.byId[it] }
            ?.takeIf { it.tag == "clipPath" }
        if (outer != null) clipPathOf(outer, vp, box)?.let { result.op(it, Path.Op.INTERSECT) }
        clipStack -= clip
        return result
    }

    private fun clipChildPath(child: SvgElement, parent: Style, vp: Viewport): Path? {
        if (child.tag != "use" && child.tag != "text" && child.tag !in SHAPE_TAGS) return null
        if (!child.conditionsPass) return null
        val view = viewFor(child, parent)
        if (view.keyword("display") == "none") return null
        val style = deriveStyle(parent, view)
        if (!style.visible) return null
        val path: Path
        when (child.tag) {
            "use" -> {
                val target = targetOf(view) ?: return null
                if (target.tag != "text" && target.tag !in SHAPE_TAGS) return null
                path = clipChildPath(target, style, vp) ?: return null
            }

            "text" -> {
                path = Path()
                for (run in layoutText(child, view, style, vp).runs) run.path?.let { path.addPath(it) }
            }

            else -> {
                val geometry = geometry(child, view, style, vp) ?: return null
                path = Path(geometry.path)
            }
        }
        if (child.tag != "use") path.fillType = if (style.clipEvenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        val local = localTransform(child, view, style, vp, null)
        if (local != null) path.transform(local.toAndroid())
        val outer = view
            .keyword("clip-path")
            ?.let(::urlReference)
            ?.let { model.byId[it] }
            ?.takeIf { it.tag == "clipPath" }
        if (outer != null) {
            clipPathOf(outer, vp) { elementBox(child, view, style, vp) }?.let { path.op(it, Path.Op.INTERSECT) }
        }
        return path
    }

    /**
     * Multiplies what was drawn in the current layer by the mask. Drawing the mask's content over opaque black first means a part
     * that is half transparent counts as half its brightness, because a colour filter only sees straight colour and would otherwise
     * read half-transparent white as fully bright.
     */
    private fun applyMask(mask: SvgElement, box: Box?, vp: Viewport) {
        val view = viewOf(mask)
        val style = styleOf(mask)
        val paint = Paint()
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        val isLuminance = view.keyword("mask-type") != "alpha"
        if (isLuminance) paint.colorFilter = LUMINANCE_TO_ALPHA
        canvas.saveLayer(null, paint)
        if (isLuminance) canvas.drawColor(Color.BLACK)
        val objectUnits = view.keyword("maskUnits") != "userSpaceOnUse"
        val contentInObjectUnits = view.keyword("maskContentUnits") == "objectBoundingBox"
        val fontSize = style.fontSize
        val region: Box?
        if (objectUnits) {
            region =
                box?.let { b ->
                    val x = (view.length("x") ?: Length(pct = -10.0)).fraction()
                    val y = (view.length("y") ?: Length(pct = -10.0)).fraction()
                    val w = (view.length("width") ?: Length(pct = 120.0)).fraction()
                    val h = (view.length("height") ?: Length(pct = 120.0)).fraction()
                    Box(b.x + x * b.w, b.y + y * b.h, w * b.w, h * b.h)
                }
        } else {
            region =
                Box(
                    (view.length("x") ?: Length(pct = -10.0)).resolve(vp.w, fontSize),
                    (view.length("y") ?: Length(pct = -10.0)).resolve(vp.h, fontSize),
                    (view.length("width") ?: Length(pct = 120.0)).resolve(vp.w, fontSize),
                    (view.length("height") ?: Length(pct = 120.0)).resolve(vp.h, fontSize),
                )
        }
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
                PreserveAspect.parse(view.keyword("preserveAspectRatio")),
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
        if (--budget < 0) return
        val view = viewFor(target, parent)
        if (view.keyword("display") == "none") return
        val style = deriveStyle(parent, view)
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
        val box = geometry.box
        for (step in style.paintOrder) {
            when (step) {
                Style.PAINT_FILL -> {
                    if (style.visible) fillPath(geometry.path, box, style, vp, alpha)
                }

                Style.PAINT_STROKE -> {
                    if (style.visible) strokePath(geometry.path, box, view, style, vp, alpha)
                }

                Style.PAINT_MARKERS -> {
                    if (style.visible && geometry.data != null) drawMarkers(geometry.data, style, vp)
                }
            }
        }
    }

    private fun fillPath(path: Path, box: Box?, style: Style, vp: Viewport, alpha: Double) {
        val paint = resolvePaint(style.fill, style, box, vp) ?: return
        val opacity = style.fillOpacity.coerceIn(0.0, 1.0) * alpha
        if (opacity <= 0.0) return
        path.fillType = if (style.fillEvenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        val p = preparePaint(paint, opacity, style)
        p.style = Paint.Style.FILL
        canvas.drawPath(path, p)
    }

    private fun strokePath(path: Path, box: Box?, view: PropView, style: Style, vp: Viewport, alpha: Double) {
        val width = style.strokeWidth.resolve(vp.diagonal, style.fontSize)
        // Android reads zero as "one device pixel, whatever the scale", where SVG means "no stroke at all".
        if (width <= 0.0) return
        val paint = resolvePaint(style.stroke, style, box, vp) ?: return
        val opacity = style.strokeOpacity.coerceIn(0.0, 1.0) * alpha
        if (opacity <= 0.0) return
        val p = preparePaint(paint, opacity, style)
        p.style = Paint.Style.STROKE
        p.strokeWidth = width.toFloat()
        p.strokeCap = style.lineCap
        p.strokeJoin = style.lineJoin
        p.strokeMiter = style.miterLimit.coerceAtLeast(1.0).toFloat()
        if (view.keyword("vector-effect") == "non-scaling-stroke") {
            strokeOnScreen(path, p, view, style, vp, paint.shader)
        } else {
            p.pathEffect = dashEffect(path, view, style, vp)
            canvas.drawPath(path, p)
        }
    }

    /**
     * A non-scaling stroke has its width, joins and dashes on screen, whatever the transforms ask of the shape. No single scale can
     * undo a transform that stretches one way, so the path goes to screen space first, and the stroke is made there.
     */
    private fun strokeOnScreen(path: Path, paint: Paint, view: PropView, style: Style, vp: Viewport, shader: Shader?) {
        val inverse = ctm.inverse() ?: return
        val onScreen = Path()
        path.transform(ctm.toAndroid(), onScreen)
        val count = canvas.save()
        canvas.concat(inverse.toAndroid())
        if (shader != null) {
            val local = Matrix()
            shader.getLocalMatrix(local)
            local.postConcat(ctm.toAndroid())
            shader.setLocalMatrix(local)
        }
        paint.pathEffect = dashEffect(onScreen, view, style, vp)
        canvas.drawPath(onScreen, paint)
        canvas.restoreToCount(count)
    }

    private fun dashEffect(path: Path, view: PropView, style: Style, vp: Viewport): DashPathEffect? {
        val dashes = style.dashArray
        if (dashes.isEmpty()) return null
        val base = vp.diagonal
        var values = DoubleArray(dashes.size) { dashes[it].resolve(base, style.fontSize) }
        if (values.any { it < 0.0 } || values.all { it <= 0.0 }) return null
        // Android needs an even count, and SVG defines an odd list as repeated once.
        if (values.size % 2 != 0) values += values
        // pathLength says what the author measured the path as, and dashes are written in that measure.
        val declaredLength = view.number("pathLength")
        val scale = if (declaredLength != null && declaredLength > 0.0) measureLength(path) / declaredLength else 1.0
        val intervals = FloatArray(values.size) { (values[it] * scale).toFloat() }
        val offset = style.dashOffset.resolve(base, style.fontSize) * scale
        return DashPathEffect(intervals, offset.toFloat())
    }

    private fun measureLength(path: Path): Double {
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

    /** The element and those it inherits from through href. A loop in the chain just ends it. */
    private fun referenceChain(el: SvgElement): List<SvgElement> {
        val chain = ArrayList<SvgElement>(2)
        var current: SvgElement? = el
        while (current != null && current !in chain) {
            chain += current
            current = targetOf(viewOf(current))
        }
        return chain
    }

    private fun attributeOf(chain: List<SvgElement>, name: String): SvgValue? {
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
                // Per the specification, a gradient with no length paints with its last colour.
                if (x1 == x2 && y1 == y2) return ResolvedPaint(stops.last().color, null)
                GradientShaders.linear(x1, y1, x2, y2, stops, spread, matrix)
            } else {
                val cx = xOf("cx", half)
                val cy = yOf("cy", half)
                val r = radiusOf("r", half)
                if (r <= 0.0) return ResolvedPaint(stops.last().color, null)
                val fx = lengthOf(chain, "fx")?.let { if (objectUnits) it.fraction() else it.resolve(vp.w, fontSize) } ?: cx
                val fy = lengthOf(chain, "fy")?.let { if (objectUnits) it.fraction() else it.resolve(vp.h, fontSize) } ?: cy
                val fr = radiusOf("fr", Length.ZERO).coerceAtLeast(0.0)
                GradientShaders.radial(cx, cy, r, fx, fy, fr, stops, spread, matrix, box)
            }
        return ResolvedPaint(Rgba.BLACK, shader)
    }

    private fun gradientStops(chain: List<SvgElement>): List<GradientStop> {
        val owner = chain.firstOrNull { element -> element.children.any { it.tag == "stop" } } ?: return emptyList()
        val stops = ArrayList<GradientStop>()
        var previous = 0.0
        for (child in owner.children) {
            if (child.tag != "stop") continue
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
        return stops
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

            // The tile is drawn at the size it will appear, so patterns stay sharp when the picture is scaled up.
            val onScreen = ctm * transform
            val scaleX = hypot(onScreen.a, onScreen.b).coerceAtLeast(1e-6)
            val scaleY = hypot(onScreen.c, onScreen.d).coerceAtLeast(1e-6)
            val pixelW = ceil(tileW * scaleX - 1e-6).toInt().coerceIn(1, MAX_TILE_SIZE)
            val pixelH = ceil(tileH * scaleY - 1e-6).toInt().coerceIn(1, MAX_TILE_SIZE)
            val bitmap = Bitmap.createBitmap(pixelW, pixelH, Bitmap.Config.ARGB_8888)
            val toBitmap = Mat2D.scale(pixelW / tileW, pixelH / tileH)
            val viewBox = numbers(attributeOf(chain, "viewBox")).takeIf { it.size == 4 && it[2] > 0.0 && it[3] > 0.0 }
            // The content's origin sits on the tile's corner, so x and y shift the picture along with the tiles. A strict reading of the
            // specification would leave the content in place and only move the window, but every browser does this, and files expect it.
            val content =
                if (viewBox != null) {
                    val aspect = PreserveAspect.parse(keywordOf(chain, "preserveAspectRatio"))
                    viewBoxTransform(viewBox[0], viewBox[1], viewBox[2], viewBox[3], 0.0, 0.0, tileW, tileH, aspect)
                } else if (keywordOf(chain, "patternContentUnits") == "objectBoundingBox" && box != null) {
                    Mat2D.scale(box.w, box.h)
                } else {
                    Mat2D.IDENTITY
                }
            val savedCanvas = canvas
            val savedCtm = ctm
            val savedFill = contextFill
            val savedStroke = contextStroke
            try {
                canvas = Canvas(bitmap)
                ctm = Mat2D.IDENTITY
                concat(toBitmap * content)
                // A pattern is not a viewport of its own: percentages inside it still mean the picture's.
                drawChildren(owner, styleOf(owner), vp)
            } finally {
                canvas = savedCanvas
                ctm = savedCtm
                contextFill = savedFill
                contextStroke = savedStroke
            }
            val shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            val local = transform * Mat2D.translate(tileX, tileY) * Mat2D.scale(tileW / pixelW, tileH / pixelH)
            shader.setLocalMatrix(local.toAndroid())
            return ResolvedPaint(Rgba.BLACK, shader)
        } catch (_: OutOfMemoryError) {
            // A tile too big for memory leaves the shape unpainted, and the rest of the picture still draws.
            return null
        } finally {
            active -= el
        }
    }

    private fun drawMarkers(data: PathData, style: Style, vp: Viewport) {
        val start = style.markerStart?.let { model.byId[it] }?.takeIf { it.tag == "marker" }
        val mid = style.markerMid?.let { model.byId[it] }?.takeIf { it.tag == "marker" }
        val end = style.markerEnd?.let { model.byId[it] }?.takeIf { it.tag == "marker" }
        if (start == null && mid == null && end == null) return
        val vertices = markerVertices(data)
        if (vertices.isEmpty()) return
        val width = style.strokeWidth.resolve(vp.diagonal, style.fontSize)
        val savedFill = contextFill
        val savedStroke = contextStroke
        contextFill = style.fill
        contextStroke = style.stroke
        for ((index, vertex) in vertices.withIndex()) {
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
        if (--budget < 0) {
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
                    else -> parseAngle(orient) ?: 0.0
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
                        PreserveAspect.parse(view.keyword("preserveAspectRatio")),
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
                Geometry(data.path, data.bounds, data)
            }

            "polyline", "polygon" -> {
                val points = numbers(view["points"])
                if (points.size < 2) return null
                val data = PathData.polyline(points, closed = el.tag == "polygon")
                Geometry(data.path, data.bounds, data)
            }

            "path" -> {
                val data = (view["d"] as? PathValue)?.data ?: return null
                if (data.isEmpty) return null
                Geometry(data.path, data.bounds, data)
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
        return Geometry(path, Box(cx - rx, cy - ry, 2 * rx, 2 * ry), null)
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
        return Geometry(path, Box(x, y, w, h), null)
    }

    /** The box an element takes up in its own user space, before its own transform. Animated values make this differ per frame. */
    private fun elementBox(el: SvgElement, view: PropView, style: Style, vp: Viewport): Box? {
        val key = BoxKey(el, style, vp)
        if (boxCache.containsKey(key)) return boxCache[key]
        if (--budget < 0) return null
        val box =
            when (el.tag) {
                "g", "a" -> childrenBox(el, style, vp)
                "switch" -> firstPassingChild(el)?.let { unionChild(null, it, style, vp) }
                "svg", "symbol" -> viewportBox(el, view, style, vp, null, null)
                "use" -> useBox(el, view, style, vp)
                "text" -> layoutText(el, view, style, vp).box
                "image" -> imageBox(el, view, style, vp)
                else -> geometry(el, view, style, vp)?.box
            }
        boxCache[key] = box
        return box
    }

    private fun childrenBox(el: SvgElement, style: Style, vp: Viewport): Box? {
        if (!active.add(el)) return null
        var union: Box? = null
        for (child in el.children) {
            if (child.tag in DRAWN_TAGS && child.conditionsPass) union = unionChild(union, child, style, vp)
        }
        active -= el
        return union
    }

    private fun unionChild(union: Box?, child: SvgElement, style: Style, vp: Viewport): Box? {
        val view = viewFor(child, style)
        if (view.keyword("display") == "none") return union
        val childStyle = deriveStyle(style, view)
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
        val targetStyle = deriveStyle(style, targetView)
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
            val bitmap = bitmapOf(image) ?: return null
            intrinsicW = bitmap.width.toDouble()
            intrinsicH = bitmap.height.toDouble()
        }
        val w = width ?: if (height != null && intrinsicH > 0) height * intrinsicW / intrinsicH else intrinsicW
        val h = height ?: if (width != null && intrinsicW > 0) width * intrinsicH / intrinsicW else intrinsicH
        return if (w > 0.0 && h > 0.0) w to h else null
    }

    private fun bitmapOf(image: EmbeddedImage): Bitmap? {
        if (image.decoded) return image.bitmap
        image.decoded = true
        val bytes = image.bytes
        try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > model.limits.maxImagePixels) sample *= 2
            val options = BitmapFactory.Options()
            options.inSampleSize = sample
            image.bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: OutOfMemoryError) {
            image.bitmap = null
        }
        return image.bitmap
    }

    private fun drawImage(el: SvgElement, view: PropView, style: Style, vp: Viewport, alpha: Double) {
        if (!style.visible) return
        val image = el.image ?: return
        val (w, h) = imageSize(el, view, style, vp) ?: return
        val x = view.length("x")?.resolve(vp.w, style.fontSize) ?: 0.0
        val y = view.length("y")?.resolve(vp.h, style.fontSize) ?: 0.0
        val aspect = PreserveAspect.parse(view.keyword("preserveAspectRatio"))
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
                if (alpha < 1.0) canvas.saveLayerAlpha(null, alphaByte(alpha))
                // An SVG image inside another SVG plays on the clock of the outer picture.
                nested.render(canvas, time, RectF(0f, 0f, nestedW.toFloat(), nestedH.toFloat()))
            }
        } else {
            val bitmap = bitmapOf(image)
            if (bitmap != null) {
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
            if (!decoration.isOverText) drawTextOutline(decoration.path, layout.box, view, decoration.style, vp, alpha)
        }
        for (run in layout.runs) {
            if (!run.style.visible) continue
            val path = run.path
            if (path == null) {
                drawGlyphsDirectly(run, alpha)
            } else {
                drawTextOutline(path, layout.box, view, run.style, vp, alpha)
            }
        }
        for (decoration in layout.decorations) {
            if (decoration.isOverText) drawTextOutline(decoration.path, layout.box, view, decoration.style, vp, alpha)
        }
    }

    private fun drawTextOutline(path: Path, box: Box?, view: PropView, style: Style, vp: Viewport, alpha: Double) {
        if (!style.visible) return
        for (step in style.paintOrder) {
            when (step) {
                Style.PAINT_FILL -> fillPath(path, box, style, vp, alpha)
                Style.PAINT_STROKE -> strokePath(path, box, view, style, vp, alpha)
            }
        }
    }

    /** Colour emoji have no outline, so they cannot go through the path route and are drawn as glyphs, with their own colours. */
    private fun drawGlyphsDirectly(run: PlacedRun, alpha: Double) {
        val paint = TextSupport.paintFor(run.style, run.letterSpacingEm)
        paint.color = run.style.color.toArgb(alpha)
        val scale = run.style.fontSize / TextSupport.NOMINAL_SIZE
        val count = canvas.save()
        canvas.translate((run.x + run.glyphShift).toFloat(), run.y.toFloat())
        canvas.scale(scale.toFloat(), scale.toFloat())
        canvas.drawText(run.text, 0f, 0f, paint)
        canvas.restoreToCount(count)
    }

    private fun layoutText(el: SvgElement, view: PropView, style: Style, vp: Viewport): TextLayout =
        TextLayouter(model.glyphs, vp) { child, parent -> viewFor(child, parent) }.layout(el, view, style)

    private companion object {
        val BASE_STYLE = Style()
        const val MAX_TILE_SIZE = 4096
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
