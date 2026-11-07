package org.kineograph.media.svg

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SvgCompositingTest {
    @Test
    fun `group opacity composites the group as one`() {
        // Two overlapping opaque squares at half opacity. As one group, the top colour shows at 0.5 in the overlap, and the red
        // underneath does not show through it. Applied per shape, the overlap would come out more opaque, with red bleeding in.
        val f =
            drawn(
                """<g opacity="0.5">
                    <rect x="10" y="10" width="50" height="50" fill="#f00"/>
                    <rect x="30" y="30" width="50" height="50" fill="#00f"/>
                </g>""",
            )
        f.assertPixelNear(20, 20, 0x80FF0000.toInt())
        f.assertPixelNear(45, 45, 0x800000FF.toInt())
        f.assertPixelNear(70, 70, 0x800000FF.toInt())
    }

    @Test
    fun `opacity on a shape with both fill and stroke composites them as one`() {
        // The stroke covers the inner half of its width on top of the fill. At group level it fully hides the fill there.
        val f =
            drawn(
                """<rect x="20" y="20" width="60" height="60" fill="#f00" stroke="#00f" stroke-width="10" opacity="0.5"/>""",
            )
        f.assertPixelNear(23, 50, 0x800000FF.toInt())
        f.assertPixelNear(50, 50, 0x80FF0000.toInt())
    }

    @Test
    fun `fill opacity and stroke opacity alone do not composite as one`() {
        val f =
            drawn(
                """<rect x="20" y="20" width="60" height="60" fill="#f00" stroke="#00f" stroke-width="10" stroke-opacity="0.5"/>""",
            )
        // The fill now shows through the half-transparent stroke, so purple, and fully opaque.
        f.assertPixelNear(23, 50, 0xFF800080.toInt())
    }

    @Test
    fun `direct opacity on a simple shape matches what a layer gives`() {
        val simple = drawn("""<rect width="100" height="100" fill="#f00" opacity="0.3"/>""")
        val grouped = drawn("""<g opacity="0.3"><rect width="100" height="100" fill="#f00"/></g>""")
        simple.assertPixelNear(50, 50, grouped.getPixel(50, 50))
    }

    @Test
    fun `opacity zero draws nothing, and values outside the range are clamped`() {
        assertEquals(0, drawn("""<rect width="100" height="100" opacity="0"/>""").paintedPixels())
        assertEquals(0, drawn("""<rect width="100" height="100" opacity="-1"/>""").paintedPixels())
        drawn("""<rect width="100" height="100" fill="red" opacity="7"/>""").assertPixel(50, 50, RED)
    }

    @Test
    fun `a clip path cuts to its shape`() {
        val f =
            drawn(
                """<defs><clipPath id="c"><circle cx="50" cy="50" r="20"/></clipPath></defs>
                <rect width="100" height="100" fill="red" clip-path="url(#c)"/>""",
            )
        f.assertPixel(50, 50, RED)
        f.assertPixel(50, 25, CLEAR)
        f.assertPixel(25, 25, CLEAR)
    }

    @Test
    fun `clip path children combine as a union`() {
        val f =
            drawn(
                """<defs><clipPath id="c">
                    <rect width="30" height="100"/><rect x="70" width="30" height="100"/>
                </clipPath></defs>
                <rect width="100" height="100" fill="red" clip-path="url(#c)"/>""",
            )
        f.assertPixel(15, 50, RED)
        f.assertPixel(50, 50, CLEAR)
        f.assertPixel(85, 50, RED)
    }

    @Test
    fun `an empty clip path hides everything`() {
        val f = drawn("""<defs><clipPath id="c"/></defs><rect width="100" height="100" fill="red" clip-path="url(#c)"/>""")
        assertEquals(0, f.paintedPixels())
    }

    @Test
    fun `a clip reference to nothing is ignored`() {
        val f = drawn("""<rect width="100" height="100" fill="red" clip-path="url(#nowhere)"/>""")
        f.assertPixel(50, 50, RED)
    }

    @Test
    fun `a clip path's own fill is irrelevant, only its outline counts`() {
        val f =
            drawn(
                """<defs><clipPath id="c"><rect width="50" height="100" fill="none" opacity="0"/></clipPath></defs>
                <rect width="100" height="100" fill="red" clip-path="url(#c)"/>""",
            )
        f.assertPixel(25, 50, RED)
    }

    @Test
    fun `clip rule decides about holes`() {
        val rings = "M10 10 H90 V90 H10 Z M30 30 H70 V70 H30 Z"
        val even =
            drawn(
                """<defs><clipPath id="c"><path d="$rings" clip-rule="evenodd"/></clipPath></defs>
                <rect width="100" height="100" fill="red" clip-path="url(#c)"/>""",
            )
        even.assertPixel(50, 50, CLEAR)
        even.assertPixel(20, 20, RED)
        val nonzero =
            drawn(
                """<defs><clipPath id="c"><path d="$rings"/></clipPath></defs>
                <rect width="100" height="100" fill="red" clip-path="url(#c)"/>""",
            )
        nonzero.assertPixel(50, 50, RED)
    }

    @Test
    fun `a clip path with a transform moves its outline`() {
        val f =
            drawn(
                """<defs><clipPath id="c" transform="translate(50 0)"><rect width="30" height="100"/></clipPath></defs>
                <rect width="100" height="100" fill="red" clip-path="url(#c)"/>""",
            )
        f.assertPixel(15, 50, CLEAR)
        f.assertPixel(65, 50, RED)
    }

    @Test
    fun `a clip child's own transform counts`() {
        val f =
            drawn(
                """<defs><clipPath id="c"><rect width="30" height="100" transform="translate(50 0)"/></clipPath></defs>
                <rect width="100" height="100" fill="red" clip-path="url(#c)"/>""",
            )
        f.assertPixel(65, 50, RED)
        f.assertPixel(15, 50, CLEAR)
    }

    @Test
    fun `clip path in object units follows the box`() {
        val f =
            drawn(
                """<defs><clipPath id="c" clipPathUnits="objectBoundingBox"><rect width="0.5" height="1"/></clipPath></defs>
                <rect x="20" y="20" width="60" height="60" fill="red" clip-path="url(#c)"/>""",
            )
        f.assertPixel(30, 50, RED)
        f.assertPixel(70, 50, CLEAR)
    }

    @Test
    fun `clip applies in the clipped element's coordinates`() {
        val f =
            drawn(
                """<defs><clipPath id="c"><rect width="10" height="10"/></clipPath></defs>
                <rect width="100" height="100" fill="red" clip-path="url(#c)" transform="translate(50 50)"/>""",
            )
        f.assertPixel(55, 55, RED)
        f.assertPixel(5, 5, CLEAR)
        f.assertPixel(70, 70, CLEAR)
    }

    @Test
    fun `clip works on groups`() {
        val f =
            drawn(
                """<defs><clipPath id="c"><rect width="50" height="100"/></clipPath></defs>
                <g clip-path="url(#c)"><rect width="100" height="100" fill="red"/></g>""",
            )
        f.assertPixel(25, 50, RED)
        f.assertPixel(75, 50, CLEAR)
    }

    @Test
    fun `clip in object units on a group uses the group's children`() {
        val f =
            drawn(
                """<defs><clipPath id="c" clipPathUnits="objectBoundingBox"><rect width="0.5" height="1"/></clipPath></defs>
                <g clip-path="url(#c)"><rect x="20" width="20" height="100" fill="red"/><rect x="60" width="20" height="100" fill="red"/></g>""",
            )
        // The children span 20 to 80, so half of that ends at x = 50.
        f.assertPixel(30, 50, RED)
        f.assertPixel(70, 50, CLEAR)
    }

    @Test
    fun `use inside a clip path works`() {
        val f =
            drawn(
                """<defs><rect id="r" width="30" height="100"/><clipPath id="c"><use href="#r" x="50"/></clipPath></defs>
                <rect width="100" height="100" fill="red" clip-path="url(#c)"/>""",
            )
        f.assertPixel(65, 50, RED)
        f.assertPixel(15, 50, CLEAR)
    }

    @Test
    fun `text inside a clip path clips to its letters`() {
        val f =
            drawn(
                """<defs><clipPath id="c"><text x="0" y="90" font-size="100" font-weight="bold">M</text></clipPath></defs>
                <rect width="100" height="100" fill="red" clip-path="url(#c)"/>""",
            )
        val painted = f.paintedPixels()
        assertTrue("painted $painted", painted in 200..8000)
    }

    @Test
    fun `a clip path can itself be clipped`() {
        val f =
            drawn(
                """<defs>
                    <clipPath id="outer"><rect y="50" width="100" height="50"/></clipPath>
                    <clipPath id="c" clip-path="url(#outer)"><rect width="50" height="100"/></clipPath>
                </defs>
                <rect width="100" height="100" fill="red" clip-path="url(#c)"/>""",
            )
        f.assertPixel(25, 75, RED)
        f.assertPixel(25, 25, CLEAR)
        f.assertPixel(75, 75, CLEAR)
    }

    @Test
    fun `a clip child can have its own clip`() {
        val f =
            drawn(
                """<defs>
                    <clipPath id="inner"><rect y="50" width="100" height="50"/></clipPath>
                    <clipPath id="c"><rect width="50" height="100" clip-path="url(#inner)"/></clipPath>
                </defs>
                <rect width="100" height="100" fill="red" clip-path="url(#c)"/>""",
            )
        f.assertPixel(25, 75, RED)
        f.assertPixel(25, 25, CLEAR)
    }

    @Test
    fun `a hidden clip child contributes nothing`() {
        val f =
            drawn(
                """<defs><clipPath id="c"><rect width="50" height="100" visibility="hidden"/><rect x="50" width="50" height="100"/></clipPath></defs>
                <rect width="100" height="100" fill="red" clip-path="url(#c)"/>""",
            )
        f.assertPixel(25, 50, CLEAR)
        f.assertPixel(75, 50, RED)
    }

    @Test
    fun `a clip path that references itself does not hang`() {
        val f =
            drawn(
                """<defs><clipPath id="c" clip-path="url(#c)"><rect width="50" height="100"/></clipPath></defs>
                <rect width="100" height="100" fill="red" clip-path="url(#c)"/>""",
            )
        f.assertPixel(25, 50, RED)
    }

    @Test
    fun `a white mask keeps, and a black mask removes`() {
        val f =
            drawn(
                """<defs><mask id="m" maskUnits="userSpaceOnUse" x="0" y="0" width="100" height="100">
                    <rect width="50" height="100" fill="white"/>
                    <rect x="50" width="50" height="100" fill="black"/>
                </mask></defs>
                <rect width="100" height="100" fill="red" mask="url(#m)"/>""",
            )
        f.assertPixel(25, 50, RED)
        f.assertPixel(75, 50, CLEAR)
    }

    @Test
    fun `a mask multiplies by luminance`() {
        fun alphaUnder(colour: String): Double {
            val f =
                drawn(
                    """<defs><mask id="m"><rect width="100" height="100" fill="$colour"/></mask></defs>
                    <rect width="100" height="100" fill="#f00" mask="url(#m)"/>""",
                )
            assertEquals(255, Color.red(f.getPixel(50, 50)).let { if (Color.alpha(f.getPixel(50, 50)) == 0) 255 else it })
            return f.centreAlpha()
        }
        assertEquals(1.0, alphaUnder("#fff"), 0.01)
        assertEquals(0.0, alphaUnder("#000"), 0.01)
        assertEquals(0.5, alphaUnder("#808080"), 0.01)
        // Pure colours count by how bright they look, not by their channel value.
        assertEquals(0.2125, alphaUnder("#f00"), 0.01)
        assertEquals(0.7154, alphaUnder("#0f0"), 0.01)
        assertEquals(0.0721, alphaUnder("#00f"), 0.01)
    }

    @Test
    fun `a half transparent mask counts for half`() {
        val f =
            drawn(
                """<defs><mask id="m"><rect width="100" height="100" fill="#fff" fill-opacity="0.5"/></mask></defs>
                <rect width="100" height="100" fill="#f00" mask="url(#m)"/>""",
            )
        assertEquals(0.5, f.centreAlpha(), 0.01)
    }

    @Test
    fun `alpha masks ignore colour`() {
        val f =
            drawn(
                """<defs><mask id="m" style="mask-type: alpha"><rect width="100" height="100" fill="#000" fill-opacity="0.5"/></mask></defs>
                <rect width="100" height="100" fill="#f00" mask="url(#m)"/>""",
            )
        assertEquals(0.5, f.centreAlpha(), 0.01)
    }

    @Test
    fun `an empty mask hides everything`() {
        val f = drawn("""<defs><mask id="m"/></defs><rect width="100" height="100" fill="red" mask="url(#m)"/>""")
        assertEquals(0, f.paintedPixels())
    }

    @Test
    fun `a mask reference to nothing is ignored`() {
        drawn("""<rect width="100" height="100" fill="red" mask="url(#nope)"/>""").assertPixel(50, 50, RED)
    }

    @Test
    fun `the default mask region is the box plus ten percent`() {
        val f =
            drawn(
                """<defs><mask id="m"><rect width="100" height="100" fill="white"/></mask></defs>
                <g mask="url(#m)"><rect x="40" y="40" width="20" height="20" fill="red"/>
                <rect x="40" y="10" width="20" height="5" fill="red" opacity="0"/></g>""",
            )
        f.assertPixel(50, 50, RED)
    }

    @Test
    fun `the mask region cuts off what falls outside it`() {
        val f =
            drawn(
                """<defs><mask id="m" x="0" y="0" width="0.5" height="1"><rect width="100" height="100" fill="white"/></mask></defs>
                <rect width="100" height="100" fill="red" mask="url(#m)"/>""",
            )
        f.assertPixel(25, 50, RED)
        f.assertPixel(75, 50, CLEAR)
    }

    @Test
    fun `mask content can be in object units`() {
        val f =
            drawn(
                """<defs><mask id="m" maskContentUnits="objectBoundingBox"><rect width="0.5" height="1" fill="white"/></mask></defs>
                <rect x="20" y="20" width="60" height="60" fill="red" mask="url(#m)"/>""",
            )
        f.assertPixel(30, 50, RED)
        f.assertPixel(70, 50, CLEAR)
    }

    @Test
    fun `a gradient in a mask gives a soft edge`() {
        val f =
            drawn(
                """<defs><linearGradient id="g"><stop offset="0" stop-color="#fff"/><stop offset="1" stop-color="#000"/></linearGradient>
                <mask id="m"><rect width="100" height="100" fill="url(#g)"/></mask></defs>
                <rect width="100" height="100" fill="#f00" mask="url(#m)"/>""",
            )
        assertEquals(0.5, Color.alpha(f.getPixel(50, 50)) / 255.0, 0.03)
        assertEquals(1.0, Color.alpha(f.getPixel(1, 50)) / 255.0, 0.03)
    }

    @Test
    fun `mask and opacity and clip all stack`() {
        val f =
            drawn(
                """<defs>
                    <mask id="m"><rect width="100" height="100" fill="#fff"/></mask>
                    <clipPath id="c"><rect width="50" height="100"/></clipPath>
                </defs>
                <rect width="100" height="100" fill="#f00" mask="url(#m)" clip-path="url(#c)" opacity="0.5"/>""",
            )
        f.assertPixelNear(25, 50, 0x80FF0000.toInt())
        f.assertPixel(75, 50, CLEAR)
    }

    @Test
    fun `a mask does not touch what is already on the canvas`() {
        val f =
            drawn(
                """<rect width="100" height="100" fill="#00f"/>
                <defs><mask id="m"><rect width="50" height="100" fill="#fff"/></mask></defs>
                <rect width="100" height="100" fill="#f00" mask="url(#m)"/>""",
            )
        f.assertPixel(25, 50, RED)
        f.assertPixel(75, 50, BLUE)
    }

    @Test
    fun `a mask that masks with itself does not hang`() {
        val f =
            drawn(
                """<defs><mask id="m"><rect width="100" height="100" fill="#fff" mask="url(#m)"/></mask></defs>
                <rect width="100" height="100" fill="red" mask="url(#m)"/>""",
            )
        f.assertPixel(50, 50, RED)
    }

    @Test
    fun `a group's box for its mask comes from its children`() {
        val f =
            drawn(
                """<defs><mask id="m" maskContentUnits="objectBoundingBox"><rect width="0.5" height="1" fill="#fff"/></mask></defs>
                <g mask="url(#m)"><rect x="20" width="60" height="100" fill="red"/></g>""",
            )
        f.assertPixel(30, 50, RED)
        f.assertPixel(70, 50, CLEAR)
    }

    @Test
    fun `filters are skipped with a warning and the element still draws`() {
        val doc =
            parse(
                svg(
                    """<defs><filter id="f"><feGaussianBlur stdDeviation="5"/></filter></defs>
                    <rect width="100" height="100" fill="red" filter="url(#f)"/>""",
                ),
            )
        assertTrue(SvgWarning.FILTER_IGNORED in doc.warnings)
        doc.frame().assertPixel(50, 50, RED)
    }

    @Test
    fun `css filter functions warn too`() {
        assertTrue(SvgWarning.FILTER_IGNORED in parse(svg("""<rect style="filter: blur(2px)"/>""")).warnings)
    }

    @Test
    fun `filter none is not a warning`() {
        assertTrue(SvgWarning.FILTER_IGNORED !in parse(svg("""<rect style="filter: none"/>""")).warnings)
    }

    @Test
    fun `an unused filter is not a warning`() {
        assertTrue(SvgWarning.FILTER_IGNORED !in parse(svg("""<defs><filter id="f"/></defs>""")).warnings)
    }
}
