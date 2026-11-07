package org.kineograph.media.svg

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/** Colours as they appear in files: every CSS name, hex, and the colour functions of CSS Color 4, as far as a picture can use them. */
internal fun parseColor(raw: String): Rgba? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    if (text[0] == '#') return parseHex(text)
    val lower = text.lowercase()
    if (lower == "transparent") return Rgba.TRANSPARENT
    NAMED_COLORS[lower]?.let { return Rgba.fromRgb(it) }
    SYSTEM_COLORS[lower]?.let { return Rgba.fromRgb(it) }
    val open = lower.indexOf('(')
    if (open < 0 || !lower.endsWith(")")) return null
    val args = lower.substring(open + 1, lower.length - 1)
    return when (lower.substring(0, open).trim()) {
        "rgb", "rgba" -> parseRgbFunction(args)
        "hsl", "hsla" -> parseHslFunction(args)
        "hwb" -> parseHwbFunction(args)
        "lab" -> parseLabFunction(args, oklab = false)
        "oklab" -> parseLabFunction(args, oklab = true)
        "lch" -> parseLchFunction(args, oklch = false)
        "oklch" -> parseLchFunction(args, oklch = true)
        "color" -> parseColorFunction(args)
        "color-mix" -> parseColorMix(args)
        else -> null
    }
}

private fun parseHex(text: String): Rgba? {
    val digits = text.substring(1)
    val nibbles = IntArray(digits.length)
    for (i in digits.indices) {
        val nibble = Character.digit(digits[i], 16)
        if (nibble < 0) return null
        nibbles[i] = nibble
    }
    fun doubled(i: Int): Double = (nibbles[i] * 17).toDouble()

    fun pair(i: Int): Double = (nibbles[i] * 16 + nibbles[i + 1]).toDouble()
    return when (digits.length) {
        3 -> Rgba(doubled(0), doubled(1), doubled(2))
        4 -> Rgba(doubled(0), doubled(1), doubled(2), doubled(3) / 255.0)
        6 -> Rgba(pair(0), pair(2), pair(4))
        8 -> Rgba(pair(0), pair(2), pair(4), pair(6) / 255.0)
        else -> null
    }
}

/** The two ways of writing arguments: the old one has commas, and the newer one spaces, a slash before the opacity, and `none`. */
private class ColorArguments(val parts: List<String>, val alpha: String?, val hasCommas: Boolean)

private fun functionArguments(args: String): ColorArguments? {
    if (',' in args) {
        val parts = splitTopLevel(args, ',').map { it.trim() }
        if (parts.size !in 3..4 || parts.any { it.isEmpty() || splitList(it).size != 1 }) return null
        return ColorArguments(parts.subList(0, 3), parts.getOrNull(3), hasCommas = true)
    }
    val slash = args.indexOf('/')
    val parts = splitList(if (slash >= 0) args.substring(0, slash) else args)
    if (parts.size != 3) return null
    return ColorArguments(parts, if (slash >= 0) args.substring(slash + 1).trim() else null, hasCommas = false)
}

private fun alphaOf(arguments: ColorArguments): Double? {
    val text = arguments.alpha ?: return 1.0
    if (text == "none") return if (arguments.hasCommas) null else 0.0
    return parseFraction(text)?.coerceIn(0.0, 1.0)
}

/** A number or percentage, where 100% means [full]. `none` counts as zero, but only the newer way of writing allows it. */
private fun amount(text: String, full: Double, arguments: ColorArguments): Double? =
    when {
        text == "none" -> if (arguments.hasCommas) null else 0.0
        text.endsWith("%") -> parseNumberOrNull(text.dropLast(1))?.let { it * full / 100.0 }
        else -> parseNumberOrNull(text)
    }

private fun parseRgbFunction(args: String): Rgba? {
    val arguments = functionArguments(args) ?: return null
    val parts = arguments.parts
    // With commas, it has to be all percentages or none of them.
    if (arguments.hasCommas && parts.map { it.endsWith("%") }.distinct().size != 1) return null
    val r = amount(parts[0], 255.0, arguments) ?: return null
    val g = amount(parts[1], 255.0, arguments) ?: return null
    val b = amount(parts[2], 255.0, arguments) ?: return null
    val a = alphaOf(arguments) ?: return null
    return Rgba(r.coerceIn(0.0, 255.0), g.coerceIn(0.0, 255.0), b.coerceIn(0.0, 255.0), a)
}

private fun hueOf(text: String, arguments: ColorArguments): Double? = if (text == "none" && !arguments.hasCommas) 0.0 else parseAngle(text)

private fun parseHslFunction(args: String): Rgba? {
    val arguments = functionArguments(args) ?: return null
    val parts = arguments.parts
    val hue = hueOf(parts[0], arguments) ?: return null
    // With commas these must be percentages. Without, a plain number means the same as a percentage.
    if (arguments.hasCommas && (!parts[1].endsWith("%") || !parts[2].endsWith("%"))) return null
    val saturation = amount(parts[1], 100.0, arguments) ?: return null
    val lightness = amount(parts[2], 100.0, arguments) ?: return null
    val alpha = alphaOf(arguments) ?: return null
    return hslToRgba(hue, (saturation / 100.0).coerceIn(0.0, 1.0), (lightness / 100.0).coerceIn(0.0, 1.0), alpha)
}

private fun parseHwbFunction(args: String): Rgba? {
    val arguments = functionArguments(args)?.takeIf { !it.hasCommas } ?: return null
    val parts = arguments.parts
    val hue = hueOf(parts[0], arguments) ?: return null
    var white = (amount(parts[1], 100.0, arguments) ?: return null).coerceIn(0.0, 100.0) / 100.0
    var black = (amount(parts[2], 100.0, arguments) ?: return null).coerceIn(0.0, 100.0) / 100.0
    val alpha = alphaOf(arguments) ?: return null
    val total = white + black
    if (total >= 1.0) {
        white /= total
        black /= total
    }
    val pure = hslToRgba(hue, 1.0, 0.5, alpha)
    fun channel(value: Double): Double = (value / 255.0 * (1.0 - white - black) + white) * 255.0
    return Rgba(channel(pure.r), channel(pure.g), channel(pure.b), alpha)
}

private fun parseLabFunction(args: String, oklab: Boolean): Rgba? {
    val arguments = functionArguments(args)?.takeIf { !it.hasCommas } ?: return null
    val parts = arguments.parts
    val lightness = amount(parts[0], if (oklab) 1.0 else 100.0, arguments) ?: return null
    val a = amount(parts[1], if (oklab) 0.4 else 125.0, arguments) ?: return null
    val b = amount(parts[2], if (oklab) 0.4 else 125.0, arguments) ?: return null
    val alpha = alphaOf(arguments) ?: return null
    return if (oklab) oklabToRgba(lightness.coerceIn(0.0, 1.0), a, b, alpha) else labToRgba(lightness.coerceIn(0.0, 100.0), a, b, alpha)
}

private fun parseLchFunction(args: String, oklch: Boolean): Rgba? {
    val arguments = functionArguments(args)?.takeIf { !it.hasCommas } ?: return null
    val parts = arguments.parts
    val lightness = amount(parts[0], if (oklch) 1.0 else 100.0, arguments) ?: return null
    val chroma = (amount(parts[1], if (oklch) 0.4 else 150.0, arguments) ?: return null).coerceAtLeast(0.0)
    val hue = hueOf(parts[2], arguments) ?: return null
    val alpha = alphaOf(arguments) ?: return null
    val a = chroma * cos(Math.toRadians(hue))
    val b = chroma * sin(Math.toRadians(hue))
    return if (oklch) oklabToRgba(lightness.coerceIn(0.0, 1.0), a, b, alpha) else labToRgba(lightness.coerceIn(0.0, 100.0), a, b, alpha)
}

private fun parseColorFunction(args: String): Rgba? {
    val slash = args.indexOf('/')
    val parts = splitList(if (slash >= 0) args.substring(0, slash) else args)
    if (parts.size != 4) return null
    val arguments = ColorArguments(parts.subList(1, 4), if (slash >= 0) args.substring(slash + 1).trim() else null, hasCommas = false)
    val x = amount(arguments.parts[0], 1.0, arguments) ?: return null
    val y = amount(arguments.parts[1], 1.0, arguments) ?: return null
    val z = amount(arguments.parts[2], 1.0, arguments) ?: return null
    val alpha = alphaOf(arguments) ?: return null
    return when (parts[0]) {
        "srgb" -> Rgba((x * 255.0).coerceIn(0.0, 255.0), (y * 255.0).coerceIn(0.0, 255.0), (z * 255.0).coerceIn(0.0, 255.0), alpha)

        "srgb-linear" -> linearToRgba(x, y, z, alpha)

        "display-p3" -> xyzD65ToRgba(multiply(P3_TO_XYZ, doubleArrayOf(srgbDecode(x), srgbDecode(y), srgbDecode(z))), alpha)

        "rec2020" -> xyzD65ToRgba(multiply(REC2020_TO_XYZ, doubleArrayOf(rec2020Decode(x), rec2020Decode(y), rec2020Decode(z))), alpha)

        "a98-rgb" -> xyzD65ToRgba(multiply(A98_TO_XYZ, doubleArrayOf(a98Decode(x), a98Decode(y), a98Decode(z))), alpha)

        "prophoto-rgb" -> xyzD50ToRgba(
            multiply(PROPHOTO_TO_XYZ, doubleArrayOf(prophotoDecode(x), prophotoDecode(y), prophotoDecode(z))),
            alpha,
        )

        "xyz", "xyz-d65" -> xyzD65ToRgba(doubleArrayOf(x, y, z), alpha)

        "xyz-d50" -> xyzD50ToRgba(doubleArrayOf(x, y, z), alpha)

        else -> null
    }
}

/** `color-mix(in space, a p%, b q%)`. The round spaces are mixed through their nearest straight one, which only changes the path taken. */
private fun parseColorMix(args: String): Rgba? {
    val parts = splitTopLevel(args, ',').map { it.trim() }
    if (parts.size != 3 || !parts[0].startsWith("in ")) return null
    val space = parts[0].removePrefix("in ").trim().substringBefore(' ')
    val first = parseMixPart(parts[1]) ?: return null
    val second = parseMixPart(parts[2]) ?: return null
    var share1 = first.second
    var share2 = second.second
    if (share1 == null && share2 == null) {
        share1 = 0.5
        share2 = 0.5
    } else if (share1 == null) {
        share1 = 1.0 - (share2 ?: 0.0)
    } else if (share2 == null) {
        share2 = 1.0 - share1
    }
    val a = share1 ?: return null
    val b = share2 ?: return null
    val total = a + b
    if (total <= 0.0) return null
    val mixed = mixIn(space, first.first, second.first, b / total)
    return mixed.copy(a = mixed.a * minOf(total, 1.0))
}

private fun parseMixPart(text: String): Pair<Rgba, Double?>? {
    val tokens = splitList(text)
    val percent = tokens.firstOrNull { it.endsWith("%") }
    val color = tokens.filter { it != percent }.joinToString(" ")
    return (parseColor(color) ?: return null) to percent?.let(::parseFraction)
}

private fun mixIn(space: String, x: Rgba, y: Rgba, p: Double): Rgba =
    when (space) {
        "srgb" -> {
            x.mix(y, p)
        }

        "srgb-linear" -> {
            val a = linearOf(x)
            val b = linearOf(y)
            val alpha = x.a + (y.a - x.a) * p
            mixPremultiplied(a, b, x.a, y.a, p, alpha) { c -> linearToRgba(c[0], c[1], c[2], alpha) }
        }

        else -> {
            val a = rgbToOklab(x)
            val b = rgbToOklab(y)
            val alpha = x.a + (y.a - x.a) * p
            mixPremultiplied(a, b, x.a, y.a, p, alpha) { c -> oklabToRgba(c[0], c[1], c[2], alpha) }
        }
    }

private inline fun mixPremultiplied(
    a: DoubleArray,
    b: DoubleArray,
    alphaA: Double,
    alphaB: Double,
    p: Double,
    alpha: Double,
    build: (DoubleArray) -> Rgba,
): Rgba {
    if (alpha <= 0.0) return Rgba.TRANSPARENT
    return build(DoubleArray(3) { (a[it] * alphaA + (b[it] * alphaB - a[it] * alphaA) * p) / alpha })
}

private fun srgbDecode(value: Double): Double {
    val magnitude = abs(value)
    val linear = if (magnitude <= 0.04045) magnitude / 12.92 else ((magnitude + 0.055) / 1.055).pow(2.4)
    return if (value < 0.0) -linear else linear
}

private fun srgbEncode(value: Double): Double {
    val magnitude = abs(value)
    val encoded = if (magnitude <= 0.0031308) magnitude * 12.92 else 1.055 * magnitude.pow(1.0 / 2.4) - 0.055
    return if (value < 0.0) -encoded else encoded
}

private fun rec2020Decode(value: Double): Double {
    val magnitude = abs(value)
    val linear = if (magnitude <
        0.018053968510807 * 4.5
    ) {
        magnitude / 4.5
    } else {
        ((magnitude + 0.09929682680944) / 1.09929682680944).pow(1.0 / 0.45)
    }
    return if (value < 0.0) -linear else linear
}

private fun a98Decode(value: Double): Double = if (value < 0.0) -abs(value).pow(563.0 / 256.0) else value.pow(563.0 / 256.0)

private fun prophotoDecode(value: Double): Double {
    val magnitude = abs(value)
    val linear = if (magnitude <= 16.0 / 512.0) magnitude / 16.0 else magnitude.pow(1.8)
    return if (value < 0.0) -linear else linear
}

private fun multiply(m: DoubleArray, v: DoubleArray): DoubleArray =
    DoubleArray(3) { row -> m[row * 3] * v[0] + m[row * 3 + 1] * v[1] + m[row * 3 + 2] * v[2] }

/** What falls outside what the screen can show is cut off at the edge, which is what browsers do when drawing in sRGB. */
private fun linearToRgba(r: Double, g: Double, b: Double, a: Double): Rgba =
    Rgba(
        (srgbEncode(r) * 255.0).coerceIn(0.0, 255.0),
        (srgbEncode(g) * 255.0).coerceIn(0.0, 255.0),
        (srgbEncode(b) * 255.0).coerceIn(0.0, 255.0),
        a,
    )

private fun linearOf(c: Rgba): DoubleArray = doubleArrayOf(srgbDecode(c.r / 255.0), srgbDecode(c.g / 255.0), srgbDecode(c.b / 255.0))

private fun xyzD65ToRgba(xyz: DoubleArray, a: Double): Rgba {
    val linear = multiply(XYZ_TO_LINEAR_SRGB, xyz)
    return linearToRgba(linear[0], linear[1], linear[2], a)
}

private fun xyzD50ToRgba(xyz: DoubleArray, a: Double): Rgba = xyzD65ToRgba(multiply(D50_TO_D65, xyz), a)

private fun labToRgba(lightness: Double, a: Double, b: Double, alpha: Double): Rgba {
    val fy = (lightness + 16.0) / 116.0
    val fx = fy + a / 500.0
    val fz = fy - b / 200.0
    fun cubed(f: Double): Double = if (f > 6.0 / 29.0) f * f * f else 108.0 / 841.0 * (f - 4.0 / 29.0)
    return xyzD50ToRgba(doubleArrayOf(cubed(fx) * 0.96429567642956, cubed(fy), cubed(fz) * 0.825104602510460), alpha)
}

private fun oklabToRgba(lightness: Double, a: Double, b: Double, alpha: Double): Rgba {
    val l = lightness + 0.3963377774 * a + 0.2158037573 * b
    val m = lightness - 0.1055613458 * a - 0.0638541728 * b
    val s = lightness - 0.0894841775 * a - 1.2914855480 * b
    val ll = l * l * l
    val mm = m * m * m
    val ss = s * s * s
    return linearToRgba(
        4.0767416621 * ll - 3.3077115913 * mm + 0.2309699292 * ss,
        -1.2684380046 * ll + 2.6097574011 * mm - 0.3413193965 * ss,
        -0.0041960863 * ll - 0.7034186147 * mm + 1.7076147010 * ss,
        alpha,
    )
}

private fun rgbToOklab(c: Rgba): DoubleArray {
    val (r, g, b) = linearOf(c)
    val l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    return doubleArrayOf(
        0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
    )
}

private operator fun DoubleArray.component1(): Double = this[0]

private operator fun DoubleArray.component2(): Double = this[1]

private operator fun DoubleArray.component3(): Double = this[2]

// The standard conversion matrices from the CSS Color specification, row by row.
private val XYZ_TO_LINEAR_SRGB =
    doubleArrayOf(
        3.2409699419045226,
        -1.5373831775700939,
        -0.4986107602930034,
        -0.9692436362808796,
        1.8759675015077204,
        0.0415550574071756,
        0.0556300796969936,
        -0.2039769588889765,
        1.0569715142428784,
    )
private val D50_TO_D65 =
    doubleArrayOf(
        0.9554734527042182,
        -0.0230985368742614,
        0.0632593086610217,
        -0.0283697069632081,
        1.0099954580058226,
        0.0210413989669430,
        0.0123140016883199,
        -0.0205076964334779,
        1.3303659366080753,
    )
private val P3_TO_XYZ =
    doubleArrayOf(
        0.4865709486482162,
        0.2656676931690931,
        0.1982172852343625,
        0.2289745640697488,
        0.6917385218365064,
        0.0792869140937450,
        0.0000000000000000,
        0.0451133818589026,
        1.0439443689009760,
    )
private val REC2020_TO_XYZ =
    doubleArrayOf(
        0.6369580483012914,
        0.1446169035862083,
        0.1688809751641721,
        0.2627002120112671,
        0.6779980715188708,
        0.0593017164698620,
        0.0000000000000000,
        0.0280726930490874,
        1.0609850577107910,
    )
private val A98_TO_XYZ =
    doubleArrayOf(
        0.5766690429101305,
        0.1855582379065463,
        0.1882286462349947,
        0.2973449752505361,
        0.6273635662554661,
        0.0752914584939979,
        0.0270313613864123,
        0.0706888525358272,
        0.9913375368376386,
    )
private val PROPHOTO_TO_XYZ =
    doubleArrayOf(
        0.7977604896723027,
        0.1351917082,
        0.0313493495815,
        0.2880711282292934,
        0.7118432178101014,
        0.0000856539606,
        0.0,
        0.0,
        0.8251046025104602,
    )

private fun hslToRgba(hueDegrees: Double, s: Double, l: Double, a: Double): Rgba {
    val h = (hueDegrees - 360.0 * floor(hueDegrees / 360.0)) / 30.0
    val range = s * minOf(l, 1.0 - l)

    fun channel(n: Double): Double {
        val k = (n + h) % 12.0
        return 255.0 * (l - range * minOf(k - 3.0, 9.0 - k, 1.0).coerceAtLeast(-1.0))
    }
    return Rgba(channel(0.0), channel(8.0), channel(4.0), a)
}

/** The names for a page's own colours. A picture has no theme, so these are their values on a plain light page. */
private val SYSTEM_COLORS: Map<String, Int> =
    hashMapOf(
        "canvas" to 0xFFFFFF,
        "canvastext" to 0x000000,
        "linktext" to 0x0000EE,
        "visitedtext" to 0x551A8B,
        "activetext" to 0xFF0000,
        "buttonface" to 0xEFEFEF,
        "buttontext" to 0x000000,
        "buttonborder" to 0x767676,
        "field" to 0xFFFFFF,
        "fieldtext" to 0x000000,
        "highlight" to 0x0078D7,
        "highlighttext" to 0xFFFFFF,
        "graytext" to 0x808080,
    )

private val NAMED_COLORS: Map<String, Int> =
    hashMapOf(
        "aliceblue" to 0xF0F8FF,
        "antiquewhite" to 0xFAEBD7,
        "aqua" to 0x00FFFF,
        "aquamarine" to 0x7FFFD4,
        "azure" to 0xF0FFFF,
        "beige" to 0xF5F5DC,
        "bisque" to 0xFFE4C4,
        "black" to 0x000000,
        "blanchedalmond" to 0xFFEBCD,
        "blue" to 0x0000FF,
        "blueviolet" to 0x8A2BE2,
        "brown" to 0xA52A2A,
        "burlywood" to 0xDEB887,
        "cadetblue" to 0x5F9EA0,
        "chartreuse" to 0x7FFF00,
        "chocolate" to 0xD2691E,
        "coral" to 0xFF7F50,
        "cornflowerblue" to 0x6495ED,
        "cornsilk" to 0xFFF8DC,
        "crimson" to 0xDC143C,
        "cyan" to 0x00FFFF,
        "darkblue" to 0x00008B,
        "darkcyan" to 0x008B8B,
        "darkgoldenrod" to 0xB8860B,
        "darkgray" to 0xA9A9A9,
        "darkgreen" to 0x006400,
        "darkgrey" to 0xA9A9A9,
        "darkkhaki" to 0xBDB76B,
        "darkmagenta" to 0x8B008B,
        "darkolivegreen" to 0x556B2F,
        "darkorange" to 0xFF8C00,
        "darkorchid" to 0x9932CC,
        "darkred" to 0x8B0000,
        "darksalmon" to 0xE9967A,
        "darkseagreen" to 0x8FBC8F,
        "darkslateblue" to 0x483D8B,
        "darkslategray" to 0x2F4F4F,
        "darkslategrey" to 0x2F4F4F,
        "darkturquoise" to 0x00CED1,
        "darkviolet" to 0x9400D3,
        "deeppink" to 0xFF1493,
        "deepskyblue" to 0x00BFFF,
        "dimgray" to 0x696969,
        "dimgrey" to 0x696969,
        "dodgerblue" to 0x1E90FF,
        "firebrick" to 0xB22222,
        "floralwhite" to 0xFFFAF0,
        "forestgreen" to 0x228B22,
        "fuchsia" to 0xFF00FF,
        "gainsboro" to 0xDCDCDC,
        "ghostwhite" to 0xF8F8FF,
        "gold" to 0xFFD700,
        "goldenrod" to 0xDAA520,
        "gray" to 0x808080,
        "green" to 0x008000,
        "greenyellow" to 0xADFF2F,
        "grey" to 0x808080,
        "honeydew" to 0xF0FFF0,
        "hotpink" to 0xFF69B4,
        "indianred" to 0xCD5C5C,
        "indigo" to 0x4B0082,
        "ivory" to 0xFFFFF0,
        "khaki" to 0xF0E68C,
        "lavender" to 0xE6E6FA,
        "lavenderblush" to 0xFFF0F5,
        "lawngreen" to 0x7CFC00,
        "lemonchiffon" to 0xFFFACD,
        "lightblue" to 0xADD8E6,
        "lightcoral" to 0xF08080,
        "lightcyan" to 0xE0FFFF,
        "lightgoldenrodyellow" to 0xFAFAD2,
        "lightgray" to 0xD3D3D3,
        "lightgreen" to 0x90EE90,
        "lightgrey" to 0xD3D3D3,
        "lightpink" to 0xFFB6C1,
        "lightsalmon" to 0xFFA07A,
        "lightseagreen" to 0x20B2AA,
        "lightskyblue" to 0x87CEFA,
        "lightslategray" to 0x778899,
        "lightslategrey" to 0x778899,
        "lightsteelblue" to 0xB0C4DE,
        "lightyellow" to 0xFFFFE0,
        "lime" to 0x00FF00,
        "limegreen" to 0x32CD32,
        "linen" to 0xFAF0E6,
        "magenta" to 0xFF00FF,
        "maroon" to 0x800000,
        "mediumaquamarine" to 0x66CDAA,
        "mediumblue" to 0x0000CD,
        "mediumorchid" to 0xBA55D3,
        "mediumpurple" to 0x9370DB,
        "mediumseagreen" to 0x3CB371,
        "mediumslateblue" to 0x7B68EE,
        "mediumspringgreen" to 0x00FA9A,
        "mediumturquoise" to 0x48D1CC,
        "mediumvioletred" to 0xC71585,
        "midnightblue" to 0x191970,
        "mintcream" to 0xF5FFFA,
        "mistyrose" to 0xFFE4E1,
        "moccasin" to 0xFFE4B5,
        "navajowhite" to 0xFFDEAD,
        "navy" to 0x000080,
        "oldlace" to 0xFDF5E6,
        "olive" to 0x808000,
        "olivedrab" to 0x6B8E23,
        "orange" to 0xFFA500,
        "orangered" to 0xFF4500,
        "orchid" to 0xDA70D6,
        "palegoldenrod" to 0xEEE8AA,
        "palegreen" to 0x98FB98,
        "paleturquoise" to 0xAFEEEE,
        "palevioletred" to 0xDB7093,
        "papayawhip" to 0xFFEFD5,
        "peachpuff" to 0xFFDAB9,
        "peru" to 0xCD853F,
        "pink" to 0xFFC0CB,
        "plum" to 0xDDA0DD,
        "powderblue" to 0xB0E0E6,
        "purple" to 0x800080,
        "rebeccapurple" to 0x663399,
        "red" to 0xFF0000,
        "rosybrown" to 0xBC8F8F,
        "royalblue" to 0x4169E1,
        "saddlebrown" to 0x8B4513,
        "salmon" to 0xFA8072,
        "sandybrown" to 0xF4A460,
        "seagreen" to 0x2E8B57,
        "seashell" to 0xFFF5EE,
        "sienna" to 0xA0522D,
        "silver" to 0xC0C0C0,
        "skyblue" to 0x87CEEB,
        "slateblue" to 0x6A5ACD,
        "slategray" to 0x708090,
        "slategrey" to 0x708090,
        "snow" to 0xFFFAFA,
        "springgreen" to 0x00FF7F,
        "steelblue" to 0x4682B4,
        "tan" to 0xD2B48C,
        "teal" to 0x008080,
        "thistle" to 0xD8BFD8,
        "tomato" to 0xFF6347,
        "turquoise" to 0x40E0D0,
        "violet" to 0xEE82EE,
        "wheat" to 0xF5DEB3,
        "white" to 0xFFFFFF,
        "whitesmoke" to 0xF5F5F5,
        "yellow" to 0xFFFF00,
        "yellowgreen" to 0x9ACD32,
    )
