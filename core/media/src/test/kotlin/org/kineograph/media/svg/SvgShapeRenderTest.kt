package org.kineograph.media.svg

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.hypot

/** Exact pixels on real Skia. Points are picked well inside or well outside shapes, so antialiasing cannot affect the answer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SvgShapeRenderTest {
    @Test
    fun `a rectangle fills exactly its pixels`() {
        val f = drawn("""<rect x="10" y="20" width="30" height="40" fill="red"/>""")
        f.assertPixel(10, 20, RED)
        f.assertPixel(39, 59, RED)
        f.assertPixel(9, 20, CLEAR)
        f.assertPixel(40, 20, CLEAR)
        f.assertPixel(10, 19, CLEAR)
        f.assertPixel(10, 60, CLEAR)
    }

    @Test
    fun `fill defaults to black and stroke to none`() {
        val f = drawn("""<rect x="10" y="10" width="20" height="20" stroke-width="10"/>""")
        f.assertPixel(20, 20, BLACK)
        f.assertPixel(7, 20, CLEAR)
    }

    @Test
    fun `a rectangle with no width or height draws nothing`() {
        assertEquals(0, drawn("""<rect width="0" height="50" stroke="red" stroke-width="4"/>""").paintedPixels())
        assertEquals(0, drawn("""<rect width="50" height="-5" stroke="red"/>""").paintedPixels())
        assertEquals(0, drawn("""<rect width="50" stroke="red"/>""").paintedPixels())
    }

    @Test
    fun `rounded corners cut the corner pixel but keep the edge`() {
        val f = drawn("""<rect x="10" y="10" width="60" height="60" rx="20" ry="20" fill="red"/>""")
        f.assertPixel(11, 11, CLEAR)
        f.assertPixel(40, 11, RED)
        f.assertPixel(11, 40, RED)
        f.assertPixel(40, 40, RED)
        f.assertPixel(68, 68, CLEAR)
    }

    @Test
    fun `one corner radius stands for both`() {
        val onlyRx = drawn("""<rect x="10" y="10" width="60" height="60" rx="20" fill="red"/>""")
        val onlyRy = drawn("""<rect x="10" y="10" width="60" height="60" ry="20" fill="red"/>""")
        val both = drawn("""<rect x="10" y="10" width="60" height="60" rx="20" ry="20" fill="red"/>""")
        assertSamePixels(both, onlyRx)
        assertSamePixels(both, onlyRy)
    }

    @Test
    fun `corner radii are limited to half the side`() {
        val tooBig = drawn("""<rect x="0" y="0" width="100" height="100" rx="500" ry="500" fill="red"/>""")
        val circle = drawn("""<rect x="0" y="0" width="100" height="100" rx="50" ry="50" fill="red"/>""")
        assertSamePixels(circle, tooBig)
        tooBig.assertPixel(2, 2, CLEAR)
        tooBig.assertPixel(50, 2, RED)
    }

    @Test
    fun `a circle fills inside its radius and not outside`() {
        val f = drawn("""<circle cx="50" cy="50" r="30" fill="red"/>""")
        f.assertPixel(50, 50, RED)
        f.assertPixel(50, 22, RED)
        f.assertPixel(78, 50, RED)
        f.assertPixel(50, 18, CLEAR)
        f.assertPixel(30, 30, RED)
        f.assertPixel(26, 26, CLEAR)
    }

    @Test
    fun `an ellipse has two radii`() {
        val f = drawn("""<ellipse cx="50" cy="50" rx="40" ry="10" fill="red"/>""")
        f.assertPixel(88, 50, RED)
        f.assertPixel(92, 50, CLEAR)
        f.assertPixel(50, 58, RED)
        f.assertPixel(50, 62, CLEAR)
    }

    @Test
    fun `a line only shows through its stroke`() {
        assertEquals(0, drawn("""<line x1="0" y1="50" x2="100" y2="50" fill="red"/>""").paintedPixels())
        val f = drawn("""<line x1="10" y1="50" x2="90" y2="50" stroke="red" stroke-width="10"/>""")
        f.assertPixel(50, 50, RED)
        f.assertPixel(50, 46, RED)
        f.assertPixel(50, 56, CLEAR)
        f.assertPixel(5, 50, CLEAR)
    }

    @Test
    fun `a polyline stays open and a polygon closes`() {
        val open = drawn("""<polyline points="10,10 90,10 90,90" fill="none" stroke="red" stroke-width="4"/>""")
        val closed = drawn("""<polygon points="10,10 90,10 90,90" fill="none" stroke="red" stroke-width="4"/>""")
        open.assertPixel(50, 10, RED)
        open.assertPixel(90, 50, RED)
        open.assertPixel(50, 50, CLEAR)
        closed.assertPixel(50, 50, RED)
    }

    @Test
    fun `a polygon fills`() {
        val f = drawn("""<polygon points="10 10, 90,10 90 90" fill="red"/>""")
        f.assertPixel(70, 30, RED)
        f.assertPixel(30, 70, CLEAR)
    }

    @Test
    fun `a dangling coordinate in points is dropped`() {
        val f = drawn("""<polygon points="10,10 90,10 90,90 50" fill="red"/>""")
        f.assertPixel(70, 30, RED)
        f.assertPixel(30, 70, CLEAR)
    }

    @Test
    fun `path commands draw what they say`() {
        val f = drawn("""<path d="M10 10 H90 V90 H10 Z" fill="red"/>""")
        f.assertPixel(50, 50, RED)
        f.assertPixel(5, 5, CLEAR)
    }

    @Test
    fun `an arc path traces a circle`() {
        val f = drawn("""<path d="M20 50 A30 30 0 1 1 80 50 A30 30 0 1 1 20 50 Z" fill="red"/>""")
        // Anywhere at least a pixel away from the circle's edge is exact, whichever way Skia rasterises the edge itself.
        for (y in 0 until 100) {
            for (x in 0 until 100) {
                val distance = hypot(x + 0.5 - 50.0, y + 0.5 - 50.0)
                if (distance < 29.0) f.assertPixel(x, y, RED)
                if (distance > 31.0) f.assertPixel(x, y, CLEAR)
            }
        }
    }

    @Test
    fun `a bad path draws up to its error and warns`() {
        val doc = parse(svg("""<path d="M10 10 H90 V90 Z oops" fill="red"/>"""))
        assertTrue(SvgWarning.INVALID_CONTENT_IGNORED in doc.warnings)
        doc.frame().assertPixel(70, 30, RED)
    }

    @Test
    fun `fill rule decides about holes`() {
        // The same direction twice, so nonzero keeps the middle filled, while evenodd cuts it out.
        val rings = "M10 10 H90 V90 H10 Z M30 30 H70 V70 H30 Z"
        drawn("""<path d="$rings" fill="red" fill-rule="nonzero"/>""").assertPixel(50, 50, RED)
        drawn("""<path d="$rings" fill="red"/>""").assertPixel(50, 50, RED)
        val evenOdd = drawn("""<path d="$rings" fill="red" fill-rule="evenodd"/>""")
        evenOdd.assertPixel(50, 50, CLEAR)
        evenOdd.assertPixel(20, 20, RED)
    }

    @Test
    fun `the stroke straddles the outline`() {
        val f = drawn("""<rect x="20" y="20" width="60" height="60" fill="none" stroke="red" stroke-width="10"/>""")
        f.assertPixel(16, 50, RED)
        f.assertPixel(23, 50, RED)
        f.assertPixel(14, 50, CLEAR)
        f.assertPixel(26, 50, CLEAR)
        f.assertPixel(50, 50, CLEAR)
    }

    @Test
    fun `the stroke goes over the fill`() {
        val f = drawn("""<rect x="20" y="20" width="60" height="60" fill="red" stroke="#00f" stroke-width="10"/>""")
        f.assertPixel(23, 50, BLUE)
        f.assertPixel(50, 50, RED)
    }

    @Test
    fun `paint order can put the stroke under the fill`() {
        val f = drawn("""<rect x="20" y="20" width="60" height="60" fill="red" stroke="#00f" stroke-width="10" paint-order="stroke"/>""")
        f.assertPixel(23, 50, RED)
        f.assertPixel(17, 50, BLUE)
    }

    @Test
    fun `a zero stroke width draws no stroke at all`() {
        // Android treats zero as a one pixel hairline, which is why this needs its own test.
        assertEquals(0, drawn("""<rect x="20" y="20" width="60" height="60" fill="none" stroke="red" stroke-width="0"/>""").paintedPixels())
    }

    @Test
    fun `line caps extend the ends as asked`() {
        fun line(cap: String) =
            drawn("""<line x1="30" y1="50" x2="70" y2="50" stroke="red" stroke-width="20" stroke-linecap="$cap"/>""")
        line("butt").assertPixel(25, 50, CLEAR)
        line("square").assertPixel(25, 50, RED)
        line("square").assertPixel(21, 41, RED)
        line("round").assertPixel(25, 50, RED)
        line("round").assertPixel(21, 41, CLEAR)
    }

    @Test
    fun `a zero-length line with a round or square cap draws a dot, and with a butt cap draws nothing`() {
        fun dot(cap: String) =
            drawn("""<line x1="50" y1="50" x2="50" y2="50" stroke="red" stroke-width="20" stroke-linecap="$cap"/>""")
        dot("round").assertPixel(50, 50, RED)
        dot("round").assertPixel(43, 43, RED)
        dot("round").assertPixel(42, 42, CLEAR)
        dot("square").assertPixel(42, 42, RED)
        assertEquals(0, dot("butt").paintedPixels())
    }

    @Test
    fun `line joins shape the outer corner`() {
        fun corner(join: String, extra: String = "") =
            drawn("""<path d="M10 90 L50 50 L90 90" fill="none" stroke="red" stroke-width="20" stroke-linejoin="$join" $extra/>""")
        // A right angle, so the miter tip is 14.1 above the corner, while a round join only reaches 10 above it.
        val tip = 50 to 38
        corner("miter").assertPixel(tip.first, tip.second, RED)
        corner("round").assertPixel(tip.first, tip.second, CLEAR)
        corner("bevel").assertPixel(tip.first, tip.second, CLEAR)
        // The round corner reaches further than the flat one.
        corner("round").assertPixel(50, 41, RED)
        corner("bevel").assertPixel(50, 41, CLEAR)
    }

    @Test
    fun `the miter limit cuts off long tips`() {
        fun corner(limit: String) =
            drawn("""<path d="M10 90 L50 10 L90 90" fill="none" stroke="red" stroke-width="10" stroke-miterlimit="$limit"/>""")
        // This angle has a miter ratio of about 2.2, between the two limits used here.
        corner("4").assertPixel(50, 2, RED)
        corner("1").assertPixel(50, 2, CLEAR)
    }

    @Test
    fun `dashes run from the start of the path`() {
        val f = drawn("""<line x1="0" y1="50" x2="100" y2="50" stroke="red" stroke-width="10" stroke-dasharray="20 10"/>""")
        f.assertPixel(10, 50, RED)
        f.assertPixel(25, 50, CLEAR)
        f.assertPixel(35, 50, RED)
    }

    @Test
    fun `an odd dash list repeats itself`() {
        val f = drawn("""<line x1="0" y1="50" x2="100" y2="50" stroke="red" stroke-width="10" stroke-dasharray="10 20 30"/>""")
        // 10 on, 20 off, 30 on, 10 off, 20 on, 30 off.
        f.assertPixel(5, 50, RED)
        f.assertPixel(15, 50, CLEAR)
        f.assertPixel(45, 50, RED)
        f.assertPixel(65, 50, CLEAR)
        f.assertPixel(75, 50, RED)
    }

    @Test
    fun `the dash offset moves the pattern`() {
        val f =
            drawn(
                """<line x1="0" y1="50" x2="100" y2="50" stroke="red" stroke-width="10" stroke-dasharray="20 10" stroke-dashoffset="15"/>""",
            )
        // Starts 15 into the pattern, so 5 on, 10 off, then 20 on.
        f.assertPixel(2, 50, RED)
        f.assertPixel(10, 50, CLEAR)
        f.assertPixel(20, 50, RED)
    }

    @Test
    fun `dashes of all zeros are a solid line and negative dashes are invalid`() {
        val solid = drawn("""<line x1="0" y1="50" x2="100" y2="50" stroke="red" stroke-width="10"/>""")
        assertSamePixels(solid, drawn("""<line x1="0" y1="50" x2="100" y2="50" stroke="red" stroke-width="10" stroke-dasharray="0 0"/>"""))
        val negative = parse(svg("""<line x1="0" y1="50" x2="100" y2="50" stroke="red" stroke-width="10" stroke-dasharray="5 -5"/>"""))
        assertSamePixels(solid, negative.frame())
        assertTrue(SvgWarning.INVALID_CONTENT_IGNORED in negative.warnings)
    }

    @Test
    fun `pathLength rescales dashes to the measure the author used`() {
        val f =
            drawn("""<line x1="0" y1="50" x2="100" y2="50" stroke="red" stroke-width="10" pathLength="1" stroke-dasharray="0.5 0.5"/>""")
        f.assertPixel(25, 50, RED)
        f.assertPixel(75, 50, CLEAR)
    }

    @Test
    fun `fill and stroke opacity act alone`() {
        val f =
            drawn(
                """<rect x="20" y="20" width="60" height="60" fill="red" fill-opacity="0.5"
                   stroke="#00f" stroke-opacity="0.25" stroke-width="10"/>""",
            )
        f.assertPixelNear(50, 50, 0x80FF0000.toInt())
        f.assertPixelNear(16, 50, 0x400000FF)
    }

    @Test
    fun `opacity accepts a percentage`() {
        drawn("""<rect width="100" height="100" fill="red" opacity="50%"/>""").assertPixelNear(50, 50, 0x80FF0000.toInt())
    }

    @Test
    fun `display none hides an element and its children`() {
        val f = drawn("""<g display="none"><rect width="100" height="100" fill="red"/></g>""")
        assertEquals(0, f.paintedPixels())
    }

    @Test
    fun `visibility hidden can be turned back on by a child`() {
        val f =
            drawn(
                """<g visibility="hidden">
                    <rect width="50" height="100" fill="red"/>
                    <rect x="50" width="50" height="100" fill="red" visibility="visible"/>
                </g>""",
            )
        f.assertPixel(25, 50, CLEAR)
        f.assertPixel(75, 50, RED)
    }

    @Test
    fun `display none cannot be turned back on by a child`() {
        val f = drawn("""<g display="none"><rect width="100" height="100" fill="red" display="inline"/></g>""")
        assertEquals(0, f.paintedPixels())
    }

    @Test
    fun `currentColor follows the color property`() {
        val f =
            drawn(
                """<g color="red"><rect width="50" height="100" fill="currentColor"/><rect x="50" width="50" height="100" fill="currentColor" color="#00f"/></g>""",
            )
        f.assertPixel(25, 50, RED)
        f.assertPixel(75, 50, BLUE)
    }

    @Test
    fun `an inherited currentColor is resolved by the child that uses it`() {
        val f = drawn("""<g fill="currentColor" color="red"><rect width="100" height="100" color="#00f"/></g>""")
        f.assertPixel(50, 50, BLUE)
    }

    @Test
    fun `translate moves`() {
        val f = drawn("""<rect width="10" height="10" fill="red" transform="translate(40 30)"/>""")
        f.assertPixel(45, 35, RED)
        f.assertPixel(5, 5, CLEAR)
    }

    @Test
    fun `translate with one argument moves along x only`() {
        val f = drawn("""<rect width="10" height="10" fill="red" transform="translate(40)"/>""")
        f.assertPixel(45, 5, RED)
    }

    @Test
    fun `scale scales from the origin`() {
        val f = drawn("""<rect x="10" y="10" width="10" height="10" fill="red" transform="scale(2 3)"/>""")
        f.assertPixel(25, 40, RED)
        f.assertPixel(15, 15, CLEAR)
        f.assertPixel(45, 40, CLEAR)
    }

    @Test
    fun `scale with one argument is uniform`() {
        val f = drawn("""<rect width="10" height="10" fill="red" transform="scale(2)"/>""")
        f.assertPixel(19, 19, RED)
        f.assertPixel(21, 21, CLEAR)
    }

    @Test
    fun `rotate turns clockwise about the origin`() {
        val f = drawn("""<rect x="20" y="-5" width="40" height="10" fill="red" transform="rotate(90)"/>""")
        f.assertPixel(0, 40, RED)
        f.assertPixel(0, 15, CLEAR)
    }

    @Test
    fun `rotate with a centre turns about that point`() {
        val f = drawn("""<rect x="60" y="45" width="30" height="10" fill="red" transform="rotate(90 50 50)"/>""")
        f.assertPixel(50, 75, RED)
        f.assertPixel(75, 50, CLEAR)
    }

    @Test
    fun `skewX shears sideways as y grows`() {
        val f = drawn("""<rect x="0" y="0" width="20" height="100" fill="red" transform="skewX(45)"/>""")
        f.assertPixel(10, 2, RED)
        f.assertPixel(80, 2, CLEAR)
        f.assertPixel(80, 75, RED)
        f.assertPixel(10, 75, CLEAR)
    }

    @Test
    fun `skewY shears vertically as x grows`() {
        val f = drawn("""<rect x="0" y="0" width="100" height="20" fill="red" transform="skewY(45)"/>""")
        f.assertPixel(2, 10, RED)
        f.assertPixel(2, 80, CLEAR)
        f.assertPixel(75, 80, RED)
        f.assertPixel(75, 10, CLEAR)
    }

    @Test
    fun `matrix applies all six numbers`() {
        val f = drawn("""<rect width="10" height="10" fill="red" transform="matrix(2 0 0 2 30 40)"/>""")
        f.assertPixel(35, 45, RED)
        f.assertPixel(49, 59, RED)
        f.assertPixel(51, 61, CLEAR)
    }

    @Test
    fun `transforms in a list apply right to left to the shape`() {
        val f = drawn("""<rect width="10" height="10" fill="red" transform="translate(50 0) scale(2)"/>""")
        f.assertPixel(65, 15, RED)
        f.assertPixel(5, 5, CLEAR)
    }

    @Test
    fun `transforms nest through groups`() {
        val f = drawn("""<g transform="translate(20 20)"><g transform="scale(2)"><rect width="10" height="10" fill="red"/></g></g>""")
        f.assertPixel(35, 35, RED)
        f.assertPixel(15, 15, CLEAR)
    }

    @Test
    fun `a bad transform means no transform`() {
        val f = drawn("""<rect width="10" height="10" fill="red" transform="spin(90)"/>""")
        f.assertPixel(5, 5, RED)
    }

    @Test
    fun `the css transform property with origin works on a shape`() {
        val f =
            drawn(
                """<rect x="40" y="10" width="20" height="10" fill="red" style="transform: rotate(90deg); transform-origin: 50px 50px"/>""",
            )
        // The bar sat above the centre, and a quarter turn puts it to the right of it.
        f.assertPixel(85, 50, RED)
        f.assertPixel(50, 15, CLEAR)
    }

    @Test
    fun `css rotate and scale and translate properties combine with transform`() {
        val f = drawn("""<rect width="10" height="10" fill="red" style="translate: 40px 40px; scale: 2"/>""")
        f.assertPixel(55, 55, RED)
        f.assertPixel(5, 5, CLEAR)
    }

    @Test
    fun `lengths in other units draw at the right size`() {
        val f = drawn("""<rect width="1in" height="1cm" fill="red"/>""", size = 120)
        f.assertPixel(95, 5, RED)
        f.assertPixel(97, 5, CLEAR)
        f.assertPixel(5, 36, RED)
        f.assertPixel(5, 39, CLEAR)
    }

    @Test
    fun `percentages measure against the viewport`() {
        val f = parse(
            """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100"><rect width="50%" height="50%" fill="red"/></svg>""",
        ).frame(0.0, 200, 100)
        f.assertPixel(99, 49, RED)
        f.assertPixel(101, 49, CLEAR)
        f.assertPixel(99, 51, CLEAR)
    }

    @Test
    fun `a percentage radius measures against the normalised diagonal`() {
        val doc =
            parse(
                """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100"><circle cx="100" cy="50" r="10%" fill="red"/></svg>""",
            )
        val f = doc.frame(0.0, 200, 100)
        // sqrt((200^2 + 100^2) / 2) = 158.1, so ten percent of that is 15.8.
        f.assertPixel(100, 50 - 14, RED)
        f.assertPixel(100, 50 - 18, CLEAR)
    }

    @Test
    fun `em lengths follow the font size`() {
        val f = drawn("""<g font-size="20"><rect width="2em" height="1em" fill="red"/></g>""")
        f.assertPixel(39, 19, RED)
        f.assertPixel(41, 19, CLEAR)
        f.assertPixel(39, 21, CLEAR)
    }

    @Test
    fun `a percentage font size scales the inherited one`() {
        val f = drawn("""<g font-size="20"><rect font-size="50%" width="2em" height="2em" fill="red"/></g>""")
        f.assertPixel(19, 19, RED)
        f.assertPixel(21, 19, CLEAR)
    }

    @Test
    fun `a viewBox scales its content to the viewport`() {
        val doc =
            parse(
                """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" viewBox="0 0 10 10"><rect x="2" y="2" width="2" height="2" fill="red"/></svg>""",
            )
        val f = doc.frame()
        f.assertPixel(30, 30, RED)
        f.assertPixel(15, 15, CLEAR)
        f.assertPixel(45, 45, CLEAR)
    }

    @Test
    fun `a viewBox with an origin shifts the content`() {
        val doc =
            parse(
                """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" viewBox="50 50 100 100"><rect x="50" y="50" width="10" height="10" fill="red"/></svg>""",
            )
        doc.frame().assertPixel(5, 5, RED)
    }

    private fun aspectCase(par: String): android.graphics.Bitmap =
        parse(
            """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100" viewBox="0 0 100 100" preserveAspectRatio="$par">
               <rect width="100" height="100" fill="red"/></svg>""",
        ).frame(0.0, 200, 100)

    @Test
    fun `preserveAspectRatio meet centres by default`() {
        val f = aspectCase("xMidYMid meet")
        f.assertPixel(100, 50, RED)
        f.assertPixel(25, 50, CLEAR)
        f.assertPixel(175, 50, CLEAR)
    }

    @Test
    fun `preserveAspectRatio aligns to either end`() {
        aspectCase("xMinYMid meet").let {
            it.assertPixel(50, 50, RED)
            it.assertPixel(150, 50, CLEAR)
        }
        aspectCase("xMaxYMid meet").let {
            it.assertPixel(150, 50, RED)
            it.assertPixel(50, 50, CLEAR)
        }
    }

    @Test
    fun `preserveAspectRatio none stretches`() {
        val f = aspectCase("none")
        f.assertPixel(5, 50, RED)
        f.assertPixel(195, 50, RED)
    }

    @Test
    fun `preserveAspectRatio slice fills and crops`() {
        val doc =
            parse(
                """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice">
                   <rect width="100" height="50" fill="red"/><rect y="50" width="100" height="50" fill="#00f"/></svg>""",
            )
        val f = doc.frame(0.0, 200, 100)
        // Scaled by two, so 50 user units of the height fit, and the middle of it shows: the seam sits at the middle row.
        f.assertPixel(100, 25, RED)
        f.assertPixel(100, 75, BLUE)
        f.assertPixel(5, 25, RED)
        f.assertPixel(195, 75, BLUE)
    }

    @Test
    fun `the destination rectangle places and fits the picture`() {
        val doc = parse(svg("""<rect width="100" height="100" fill="red"/>"""))
        val bitmap = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
        doc.render(android.graphics.Canvas(bitmap), 0.0, android.graphics.RectF(20f, 30f, 40f, 50f))
        bitmap.assertPixel(30, 40, RED)
        bitmap.assertPixel(15, 40, CLEAR)
        bitmap.assertPixel(45, 40, CLEAR)
    }

    @Test
    fun `a wrongly shaped destination letterboxes rather than distorts`() {
        val doc = parse(svg("""<rect width="100" height="100" fill="red"/>"""))
        val f = doc.frame(0.0, 200, 100)
        f.assertPixel(100, 50, RED)
        f.assertPixel(25, 50, CLEAR)
    }

    @Test
    fun `content beyond the root is cut off`() {
        val doc = parse(svg("""<rect x="50" y="50" width="500" height="500" fill="red"/>"""))
        val bitmap = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
        doc.render(android.graphics.Canvas(bitmap), 0.0, android.graphics.RectF(0f, 0f, 60f, 60f))
        bitmap.assertPixel(50, 50, RED)
        bitmap.assertPixel(70, 70, CLEAR)
    }

    @Test
    fun `intrinsic size comes from width and height`() {
        val doc = parse("""<svg xmlns="http://www.w3.org/2000/svg" width="2in" height="30mm"/>""")
        assertEquals(192f, doc.width, 1e-3f)
        assertEquals((30 * 96 / 25.4).toFloat(), doc.height, 1e-3f)
    }

    @Test
    fun `intrinsic size falls back to the viewBox`() {
        val doc = parse("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 32"/>""")
        assertEquals(64f, doc.width)
        assertEquals(32f, doc.height)
    }

    @Test
    fun `one given dimension takes the other from the viewBox shape`() {
        val doc = parse("""<svg xmlns="http://www.w3.org/2000/svg" width="128" viewBox="0 0 64 32"/>""")
        assertEquals(128f, doc.width)
        assertEquals(64f, doc.height)
    }

    @Test
    fun `percentage sizes count as unset`() {
        val doc = parse("""<svg xmlns="http://www.w3.org/2000/svg" width="100%" height="100%" viewBox="0 0 64 32"/>""")
        assertEquals(64f, doc.width)
        assertEquals(32f, doc.height)
    }

    @Test
    fun `nothing about size falls back to the CSS default`() {
        val doc = parse("""<svg xmlns="http://www.w3.org/2000/svg"/>""")
        assertEquals(300f, doc.width)
        assertEquals(150f, doc.height)
    }

    @Test
    fun `a nested svg opens its own viewport and clips to it`() {
        val f = drawn("""<svg x="20" y="20" width="30" height="30"><rect width="100" height="100" fill="red"/></svg>""")
        f.assertPixel(35, 35, RED)
        f.assertPixel(55, 35, CLEAR)
        f.assertPixel(15, 35, CLEAR)
    }

    @Test
    fun `a nested svg with overflow visible does not clip`() {
        val f = drawn("""<svg x="20" y="20" width="30" height="30" overflow="visible"><rect width="100" height="100" fill="red"/></svg>""")
        f.assertPixel(55, 35, RED)
    }

    @Test
    fun `a nested viewBox scales`() {
        val f = drawn("""<svg x="10" y="10" width="20" height="20" viewBox="0 0 2 2"><rect width="1" height="1" fill="red"/></svg>""")
        f.assertPixel(15, 15, RED)
        f.assertPixel(25, 25, CLEAR)
    }

    @Test
    fun `percentages inside a nested svg measure against it`() {
        val f = drawn("""<svg width="50" height="50"><rect width="50%" height="50%" fill="red"/></svg>""")
        f.assertPixel(24, 24, RED)
        f.assertPixel(26, 24, CLEAR)
    }

    @Test
    fun `defs are not drawn directly`() {
        assertEquals(0, drawn("""<defs><rect width="100" height="100" fill="red"/></defs>""").paintedPixels())
    }

    @Test
    fun `use draws its target, moved`() {
        val f = drawn("""<defs><rect id="a" width="10" height="10" fill="red"/></defs><use href="#a" x="40" y="50"/>""")
        f.assertPixel(45, 55, RED)
        f.assertPixel(5, 5, CLEAR)
    }

    @Test
    fun `use reads the old xlink form too`() {
        val f = drawn("""<defs><rect id="a" width="10" height="10" fill="red"/></defs><use xlink:href="#a"/>""")
        f.assertPixel(5, 5, RED)
    }

    @Test
    fun `use can reach something drawn elsewhere`() {
        val f = drawn("""<rect id="a" width="10" height="10" fill="red"/><use href="#a" x="50"/>""")
        f.assertPixel(5, 5, RED)
        f.assertPixel(55, 5, RED)
    }

    @Test
    fun `what the target leaves unset is inherited from the use`() {
        val f =
            drawn(
                """<defs><rect id="a" width="10" height="10"/><rect id="b" width="10" height="10" fill="red"/></defs>
                <use href="#a" fill="#00f"/><use href="#b" x="20" fill="#00f"/>""",
            )
        f.assertPixel(5, 5, BLUE)
        f.assertPixel(25, 5, RED)
    }

    @Test
    fun `use applies its transform before its x and y`() {
        val f = drawn("""<defs><rect id="a" width="10" height="10" fill="red"/></defs><use href="#a" x="10" transform="scale(2)"/>""")
        f.assertPixel(30, 10, RED)
        f.assertPixel(5, 5, CLEAR)
    }

    @Test
    fun `symbol only shows through use and takes its size`() {
        val body =
            """<symbol id="s" viewBox="0 0 10 10"><rect width="5" height="5" fill="red"/></symbol>"""
        assertEquals(0, drawn(body).paintedPixels())
        val f = drawn("""$body<use href="#s" x="20" y="20" width="40" height="40"/>""")
        f.assertPixel(30, 30, RED)
        f.assertPixel(50, 50, CLEAR)
    }

    @Test
    fun `a symbol clips to its viewport`() {
        val f =
            drawn(
                """<symbol id="s" viewBox="0 0 10 10"><rect width="100" height="100" fill="red"/></symbol><use href="#s" width="20" height="20"/>""",
            )
        f.assertPixel(10, 10, RED)
        f.assertPixel(30, 10, CLEAR)
    }

    @Test
    fun `a non-scaling stroke keeps its width on screen`() {
        val doc =
            parse(
                """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" viewBox="0 0 10 10">
                <line x1="0" y1="5" x2="10" y2="5" stroke="red" stroke-width="2" vector-effect="non-scaling-stroke"/></svg>""",
            )
        // Two pixels wide, around the middle row, and not the twenty that the viewBox would make of it.
        assertEquals(49.0, doc.frame().topEdge(), 0.05)
        val plain =
            parse(
                """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" viewBox="0 0 10 10">
                <line x1="0" y1="5" x2="10" y2="5" stroke="red" stroke-width="2"/></svg>""",
            )
        assertEquals(40.0, plain.frame().topEdge(), 0.05)
    }

    @Test
    fun `crisp edges turn antialiasing off`() {
        val smooth = drawn("""<rect x="10.5" y="10" width="20" height="20" fill="red"/>""")
        val crisp = drawn("""<rect x="10.5" y="10" width="20" height="20" fill="red" shape-rendering="crispEdges"/>""")
        assertTrue(Color.alpha(smooth.getPixel(10, 20)) in 1..254)
        assertTrue(Color.alpha(crisp.getPixel(10, 20)) == 0 || Color.alpha(crisp.getPixel(10, 20)) == 255)
    }

    @Test
    fun `pixelated images stay blocky and others are smoothed`() {
        val two = android.graphics.Bitmap.createBitmap(2, 1, android.graphics.Bitmap.Config.ARGB_8888)
        two.setPixel(0, 0, RED)
        two.setPixel(1, 0, BLUE)
        val out = java.io.ByteArrayOutputStream()
        two.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        val uri = "data:image/png;base64," + java.util.Base64
            .getEncoder()
            .encodeToString(out.toByteArray())
        val attributes = """width="100" height="50" preserveAspectRatio="none""""
        val smooth = drawn("""<image href="$uri" $attributes/>""")
        val blocky = drawn("""<image href="$uri" $attributes image-rendering="pixelated"/>""")
        blocky.assertPixel(49, 25, RED)
        blocky.assertPixel(51, 25, BLUE)
        assertTrue(Color.red(smooth.getPixel(50, 25)) in 1..254)
    }

    @Test
    fun `an animated svg image plays on the outer clock and counts as animation`() {
        val inner =
            """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><rect width="10" height="10" fill="red">
            <animate attributeName="fill-opacity" from="0" to="1" dur="2s" repeatCount="indefinite"/></rect></svg>"""
        val uri = "data:image/svg+xml;base64," + java.util.Base64
            .getEncoder()
            .encodeToString(inner.toByteArray())
        val doc = parse(svg("""<image href="$uri" width="100" height="100"/>"""))
        assertTrue(doc.animation.isAnimated)
        assertTrue(doc.animation.loopsForever)
        assertEquals(2.0, doc.animation.durationSeconds, 1e-9)
        assertEquals(0.5, doc.frame(1.0).centreAlpha(), 0.01)
    }

    @Test
    fun `a focal radius starts the gradient from a circle`() {
        val f =
            drawn(
                """<defs><radialGradient id="g" gradientUnits="userSpaceOnUse" cx="50" cy="50" r="50" fr="25">
                    <stop offset="0" stop-color="#f00"/><stop offset="1" stop-color="#00f"/>
                </radialGradient></defs><rect width="100" height="100" fill="url(#g)"/>""",
            )
        f.assertPixelNear(50, 50, RED, tolerance = 4)
        f.assertPixelNear(60, 50, RED, tolerance = 4)
        // Half way between the inner circle at 25 and the outer one at 50.
        f.assertPixelNear(87, 50, 0xFF800080.toInt(), tolerance = 16)
    }

    @Test
    fun `a broken use reference is simply nothing`() {
        assertEquals(0, drawn("""<use href="#nowhere"/>""").paintedPixels())
    }

    @Test
    fun `use chains resolve`() {
        val f =
            drawn("""<defs><rect id="a" width="10" height="10" fill="red"/><use id="b" href="#a" x="10"/></defs><use href="#b" x="10"/>""")
        f.assertPixel(25, 5, RED)
    }

    @Test
    fun `a link draws like a group`() {
        val f = drawn("""<a href="https://example.org"><rect width="10" height="10" fill="red"/></a>""")
        f.assertPixel(5, 5, RED)
    }

    @Test
    fun `switch draws the first child it can`() {
        val f =
            drawn(
                """<switch>
                    <foreignObject width="100" height="100"><div xmlns="http://www.w3.org/1999/xhtml">hi</div></foreignObject>
                    <rect requiredExtensions="http://example.org/unknown" width="100" height="100" fill="#00f"/>
                    <rect width="100" height="100" fill="red"/>
                    <rect width="100" height="100" fill="lime"/>
                </switch>""",
            )
        f.assertPixel(50, 50, RED)
    }

    @Test
    fun `text draws something where it was asked to`() {
        val f = drawn("""<text x="10" y="60" font-size="40" fill="red">H</text>""")
        assertTrue(f.paintedPixels() > 20)
        var lowest = -1
        for (y in 0 until 100) for (x in 0 until 100) if (Color.alpha(f.getPixel(x, y)) > 0) lowest = y
        // Sits on the baseline, so nothing of an H falls below y = 60.
        assertTrue("lowest ink at $lowest", lowest in 55..61)
        assertTrue(f.leftEdge(45) >= 10.0)
    }

    @Test
    fun `text is drawn in its fill colour`() {
        val f = drawn("""<text x="5" y="80" font-size="100" font-weight="bold" fill="#00f">M</text>""")
        var found = false
        for (y in 0 until 100) for (x in 0 until 100) if (f.getPixel(x, y) == BLUE) found = true
        assertTrue(found)
    }

    @Test
    fun `text anchor moves text around its position`() {
        fun ink(anchor: String): Double =
            drawn("""<text x="50" y="60" font-size="30" text-anchor="$anchor">WWW</text>""").leftEdge(50)
        val start = ink("start")
        val middle = ink("middle")
        val end = ink("end")
        assertTrue("start $start", start >= 49.0)
        assertTrue("middle $middle end $end", end < middle && middle < start)
    }

    @Test
    fun `text is cut to the anchored width in both directions`() {
        val f = drawn("""<text x="50" y="60" font-size="20" text-anchor="middle">HHHH</text>""")
        val left = f.leftEdge(55)
        val right = f.rightEdge(55)
        // Centred within a pixel or two: side bearings differ a little between glyph edges.
        assertEquals(50.0, (left + right) / 2.0, 2.0)
    }

    @Test
    fun `a tspan continues the line and can restyle it`() {
        val f = drawn("""<text x="0" y="60" font-size="30" fill="red">II<tspan fill="#00f">II</tspan></text>""")
        var red = 0
        var blue = 0
        for (y in 0 until 100) {
            for (x in 0 until 100) {
                if (f.getPixel(x, y) == RED) red++
                if (f.getPixel(x, y) == BLUE) blue++
            }
        }
        assertTrue("red $red blue $blue", red > 0 && blue > 0)
        assertTrue(f.leftEdge(50) < f.rightEdge(50))
    }

    @Test
    fun `a tspan with x and y starts somewhere new`() {
        val f = drawn("""<text x="0" y="20" font-size="20">H<tspan x="50" y="80">H</tspan></text>""")
        assertTrue(f.leftEdge(70) >= 50.0)
        assertTrue(f.rightEdge(10) < 40.0)
    }

    @Test
    fun `dx and dy shift the pen`() {
        val plain = drawn("""<text x="10" y="50" font-size="20">H</text>""")
        val shifted = drawn("""<text x="10" y="50" dx="20" dy="10" font-size="20">H</text>""")
        assertEquals(plain.leftEdge(40) + 20.0, shifted.leftEdge(50), 0.5)
    }

    @Test
    fun `whitespace in text collapses`() {
        val spaced = drawn(
            """<text x="0" y="50" font-size="20">  A
              B  </text>""",
        )
        val tight = drawn("""<text x="0" y="50" font-size="20">A B</text>""")
        assertSamePixels(tight, spaced)
    }

    @Test
    fun `xml space preserve keeps it`() {
        val spaced = drawn("""<text x="0" y="50" font-size="20" xml:space="preserve">A   B</text>""")
        val tight = drawn("""<text x="0" y="50" font-size="20">A B</text>""")
        assertTrue(spaced.rightEdge(45) > tight.rightEdge(45))
    }

    @Test
    fun `font properties change the outline`() {
        val regular = drawn("""<text x="5" y="60" font-size="30">Hag</text>""")
        assertDiffer(regular, drawn("""<text x="5" y="60" font-size="30" font-weight="bold">Hag</text>"""))
        assertDiffer(regular, drawn("""<text x="5" y="60" font-size="30" font-size-adjust="0" font-family="monospace">Hag</text>"""))
        assertDiffer(regular, drawn("""<text x="5" y="60" font-size="50">Hag</text>"""))
    }

    @Test
    fun `a rotate attribute on text is not a transform, and warns`() {
        val document = parse(svg("""<text x="10" y="50" font-size="30" rotate="90">HH</text>"""))
        assertTrue(SvgWarning.UNSUPPORTED_FEATURE_IGNORED in document.warnings)
        assertSamePixels(drawn("""<text x="10" y="50" font-size="30">HH</text>"""), document.frame())
    }

    @Test
    fun `keywords that mean nothing special raise no warnings`() {
        // All of these turn up in exports from desktop drawing programs.
        val document =
            parse(
                svg(
                    """<text style="letter-spacing: normal; font-variant: normal; line-height: 125%; -inkscape-font-specification: Sans">a</text>
                    <rect width="auto" height="auto" rx="auto" transform="" fill="" d="" style=""/>
                    <rect style="marker: none; mix-blend-mode: normal; isolation: auto; filter: none; clip-path: none; mask: none"/>""",
                ),
            )
        assertEquals(emptySet<SvgWarning>(), document.warnings)
    }

    @Test
    fun `the initial keyword resets keyword properties too`() {
        val f =
            drawn(
                """<g visibility="hidden" stroke-linecap="round" font-weight="bold" fill-rule="evenodd">
                    <rect width="100" height="100" fill="red" style="visibility: initial"/>
                </g>""",
            )
        f.assertPixel(50, 50, RED)
        val rings =
            drawn(
                """<g fill-rule="evenodd"><path d="M0 0 H100 V100 H0 Z M25 25 H75 V75 H25 Z" fill="red" style="fill-rule: initial"/></g>""",
            )
        rings.assertPixel(50, 50, RED)
    }

    @Test
    fun `a transform box on a root or a symbol sees its content`() {
        val root =
            parse(
                """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><defs><clipPath id="c" clipPathUnits="objectBoundingBox"><rect width="0.5" height="1"/></clipPath></defs>
                <g clip-path="url(#c)"><rect width="100" height="100" fill="red"/></g></svg>""",
            )
        root.frame().assertPixel(25, 50, RED)
        root.frame().assertPixel(75, 50, CLEAR)
        val symbol =
            drawn(
                """<defs><clipPath id="c" clipPathUnits="objectBoundingBox"><rect width="0.5" height="1"/></clipPath>
                <symbol id="s" clip-path="url(#c)"><rect width="100" height="100" fill="red"/></symbol></defs><use href="#s"/>""",
            )
        symbol.assertPixel(25, 50, RED)
        symbol.assertPixel(75, 50, CLEAR)
    }

    @Test
    fun `font family names map to generic families`() {
        assertEquals("serif", TextSupport.genericFamily("Times New Roman, serif"))
        assertEquals("serif", TextSupport.genericFamily("'Georgia'"))
        assertEquals("sans-serif", TextSupport.genericFamily("Arial"))
        assertEquals("monospace", TextSupport.genericFamily("\"Courier New\", monospace"))
        assertEquals("monospace", TextSupport.genericFamily("NoSuchFont, Consolas"))
        assertEquals("cursive", TextSupport.genericFamily("Comic Sans MS"))
        assertEquals("sans-serif", TextSupport.genericFamily("NoSuchFont"))
        assertEquals("sans-serif", TextSupport.genericFamily(""))
    }

    @Test
    fun `the font shorthand sets its parts`() {
        val shorthand = drawn("""<text x="5" y="60" style="font: italic bold 30px serif">Hag</text>""")
        val longhand = drawn("""<text x="5" y="60" font-style="italic" font-weight="bold" font-size="30" font-family="serif">Hag</text>""")
        assertSamePixels(longhand, shorthand)
    }

    @Test
    fun `dominant baseline moves text vertically`() {
        val auto = drawn("""<text x="5" y="50" font-size="30">H</text>""")
        val hanging = drawn("""<text x="5" y="50" font-size="30" dominant-baseline="hanging">H</text>""")
        assertTrue(hanging.topEdge(15) > auto.topEdge(15))
    }

    @Test
    fun `an embedded png is drawn at its box`() {
        val png = pngDataUri(2, 2, RED)
        val f = drawn("""<image href="$png" x="10" y="10" width="50" height="50"/>""")
        f.assertPixel(30, 30, RED)
        f.assertPixel(5, 5, CLEAR)
        f.assertPixel(65, 30, CLEAR)
    }

    @Test
    fun `an image with no size uses its own`() {
        val f = drawn("""<image href="${pngDataUri(20, 10, RED)}" x="5" y="5"/>""")
        f.assertPixel(20, 10, RED)
        f.assertPixel(30, 10, CLEAR)
        f.assertPixel(20, 20, CLEAR)
    }

    @Test
    fun `an image keeps its shape inside a different box`() {
        val f = drawn("""<image href="${pngDataUri(10, 10, RED)}" width="100" height="50"/>""")
        f.assertPixel(50, 25, RED)
        f.assertPixel(10, 25, CLEAR)
    }

    @Test
    fun `an image can be stretched with preserveAspectRatio none`() {
        val f = drawn("""<image href="${pngDataUri(10, 10, RED)}" width="100" height="50" preserveAspectRatio="none"/>""")
        f.assertPixel(5, 25, RED)
    }

    @Test
    fun `slice crops the image to its box`() {
        val f = drawn("""<image href="${pngDataUri(10, 10, RED)}" width="100" height="50" preserveAspectRatio="xMidYMid slice"/>""")
        f.assertPixel(50, 25, RED)
        f.assertPixel(50, 60, CLEAR)
    }

    @Test
    fun `an embedded svg image is drawn as vector art`() {
        val inner = """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><rect width="5" height="10" fill="red"/></svg>"""
        val uri = "data:image/svg+xml;base64," + java.util.Base64
            .getEncoder()
            .encodeToString(inner.toByteArray())
        val f = drawn("""<image href="$uri" width="100" height="100"/>""")
        f.assertPixel(25, 50, RED)
        f.assertPixel(75, 50, CLEAR)
    }

    @Test
    fun `an embedded svg image can be written as plain text`() {
        val inner =
            "%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='10'%3E" +
                "%3Crect width='10' height='10' fill='red'/%3E%3C/svg%3E"
        val f = drawn("""<image href="data:image/svg+xml,$inner" width="100" height="100"/>""")
        f.assertPixel(50, 50, RED)
    }

    @Test
    fun `broken image data warns and draws nothing`() {
        val doc = parse(svg("""<image href="data:image/png;base64,AAAA" width="100" height="100"/>"""))
        assertEquals(0, doc.frame().paintedPixels())
    }

    @Test
    fun `an image outside the file is never fetched`() {
        val doc = parse(svg("""<image href="https://example.org/a.png" width="100" height="100"/>"""))
        assertEquals(0, doc.frame().paintedPixels())
        assertTrue(SvgWarning.EXTERNAL_REFERENCE_IGNORED in doc.warnings)
    }
}

internal fun pngDataUri(width: Int, height: Int, color: Int): String {
    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(color)
    val out = java.io.ByteArrayOutputStream()
    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
    return "data:image/png;base64," + java.util.Base64
        .getEncoder()
        .encodeToString(out.toByteArray())
}
