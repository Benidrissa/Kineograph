package org.kineograph.media.svg

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build

private val TEXT_CHILD_TAGS = setOf("tspan", "textPath", "a", "altGlyph")

/** A run of text measured at a fixed size, so that its outline can be scaled to any font size without shaping it again. */
internal class GlyphRun(val path: Path, val advance: Double, val ascent: Double, val descent: Double, val xHeight: Double) {
    /** False for colour emoji, whose glyphs are bitmaps with no outline to fill. */
    val hasOutline: Boolean get() = !path.isEmpty
}

internal class PlacedRun(
    val style: Style,
    val text: String,
    var x: Double,
    val y: Double,
    val glyphs: GlyphRun,
    val letterSpacingEm: Float,
) {
    var path: Path? = null
}

internal class TextLayout(val runs: List<PlacedRun>, val box: Box?)

internal object TextSupport {
    /**
     * Text is shaped at this size, whatever size it will be drawn at. Glyph positions would otherwise change with the scale, because
     * fonts round them to whole pixels at small sizes, and an animated `font-size` would jitter.
     */
    const val NOMINAL_SIZE: Double = 256.0

    private val typefaces = HashMap<String, Typeface>()

    fun paintFor(style: Style, letterSpacingEm: Float): Paint {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = NOMINAL_SIZE.toFloat()
        paint.typeface = typefaceFor(style)
        paint.isSubpixelText = true
        paint.isLinearText = true
        if (letterSpacingEm != 0f) paint.letterSpacing = letterSpacingEm
        return paint
    }

    @Synchronized
    private fun typefaceFor(style: Style): Typeface {
        val family = genericFamily(style.fontFamily)
        val key = "$family|${style.fontWeight}|${style.italic}"
        return typefaces.getOrPut(key) {
            val isBold = style.fontWeight >= 600
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.create(Typeface.create(family, Typeface.NORMAL), style.fontWeight.coerceIn(1, 1000), style.italic)
            } else {
                val styleFlag =
                    when {
                        isBold && style.italic -> Typeface.BOLD_ITALIC
                        isBold -> Typeface.BOLD
                        style.italic -> Typeface.ITALIC
                        else -> Typeface.NORMAL
                    }
                Typeface.create(family, styleFlag)
            }
        }
    }

    /** Files name fonts this phone does not have, and the app bundles none. Each name is mapped to the generic face closest in feel. */
    internal fun genericFamily(list: String): String {
        for (raw in list.split(',')) {
            when (raw.trim().trim('"', '\'').lowercase()) {
                "serif", "times", "times new roman", "georgia", "garamond", "palatino", "palatino linotype", "cambria",
                "book antiqua", "baskerville", "noto serif", "droid serif", "ui-serif",
                -> return "serif"

                "sans-serif", "arial", "helvetica", "helvetica neue", "verdana", "tahoma", "trebuchet ms", "roboto", "open sans",
                "segoe ui", "calibri", "system-ui", "-apple-system", "blinkmacsystemfont", "ui-sans-serif", "noto sans", "lato",
                "impact", "fantasy",
                -> return "sans-serif"

                "monospace", "courier", "courier new", "consolas", "menlo", "monaco", "lucida console", "roboto mono",
                "ui-monospace", "sfmono-regular", "droid sans mono", "source code pro",
                -> return "monospace"

                "cursive", "comic sans ms", "comic sans", "brush script mt", "casual", "ui-rounded" -> return "cursive"
            }
        }
        return "sans-serif"
    }
}

internal class GlyphCache {
    private val runs = HashMap<String, GlyphRun>()

    fun get(style: Style, letterSpacingEm: Float, text: String): GlyphRun {
        val key = "${style.fontFamily}|${style.fontWeight}|${style.italic}|$letterSpacingEm|$text"
        return runs.getOrPut(key) { measure(style, letterSpacingEm, text) }
    }

    private fun measure(style: Style, letterSpacingEm: Float, text: String): GlyphRun {
        val paint = TextSupport.paintFor(style, letterSpacingEm)
        val path = Path()
        paint.getTextPath(text, 0, text.length, 0f, 0f, path)
        val metrics = paint.fontMetrics
        val bounds = Rect()
        paint.getTextBounds("x", 0, 1, bounds)
        return GlyphRun(
            path,
            paint.measureText(text).toDouble(),
            -metrics.ascent.toDouble(),
            metrics.descent.toDouble(),
            -bounds.top.toDouble(),
        )
    }
}

/**
 * Lays out the simple SVG text model: runs of text in a line, with x, y, dx and dy from `text` and `tspan`, where every absolute
 * position begins a new anchored chunk.
 */
internal class TextLayouter(
    private val cache: GlyphCache,
    private val vp: Viewport,
    private val viewFor: (SvgElement, Style) -> PropView,
) {
    private class Level(val xs: List<Double>, val ys: List<Double>, val dxs: List<Double>, val dys: List<Double>) {
        var index = 0
    }

    private val runs = ArrayList<PlacedRun>()
    private val levels = ArrayList<Level>()
    private val pending = StringBuilder()
    private var pendingStyle: Style? = null
    private var penX = 0.0
    private var penY = 0.0
    private var chunkStart = 0
    private var chunkOriginX = 0.0

    /** Starting true is what drops the whitespace at the start of a text element. */
    private var lastWasSpace = true

    fun layout(el: SvgElement, view: PropView, style: Style): TextLayout {
        visit(el, view, style)
        flush(trimEnd = true)
        finishChunk()
        return TextLayout(runs, finishRuns())
    }

    private fun visit(el: SvgElement, view: PropView, style: Style) {
        levels += readLevel(view, style)
        for (node in el.content.orEmpty()) {
            when (node) {
                is String -> {
                    emit(node, style)
                }

                is SvgElement -> {
                    if (node.tag !in TEXT_CHILD_TAGS || !node.conditionsPass) continue
                    val childView = viewFor(node, style)
                    if (childView.keyword("display") == "none") continue
                    visit(node, childView, deriveStyle(style, childView))
                }
            }
        }
        levels.removeAt(levels.lastIndex)
    }

    private fun readLevel(view: PropView, style: Style): Level =
        Level(
            view.lengths("x").map { it.resolve(vp.w, style.fontSize) },
            view.lengths("y").map { it.resolve(vp.h, style.fontSize) },
            view.lengths("dx").map { it.resolve(vp.w, style.fontSize) },
            view.lengths("dy").map { it.resolve(vp.h, style.fontSize) },
        )

    private fun emit(text: String, style: Style) {
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            i += Character.charCount(codePoint)
            var character = codePoint
            val isSpace = codePoint == ' '.code || codePoint == '\t'.code || codePoint == '\n'.code || codePoint == '\r'.code
            if (style.preserveSpace) {
                if (isSpace) character = ' '.code
                lastWasSpace = false
            } else if (isSpace) {
                if (lastWasSpace) continue
                character = ' '.code
                lastWasSpace = true
            } else {
                lastWasSpace = false
            }
            placeCharacter(character, style)
        }
    }

    /** The innermost element with a value for this character wins, while every level counts the character as used. */
    private fun placeCharacter(character: Int, style: Style) {
        var x: Double? = null
        var y: Double? = null
        var dx: Double? = null
        var dy: Double? = null
        for (level in levels.asReversed()) {
            if (x == null) x = level.xs.getOrNull(level.index)
            if (y == null) y = level.ys.getOrNull(level.index)
            if (dx == null) dx = level.dxs.getOrNull(level.index)
            if (dy == null) dy = level.dys.getOrNull(level.index)
        }
        for (level in levels) level.index++
        if (x != null || y != null) {
            flush(trimEnd = false)
            finishChunk()
            if (x != null) penX = x
            if (y != null) penY = y
            chunkStart = runs.size
            chunkOriginX = penX
        }
        if (dx != null || dy != null) {
            flush(trimEnd = false)
            penX += dx ?: 0.0
            penY += dy ?: 0.0
        }
        if (pendingStyle !== style) flush(trimEnd = false)
        pendingStyle = style
        pending.appendCodePoint(character)
    }

    private fun flush(trimEnd: Boolean) {
        val style = pendingStyle ?: return
        var text = pending.toString()
        pending.setLength(0)
        pendingStyle = null
        if (trimEnd && !style.preserveSpace) text = text.trimEnd(' ')
        if (text.isEmpty() || style.fontSize <= 0.0) return
        val spacing = style.letterSpacing.resolve(vp.diagonal, style.fontSize)
        val spacingEm = (spacing / style.fontSize).toFloat()
        val glyphs = cache.get(style, spacingEm, text)
        val scale = style.fontSize / TextSupport.NOMINAL_SIZE
        runs += PlacedRun(style, text, penX, penY + baselineShift(style, glyphs, scale), glyphs, spacingEm)
        penX += glyphs.advance * scale
    }

    private fun baselineShift(style: Style, glyphs: GlyphRun, scale: Double): Double =
        when (style.baseline) {
            "middle" -> glyphs.xHeight * scale / 2.0
            "central" -> (glyphs.ascent - glyphs.descent) * scale / 2.0
            "hanging" -> glyphs.ascent * scale * 0.8
            "text-before-edge", "text-top" -> glyphs.ascent * scale
            "text-after-edge", "text-bottom", "ideographic" -> -glyphs.descent * scale
            "mathematical" -> glyphs.ascent * scale * 0.5
            else -> 0.0
        }

    private fun finishChunk() {
        if (chunkStart >= runs.size) return
        val advance = penX - chunkOriginX
        val shift =
            when (runs[chunkStart].style.textAnchor) {
                Style.ANCHOR_MIDDLE -> advance / 2.0
                Style.ANCHOR_END -> advance
                else -> 0.0
            }
        if (shift != 0.0) {
            for (i in chunkStart until runs.size) runs[i].x -= shift
            penX -= shift
        }
        chunkStart = runs.size
    }

    private fun finishRuns(): Box? {
        var box: Box? = null
        val matrix = Matrix()
        for (run in runs) {
            val scale = run.style.fontSize / TextSupport.NOMINAL_SIZE
            val glyphs = run.glyphs
            if (glyphs.hasOutline) {
                val placed = Path()
                matrix.setScale(scale.toFloat(), scale.toFloat())
                matrix.postTranslate(run.x.toFloat(), run.y.toFloat())
                glyphs.path.transform(matrix, placed)
                run.path = placed
            }
            val cell = Box(run.x, run.y - glyphs.ascent * scale, glyphs.advance * scale, (glyphs.ascent + glyphs.descent) * scale)
            box = box?.union(cell) ?: cell
        }
        return box
    }
}
