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
class SvgCssAnimationTest {
    private val fade = "@keyframes fade { from { opacity: 0 } to { opacity: 1 } }"

    /** A full-canvas rectangle with an opacity animation. Its alpha reads the value back, to within 1/255. */
    private fun probe(animation: String, extraCss: String = "", attributes: String = ""): SvgDocument =
        parse(svg("""<style>$fade $extraCss rect { $animation }</style><rect width="100" height="100" $attributes/>"""))

    private fun assertAlpha(doc: SvgDocument, time: Double, expected: Double) {
        assertEquals("at t=$time", expected, doc.frame(time).centreAlpha(), 0.006)
    }

    @Test
    fun `a shorthand plays`() {
        val doc = probe("animation: fade 2s linear")
        assertAlpha(doc, 0.5, 0.25)
        assertAlpha(doc, 1.0, 0.5)
    }

    @Test
    fun `the timing function defaults to ease`() {
        val doc = probe("animation: fade 1s")
        assertAlpha(doc, 0.5, 0.8024)
    }

    @Test
    fun `longhands play`() {
        val doc =
            probe(
                """animation-name: fade; animation-duration: 2s; animation-timing-function: linear; animation-delay: 1s;
                animation-iteration-count: 2; animation-direction: normal; animation-fill-mode: both""",
            )
        assertAlpha(doc, 0.5, 0.0)
        assertAlpha(doc, 2.0, 0.5)
        assertAlpha(doc, 4.0, 0.5)
        assertAlpha(doc, 9.0, 1.0)
    }

    @Test
    fun `a longhand after the shorthand overrides it`() {
        val doc = probe("animation: fade 2s linear; animation-duration: 4s")
        assertAlpha(doc, 1.0, 0.25)
    }

    @Test
    fun `the shorthand after a longhand resets it`() {
        val doc = probe("animation-duration: 4s; animation: fade 2s linear")
        assertAlpha(doc, 1.0, 0.5)
    }

    @Test
    fun `shorthand words may come in any order`() {
        assertAlpha(probe("animation: linear 2s fade"), 1.0, 0.5)
        assertAlpha(probe("animation: 2s fade linear 1s forwards"), 2.0, 0.5)
        assertAlpha(probe("animation: infinite fade 1s linear alternate"), 1.5, 0.5)
    }

    @Test
    fun `milliseconds count`() {
        assertAlpha(probe("animation: fade 500ms linear"), 0.25, 0.5)
    }

    @Test
    fun `iteration count repeats`() {
        val doc = probe("animation: fade 1s linear 2", attributes = """opacity="0.3"""")
        assertAlpha(doc, 1.25, 0.25)
        assertAlpha(doc, 2.5, 0.3)
    }

    @Test
    fun `infinite never stops`() {
        val doc = probe("animation: fade 1s linear infinite")
        assertAlpha(doc, 0.25, 0.25)
        assertAlpha(doc, 500.75, 0.75)
    }

    @Test
    fun `fractional iteration counts stop part way`() {
        val doc = probe("animation: fade 1s linear 1.5 forwards")
        assertAlpha(doc, 3.0, 0.5)
    }

    @Test
    fun `direction reverse plays backwards`() {
        val doc = probe("animation: fade 2s linear reverse")
        assertAlpha(doc, 0.5, 0.75)
        assertAlpha(doc, 1.5, 0.25)
    }

    @Test
    fun `direction alternate swaps on odd runs`() {
        val doc = probe("animation: fade 2s linear infinite alternate")
        assertAlpha(doc, 0.5, 0.25)
        assertAlpha(doc, 2.5, 0.75)
        assertAlpha(doc, 4.5, 0.25)
    }

    @Test
    fun `direction alternate-reverse swaps on even runs`() {
        val doc = probe("animation: fade 2s linear infinite alternate-reverse")
        assertAlpha(doc, 0.5, 0.75)
        assertAlpha(doc, 2.5, 0.25)
    }

    @Test
    fun `reverse applies the timing function after flipping`() {
        // Half way through, the flipped progress is also a half, so the curve gives its usual value there.
        val doc = probe("animation: fade 1s ease-in reverse")
        assertAlpha(doc, 0.5, 0.3154)
    }

    @Test
    fun `fill none shows the base value outside the run`() {
        val doc = probe("animation: fade 1s linear 1s", attributes = """opacity="0.3"""")
        assertAlpha(doc, 0.5, 0.3)
        assertAlpha(doc, 1.5, 0.5)
        assertAlpha(doc, 2.5, 0.3)
    }

    @Test
    fun `fill forwards holds the end`() {
        val doc = probe("animation: fade 1s linear forwards", attributes = """opacity="0.3"""")
        assertAlpha(doc, 5.0, 1.0)
    }

    @Test
    fun `fill backwards shows the start during the delay`() {
        val doc = probe("animation: fade 1s linear 1s backwards", attributes = """opacity="0.3"""")
        assertAlpha(doc, 0.5, 0.0)
        assertAlpha(doc, 5.0, 0.3)
    }

    @Test
    fun `fill both does both`() {
        val doc = probe("animation: fade 1s linear 1s both", attributes = """opacity="0.3"""")
        assertAlpha(doc, 0.5, 0.0)
        assertAlpha(doc, 5.0, 1.0)
    }

    @Test
    fun `forwards after reverse holds the start`() {
        val doc = probe("animation: fade 1s linear reverse forwards")
        assertAlpha(doc, 5.0, 0.0)
    }

    @Test
    fun `forwards after alternate holds where the last run ended`() {
        assertAlpha(probe("animation: fade 1s linear 2 alternate forwards"), 5.0, 0.0)
        assertAlpha(probe("animation: fade 1s linear 3 alternate forwards"), 5.0, 1.0)
    }

    @Test
    fun `a negative delay starts part way in`() {
        val doc = probe("animation: fade 2s linear -1s")
        assertAlpha(doc, 0.0, 0.5)
        assertAlpha(doc, 0.5, 0.75)
    }

    @Test
    fun `a paused animation stays at its start`() {
        val doc = probe("animation: fade 2s linear both; animation-play-state: paused")
        assertAlpha(doc, 1.0, 0.0)
        assertAlpha(doc, 5.0, 0.0)
    }

    @Test
    fun `a paused animation with a negative delay stays where that puts it`() {
        val doc = probe("animation: fade 2s linear -1s paused")
        assertAlpha(doc, 5.0, 0.5)
    }

    @Test
    fun `direction stays right however late the time`() {
        // Past the range of an Int, an alternating animation must still know which way it is going.
        val doc = probe("animation: fade 0.001s linear infinite alternate")
        assertTrue(doc.frame(999_999_999.0).centreAlpha() in 0.0..1.0)
        val slow = probe("animation: fade 2s linear infinite alternate")
        assertAlpha(slow, 1_000_000.5, 0.25)
        assertAlpha(slow, 1_000_002.5, 0.75)
    }

    @Test
    fun `every keyword easing matches its curve`() {
        assertAlpha(probe("animation: fade 1s linear"), 0.5, 0.5)
        assertAlpha(probe("animation: fade 1s ease"), 0.5, 0.8024)
        assertAlpha(probe("animation: fade 1s ease-in"), 0.5, 0.3154)
        assertAlpha(probe("animation: fade 1s ease-out"), 0.5, 0.6846)
        assertAlpha(probe("animation: fade 1s ease-in-out"), 0.25, 0.1292)
    }

    @Test
    fun `cubic-bezier is honoured`() {
        assertAlpha(probe("animation: fade 1s cubic-bezier(.1,.7,1,.1)"), 0.5, 0.4173)
    }

    @Test
    fun `cubic-bezier may overshoot, and the value clamps at drawing`() {
        val doc = probe("animation: fade 1s cubic-bezier(.5, 1.5, .5, 1.5)")
        assertAlpha(doc, 0.5, 1.0)
    }

    @Test
    fun `steps jump`() {
        val doc = probe("animation: fade 1s steps(4)")
        assertAlpha(doc, 0.1, 0.0)
        assertAlpha(doc, 0.3, 0.25)
        assertAlpha(doc, 0.9, 0.75)
    }

    @Test
    fun `steps with start jump first`() {
        val doc = probe("animation: fade 1s steps(4, start)")
        assertAlpha(doc, 0.1, 0.25)
        assertAlpha(doc, 0.9, 1.0)
    }

    @Test
    fun `step-start and step-end`() {
        assertAlpha(probe("animation: fade 1s step-start"), 0.5, 1.0)
        assertAlpha(probe("animation: fade 1s step-end"), 0.5, 0.0)
    }

    @Test
    fun `steps with the other jump positions`() {
        val none = probe("animation: fade 1s steps(3, jump-none)")
        assertAlpha(none, 0.1, 0.0)
        assertAlpha(none, 0.5, 0.5)
        assertAlpha(none, 0.9, 1.0)
        val both = probe("animation: fade 1s steps(3, jump-both)")
        assertAlpha(both, 0.1, 0.25)
        assertAlpha(both, 0.9, 0.75)
    }

    @Test
    fun `easing applies per keyframe gap, not across the whole run`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { 0% { opacity: 0 } 50% { opacity: 0.5 } 100% { opacity: 1 } }
                    rect { animation: k 2s ease-in-out }</style><rect width="100" height="100"/>""",
                ),
            )
        // A quarter into the first gap, on its curve, and then the same in the second.
        assertAlpha(doc, 0.25, 0.5 * 0.1292)
        assertAlpha(doc, 1.25, 0.5 + 0.5 * 0.1292)
    }

    @Test
    fun `a keyframe can set its own easing`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k {
                        0% { opacity: 0; animation-timing-function: linear }
                        50% { opacity: 0.5; animation-timing-function: step-end }
                        100% { opacity: 1 }
                    } rect { animation: k 2s ease }</style><rect width="100" height="100"/>""",
                ),
            )
        assertAlpha(doc, 0.5, 0.25)
        assertAlpha(doc, 1.5, 0.5)
    }

    @Test
    fun `keyframe selectors can be percentages, lists and in any order`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { 100% { opacity: 1 } 0%, 50% { opacity: 0 } } rect { animation: k 2s linear }</style>
                    <rect width="100" height="100"/>""",
                ),
            )
        assertAlpha(doc, 0.5, 0.0)
        assertAlpha(doc, 1.5, 0.5)
    }

    @Test
    fun `missing first and last keyframes use the base value`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { 50% { opacity: 1 } } rect { animation: k 2s linear }</style>
                    <rect width="100" height="100" opacity="0.2"/>""",
                ),
            )
        assertAlpha(doc, 0.0, 0.2)
        assertAlpha(doc, 0.5, 0.6)
        assertAlpha(doc, 1.0, 1.0)
        assertAlpha(doc, 1.5, 0.6)
    }

    @Test
    fun `missing keyframes use an inherited value`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { to { fill-opacity: 1 } } rect { animation: k 2s linear }</style>
                    <g fill-opacity="0.2"><rect width="100" height="100"/></g>""",
                ),
            )
        assertAlpha(doc, 1.0, 0.6)
    }

    @Test
    fun `a keyframe property each has its own stops`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { 0% { fill: #f00; x: 0px } 50% { fill: #00f } 100% { x: 100px; fill: #f00 } }
                    rect { animation: k 2s linear }</style><rect width="10" height="100"/>""",
                ),
            )
        // x runs straight from 0 to 100 over the whole time, past the 50% stop it does not have.
        assertEquals(25.0, doc.frame(0.5).leftEdge(), 0.05)
    }

    @Test
    fun `the last block for a moment wins`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { 0% { opacity: 0 } 0% { opacity: 0.5 } 100% { opacity: 0.5 } } rect { animation: k 2s linear }</style>
                    <rect width="100" height="100"/>""",
                ),
            )
        assertAlpha(doc, 0.0, 0.5)
    }

    @Test
    fun `the last keyframes rule of a name wins`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { to { opacity: 1 } } @keyframes k { from { opacity: 0 } to { opacity: 0 } }
                    rect { animation: k 2s linear }</style><rect width="100" height="100"/>""",
                ),
            )
        assertAlpha(doc, 1.0, 0.0)
    }

    @Test
    fun `prefixed keyframes and animation work`() {
        val doc =
            parse(
                svg(
                    """<style>@-webkit-keyframes k { from { opacity: 0 } to { opacity: 1 } }
                    rect { -webkit-animation: k 2s linear }</style><rect width="100" height="100"/>""",
                ),
            )
        assertAlpha(doc, 1.0, 0.5)
    }

    @Test
    fun `a name with no keyframes does nothing`() {
        val doc = probe("animation: nosuch 2s linear")
        assertEquals(false, doc.animation.isAnimated)
    }

    @Test
    fun `animation-name none does nothing`() {
        assertEquals(false, probe("animation: none").animation.isAnimated)
    }

    @Test
    fun `important in a keyframe is ignored`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { from { opacity: 0 !important; fill-opacity: 0 } to { fill-opacity: 1 } } rect { animation: k 2s linear }</style>
                    <rect width="100" height="100" opacity="0.5"/>""",
                ),
            )
        assertAlpha(doc, 1.0, 0.25)
    }

    @Test
    fun `an important static value beats the animation`() {
        val doc = probe("animation: fade 2s linear; opacity: 0.3 !important")
        assertAlpha(doc, 1.0, 0.3)
    }

    @Test
    fun `a css animation beats smil on the same property`() {
        val doc =
            parse(
                svg(
                    """<style>$fade rect { animation: fade 2s linear }</style>
                    <rect width="100" height="100"><animate attributeName="opacity" from="1" to="1" dur="4s"/></rect>""",
                ),
            )
        assertAlpha(doc, 1.0, 0.5)
    }

    @Test
    fun `animations in a list run together, and later ones win a property`() {
        val doc =
            parse(
                svg(
                    """<style>
                    @keyframes a { from { opacity: 0 } to { opacity: 0 } }
                    @keyframes b { from { opacity: 0.5 } to { opacity: 0.5 } }
                    rect { animation: a 2s linear, b 2s linear 1s }
                    </style><rect width="100" height="100"/>""",
                ),
            )
        assertAlpha(doc, 0.5, 0.0)
        assertAlpha(doc, 1.5, 0.5)
    }

    @Test
    fun `longhand lists cycle to match the names`() {
        val doc =
            parse(
                svg(
                    """<style>
                    @keyframes a { from { opacity: 0 } to { opacity: 1 } }
                    @keyframes b { from { fill-opacity: 0 } to { fill-opacity: 1 } }
                    rect { animation-name: a, b; animation-duration: 2s; animation-timing-function: linear }
                    </style><rect width="100" height="100"/>""",
                ),
            )
        // Both at a half, multiplied together.
        assertAlpha(doc, 1.0, 0.25)
    }

    @Test
    fun `animations reach an element through a class and a selector`() {
        val doc =
            parse(
                svg(
                    """<style>$fade .a .b { animation: fade 2s linear }</style>
                    <g class="a"><rect class="b" width="100" height="100"/></g>""",
                ),
            )
        assertAlpha(doc, 1.0, 0.5)
    }

    @Test
    fun `an inline style can start an animation`() {
        val doc =
            parse(
                svg(
                    """<style>$fade</style>
                    <rect style="animation: fade 2s linear" width="100" height="100"/>""",
                ),
            )
        assertAlpha(doc, 1.0, 0.5)
    }

    @Test
    fun `a staggered delay comes through nth-child`() {
        val doc =
            parse(
                svg(
                    """<style>$fade
                    rect { animation: fade 1s linear both }
                    rect:nth-child(2) { animation-delay: 1s }
                    </style>
                    <g><rect width="50" height="100"/><rect x="50" width="50" height="100"/></g>""",
                ),
            )
        val f = doc.frame(0.5)
        assertEquals(0.5, Color.alpha(f.getPixel(25, 50)) / 255.0, 0.01)
        assertEquals(0, Color.alpha(f.getPixel(75, 50)))
    }

    @Test
    fun `colours interpolate, and fading to transparent has no dark fringe`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { from { fill: #f00 } to { fill: rgba(0, 0, 255, 0) } } rect { animation: k 2s linear }</style>
                    <rect width="100" height="100"/>""",
                ),
            )
        // Half way, premultiplied: half alpha, and all of what is left is still red.
        doc.frame(1.0).assertPixelNear(50, 50, 0x80FF0000.toInt(), tolerance = 3)
    }

    @Test
    fun `fill and stroke animate`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { from { fill: #f00; stroke: #f00 } to { fill: #00f; stroke: #00f } }
                    rect { animation: k 2s linear; stroke-width: 10px }</style>
                    <rect x="20" y="20" width="60" height="60"/>""",
                ),
            )
        doc.frame(1.0).assertPixelNear(50, 50, 0xFF800080.toInt())
        doc.frame(1.0).assertPixelNear(16, 50, 0xFF800080.toInt())
    }

    @Test
    fun `currentColor in a keyframe follows color`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { from { fill: #f00 } to { fill: currentColor } } rect { animation: k 2s linear; color: #00f }</style>
                    <rect width="100" height="100"/>""",
                ),
            )
        doc.frame(1.0).assertPixelNear(50, 50, 0xFF800080.toInt())
    }

    @Test
    fun `a dash offset drawing effect works`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes draw { from { stroke-dashoffset: 100 } to { stroke-dashoffset: 0 } }
                    line { stroke: #f00; stroke-width: 10; stroke-dasharray: 100; animation: draw 2s linear forwards }</style>
                    <line x1="0" y1="50" x2="100" y2="50"/>""",
                ),
            )
        assertEquals(0, doc.frame(0.0).paintedPixels())
        assertEquals(50.0, doc.frame(1.0).rightEdge(), 0.05)
        assertEquals(100.0, doc.frame(2.0).rightEdge(), 0.05)
    }

    @Test
    fun `the same effect works with pathLength`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes draw { from { stroke-dashoffset: 1 } to { stroke-dashoffset: 0 } }
                    line { stroke: #f00; stroke-width: 10; stroke-dasharray: 1; animation: draw 2s linear forwards }</style>
                    <line x1="0" y1="50" x2="100" y2="50" pathLength="1"/>""",
                ),
            )
        assertEquals(50.0, doc.frame(1.0).rightEdge(), 0.05)
    }

    @Test
    fun `stroke-dasharray interpolates in keyframes`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { from { stroke-dasharray: 0 100 } to { stroke-dasharray: 100 100 } }
                    line { stroke: #f00; stroke-width: 10; animation: k 2s linear }</style>
                    <line x1="0" y1="50" x2="100" y2="50"/>""",
                ),
            )
        assertEquals(50.0, doc.frame(1.0).rightEdge(), 0.05)
    }

    @Test
    fun `geometry properties animate`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { from { r: 10px; cx: 20px } to { r: 40px; cx: 60px } }
                    circle { cy: 50px; animation: k 2s linear; fill: #f00 }</style><circle/>""",
                ),
            )
        doc.frame(1.0).assertPixel(40, 50, RED)
        assertEquals(40.0 - 25.0, doc.frame(1.0).leftEdge(), 0.1)
    }

    @Test
    fun `path d animates through css`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { from { d: path("M0 0 H10 V100 H0 Z") } to { d: path("M0 0 H50 V100 H0 Z") } }
                    path { fill: #f00; animation: k 2s linear }</style><path d="M0 0 H10 V100 H0 Z"/>""",
                ),
            )
        assertEquals(30.0, doc.frame(1.0).rightEdge(), 0.05)
    }

    @Test
    fun `visibility in css stays visible through a transition touching visible`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { from { visibility: hidden } to { visibility: visible } }
                    rect { animation: k 2s linear; fill: #f00 }</style><rect width="100" height="100"/>""",
                ),
            )
        doc.frame(0.1).assertPixel(50, 50, RED)
    }

    @Test
    fun `display none only takes hold at the very ends of an animation`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { from { display: none } to { display: inline } }
                    rect { animation: k 2s linear forwards; fill: #f00 }</style><rect width="100" height="100"/>""",
                ),
            )
        assertEquals(0, doc.frame(0.0).paintedPixels())
        doc.frame(0.5).assertPixel(50, 50, RED)
        doc.frame(1.5).assertPixel(50, 50, RED)
        val out =
            parse(
                svg(
                    """<style>@keyframes k { from { display: inline } to { display: none } }
                    rect { animation: k 2s linear forwards; fill: #f00 }</style><rect width="100" height="100"/>""",
                ),
            )
        out.frame(1.5).assertPixel(50, 50, RED)
        assertEquals(0, out.frame(2.0).paintedPixels())
    }

    @Test
    fun `custom properties reach into keyframes`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { from { opacity: var(--start) } to { opacity: 1 } }
                    rect { --start: 0.5; animation: k 2s linear }</style><rect width="100" height="100"/>""",
                ),
            )
        assertAlpha(doc, 1.0, 0.75)
    }

    // Transforms

    private fun spin(rule: String, shape: String): SvgDocument =
        parse(svg("""<style>@keyframes spin { from { transform: rotate(0deg) } to { transform: rotate(360deg) } } $rule</style>$shape"""))

    @Test
    fun `translate in keyframes moves a shape`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { from { transform: translate(0, 0) } to { transform: translate(60px, 40px) } }
                    rect { animation: k 2s linear; fill: #f00 }</style><rect width="10" height="10"/>""",
                ),
            )
        doc.frame(1.0).assertPixel(35, 25, RED)
        doc.frame(1.0).assertPixel(5, 5, CLEAR)
    }

    @Test
    fun `none interpolates with a transform`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { from { transform: none } to { transform: translate(60px, 0) } }
                    rect { animation: k 2s linear; fill: #f00 }</style><rect width="10" height="10"/>""",
                ),
            )
        doc.frame(1.0).assertPixel(35, 5, RED)
    }

    @Test
    fun `a missing from keyframe starts from the static transform`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { to { transform: translate(60px, 0) } }
                    rect { transform: translate(20px, 0); animation: k 2s linear; fill: #f00 }</style><rect width="10" height="10"/>""",
                ),
            )
        doc.frame(1.0).assertPixel(45, 5, RED)
    }

    @Test
    fun `a full turn passes through the half turn`() {
        val doc =
            spin(
                "rect { animation: spin 4s linear infinite; transform-origin: 50px 50px; fill: #f00 }",
                """<rect x="60" y="45" width="30" height="10"/>""",
            )
        doc.frame(0.0).assertPixel(75, 50, RED)
        doc.frame(1.0).assertPixel(50, 75, RED)
        doc.frame(2.0).assertPixel(25, 50, RED)
        doc.frame(3.0).assertPixel(50, 25, RED)
    }

    @Test
    fun `transform-origin percentages default to the view box`() {
        val doc =
            spin(
                "rect { animation: spin 4s linear infinite; transform-origin: 50% 50%; fill: #f00 }",
                """<rect x="60" y="45" width="30" height="10"/>""",
            )
        // The centre of the 100 by 100 canvas, and not of the rectangle.
        doc.frame(1.0).assertPixel(50, 75, RED)
    }

    @Test
    fun `transform-box fill-box measures percentages from the shape`() {
        val doc =
            spin(
                "rect { animation: spin 4s linear infinite; transform-box: fill-box; transform-origin: 50% 50%; fill: #f00 }",
                """<rect x="40" y="10" width="20" height="4"/>""",
            )
        // Turning about its own centre, (50, 12), and a quarter turn stands it upright there.
        val f = doc.frame(1.0)
        f.assertPixel(50, 18, RED)
        f.assertPixel(42, 12, CLEAR)
    }

    @Test
    fun `transform-box fill-box has its default origin at the box corner`() {
        val doc =
            spin(
                "rect { animation: spin 4s linear infinite; transform-box: fill-box; fill: #f00 }",
                """<rect x="40" y="40" width="20" height="4"/>""",
            )
        // Quarter turn about (40, 40): goes straight down from there.
        val f = doc.frame(1.0)
        f.assertPixel(38, 50, RED)
        f.assertPixel(50, 42, CLEAR)
    }

    @Test
    fun `transform-box fill-box works on a group, from its children`() {
        val doc =
            spin(
                "g { animation: spin 4s linear infinite; transform-box: fill-box; transform-origin: center; fill: #f00 }",
                """<g><rect x="30" y="48" width="10" height="4"/><rect x="60" y="48" width="10" height="4"/></g>""",
            )
        // The group spans 30 to 70 and turns about (50, 50), so its parts end up above and below the centre.
        val f = doc.frame(1.0)
        f.assertPixel(50, 35, RED)
        f.assertPixel(50, 65, RED)
        f.assertPixel(35, 50, CLEAR)
    }

    @Test
    fun `transform-box stroke-box includes the stroke`() {
        val doc =
            parse(
                svg(
                    """<rect x="40" y="40" width="20" height="20" fill="#f00" stroke="#f00" stroke-width="10"
                        style="transform-box: stroke-box; transform-origin: 0 0; transform: scale(2)"/>""",
                ),
            )
        // The stroke box starts at (35, 35), and that corner stays put under the scale.
        val f = doc.frame()
        f.assertPixel(36, 36, RED)
        f.assertPixel(33, 33, CLEAR)
        f.assertPixel(84, 84, RED)
    }

    @Test
    fun `transform-origin keywords work in either order`() {
        val body = """<rect x="0" y="0" width="100" height="10" fill="#f00" style="transform: scale(0.5); transform-origin: %s"/>"""
        val leftTop = drawn(body.replace("%s", "left top"))
        leftTop.assertPixel(25, 2, RED)
        leftTop.assertPixel(75, 2, CLEAR)
        // Shrinking towards the top right corner, (100, 0), keeps it against that corner, however the words are ordered.
        val rightTop = drawn(body.replace("%s", "right top"))
        rightTop.assertPixel(75, 2, RED)
        rightTop.assertPixel(25, 2, CLEAR)
        assertSamePixels(rightTop, drawn(body.replace("%s", "top right")))
    }

    @Test
    fun `one transform-origin value leaves the other axis at the centre`() {
        val f =
            drawn("""<rect x="45" y="0" width="10" height="10" fill="#f00" style="transform: rotate(90deg); transform-origin: 50px"/>""")
        // Origin (50, 50), so the square at the top ends up at the far right.
        f.assertPixel(95, 50, RED)
    }

    @Test
    fun `transform-origin can move during the animation`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { from { transform-origin: 0px 0px } to { transform-origin: 100px 100px } }
                    rect { transform: scale(0.5); animation: k 2s linear; fill: #f00 }</style>
                    <rect width="100" height="100"/>""",
                ),
            )
        val halfway = doc.frame(1.0)
        // Scaled by a half about (50, 50): covers 25 to 75.
        halfway.assertPixel(50, 50, RED)
        halfway.assertPixel(10, 10, CLEAR)
        halfway.assertPixel(90, 90, CLEAR)
    }

    @Test
    fun `percentages in translate measure against the reference box`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { from { transform: translate(0, 0) } to { transform: translateX(50%) } }
                    rect { transform-box: fill-box; animation: k 2s linear forwards; fill: #f00 }</style>
                    <rect width="40" height="100"/>""",
                ),
            )
        assertEquals(20.0, doc.frame(2.0).leftEdge(), 0.05)
    }

    @Test
    fun `lists of different shape blend as matrices`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { from { transform: translate(40px, 0) } to { transform: scale(2) rotate(0deg) } }
                    rect { animation: k 2s linear; fill: #f00 }</style><rect width="10" height="10"/>""",
                ),
            )
        // Half way: moved 20, and scaled 1.5.
        val f = doc.frame(1.0)
        assertEquals(20.0, f.leftEdge(5), 0.1)
        assertEquals(35.0, f.rightEdge(5), 0.1)
    }

    @Test
    fun `scale and rotate keyframes with functions in a list`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { from { transform: translate(50px, 50px) scale(1) } to { transform: translate(50px, 50px) scale(3) } }
                    rect { animation: k 2s linear; fill: #f00 }</style><rect x="-5" y="-5" width="10" height="10"/>""",
                ),
            )
        assertEquals(40.0, doc.frame(1.0).leftEdge(), 0.05)
    }

    @Test
    fun `the individual transform properties animate`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { from { translate: 0px 0px } to { translate: 40px 0px } }
                    rect { animation: k 2s linear; fill: #f00 }</style><rect width="10" height="10"/>""",
                ),
            )
        doc.frame(1.0).assertPixel(25, 5, RED)
        val r =
            parse(
                svg(
                    """<style>@keyframes k { from { rotate: 0deg } to { rotate: 180deg } }
                    rect { animation: k 2s linear; transform-origin: 50px 50px; fill: #f00 }</style><rect x="60" y="45" width="30" height="10"/>""",
                ),
            )
        r.frame(1.0).assertPixel(50, 75, RED)
        val s =
            parse(
                svg(
                    """<style>@keyframes k { from { scale: 1 } to { scale: 3 } }
                    rect { animation: k 2s linear; fill: #f00 }</style><rect width="10" height="10"/>""",
                ),
            )
        s.frame(1.0).assertPixel(19, 19, RED)
        s.frame(1.0).assertPixel(21, 21, CLEAR)
    }

    @Test
    fun `a root element animates about its centre`() {
        val doc =
            parse(
                """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">
                <style>@keyframes spin { to { transform: rotate(180deg) } } svg { animation: spin 2s linear forwards }</style>
                <rect width="50" height="50" fill="#f00"/></svg>""",
            )
        doc.frame(2.0).assertPixel(75, 75, RED)
        doc.frame(2.0).assertPixel(25, 25, CLEAR)
    }

    @Test
    fun `a keyframed use element moves its target`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { to { transform: translateX(60px) } } use { animation: k 2s linear }</style>
                    <defs><rect id="a" width="10" height="10" fill="#f00"/></defs><use href="#a"/>""",
                ),
            )
        doc.frame(1.0).assertPixel(35, 5, RED)
    }

    @Test
    fun `a keyframed element inside a symbol animates`() {
        val doc =
            parse(
                svg(
                    """<style>@keyframes k { to { opacity: 0 } } .x { animation: k 2s linear }</style>
                    <symbol id="s"><rect class="x" width="100" height="100"/></symbol><use href="#s"/>""",
                ),
            )
        assertAlpha(doc, 1.0, 0.5)
    }

    @Test
    fun `motion paths in css warn rather than silently doing nothing`() {
        val doc = parse(svg("""<rect style="offset-path: path('M0 0 L10 10')" width="100" height="100"/>"""))
        assertTrue(SvgWarning.UNSUPPORTED_FEATURE_IGNORED in doc.warnings)
    }

    @Test
    fun `a spinner from a fixture loops`() {
        val doc = SvgParser.parse(fixture("spinner-css.svg"))
        assertTrue(doc.animation.isAnimated)
        assertTrue(doc.animation.loopsForever)
        assertEquals(1.2, doc.animation.durationSeconds, 1e-6)
        assertSamePixels(doc.frame(0.0), doc.frame(1.2))
        assertDiffer(doc.frame(0.0), doc.frame(0.3))
    }
}
