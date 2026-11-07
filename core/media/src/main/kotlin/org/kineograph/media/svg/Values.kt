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
     * equal this reduces to plain channel mixing.
     */
    fun mix(other: Rgba, p: Double): Rgba {
        val alpha = (a + (other.a - a) * p).coerceIn(0.0, 1.0)
        if (alpha <= 0.0) return Rgba(0.0, 0.0, 0.0, 0.0)
        fun channel(x: Double, y: Double): Double = ((x * a + (y * other.a - x * a) * p) / alpha).coerceIn(0.0, 255.0)
        return Rgba(channel(r, other.r), channel(g, other.g), channel(b, other.b), alpha)
    }

    /** SMIL works on each channel on its own, opacity included, which is not the same as CSS once the two opacities differ. */
    fun mixStraight(other: Rgba, p: Double): Rgba =
        Rgba(
            (r + (other.r - r) * p).coerceIn(0.0, 255.0),
            (g + (other.g - g) * p).coerceIn(0.0, 255.0),
            (b + (other.b - b) * p).coerceIn(0.0, 255.0),
            (a + (other.a - a) * p).coerceIn(0.0, 1.0),
        )

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

/** A `clip-path` shape part way through an animation. A shape that is not moving stays as text, like any other value of that property. */
internal class ClipShapeValue(val shape: BasicShape) : SvgValue

/**
 * What `offset-path` says to follow: a path of its own, or one elsewhere in the file. With neither, it is `none`.
 *
 * @property reference the id of an element to take the path from.
 */
internal class OffsetPathValue(val data: PathData?, val reference: String?) : SvgValue

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
     * follow it, so `1em` reads as the number 1 followed by a unit. A dot must have digits after it: browsers treat `5.` as a mistake.
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
            if (i >= n || !text[i].isAsciiDigit()) return null
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
    if (isMathFunction(text)) return CssMath(text).number()
    return if (text.endsWith("%")) parseNumberOrNull(text.dropLast(1))?.div(100.0) else parseNumberOrNull(text)
}

/** A plain number, or one worked out by a math function, as CSS allows wherever it wants a number. */
internal fun parseCssNumber(raw: String): Double? {
    val text = raw.trim()
    return if (isMathFunction(text)) CssMath(text).number()?.takeIf { '%' !in text } else parseNumberOrNull(text)
}

internal fun parseLengthOrNull(raw: String): Length? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    if (isMathFunction(text)) return CssMath(text).length()
    val scanner = NumberScanner(text)
    val value = scanner.number() ?: return null
    return lengthWithUnit(value, text.substring(scanner.pos))
}

/**
 * `rem` depends on the file's outermost element, which a length cannot know by itself, so it becomes plain pixels before it is read.
 *
 * Written by hand because a regular expression would try again from every digit of a long number, and take quadratic time.
 */
internal fun resolveRem(text: String, rootFontSize: Double): String {
    if (!text.contains("rem", ignoreCase = true)) return text
    var out: StringBuilder? = null
    var copied = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (!c.isAsciiDigit() && c != '.' && c != '+' && c != '-') {
            i++
            continue
        }
        val end = numberEnd(text, i)
        val isRem =
            end > 0 && text.regionMatches(end, "rem", 0, 3, ignoreCase = true) &&
                !(end + 3 < text.length && (text[end + 3].isLetterOrDigit() || text[end + 3] == '_'))
        val value = if (isRem) text.substring(i, end).toDoubleOrNull() else null
        if (value != null) {
            val builder = out ?: StringBuilder(text.length).also { out = it }
            builder.append(text, copied, i).append(value * rootFontSize).append("px")
            i = end + 3
            copied = i
        } else if (c.isAsciiDigit()) {
            // Starting again from any other digit of this run would only end up in the same place.
            while (i < text.length && text[i].isAsciiDigit()) i++
        } else {
            i++
        }
    }
    val builder = out ?: return text
    return builder.append(text, copied, text.length).toString()
}

/** Where the number starting exactly at [start] ends, or -1 when none starts there. */
private fun numberEnd(text: String, start: Int): Int {
    val n = text.length
    var i = start
    if (i < n && (text[i] == '+' || text[i] == '-')) i++
    val digitsStart = i
    while (i < n && text[i].isAsciiDigit()) i++
    val hasWhole = i > digitsStart
    if (i + 1 < n && text[i] == '.' && text[i + 1].isAsciiDigit()) {
        i++
        while (i < n && text[i].isAsciiDigit()) i++
    } else if (!hasWhole) {
        return -1
    }
    if (i < n && (text[i] == 'e' || text[i] == 'E')) {
        var j = i + 1
        if (j < n && (text[j] == '+' || text[j] == '-')) j++
        val exponentStart = j
        while (j < n && text[j].isAsciiDigit()) j++
        if (j > exponentStart) i = j
    }
    return i
}

/** CSS wants a unit on every length and angle, with zero as the one exception. Text that is a bare number fails this. */
internal fun hasCssUnit(text: String): Boolean {
    val trimmed = text.trim()
    val number = parseNumberOrNull(trimmed) ?: return true
    return number == 0.0
}

/** Null for a unit we do not know, and for a number so big that the unit takes it past infinity, which no drawing code should meet. */
internal fun lengthWithUnit(value: Double, unit: String): Length? =
    when (unit.lowercase()) {
        "", "px" -> Length(px = value)

        "%" -> Length(pct = value)

        "em" -> Length(em = value)

        // No font in this renderer reports an x-height before drawing. The SVG specification allows half an em in that case.
        "ex" -> Length(em = value * 0.5)

        "pt" -> Length(px = value * 96.0 / 72.0)

        "pc" -> Length(px = value * 16.0)

        "mm" -> Length(px = value * 96.0 / 25.4)

        "cm" -> Length(px = value * 96.0 / 2.54)

        "in" -> Length(px = value * 96.0)

        "q" -> Length(px = value * 96.0 / 101.6)

        // Viewport units behave as a percentage of the viewport, which is exactly right along their own axis.
        "vw", "vh", "vmin", "vmax" -> Length(pct = value)

        else -> null
    }?.takeIf { it.isFinite() }

private fun Length.isFinite(): Boolean = px.isFinite() && pct.isFinite() && em.isFinite()

/** Whether this text is one of the CSS math functions, which can stand wherever a number, a length, an angle or a time can. */
internal fun isMathFunction(text: String): Boolean =
    text.startsWith("calc(", ignoreCase = true) ||
        text.startsWith("min(", ignoreCase = true) ||
        text.startsWith("max(", ignoreCase = true) ||
        text.startsWith("clamp(", ignoreCase = true)

/**
 * Works out `calc()`, `min()`, `max()` and `clamp()`. By the time this runs, any `var()` has been replaced, which is how a file staggers its parts
 * with `calc(var(--i) * -0.25s)`. A length comes out in the same sum-of-parts form as any other, so that animation does not need to know it was ever
 * an expression.
 *
 * Only `min()` and friends with one kind of length can be settled here. Comparing `10%` with `30px` has to wait until the percentage means something,
 * and no value has a way to wait for that, so that one counts as unreadable.
 */
internal class CssMath(private val text: String) {
    private var pos = 0
    private var depth = 0

    private enum class Kind { NUMBER, LENGTH, ANGLE, TIME }

    /** [amount] is the value for all but a length, which needs [length]. An angle is in degrees, and a time in seconds. */
    private class Term(val kind: Kind, val amount: Double, val length: Length) {
        val isFinite: Boolean get() = amount.isFinite() && length.px.isFinite() && length.pct.isFinite() && length.em.isFinite()

        fun times(k: Double): Term = Term(kind, amount * k, length * k)
    }

    /** Numbers that are fine by themselves can still multiply past infinity, or make nothing at all out of zero and infinity. */
    private fun whole(): Term? {
        val result = factor() ?: return null
        skipSpace()
        return result.takeIf { pos == text.length && it.isFinite }
    }

    /** A bare number counts as pixels, because a length in an attribute needs no unit, and a math function is no different there. */
    fun length(): Length? =
        whole()?.let {
            when (it.kind) {
                Kind.LENGTH -> it.length
                Kind.NUMBER -> Length(px = it.amount)
                else -> null
            }
        }

    /** A percentage counts as its fraction, because that is what the properties that ask for a number here take it as. */
    fun number(): Double? =
        whole()?.let {
            when {
                it.kind == Kind.NUMBER -> it.amount
                it.kind == Kind.LENGTH && it.length.px == 0.0 && it.length.em == 0.0 -> it.length.pct / 100.0
                else -> null
            }
        }

    fun degrees(): Double? = whole()?.takeIf { it.kind == Kind.ANGLE }?.amount

    fun seconds(): Double? = whole()?.takeIf { it.kind == Kind.TIME }?.amount

    private fun skipSpace() {
        while (pos < text.length && isSvgSpace(text[pos])) pos++
    }

    private fun sum(): Term? {
        var left = product() ?: return null
        while (true) {
            skipSpace()
            val c = text.getOrNull(pos)
            if (c != '+' && c != '-') return left
            pos++
            val right = product() ?: return null
            left = (if (c == '+') add(left, right) else add(left, right.times(-1.0))) ?: return null
        }
    }

    private fun add(a: Term, b: Term): Term? =
        when {
            a.kind == b.kind -> Term(a.kind, a.amount + b.amount, a.length + b.length)

            // CSS would refuse this. An attribute's bare numbers are pixels, though, and files that mix them do mean that.
            a.kind == Kind.NUMBER && b.kind == Kind.LENGTH -> Term(Kind.LENGTH, 0.0, Length(px = a.amount) + b.length)

            a.kind == Kind.LENGTH && b.kind == Kind.NUMBER -> Term(Kind.LENGTH, 0.0, a.length + Length(px = b.amount))

            else -> null
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
                    c == '*' && right.kind == Kind.NUMBER -> left.times(right.amount)
                    c == '*' && left.kind == Kind.NUMBER -> right.times(left.amount)
                    c == '/' && right.kind == Kind.NUMBER && right.amount != 0.0 -> left.times(1.0 / right.amount)
                    else -> return null
                }
        }
    }

    private fun factor(): Term? {
        skipSpace()
        val function = functionAt()
        if (function != null) pos += function.length
        if (text.getOrNull(pos) == '(') {
            pos++
            // Each level is a few frames of stack. No real expression has this many brackets.
            if (++depth > MAX_DEPTH) return null
            val inner = if (function == null || function == "calc") sum() else compare(function)
            depth--
            if (inner == null) return null
            skipSpace()
            if (text.getOrNull(pos) != ')') return null
            pos++
            return inner
        }
        val scanner = NumberScanner(text, pos)
        val value = scanner.number() ?: return null
        var end = scanner.pos
        while (end < text.length && (text[end].isLetter() || text[end] == '%')) end++
        val term = termFor(value, text.substring(scanner.pos, end)) ?: return null
        pos = end
        return term
    }

    private fun functionAt(): String? {
        for (name in FUNCTIONS) {
            if (text.startsWith(name, pos, ignoreCase = true) && text.getOrNull(pos + name.length) == '(') return name
        }
        return null
    }

    private fun termFor(value: Double, unit: String): Term? {
        val lower = unit.lowercase()
        return when {
            lower.isEmpty() -> Term(Kind.NUMBER, value, Length.ZERO)
            lower == "s" -> Term(Kind.TIME, value, Length.ZERO)
            lower == "ms" -> Term(Kind.TIME, value / 1000.0, Length.ZERO)
            lower == "deg" -> Term(Kind.ANGLE, value, Length.ZERO)
            lower == "rad" -> Term(Kind.ANGLE, Math.toDegrees(value), Length.ZERO)
            lower == "grad" -> Term(Kind.ANGLE, value * 0.9, Length.ZERO)
            lower == "turn" -> Term(Kind.ANGLE, value * 360.0, Length.ZERO)
            else -> lengthWithUnit(value, unit)?.let { Term(Kind.LENGTH, 0.0, it) }
        }
    }

    private fun compare(function: String): Term? {
        val terms = ArrayList<Term>(3)
        while (true) {
            terms += sum() ?: return null
            skipSpace()
            if (text.getOrNull(pos) != ',') break
            pos++
        }
        if (function == "clamp" && terms.size != 3) return null
        val kind = terms[0].kind
        if (terms.any { it.kind != kind }) return null
        val values = DoubleArray(terms.size) { comparable(terms[it], terms[0]) ?: return null }

        fun pick(index: Int): Term = terms[index]

        fun indexOfSmallest(among: IntRange): Int = among.minBy { values[it] }

        fun indexOfLargest(among: IntRange): Int = among.maxBy { values[it] }
        return when (function) {
            "min" -> {
                pick(indexOfSmallest(terms.indices))
            }

            "max" -> {
                pick(indexOfLargest(terms.indices))
            }

            else -> {
                // A low bound above the high one wins, as CSS says.
                val capped = if (values[1] <= values[2]) 1 else 2
                if (values[0] >= values[capped]) pick(0) else pick(capped)
            }
        }
    }

    /** What to compare [term] by, which has to mean the same as for [first]. Null when only the drawing could say which is larger. */
    private fun comparable(term: Term, first: Term): Double? {
        if (term.kind != Kind.LENGTH) return term.amount
        val a = term.length
        val b = first.length
        return when {
            a.pct == 0.0 && a.em == 0.0 && b.pct == 0.0 && b.em == 0.0 -> a.px
            a.px == 0.0 && a.em == 0.0 && b.px == 0.0 && b.em == 0.0 -> a.pct
            a.px == 0.0 && a.pct == 0.0 && b.px == 0.0 && b.pct == 0.0 -> a.em
            else -> null
        }
    }

    private companion object {
        const val MAX_DEPTH = 64
        val FUNCTIONS = listOf("calc", "min", "max", "clamp")
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
        return seconds.takeIf { it.isFinite() }
    }
    val scanner = NumberScanner(text)
    val value = scanner.number() ?: return null
    return when (text.substring(scanner.pos).trim().lowercase()) {
        "", "s" -> value
        "ms" -> value / 1000.0
        "min" -> value * 60.0
        "h" -> value * 3600.0
        else -> null
    }?.takeIf { it.isFinite() }
}

/**
 * A duration a timeline can work with. Below this, dividing a moment by it stops giving usable numbers, and no frame could show the
 * difference. Above this it lasts longer than any moment that can be asked for.
 */
internal fun usableDuration(seconds: Double): Double = seconds.coerceIn(MIN_DURATION_SECONDS, MAX_TIME_SECONDS)

private const val MIN_DURATION_SECONDS = 1e-6

private fun String.toUnsignedNumber(): Double? {
    if (isEmpty() || this[0] == '-' || this[0] == '+') return null
    return parseNumberOrNull(this)
}

/** CSS times must carry a unit. That is what tells a duration apart from an iteration count in the `animation` shorthand. */
internal fun parseCssTime(raw: String): Double? {
    val text = raw.trim().lowercase()
    if (isMathFunction(text)) return CssMath(text).seconds()
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
    if (isMathFunction(text)) return CssMath(text).degrees()
    val scanner = NumberScanner(text)
    val value = scanner.number() ?: return null
    return when (text.substring(scanner.pos)) {
        "", "deg" -> value
        "rad" -> Math.toDegrees(value)
        "grad" -> value * 0.9
        "turn" -> value * 360.0
        else -> null
    }?.takeIf { it.isFinite() }
}
