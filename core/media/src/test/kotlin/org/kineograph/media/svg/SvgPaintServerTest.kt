package org.kineograph.media.svg

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.hypot

/** Gradient, pattern and marker geometry, read from pixels on real Skia. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SvgPaintServerTest {
    private val redToBlue = """<stop offset="0" stop-color="#f00"/><stop offset="1" stop-color="#00f"/>"""

    private fun gradientRect(gradient: String, rect: String = """width="100" height="100""""): android.graphics.Bitmap =
        drawn("""<defs>$gradient</defs><rect $rect fill="url(#g)"/>""")

    /** How far from red to blue a pixel is, from 0 to 1. */
    private fun android.graphics.Bitmap.mix(x: Int, y: Int): Double = Color.blue(getPixel(x, y)) / 255.0

    @Test
    fun `a default linear gradient runs left to right across the box`() {
        val f = gradientRect("""<linearGradient id="g">$redToBlue</linearGradient>""")
        assertEquals(0.0, f.mix(0, 50), 0.02)
        assertEquals(0.25, f.mix(25, 50), 0.02)
        assertEquals(0.5, f.mix(50, 50), 0.02)
        assertEquals(1.0, f.mix(99, 50), 0.02)
        assertEquals(f.getPixel(50, 0), f.getPixel(50, 99))
    }

    @Test
    fun `object units scale with the shape they paint`() {
        val f = gradientRect("""<linearGradient id="g">$redToBlue</linearGradient>""", """x="50" y="0" width="50" height="100"""")
        assertEquals(0.0, f.mix(50, 50), 0.03)
        assertEquals(0.5, f.mix(75, 50), 0.02)
        assertEquals(1.0, f.mix(99, 50), 0.03)
    }

    @Test
    fun `object units use tight bounds on curves`() {
        // The control points reach x = 200, but the curve only reaches 150, so the gradient ends there.
        val f =
            drawn(
                """<defs><linearGradient id="g">$redToBlue</linearGradient></defs>
                <path d="M0 0 C200 0 200 100 0 100 Z" transform="scale(0.5)" fill="url(#g)"/>""",
            )
        assertEquals(0.5, f.mix(37, 25), 0.04)
    }

    @Test
    fun `gradient vectors can be diagonal`() {
        val f = gradientRect("""<linearGradient id="g" x1="0" y1="0" x2="1" y2="1">$redToBlue</linearGradient>""")
        assertEquals(0.0, f.mix(1, 1), 0.03)
        assertEquals(0.5, f.mix(50, 50), 0.03)
        assertEquals(0.5, f.mix(90, 10), 0.03)
        assertEquals(1.0, f.mix(98, 98), 0.03)
    }

    @Test
    fun `gradient vectors accept percentages in object units`() {
        val a = gradientRect("""<linearGradient id="g" x1="0%" x2="100%">$redToBlue</linearGradient>""")
        val b = gradientRect("""<linearGradient id="g" x1="0" x2="1">$redToBlue</linearGradient>""")
        assertSamePixels(a, b)
    }

    @Test
    fun `user space gradients stay put whatever they paint`() {
        val gradient =
            """<linearGradient id="g" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="100" y2="0">$redToBlue</linearGradient>"""
        val f = gradientRect(gradient, """x="50" y="0" width="50" height="100"""")
        assertEquals(0.5, f.mix(50, 50), 0.02)
        assertEquals(0.75, f.mix(75, 50), 0.02)
    }

    @Test
    fun `user space percentages measure against the viewport`() {
        val gradient =
            """<linearGradient id="g" gradientUnits="userSpaceOnUse" x1="0%" x2="50%">$redToBlue</linearGradient>"""
        val f = gradientRect(gradient)
        assertEquals(0.5, f.mix(25, 50), 0.02)
        assertEquals(1.0, f.mix(75, 50), 0.02)
    }

    @Test
    fun `user space gradients move with the shape's transform`() {
        val gradient =
            """<linearGradient id="g" gradientUnits="userSpaceOnUse" x1="0" x2="100">$redToBlue</linearGradient>"""
        val f = drawn("""<defs>$gradient</defs><rect width="100" height="100" fill="url(#g)" transform="translate(50 0)"/>""")
        assertEquals(0.0, f.mix(51, 50), 0.03)
        assertEquals(0.25, f.mix(75, 50), 0.02)
    }

    @Test
    fun `gradientTransform turns the gradient`() {
        val f = gradientRect("""<linearGradient id="g" gradientTransform="rotate(90 0.5 0.5)">$redToBlue</linearGradient>""")
        // Now running top to bottom.
        assertEquals(f.getPixel(10, 50), f.getPixel(90, 50))
        assertEquals(0.0, f.mix(50, 1), 0.03)
        assertEquals(1.0, f.mix(50, 98), 0.03)
    }

    @Test
    fun `gradientTransform works in user space`() {
        val f =
            gradientRect(
                """<linearGradient id="g" gradientUnits="userSpaceOnUse" x1="0" x2="50" gradientTransform="translate(50 0)">$redToBlue</linearGradient>""",
            )
        assertEquals(0.0, f.mix(25, 50), 0.02)
        assertEquals(0.5, f.mix(75, 50), 0.02)
    }

    @Test
    fun `pad holds the end colours`() {
        val f = gradientRect("""<linearGradient id="g" x1="0.25" x2="0.75">$redToBlue</linearGradient>""")
        assertEquals(0.0, f.mix(10, 50), 0.01)
        assertEquals(0.5, f.mix(50, 50), 0.02)
        assertEquals(1.0, f.mix(90, 50), 0.01)
    }

    @Test
    fun `repeat starts over`() {
        val f = gradientRect("""<linearGradient id="g" x1="0" x2="0.5" spreadMethod="repeat">$redToBlue</linearGradient>""")
        assertEquals(0.5, f.mix(25, 50), 0.03)
        assertEquals(0.0, f.mix(51, 50), 0.05)
        assertEquals(0.5, f.mix(75, 50), 0.03)
    }

    @Test
    fun `reflect goes back and forth`() {
        val f = gradientRect("""<linearGradient id="g" x1="0" x2="0.5" spreadMethod="reflect">$redToBlue</linearGradient>""")
        assertEquals(0.5, f.mix(25, 50), 0.03)
        assertEquals(1.0, f.mix(50, 50), 0.05)
        assertEquals(0.5, f.mix(75, 50), 0.03)
    }

    @Test
    fun `stops can sit anywhere`() {
        val f =
            gradientRect(
                """<linearGradient id="g"><stop offset="25%" stop-color="#f00"/><stop offset="75%" stop-color="#00f"/></linearGradient>""",
            )
        assertEquals(0.0, f.mix(10, 50), 0.01)
        assertEquals(0.5, f.mix(50, 50), 0.02)
        assertEquals(1.0, f.mix(90, 50), 0.01)
    }

    @Test
    fun `two stops at the same offset make a hard edge`() {
        val f =
            gradientRect(
                """<linearGradient id="g">
                    <stop offset="0.5" stop-color="#f00"/><stop offset="0.5" stop-color="#00f"/>
                </linearGradient>""",
            )
        f.assertPixel(25, 50, RED)
        f.assertPixel(75, 50, BLUE)
    }

    @Test
    fun `a stop that goes backwards is moved up to the one before it`() {
        val f =
            gradientRect(
                """<linearGradient id="g">
                    <stop offset="0.8" stop-color="#f00"/><stop offset="0.2" stop-color="#00f"/>
                </linearGradient>""",
            )
        f.assertPixel(25, 50, RED)
        f.assertPixel(90, 50, BLUE)
    }

    @Test
    fun `stop opacity fades the colour`() {
        val f =
            gradientRect(
                """<linearGradient id="g">
                    <stop offset="0" stop-color="#f00" stop-opacity="1"/><stop offset="1" stop-color="#f00" stop-opacity="0"/>
                </linearGradient>""",
            )
        assertEquals(1.0, Color.alpha(f.getPixel(0, 50)) / 255.0, 0.02)
        assertEquals(0.5, Color.alpha(f.getPixel(50, 50)) / 255.0, 0.02)
        assertEquals(0.0, Color.alpha(f.getPixel(99, 50)) / 255.0, 0.02)
        assertEquals(255, Color.red(f.getPixel(50, 50)))
    }

    @Test
    fun `colour and alpha fade together the SVG way`() {
        // Half way from opaque red to transparent blue, SVG has half-transparent purple. Mixing premultiplied, as Android does by
        // itself, would have half-transparent red, with no blue at all.
        val f =
            gradientRect(
                """<linearGradient id="g">
                    <stop offset="0" stop-color="#f00" stop-opacity="1"/><stop offset="1" stop-color="#00f" stop-opacity="0"/>
                </linearGradient>""",
            )
        val middle = f.getPixel(50, 50)
        assertEquals(0.5, Color.alpha(middle) / 255.0, 0.03)
        assertEquals(127.0, Color.red(middle).toDouble(), 8.0)
        assertEquals(127.0, Color.blue(middle).toDouble(), 8.0)
    }

    @Test
    fun `fill opacity multiplies a gradient`() {
        val f =
            drawn(
                """<defs><linearGradient id="g">$redToBlue</linearGradient></defs><rect width="100" height="100" fill="url(#g)" fill-opacity="0.5"/>""",
            )
        assertEquals(0.5, Color.alpha(f.getPixel(50, 50)) / 255.0, 0.02)
    }

    @Test
    fun `stop-color can use currentColor, css and inherit`() {
        val f =
            drawn(
                """<style>.b { stop-color: #00f }</style>
                <defs><linearGradient id="g" color="#f00"><stop offset="0" stop-color="currentColor"/><stop offset="1" class="b"/></linearGradient></defs>
                <rect width="100" height="100" fill="url(#g)"/>""",
            )
        assertEquals(0.0, f.mix(1, 50), 0.03)
        assertEquals(1.0, f.mix(98, 50), 0.03)
        assertEquals(255, Color.red(f.getPixel(1, 50)), 10)
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) {
        assertTrue("expected about $expected, was $actual", abs(expected - actual) <= tolerance)
    }

    @Test
    fun `href inherits attributes and stops`() {
        val f =
            drawn(
                """<defs>
                    <linearGradient id="base" gradientUnits="userSpaceOnUse" x1="0" x2="100">$redToBlue</linearGradient>
                    <linearGradient id="g" href="#base" x2="50"/>
                </defs><rect width="100" height="100" fill="url(#g)"/>""",
            )
        // Stops and units come from #base, and x2 from itself.
        assertEquals(0.5, f.mix(25, 50), 0.02)
        assertEquals(1.0, f.mix(75, 50), 0.01)
    }

    @Test
    fun `href reads the old xlink form and own stops win`() {
        val f =
            drawn(
                """<defs>
                    <linearGradient id="base">$redToBlue</linearGradient>
                    <linearGradient id="g" xlink:href="#base"><stop offset="0" stop-color="#0f0"/><stop offset="1" stop-color="#0f0"/></linearGradient>
                </defs><rect width="100" height="100" fill="url(#g)"/>""",
            )
        f.assertPixel(50, 50, LIME)
    }

    @Test
    fun `a radial gradient can inherit from a linear one`() {
        val f =
            drawn(
                """<defs>
                    <linearGradient id="base">$redToBlue</linearGradient>
                    <radialGradient id="g" href="#base"/>
                </defs><rect width="100" height="100" fill="url(#g)"/>""",
            )
        assertEquals(0.0, f.mix(50, 50), 0.03)
        assertEquals(0.5, f.mix(75, 50), 0.03)
    }

    @Test
    fun `a loop in href does not hang`() {
        val f =
            drawn(
                """<defs>
                    <linearGradient id="a" href="#b"/><linearGradient id="b" href="#a"/>
                </defs><rect width="100" height="100" fill="url(#a) red"/>""",
            )
        f.assertPixel(50, 50, RED)
    }

    @Test
    fun `no stops means no paint, and one stop means a solid colour`() {
        val none = gradientRect("""<linearGradient id="g"/>""")
        assertEquals(0, none.paintedPixels())
        val one = gradientRect("""<linearGradient id="g"><stop offset="0.5" stop-color="#f00"/></linearGradient>""")
        one.assertPixel(10, 50, RED)
        one.assertPixel(90, 50, RED)
    }

    @Test
    fun `a zero-length gradient paints with its last colour`() {
        val f = gradientRect("""<linearGradient id="g" x1="0.5" x2="0.5">$redToBlue</linearGradient>""")
        f.assertPixel(10, 50, BLUE)
    }

    @Test
    fun `object units on a shape with no height use the fallback`() {
        val f =
            drawn(
                """<defs><linearGradient id="g">$redToBlue</linearGradient></defs>
                <line x1="0" y1="50" x2="100" y2="50" stroke="url(#g) red" stroke-width="10"/>""",
            )
        f.assertPixel(50, 50, RED)
    }

    @Test
    fun `a missing paint server uses its fallback`() {
        drawn("""<rect width="100" height="100" fill="url(#nowhere) red"/>""").assertPixel(50, 50, RED)
    }

    @Test
    fun `a missing paint server with no fallback paints nothing`() {
        assertEquals(0, drawn("""<rect width="100" height="100" fill="url(#nowhere)"/>""").paintedPixels())
    }

    @Test
    fun `an external paint server is never fetched`() {
        val doc = parse(svg("""<rect width="100" height="100" fill="url(https://example.org/a.svg#g) red"/>"""))
        doc.frame().assertPixel(50, 50, RED)
        assertTrue(SvgWarning.EXTERNAL_REFERENCE_IGNORED in doc.warnings)
    }

    @Test
    fun `a stroke takes a gradient too`() {
        val f =
            drawn(
                """<defs><linearGradient id="g" gradientUnits="userSpaceOnUse" x1="0" x2="100">$redToBlue</linearGradient></defs>
                <line x1="0" y1="50" x2="100" y2="50" stroke="url(#g)" stroke-width="10"/>""",
            )
        assertEquals(0.5, f.mix(50, 50), 0.02)
    }

    @Test
    fun `a radial gradient spreads out from its centre`() {
        val f = gradientRect("""<radialGradient id="g">$redToBlue</radialGradient>""")
        assertEquals(0.0, f.mix(50, 50), 0.03)
        assertEquals(0.5, f.mix(75, 50), 0.03)
        assertEquals(0.5, f.mix(50, 75), 0.03)
        assertEquals(1.0, f.mix(1, 1), 0.01)
    }

    @Test
    fun `object units squash a radial gradient to fit the box`() {
        val f = gradientRect("""<radialGradient id="g">$redToBlue</radialGradient>""", """y="25" width="100" height="50"""")
        // An ellipse: half way out along x at 25 pixels, half way out along y at 12.5.
        assertEquals(0.5, f.mix(75, 50), 0.03)
        assertEquals(0.5, f.mix(50, 62), 0.05)
    }

    @Test
    fun `radial geometry follows cx, cy and r`() {
        val f = gradientRect("""<radialGradient id="g" cx="0" cy="0" r="1">$redToBlue</radialGradient>""")
        assertEquals(0.0, f.mix(1, 1), 0.03)
        assertEquals(0.5, f.mix(50, 0), 0.03)
        assertEquals(0.5, f.mix(0, 50), 0.03)
    }

    @Test
    fun `a radial gradient in user space`() {
        val f =
            gradientRect(
                """<radialGradient id="g" gradientUnits="userSpaceOnUse" cx="20" cy="20" r="20">$redToBlue</radialGradient>""",
            )
        assertEquals(0.0, f.mix(20, 20), 0.05)
        assertEquals(0.5, f.mix(30, 20), 0.03)
        assertEquals(1.0, f.mix(60, 60), 0.01)
    }

    @Test
    fun `a focal point moves the start of the gradient`() {
        val f = gradientRect("""<radialGradient id="g" fx="0.25" fy="0.5">$redToBlue</radialGradient>""")
        assertEquals(0.0, f.mix(25, 50), 0.04)
        // From the focus at x = 25 to the rim at x = 100 is 75 long, so halfway along that line is x = 62.5.
        assertEquals(0.5, f.mix(62, 50), 0.04)
        // Towards the near side the rim is only 25 away.
        assertEquals(0.5, f.mix(12, 50), 0.05)
    }

    @Test
    fun `a focal point outside the circle makes a cone with nothing outside it`() {
        val f = gradientRect("""<radialGradient id="g" fx="-5" fy="0.5">$redToBlue</radialGradient>""")
        // The cone opens from far off to the left, and only just misses this corner.
        f.assertPixel(1, 1, CLEAR)
        assertEquals(255, Color.alpha(f.getPixel(1, 50)))
    }

    @Test
    fun `the focal gradient looks the same on phones before API 31`() {
        val body =
            """<defs><radialGradient id="g" fx="0.25" fy="0.5">$redToBlue</radialGradient></defs>
            <rect width="100" height="100" fill="url(#g)"/>"""
        val native = drawn(body)
        GradientShaders.forceSoftwareFocal = true
        val software =
            try {
                drawn(body)
            } finally {
                GradientShaders.forceSoftwareFocal = false
            }
        for (y in 5 until 95 step 5) {
            for (x in 5 until 95 step 5) {
                val difference = abs(Color.blue(native.getPixel(x, y)) - Color.blue(software.getPixel(x, y)))
                assertTrue("blue differs by $difference at ($x, $y)", difference <= 10)
            }
        }
    }

    @Test
    fun `the software focal gradient honours spread and gradientTransform`() {
        val body =
            """<defs><radialGradient id="g" r="0.25" fx="0.5" fy="0.45" spreadMethod="repeat" gradientTransform="translate(0.1 0)">$redToBlue</radialGradient></defs>
            <rect width="100" height="100" fill="url(#g)"/>"""
        val native = drawn(body)
        GradientShaders.forceSoftwareFocal = true
        val software =
            try {
                drawn(body)
            } finally {
                GradientShaders.forceSoftwareFocal = false
            }
        var close = 0
        var total = 0
        for (y in 5 until 95 step 3) {
            for (x in 5 until 95 step 3) {
                total++
                if (abs(Color.blue(native.getPixel(x, y)) - Color.blue(software.getPixel(x, y))) <= 24) close++
            }
        }
        // Repeats have hard edges, and a smaller image cannot place those to the pixel. Everywhere else must match.
        assertTrue("$close of $total close", close > total * 9 / 10)
    }

    @Test
    fun `focal parameter finds the circle through a point`() {
        fun t(x: Double, y: Double) = GradientShaders.focalParameter(x, y, 0.0, 0.0, 10.0, 0.0, 0.0, 0.0)
        assertEquals(0.5, t(5.0, 0.0), 1e-9)
        assertEquals(1.0, t(0.0, 10.0), 1e-9)
        assertEquals(hypot(3.0, 4.0) / 10.0, t(3.0, 4.0), 1e-9)
        val shifted = GradientShaders.focalParameter(10.0, 0.0, 0.0, 0.0, 10.0, 5.0, 0.0, 0.0)
        assertEquals(1.0, shifted, 1e-9)
    }

    @Test
    fun `a pattern tiles its content in user space`() {
        val f =
            drawn(
                """<defs><pattern id="p" width="20" height="20" patternUnits="userSpaceOnUse">
                    <rect width="10" height="10" fill="red"/>
                </pattern></defs>
                <rect width="100" height="100" fill="url(#p)"/>""",
            )
        f.assertPixel(5, 5, RED)
        f.assertPixel(15, 5, CLEAR)
        f.assertPixel(25, 5, RED)
        f.assertPixel(45, 45, RED)
        f.assertPixel(55, 45, CLEAR)
    }

    @Test
    fun `a pattern's x and y shift the tiles`() {
        val f =
            drawn(
                """<defs><pattern id="p" x="5" y="5" width="20" height="20" patternUnits="userSpaceOnUse">
                    <rect width="10" height="10" fill="red"/>
                </pattern></defs>
                <rect width="100" height="100" fill="url(#p)"/>""",
            )
        f.assertPixel(10, 10, RED)
        f.assertPixel(2, 2, CLEAR)
        f.assertPixel(20, 20, CLEAR)
        f.assertPixel(30, 30, RED)
    }

    @Test
    fun `a pattern in object units follows the shape's box`() {
        val f =
            drawn(
                """<defs><pattern id="p" width="0.5" height="0.5">
                    <rect width="10" height="10" fill="red"/>
                </pattern></defs>
                <rect x="20" y="20" width="60" height="60" fill="url(#p)"/>""",
            )
        // Tiles are 30 wide, and content sits in user space at the tile's own corner.
        f.assertPixel(25, 25, RED)
        f.assertPixel(35, 25, CLEAR)
        f.assertPixel(55, 25, RED)
    }

    @Test
    fun `pattern content can be in object units`() {
        val f =
            drawn(
                """<defs><pattern id="p" width="1" height="1" patternContentUnits="objectBoundingBox">
                    <rect width="0.5" height="1" fill="red"/>
                </pattern></defs>
                <rect x="20" y="20" width="60" height="60" fill="url(#p)"/>""",
            )
        f.assertPixel(30, 50, RED)
        f.assertPixel(70, 50, CLEAR)
    }

    @Test
    fun `a pattern's viewBox scales its content`() {
        val f =
            drawn(
                """<defs><pattern id="p" width="50" height="50" patternUnits="userSpaceOnUse" viewBox="0 0 10 10">
                    <rect width="5" height="5" fill="red"/>
                </pattern></defs>
                <rect width="100" height="100" fill="url(#p)"/>""",
            )
        f.assertPixel(10, 10, RED)
        f.assertPixel(35, 35, CLEAR)
        f.assertPixel(60, 60, RED)
    }

    @Test
    fun `patternTransform moves the tiles`() {
        val f =
            drawn(
                """<defs><pattern id="p" width="20" height="20" patternUnits="userSpaceOnUse" patternTransform="translate(10 0)">
                    <rect width="10" height="20" fill="red"/>
                </pattern></defs>
                <rect width="100" height="100" fill="url(#p)"/>""",
            )
        f.assertPixel(5, 50, CLEAR)
        f.assertPixel(15, 50, RED)
    }

    @Test
    fun `a pattern scales with the picture and stays sharp`() {
        val doc =
            parse(
                """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10">
                <defs><pattern id="p" width="2" height="2" patternUnits="userSpaceOnUse"><rect width="1" height="2" fill="red"/></pattern></defs>
                <rect width="10" height="10" fill="url(#p)"/></svg>""",
            )
        val f = doc.frame(0.0, 100, 100)
        f.assertPixel(5, 50, RED)
        f.assertPixel(15, 50, CLEAR)
        f.assertPixel(25, 50, RED)
    }

    @Test
    fun `pattern href inherits attributes and content`() {
        val f =
            drawn(
                """<defs>
                    <pattern id="base" width="20" height="20" patternUnits="userSpaceOnUse"><rect width="10" height="10" fill="red"/></pattern>
                    <pattern id="p" href="#base" width="40"/>
                </defs><rect width="100" height="100" fill="url(#p)"/>""",
            )
        f.assertPixel(5, 5, RED)
        f.assertPixel(25, 5, CLEAR)
        f.assertPixel(45, 5, RED)
    }

    @Test
    fun `a pattern using itself does not hang`() {
        val f =
            drawn(
                """<defs><pattern id="p" width="20" height="20" patternUnits="userSpaceOnUse">
                    <rect width="10" height="10" fill="url(#p)"/>
                    <rect x="10" width="10" height="10" fill="red"/>
                </pattern></defs>
                <rect width="100" height="100" fill="url(#p)"/>""",
            )
        f.assertPixel(15, 5, RED)
    }

    @Test
    fun `a stroke takes a pattern`() {
        val f =
            drawn(
                """<defs><pattern id="p" width="20" height="100" patternUnits="userSpaceOnUse"><rect width="10" height="100" fill="red"/></pattern></defs>
                <line x1="0" y1="50" x2="100" y2="50" stroke="url(#p)" stroke-width="10"/>""",
            )
        f.assertPixel(5, 50, RED)
        f.assertPixel(15, 50, CLEAR)
    }

    private val arrow =
        """<marker id="m" markerWidth="10" markerHeight="10" refX="5" refY="5" markerUnits="userSpaceOnUse">
            <rect width="10" height="10" fill="red"/>
        </marker>"""

    @Test
    fun `markers go on the vertices they are named for`() {
        val body = """<defs>$arrow</defs><path d="M10 50 L50 50 L90 50" fill="none" stroke="none" """
        val start = drawn("""$body marker-start="url(#m)"/>""")
        start.assertPixel(10, 50, RED)
        start.assertPixel(50, 50, CLEAR)
        start.assertPixel(90, 50, CLEAR)
        val mid = drawn("""$body marker-mid="url(#m)"/>""")
        mid.assertPixel(50, 50, RED)
        mid.assertPixel(10, 50, CLEAR)
        val end = drawn("""$body marker-end="url(#m)"/>""")
        end.assertPixel(90, 50, RED)
        end.assertPixel(50, 50, CLEAR)
    }

    @Test
    fun `the marker shorthand from css sets all three`() {
        val f =
            drawn(
                """<style>path { marker: url(#m) }</style><defs>$arrow</defs>
                <path d="M10 50 L50 50 L90 50" fill="none"/>""",
            )
        f.assertPixel(10, 50, RED)
        f.assertPixel(50, 50, RED)
        f.assertPixel(90, 50, RED)
    }

    @Test
    fun `markers work on lines, polylines and polygons`() {
        val line = drawn("""<defs>$arrow</defs><line x1="20" y1="20" x2="80" y2="80" marker-end="url(#m)"/>""")
        line.assertPixel(80, 80, RED)
        val polyline = drawn("""<defs>$arrow</defs><polyline points="10,50 50,50 90,50" fill="none" marker-mid="url(#m)"/>""")
        polyline.assertPixel(50, 50, RED)
        val polygon = drawn("""<defs>$arrow</defs><polygon points="10,10 90,10 90,90" fill="none" marker-mid="url(#m)"/>""")
        polygon.assertPixel(90, 10, RED)
    }

    @Test
    fun `markers do not apply to other shapes`() {
        val f = drawn("""<defs>$arrow</defs><rect x="20" y="20" width="60" height="60" fill="none" marker-start="url(#m)"/>""")
        assertEquals(0, f.paintedPixels())
    }

    @Test
    fun `stroke width units scale a marker`() {
        val f =
            drawn(
                """<defs><marker id="m" markerWidth="2" markerHeight="2" refX="1" refY="1"><rect width="2" height="2" fill="red"/></marker></defs>
                <path d="M50 50 L90 50" stroke="none" stroke-width="10" marker-start="url(#m)"/>""",
            )
        // Two units at a stroke width of 10 is 20 pixels, centred on the start point.
        f.assertPixel(45, 50, RED)
        f.assertPixel(55, 50, RED)
        f.assertPixel(35, 50, CLEAR)
    }

    @Test
    fun `orient auto turns a marker along the path`() {
        val marker =
            """<marker id="m" markerWidth="40" markerHeight="10" refX="0" refY="5" markerUnits="userSpaceOnUse" orient="auto">
                <rect width="40" height="10" fill="red"/>
            </marker>"""
        val f = drawn("""<defs>$marker</defs><path d="M50 10 L50 90" fill="none" stroke="none" marker-start="url(#m)"/>""")
        // The path heads down, so the bar extends downwards from the start point.
        f.assertPixel(50, 30, RED)
        f.assertPixel(70, 10, CLEAR)
    }

    @Test
    fun `a fixed orient angle turns a marker`() {
        val marker =
            """<marker id="m" markerWidth="40" markerHeight="10" refX="0" refY="5" markerUnits="userSpaceOnUse" orient="90">
                <rect width="40" height="10" fill="red"/>
            </marker>"""
        val f = drawn("""<defs>$marker</defs><path d="M50 10 L90 10" fill="none" stroke="none" marker-start="url(#m)"/>""")
        f.assertPixel(50, 30, RED)
        f.assertPixel(70, 10, CLEAR)
    }

    @Test
    fun `auto-start-reverse flips only the start marker`() {
        val marker =
            """<marker id="m" markerWidth="30" markerHeight="10" refX="0" refY="5" markerUnits="userSpaceOnUse" orient="auto-start-reverse">
                <rect width="30" height="10" fill="red"/>
            </marker>"""
        val f =
            drawn("""<defs>$marker</defs><path d="M40 50 L60 50" fill="none" stroke="none" marker-start="url(#m)" marker-end="url(#m)"/>""")
        f.assertPixel(25, 50, RED)
        f.assertPixel(75, 50, RED)
    }

    @Test
    fun `a mid marker bisects the corner it sits on`() {
        val vertices = markerVertices(parsePathData("M0 0 L10 0 L10 10").data)
        assertEquals(0.0, vertices[0].angle, 1e-6)
        assertEquals(45.0, vertices[1].angle, 1e-6)
        assertEquals(90.0, vertices[2].angle, 1e-6)
    }

    @Test
    fun `a closed path has a vertex back at its start, bisecting too`() {
        val vertices = markerVertices(parsePathData("M0 0 L10 0 L10 10 L0 10 Z").data)
        assertEquals(5, vertices.size)
        assertEquals(0.0, vertices.last().x, 1e-9)
        assertEquals(0.0, vertices.last().y, 1e-9)
        // Coming in heading up, and leaving heading right.
        assertEquals(-45.0, vertices[0].angle, 1e-6)
        assertEquals(-45.0, vertices.last().angle, 1e-6)
    }

    @Test
    fun `an arc is one vertex however many curves it became`() {
        val vertices = markerVertices(parsePathData("M0 0 A10 10 0 1 1 20 0").data)
        assertEquals(2, vertices.size)
        assertEquals(20.0, vertices[1].x, 1e-9)
        // A half circle from left to right, going over the top: it leaves straight up and arrives straight down.
        assertEquals(-90.0, vertices[0].angle, 1e-3)
        assertEquals(90.0, vertices[1].angle, 1e-3)
    }

    @Test
    fun `markers clip to their box unless told otherwise`() {
        val clipped =
            """<marker id="m" markerWidth="10" markerHeight="10" markerUnits="userSpaceOnUse"><rect width="50" height="50" fill="red"/></marker>"""
        val visible =
            """<marker id="m" markerWidth="10" markerHeight="10" markerUnits="userSpaceOnUse" overflow="visible"><rect width="50" height="50" fill="red"/></marker>"""
        val path = """<path d="M20 20 L90 20" fill="none" marker-start="url(#m)"/>"""
        drawn("<defs>$clipped</defs>$path").assertPixel(50, 50, CLEAR)
        drawn("<defs>$visible</defs>$path").assertPixel(50, 50, RED)
    }

    @Test
    fun `a marker's viewBox maps its content, and refX uses its units`() {
        val f =
            drawn(
                """<defs><marker id="m" markerWidth="20" markerHeight="20" viewBox="0 0 2 2" refX="1" refY="1" markerUnits="userSpaceOnUse">
                    <rect width="2" height="2" fill="red"/>
                </marker></defs>
                <path d="M50 50 L90 50" fill="none" marker-start="url(#m)"/>""",
            )
        f.assertPixel(45, 45, RED)
        f.assertPixel(55, 55, RED)
        f.assertPixel(35, 50, CLEAR)
    }

    @Test
    fun `context-stroke paints a marker with the shape's own stroke`() {
        val f =
            drawn(
                """<defs><marker id="m" markerWidth="10" markerHeight="10" refX="5" refY="5" markerUnits="userSpaceOnUse">
                    <rect width="10" height="10" fill="context-stroke"/>
                </marker></defs>
                <line x1="20" y1="50" x2="80" y2="50" stroke="#00f" stroke-width="1" marker-start="url(#m)"/>""",
            )
        f.assertPixel(18, 47, BLUE)
    }
}
