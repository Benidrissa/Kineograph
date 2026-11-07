package org.kineograph.media.svg

import android.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What can be done about a filter, without drawing any blur. The pictures in `real-*.svg` compare the same files with a browser. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SvgFilterTest {
    private fun real(name: String, size: Int = 200) = SvgParser.parse(fixture(name)).frame(0.0, size, size)

    private val square = """<rect x="10" y="10" width="80" height="80" fill="#f00" filter="url(#f)"/>"""

    @Test
    fun `a filter in a mask, the way Cairo writes opacity, keeps the mask meaning what it says`() {
        val picture = real("real-cairo-alpha-mask.svg")
        // Half of the mask's white over white paper, and all of it.
        picture.assertPixelNear(100, 50, 0xFFFF7F7F.toInt())
        picture.assertPixel(100, 110, Color.BLUE)
    }

    @Test
    fun `an exact colour matrix is not something to warn about`() {
        val document = SvgParser.parse(fixture("real-cairo-alpha-mask.svg"))
        assertFalse(SvgWarning.FILTER_IGNORED in document.warnings)
    }

    @Test
    fun `an inverted mask shows a hole where the mask has white`() {
        val picture = real("real-illustrator-inverted-mask.svg")
        // Black inverted to white shows the pink, and the white circle inverted to black is a hole in it.
        picture.assertPixelNear(30, 70, 0xFFEC008C.toInt())
        picture.assertPixel(100, 70, CLEAR)
    }

    @Test
    fun `a colour matrix takes the colours it is told to, inside its region`() {
        val picture =
            drawn(
                """<filter id="f" filterUnits="userSpaceOnUse" x="0" y="0" width="50" height="100" color-interpolation-filters="sRGB">
                     <feColorMatrix values="0 0 1 0 0  0 1 0 0 0  1 0 0 0 0  0 0 0 1 0"/>
                   </filter>
                   $square""",
            )
        picture.assertPixelNear(30, 50, Color.BLUE)
        // Outside the region, nothing shows of what was drawn there.
        picture.assertPixel(70, 50, CLEAR)
    }

    @Test
    fun `the named kinds of colour matrix work too, one after another`() {
        val grey =
            drawn(
                """<filter id="f" color-interpolation-filters="sRGB"><feColorMatrix type="saturate" values="0"/></filter>$square""",
            ).getPixel(50, 50)
        assertTrue("was ${Integer.toHexString(grey)}", Color.red(grey) == Color.green(grey) && Color.green(grey) == Color.blue(grey))
        val twice =
            drawn(
                """<filter id="f" color-interpolation-filters="sRGB"><feColorMatrix type="hueRotate" values="180"/>
                     <feColorMatrix type="hueRotate" values="180"/></filter>$square""",
            )
        twice.assertPixelNear(50, 50, Color.RED, tolerance = 6)
    }

    @Test
    fun `a filter that never reads the element draws no copy of it`() {
        val shadowOnly =
            """<filter id="f"><feOffset in="SourceAlpha" dy="2" result="o"/><feGaussianBlur in="o" stdDeviation="3"/></filter>$square"""
        val document = parse(svg(shadowOnly))
        document.frame().assertPixel(50, 50, CLEAR)
        assertTrue(SvgWarning.FILTER_IGNORED in document.warnings)
    }

    @Test
    fun `Sketch's shadows leave the shapes they fall on as they are`() {
        val picture = real("real-sketch-shadows.svg")
        // The black copies of each shape are what used to cover these.
        picture.assertPixel(55, 55, 0xFFFF9500.toInt())
        picture.assertPixel(150, 55, 0xFF4A90E2.toInt())
        picture.assertPixelNear(100, 150, 0x4DFFFFFF)
    }

    @Test
    fun `an empty filter draws nothing, as the specification says`() {
        drawn("""<filter id="f"/>$square""").assertPixel(50, 50, CLEAR)
    }

    @Test
    fun `a filter that does read the element, but not in a way this can do, still draws the element`() {
        val merged =
            """<filter id="f"><feGaussianBlur in="SourceAlpha" stdDeviation="2" result="b"/>
               <feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter>$square"""
        val document = parse(svg(merged))
        document.frame().assertPixel(50, 50, Color.RED)
        assertTrue(SvgWarning.FILTER_IGNORED in document.warnings)
    }

    @Test
    fun `a primitive that takes what came before reads the element if the first one does`() {
        val chain = """<filter id="f"><feOffset dx="1" dy="1"/><feGaussianBlur stdDeviation="1"/></filter>$square"""
        drawn(chain).assertPixel(50, 50, Color.RED)
    }

    @Test
    fun `a filter with no end of primitives is quick to give up on`() {
        val many = """<feOffset result="a" in="a"/>""".repeat(100_000)
        drawn("""<filter id="f">$many</filter>$square""").assertPixel(50, 50, Color.RED)
    }

    @Test
    fun `a reference to something that is not a filter is ignored`() {
        drawn("""<rect id="f" width="1" height="1"/>$square""").assertPixel(50, 50, Color.RED)
        drawn(square).assertPixel(50, 50, Color.RED)
    }

    @Test
    fun `a filter works with the opacity and the clip around it`() {
        val picture =
            drawn(
                """<filter id="f" color-interpolation-filters="sRGB"><feColorMatrix values="0 0 1 0 0  0 1 0 0 0  1 0 0 0 0  0 0 0 1 0"/></filter>
                   <clipPath id="c"><rect width="50" height="100"/></clipPath>
                   <rect width="100" height="100" fill="#f00" filter="url(#f)" clip-path="url(#c)" opacity="0.5"/>""",
            )
        picture.assertPixelNear(25, 50, 0x800000FF.toInt())
        picture.assertPixel(75, 50, CLEAR)
    }
}
