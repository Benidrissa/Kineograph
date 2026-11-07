package org.kineograph.media.svg

import kotlin.math.floor

/**
 * A length stored as a sum of absolute, percentage and font-relative parts.
 *
 * Keeping the parts apart until drawing means two lengths with different units can still be added and interpolated, which
 * animation needs (`10%` to `50px`), without knowing yet which viewport or font they will be resolved against.
 */
internal data class Length(val px: Double = 0.0, val pct: Double = 0.0, val em: Double = 0.0) {
    operator fun plus(other: Length): Length = Length(px + other.px, pct + other.pct, em + other.em)

    operator fun minus(other: Length): Length = Length(px - other.px, pct - other.pct, em - other.em)

    operator fun times(k: Double): Length = Length(px * k, pct * k, em * k)

    fun resolve(percentBase: Double, fontSize: Double): Double = px + pct * percentBase / 100.0 + em * fontSize

    /** The value in object-bounding-box space, where `0.5` and `50%` mean the same thing. */
    fun fraction(): Double = px + pct / 100.0

    companion object {
        val ZERO: Length = Length()
    }
}

/** Colour channels on the 0 to 255 scale, kept as doubles so that interpolation does not build up rounding errors. */
internal data class Rgba(val r: Double, val g: Double, val b: Double, val a: Double = 1.0) {
    fun toArgb(alphaScale: Double = 1.0): Int {
        val alpha = roundChannel((a * alphaScale).coerceIn(0.0, 1.0) * 255.0)
        return (alpha shl 24) or (roundChannel(r) shl 16) or (roundChannel(g) shl 8) or roundChannel(b)
    }

    /**
     * CSS mixes colours premultiplied, so that fading to `transparent` does not pass through a dark fringe. When both alphas are
     * equal this reduces to plain channel mixing, which is what SMIL expects.
     */
    fun mix(other: Rgba, p: Double): Rgba {
        val alpha = (a + (other.a - a) * p).coerceIn(0.0, 1.0)
        if (alpha <= 0.0) return Rgba(0.0, 0.0, 0.0, 0.0)
        fun channel(x: Double, y: Double): Double = ((x * a + (y * other.a - x * a) * p) / alpha).coerceIn(0.0, 255.0)
        return Rgba(channel(r, other.r), channel(g, other.g), channel(b, other.b), alpha)
    }

    private fun roundChannel(value: Double): Int = floor(value.coerceIn(0.0, 255.0) + 0.5).toInt()

    companion object {
        val BLACK: Rgba = Rgba(0.0, 0.0, 0.0)
        val TRANSPARENT: Rgba = Rgba(0.0, 0.0, 0.0, 0.0)

        fun fromRgb(rgb: Int, alpha: Double = 1.0): Rgba =
            Rgba(((rgb shr 16) and 0xFF).toDouble(), ((rgb shr 8) and 0xFF).toDouble(), (rgb and 0xFF).toDouble(), alpha)
    }
}

/** A property value in the form animation and drawing both use, so a value only has to be parsed once. */
internal sealed interface SvgValue

internal data class NumberValue(val value: Double) : SvgValue

internal data class LengthValue(val length: Length) : SvgValue

internal data class LengthListValue(val items: List<Length>) : SvgValue

/** Dash patterns get their own type because, unlike point lists, lists of different sizes can still be interpolated. */
internal data class DashValue(val items: List<Length>) : SvgValue

internal data class KeywordValue(val text: String) : SvgValue

internal data class TransformValue(val ops: List<TransformOp>) : SvgValue

internal class PathValue(val data: PathData, val hadError: Boolean = false) : SvgValue

internal data object InheritValue : SvgValue

internal data object InitialValue : SvgValue

internal sealed interface PaintValue : SvgValue {
    data object None : PaintValue

    data object Current : PaintValue

    data object ContextFill : PaintValue

    data object ContextStroke : PaintValue

    data class Solid(val color: Rgba) : PaintValue

    /** A paint server. [id] is null when the reference pointed outside the file; then only [fallback] can be used. */
    data class Ref(val id: String?, val fallback: PaintValue?) : PaintValue
}

/** Walks the number grammar of path data and transforms, where separators are optional: `.5-1e3` is two numbers. */
internal class NumberScanner(private val text: String, var pos: Int = 0) {
    val atEnd: Boolean get() = pos >= text.length

    fun peek(): Char = if (pos < text.length) text[pos] else '\u0000'

    fun skipWhitespace() {
        while (pos < text.length && isSvgSpace(text[pos])) pos++
    }

    fun skipSeparators() {
        skipWhitespace()
        if (pos < text.length && text[pos] == ',') {
            pos++
            skipWhitespace()
        }
    }

    /**
     * Reads one number, or returns null and stays put. The grammar is checked by hand because Double.parseDouble also accepts
     * `NaN`, `Infinity`, `1f` and hexadecimal, none of which may reach the renderer. An `e` only starts an exponent when digits
     * follow it, so `1em` reads as the number 1 followed by a unit.
     */
    fun number(): Double? {
        val n = text.length
        var i = pos
        if (i < n && (text[i] == '+' || text[i] == '-')) i++
        var digits = 0
        while (i < n && text[i].isAsciiDigit()) {
            i++
            digits++
        }
        if (i < n && text[i] == '.') {
            i++
            while (i < n && text[i].isAsciiDigit()) {
                i++
                digits++
            }
        }
        if (digits == 0) return null
        if (i < n && (text[i] == 'e' || text[i] == 'E')) {
            var j = i + 1
            if (j < n && (text[j] == '+' || text[j] == '-')) j++
            val exponentStart = j
            while (j < n && text[j].isAsciiDigit()) j++
            if (j > exponentStart) i = j
        }
        val value = text.substring(pos, i).toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        pos = i
        return value
    }

    /** Arc flags are exactly one character, which is what lets `a1 1 0 00.5.5` work without separators. */
    fun flag(): Double? {
        val c = peek()
        if (c != '0' && c != '1') return null
        pos++
        return if (c == '1') 1.0 else 0.0
    }
}

internal fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

internal fun isSvgSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'

internal fun parseNumberOrNull(raw: String): Double? {
    val text = raw.trim()
    val scanner = NumberScanner(text)
    val value = scanner.number() ?: return null
    return if (scanner.atEnd) value else null
}

/** A number or a percentage as a plain fraction, the form opacity and stop offsets both accept. */
internal fun parseFraction(raw: String): Double? {
    val text = raw.trim()
    return if (text.endsWith("%")) parseNumberOrNull(text.dropLast(1))?.div(100.0) else parseNumberOrNull(text)
}

internal fun parseLengthOrNull(raw: String): Length? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    if (text.startsWith("calc(", ignoreCase = true)) return CalcParser(text).parse()
    val scanner = NumberScanner(text)
    val value = scanner.number() ?: return null
    return lengthWithUnit(value, text.substring(scanner.pos))
}

internal fun lengthWithUnit(value: Double, unit: String): Length? =
    when (unit.lowercase()) {
        "", "px" -> Length(px = value)

        "%" -> Length(pct = value)

        "em" -> Length(em = value)

        // No font in this renderer reports an x-height before drawing. The SVG specification allows half an em in that case.
        "ex" -> Length(em = value * 0.5)

        "rem" -> Length(px = value * 16.0)

        "pt" -> Length(px = value * 96.0 / 72.0)

        "pc" -> Length(px = value * 16.0)

        "mm" -> Length(px = value * 96.0 / 25.4)

        "cm" -> Length(px = value * 96.0 / 2.54)

        "in" -> Length(px = value * 96.0)

        "q" -> Length(px = value * 96.0 / 101.6)

        // Viewport units behave as a percentage of the viewport, which is exactly right along their own axis.
        "vw", "vh", "vmin", "vmax" -> Length(pct = value)

        else -> null
    }

/** Parses `calc()` into the same sum-of-parts form, so that animation does not need to know it was ever an expression. */
private class CalcParser(private val text: String) {
    private var pos = 0

    private class Term(val length: Length, val isScalar: Boolean)

    fun parse(): Length? {
        val result = factor() ?: return null
        skipSpace()
        return result.length.takeIf { pos == text.length }
    }

    private fun skipSpace() {
        while (pos < text.length && isSvgSpace(text[pos])) pos++
    }

    private fun expression(): Term? {
        var left = product() ?: return null
        while (true) {
            skipSpace()
            val c = text.getOrNull(pos)
            if (c != '+' && c != '-') return left
            pos++
            val right = product() ?: return null
            left = Term(if (c == '+') left.length + right.length else left.length - right.length, left.isScalar && right.isScalar)
        }
    }

    private fun product(): Term? {
        var left = factor() ?: return null
        while (true) {
            skipSpace()
            val c = text.getOrNull(pos)
            if (c != '*' && c != '/') return left
            pos++
            val right = factor() ?: return null
            left =
                when {
                    c == '*' && right.isScalar -> Term(left.length * right.length.px, left.isScalar)
                    c == '*' && left.isScalar -> Term(right.length * left.length.px, false)
                    c == '/' && right.isScalar && right.length.px != 0.0 -> Term(left.length * (1.0 / right.length.px), left.isScalar)
                    else -> return null
                }
        }
    }

    private fun factor(): Term? {
        skipSpace()
        if (text.startsWith("calc(", pos, ignoreCase = true)) pos += 4
        if (text.getOrNull(pos) == '(') {
            pos++
            val inner = expression() ?: return null
            skipSpace()
            if (text.getOrNull(pos) != ')') return null
            pos++
            return inner
        }
        val scanner = NumberScanner(text, pos)
        val value = scanner.number() ?: return null
        var end = scanner.pos
        while (end < text.length && (text[end].isLetter() || text[end] == '%')) end++
        val unit = text.substring(scanner.pos, end)
        val length = lengthWithUnit(value, unit) ?: return null
        pos = end
        return Term(length, unit.isEmpty())
    }
}

/** Splits on whitespace and commas, but never inside parentheses, so `calc(1px + 2px) 3px` stays two items. */
internal fun splitList(text: String): List<String> {
    val out = ArrayList<String>()
    var depth = 0
    var start = -1
    for (i in text.indices) {
        val c = text[i]
        if (c == '(') depth++
        if (c == ')') depth--
        if (depth <= 0 && (c == ',' || isSvgSpace(c))) {
            if (start >= 0) out += text.substring(start, i)
            start = -1
        } else if (start < 0) {
            start = i
        }
    }
    if (start >= 0) out += text.substring(start)
    return out
}

/** Splits on one separator outside parentheses and quotes: a data URI inside `url()` may hold both commas and semicolons. */
internal fun splitTopLevel(text: String, separator: Char): List<String> {
    val out = ArrayList<String>()
    var depth = 0
    var quote = '\u0000'
    var start = 0
    for (i in text.indices) {
        val c = text[i]
        when {
            quote != '\u0000' -> {
                if (c == quote) quote = '\u0000'
            }

            c == '"' || c == '\'' -> {
                quote = c
            }

            c == '(' -> {
                depth++
            }

            c == ')' -> {
                depth--
            }

            c == separator && depth <= 0 -> {
                out += text.substring(start, i)
                start = i + 1
            }
        }
    }
    out += text.substring(start)
    return out
}

internal fun parseLengthList(raw: String): List<Length>? {
    val parts = splitList(raw)
    if (parts.isEmpty()) return null
    val out = ArrayList<Length>(parts.size)
    for (part in parts) out += parseLengthOrNull(part) ?: return null
    return out
}

internal fun parseNumberList(raw: String): List<Double>? {
    val scanner = NumberScanner(raw)
    val out = ArrayList<Double>()
    scanner.skipWhitespace()
    while (!scanner.atEnd) {
        out += scanner.number() ?: return null
        scanner.skipSeparators()
    }
    return out
}

/** SMIL clock values: `2s`, `500ms`, `1.5min`, `1h`, `02:30`, `01:02:03.5` or a bare number of seconds. */
internal fun parseClock(raw: String): Double? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    if (':' in text) {
        val parts = text.split(':')
        if (parts.size !in 2..3) return null
        var seconds = 0.0
        for (part in parts) {
            val value = part.trim().toUnsignedNumber() ?: return null
            seconds = seconds * 60.0 + value
        }
        return seconds
    }
    val scanner = NumberScanner(text)
    val value = scanner.number() ?: return null
    return when (text.substring(scanner.pos).trim().lowercase()) {
        "", "s" -> value
        "ms" -> value / 1000.0
        "min" -> value * 60.0
        "h" -> value * 3600.0
        else -> null
    }
}

private fun String.toUnsignedNumber(): Double? {
    if (isEmpty() || this[0] == '-' || this[0] == '+') return null
    return parseNumberOrNull(this)
}

/** CSS times must carry a unit. That is what tells a duration apart from an iteration count in the `animation` shorthand. */
internal fun parseCssTime(raw: String): Double? {
    val text = raw.trim().lowercase()
    val scanner = NumberScanner(text)
    val value = scanner.number() ?: return null
    return when (text.substring(scanner.pos)) {
        "s" -> value
        "ms" -> value / 1000.0
        else -> null
    }
}

/** An angle in degrees. A bare number counts as degrees, because that is what SVG transform attributes mean by it. */
internal fun parseAngle(raw: String): Double? {
    val text = raw.trim().lowercase()
    val scanner = NumberScanner(text)
    val value = scanner.number() ?: return null
    return when (text.substring(scanner.pos)) {
        "", "deg" -> value
        "rad" -> Math.toDegrees(value)
        "grad" -> value * 0.9
        "turn" -> value * 360.0
        else -> null
    }
}
