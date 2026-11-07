package org.kineograph.media.svg

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * What can be done about a `filter`. Blurs and shadows are out of reach here, but two things can be told from its primitives alone, and both change
 * a picture far more than any blur does.
 */
internal sealed interface FilterPlan {
    /** Cannot be drawn, so the element draws without it, which is usually close: a shadow goes missing, while what casts it stays. */
    data object Skipped : FilterPlan

    /**
     * Nothing it makes comes from the element's own colours, as with a shadow drawn by a copy of a shape. Drawing the element itself instead would
     * paint a black shape over whatever the shadow was meant to fall on. So all of it goes.
     */
    data object DrawsNothing : FilterPlan

    /**
     * Only `feColorMatrix`, which a colour filter on a layer does exactly. Cairo marks up every mask with one, and turning that into nothing would
     * make the mask mean something else.
     *
     * @property matrix in the form android.graphics.ColorMatrix takes.
     * @property isExact false if the matrix has to be worked out on linear light, as is the default, and depends on colours that could tell the
     *   difference. Android's colour filters only work on colours as they are stored, so it is close, and not the same, in the mid tones.
     */
    class Recolour(val matrix: FloatArray, val region: FilterRegion, val isExact: Boolean) : FilterPlan
}

/** Where a filter's result shows. Outside of it nothing does, not even what was drawn there. */
internal class FilterRegion(val inObjectUnits: Boolean, val x: Length, val y: Length, val width: Length, val height: Length)

private val STANDARD_INPUTS = setOf("SourceAlpha", "BackgroundImage", "BackgroundAlpha", "FillPaint", "StrokePaint")

private const val SOURCE_GRAPHIC = "SourceGraphic"
private const val MAX_PRIMITIVES = 256

/** Reads a `filter` element. Each primitive reads earlier results by name, or else the one just before it, and only the last one is what shows. */
internal fun readFilterPlan(filter: SvgElement): FilterPlan {
    val primitives = filter.children.filter { it.tag.startsWith("fe") }
    // The specification is plain about this one: an empty filter draws nothing at all.
    if (primitives.isEmpty()) return FilterPlan.DrawsNothing
    // Looking up a name goes back through those before it, which a file with enough of them could make take for ever. No real filter comes close.
    if (primitives.size > MAX_PRIMITIVES) return FilterPlan.Skipped
    if (!readsSourceGraphic(primitives)) return FilterPlan.DrawsNothing
    return readRecolour(filter, primitives) ?: FilterPlan.Skipped
}

private fun inputsOf(primitive: SvgElement): List<String?> =
    when (primitive.tag) {
        "feFlood", "feImage", "feTurbulence" -> emptyList()
        "feMerge" -> primitive.children.filter { it.tag == "feMergeNode" }.map { it.attrs["in"]?.trim() }
        "feBlend", "feComposite", "feDisplacementMap" -> listOf(primitive.attrs["in"]?.trim(), primitive.attrs["in2"]?.trim())
        else -> listOf(primitive.attrs["in"]?.trim())
    }

/** The index of the primitive a name stands for, the latest before [before] that gave its result that name. */
private fun indexOfInput(name: String?, primitives: List<SvgElement>, before: Int): Int {
    if (!name.isNullOrEmpty()) {
        for (i in before - 1 downTo 0) if (primitives[i].attrs["result"]?.trim() == name) return i
    }
    // A name nothing has, like none at all, means what came just before.
    return before - 1
}

private fun readsSourceGraphic(primitives: List<SvgElement>): Boolean {
    val needed = BooleanArray(primitives.size)
    needed[primitives.lastIndex] = true
    // Each can only read those before it, so one pass from the back gets to everything the last one needs.
    for (i in primitives.indices.reversed()) {
        if (!needed[i]) continue
        for (input in inputsOf(primitives[i])) {
            if (input == SOURCE_GRAPHIC) return true
            if (input in STANDARD_INPUTS) continue
            val from = indexOfInput(input, primitives, i)
            if (from < 0) return true
            needed[from] = true
        }
    }
    return false
}

private fun readRecolour(filter: SvgElement, primitives: List<SvgElement>): FilterPlan.Recolour? {
    if (primitives.any { it.tag != "feColorMatrix" }) return null
    var total = IDENTITY_MATRIX
    for ((index, primitive) in primitives.withIndex()) {
        val input = primitive.attrs["in"]?.trim()
        // A straight line of them, each reading the one before, is what multiplying them together comes to.
        val isNext =
            if (index == 0) {
                input.isNullOrEmpty() || input == SOURCE_GRAPHIC
            } else {
                input != SOURCE_GRAPHIC && input !in STANDARD_INPUTS && indexOfInput(input, primitives, index) == index - 1
            }
        if (!isNext) return null
        total = multiply(matrixOf(primitive) ?: return null, total)
    }
    val onFilter = interpolationOf(filter)
    val onLinearLight = primitives.any { (interpolationOf(it) ?: onFilter) != "srgb" }
    val exact = !onLinearLight || ignoresColours(total)
    return FilterPlan.Recolour(toAndroidMatrix(if (onLinearLight && exact) constantsFromLinear(total) else total), regionOf(filter), exact)
}

/** Inkscape writes this one in a style attribute. A stylesheet could say it as well, but that would have to wait for the cascade, and nothing does that. */
private fun interpolationOf(element: SvgElement): String? {
    val attribute = element.attrs["color-interpolation-filters"]
    val value =
        attribute
            ?: element.attrs["style"]?.let { style ->
                parseDeclarations(style).lastOrNull { it.name == "color-interpolation-filters" }?.value
            }
    return value?.trim()?.lowercase()
}

/** Whether the result depends on nothing but opacity, in which case it makes no difference how the colours are held. */
private fun ignoresColours(m: DoubleArray): Boolean {
    for (row in 0 until 4) {
        for (column in 0 until 3) if (m[row * 5 + column] != 0.0) return false
    }
    // Colour depending on opacity would be a colour worked out on linear light, for each opacity there is.
    for (row in 0 until 3) if (m[row * 5 + 3] != 0.0) return false
    return true
}

/** A colour given outright is still a colour on linear light, and has to become what the same colour looks like as stored. */
private fun constantsFromLinear(m: DoubleArray): DoubleArray {
    val out = m.copyOf()
    for (row in 0 until 3) out[row * 5 + 4] = linearToStored(m[row * 5 + 4].coerceIn(0.0, 1.0))
    return out
}

private fun linearToStored(value: Double): Double = if (value <= 0.0031308) value * 12.92 else 1.055 * value.pow(1.0 / 2.4) - 0.055

private fun regionOf(filter: SvgElement): FilterRegion {
    fun length(name: String, default: Double): Length = filter.attrs[name]?.let(::parseLengthOrNull) ?: Length(pct = default)
    return FilterRegion(
        filter.attrs["filterUnits"]?.trim() != "userSpaceOnUse",
        length("x", -10.0),
        length("y", -10.0),
        length("width", 120.0),
        length("height", 120.0),
    )
}

private val IDENTITY_MATRIX =
    doubleArrayOf(
        1.0,
        0.0,
        0.0,
        0.0,
        0.0,
        0.0,
        1.0,
        0.0,
        0.0,
        0.0,
        0.0,
        0.0,
        1.0,
        0.0,
        0.0,
        0.0,
        0.0,
        0.0,
        1.0,
        0.0,
    )

private const val BYTES_PER_CHAR_OF_NUMBERS = 20

/** Five columns by four rows, row by row, with the last column as a fraction: what the specification writes down. Null if the values make no sense. */
private fun matrixOf(primitive: SvgElement): DoubleArray? {
    // Only twenty numbers can mean anything here, but the file can give millions, and each one is an object until they have been counted.
    val text = primitive.attrs["values"]
    if (text != null && !HeapWatch.hasRoomForText(text.length, BYTES_PER_CHAR_OF_NUMBERS)) return null
    val values = text?.let(::parseNumberList)
    return when (primitive.attrs["type"]?.trim() ?: "matrix") {
        "matrix" -> {
            when {
                values == null || values.isEmpty() -> IDENTITY_MATRIX
                values.size == 20 -> values.toDoubleArray()
                else -> null
            }
        }

        "saturate" -> {
            saturation(values?.firstOrNull() ?: 1.0)
        }

        "hueRotate" -> {
            hueRotation(values?.firstOrNull() ?: 0.0)
        }

        "luminanceToAlpha" -> {
            doubleArrayOf(
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.2125,
                0.7154,
                0.0721,
                0.0,
                0.0,
            )
        }

        else -> {
            null
        }
    }
}

private fun saturation(s: Double): DoubleArray =
    doubleArrayOf(
        0.213 + 0.787 * s,
        0.715 - 0.715 * s,
        0.072 - 0.072 * s,
        0.0,
        0.0,
        0.213 - 0.213 * s,
        0.715 + 0.285 * s,
        0.072 - 0.072 * s,
        0.0,
        0.0,
        0.213 - 0.213 * s,
        0.715 - 0.715 * s,
        0.072 + 0.928 * s,
        0.0,
        0.0,
        0.0,
        0.0,
        0.0,
        1.0,
        0.0,
    )

private fun hueRotation(degrees: Double): DoubleArray {
    val radians = Math.toRadians(degrees)
    val c = cos(radians)
    val s = sin(radians)
    return doubleArrayOf(
        0.213 + c * 0.787 - s * 0.213,
        0.715 - c * 0.715 - s * 0.715,
        0.072 - c * 0.072 + s * 0.928,
        0.0,
        0.0,
        0.213 - c * 0.213 + s * 0.143,
        0.715 + c * 0.285 + s * 0.140,
        0.072 - c * 0.072 - s * 0.283,
        0.0,
        0.0,
        0.213 - c * 0.213 - s * 0.787,
        0.715 - c * 0.715 + s * 0.715,
        0.072 + c * 0.928 + s * 0.072,
        0.0,
        0.0,
        0.0,
        0.0,
        0.0,
        1.0,
        0.0,
    )
}

/** [a] after [b]. Both are really five by five, with a last row that does nothing. */
private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
    val out = DoubleArray(20)
    for (row in 0 until 4) {
        for (column in 0 until 5) {
            var sum = if (column == 4) a[row * 5 + 4] else 0.0
            for (k in 0 until 4) sum += a[row * 5 + k] * b[k * 5 + column]
            out[row * 5 + column] = sum
        }
    }
    return out
}

private const val CHANNEL_SCALE = 255f

/** Android counts the last column in steps of a colour channel, where SVG counts it as a fraction. */
private fun toAndroidMatrix(m: DoubleArray): FloatArray =
    FloatArray(20) { i -> if (i % 5 == 4) m[i].toFloat() * CHANNEL_SCALE else m[i].toFloat() }
