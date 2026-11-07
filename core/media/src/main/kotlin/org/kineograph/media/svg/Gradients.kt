package org.kineograph.media.svg

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.floor
import kotlin.math.sqrt

internal class GradientStop(val offset: Double, val color: Rgba)

/** Builds Android shaders from SVG gradients, smoothing over the places where the two disagree. */
internal object GradientShaders {
    /** Stops added per span where colour and alpha both change. See [expand] for why. Enough that the steps cannot be seen. */
    private const val SUBDIVISIONS = 16

    /** More for linear light, whose curve is steepest right next to a stop, near black. */
    private const val LINEAR_SUBDIVISIONS = 64
    private const val SOFTWARE_FOCAL_SIZE = 256

    /** The focal gradient before API 31 is computed by hand. Tests turn this on to exercise that path on a newer runtime. */
    internal var forceSoftwareFocal: Boolean = false

    fun worksOutFocalByHand(cx: Double, cy: Double, fx: Double, fy: Double, fr: Double): Boolean =
        (fx != cx || fy != cy || fr != 0.0) && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || forceSoftwareFocal)

    /** [matrix] places the gradient in user space: unit box, gradientTransform and all. */
    fun linear(
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        stops: List<GradientStop>,
        spread: Shader.TileMode,
        matrix: Mat2D,
        linearColors: Boolean,
    ): Shader {
        val expanded = expand(stops, linearColors)
        val shader =
            LinearGradient(
                x1.toFloat(),
                y1.toFloat(),
                x2.toFloat(),
                y2.toFloat(),
                colors(expanded),
                positions(expanded),
                spread,
            )
        shader.setLocalMatrix(matrix.toAndroid())
        return shader
    }

    fun radial(
        cx: Double,
        cy: Double,
        r: Double,
        fx: Double,
        fy: Double,
        fr: Double,
        stops: List<GradientStop>,
        spread: Shader.TileMode,
        matrix: Mat2D,
        box: Box?,
        linearColors: Boolean,
    ): Shader {
        val expanded = expand(stops, linearColors)
        // A focal point outside the circle is not moved back in. As SVG 2 says, the gradient then becomes a cone, with nothing outside it.
        val isPlain = fx == cx && fy == cy && fr == 0.0
        if (isPlain) return plainRadial(cx, cy, r, expanded, spread, matrix)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !forceSoftwareFocal) {
            val shader = twoPointRadial(cx, cy, r, fx, fy, fr, expanded, spread)
            shader.setLocalMatrix(matrix.toAndroid())
            return shader
        }
        return softwareFocal(cx, cy, r, fx, fy, fr, expanded, spread, matrix, box)
    }

    private fun plainRadial(cx: Double, cy: Double, r: Double, stops: List<GradientStop>, spread: Shader.TileMode, matrix: Mat2D): Shader {
        val shader = RadialGradient(cx.toFloat(), cy.toFloat(), r.toFloat(), colors(stops), positions(stops), spread)
        shader.setLocalMatrix(matrix.toAndroid())
        return shader
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun twoPointRadial(
        cx: Double,
        cy: Double,
        r: Double,
        fx: Double,
        fy: Double,
        fr: Double,
        stops: List<GradientStop>,
        spread: Shader.TileMode,
    ): Shader {
        val colors = colors(stops)
        return RadialGradient(
            fx.toFloat(),
            fy.toFloat(),
            fr.toFloat(),
            cx.toFloat(),
            cy.toFloat(),
            r.toFloat(),
            LongArray(colors.size) { Color.pack(colors[it]) },
            positions(stops),
            spread,
        )
    }

    /**
     * Android blends gradient colours after multiplying them by alpha, while SVG blends them before, so `red` to transparent `blue`
     * comes out with no purple in it. Extra stops computed the SVG way pin the result to the right curve. Android also only blends in
     * sRGB, so `color-interpolation: linearRGB` goes the same way.
     */
    private fun expand(stops: List<GradientStop>, linearColors: Boolean): List<GradientStop> {
        val needsHelp =
            linearColors ||
                (0 until stops.size - 1).any { i ->
                    val a = stops[i].color
                    val b = stops[i + 1].color
                    a.a != b.a && (a.r != b.r || a.g != b.g || a.b != b.b) && stops[i].offset < stops[i + 1].offset
                }
        if (!needsHelp) return stops
        val subdivisions = if (linearColors) LINEAR_SUBDIVISIONS else SUBDIVISIONS
        val out = ArrayList<GradientStop>(stops.size * subdivisions)
        for (i in 0 until stops.size - 1) {
            val a = stops[i]
            val b = stops[i + 1]
            out += a
            val differs =
                linearColors || (a.color.a != b.color.a && (a.color.r != b.color.r || a.color.g != b.color.g || a.color.b != b.color.b))
            if (differs && a.offset < b.offset) {
                for (k in 1 until subdivisions) {
                    val p = k.toDouble() / subdivisions
                    val color = if (linearColors) linearMix(a.color, b.color, p) else straightMix(a.color, b.color, p)
                    out += GradientStop(a.offset + (b.offset - a.offset) * p, color)
                }
            }
        }
        out += stops.last()
        return out
    }

    private fun straightMix(a: Rgba, b: Rgba, p: Double): Rgba =
        Rgba(a.r + (b.r - a.r) * p, a.g + (b.g - a.g) * p, a.b + (b.b - a.b) * p, a.a + (b.a - a.a) * p)

    private fun linearMix(a: Rgba, b: Rgba, p: Double): Rgba {
        fun channel(x: Double, y: Double): Double {
            val from = srgbDecode(x / 255.0)
            return srgbEncode(from + (srgbDecode(y / 255.0) - from) * p) * 255.0
        }
        return Rgba(channel(a.r, b.r), channel(a.g, b.g), channel(a.b, b.b), a.a + (b.a - a.a) * p)
    }

    private fun colors(stops: List<GradientStop>): IntArray = IntArray(stops.size) { stops[it].color.toArgb() }

    private fun positions(stops: List<GradientStop>): FloatArray = FloatArray(stops.size) { stops[it].offset.toFloat() }

    /**
     * Before API 31 Android cannot move a radial gradient's focal point, and a picture must look the same on every phone. The colours
     * are worked out per pixel, into a small image stretched across the shape.
     */
    private fun softwareFocal(
        cx: Double,
        cy: Double,
        r: Double,
        fx: Double,
        fy: Double,
        fr: Double,
        stops: List<GradientStop>,
        spread: Shader.TileMode,
        matrix: Mat2D,
        box: Box?,
    ): Shader {
        // Without a box to cover, or without an inverse to compute with, a centred gradient is the closest thing left.
        val inverse = matrix.inverse()
        if (box == null || inverse == null || box.w <= 0.0 || box.h <= 0.0) {
            return plainRadial(cx, cy, r, stops, spread, matrix)
        }
        // Strokes reach outside the box, and a little margin keeps them from smearing the edge pixels outwards.
        val area = box.outset(maxOf(box.w, box.h) * 0.25)
        val size = SOFTWARE_FOCAL_SIZE
        val pixels = IntArray(size * size)
        val table = colorTable(stops)
        for (row in 0 until size) {
            val userY = area.y + (row + 0.5) * area.h / size
            for (column in 0 until size) {
                val userX = area.x + (column + 0.5) * area.w / size
                val gx = inverse.mapX(userX, userY)
                val gy = inverse.mapY(userX, userY)
                val t = focalParameter(gx, gy, cx, cy, r, fx, fy, fr)
                pixels[row * size + column] =
                    if (t.isNaN()) 0 else table[(spreadParameter(t, spread) * (table.size - 1) + 0.5).toInt()]
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix((Mat2D.translate(area.x, area.y) * Mat2D.scale(area.w / size, area.h / size)).toAndroid())
        return shader
    }

    private fun colorTable(stops: List<GradientStop>): IntArray {
        val table = IntArray(1024)
        var index = 0
        for (i in table.indices) {
            val t = i.toDouble() / (table.size - 1)
            while (index < stops.size - 2 && t >= stops[index + 1].offset) index++
            val a = stops[index]
            val b = stops[index + 1]
            val span = b.offset - a.offset
            val p = if (span > 0.0) {
                ((t - a.offset) / span).coerceIn(0.0, 1.0)
            } else if (t >= b.offset) {
                1.0
            } else {
                0.0
            }
            table[i] = straightMix(a.color, b.color, p).toArgb()
        }
        return table
    }

    private fun spreadParameter(t: Double, spread: Shader.TileMode): Double =
        when (spread) {
            Shader.TileMode.REPEAT -> {
                t - floor(t)
            }

            Shader.TileMode.MIRROR -> {
                val m = t - 2.0 * floor(t / 2.0)
                if (m > 1.0) 2.0 - m else m
            }

            else -> {
                t.coerceIn(0.0, 1.0)
            }
        }

    /** The largest t where the circle growing from the focal point to the outer circle passes through (x, y). */
    internal fun focalParameter(x: Double, y: Double, cx: Double, cy: Double, r: Double, fx: Double, fy: Double, fr: Double): Double {
        val dx = cx - fx
        val dy = cy - fy
        val dr = r - fr
        val px = x - fx
        val py = y - fy
        val a = dx * dx + dy * dy - dr * dr
        val b = px * dx + py * dy + fr * dr
        val c = px * px + py * py - fr * fr
        if (a == 0.0) return if (b == 0.0) Double.NaN else c / (2.0 * b)
        val discriminant = b * b - a * c
        if (discriminant < 0.0) return Double.NaN
        val root = sqrt(discriminant)
        val t1 = (b + root) / a
        val t2 = (b - root) / a
        val high = maxOf(t1, t2)
        val low = minOf(t1, t2)
        return when {
            fr + high * dr >= 0.0 -> high
            fr + low * dr >= 0.0 -> low
            else -> Double.NaN
        }
    }
}
