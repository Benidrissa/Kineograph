package org.kineograph.media.svg

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Most tests animate something simple and read a number back. A full-canvas rectangle's opacity gives a value to within 1/255,
 * and a shape's edge gives a position to a fraction of a pixel through its antialiasing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SvgSmilTest {
    /** The opacity of a full-canvas rectangle with this animation of its opacity inside. The base opacity is 1. */
    private fun opacityProbe(animation: String, base: String = "1"): SvgDocument =
        parse(svg("""<rect width="100" height="100" opacity="$base">$animation</rect>"""))

    private fun assertAlpha(doc: SvgDocument, time: Double, expected: Double) {
        assertEquals("at t=$time", expected, doc.frame(time).centreAlpha(), 0.006)
    }

    /** The x position of a bar 200 wide, whose left edge is the probe, under this animation of x. */
    private fun xProbe(animation: String, baseX: String = "0"): SvgDocument =
        parse(svg("""<rect x="$baseX" y="0" width="200" height="100" fill="#f00">$animation</rect>"""))

    private fun assertX(doc: SvgDocument, time: Double, expected: Double) {
        assertEquals("at t=$time", expected, doc.frame(time).leftEdge(), 0.02)
    }

    @Test
    fun `from and to interpolate linearly`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" dur="2s" fill="freeze"/>""")
        assertAlpha(doc, 0.0, 0.0)
        assertAlpha(doc, 0.5, 0.25)
        assertAlpha(doc, 1.0, 0.5)
        assertAlpha(doc, 1.5, 0.75)
    }

    @Test
    fun `fill remove returns to the base value after the end`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="0.5" dur="1s"/>""", base = "0.8")
        assertAlpha(doc, 0.5, 0.25)
        assertAlpha(doc, 1.5, 0.8)
    }

    @Test
    fun `fill freeze holds the end value`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="0.5" dur="1s" fill="freeze"/>""", base = "0.8")
        assertAlpha(doc, 1.0, 0.5)
        assertAlpha(doc, 100.0, 0.5)
    }

    @Test
    fun `the value before begin is the base value`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" begin="2s" dur="1s"/>""", base = "0.3")
        assertAlpha(doc, 1.0, 0.3)
        assertAlpha(doc, 2.5, 0.5)
    }

    @Test
    fun `values walk through a list in equal parts`() {
        val doc = opacityProbe("""<animate attributeName="opacity" values="0;1;0.5" dur="2s" fill="freeze"/>""")
        assertAlpha(doc, 0.5, 0.5)
        assertAlpha(doc, 1.0, 1.0)
        assertAlpha(doc, 1.5, 0.75)
        assertAlpha(doc, 2.0, 0.5)
    }

    @Test
    fun `keyTimes place the values`() {
        val doc = opacityProbe("""<animate attributeName="opacity" values="0;1;0.5" keyTimes="0;0.25;1" dur="4s" fill="freeze"/>""")
        assertAlpha(doc, 0.5, 0.5)
        assertAlpha(doc, 1.0, 1.0)
        assertAlpha(doc, 2.5, 0.75)
    }

    @Test
    fun `a trailing semicolon in values is forgiven`() {
        val doc = opacityProbe("""<animate attributeName="opacity" values="0;1;" dur="2s"/>""")
        assertAlpha(doc, 1.0, 0.5)
    }

    @Test
    fun `discrete mode holds each value for its share`() {
        val doc = opacityProbe("""<animate attributeName="opacity" values="0;0.5;1" calcMode="discrete" dur="3s" fill="freeze"/>""")
        assertAlpha(doc, 0.5, 0.0)
        assertAlpha(doc, 1.5, 0.5)
        assertAlpha(doc, 2.5, 1.0)
        assertAlpha(doc, 3.0, 1.0)
    }

    @Test
    fun `discrete mode follows keyTimes`() {
        val doc = opacityProbe("""<animate attributeName="opacity" values="0;0.5;1" keyTimes="0;0.2;0.9" calcMode="discrete" dur="10s"/>""")
        assertAlpha(doc, 1.0, 0.0)
        assertAlpha(doc, 3.0, 0.5)
        assertAlpha(doc, 9.5, 1.0)
    }

    @Test
    fun `paced mode keeps constant speed through uneven values`() {
        val doc = opacityProbe("""<animate attributeName="opacity" values="0;0.1;1" calcMode="paced" dur="10s"/>""")
        assertAlpha(doc, 1.0, 0.1)
        assertAlpha(doc, 5.5, 0.55)
        // Linear mode would be at 0.1 half way through.
        val linear = opacityProbe("""<animate attributeName="opacity" values="0;0.1;1" dur="10s"/>""")
        assertAlpha(linear, 5.0, 0.1)
    }

    @Test
    fun `paced mode ignores keyTimes`() {
        val doc = opacityProbe("""<animate attributeName="opacity" values="0;1" keyTimes="0;0.5" calcMode="paced" dur="10s"/>""")
        assertAlpha(doc, 5.0, 0.5)
    }

    @Test
    fun `spline mode eases each segment`() {
        val doc =
            opacityProbe(
                """<animate attributeName="opacity" values="0;1" keyTimes="0;1" keySplines="0.42 0 0.58 1" calcMode="spline" dur="1s"/>""",
            )
        assertAlpha(doc, 0.25, 0.1292)
        assertAlpha(doc, 0.5, 0.5)
    }

    @Test
    fun `spline segments each have their own curve`() {
        val doc =
            opacityProbe(
                """<animate attributeName="opacity" values="0;0.5;1" keyTimes="0;0.5;1" keySplines="0 0 1 1; .42 0 .58 1" calcMode="spline" dur="2s"/>""",
            )
        assertAlpha(doc, 0.5, 0.25)
        assertAlpha(doc, 1.25, 0.5 + 0.5 * 0.1292)
    }

    @Test
    fun `invalid keyTimes switch the animation off, with a warning`() {
        val doc = opacityProbe("""<animate attributeName="opacity" values="0;1;0" keyTimes="0;1" dur="2s"/>""", base = "0.3")
        assertAlpha(doc, 1.0, 0.3)
        assertTrue(SvgWarning.INVALID_CONTENT_IGNORED in doc.warnings)
        assertTrue(doc.animation.isAnimated)
    }

    @Test
    fun `invalid keySplines switch the animation off`() {
        val doc =
            opacityProbe(
                """<animate attributeName="opacity" values="0;1" keySplines="0 0 2 1" calcMode="spline" dur="2s"/>""",
                base = "0.3",
            )
        assertAlpha(doc, 1.0, 0.3)
    }

    @Test
    fun `begin takes clock values with units`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" begin="500ms" dur="1000ms"/>""", base = "0.3")
        assertAlpha(doc, 0.25, 0.3)
        assertAlpha(doc, 1.0, 0.5)
    }

    @Test
    fun `dur takes clock values`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" dur="00:02"/>""")
        assertAlpha(doc, 1.0, 0.5)
    }

    @Test
    fun `begin offset lists start it again`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" begin="1s;3s" dur="1s"/>""", base = "0.3")
        assertAlpha(doc, 0.5, 0.3)
        assertAlpha(doc, 1.5, 0.5)
        assertAlpha(doc, 2.5, 0.3)
        assertAlpha(doc, 3.25, 0.25)
    }

    @Test
    fun `a negative begin starts part way through`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" begin="-1s" dur="2s"/>""")
        assertAlpha(doc, 0.0, 0.5)
        assertAlpha(doc, 0.5, 0.75)
    }

    @Test
    fun `a syncbase on end starts after the other one ends`() {
        val doc =
            parse(
                svg(
                    """<rect width="100" height="100" opacity="0.3">
                        <animate id="a" attributeName="fill-opacity" from="1" to="1" begin="1s" dur="1s"/>
                        <animate attributeName="opacity" from="0" to="1" begin="a.end+0.5s" dur="1s"/>
                    </rect>""",
                ),
            )
        assertAlpha(doc, 2.4, 0.3)
        assertAlpha(doc, 3.0, 0.5)
    }

    @Test
    fun `a syncbase on begin follows the other one's start`() {
        val doc =
            parse(
                svg(
                    """<rect width="100" height="100" opacity="0.3">
                        <animate id="a" attributeName="fill-opacity" from="1" to="1" begin="1s" dur="5s"/>
                        <animate attributeName="opacity" from="0" to="1" begin="a.begin+250ms" dur="1s"/>
                    </rect>""",
                ),
            )
        assertAlpha(doc, 1.2, 0.3)
        assertAlpha(doc, 1.75, 0.5)
    }

    @Test
    fun `a negative syncbase offset starts early`() {
        val doc =
            parse(
                svg(
                    """<rect width="100" height="100" opacity="0.3">
                        <animate id="a" attributeName="fill-opacity" from="1" to="1" begin="1s" dur="2s"/>
                        <animate attributeName="opacity" from="0" to="1" begin="a.end - 1s" dur="1s"/>
                    </rect>""",
                ),
            )
        assertAlpha(doc, 2.5, 0.5)
    }

    @Test
    fun `an id with a dash or an escaped dot still works in a syncbase`() {
        val doc =
            parse(
                svg(
                    """<rect width="100" height="100" opacity="0.3">
                        <animate id="a-b.c" attributeName="fill-opacity" from="1" to="1" dur="1s"/>
                        <animate attributeName="opacity" from="0" to="1" begin="a-b\.c.end" dur="1s"/>
                    </rect>""",
                ),
            )
        assertAlpha(doc, 1.5, 0.5)
    }

    @Test
    fun `a repeat syncbase fires when the other one repeats`() {
        val doc =
            parse(
                svg(
                    """<rect width="100" height="100" opacity="0.3">
                        <animate id="a" attributeName="fill-opacity" from="1" to="1" dur="1s" repeatCount="5"/>
                        <animate attributeName="opacity" from="0" to="1" begin="a.repeat(2)" dur="1s"/>
                    </rect>""",
                ),
            )
        assertAlpha(doc, 1.5, 0.3)
        assertAlpha(doc, 2.5, 0.5)
    }

    @Test
    fun `endEvent counts as a syncbase since no one has to do anything`() {
        val doc =
            parse(
                svg(
                    """<rect width="100" height="100" opacity="0.3">
                        <animate id="a" attributeName="fill-opacity" from="1" to="1" dur="1s"/>
                        <animate attributeName="opacity" from="0" to="1" begin="a.endEvent" dur="1s"/>
                    </rect>""",
                ),
            )
        assertAlpha(doc, 1.5, 0.5)
        assertTrue(SvgWarning.EVENT_TRIGGER_IGNORED !in doc.warnings)
    }

    @Test
    fun `a chain of syncbases resolves in any document order`() {
        val doc =
            parse(
                svg(
                    """<rect width="100" height="100" opacity="0.3">
                        <animate attributeName="opacity" from="0" to="1" begin="b.end" dur="1s"/>
                        <animate id="b" attributeName="fill-opacity" from="1" to="1" begin="a.end" dur="1s"/>
                        <animate id="a" attributeName="fill-opacity" from="1" to="1" dur="1s"/>
                    </rect>""",
                ),
            )
        assertAlpha(doc, 1.5, 0.3)
        assertAlpha(doc, 2.5, 0.5)
        assertEquals(3.0, doc.animation.durationSeconds, 1e-9)
    }

    private val pingPong =
        """<rect width="100" height="100" opacity="0.3">
            <animate id="a" attributeName="opacity" from="0" to="0.5" begin="0s;b.end" dur="1s"/>
            <animate id="b" attributeName="opacity" from="1" to="0.5" begin="a.end" dur="1s"/>
        </rect>"""

    @Test
    fun `two elements that restart each other loop for ever`() {
        val doc = parse(svg(pingPong))
        assertAlpha(doc, 0.5, 0.25)
        assertAlpha(doc, 1.5, 0.75)
        assertAlpha(doc, 2.5, 0.25)
        assertAlpha(doc, 3.5, 0.75)
        assertTrue(doc.animation.loopsForever)
        assertEquals(2.0, doc.animation.durationSeconds, 1e-6)
        assertEquals(0.0, doc.animation.loopStartSeconds, 1e-6)
    }

    @Test
    fun `a loop whose second element freezes only starts to repeat once that has run`() {
        val doc =
            parse(
                svg(
                    """<rect width="100" height="100" opacity="0.3">
                        <animate id="a" attributeName="fill-opacity" from="1" to="1" begin="0s;b.end" dur="1s"/>
                        <animate id="b" attributeName="opacity" from="1" to="0.5" begin="a.end" dur="1s" fill="freeze"/>
                    </rect>""",
                ),
            )
        assertEquals(2.0, doc.animation.durationSeconds, 1e-6)
        assertEquals(1.0, doc.animation.loopStartSeconds, 1e-6)
        // At t=0.5 it has not run yet, and by t=2.5 it holds its end value.
        assertAlpha(doc, 0.5, 0.3)
        assertAlpha(doc, 2.5, 0.5)
        assertAlpha(doc, 4.5, 0.5)
    }

    @Test
    fun `a long list of fixed times is not mistaken for a loop`() {
        val times = (0 until 500).joinToString(";") { "${it}s" }
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" begin="$times" dur="0.5s"/>""", base = "0.3")
        assertTrue(!doc.animation.loopsForever)
        assertEquals(499.5, doc.animation.durationSeconds, 1e-6)
        assertAlpha(doc, 300.25, 0.5)
        assertAlpha(doc, 300.75, 0.3)
        assertAlpha(doc, 600.25, 0.3)
    }

    @Test
    fun `such a loop gives the same answer an hour in`() {
        val doc = parse(svg(pingPong))
        assertAlpha(doc, 3600.5, 0.25)
        assertAlpha(doc, 3601.5, 0.75)
        assertAlpha(doc, 7201.5, 0.75)
    }

    @Test
    fun `end stops it early`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" end="1s" dur="4s"/>""", base = "0.3")
        assertAlpha(doc, 0.5, 0.125)
        assertAlpha(doc, 1.5, 0.3)
    }

    @Test
    fun `freezing at an early end holds the value reached`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" end="1s" dur="4s" fill="freeze"/>""")
        assertAlpha(doc, 2.0, 0.25)
    }

    @Test
    fun `end with no dur runs to the end at the first value`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0.5" to="1" end="1s"/>""", base = "0.3")
        assertAlpha(doc, 0.5, 0.5)
        assertAlpha(doc, 1.5, 0.3)
    }

    @Test
    fun `end lists pair up with begin lists`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" begin="0s;2s" end="1s;3s" dur="10s"/>""", base = "0.3")
        assertAlpha(doc, 0.5, 0.05)
        assertAlpha(doc, 1.5, 0.3)
        assertAlpha(doc, 2.5, 0.05)
        assertAlpha(doc, 3.5, 0.3)
    }

    @Test
    fun `an end that is a syncbase works`() {
        val doc =
            parse(
                svg(
                    """<rect width="100" height="100" opacity="0.3">
                        <animate id="a" attributeName="fill-opacity" from="1" to="1" dur="1s"/>
                        <animate attributeName="opacity" from="0" to="1" end="a.end" dur="4s"/>
                    </rect>""",
                ),
            )
        assertAlpha(doc, 0.5, 0.125)
        assertAlpha(doc, 1.5, 0.3)
    }

    @Test
    fun `an end before the begin means the element never runs`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" begin="2s" end="1s" dur="1s"/>""", base = "0.3")
        assertAlpha(doc, 2.5, 0.3)
    }

    @Test
    fun `end indefinite changes nothing`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" end="indefinite" dur="2s"/>""")
        assertAlpha(doc, 1.0, 0.5)
        assertTrue(SvgWarning.EVENT_TRIGGER_IGNORED !in doc.warnings)
    }

    @Test
    fun `repeatCount repeats`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" dur="1s" repeatCount="2"/>""", base = "0.3")
        assertAlpha(doc, 0.25, 0.25)
        assertAlpha(doc, 1.25, 0.25)
        assertAlpha(doc, 2.25, 0.3)
    }

    @Test
    fun `repeatCount may be fractional, and freezing then holds the fraction`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" dur="1s" repeatCount="2.5" fill="freeze"/>""")
        assertAlpha(doc, 2.25, 0.25)
        assertAlpha(doc, 3.0, 0.5)
    }

    @Test
    fun `freezing on an exact repeat boundary holds the end value, not the start`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0.2" to="0.8" dur="1s" repeatCount="2" fill="freeze"/>""")
        assertAlpha(doc, 2.0, 0.8)
        assertAlpha(doc, 9.0, 0.8)
    }

    @Test
    fun `repeatCount indefinite never stops`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" dur="1s" repeatCount="indefinite"/>""")
        assertAlpha(doc, 0.25, 0.25)
        assertAlpha(doc, 100.75, 0.75)
    }

    @Test
    fun `repeatDur repeats for a length of time`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" dur="1s" repeatDur="1.5s" fill="freeze"/>""")
        assertAlpha(doc, 1.25, 0.25)
        assertAlpha(doc, 2.0, 0.5)
    }

    @Test
    fun `repeatDur indefinite never stops`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" dur="1s" repeatDur="indefinite"/>""")
        assertAlpha(doc, 50.25, 0.25)
    }

    @Test
    fun `the shorter of repeatCount and repeatDur wins`() {
        val doc =
            opacityProbe("""<animate attributeName="opacity" from="0" to="1" dur="1s" repeatCount="5" repeatDur="1.5s" fill="freeze"/>""")
        assertAlpha(doc, 3.0, 0.5)
    }

    @Test
    fun `end stops an indefinite repeat`() {
        val doc =
            opacityProbe(
                """<animate attributeName="opacity" from="0" to="1" dur="1s" repeatCount="indefinite" end="2.5s" fill="freeze"/>""",
            )
        assertAlpha(doc, 1.25, 0.25)
        assertAlpha(doc, 5.0, 0.5)
    }

    @Test
    fun `an invalid dur means an indefinite one`() {
        val zero = opacityProbe("""<animate attributeName="opacity" from="0.5" to="1" dur="0s"/>""", base = "0.3")
        assertAlpha(zero, 10.0, 0.5)
        val nonsense = opacityProbe("""<animate attributeName="opacity" from="0.5" to="1" dur="soon"/>""", base = "0.3")
        assertAlpha(nonsense, 10.0, 0.5)
    }

    @Test
    fun `min stretches the active time, showing the fill`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="0.5" dur="1s" min="3s" fill="freeze"/>""", base = "0.3")
        assertAlpha(doc, 2.0, 0.5)
        val removed = opacityProbe("""<animate attributeName="opacity" from="0" to="0.5" dur="1s" min="3s"/>""", base = "0.3")
        assertAlpha(removed, 2.0, 0.3)
    }

    @Test
    fun `max cuts the active time`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" dur="4s" max="1s" fill="freeze"/>""")
        assertAlpha(doc, 3.0, 0.25)
    }

    @Test
    fun `restart always starts again in the middle`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" begin="0s;0.5s" dur="1s"/>""")
        assertAlpha(doc, 0.75, 0.25)
    }

    @Test
    fun `restart whenNotActive waits for the end`() {
        val doc =
            opacityProbe(
                """<animate attributeName="opacity" from="0" to="1" begin="0s;0.5s;3s" dur="1s" restart="whenNotActive"/>""",
                base = "0.3",
            )
        assertAlpha(doc, 0.75, 0.75)
        assertAlpha(doc, 2.0, 0.3)
        assertAlpha(doc, 3.5, 0.5)
    }

    @Test
    fun `restart never runs it once`() {
        val doc =
            opacityProbe("""<animate attributeName="opacity" from="0" to="1" begin="0s;2s" dur="1s" restart="never"/>""", base = "0.3")
        assertAlpha(doc, 0.5, 0.5)
        assertAlpha(doc, 2.5, 0.3)
    }

    @Test
    fun `a click begin never starts, with a warning`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" begin="click" dur="1s"/>""", base = "0.3")
        assertAlpha(doc, 0.5, 0.3)
        assertAlpha(doc, 50.0, 0.3)
        assertTrue(SvgWarning.EVENT_TRIGGER_IGNORED in doc.warnings)
        assertTrue(doc.animation.isAnimated)
    }

    @Test
    fun `other event forms never start either`() {
        for (begin in listOf("rect1.click", "click+1s", "mouseover", "accessKey(a)", "indefinite", "a.mouseover-1s")) {
            val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" begin="$begin" dur="1s"/>""", base = "0.3")
            assertAlpha(doc, 0.5, 0.3)
            assertTrue(begin, SvgWarning.EVENT_TRIGGER_IGNORED in doc.warnings)
        }
    }

    @Test
    fun `an event beside a time still lets the time work`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" begin="1s;click" dur="1s"/>""", base = "0.3")
        assertAlpha(doc, 1.5, 0.5)
        assertTrue(SvgWarning.EVENT_TRIGGER_IGNORED in doc.warnings)
    }

    @Test
    fun `an element waiting on a click also waits through a syncbase`() {
        val doc =
            parse(
                svg(
                    """<rect width="100" height="100" opacity="0.3">
                        <animate id="a" attributeName="fill-opacity" from="1" to="1" begin="click" dur="1s"/>
                        <animate attributeName="opacity" from="0" to="1" begin="a.end" dur="1s"/>
                    </rect>""",
                ),
            )
        assertAlpha(doc, 0.5, 0.3)
        assertAlpha(doc, 1.5, 0.3)
        assertEquals(0.0, doc.animation.durationSeconds, 1e-9)
    }

    @Test
    fun `an event end does not stop anything`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" end="click" dur="2s"/>""")
        assertAlpha(doc, 1.0, 0.5)
        assertTrue(SvgWarning.EVENT_TRIGGER_IGNORED in doc.warnings)
    }

    @Test
    fun `a syncbase to nothing never starts`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" begin="nothing.end" dur="1s"/>""", base = "0.3")
        assertAlpha(doc, 0.5, 0.3)
    }

    @Test
    fun `by counts from zero and adds to the base`() {
        val doc = xProbe("""<animate attributeName="x" by="20" dur="1s" fill="freeze"/>""", baseX = "10")
        assertX(doc, 0.5, 20.0)
        assertX(doc, 1.0, 30.0)
    }

    @Test
    fun `from and by go from one to their sum`() {
        val doc = xProbe("""<animate attributeName="x" from="5" by="10" dur="1s" fill="freeze"/>""", baseX = "40")
        assertX(doc, 0.5, 10.0)
        assertX(doc, 1.0, 15.0)
    }

    @Test
    fun `a to animation starts from the base`() {
        val doc = xProbe("""<animate attributeName="x" to="30" dur="1s" fill="freeze"/>""", baseX = "10")
        assertX(doc, 0.5, 20.0)
        assertX(doc, 1.0, 30.0)
    }

    @Test
    fun `a to animation starts from what is inherited`() {
        val doc =
            parse(
                svg(
                    """<g fill-opacity="0.2"><rect width="100" height="100"><animate attributeName="fill-opacity" to="0.8" dur="1s"/></rect></g>""",
                ),
            )
        assertAlpha(doc, 0.5, 0.5)
    }

    @Test
    fun `a to animation starts from the initial value when nothing else is set`() {
        val doc = opacityProbe("""<animate attributeName="fill-opacity" to="0" dur="1s"/>""")
        assertAlpha(doc, 0.5, 0.5)
    }

    @Test
    fun `a discrete to animation holds the value all the way`() {
        val doc = opacityProbe("""<animate attributeName="opacity" to="0.5" dur="2s" calcMode="discrete"/>""", base = "0.2")
        assertAlpha(doc, 0.5, 0.5)
    }

    @Test
    fun `additive sum adds to the base, and replace does not`() {
        val replace = xProbe("""<animate attributeName="x" from="0" to="20" dur="1s"/>""", baseX = "10")
        assertX(replace, 0.5, 10.0)
        val sum = xProbe("""<animate attributeName="x" from="0" to="20" additive="sum" dur="1s"/>""", baseX = "10")
        assertX(sum, 0.5, 20.0)
    }

    @Test
    fun `additive animations stack`() {
        val doc =
            xProbe(
                """<animate attributeName="x" from="0" to="10" additive="sum" dur="1s"/>
                <animate attributeName="x" from="0" to="20" additive="sum" dur="1s"/>""",
                baseX = "5",
            )
        assertX(doc, 0.5, 20.0)
    }

    @Test
    fun `a later animation on the same property replaces an earlier one`() {
        val doc =
            xProbe(
                """<animate attributeName="x" from="0" to="10" dur="2s"/>
                <animate attributeName="x" from="50" to="60" dur="2s"/>""",
            )
        assertX(doc, 1.0, 55.0)
    }

    @Test
    fun `a later start outranks earlier document order`() {
        val doc =
            xProbe(
                """<animate attributeName="x" from="50" to="60" begin="1s" dur="2s"/>
                <animate attributeName="x" from="0" to="10" dur="4s"/>""",
            )
        assertX(doc, 0.5, 1.25)
        assertX(doc, 2.0, 55.0)
    }

    @Test
    fun `accumulate builds on each repeat`() {
        val doc = xProbe("""<animate attributeName="x" from="0" to="10" dur="1s" repeatCount="3" accumulate="sum" fill="freeze"/>""")
        assertX(doc, 0.5, 5.0)
        assertX(doc, 1.5, 15.0)
        assertX(doc, 2.5, 25.0)
        assertX(doc, 4.0, 30.0)
    }

    @Test
    fun `accumulate has no effect on a to animation`() {
        val doc = xProbe("""<animate attributeName="x" to="10" dur="1s" repeatCount="3" accumulate="sum"/>""")
        assertX(doc, 1.5, 5.0)
    }

    @Test
    fun `an animation can target through href`() {
        val doc =
            parse(
                svg(
                    """<rect id="r" width="100" height="100" opacity="0.3"/>
                    <animate href="#r" attributeName="opacity" from="0" to="1" dur="1s"/>""",
                ),
            )
        assertAlpha(doc, 0.5, 0.5)
    }

    @Test
    fun `an animation can target through xlink href`() {
        val doc =
            parse(
                svg(
                    """<animate xlink:href="#r" attributeName="opacity" from="0" to="1" dur="1s"/>
                    <rect id="r" width="100" height="100" opacity="0.3"/>""",
                ),
            )
        assertAlpha(doc, 0.5, 0.5)
    }

    @Test
    fun `an animation aimed at nothing does nothing`() {
        val doc =
            parse(
                svg("""<rect width="100" height="100" opacity="0.3"/><animate href="#nowhere" attributeName="opacity" to="1" dur="1s"/>"""),
            )
        assertAlpha(doc, 0.5, 0.3)
        assertTrue(doc.animation.isAnimated)
    }

    @Test
    fun `an animation of a referenced element shows through use`() {
        val doc =
            parse(
                svg(
                    """<defs><rect id="r" width="100" height="100" opacity="0.3"><animate attributeName="opacity" from="0" to="1" dur="1s"/></rect></defs>
                    <use href="#r"/>""",
                ),
            )
        assertAlpha(doc, 0.5, 0.5)
    }

    @Test
    fun `an attribute that does not exist warns and does nothing`() {
        val doc = opacityProbe("""<animate attributeName="bogus" from="0" to="1" dur="1s"/>""")
        assertAlpha(doc, 0.5, 1.0)
        assertTrue(SvgWarning.UNSUPPORTED_FEATURE_IGNORED in doc.warnings)
    }

    @Test
    fun `unusable values warn and do nothing`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="zero" to="1" dur="1s"/>""", base = "0.3")
        assertAlpha(doc, 0.5, 0.3)
        assertTrue(SvgWarning.INVALID_CONTENT_IGNORED in doc.warnings)
    }

    @Test
    fun `lengths in units interpolate`() {
        val doc = xProbe("""<animate attributeName="x" from="0in" to="0.5in" dur="1s"/>""")
        assertX(doc, 0.5, 24.0)
    }

    @Test
    fun `lengths in different units interpolate through their parts`() {
        // Half way from 20% of 100 to 40px, by parts: 10% + 20px.
        val doc = xProbe("""<animate attributeName="x" from="20%" to="40px" dur="1s"/>""")
        assertX(doc, 0.5, 30.0)
    }

    @Test
    fun `percent lengths interpolate`() {
        val doc =
            parse(
                svg(
                    """<rect y="0" height="100" fill="red" width="0"><animate attributeName="width" from="0%" to="100%" dur="1s"/></rect>""",
                ),
            )
        assertEquals(50.0, doc.frame(0.5).rightEdge(), 0.02)
    }

    @Test
    fun `colours interpolate channel by channel`() {
        val doc =
            parse(svg("""<rect width="100" height="100"><animate attributeName="fill" from="#ff0000" to="#0000ff" dur="1s"/></rect>"""))
        doc.frame(0.5).assertPixelNear(50, 50, 0xFF800080.toInt())
        doc.frame(0.25).assertPixelNear(50, 50, 0xFFBF0040.toInt())
    }

    @Test
    fun `named colours and currentColor interpolate`() {
        val doc =
            parse(
                svg(
                    """<rect width="100" height="100" color="#00f"><animate attributeName="fill" from="red" to="currentColor" dur="1s"/></rect>""",
                ),
            )
        doc.frame(0.5).assertPixelNear(50, 50, 0xFF800080.toInt())
    }

    @Test
    fun `an animated color drives currentColor`() {
        val doc =
            parse(
                svg(
                    """<rect width="100" height="100" fill="currentColor"><animate attributeName="color" from="#f00" to="#00f" dur="1s"/></rect>""",
                ),
            )
        doc.frame(0.5).assertPixelNear(50, 50, 0xFF800080.toInt())
    }

    @Test
    fun `a fill to a gradient flips half way`() {
        val doc =
            parse(
                svg(
                    """<defs><linearGradient id="g"><stop stop-color="#00f"/></linearGradient></defs>
                    <rect width="100" height="100"><animate attributeName="fill" from="#f00" to="url(#g)" dur="1s"/></rect>""",
                ),
            )
        doc.frame(0.25).assertPixel(50, 50, RED)
        doc.frame(0.75).assertPixel(50, 50, BLUE)
    }

    @Test
    fun `stop colours animate`() {
        val doc =
            parse(
                svg(
                    """<defs><linearGradient id="g"><stop offset="0" stop-color="#f00"><animate attributeName="stop-color" to="#00f" dur="1s" fill="freeze"/></stop><stop offset="1" stop-color="#00f"/></linearGradient></defs>
                    <rect width="100" height="100" fill="url(#g)"/>""",
                ),
            )
        doc.frame(1.0).assertPixel(2, 50, BLUE)
    }

    @Test
    fun `gradient geometry animates`() {
        val doc =
            parse(
                svg(
                    """<defs><linearGradient id="g" gradientUnits="userSpaceOnUse" x1="0" x2="100">
                        <stop offset="0" stop-color="#000"/><stop offset="1" stop-color="#00f"/>
                        <animate attributeName="x2" from="100" to="50" dur="1s" fill="freeze"/>
                    </linearGradient></defs><rect width="100" height="100" fill="url(#g)"/>""",
                ),
            )
        assertEquals(0.5, Color.blue(doc.frame(0.0).getPixel(50, 50)) / 255.0, 0.03)
        assertEquals(1.0, Color.blue(doc.frame(1.0).getPixel(75, 50)) / 255.0, 0.01)
    }

    @Test
    fun `points interpolate`() {
        val doc =
            parse(
                svg(
                    """<polygon points="0,0 10,0 10,100 0,100" fill="red">
                        <animate attributeName="points" to="0,0 50,0 50,100 0,100" dur="1s" fill="freeze"/>
                    </polygon>""",
                ),
            )
        assertEquals(30.0, doc.frame(0.5).rightEdge(), 0.05)
        assertEquals(50.0, doc.frame(1.0).rightEdge(), 0.05)
    }

    @Test
    fun `points of different counts flip half way`() {
        val doc =
            parse(
                svg(
                    """<polygon points="0,0 10,0 10,100 0,100" fill="red">
                        <animate attributeName="points" to="0,0 50,0 50,100" dur="1s"/>
                    </polygon>""",
                ),
            )
        assertEquals(10.0, doc.frame(0.25).rightEdge(), 0.05)
        assertEquals(50.0, doc.frame(0.75).rightEdge(5), 3.0)
    }

    @Test
    fun `path d interpolates when the commands match`() {
        val doc =
            parse(
                svg(
                    """<path d="M0 0 H10 V100 H0 Z" fill="red">
                        <animate attributeName="d" to="M0 0 H50 V100 H0 Z" dur="1s" fill="freeze"/>
                    </path>""",
                ),
            )
        assertEquals(30.0, doc.frame(0.5).rightEdge(), 0.05)
        assertEquals(50.0, doc.frame(1.0).rightEdge(), 0.05)
    }

    @Test
    fun `path d interpolates between absolute and relative forms`() {
        val doc =
            parse(
                svg(
                    """<path d="M0 0 H10 V100 H0 Z" fill="red">
                        <animate attributeName="d" values="M0 0 H10 V100 H0 Z;m0 0 h50 v100 h-50 z" dur="1s"/>
                    </path>""",
                ),
            )
        assertEquals(30.0, doc.frame(0.5).rightEdge(), 0.05)
    }

    @Test
    fun `curves and arcs in paths interpolate`() {
        val doc =
            parse(
                svg(
                    """<path fill="red" d="M0 50 A10 10 0 0 1 20 50 Z">
                        <animate attributeName="d" to="M0 50 A40 40 0 0 1 80 50 Z" dur="1s" fill="freeze"/>
                    </path>""",
                ),
            )
        assertEquals(80.0, doc.frame(1.0).rightEdge(49), 0.5)
    }

    @Test
    fun `path d with different commands flips half way`() {
        val doc =
            parse(
                svg(
                    """<path d="M0 0 H10 V100 H0 Z" fill="red">
                        <animate attributeName="d" to="M0 0 L50 0 L50 100 L0 100 Z" dur="1s"/>
                    </path>""",
                ),
            )
        assertEquals(10.0, doc.frame(0.25).rightEdge(), 0.05)
        assertEquals(50.0, doc.frame(0.75).rightEdge(), 0.05)
    }

    @Test
    fun `stroke dasharray interpolates`() {
        val doc =
            parse(
                svg(
                    """<line x1="0" y1="50" x2="100" y2="50" stroke="red" stroke-width="10" stroke-dasharray="0 100">
                        <animate attributeName="stroke-dasharray" to="100 100" dur="1s" fill="freeze"/>
                    </line>""",
                ),
            )
        assertEquals(50.0, doc.frame(0.5).rightEdge(), 0.05)
        assertEquals(100.0, doc.frame(1.0).rightEdge(), 0.05)
    }

    @Test
    fun `dash lists of different sizes repeat until they line up`() {
        val doc =
            parse(
                svg(
                    """<line x1="0" y1="50" x2="100" y2="50" stroke="red" stroke-width="10">
                        <animate attributeName="stroke-dasharray" from="10 10" to="20 20 20 20" dur="1s"/>
                    </line>""",
                ),
            )
        doc.frame(0.5).assertPixel(7, 50, RED)
        doc.frame(0.5).assertPixel(22, 50, CLEAR)
    }

    @Test
    fun `stroke dashoffset animates`() {
        val doc =
            parse(
                svg(
                    """<line x1="0" y1="50" x2="100" y2="50" stroke="red" stroke-width="10" stroke-dasharray="100" stroke-dashoffset="100">
                        <animate attributeName="stroke-dashoffset" to="0" dur="1s" fill="freeze"/>
                    </line>""",
                ),
            )
        assertEquals(0, doc.frame(0.0).paintedPixels())
        assertEquals(50.0, doc.frame(0.5).rightEdge(), 0.05)
        assertEquals(100.0, doc.frame(1.0).rightEdge(), 0.05)
    }

    @Test
    fun `stroke width animates`() {
        val doc =
            parse(
                svg(
                    """<line x1="0" y1="50" x2="100" y2="50" stroke="red" stroke-width="0">
                        <animate attributeName="stroke-width" to="40" dur="1s"/>
                    </line>""",
                ),
            )
        assertEquals(40.0, doc.frame(0.5).topEdge(), 0.05)
    }

    @Test
    fun `display switches discretely`() {
        val doc =
            parse(
                svg(
                    """<rect width="100" height="100" fill="red"><animate attributeName="display" values="none;inline" dur="2s"/></rect>""",
                ),
            )
        assertEquals(0, doc.frame(0.5).paintedPixels())
        doc.frame(1.5).assertPixel(50, 50, RED)
    }

    @Test
    fun `visibility switches discretely, even with a linear calcMode`() {
        val doc =
            parse(
                svg(
                    """<rect width="100" height="100" fill="red"><animate attributeName="visibility" from="hidden" to="visible" dur="2s"/></rect>""",
                ),
            )
        assertEquals(0, doc.frame(0.5).paintedPixels())
        doc.frame(1.5).assertPixel(50, 50, RED)
    }

    @Test
    fun `discrete values can use keyTimes`() {
        val doc =
            parse(
                svg(
                    """<rect width="100" height="100" fill="red">
                        <animate attributeName="visibility" values="hidden;visible;hidden" keyTimes="0;0.5;0.6" dur="10s"/>
                    </rect>""",
                ),
            )
        assertEquals(0, doc.frame(4.0).paintedPixels())
        doc.frame(5.5).assertPixel(50, 50, RED)
        assertEquals(0, doc.frame(7.0).paintedPixels())
    }

    @Test
    fun `set applies a value at its begin, for its duration`() {
        val doc =
            parse(svg("""<rect width="100" height="100" fill="#f00"><set attributeName="fill" to="#00f" begin="1s" dur="1s"/></rect>"""))
        doc.frame(0.5).assertPixel(50, 50, RED)
        doc.frame(1.5).assertPixel(50, 50, BLUE)
        doc.frame(2.5).assertPixel(50, 50, RED)
    }

    @Test
    fun `set with no dur holds until it is told otherwise`() {
        val doc = parse(svg("""<rect width="100" height="100" fill="#f00"><set attributeName="fill" to="#00f" begin="1s"/></rect>"""))
        doc.frame(50.0).assertPixel(50, 50, BLUE)
        assertEquals(1.0, doc.animation.durationSeconds, 1e-9)
    }

    @Test
    fun `set can freeze`() {
        val doc =
            parse(svg("""<rect width="100" height="100" fill="#f00"><set attributeName="fill" to="#00f" dur="1s" fill="freeze"/></rect>"""))
        doc.frame(5.0).assertPixel(50, 50, BLUE)
    }

    @Test
    fun `set works on a length`() {
        val doc = xProbe("""<set attributeName="x" to="40" begin="1s"/>""")
        assertX(doc, 0.5, 0.0)
        assertX(doc, 1.5, 40.0)
    }

    @Test
    fun `discard removes an element for good`() {
        val doc = parse(svg("""<rect width="100" height="100" fill="#f00"><discard begin="1s"/></rect>"""))
        doc.frame(0.5).assertPixel(50, 50, RED)
        assertEquals(0, doc.frame(1.5).paintedPixels())
    }

    @Test
    fun `viewBox animates`() {
        val doc =
            parse(
                """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" viewBox="0 0 100 100">
                    <animate attributeName="viewBox" to="0 0 50 50" dur="1s" fill="freeze"/>
                    <rect width="50" height="50" fill="red"/></svg>""",
            )
        assertEquals(50.0, doc.frame(0.0).rightEdge(25), 0.05)
        doc.frame(1.0).assertPixel(90, 90, RED)
    }

    @Test
    fun `the root's opacity animates`() {
        val doc =
            parse(
                """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><animate attributeName="opacity" from="0" to="1" dur="1s"/>
                    <rect width="100" height="100"/></svg>""",
            )
        assertAlpha(doc, 0.5, 0.5)
    }

    @Test
    fun `important static values beat animations`() {
        val doc =
            parse(
                svg(
                    """<style>rect { opacity: 0.3 !important }</style>
                    <rect width="100" height="100"><animate attributeName="opacity" from="0" to="1" dur="1s"/></rect>""",
                ),
            )
        assertAlpha(doc, 0.5, 0.3)
    }

    @Test
    fun `animations beat plain style rules and style attributes`() {
        val doc =
            parse(
                svg(
                    """<style>rect { opacity: 0.3 }</style>
                    <rect width="100" height="100" style="opacity: 0.2"><animate attributeName="opacity" from="0" to="1" dur="1s"/></rect>""",
                ),
            )
        assertAlpha(doc, 0.5, 0.5)
    }

    // animateTransform

    private fun transformProbe(animation: String, base: String = ""): SvgDocument =
        parse(svg("""<rect width="10" height="10" fill="#f00" transform="$base">$animation</rect>"""))

    @Test
    fun `animateTransform translate moves`() {
        val doc =
            transformProbe(
                """<animateTransform attributeName="transform" type="translate" from="0 0" to="40 60" dur="1s" fill="freeze"/>""",
            )
        doc.frame(0.5).assertPixel(25, 35, RED)
        doc.frame(1.0).assertPixel(45, 65, RED)
        doc.frame(1.0).assertPixel(5, 5, CLEAR)
    }

    @Test
    fun `animateTransform translate with one number moves along x`() {
        val doc = transformProbe("""<animateTransform attributeName="transform" type="translate" to="40" dur="1s" fill="freeze"/>""")
        doc.frame(1.0).assertPixel(45, 5, RED)
    }

    @Test
    fun `animateTransform scale`() {
        val doc = transformProbe("""<animateTransform attributeName="transform" type="scale" from="1" to="5" dur="1s" fill="freeze"/>""")
        doc.frame(0.5).assertPixel(29, 29, RED)
        doc.frame(0.5).assertPixel(31, 31, CLEAR)
        doc.frame(1.0).assertPixel(49, 49, RED)
    }

    @Test
    fun `animateTransform scale with two numbers`() {
        val doc =
            transformProbe("""<animateTransform attributeName="transform" type="scale" from="1 1" to="5 2" dur="1s" fill="freeze"/>""")
        doc.frame(1.0).assertPixel(49, 19, RED)
        doc.frame(1.0).assertPixel(49, 21, CLEAR)
    }

    @Test
    fun `animateTransform rotate turns about the origin`() {
        val doc =
            parse(
                svg(
                    """<rect x="20" y="-5" width="40" height="10" fill="#f00">
                        <animateTransform attributeName="transform" type="rotate" from="0" to="90" dur="1s" fill="freeze"/>
                    </rect>""",
                ),
            )
        doc.frame(1.0).assertPixel(0, 40, RED)
        doc.frame(0.0).assertPixel(40, 0, RED)
    }

    @Test
    fun `animateTransform rotate turns about a centre`() {
        val doc =
            parse(
                svg(
                    """<rect x="60" y="45" width="30" height="10" fill="#f00">
                        <animateTransform attributeName="transform" type="rotate" from="0 50 50" to="360 50 50" dur="4s" repeatCount="indefinite"/>
                    </rect>""",
                ),
            )
        doc.frame(0.0).assertPixel(75, 50, RED)
        doc.frame(1.0).assertPixel(50, 75, RED)
        doc.frame(2.0).assertPixel(25, 50, RED)
        doc.frame(3.0).assertPixel(50, 25, RED)
        doc.frame(4.0).assertPixel(75, 50, RED)
    }

    @Test
    fun `animateTransform rotate centre can move too`() {
        val doc =
            transformProbe(
                """<animateTransform attributeName="transform" type="rotate" from="90 10 10" to="90 50 50" dur="1s" fill="freeze"/>""",
            )
        // Centre at (50, 50) by the end. The square (0..10)² maps to x in 90..100, y in 0..10.
        doc.frame(1.0).assertPixel(95, 5, RED)
    }

    @Test
    fun `animateTransform skewX`() {
        val doc =
            parse(
                svg(
                    """<rect width="20" height="100" fill="#f00">
                        <animateTransform attributeName="transform" type="skewX" from="0" to="45" dur="1s" fill="freeze"/>
                    </rect>""",
                ),
            )
        doc.frame(1.0).assertPixel(80, 75, RED)
        doc.frame(1.0).assertPixel(10, 75, CLEAR)
    }

    @Test
    fun `animateTransform skewY`() {
        val doc =
            parse(
                svg(
                    """<rect width="100" height="20" fill="#f00">
                        <animateTransform attributeName="transform" type="skewY" from="0" to="45" dur="1s" fill="freeze"/>
                    </rect>""",
                ),
            )
        doc.frame(1.0).assertPixel(75, 80, RED)
        doc.frame(1.0).assertPixel(75, 10, CLEAR)
    }

    @Test
    fun `animateTransform values walk a list`() {
        val doc =
            transformProbe(
                """<animateTransform attributeName="transform" type="translate" values="0 0;50 0;50 50" dur="2s" fill="freeze"/>""",
            )
        doc.frame(0.5).assertPixel(30, 5, RED)
        doc.frame(1.0).assertPixel(55, 5, RED)
        doc.frame(1.5).assertPixel(55, 30, RED)
    }

    @Test
    fun `animateTransform replaces the base transform by default`() {
        val doc =
            transformProbe(
                """<animateTransform attributeName="transform" type="translate" from="0 0" to="10 0" dur="1s" fill="freeze"/>""",
                base = "translate(50 50)",
            )
        doc.frame(1.0).assertPixel(15, 5, RED)
        doc.frame(1.0).assertPixel(65, 55, CLEAR)
    }

    @Test
    fun `additive sum applies after the base transform`() {
        val doc =
            transformProbe(
                """<animateTransform attributeName="transform" type="translate" from="0 0" to="10 0" dur="1s" fill="freeze" additive="sum"/>""",
                base = "translate(50 50)",
            )
        doc.frame(1.0).assertPixel(65, 55, RED)
    }

    @Test
    fun `additive transforms multiply in the order they stack`() {
        val doc =
            parse(
                svg(
                    """<rect width="10" height="10" fill="#f00">
                        <animateTransform attributeName="transform" type="translate" from="50 50" to="50 50" dur="1s" additive="sum" fill="freeze"/>
                        <animateTransform attributeName="transform" type="scale" from="2" to="2" dur="1s" additive="sum" fill="freeze"/>
                    </rect>""",
                ),
            )
        doc.frame(0.5).assertPixel(65, 65, RED)
        doc.frame(0.5).assertPixel(45, 45, CLEAR)
    }

    @Test
    fun `by on animateTransform adds to the base`() {
        val doc =
            transformProbe(
                """<animateTransform attributeName="transform" type="translate" by="20 0" dur="1s" fill="freeze"/>""",
                base = "translate(50 0)",
            )
        doc.frame(1.0).assertPixel(75, 5, RED)
    }

    @Test
    fun `accumulate on animateTransform translate walks on`() {
        val doc =
            transformProbe(
                """<animateTransform attributeName="transform" type="translate" from="0 0" to="20 0" dur="1s" repeatCount="3" accumulate="sum" fill="freeze"/>""",
            )
        doc.frame(1.5).assertPixel(35, 5, RED)
        doc.frame(3.0).assertPixel(65, 5, RED)
    }

    @Test
    fun `accumulate on animateTransform rotate adds angles`() {
        val doc =
            parse(
                svg(
                    """<rect x="60" y="45" width="30" height="10" fill="#f00" transform="translate(50 50) translate(-50 -50)">
                        <animateTransform attributeName="transform" type="rotate" from="0" to="45" dur="1s" repeatCount="3" accumulate="sum" fill="freeze"/>
                    </rect>""",
                ),
            )
        // Three runs of 45 degrees about the origin make 135, which puts the bar's middle, (75, 50), at about (-88, 18) and off canvas.
        val end = doc.frame(3.0)
        assertEquals(0, end.paintedPixels())
        // After the first run and a third of the way into the second, it is at 60 degrees: (75, 50) goes to about (-5.8, 90).
        val second = doc.frame(4.0 / 3.0)
        assertTrue(second.paintedPixels() > 0)
    }

    @Test
    fun `an unknown animateTransform type warns`() {
        val doc = transformProbe("""<animateTransform attributeName="transform" type="wiggle" from="0" to="1" dur="1s"/>""")
        assertTrue(SvgWarning.INVALID_CONTENT_IGNORED in doc.warnings)
        doc.frame(0.5).assertPixel(5, 5, RED)
    }

    @Test
    fun `animate on the transform attribute interpolates lists`() {
        val doc = transformProbe("""<animate attributeName="transform" from="translate(0 0)" to="translate(40 0)" dur="1s"/>""")
        doc.frame(0.5).assertPixel(25, 5, RED)
    }

    @Test
    fun `paced animateTransform spaces by distance`() {
        val doc =
            transformProbe(
                """<animateTransform attributeName="transform" type="translate" values="0 0;10 0;100 0" calcMode="paced" dur="10s"/>""",
            )
        doc.frame(5.0).assertPixel(55, 5, RED)
    }

    @Test
    fun `gradientTransform animates`() {
        val doc =
            parse(
                svg(
                    """<defs><linearGradient id="g" gradientUnits="userSpaceOnUse" x1="0" x2="100">
                        <stop offset="0" stop-color="#000"/><stop offset="1" stop-color="#00f"/>
                        <animateTransform attributeName="gradientTransform" type="translate" from="0 0" to="50 0" dur="1s" fill="freeze"/>
                    </linearGradient></defs><rect width="100" height="100" fill="url(#g)"/>""",
                ),
            )
        assertEquals(0.5, Color.blue(doc.frame(0.0).getPixel(50, 50)) / 255.0, 0.03)
        assertEquals(0.0, Color.blue(doc.frame(1.0).getPixel(50, 50)) / 255.0, 0.03)
    }

    // animateMotion. The moving thing is a square centred on the origin, located by the centre of its pixels.

    private val dot = """<rect x="-5" y="-5" width="10" height="10" fill="#f00">"""

    private fun assertDot(doc: SvgDocument, time: Double, x: Double, y: Double) {
        val f = doc.frame(time)
        var sumX = 0.0
        var sumY = 0.0
        var total = 0.0
        for (py in 0 until f.height) {
            for (px in 0 until f.width) {
                val alpha = Color.alpha(f.getPixel(px, py)) / 255.0
                sumX += alpha * (px + 0.5)
                sumY += alpha * (py + 0.5)
                total += alpha
            }
        }
        assertEquals("x at t=$time", x, sumX / total, 0.1)
        assertEquals("y at t=$time", y, sumY / total, 0.1)
    }

    @Test
    fun `animateMotion follows a path`() {
        val doc = parse(svg("""$dot<animateMotion path="M10 50 L90 50" dur="2s" fill="freeze"/></rect>"""))
        assertDot(doc, 0.0, 10.0, 50.0)
        assertDot(doc, 1.0, 50.0, 50.0)
        assertDot(doc, 2.0, 90.0, 50.0)
        assertDot(doc, 5.0, 90.0, 50.0)
    }

    @Test
    fun `animateMotion is paced along uneven segments by default`() {
        val doc = parse(svg("""$dot<animateMotion path="M10 10 L20 10 L20 90" dur="9s"/></rect>"""))
        // Ten then eighty long: at a third of the time it has gone 30, so 20 down the second segment.
        assertDot(doc, 3.0, 20.0, 30.0)
    }

    @Test
    fun `calcMode linear gives each segment an equal share of time`() {
        val doc = parse(svg("""$dot<animateMotion path="M10 10 L20 10 L20 90" dur="2s" calcMode="linear"/></rect>"""))
        assertDot(doc, 0.5, 15.0, 10.0)
        assertDot(doc, 1.0, 20.0, 10.0)
        assertDot(doc, 1.5, 20.0, 50.0)
    }

    @Test
    fun `calcMode linear follows keyTimes for segments`() {
        val doc = parse(svg("""$dot<animateMotion path="M10 10 L20 10 L20 90" keyTimes="0;0.5;1" dur="2s" calcMode="linear"/></rect>"""))
        assertDot(doc, 1.5, 20.0, 50.0)
        val skewed =
            parse(svg("""$dot<animateMotion path="M10 10 L20 10 L20 90" keyTimes="0;0.25;1" dur="4s" calcMode="linear"/></rect>"""))
        assertDot(skewed, 0.5, 15.0, 10.0)
    }

    @Test
    fun `calcMode discrete jumps from vertex to vertex`() {
        val doc = parse(svg("""$dot<animateMotion path="M10 10 L50 10 L50 90" dur="3s" calcMode="discrete" fill="freeze"/></rect>"""))
        assertDot(doc, 0.5, 10.0, 10.0)
        assertDot(doc, 1.5, 50.0, 10.0)
        assertDot(doc, 2.5, 50.0, 90.0)
    }

    @Test
    fun `calcMode spline eases the walk`() {
        val doc =
            parse(
                svg(
                    """$dot<animateMotion path="M0 50 L100 50" dur="1s" calcMode="spline" keySplines="0.42 0 0.58 1" keyTimes="0;1"/></rect>""",
                ),
            )
        // The square is partly off the canvas at this point, so its visible centre says less than its right edge does.
        assertEquals(12.92 + 5.0, doc.frame(0.25).rightEdge(), 0.05)
    }

    @Test
    fun `mpath names the path`() {
        val doc =
            parse(
                svg(
                    """<defs><path id="p" d="M10 50 L90 50"/></defs>
                    $dot<animateMotion dur="2s"><mpath href="#p"/></animateMotion></rect>""",
                ),
            )
        assertDot(doc, 1.0, 50.0, 50.0)
    }

    @Test
    fun `mpath reads the old xlink form`() {
        val doc =
            parse(
                svg(
                    """<defs><path id="p" d="M10 50 L90 50"/></defs>
                    $dot<animateMotion dur="2s"><mpath xlink:href="#p"/></animateMotion></rect>""",
                ),
            )
        assertDot(doc, 1.0, 50.0, 50.0)
    }

    @Test
    fun `mpath wins over a path attribute`() {
        val doc =
            parse(
                svg(
                    """<defs><path id="p" d="M10 50 L90 50"/></defs>
                    $dot<animateMotion path="M10 10 L90 10" dur="2s"><mpath href="#p"/></animateMotion></rect>""",
                ),
            )
        assertDot(doc, 1.0, 50.0, 50.0)
    }

    @Test
    fun `keyPoints set the place along the path at each keyTime`() {
        val doc =
            parse(
                svg(
                    """$dot<animateMotion path="M10 50 L90 50" keyPoints="0;1;0.5" keyTimes="0;0.25;1" dur="4s" calcMode="linear"/></rect>""",
                ),
            )
        assertDot(doc, 0.5, 50.0, 50.0)
        assertDot(doc, 1.0, 90.0, 50.0)
        assertDot(doc, 2.5, 70.0, 50.0)
    }

    @Test
    fun `keyPoints count distance along the whole path`() {
        val doc =
            parse(
                svg(
                    """$dot<animateMotion path="M20 50 L50 50 L50 80" keyPoints="0.5;1" keyTimes="0;1" dur="1s" calcMode="linear" fill="freeze"/></rect>""",
                ),
            )
        assertDot(doc, 0.0, 50.0, 50.0)
    }

    @Test
    fun `keyPoints work with the default paced mode too`() {
        val doc =
            parse(
                svg(
                    """$dot<animateMotion path="M10 50 L90 50" keyPoints="0;1" keyTimes="0;1" dur="2s"/></rect>""",
                ),
            )
        assertDot(doc, 1.0, 50.0, 50.0)
    }

    @Test
    fun `rotate auto faces along the path`() {
        val doc =
            parse(
                svg(
                    """<rect x="0" y="-2" width="40" height="4" fill="#f00"><animateMotion path="M50 10 L50 90" dur="2s" rotate="auto"/></rect>""",
                ),
            )
        // A bar pointing along +x at rest. Heading down, it points down from the moving point.
        val f = doc.frame(0.0)
        f.assertPixel(50, 30, RED)
        f.assertPixel(70, 10, CLEAR)
    }

    @Test
    fun `rotate auto-reverse faces backwards along the path`() {
        val doc =
            parse(
                svg(
                    """<rect x="0" y="-2" width="40" height="4" fill="#f00"><animateMotion path="M50 50 L50 90" dur="2s" rotate="auto-reverse"/></rect>""",
                ),
            )
        doc.frame(0.0).assertPixel(50, 30, RED)
    }

    @Test
    fun `rotate with a number holds that angle`() {
        val doc =
            parse(
                svg(
                    """<rect x="0" y="-2" width="40" height="4" fill="#f00"><animateMotion path="M10 50 L90 50" dur="2s" rotate="90"/></rect>""",
                ),
            )
        doc.frame(0.0).assertPixel(10, 70, RED)
    }

    @Test
    fun `rotate auto turns with a curve`() {
        val doc =
            parse(
                svg(
                    """<rect x="0" y="-2" width="20" height="4" fill="#f00"><animateMotion path="M20 50 A30 30 0 0 1 80 50" dur="2s" rotate="auto"/></rect>""",
                ),
            )
        // Half way along the half circle, at the very top, heading right.
        doc.frame(1.0).assertPixel(60, 20, RED)
    }

    @Test
    fun `motion stacks on top of the element's own transform`() {
        val doc =
            parse(
                svg(
                    """<rect x="-5" y="-5" width="10" height="10" fill="#f00" transform="translate(10 0) scale(2)">
                        <animateMotion path="M50 50 L90 50" dur="2s"/></rect>""",
                ),
            )
        // Moves by (50, 50) first, and the element's own translate and scale apply inside that.
        assertDot(doc, 0.0, 60.0, 50.0)
    }

    @Test
    fun `animateMotion values give the points to pass through`() {
        val doc = parse(svg("""$dot<animateMotion values="10,50; 90,50" dur="2s"/></rect>"""))
        assertDot(doc, 1.0, 50.0, 50.0)
    }

    @Test
    fun `animateMotion from and to make a straight line`() {
        val doc = parse(svg("""$dot<animateMotion from="10,50" to="90,50" dur="2s"/></rect>"""))
        assertDot(doc, 1.0, 50.0, 50.0)
    }

    @Test
    fun `animateMotion by starts from where the element sits`() {
        val doc = parse(svg("""$dot<animateMotion by="40,20" dur="2s" fill="freeze"/></rect>"""))
        assertDot(doc, 1.0, 20.0, 10.0)
    }

    @Test
    fun `animateMotion on a group moves the group`() {
        val doc = parse(svg("""<g>$dot</rect><animateMotion path="M10 50 L90 50" dur="2s"/></g>"""))
        assertDot(doc, 1.0, 50.0, 50.0)
    }

    @Test
    fun `animateMotion accumulate walks on`() {
        val doc = parse(svg("""$dot<animateMotion path="M0 0 L20 20" dur="1s" repeatCount="3" accumulate="sum" fill="freeze"/></rect>"""))
        assertDot(doc, 1.5, 30.0, 30.0)
    }

    @Test
    fun `a path of separate parts walks across them`() {
        val doc = parse(svg("""$dot<animateMotion path="M10 10 L30 10 M10 50 L30 50" dur="4s"/></rect>"""))
        assertDot(doc, 1.0, 20.0, 10.0)
        assertDot(doc, 3.0, 20.0, 50.0)
    }

    @Test
    fun `a motion path of one point just sits there`() {
        val doc = parse(svg("""$dot<animateMotion path="M40 40" dur="2s"/></rect>"""))
        assertDot(doc, 1.0, 40.0, 40.0)
    }

    @Test
    fun `an animateMotion with nothing to follow warns and does nothing`() {
        val doc = parse(svg("""$dot<animateMotion dur="2s"/></rect>"""))
        assertTrue(SvgWarning.INVALID_CONTENT_IGNORED in doc.warnings)
    }

    @Test
    fun `animation in a clip path moves it`() {
        val doc =
            parse(
                svg(
                    """<defs><clipPath id="c"><rect width="50" height="100"><animate attributeName="x" from="0" to="50" dur="1s" fill="freeze"/></rect></clipPath></defs>
                    <rect width="100" height="100" fill="#f00" clip-path="url(#c)"/>""",
                ),
            )
        doc.frame(0.0).assertPixel(25, 50, RED)
        doc.frame(1.0).assertPixel(25, 50, CLEAR)
        doc.frame(1.0).assertPixel(75, 50, RED)
    }

    @Test
    fun `animation in a mask moves it`() {
        val doc =
            parse(
                svg(
                    """<defs><mask id="m" maskUnits="userSpaceOnUse" x="0" y="0" width="100" height="100"><rect width="50" height="100" fill="#fff"><animate attributeName="x" from="0" to="50" dur="1s" fill="freeze"/></rect></mask></defs>
                    <rect width="100" height="100" fill="#f00" mask="url(#m)"/>""",
                ),
            )
        doc.frame(0.0).assertPixel(25, 50, RED)
        doc.frame(1.0).assertPixel(25, 50, CLEAR)
        doc.frame(1.0).assertPixel(75, 50, RED)
    }

    @Test
    fun `any order of frames gives the same pictures`() {
        val doc =
            parse(
                svg(
                    """<rect width="10" height="10" fill="#f00"><animateTransform attributeName="transform" type="rotate" from="0 50 50" to="360 50 50" dur="3s" repeatCount="indefinite"/>
                    <animate attributeName="fill" values="#f00;#00f;#f00" dur="2s" repeatCount="indefinite"/></rect>""",
                ),
            )
        val forwards = listOf(0.0, 0.7, 1.4, 2.9).map { doc.frame(it) }
        val backwards = listOf(2.9, 1.4, 0.7, 0.0).map { doc.frame(it) }.reversed()
        for (i in forwards.indices) assertSamePixels(forwards[i], backwards[i])
        assertDiffer(forwards[0], forwards[1])
    }

    @Test
    fun `the same time twice gives the same picture`() {
        val doc = SvgParser.parse(fixture("bounce-smil.svg"))
        assertSamePixels(doc.frame(1.234), doc.frame(1.234))
    }

    @Test
    fun `a time out of range is handled`() {
        val doc = opacityProbe("""<animate attributeName="opacity" from="0" to="1" dur="1s"/>""")
        doc.frame(Double.NaN)
        doc.frame(-5.0)
        doc.frame(Double.POSITIVE_INFINITY)
        doc.frame(Double.MAX_VALUE)
    }

    @Test
    fun `a drawing from a fixture plays`() {
        val doc = SvgParser.parse(fixture("bounce-smil.svg"))
        assertTrue(doc.animation.isAnimated)
        assertTrue(doc.animation.loopsForever)
        assertEquals(2.0, doc.animation.durationSeconds, 1e-6)

        fun ballY(time: Double): Double {
            val f = doc.frame(time)
            var sum = 0.0
            var count = 0
            for (y in 0 until f.height) {
                for (x in 0 until f.width) {
                    if (f.getPixel(x, y) == RED) {
                        sum += y
                        count++
                    }
                }
            }
            return sum / count
        }
        // The ball falls through the first half, then rises back.
        assertTrue(ballY(1.0) > ballY(0.0) + 30)
        assertEquals(ballY(0.0), ballY(2.0), 0.5)
    }
}
