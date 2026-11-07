package org.kineograph.media.svg

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** Things real files do, each found by comparing them with a browser. `SvgBrowserComparisonTest` has the pictures, and this says what was wrong. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SvgRealWorldTest {
    private fun widthOf(picture: Bitmap, y: Int): Int {
        var count = 0
        for (x in 0 until picture.width) if (Color.alpha(picture.getPixel(x, y)) > 127) count++
        return count
    }

    // ----- The outermost element's own box -----

    @Test
    fun `the outermost element's background fills all it was given`() {
        val picture = parse(fixtureText("real-background-letterbox.svg")).frame(0.0, 200, 200)
        // The viewBox is half as tall as the picture, and the background is not.
        picture.assertPixel(5, 5, 0xFFFFFDF5.toInt())
        picture.assertPixel(100, 100, 0xFF2563EB.toInt())
    }

    @Test
    fun `background counts for a style attribute and a stylesheet, and only on the outermost element`() {
        parse(svg("", attributes = """style="background:#111827"""")).frame().assertPixel(50, 50, 0xFF111827.toInt())
        parse(svg("", attributes = """style="background-color: rgb(1, 2, 3)"""")).frame().assertPixel(50, 50, 0xFF010203.toInt())
        parse(svg("<style>svg { background: #010203 }</style>")).frame().assertPixel(50, 50, 0xFF010203.toInt())
        parse(svg("""<svg width="50" height="50" style="background:#f00"/>""")).frame().assertPixel(10, 10, CLEAR)
        parse(svg("", attributes = """style="background:none"""")).frame().assertPixel(50, 50, CLEAR)
    }

    @Test
    fun `the background belongs to the canvas, so the element's own opacity has no say in it`() {
        val faded = parse(svg("""<rect width="100" height="50" fill="#fff"/>""", attributes = """style="background:#000;opacity:.5""""))
        val picture = faded.frame()
        picture.assertPixel(50, 75, Color.BLACK)
        picture.assertPixelNear(50, 25, 0xFF808080.toInt())
    }

    @Test
    fun `a hidden outermost element shows what it draws, as the file does opened by itself`() {
        parse(fixtureText("real-root-hidden.svg")).frame(0.0, 200, 200).assertPixel(100, 100, 0xFF16A34A.toInt())
    }

    // ----- Shapes for clip-path, in motion -----

    @Test
    fun `an animated clip-path shape moves all the way through`() {
        val wipe =
            svg(
                """<style>.w { clip-path: inset(0 100% 0 0); animation: k 2s linear forwards } @keyframes k { to { clip-path: inset(0 0 0 0) } }</style>
                   <rect class="w" width="80" height="20"/>""",
            )
        val document = parse(wipe)
        assertEquals(0, widthOf(document.frame(0.0), 10))
        assertEquals(20, widthOf(document.frame(0.5), 10))
        assertEquals(40, widthOf(document.frame(1.0), 10))
        assertEquals(60, widthOf(document.frame(1.5), 10))
        assertEquals(80, widthOf(document.frame(2.0), 10))
    }

    @Test
    fun `shapes with no in-between still flip half way, as in browsers`() {
        val flip =
            svg(
                """<style>.w { clip-path: circle(10px at 0 0); animation: k 2s linear forwards } @keyframes k { to { clip-path: inset(0) } }</style>
                   <rect class="w" width="80" height="20"/>""",
            )
        val document = parse(flip)
        document.frame(0.9).assertPixel(40, 10, CLEAR)
        assertTrue(Color.alpha(document.frame(1.1).getPixel(40, 10)) != 0)
    }

    // ----- Math -----

    @Test
    fun `math works wherever a number, a length, an angle or a time can be`() {
        assertEquals(Length(px = 10.0), parseLengthOrNull("min(10px, 30px)"))
        assertEquals(Length(px = 30.0), parseLengthOrNull("max(10px, 30px)"))
        assertEquals(Length(px = 20.0), parseLengthOrNull("clamp(0px, 50px, 20px)"))
        assertEquals(Length(px = 5.0), parseLengthOrNull("clamp(5px, 1px, 20px)"))
        assertEquals(Length(pct = 10.0), parseLengthOrNull("min(10%, 30%)"))
        assertEquals(Length(px = 12.0), parseLengthOrNull("MIN(calc(2px * 6), 30px)"))
        assertEquals(Length(px = 15.0, pct = 10.0), parseLengthOrNull("calc(10% + min(15px, 20px))"))
        assertEquals(0.4, parseFraction("calc(.2 + .2)") ?: 0.0, 1e-9)
        assertEquals(0.25, parseFraction("calc(1 / 4)") ?: 0.0, 1e-9)
        assertEquals(0.5, parseFraction("calc(25% + 25%)") ?: 0.0, 1e-9)
        assertEquals(45.0, parseAngle("calc(.125turn)") ?: 0.0, 1e-9)
        assertEquals(90.0, parseAngle("calc(3 * 30deg)") ?: 0.0, 1e-9)
        assertEquals(180.0, parseAngle("max(1rad, 200grad)") ?: 0.0, 1e-9)
        assertEquals(-0.5, parseCssTime("calc(2 * -0.25s)") ?: 0.0, 1e-9)
        assertEquals(1.5, parseCssTime("calc(1s + 500ms)") ?: 0.0, 1e-9)
        assertEquals(2.0, parseCssTime("CALC(1S * 2)") ?: 0.0, 1e-9)
    }

    @Test
    fun `math that does not add up is not a value`() {
        assertNull(parseLengthOrNull("min(10%, 30px)"))
        assertNull(parseLengthOrNull("clamp(1px, 2px)"))
        assertNull(parseAngle("calc(1s + 1deg)"))
        assertNull(parseAngle("calc(10px)"))
        assertNull(parseCssTime("calc(10deg)"))
        assertNull(parseCssTime("calc(1s * 2s)"))
        assertNull(parseFraction("calc(10px)"))
    }

    @Test
    fun `a stagger written with calc really staggers`() {
        val staggered =
            svg(
                """<style>.g { animation: k 2s linear calc(var(--i) * -0.5s) infinite } @keyframes k { to { transform: translateY(40px) } }</style>
                   <rect class="g" style="--i:0" x="0" width="10" height="10"/>
                   <rect class="g" style="--i:1" x="20" width="10" height="10"/>
                   <rect class="g" style="--i:2" x="40" width="10" height="10"/>""",
            )
        val picture = parse(staggered).frame(0.0)
        picture.assertPixel(5, 5, BLACK)
        picture.assertPixel(25, 12, BLACK)
        picture.assertPixel(45, 22, BLACK)
    }

    @Test
    fun `calc in a transform, with brackets of its own`() {
        val turned =
            drawn(
                """<style>.a { transform: translateX(calc(var(--i) * 10px)) } .c { transform: scale(calc(1 + 1)) }</style>
                   <rect class="a" style="--i:2" width="10" height="10"/><rect class="c" y="20" width="10" height="10"/>""",
            )
        turned.assertPixel(25, 5, BLACK)
        turned.assertPixel(5, 5, CLEAR)
        turned.assertPixel(15, 45, BLACK)
    }

    @Test
    fun `calc in a time counts towards how long the picture plays`() {
        val delayed =
            parse(
                svg(
                    """<style>.a { animation: k 1s forwards; animation-delay: calc(6 * .5s) } @keyframes k { to { opacity: 0 } }</style><rect class="a"/>""",
                ),
            )
        assertEquals(4.0, delayed.animation.durationSeconds, 1e-9)
        val looped =
            parse(
                svg("""<style>.a { animation: k calc(1s + 500ms) infinite } @keyframes k { to { opacity: 0 } }</style><rect class="a"/>"""),
            )
        assertEquals(1.5, looped.animation.durationSeconds, 1e-9)
        assertTrue(looped.animation.loopsForever)
    }

    // ----- Turns in 3D -----

    @Test
    fun `a full turn around an axis goes through every angle on the way`() {
        val flip =
            parse(
                svg(
                    """<style>.f { transform-box: fill-box; transform-origin: center; animation: k 2s linear infinite } @keyframes k { to { transform: rotateY(360deg) } }</style>
                       <rect class="f" x="20" y="20" width="60" height="60"/>""",
                ),
            )
        assertEquals(60, widthOf(flip.frame(0.0), 50))
        assertTrue(abs(widthOf(flip.frame(0.25), 50) - 42) <= 1)
        assertTrue(widthOf(flip.frame(0.5), 50) <= 1)
        assertTrue(abs(widthOf(flip.frame(1.0), 50) - 60) <= 1)
    }

    @Test
    fun `any axis can be turned around`() {
        val op = parseCssTransformList("rotate3d(0, 1, 0, 180deg)")?.single() as? TransformOp.Turn3d
        assertNotNull(op)
        val back = parseCssTransformList("rotate3d(0, 0, 0, 90deg)")?.single() as? TransformOp.Rotate
        assertEquals(0.0, back?.degrees ?: 1.0, 0.0)
        // Straight on, a half turn around the diagonal swaps the two axes.
        val diagonal = parseCssTransformList("rotate3d(1, 1, 0, 180deg)")?.single()?.matrix(TransformContext(0.0, 0.0, 0.0))
        assertEquals(0.0, diagonal?.a ?: 1.0, 1e-9)
        assertEquals(1.0, diagonal?.b ?: 0.0, 1e-9)
        assertEquals(1.0, diagonal?.c ?: 0.0, 1e-9)
        assertEquals(0.0, diagonal?.d ?: 1.0, 1e-9)
    }

    // ----- CSS motion paths -----

    @Test
    fun `an element follows its offset path`() {
        val moving =
            parse(
                svg(
                    """<style>.d { offset-path: path("M10 50 h80"); offset-rotate: 0deg; animation: k 2s linear infinite } @keyframes k { from { offset-distance: 0% } to { offset-distance: 100% } }</style>
                       <circle class="d" r="4"/>""",
                ),
            )
        assertFalse(SvgWarning.UNSUPPORTED_FEATURE_IGNORED in moving.warnings)
        moving.frame(0.0).assertPixel(10, 50, BLACK)
        moving.frame(1.0).assertPixel(50, 50, BLACK)
        moving.frame(1.0).assertPixel(10, 50, CLEAR)
    }

    @Test
    fun `by itself it faces along the path, and it can say otherwise`() {
        fun tip(rotate: String): Bitmap =
            drawn(
                """<style>.d { offset-path: path("M50 10 v80"); offset-distance: 50%; $rotate }</style><rect class="d" x="0" y="-2" width="20" height="4"/>""",
            )
        // Pointing down the path, and then along the page.
        tip("").assertPixel(50, 60, BLACK)
        tip("offset-rotate: 0deg").assertPixel(60, 50, BLACK)
        tip("offset-rotate: reverse").assertPixel(50, 40, BLACK)
        tip("offset-rotate: auto 90deg").assertPixel(40, 50, BLACK)
    }

    @Test
    fun `a path can be taken from elsewhere in the file`() {
        drawn(
            """<style>.d { offset-path: url(#p); offset-distance: 100%; offset-rotate: 0deg }</style>
               <path id="p" d="M10 10 h40" fill="none"/><circle class="d" r="4"/>""",
        ).assertPixel(50, 10, BLACK)
    }

    @Test
    fun `what cannot be followed still says so`() {
        val document = parse(svg("""<style>.d { offset-path: ray(45deg) }</style><circle class="d" r="4"/>"""))
        assertTrue(SvgWarning.UNSUPPORTED_FEATURE_IGNORED in document.warnings)
    }

    // ----- Strokes that keep their width -----

    @Test
    fun `a non-scaling stroke keeps its look whatever size the picture is drawn at`() {
        val line =
            parse(
                svg(
                    """<g transform="scale(2)"><line x1="0" y1="25" x2="50" y2="25" stroke="#000" stroke-width="2" vector-effect="non-scaling-stroke"/></g>""",
                ),
            )

        fun thickness(size: Int): Int {
            val picture = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            line.render(Canvas(picture), 0.0, RectF(0f, 0f, size.toFloat(), size.toFloat()))
            var count = 0
            for (y in 0 until size) if (Color.alpha(picture.getPixel(size / 2, y)) > 127) count++
            return count
        }
        assertEquals(2, thickness(100))
        assertEquals(6, thickness(300))
    }

    @Test
    fun `its dashes grow with the picture as well`() {
        val line =
            parse(
                svg(
                    """<line x1="0" y1="50" x2="100" y2="50" stroke="#000" stroke-width="4" stroke-dasharray="10 10" vector-effect="non-scaling-stroke"/>""",
                ),
            )
        val big = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        line.render(Canvas(big), 0.0, RectF(0f, 0f, 300f, 300f))
        big.assertPixel(15, 150, BLACK)
        big.assertPixel(45, 150, CLEAR)
    }

    // ----- Text -----

    @Test
    fun `right to left text starts from the anchor and goes left`() {
        val reversed = drawn("""<text x="90" y="30" direction="rtl" font-size="20" font-family="sans-serif">rtl</text>""")
        var rightmost = -1
        for (y in 0 until 100) for (x in 0 until 100) if (Color.alpha(reversed.getPixel(x, y)) > 0 && x > rightmost) rightmost = x
        assertTrue("ended at $rightmost", rightmost in 80..90)
        val mirrored = drawn("""<text x="10" y="30" direction="rtl" text-anchor="end" font-size="20" font-family="sans-serif">rtl</text>""")
        var leftmost = 100
        for (y in 0 until 100) for (x in 0 until 100) if (Color.alpha(mirrored.getPixel(x, y)) > 0 && x < leftmost) leftmost = x
        assertTrue("started at $leftmost", leftmost in 10..15)
    }

    @Test
    fun `text can run downwards`() {
        val down = drawn("""<text x="50" y="5" writing-mode="tb" font-size="20" font-family="sans-serif">Long</text>""")
        var bottom = 0
        var right = 0
        var left = 100
        for (y in 0 until 100) {
            for (x in 0 until 100) {
                if (Color.alpha(down.getPixel(x, y)) > 0) {
                    if (y > bottom) bottom = y
                    if (x > right) right = x
                    if (x < left) left = x
                }
            }
        }
        assertTrue("reached down to $bottom", bottom > 30)
        assertTrue("from $left to $right", left >= 38 && right <= 62)
    }

    @Test
    fun `upright letters go one below the other`() {
        val up =
            drawn(
                """<text x="50" y="5" style="writing-mode:vertical-rl;text-orientation:upright" font-size="20" font-family="sans-serif">II</text>""",
            )
        // Two separate marks, in one column, with a gap between them.
        var marks = 0
        var inMark = false
        for (y in 0 until 100) {
            val here = (40..60).any { Color.alpha(up.getPixel(it, y)) > 127 }
            if (here && !inMark) marks++
            inMark = here
        }
        assertEquals(2, marks)
    }

    private fun inkWidth(picture: Bitmap): Pair<Int, Int> {
        var left = picture.width
        var right = -1
        for (y in 0 until picture.height) {
            for (x in 0 until picture.width) {
                if (Color.alpha(picture.getPixel(x, y)) > 63) {
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        return left to right
    }

    @Test
    fun `textLength makes a text as long as the file says`() {
        val spread = drawn("""<text x="10" y="50" font-size="10" font-family="sans-serif" textLength="80">spread</text>""")
        val (left, right) = inkWidth(spread)
        assertTrue("from $left", abs(left - 10) <= 2)
        assertTrue("to $right", abs(right - 90) <= 2)
        val squeezed = drawn("""<text x="10" y="50" font-size="20" font-family="sans-serif" textLength="30">squeezed up</text>""")
        assertTrue(abs(inkWidth(squeezed).second - 40) <= 3)
    }

    @Test
    fun `lengthAdjust can stretch the letters instead`() {
        val stretched =
            drawn(
                """<text x="10" y="50" font-size="10" font-family="sans-serif" textLength="80" lengthAdjust="spacingAndGlyphs">HHH</text>""",
            )
        val (left, right) = inkWidth(stretched)
        // A letter's own margins get stretched along with it.
        assertTrue("from $left", abs(left - 10) <= 6)
        assertTrue("to $right", abs(right - 90) <= 6)
        // The letters themselves are wider, so more of the picture is covered than by spacing them out.
        val spaced = drawn("""<text x="10" y="50" font-size="10" font-family="sans-serif" textLength="80">HHH</text>""")
        assertTrue(stretched.paintedPixels() > spaced.paintedPixels())
    }

    @Test
    fun `textLength stays out of the warnings on a whole text, and in them on a part of one`() {
        assertFalse(SvgWarning.UNSUPPORTED_FEATURE_IGNORED in parse(svg("""<text textLength="80">a</text>""")).warnings)
        assertTrue(SvgWarning.UNSUPPORTED_FEATURE_IGNORED in parse(svg("""<text><tspan textLength="80">a</tspan></text>""")).warnings)
    }

    // ----- Selectors, and rules about rules -----

    private fun styled(css: String, element: String): Bitmap = drawn("<style><![CDATA[$css]]></style>$element")

    private val box = """<rect class="a b" width="100" height="100"/>"""

    @Test
    fun `what can never be true is a selector like any other`() {
        styled(".a:hover { fill: #f00 }", box).assertPixel(50, 50, BLACK)
        styled(".a:not(:hover) { fill: #f00 }", box).assertPixel(50, 50, RED)
        styled(":not(:hover).a { fill: #f00 }", box).assertPixel(50, 50, RED)
        styled(".a::before { fill: #f00 }", box).assertPixel(50, 50, BLACK)
    }

    @Test
    fun `one selector that makes no sense voids its whole list`() {
        styled(".a, .b:::x { fill: #f00 }", box).assertPixel(50, 50, BLACK)
        styled(".a, .b:nonsense { fill: #f00 }", box).assertPixel(50, 50, BLACK)
        // Forgiving is what these are for.
        styled(":is(.a, .b:::x) { fill: #f00 }", box).assertPixel(50, 50, RED)
    }

    @Test
    fun `is and where differ in what they add`() {
        styled(":is(.a, .x) { fill: #f00 } rect { fill: #00f }", box).assertPixel(50, 50, RED)
        styled(":where(.a, .x) { fill: #f00 } rect { fill: #00f }", box).assertPixel(50, 50, BLUE)
    }

    @Test
    fun `has looks at what is around`() {
        val group = """<g><rect class="a" width="10" height="10"/><rect class="c" x="20" width="10" height="10"/></g>"""
        styled("g:has(> .a) .c { fill: #f00 }", group).assertPixel(25, 5, RED)
        styled("g:has(> .z) .c { fill: #f00 }", group).assertPixel(25, 5, BLACK)
        styled(".a:has(+ .c) { fill: #f00 }", group).assertPixel(5, 5, RED)
        styled(".a:has(~ .c) { fill: #f00 }", group).assertPixel(5, 5, RED)
        styled("svg:has(.c) .a { fill: #f00 }", group).assertPixel(5, 5, RED)
    }

    @Test
    fun `rules can sit inside rules`() {
        val group = """<g class="n"><rect class="k" width="10" height="10"/></g>"""
        styled(".n { & .k { fill: #f00 } }", group).assertPixel(5, 5, RED)
        styled(".n { .k { fill: #f00 } }", group).assertPixel(5, 5, RED)
        styled(".k { &.k { fill: #f00 } }", group).assertPixel(5, 5, RED)
        styled(".z { .k { fill: #f00 } }", group).assertPixel(5, 5, BLACK)
        styled(".n { fill: #00f; .k { fill: #f00 } }", """<g class="n"><rect width="10" height="10"/></g>""").assertPixel(5, 5, BLUE)
        styled(".n { @media (min-width: 1px) { .k { fill: #f00 } } }", group).assertPixel(5, 5, RED)
    }

    @Test
    fun `a few levels of the same selector inside itself do not take over`() {
        var css = ".k { fill: #f00 }"
        repeat(40) { css = "&&&&&&&& { $css }" }
        val document = parse(svg("""<style><![CDATA[.a { $css }]]></style><rect class="a" width="10" height="10"/>"""))
        // It does not matter what comes of it, as long as it comes.
        document.frame().assertPixel(5, 5, BLACK)
    }

    @Test
    fun `supports answers the question`() {
        styled("@supports (display: grid) { .a { fill: #f00 } }", box).assertPixel(50, 50, RED)
        styled("@supports not (display: grid) { .a { fill: #f00 } }", box).assertPixel(50, 50, BLACK)
        styled("@supports (fill: nonsense) { .a { fill: #f00 } }", box).assertPixel(50, 50, BLACK)
        styled("@supports (-ms-ime-align: auto) { .a { fill: #f00 } }", box).assertPixel(50, 50, BLACK)
        styled("@supports ((display: grid) and (color: red)) or (fill: nonsense) { .a { fill: #f00 } }", box).assertPixel(50, 50, RED)
        styled("@supports selector(:is(a)) { .a { fill: #f00 } }", box).assertPixel(50, 50, RED)
    }

    @Test
    fun `later layers win, and rules in no layer beat them all`() {
        styled("@layer one { .a.b { fill: #00f } } @layer two { .a { fill: #f00 } }", box).assertPixel(50, 50, RED)
        styled("@layer two, one; @layer one { .a { fill: #f00 } } @layer two { .a.b { fill: #00f } }", box).assertPixel(50, 50, RED)
        styled("rect { fill: #f00 } @layer one { .a.b { fill: #00f } }", box).assertPixel(50, 50, RED)
    }

    @Test
    fun `for what is important, layers count the other way round`() {
        styled("@layer one { .a { fill: #f00 !important } } @layer two { .a { fill: #00f !important } } .a { fill: #0f0 !important }", box)
            .assertPixel(50, 50, RED)
    }

    private fun fixtureText(name: String): String = String(fixture(name))
}
