package org.kineograph.media.svg

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.round

private val TEXT_CHILD_TAGS = setOf("tspan", "textPath", "a", "altGlyph")

/** A run of text measured at a fixed size, so that its outline can be scaled to any font size without shaping it again. */
internal class GlyphRun(
    val path: Path,
    val advance: Double,
    val ascent: Double,
    val descent: Double,
    val xHeight: Double,
    /** A fair guess at the native memory of [path]. There is no way to ask, and that memory never shows on the heap. */
    val bytes: Long,
) {
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
    val decorations: List<ActiveDecoration>,
    /** Where this run follows a path, [x] says how far along it. [y] says how far away from it, as with any baseline. */
    val onPath: TextOnPath?,
) {
    var path: Path? = null

    /**
     * Android splits letter spacing into half before each letter and half after it, where SVG puts it all after. Glyphs are
     * placed by this much relative to [x], so that the first letter of a run stays on its position.
     */
    val glyphShift: Double get() = -letterSpacingEm * style.fontSize / 2.0

    /** The box this run takes up, for telling how long drawing it will take. */
    val cell: Box
        get() {
            val scale = style.fontSize / TextSupport.NOMINAL_SIZE
            return Box(x, y - glyphs.ascent * scale, glyphs.advance * scale, (glyphs.ascent + glyphs.descent) * scale)
        }
}

/** The path a `textPath` follows, placed in the coordinates of the text. */
internal class TextOnPath(val measure: MotionMeasure, val matrix: Mat2D) {
    val length: Double get() = measure.total

    /** The position and direction at a distance along the path. */
    fun at(distance: Double): MotionPosition {
        val position = measure.at(distance)
        if (matrix.isIdentity) return position
        val turn = Math.toDegrees(atan2(matrix.b, matrix.a))
        return MotionPosition(matrix.mapX(position.x, position.y), matrix.mapY(position.x, position.y), position.degrees + turn)
    }
}

/** A `text-decoration` in force, with the element that asked for it. Its paint is that element's, whatever is written beneath. */
internal class ActiveDecoration(val kind: Int, val style: Style) {
    companion object {
        const val UNDERLINE: Int = 0
        const val OVERLINE: Int = 1
        const val LINE_THROUGH: Int = 2
    }
}

/** One line of decoration, ready to paint like any other shape. */
internal class PlacedDecoration(val path: Path, val style: Style, val isOverText: Boolean, val line: Box)

internal class TextLayout(val runs: List<PlacedRun>, val decorations: List<PlacedDecoration>, val box: Box?) {
    /** Hands back the memory of the outlines right away. Waiting for the collector would let a few text elements hold gigabytes of it. */
    fun release() {
        for (run in runs) run.path?.reset()
        for (decoration in decorations) decoration.path.reset()
    }
}

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
        if (style.fontFeatures.isNotEmpty()) paint.fontFeatureSettings = style.fontFeatures
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
        // With no name at all, every browser falls back to its default font, which is a serif one.
        if (list.isBlank()) return "serif"
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

/**
 * Text outlines kept between frames, up to a total. An animated letter spacing makes a new outline for every frame, so with no total this
 * grows until the system kills the app, without any error along the way that could be caught: these outlines are outside the heap.
 */
internal class GlyphCache(private val maxBytes: Long) {
    private val runs = LinkedHashMap<String, GlyphRun>(16, 0.75f, true)
    private var bytes = 0L

    val entryCount: Int get() = runs.size
    val heldBytes: Long get() = bytes

    /** Null when the frame cannot pay for it. */
    fun get(style: Style, letterSpacingEm: Float, text: String, frame: FrameWork): GlyphRun? {
        frame.spend(text.length / KEY_CHARS_PER_UNIT)
        val key = "${style.fontFamily}|${style.fontWeight}|${style.italic}|${style.fontFeatures}|$letterSpacingEm|$text"
        runs[key]?.let { return it }
        val cost = text.length * WorkCost.SHAPED_CHARACTER
        val size = outlineBytes(text)
        if (!frame.canAfford(cost) || !frame.reserveText(size)) {
            frame.noteLeftOut()
            return null
        }
        frame.spend(cost)
        val run = measure(style, letterSpacingEm, text, size)
        runs[key] = run
        bytes += size
        return run
    }

    /**
     * Lets go of what no longer fits, starting with what was used longest ago. This frees the memory at once, which is safe only between
     * text layouts, since nothing else keeps a run for longer than one layout.
     */
    fun trim() {
        val entries = runs.entries.iterator()
        while (bytes > maxBytes && entries.hasNext()) {
            val run = entries.next().value
            bytes -= run.bytes
            run.path.reset()
            entries.remove()
        }
    }

    private fun measure(style: Style, letterSpacingEm: Float, text: String, size: Long): GlyphRun {
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
            size,
        )
    }

    companion object {
        private const val KEY_CHARS_PER_UNIT = 8L

        /** Measured on Latin letters. The much busier outlines of Chinese and Japanese take several times that. */
        private const val BYTES_PER_SIMPLE_CHARACTER = 1024L
        private const val BYTES_PER_COMPLEX_CHARACTER = 4096L

        fun outlineBytes(text: String): Long {
            var total = BASE_BYTES
            for (c in text) total += if (c.code < 0x0800) BYTES_PER_SIMPLE_CHARACTER else BYTES_PER_COMPLEX_CHARACTER
            return total
        }

        /** How much of a text fits in [bytes], to the end of a character. */
        fun charactersFitting(text: String, bytes: Long): Int {
            var total = BASE_BYTES
            var i = 0
            while (i < text.length) {
                total += if (text[i].code < 0x0800) BYTES_PER_SIMPLE_CHARACTER else BYTES_PER_COMPLEX_CHARACTER
                if (total > bytes) break
                i++
            }
            return if (i > 0 && i < text.length && text[i].isLowSurrogate()) i - 1 else i
        }

        private const val BASE_BYTES = 256L
    }
}

/**
 * Lays out the simple SVG text model: runs of text in a line, with x, y, dx and dy from `text` and `tspan`, where every absolute
 * position begins a new anchored chunk.
 */
internal class TextLayouter(
    private val cache: GlyphCache,
    private val frame: FrameWork,
    private val vp: Viewport,
    private val viewFor: (SvgElement, Style) -> PropView,
    private val pathFor: (PropView) -> TextOnPath?,
) {
    private class Level(
        val xs: List<Double>,
        val ys: List<Double>,
        val dxs: List<Double>,
        val dys: List<Double>,
        val decorations: List<ActiveDecoration>,
        /** How far this element and those around it raise or lower the text, as asked by `baseline-shift` and `alignment-baseline`. */
        val shift: Double,
    ) {
        var index = 0
    }

    private val runs = ArrayList<PlacedRun>()
    private val levels = ArrayList<Level>()
    private val pending = StringBuilder()
    private var pendingStyle: Style? = null
    private var pendingDecorations: List<ActiveDecoration> = emptyList()
    private var pendingShift = 0.0
    private var insideWord = false
    private var penX = 0.0
    private var penY = 0.0
    private var chunkStart = 0
    private var chunkOriginX = 0.0

    /** Set while inside a `textPath`, where the pen's x is a distance along the path and its y a distance away from it. */
    private var onPath: TextOnPath? = null

    /** Starting true is what drops the whitespace at the start of a text element. */
    private var lastWasSpace = true

    /** Word spacing owed for a space just placed. It waits for the next letter, because a space at the very end adds nothing. */
    private var owedWordSpacing = 0.0
    private val placedDecorations = ArrayList<PlacedDecoration>()

    fun layout(el: SvgElement, view: PropView, style: Style): TextLayout {
        // Nothing is in use between layouts, so this is the moment for it.
        cache.trim()
        frame.beginText()
        visit(el, view, style)
        flush(trimEnd = true)
        finishChunk()
        val box = finishRuns()
        return TextLayout(runs, placedDecorations, box)
    }

    private fun visit(el: SvgElement, view: PropView, style: Style) {
        if (!frame.enter()) return
        levels += readLevel(el, view, style)
        for (node in el.content.orEmpty()) {
            if (!frame.visit(WorkCost.BOX_MEASURED)) break
            when (node) {
                is String -> {
                    emit(node, style)
                }

                is SvgElement -> {
                    if (node.tag !in TEXT_CHILD_TAGS || !node.conditionsPass) continue
                    val childView = viewFor(node, style)
                    if (childView.keyword("display") == "none") continue
                    val childStyle = deriveStyle(style, childView)
                    if (node.tag == "textPath") {
                        visitTextPath(node, childView, childStyle)
                    } else {
                        visit(node, childView, childStyle)
                    }
                }
            }
        }
        levels.removeAt(levels.lastIndex)
        frame.leave()
    }

    /** Without a path to follow, its words are not drawn at all, as in browsers. */
    private fun visitTextPath(el: SvgElement, view: PropView, style: Style) {
        val path = pathFor(view) ?: return
        flush(trimEnd = false)
        finishChunk()
        onPath = path
        penX = view.length("startOffset")?.resolve(path.length, style.fontSize) ?: 0.0
        penY = 0.0
        chunkStart = runs.size
        chunkOriginX = penX
        visit(el, view, style)
        flush(trimEnd = false)
        finishChunk()
        onPath = null
        // Text after it carries on from where this ended.
        val end = path.at(penX)
        penX = end.x
        penY = end.y
        chunkStart = runs.size
        chunkOriginX = penX
    }

    private fun readLevel(el: SvgElement, view: PropView, style: Style): Level {
        val decorations = levels.lastOrNull()?.decorations.orEmpty() + decorationsOf(view, style)
        frame.spend(
            (
                view
                    .lengths(
                        "x",
                    ).size + view.lengths("y").size + view.lengths("dx").size + view.lengths("dy").size + decorations.size
            ).toLong(),
        )
        return Level(
            view.lengths("x").map { it.resolve(vp.w, style.fontSize) },
            view.lengths("y").map { it.resolve(vp.h, style.fontSize) },
            view.lengths("dx").map { it.resolve(vp.w, style.fontSize) },
            view.lengths("dy").map { it.resolve(vp.h, style.fontSize) },
            decorations,
            (levels.lastOrNull()?.shift ?: 0.0) + ownShift(el, view, style),
        )
    }

    /** Both move a whole element's text at once: its letters, and everything inside it. */
    private fun ownShift(el: SvgElement, view: PropView, style: Style): Double {
        val shifting = view.keyword("baseline-shift")
        val aligning = view.keyword("alignment-baseline")
        if (shifting == null && aligning == null) return 0.0
        val glyphs = cache.get(style, 0f, "x", frame) ?: return 0.0
        val scale = style.fontSize / TextSupport.NOMINAL_SIZE
        val height = round(glyphs.ascent * scale) + round(glyphs.descent * scale)
        val shift =
            when (shifting) {
                null, "baseline" -> 0.0
                "sub" -> height / 2.0
                "super" -> -height / 2.0
                else -> parseLengthOrNull(shifting)?.let { -it.resolve(style.fontSize, style.fontSize) } ?: 0.0
            }
        // A plain `text` element has nothing around it to line up with, yet browsers still move it, as if its own baseline were the parent's.
        return shift + (aligning?.let { baselineOffset(it, glyphs, scale) } ?: 0.0)
    }

    /** `text-decoration` is not inherited, but it does draw across everything inside the element that asks for it. */
    private fun decorationsOf(view: PropView, style: Style): List<ActiveDecoration> {
        val text = view.keyword("text-decoration-line") ?: view.keyword("text-decoration") ?: return emptyList()
        frame.spend(text.length.toLong())
        val out = ArrayList<ActiveDecoration>(1)
        // Saying underline a thousand times still makes one line, as in browsers, and not a thousand for each piece of text.
        for (token in splitList(text)) {
            val kind =
                when (token) {
                    "underline" -> ActiveDecoration.UNDERLINE
                    "overline" -> ActiveDecoration.OVERLINE
                    "line-through" -> ActiveDecoration.LINE_THROUGH
                    else -> continue
                }
            if (out.none { it.kind == kind }) out += ActiveDecoration(kind, style)
        }
        return out
    }

    /** Case changes work on what will be drawn, while whether a letter starts a word depends on what came before, so it is kept. */
    private fun transformCase(text: String, style: Style): String =
        when (style.textTransform) {
            "uppercase" -> {
                text.uppercase(Locale.ROOT)
            }

            "lowercase" -> {
                text.lowercase(Locale.ROOT)
            }

            "capitalize" -> {
                val out = StringBuilder(text.length)
                for (c in text) {
                    val isLetterOrDigit = c.isLetterOrDigit()
                    out.append(if (isLetterOrDigit && !insideWord) c.titlecaseChar() else c)
                    insideWord = isLetterOrDigit || c == '\'' || c == '\u2019'
                }
                out.toString()
            }

            else -> {
                text
            }
        }

    private fun emit(rawText: String, style: Style) {
        // Every character looks through every level around it for a position of its own.
        if (!frame.spend(rawText.length * (WorkCost.LAID_OUT_CHARACTER + levels.size))) return
        val text = transformCase(rawText, style)
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
            if (character == ' '.code) owedWordSpacing += style.wordSpacing.resolve(vp.diagonal, style.fontSize)
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
        // On a path, places come from the path. Only the nudges mean anything there.
        if (onPath != null) {
            x = null
            y = null
        }
        if (owedWordSpacing != 0.0) {
            flush(trimEnd = false)
            penX += owedWordSpacing
            owedWordSpacing = 0.0
        }
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
        val decorations = levels.lastOrNull()?.decorations.orEmpty()
        val shift = levels.lastOrNull()?.shift ?: 0.0
        if (pendingStyle !== style || pendingDecorations !== decorations || pendingShift != shift) flush(trimEnd = false)
        pendingStyle = style
        pendingDecorations = decorations
        pendingShift = shift
        pending.appendCodePoint(character)
    }

    private fun flush(trimEnd: Boolean) {
        val style = pendingStyle ?: return
        var text = pending.toString()
        pending.setLength(0)
        pendingStyle = null
        if (trimEnd && !style.preserveSpace) text = text.trimEnd(' ')
        if (text.isEmpty() || style.fontSize <= 0.0) return
        if (!frame.spend(WorkCost.PLACED_RUN)) return
        // An outline and a copy of it in place, so the text that fits in memory is half of what is free. What does not fit goes unseen.
        val fitting = GlyphCache.charactersFitting(text, frame.textBytesAvailable / 2)
        if (fitting < text.length) {
            frame.noteLeftOut()
            if (fitting == 0) return
            text = text.substring(0, fitting)
        }
        val spacing = style.letterSpacing.resolve(vp.diagonal, style.fontSize)
        val spacingEm = (spacing / style.fontSize).toFloat()
        val glyphs = cache.get(style, spacingEm, text, frame) ?: return
        // The copy, which every run needs, whether its outline was already known or not.
        if (!frame.reserveText(glyphs.bytes)) {
            frame.noteLeftOut()
            return
        }
        val scale = style.fontSize / TextSupport.NOMINAL_SIZE
        val y = penY + pendingShift + baselineOffset(style.baseline, glyphs, scale)
        runs += PlacedRun(style, text, penX, y, glyphs, spacingEm, pendingDecorations, onPath)
        penX += glyphs.advance * scale
    }

    /** Browsers work with these metrics rounded to whole pixels, so an edge they measure from lands there too. */
    private fun baselineOffset(baseline: String, glyphs: GlyphRun, scale: Double): Double {
        val ascent = round(glyphs.ascent * scale)
        val descent = round(glyphs.descent * scale)
        return when (baseline) {
            "middle" -> glyphs.xHeight * scale / 2.0
            "central" -> (ascent - descent) / 2.0
            "hanging" -> ascent * 0.8
            "text-before-edge" -> ascent
            "text-after-edge", "ideographic" -> -descent
            "mathematical" -> ascent * 0.5
            else -> 0.0
        }
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
            val along = run.onPath
            if (along != null) {
                val placed = placeAlong(run, along, scale)
                run.path = placed
                val bounds = RectF()
                placed.computeBounds(bounds, true)
                if (!bounds.isEmpty) {
                    val cell = Box(bounds.left.toDouble(), bounds.top.toDouble(), bounds.width().toDouble(), bounds.height().toDouble())
                    box = box?.union(cell) ?: cell
                }
                continue
            }
            if (glyphs.hasOutline) {
                val placed = Path()
                matrix.setScale(scale.toFloat(), scale.toFloat())
                matrix.postTranslate((run.x + run.glyphShift).toFloat(), run.y.toFloat())
                glyphs.path.transform(matrix, placed)
                run.path = placed
            }
            val cell = run.cell
            box = box?.union(cell) ?: cell
            for (decoration in run.decorations) {
                // Many short runs and many lines over them multiply, and each one is a path of its own.
                if (!frame.spend(WorkCost.PLACED_RUN) || !frame.reserveText(DECORATION_BYTES)) {
                    frame.noteLeftOut()
                    break
                }
                placedDecorations += placeDecoration(run, decoration, scale)
            }
        }
        return box
    }

    /** Each letter stands at the middle of its own width on the path, turned to the path's direction there. What falls off the ends goes. */
    private fun placeAlong(run: PlacedRun, along: TextOnPath, scale: Double): Path {
        val placed = Path()
        val paint = TextSupport.paintFor(run.style, run.letterSpacingEm)
        val text = run.text
        val matrix = Matrix()
        var i = 0
        var previous = 0
        var before = 0.0
        while (i < text.length) {
            if (!frame.spend(WorkCost.PLACED_GLYPH)) break
            val next = i + Character.charCount(text.codePointAt(i))
            // Measured along with the letter before it, so that the pairs of letters a font tucks together stay that way. Measuring all
            // of the text up to here instead would be the same but for the time it takes, which on a long text is minutes per frame.
            val width = (paint.measureText(text, previous, next) - paint.measureText(text, previous, i)) * scale
            val middle = run.x + before + width / 2.0
            if (middle in 0.0..along.length) {
                val position = along.at(middle)
                matrix.setScale(scale.toFloat(), scale.toFloat())
                matrix.postTranslate((-width / 2.0).toFloat(), run.y.toFloat())
                matrix.postRotate(position.degrees.toFloat())
                matrix.postTranslate(position.x.toFloat(), position.y.toFloat())
                val glyph = cache.get(run.style, 0f, text.substring(i, next), frame) ?: break
                if (!frame.reserveText(glyph.bytes)) {
                    frame.noteLeftOut()
                    break
                }
                placed.addPath(glyph.path, matrix)
            }
            before += width
            previous = i
            i = next
        }
        return placed
    }

    private companion object {
        /** A path of its own and the objects around it, on and off the heap. */
        const val DECORATION_BYTES = 512L
    }

    /** Sizes and places a line the way browsers do: its thickness is a tenth of the font size, set off from the baseline by half that. */
    private fun placeDecoration(run: PlacedRun, decoration: ActiveDecoration, scale: Double): PlacedDecoration {
        val fontSize = run.style.fontSize
        val thickness = maxOf(1.0, fontSize / 10.0)
        val ascent = round(run.glyphs.ascent * scale)
        val top =
            when (decoration.kind) {
                ActiveDecoration.UNDERLINE -> run.y + maxOf(1.0, ceil(thickness / 2.0))
                ActiveDecoration.OVERLINE -> run.y - ascent - thickness
                else -> run.y - ascent / 3.0 - thickness / 2.0
            }
        val path = Path()
        path.addRect(
            run.x.toFloat(),
            top.toFloat(),
            (run.x + run.glyphs.advance * scale).toFloat(),
            (top + thickness).toFloat(),
            Path.Direction.CW,
        )
        val line = Box(run.x, top, run.glyphs.advance * scale, thickness)
        return PlacedDecoration(path, decoration.style, decoration.kind == ActiveDecoration.LINE_THROUGH, line)
    }
}
