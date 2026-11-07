package org.kineograph.media.svg

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
class SvgCssCascadeTest {
    /** Draws one full-canvas rectangle with the given attributes under the given stylesheet, and returns its colour. */
    private fun colourOf(css: String, attributes: String, wrapper: String = "%s"): Int {
        val rect = """<rect width="100" height="100" $attributes/>"""
        return drawn("<style>$css</style>" + wrapper.replace("%s", rect)).getPixel(50, 50)
    }

    @Test
    fun `a type selector matches by element name`() {
        assertEquals(RED, colourOf("rect { fill: red }", ""))
        assertEquals(BLACK, colourOf("circle { fill: red }", ""))
    }

    @Test
    fun `a class selector matches any listed class`() {
        assertEquals(RED, colourOf(".a { fill: red }", """class="a""""))
        assertEquals(RED, colourOf(".b { fill: red }", """class="a b c""""))
        assertEquals(BLACK, colourOf(".z { fill: red }", """class="a b c""""))
    }

    @Test
    fun `class names are case sensitive`() {
        assertEquals(BLACK, colourOf(".A { fill: red }", """class="a""""))
    }

    @Test
    fun `an id selector matches by id`() {
        assertEquals(RED, colourOf("#x { fill: red }", """id="x""""))
        assertEquals(BLACK, colourOf("#y { fill: red }", """id="x""""))
    }

    @Test
    fun `the universal selector matches everything`() {
        assertEquals(RED, colourOf("* { fill: red }", ""))
    }

    @Test
    fun `compound selectors need every part`() {
        assertEquals(RED, colourOf("rect.a#x { fill: red }", """class="a" id="x""""))
        assertEquals(BLACK, colourOf("circle.a { fill: red }", """class="a""""))
        assertEquals(BLACK, colourOf(".a.b { fill: red }", """class="a""""))
    }

    @Test
    fun `comma lists match any of them`() {
        assertEquals(RED, colourOf("circle, rect { fill: red }", ""))
        assertEquals(RED, colourOf(".nope, .a, .also-nope { fill: red }", """class="a""""))
    }

    @Test
    fun `a selector nobody can match does not spoil the rest of its list`() {
        assertEquals(RED, colourOf("rect:hover, .a { fill: red }", """class="a""""))
        assertEquals(BLACK, colourOf("rect:hover { fill: red }", ""))
    }

    @Test
    fun `descendant combinators look up the whole chain`() {
        val nested = """<g class="o"><g><g>%s</g></g></g>"""
        assertEquals(RED, colourOf(".o rect { fill: red }", "", nested))
        assertEquals(BLACK, colourOf(".z rect { fill: red }", "", nested))
        assertEquals(RED, colourOf("g g g rect { fill: red }", "", nested))
        assertEquals(BLACK, colourOf("g g g g rect { fill: red }", "", nested))
        assertEquals(RED, colourOf("svg .o g rect { fill: red }", "", nested))
    }

    @Test
    fun `descendant matching can skip over a level that also fits`() {
        // `.a .b rect`: the nearest .b has no .a above it only if you stop looking early. The outer .b does.
        val nested = """<g class="a"><g class="b"><g class="a"><g class="b">%s</g></g></g></g>"""
        assertEquals(RED, colourOf(".a .b rect { fill: red }", "", nested))
    }

    @Test
    fun `a child combinator needs the direct parent`() {
        val wrapped = """<g class="o"><g>%s</g></g>"""
        assertEquals(BLACK, colourOf(".o > rect { fill: red }", "", wrapped))
        assertEquals(RED, colourOf(".o > g > rect { fill: red }", "", wrapped))
    }

    @Test
    fun `sibling combinators and structural selectors work`() {
        val siblings =
            """<circle/><rect width="100" height="100"/>"""
        val css = "<style>circle + rect { fill: red }</style>"
        assertEquals(RED, drawn(css + siblings).getPixel(50, 50))
        val nth =
            drawn(
                """<style>rect:nth-child(2) { fill: red } rect:first-child { fill: #00f } rect:last-child { fill: lime }</style>
                <g><rect width="30" height="100"/><rect x="30" width="30" height="100"/><rect x="60" width="40" height="100"/></g>""",
            )
        nth.assertPixel(10, 50, BLUE)
        nth.assertPixel(40, 50, RED)
        nth.assertPixel(80, 50, LIME)
    }

    @Test
    fun `nth-child counts elements that are not drawn, as browsers do`() {
        val f =
            drawn(
                """<style>rect:nth-child(2) { fill: red }</style>
                <g><title>a title comes first</title><rect width="50" height="100" fill="#00f"/><rect x="50" width="50" height="100" fill="#00f"/></g>""",
            )
        f.assertPixel(25, 50, RED)
        f.assertPixel(75, 50, BLUE)
    }

    @Test
    fun `not and attribute selectors work`() {
        assertEquals(RED, colourOf("rect:not(.b) { fill: red }", """class="a""""))
        assertEquals(BLACK, colourOf("rect:not(.a) { fill: red }", """class="a""""))
        assertEquals(RED, colourOf("""rect[data-x="1"] { fill: red }""", """data-x="1""""))
        assertEquals(BLACK, colourOf("""rect[data-x="2"] { fill: red }""", """data-x="1""""))
        assertEquals(RED, colourOf("rect[data-x] { fill: red }", """data-x="1""""))
    }

    @Test
    fun `root selector matches only the outermost element`() {
        assertEquals(RED, colourOf(":root { fill: red }", ""))
        assertEquals(BLACK, colourOf("rect:root { fill: red }", ""))
    }

    @Test
    fun `a class beats a type regardless of order`() {
        assertEquals(RED, colourOf(".a { fill: red } rect { fill: blue }", """class="a""""))
        assertEquals(RED, colourOf("rect { fill: blue } .a { fill: red }", """class="a""""))
    }

    @Test
    fun `an id beats classes however many there are`() {
        assertEquals(RED, colourOf("#x { fill: red } .a.b.c { fill: blue }", """id="x" class="a b c""""))
    }

    @Test
    fun `a longer selector beats a shorter one of the same kind`() {
        assertEquals(RED, colourOf("g rect { fill: red } rect { fill: blue }", "", "<g>%s</g>"))
        assertEquals(RED, colourOf("rect { fill: blue } g rect { fill: red }", "", "<g>%s</g>"))
    }

    @Test
    fun `the later rule wins when specificity ties`() {
        assertEquals(RED, colourOf(".a { fill: blue } .b { fill: red }", """class="a b""""))
        assertEquals(BLUE, colourOf(".b { fill: red } .a { fill: blue }", """class="a b""""))
    }

    @Test
    fun `any rule beats a presentation attribute`() {
        assertEquals(RED, colourOf("* { fill: red }", """fill="blue""""))
    }

    @Test
    fun `the style attribute beats any rule`() {
        assertEquals(RED, colourOf("#x { fill: blue }", """id="x" style="fill: red""""))
    }

    @Test
    fun `important beats the style attribute`() {
        assertEquals(RED, colourOf("rect { fill: red !important }", """style="fill: blue""""))
    }

    @Test
    fun `important beats higher specificity`() {
        assertEquals(RED, colourOf("rect { fill: red !important } #x { fill: blue }", """id="x""""))
    }

    @Test
    fun `specificity decides between two importants`() {
        assertEquals(RED, colourOf("#x { fill: red !important } rect { fill: blue !important }", """id="x""""))
    }

    @Test
    fun `an important style attribute beats an important rule`() {
        assertEquals(RED, colourOf("#x { fill: blue !important }", """id="x" style="fill: red !important""""))
    }

    @Test
    fun `later declarations in one block win`() {
        assertEquals(RED, colourOf("rect { fill: blue; fill: red }", ""))
    }

    @Test
    fun `an invalid value is ignored instead of erasing what came before`() {
        assertEquals(RED, colourOf("rect { fill: red; fill: notacolour }", ""))
        assertEquals(RED, colourOf("rect { fill: notacolour }", """fill="red""""))
    }

    @Test
    fun `invalid values get a warning`() {
        assertTrue(SvgWarning.INVALID_CONTENT_IGNORED in parse(svg("""<rect fill="notacolour"/>""")).warnings)
    }

    @Test
    fun `fill inherits, and a child can override it`() {
        val f = drawn("""<g fill="red"><rect width="50" height="100"/><rect x="50" width="50" height="100" fill="#00f"/></g>""")
        f.assertPixel(25, 50, RED)
        f.assertPixel(75, 50, BLUE)
    }

    @Test
    fun `css set on a parent inherits too`() {
        val f = drawn("""<style>g { fill: red }</style><g><rect width="100" height="100"/></g>""")
        f.assertPixel(50, 50, RED)
    }

    @Test
    fun `stroke properties inherit`() {
        val f = drawn("""<g stroke="red" stroke-width="20"><line x1="0" y1="50" x2="100" y2="50"/></g>""")
        f.assertPixel(50, 45, RED)
    }

    @Test
    fun `opacity does not inherit, it multiplies by compositing`() {
        val f = drawn("""<g opacity="0.5"><rect width="100" height="100" fill="red"/></g>""")
        f.assertPixelNear(50, 50, 0x80FF0000.toInt())
        val g = drawn("""<g opacity="0.5"><rect width="100" height="100" fill="red" opacity="0.5"/></g>""")
        g.assertPixelNear(50, 50, 0x40FF0000)
    }

    @Test
    fun `display does not inherit`() {
        // A child of a display:inline group stays visible though another group is none.
        val f = drawn("""<g display="inline"><rect width="100" height="100" fill="red"/></g>""")
        f.assertPixel(50, 50, RED)
    }

    @Test
    fun `transform does not inherit`() {
        val f = drawn("""<style>g { transform: translate(50px, 0) }</style><g><rect width="10" height="10" fill="red"/></g>""")
        f.assertPixel(55, 5, RED)
        f.assertPixel(5, 5, CLEAR)
    }

    @Test
    fun `the inherit keyword takes the parent's value even over a rule`() {
        val f = drawn("""<style>rect { fill: inherit }</style><g fill="red"><rect width="100" height="100" fill="#00f"/></g>""")
        f.assertPixel(50, 50, RED)
    }

    @Test
    fun `the initial keyword resets an inherited property`() {
        val f = drawn("""<g fill="red"><rect width="100" height="100" style="fill: initial"/></g>""")
        f.assertPixel(50, 50, BLACK)
    }

    @Test
    fun `unset inherits for an inherited property`() {
        val f = drawn("""<g fill="red"><rect width="100" height="100" fill="#00f" style="fill: unset"/></g>""")
        f.assertPixel(50, 50, RED)
    }

    @Test
    fun `opacity inherit takes the parent's own value`() {
        val f = drawn("""<g opacity="0.5"><rect width="100" height="100" fill="red" style="opacity: inherit"/></g>""")
        f.assertPixelNear(50, 50, 0x40FF0000)
    }

    @Test
    fun `css lengths and transforms in stylesheets are honoured`() {
        val f = drawn("""<style>rect { stroke: red; stroke-width: 10px; fill: none }</style><rect x="20" y="20" width="60" height="60"/>""")
        f.assertPixel(16, 50, RED)
    }

    @Test
    fun `geometry can be set from css`() {
        val f = drawn("""<style>circle { cx: 50px; cy: 50px; r: 20px; fill: red }</style><circle/>""")
        f.assertPixel(50, 50, RED)
        f.assertPixel(50, 25, CLEAR)
        val g = drawn("""<style>rect { width: 50px; height: 50px; x: 10px; fill: red }</style><rect/>""")
        g.assertPixel(30, 30, RED)
        g.assertPixel(5, 30, CLEAR)
    }

    @Test
    fun `a path can come from the css d property`() {
        val f = drawn("""<style>path { d: path("M0 0 H100 V100 H0 Z"); fill: red }</style><path/>""")
        f.assertPixel(50, 50, RED)
    }

    @Test
    fun `attributes that are not properties cannot be set from css`() {
        val f = drawn("""<style>polygon { points: 0,0 100,0 100,100; fill: red }</style><polygon points="0,0 10,0 10,10"/>""")
        f.assertPixel(50, 25, CLEAR)
    }

    @Test
    fun `comments and cdata and markup comments do not get in the way`() {
        val f =
            drawn(
                """<style><![CDATA[
                    /* a comment with a brace } in it */
                    rect > .nothing { fill: blue }
                    rect { /* inner */ fill: /* mid */ red /* end */; }
                ]]></style><rect width="100" height="100"/>""",
            )
        f.assertPixel(50, 50, RED)
    }

    @Test
    fun `broken css keeps what can be saved`() {
        val f = drawn("""<style>@@@ ; rect { fill: red } .a { fill</style><rect width="100" height="100"/>""")
        f.assertPixel(50, 50, RED)
    }

    @Test
    fun `css that never closes still applies`() {
        val f = drawn("""<style>rect { fill: red</style><rect width="100" height="100"/>""")
        f.assertPixel(50, 50, RED)
    }

    @Test
    fun `a style element with another type is left alone`() {
        val f = drawn("""<style type="text/less">rect { fill: red }</style><rect width="100" height="100"/>""")
        f.assertPixel(50, 50, BLACK)
    }

    @Test
    fun `media queries are answered for an everyday screen`() {
        val f =
            drawn(
                """<style>
                @media screen { rect { fill: red } }
                @media print { rect { fill: blue } }
                @media (prefers-color-scheme: dark) { rect { fill: lime } }
                </style><rect width="100" height="100"/>""",
            )
        f.assertPixel(50, 50, RED)
    }

    @Test
    fun `an import is never fetched`() {
        val doc =
            parse(svg("""<style>@import url("https://example.org/a.css"); rect { fill: red }</style><rect width="100" height="100"/>"""))
        assertTrue(SvgWarning.EXTERNAL_REFERENCE_IGNORED in doc.warnings)
        doc.frame().assertPixel(50, 50, RED)
    }

    @Test
    fun `custom properties resolve with var`() {
        val f =
            drawn(
                """<style>:root { --main: red } g { --other: #00f } .c { fill: var(--missing, var(--main)) } .d { fill: var(--other) }</style>
                <rect class="c" width="50" height="100"/><g><rect class="d" x="50" width="50" height="100"/></g>""",
            )
        f.assertPixel(25, 50, RED)
        f.assertPixel(75, 50, BLUE)
    }

    @Test
    fun `a var with no value voids the declaration`() {
        val f = drawn("""<style>rect { fill: var(--nope) }</style><rect width="100" height="100" fill="red"/>""")
        f.assertPixel(50, 50, RED)
    }

    @Test
    fun `selectors match the file, not where use places a copy`() {
        val f =
            drawn(
                """<style>g rect { fill: red }</style>
                <defs><rect id="a" width="100" height="100"/></defs>
                <g><use href="#a"/></g>""",
            )
        // The rectangle is not inside the group in the file, so the rule never applied to it.
        f.assertPixel(50, 50, BLACK)
    }

    @Test
    fun `vendor prefixes count for the properties that used them`() {
        val f = drawn("""<rect width="10" height="10" fill="red" style="-webkit-transform: translate(50px, 0)"/>""")
        f.assertPixel(55, 5, RED)
    }
}
