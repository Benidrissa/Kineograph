package org.kineograph.media.svg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SvgDurationTest {
    private fun smil(attributes: String): String = """<animate attributeName="opacity" from="0" to="1" $attributes/>"""

    private fun doc(body: String): SvgAnimationInfo = parse(svg("""<rect width="10" height="10">$body</rect>""")).animation

    private fun css(rule: String): SvgAnimationInfo =
        parse(
            svg(
                """<style>@keyframes k { to { opacity: 0 } } $rule</style>
                <rect id="a" class="a" width="10" height="10"/><circle id="b" class="b" r="5"/>""",
            ),
        ).animation

    @Test
    fun `a static picture is not animated`() {
        val info = parse(svg("""<rect width="10" height="10"/>""")).animation
        assertEquals(SvgAnimationInfo.STATIC, info)
    }

    @Test
    fun `one run ends at its begin plus its duration`() {
        val info = doc(smil("""begin="1s" dur="2s""""))
        assertTrue(info.isAnimated)
        assertFalse(info.loopsForever)
        assertEquals(3.0, info.durationSeconds, 1e-9)
    }

    @Test
    fun `a counted repeat ends after its last run`() {
        val info = doc(smil("""dur="2s" repeatCount="3""""))
        assertFalse(info.loopsForever)
        assertEquals(6.0, info.durationSeconds, 1e-9)
    }

    @Test
    fun `a fractional repeat ends part way through a run`() {
        assertEquals(5.0, doc(smil("""dur="2s" repeatCount="2.5"""")).durationSeconds, 1e-9)
    }

    @Test
    fun `repeatDur sets the end`() {
        assertEquals(3.0, doc(smil("""dur="2s" repeatDur="3s"""")).durationSeconds, 1e-9)
    }

    @Test
    fun `an end sets the end`() {
        assertEquals(1.5, doc(smil("""dur="2s" end="1.5s"""")).durationSeconds, 1e-9)
        assertEquals(1.5, doc(smil("""dur="2s" repeatCount="indefinite" end="1.5s"""")).durationSeconds, 1e-9)
        assertFalse(doc(smil("""dur="2s" repeatCount="indefinite" end="1.5s"""")).loopsForever)
    }

    @Test
    fun `the last of several animations sets the end`() {
        assertEquals(4.0, doc(smil("""dur="1s"""") + smil("""begin="3s" dur="1s"""") + smil("""dur="2s"""")).durationSeconds, 1e-9)
    }

    @Test
    fun `several begins end after the last run`() {
        assertEquals(6.0, doc(smil("""begin="1s;5s" dur="1s"""")).durationSeconds, 1e-9)
    }

    @Test
    fun `a chain of syncbases ends after the last link`() {
        val info =
            doc(
                """<animate id="a" attributeName="opacity" to="1" dur="1s"/>
                <animate id="b" attributeName="opacity" to="1" begin="a.end+0.5s" dur="1s"/>""",
            )
        assertEquals(2.5, info.durationSeconds, 1e-9)
    }

    @Test
    fun `a set ends when it happens`() {
        assertEquals(2.0, doc("""<set attributeName="fill" to="red" begin="2s"/>""").durationSeconds, 1e-9)
    }

    @Test
    fun `a click animation counts as animated, with nothing to play`() {
        val info = doc(smil("""begin="click" dur="2s""""))
        assertTrue(info.isAnimated)
        assertEquals(0.0, info.durationSeconds, 1e-9)
        assertFalse(info.loopsForever)
    }

    @Test
    fun `an animation that changes nothing still counts`() {
        val info = doc("""<animate attributeName="opacity" from="1" to="1" dur="2s"/>""")
        assertTrue(info.isAnimated)
        assertEquals(2.0, info.durationSeconds, 1e-9)
    }

    @Test
    fun `a loop lasts one cycle`() {
        val info = doc(smil("""dur="2s" repeatCount="indefinite""""))
        assertTrue(info.loopsForever)
        assertEquals(2.0, info.durationSeconds, 1e-9)
        assertEquals(0.0, info.loopStartSeconds, 1e-9)
    }

    @Test
    fun `loops of different lengths meet at their least common multiple`() {
        val info = doc(smil("""dur="2s" repeatCount="indefinite"""") + smil("""dur="3s" repeatCount="indefinite""""))
        assertEquals(6.0, info.durationSeconds, 1e-9)
    }

    @Test
    fun `the common multiple works with fractions`() {
        val info = doc(smil("""dur="0.1s" repeatCount="indefinite"""") + smil("""dur="0.15s" repeatCount="indefinite""""))
        assertEquals(0.3, info.durationSeconds, 1e-9)
        val longer = doc(smil("""dur="1.5s" repeatCount="indefinite"""") + smil("""dur="2.5s" repeatCount="indefinite""""))
        assertEquals(7.5, longer.durationSeconds, 1e-9)
    }

    @Test
    fun `a rounded third lines up with a whole second`() {
        val info = doc(smil("""dur="0.333s" repeatCount="indefinite"""") + smil("""dur="1s" repeatCount="indefinite""""))
        assertEquals(1.0, info.durationSeconds, 1e-9)
    }

    @Test
    fun `an unreasonably long common multiple falls back to the longest cycle`() {
        val info =
            doc(
                smil("""dur="7s" repeatCount="indefinite"""") + smil("""dur="11s" repeatCount="indefinite"""") +
                    smil("""dur="13s" repeatCount="indefinite""""),
            )
        assertTrue(info.loopsForever)
        assertEquals(13.0, info.durationSeconds, 1e-9)
    }

    @Test
    fun `the limit on a seamless loop comes from the limits`() {
        val body = smil("""dur="7s" repeatCount="indefinite"""") + smil("""dur="11s" repeatCount="indefinite"""")
        val source = svg("""<rect>$body</rect>""")
        assertEquals(
            11.0,
            SvgParser.parse(source, SvgLimits.forDevice().copy(maxSeamlessLoopSeconds = 60.0)).animation.durationSeconds,
            1e-9,
        )
        assertEquals(
            77.0,
            SvgParser.parse(source, SvgLimits.forDevice().copy(maxSeamlessLoopSeconds = 100.0)).animation.durationSeconds,
            1e-9,
        )
    }

    @Test
    fun `a delayed loop says where its repeating part starts`() {
        val info = doc(smil("""begin="1.5s" dur="2s" repeatCount="indefinite""""))
        assertTrue(info.loopsForever)
        assertEquals(2.0, info.durationSeconds, 1e-9)
        assertEquals(1.5, info.loopStartSeconds, 1e-9)
    }

    @Test
    fun `an intro before a loop moves the start of the repeating part`() {
        val info = doc(smil("""dur="5s"""") + smil("""dur="2s" repeatCount="indefinite""""))
        assertTrue(info.loopsForever)
        assertEquals(2.0, info.durationSeconds, 1e-9)
        assertEquals(5.0, info.loopStartSeconds, 1e-9)
    }

    @Test
    fun `sampling from the loop start really is seamless`() {
        val document = parse(svg("""<rect width="100" height="100">${smil("""begin="1.5s" dur="2s" repeatCount="indefinite"""")}</rect>"""))
        val info = document.animation
        assertSamePixels(document.frame(info.loopStartSeconds), document.frame(info.loopStartSeconds + info.durationSeconds))
    }

    @Test
    fun `a negative begin loops from the start`() {
        val info = doc(smil("""begin="-1s" dur="2s" repeatCount="indefinite""""))
        assertEquals(0.0, info.loopStartSeconds, 1e-9)
    }

    @Test
    fun `an indefinite dur with no repeat holds the first value, and that is no loop`() {
        val info = doc("""<animate attributeName="opacity" values="0;1" begin="1s"/>""")
        assertFalse(info.loopsForever)
        assertEquals(1.0, info.durationSeconds, 1e-9)
    }

    @Test
    fun `a chain that loops finds its period`() {
        val info =
            doc(
                """<animate id="a" attributeName="opacity" to="1" begin="0s;b.end" dur="0.5s"/>
                <animate id="b" attributeName="opacity" to="1" begin="a.end+0.25s" dur="1s"/>""",
            )
        assertTrue(info.loopsForever)
        assertEquals(1.75, info.durationSeconds, 1e-6)
    }

    @Test
    fun `a chain's period combines with other loops`() {
        val info =
            doc(
                """<animate id="a" attributeName="opacity" to="1" begin="0s;a.end" dur="1.5s"/>""" +
                    smil("""dur="2s" repeatCount="indefinite""""),
            )
        assertTrue(info.loopsForever)
        assertEquals(6.0, info.durationSeconds, 1e-6)
    }

    @Test
    fun `a chain that adds a gap on itself loops`() {
        val info = doc("""<animate id="a" attributeName="opacity" to="1" begin="0s;a.end+1s" dur="1s"/>""")
        assertTrue(info.loopsForever)
        assertEquals(2.0, info.durationSeconds, 1e-6)
    }

    @Test
    fun `css one run ends after delay and duration`() {
        val info = css(".a { animation: k 2s 1s }")
        assertTrue(info.isAnimated)
        assertFalse(info.loopsForever)
        assertEquals(3.0, info.durationSeconds, 1e-9)
    }

    @Test
    fun `css counted repeats multiply`() {
        assertEquals(6.0, css(".a { animation: k 2s 3 }").durationSeconds, 1e-9)
    }

    @Test
    fun `a negative css delay shortens the run`() {
        assertEquals(1.0, css(".a { animation: k 2s -1s }").durationSeconds, 1e-9)
    }

    @Test
    fun `a css loop lasts one cycle`() {
        val info = css(".a { animation: k 2s infinite }")
        assertTrue(info.loopsForever)
        assertEquals(2.0, info.durationSeconds, 1e-9)
    }

    @Test
    fun `an alternating css loop lasts two runs`() {
        assertEquals(4.0, css(".a { animation: k 2s infinite alternate }").durationSeconds, 1e-9)
        assertEquals(4.0, css(".a { animation: k 2s infinite alternate-reverse }").durationSeconds, 1e-9)
        assertEquals(2.0, css(".a { animation: k 2s infinite reverse }").durationSeconds, 1e-9)
    }

    @Test
    fun `css loops combine with each other`() {
        assertEquals(6.0, css(".a { animation: k 2s infinite } .b { animation: k 3s infinite }").durationSeconds, 1e-9)
        assertEquals(12.0, css(".a { animation: k 2s infinite alternate } .b { animation: k 3s infinite }").durationSeconds, 1e-9)
    }

    @Test
    fun `css and smil loops combine`() {
        val info =
            parse(
                svg(
                    """<style>@keyframes k { to { opacity: 0 } } .a { animation: k 2s infinite }</style>
                    <rect class="a"/><circle r="5">${smil("""dur="3s" repeatCount="indefinite"""")}</circle>""",
                ),
            ).animation
        assertEquals(6.0, info.durationSeconds, 1e-9)
    }

    @Test
    fun `a delayed css loop says where the repeating part starts`() {
        val info = css(".a { animation: k 2s 1s infinite }")
        assertEquals(1.0, info.loopStartSeconds, 1e-9)
        assertEquals(2.0, info.durationSeconds, 1e-9)
    }

    @Test
    fun `a negative css delay loops from the start`() {
        val info = css(".a { animation: k 2s -1s infinite }")
        assertEquals(0.0, info.loopStartSeconds, 1e-9)
    }

    @Test
    fun `a paused css animation does not count towards time`() {
        val info = css(".a { animation: k 2s infinite paused }")
        assertTrue(info.isAnimated)
        assertFalse(info.loopsForever)
        assertEquals(0.0, info.durationSeconds, 1e-9)
    }

    @Test
    fun `a zero duration css animation counts, with nothing to play`() {
        val info = css(".a { animation: k 0s }")
        assertTrue(info.isAnimated)
        assertEquals(0.0, info.durationSeconds, 1e-9)
    }

    @Test
    fun `keyframes nothing uses do not count as animation`() {
        assertFalse(css("").isAnimated)
    }

    @Test
    fun `a looping picture repeats exactly after the suggested time`() {
        val document =
            parse(
                svg(
                    """<rect width="10" height="10" fill="#f00"><animateTransform attributeName="transform" type="translate" from="0 0" to="30 0" dur="2s" repeatCount="indefinite"/></rect>
                    <rect y="20" width="10" height="10" fill="#00f"><animateTransform attributeName="transform" type="translate" from="0 0" to="30 0" dur="3s" repeatCount="indefinite"/></rect>""",
                ),
            )
        val duration = document.animation.durationSeconds
        assertEquals(6.0, duration, 1e-9)
        assertSamePixels(document.frame(0.5), document.frame(0.5 + duration))
        assertDiffer(document.frame(0.5), document.frame(0.5 + 2.0))
    }

    @Test
    fun `the common multiple helper handles a single cycle and rejects going over`() {
        assertEquals(2.5, AnimationSummary.seamlessLoop(listOf(2.5), 60.0), 1e-9)
        assertEquals(90.0, AnimationSummary.seamlessLoop(listOf(90.0), 60.0), 1e-9)
        assertEquals(4.0, AnimationSummary.seamlessLoop(listOf(2.0, 4.0, 1.0), 60.0), 1e-9)
    }
}
