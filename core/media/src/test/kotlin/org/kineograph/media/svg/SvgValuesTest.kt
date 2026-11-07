package org.kineograph.media.svg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class SvgValuesTest {
    private fun px(text: String): Double = checkNotNull(parseLengthOrNull(text)) { "could not read $text" }.px

    @Test
    fun `absolute units convert at 96 pixels to the inch`() {
        assertEquals(10.0, px("10"), 1e-9)
        assertEquals(10.0, px("10px"), 1e-9)
        assertEquals(96.0, px("1in"), 1e-9)
        assertEquals(96.0 / 2.54, px("1cm"), 1e-9)
        assertEquals(96.0 / 25.4, px("1mm"), 1e-9)
        assertEquals(96.0 / 72.0, px("1pt"), 1e-9)
        assertEquals(16.0, px("1pc"), 1e-9)
    }

    @Test
    fun `units are not case sensitive`() {
        assertEquals(96.0, px("1IN"), 1e-9)
        assertEquals(10.0, px("10PX"), 1e-9)
    }

    @Test
    fun `relative units keep their parts until they are resolved`() {
        assertEquals(Length(pct = 50.0), parseLengthOrNull("50%"))
        assertEquals(Length(em = 2.0), parseLengthOrNull("2em"))
        assertEquals(Length(em = 1.0), parseLengthOrNull("2ex"))
    }

    @Test
    fun `resolving uses the right base for each part`() {
        assertEquals(50.0, checkNotNull(parseLengthOrNull("25%")).resolve(200.0, 16.0), 1e-9)
        assertEquals(32.0, checkNotNull(parseLengthOrNull("2em")).resolve(200.0, 16.0), 1e-9)
    }

    @Test
    fun `odd number forms are read`() {
        assertEquals(0.5, px(".5"), 1e-9)
        assertEquals(-3.0, px("-3"), 1e-9)
        assertEquals(3.0, px("+3"), 1e-9)
        assertEquals(1000.0, px("1e3"), 1e-9)
        assertEquals(16.0, checkNotNull(parseLengthOrNull("1em")).resolve(0.0, 16.0), 1e-9)
    }

    @Test
    fun `junk is not a length`() {
        assertNull(parseLengthOrNull("abc"))
        assertNull(parseLengthOrNull("10foo"))
        assertNull(parseLengthOrNull(""))
        assertNull(parseLengthOrNull("10 px"))
        // A dot with no digit after it is a mistake to Chromium, though SVG 1.1 allowed it.
        assertNull(parseLengthOrNull("5."))
        assertNull(parseLengthOrNull("NaN"))
        assertNull(parseLengthOrNull("Infinity"))
    }

    @Test
    fun `calc reduces to the same parts`() {
        assertEquals(Length(px = -10.0, pct = 50.0), parseLengthOrNull("calc(50% - 10px)"))
        assertEquals(Length(px = 20.0), parseLengthOrNull("calc(10px * 2)"))
        assertEquals(Length(pct = 25.0), parseLengthOrNull("calc(50% / 2)"))
        assertEquals(Length(px = 12.0), parseLengthOrNull("calc((2px + 4px) * 2)"))
        assertNull(parseLengthOrNull("calc(10px * 2px)"))
        assertNull(parseLengthOrNull("calc(10px / 0)"))
    }

    @Test
    fun `clock values come in every SMIL form`() {
        assertEquals(2.0, checkNotNull(parseClock("2s")), 1e-9)
        assertEquals(2.0, checkNotNull(parseClock("2")), 1e-9)
        assertEquals(0.5, checkNotNull(parseClock("500ms")), 1e-9)
        assertEquals(90.0, checkNotNull(parseClock("1.5min")), 1e-9)
        assertEquals(3600.0, checkNotNull(parseClock("1h")), 1e-9)
        assertEquals(150.0, checkNotNull(parseClock("02:30")), 1e-9)
        assertEquals(3723.5, checkNotNull(parseClock("01:02:03.5")), 1e-9)
        assertNull(parseClock("soon"))
        assertNull(parseClock("1:2:3:4"))
    }

    @Test
    fun `css time needs a unit`() {
        assertEquals(0.2, checkNotNull(parseCssTime("200ms")), 1e-9)
        assertEquals(1.5, checkNotNull(parseCssTime("1.5s")), 1e-9)
        assertNull(parseCssTime("3"))
    }

    @Test
    fun `angles convert to degrees`() {
        assertEquals(45.0, checkNotNull(parseAngle("45")), 1e-9)
        assertEquals(45.0, checkNotNull(parseAngle("45deg")), 1e-9)
        assertEquals(180.0, checkNotNull(parseAngle("3.14159265358979rad")), 1e-6)
        assertEquals(90.0, checkNotNull(parseAngle("100grad")), 1e-9)
        assertEquals(180.0, checkNotNull(parseAngle(".5turn")), 1e-9)
    }

    private fun argb(text: String): Int = checkNotNull(parseColor(text)) { "could not read $text" }.toArgb()

    @Test
    fun `named colours are all there`() {
        assertEquals(0xFFFF0000.toInt(), argb("red"))
        assertEquals(0xFFF0F8FF.toInt(), argb("aliceblue"))
        assertEquals(0xFF9ACD32.toInt(), argb("yellowgreen"))
        assertEquals(0xFF663399.toInt(), argb("rebeccapurple"))
        assertEquals(0xFF808080.toInt(), argb("grey"))
        assertEquals(0xFF808080.toInt(), argb("gray"))
        assertEquals(0xFFFF0000.toInt(), argb("RED"))
        assertEquals(0x00000000, argb("transparent"))
    }

    @Test
    fun `every name in the CSS colour list is known`() {
        val names =
            "aliceblue antiquewhite aqua aquamarine azure beige bisque black blanchedalmond blue blueviolet brown burlywood " +
                "cadetblue chartreuse chocolate coral cornflowerblue cornsilk crimson cyan darkblue darkcyan darkgoldenrod " +
                "darkgray darkgreen darkgrey darkkhaki darkmagenta darkolivegreen darkorange darkorchid darkred darksalmon " +
                "darkseagreen darkslateblue darkslategray darkslategrey darkturquoise darkviolet deeppink deepskyblue dimgray " +
                "dimgrey dodgerblue firebrick floralwhite forestgreen fuchsia gainsboro ghostwhite gold goldenrod gray green " +
                "greenyellow grey honeydew hotpink indianred indigo ivory khaki lavender lavenderblush lawngreen lemonchiffon " +
                "lightblue lightcoral lightcyan lightgoldenrodyellow lightgray lightgreen lightgrey lightpink lightsalmon " +
                "lightseagreen lightskyblue lightslategray lightslategrey lightsteelblue lightyellow lime limegreen linen " +
                "magenta maroon mediumaquamarine mediumblue mediumorchid mediumpurple mediumseagreen mediumslateblue " +
                "mediumspringgreen mediumturquoise mediumvioletred midnightblue mintcream mistyrose moccasin navajowhite navy " +
                "oldlace olive olivedrab orange orangered orchid palegoldenrod palegreen paleturquoise palevioletred papayawhip " +
                "peachpuff peru pink plum powderblue purple rebeccapurple red rosybrown royalblue saddlebrown salmon sandybrown " +
                "seagreen seashell sienna silver skyblue slateblue slategray slategrey snow springgreen steelblue tan teal " +
                "thistle tomato turquoise violet wheat white whitesmoke yellow yellowgreen"
        for (name in names.split(' ')) assertNotNull("missing $name", parseColor(name))
    }

    @Test
    fun `hex colours come in four lengths`() {
        assertEquals(0xFFFF0000.toInt(), argb("#f00"))
        assertEquals(0xFFFF0000.toInt(), argb("#FF0000"))
        assertEquals(0x88FF0000.toInt(), argb("#f008"))
        assertEquals(0x80FF0000.toInt(), argb("#ff000080"))
        assertNull(parseColor("#12345"))
        assertNull(parseColor("#xyz"))
    }

    @Test
    fun `rgb accepts numbers and percentages with either separator`() {
        assertEquals(0xFF0A141E.toInt(), argb("rgb(10, 20, 30)"))
        assertEquals(0xFF0A141E.toInt(), argb("rgb(10 20 30)"))
        assertEquals(0xFFFF8000.toInt(), argb("rgb(100%, 50%, 0%)"))
        assertEquals(0x800A141E.toInt(), argb("rgba(10, 20, 30, 0.5)"))
        assertEquals(0x800A141E.toInt(), argb("rgb(10 20 30 / 50%)"))
        assertEquals(0xFFFF0000.toInt(), argb("rgb(300, -5, 0)"))
    }

    @Test
    fun `hsl covers the colour wheel`() {
        assertEquals(0xFFFF0000.toInt(), argb("hsl(0, 100%, 50%)"))
        assertEquals(0xFF00FF00.toInt(), argb("hsl(120, 100%, 50%)"))
        assertEquals(0xFF0000FF.toInt(), argb("hsl(240, 100%, 50%)"))
        assertEquals(0xFFFFFFFF.toInt(), argb("hsl(0, 0%, 100%)"))
        assertEquals(0xFF000000.toInt(), argb("hsl(0, 0%, 0%)"))
        assertEquals(0xFF808080.toInt(), argb("hsl(77, 0%, 50%)"))
        assertEquals(0x80FF0000.toInt(), argb("hsla(360, 100%, 50%, 0.5)"))
        assertEquals(0xFFFF0000.toInt(), argb("hsl(-360deg 100% 50%)"))
    }

    @Test
    fun `junk is not a colour`() {
        assertNull(parseColor("notacolor"))
        assertNull(parseColor("rgb(1, 2)"))
        assertNull(parseColor(""))
    }

    @Test
    fun `paint values cover all their forms`() {
        assertEquals(PaintValue.None, parsePaint("none"))
        assertEquals(PaintValue.Current, parsePaint("currentColor"))
        assertEquals(PaintValue.Current, parsePaint("currentcolor"))
        assertEquals(PaintValue.Ref("a", null), parsePaint("url(#a)"))
        assertEquals(PaintValue.Ref("a", null), parsePaint("url('#a')"))
        assertEquals(PaintValue.Ref("a", null), parsePaint("url( \"#a\" )"))
        assertEquals(PaintValue.Ref("a", PaintValue.None), parsePaint("url(#a) none"))
        assertEquals(PaintValue.Ref("a", PaintValue.Solid(Rgba.fromRgb(0xFF0000))), parsePaint("url(#a) red"))
        // A reference outside the file has no id, but its fallback still counts.
        assertEquals(PaintValue.Ref(null, PaintValue.None), parsePaint("url(other.svg#a) none"))
        assertNull(parsePaint("url(#a"))
    }

    @Test
    fun `the transform attribute has its own syntax`() {
        assertEquals(
            listOf(TransformOp.Translate(Length(px = 10.0), Length(px = 20.0)), TransformOp.Rotate(45.0, 1.0, 2.0)),
            parseTransformList("translate(10 20) rotate(45 1 2)"),
        )
        assertEquals(listOf<TransformOp>(), parseTransformList("none"))
        assertEquals(listOf(TransformOp.Scale(2.0, 2.0)), parseTransformList("scale(2)"))
        assertEquals(listOf(TransformOp.Skew(30.0, 0.0)), parseTransformList("skewX(30)"))
        assertEquals(listOf(TransformOp.Raw(Mat2D(1.0, 2.0, 3.0, 4.0, 5.0, 6.0))), parseTransformList("matrix(1 2 3 4 5 6)"))
        assertEquals(listOf(TransformOp.Raw(Mat2D(1.0, 2.0, 3.0, 4.0, 5.0, 6.0))), parseTransformList("matrix(1,2,3,4,5,6)"))
        assertEquals(2, parseTransformList("translate(1),rotate(2)")?.size)
        assertNull(parseTransformList("rotate(abc)"))
        assertNull(parseTransformList("matrix(1 2 3)"))
        assertNull(parseTransformList("explode(1)"))
        assertNull(parseTransformList("translate(10"))
        // CSS forms are mistakes here. Browsers answer them with no transform at all, and so does this.
        assertNull(parseTransformList("translate(10px, 50%)"))
        assertNull(parseTransformList("rotate(45deg)"))
        assertNull(parseTransformList("translateY(5)"))
        assertNull(parseTransformList("scale(150%)"))
    }

    @Test
    fun `the transform property has CSS syntax`() {
        assertEquals(
            listOf(TransformOp.Translate(Length(px = 10.0), Length(pct = 50.0)), TransformOp.Rotate(90.0)),
            parseCssTransformList("translate(10px, 50%) rotate(.25turn)"),
        )
        assertEquals(listOf(TransformOp.Scale(1.5, 1.5)), parseCssTransformList("scale(150%)"))
        assertEquals(listOf(TransformOp.Translate(Length.ZERO, Length(px = 5.0))), parseCssTransformList("translateY(5px)"))
        assertEquals(listOf(TransformOp.Translate(Length.ZERO, Length.ZERO)), parseCssTransformList("translate(0, 0)"))
        assertEquals(listOf(TransformOp.Rotate(0.0)), parseCssTransformList("rotate(0)"))
        assertEquals(listOf<TransformOp>(), parseCssTransformList("none"))
        // These are the attribute's forms, and mistakes here.
        assertNull(parseCssTransformList("translate(10, 20)"))
        assertNull(parseCssTransformList("rotate(45)"))
        assertNull(parseCssTransformList("rotate(45deg 1 2)"))
        assertNull(parseCssTransformList("translate(1px 2px)"))
        assertNull(parseCssTransformList("translate(1px), rotate(2deg)"))
    }

    @Test
    fun `lengths in a stylesheet need units, except where browsers exempt them`() {
        assertNull(parseProperty("font-size", "20", css = true))
        assertNull(parseProperty("letter-spacing", "4", css = true))
        assertNull(parseProperty("transform-origin", "30 170", css = true))
        assertEquals(LengthValue(Length(px = 20.0)), parseProperty("font-size", "20"))
        assertEquals(LengthValue(Length(px = 20.0)), parseProperty("font-size", "20px", css = true))
        assertEquals(LengthValue(Length(px = 0.0)), parseProperty("font-size", "0", css = true))
        assertEquals(LengthValue(Length(px = 5.0)), parseProperty("stroke-width", "5", css = true))
        assertEquals(LengthValue(Length(px = 50.0)), parseProperty("x", "50", css = true))
        assertEquals(LengthValue(Length(px = 20.0)), parseProperty("r", "20", css = true))
    }

    @Test
    fun `rem is measured from the outermost element`() {
        assertEquals("20.0px", resolveRem("2rem", 10.0))
        assertEquals("translate(15.0px, 5px)", resolveRem("translate(1.5rem, 5px)", 10.0))
        assertEquals("-5.0px", resolveRem("-.5REM", 10.0))
        assertEquals("2em", resolveRem("2em", 10.0))
        assertEquals("remember", resolveRem("remember", 10.0))
        // Only where a length can be. This could be part of an embedded picture.
        assertEquals("data:image/png;base64,AA1rem/AA", resolveRemIn("href", "data:image/png;base64,AA1rem/AA", 10.0))
        assertEquals("10.0px", resolveRemIn("width", "1rem", 10.0))
    }

    @Test
    fun `media queries are answered for a plain screen with the size of the picture`() {
        val context = MediaContext(200.0, 100.0)
        assertTrue(mediaMatches("", context))
        assertTrue(mediaMatches("screen", context))
        assertTrue(mediaMatches("all", context))
        assertFalse(mediaMatches("print", context))
        assertTrue(mediaMatches("print, screen", context))
        assertFalse(mediaMatches("not screen", context))
        assertTrue(mediaMatches("not print", context))
        assertTrue(mediaMatches("only screen", context))
        assertTrue(mediaMatches("(min-width: 100px)", context))
        assertFalse(mediaMatches("(min-width: 10000px)", context))
        assertTrue(mediaMatches("screen and (max-width: 300px) and (max-height: 100px)", context))
        assertFalse(mediaMatches("screen and (max-height: 50px)", context))
        assertTrue(mediaMatches("(orientation: landscape)", context))
        assertFalse(mediaMatches("(orientation: portrait)", context))
        assertTrue(mediaMatches("(prefers-color-scheme: light)", context))
        assertFalse(mediaMatches("(prefers-color-scheme: dark)", context))
        assertTrue(mediaMatches("(prefers-reduced-motion: no-preference)", context))
        assertFalse(mediaMatches("(prefers-reduced-motion: reduce)", context))
        assertFalse(mediaMatches("(no-such-feature: 1)", context))
    }

    @Test
    fun `the newer colour functions come out as sRGB`() {
        fun assertColor(text: String, r: Int, g: Int, b: Int, a: Double = 1.0) {
            val color = checkNotNull(parseColor(text)) { text }
            assertEquals(text, r.toDouble(), color.r, 1.5)
            assertEquals(text, g.toDouble(), color.g, 1.5)
            assertEquals(text, b.toDouble(), color.b, 1.5)
            assertEquals(text, a, color.a, 0.01)
        }
        assertColor("hwb(120 0% 0%)", 0, 255, 0)
        assertColor("hwb(240 20% 30%)", 51, 51, 179)
        assertColor("hwb(0 60% 60%)", 128, 128, 128)
        assertColor("lab(50% 40 59.5)", 191, 87, 0)
        assertColor("lch(50% 72 56)", 191, 87, 0)
        assertColor("oklab(100% 0 0)", 255, 255, 255)
        assertColor("oklch(0% 0 0)", 0, 0, 0)
        assertColor("oklch(62.8% 0.2577 29.23)", 255, 0, 0)
        assertColor("color(srgb 0.2 0.6 0.8 / 50%)", 51, 153, 204, 0.5)
        assertColor("color(srgb-linear 1 1 1)", 255, 255, 255)
        assertColor("color(display-p3 1 1 1)", 255, 255, 255)
        assertColor("color-mix(in srgb, red, blue)", 128, 0, 128)
        assertColor("color-mix(in srgb, red 25%, blue)", 64, 0, 191)
        assertColor("rgb(none 100 0)", 0, 100, 0)
        assertColor("hsl(120 100 50)", 0, 255, 0)
        assertColor("Canvas", 255, 255, 255)
        assertColor("CanvasText", 0, 0, 0)
    }

    @Test
    fun `the old way of writing colours stays strict`() {
        assertNull(parseColor("rgb(50%, 100, 0)"))
        assertNull(parseColor("hsl(120, 100, 50)"))
        assertNull(parseColor("rgb(none, 100, 0)"))
        assertNull(parseColor("rgb(1 2)"))
        assertNotNull(parseColor("rgb(50% 100 0)"))
    }

    @Test
    fun `linear easing goes through its points`() {
        val even = checkNotNull(parseEasing("linear(0, 0.9, 1)"))
        assertEquals(0.45, even.ease(0.25), 1e-9)
        assertEquals(0.9, even.ease(0.5), 1e-9)
        assertEquals(1.0, even.ease(1.0), 1e-9)
        val placed = checkNotNull(parseEasing("linear(0, 0.25 75%, 1)"))
        assertEquals(0.125, placed.ease(0.375), 1e-9)
        assertEquals(0.625, placed.ease(0.875), 1e-9)
        val flat = checkNotNull(parseEasing("linear(0 0%, 0.5 25% 75%, 1 100%)"))
        assertEquals(0.5, flat.ease(0.5), 1e-9)
        assertEquals(0.25, flat.ease(0.125), 1e-9)
        assertNull(parseEasing("linear(0)"))
        assertNull(parseEasing("linear(a, b)"))
    }

    @Test
    fun `matrices blend the way a browser blends them`() {
        val half = blendMatrices(Mat2D.IDENTITY, Mat2D.rotate(90.0), 0.5)
        assertEquals(cos(Math.toRadians(45.0)), half.a, 1e-9)
        assertEquals(sin(Math.toRadians(45.0)), half.b, 1e-9)
        // A half turn goes in the positive direction, for all that either way round is as short.
        val quarter = blendMatrices(Mat2D.IDENTITY, Mat2D.rotate(180.0), 0.5)
        assertEquals(1.0, quarter.b, 1e-9)
        // With no area left, a matrix cannot be taken apart, and it flips half way.
        val flat = Mat2D.scale(0.0, 1.0)
        assertEquals(Mat2D.IDENTITY, blendMatrices(Mat2D.IDENTITY, flat, 0.25))
        assertEquals(flat, blendMatrices(Mat2D.IDENTITY, flat, 0.75))
    }

    @Test
    fun `matrix multiplication applies the right hand side first`() {
        val m = Mat2D.translate(10.0, 0.0) * Mat2D.scale(2.0, 2.0)
        assertEquals(12.0, m.mapX(1.0, 0.0), 1e-9)
    }

    @Test
    fun `a matrix times its inverse is the identity`() {
        val m = Mat2D(2.0, 1.0, -1.0, 3.0, 5.0, 7.0)
        val product = m * checkNotNull(m.inverse())
        assertEquals(1.0, product.a, 1e-9)
        assertEquals(0.0, product.b, 1e-9)
        assertEquals(0.0, product.c, 1e-9)
        assertEquals(1.0, product.d, 1e-9)
        assertEquals(0.0, product.e, 1e-9)
        assertEquals(0.0, product.f, 1e-9)
        assertNull(Mat2D(0.0, 0.0, 0.0, 0.0, 1.0, 1.0).inverse())
    }

    @Test
    fun `right angle rotations are exact`() {
        assertEquals(Mat2D(0.0, 1.0, -1.0, 0.0), Mat2D.rotate(90.0))
        assertEquals(Mat2D(-1.0, 0.0, 0.0, -1.0), Mat2D.rotate(180.0))
        assertEquals(Mat2D(0.0, -1.0, 1.0, 0.0), Mat2D.rotate(-90.0))
    }

    private fun matrixOf(ops: List<TransformOp>): Mat2D = combine(ops, TransformContext(100.0, 100.0, 16.0))

    @Test
    fun `matching lists interpolate function by function`() {
        val a = checkNotNull(parseTransformList("rotate(0) translate(0 0)"))
        val b = checkNotNull(parseTransformList("rotate(360) translate(10 0)"))
        val middle = interpolateTransforms(a, b, 0.5)
        // Half of a full turn is half a turn. As matrices the two ends would be identical and nothing would turn at all.
        assertEquals(TransformOp.Rotate(180.0), middle[0])
        assertEquals(TransformOp.Translate(Length(px = 5.0), Length.ZERO), middle[1])
    }

    @Test
    fun `none interpolates with a list as if it were that list at rest`() {
        val middle = interpolateTransforms(emptyList(), checkNotNull(parseTransformList("scale(3) translate(10 0)")), 0.5)
        assertEquals(TransformOp.Scale(2.0, 2.0), middle[0])
        assertEquals(TransformOp.Translate(Length(px = 5.0), Length.ZERO), middle[1])
    }

    @Test
    fun `lists that do not match blend as matrices`() {
        val a = checkNotNull(parseTransformList("translate(10 0)"))
        val b = checkNotNull(parseTransformList("scale(2) rotate(0)"))
        val start = matrixOf(interpolateTransforms(a, b, 0.0))
        assertEquals(10.0, start.e, 1e-6)
        assertEquals(1.0, start.a, 1e-6)
        val end = matrixOf(interpolateTransforms(a, b, 1.0))
        assertEquals(0.0, end.e, 1e-6)
        assertEquals(2.0, end.a, 1e-6)
        val middle = matrixOf(interpolateTransforms(a, b, 0.5))
        assertEquals(5.0, middle.e, 1e-6)
        assertEquals(1.5, middle.a, 1e-6)
    }

    @Test
    fun `blending matrices takes rotation the short way round`() {
        val middle = blendMatrices(Mat2D.rotate(10.0), Mat2D.rotate(350.0), 0.5)
        // Through 0 degrees, not through 180.
        assertEquals(1.0, middle.a, 1e-6)
        assertEquals(0.0, middle.b, 1e-6)
    }

    @Test
    fun `blending a matrix with itself changes nothing`() {
        val m = Mat2D.translate(3.0, 4.0) * Mat2D.rotate(30.0) * Mat2D.scale(2.0, 3.0) * Mat2D.skew(10.0, 0.0)
        val same = blendMatrices(m, m, 0.5)
        assertEquals(m.a, same.a, 1e-6)
        assertEquals(m.b, same.b, 1e-6)
        assertEquals(m.c, same.c, 1e-6)
        assertEquals(m.d, same.d, 1e-6)
        assertEquals(m.e, same.e, 1e-6)
        assertEquals(m.f, same.f, 1e-6)
    }

    @Test
    fun `easing curves match values worked out separately`() {
        assertEquals(0.8024, checkNotNull(parseEasing("ease")).ease(0.5), 1e-3)
        assertEquals(0.4085, checkNotNull(parseEasing("ease")).ease(0.25), 1e-3)
        assertEquals(0.3154, checkNotNull(parseEasing("ease-in")).ease(0.5), 1e-3)
        assertEquals(0.6846, checkNotNull(parseEasing("ease-out")).ease(0.5), 1e-3)
        assertEquals(0.5, checkNotNull(parseEasing("ease-in-out")).ease(0.5), 1e-3)
        assertEquals(0.1292, checkNotNull(parseEasing("ease-in-out")).ease(0.25), 1e-3)
        assertEquals(0.4173, checkNotNull(parseEasing("cubic-bezier(.1, .7, 1, .1)")).ease(0.5), 1e-3)
        assertEquals(0.25, checkNotNull(parseEasing("linear")).ease(0.25), 1e-9)
    }

    @Test
    fun `cubic bezier may overshoot in y but not in x`() {
        assertEquals(1.25, checkNotNull(parseEasing("cubic-bezier(.5, 1.5, .5, 1.5)")).ease(0.5), 1e-3)
        assertNull(parseEasing("cubic-bezier(1.5, 0, .5, 1)"))
        assertNull(parseEasing("cubic-bezier(0, 0, 1)"))
    }

    @Test
    fun `curves start at zero and end at one`() {
        for (name in listOf("ease", "ease-in", "ease-out", "ease-in-out", "linear", "cubic-bezier(.3,.9,.7,.1)")) {
            val easing = checkNotNull(parseEasing(name))
            assertEquals(name, 0.0, easing.ease(0.0), 1e-9)
            assertEquals(name, 1.0, easing.ease(1.0), 1e-9)
        }
    }

    @Test
    fun `steps land where the specification says`() {
        val end = checkNotNull(parseEasing("steps(4)"))
        assertEquals(0.0, end.ease(0.1), 1e-9)
        assertEquals(0.25, end.ease(0.3), 1e-9)
        assertEquals(0.75, end.ease(0.99), 1e-9)
        assertEquals(1.0, end.ease(1.0), 1e-9)

        val start = checkNotNull(parseEasing("steps(4, start)"))
        assertEquals(0.25, start.ease(0.0), 1e-9)
        assertEquals(0.5, start.ease(0.3), 1e-9)
        assertEquals(1.0, start.ease(1.0), 1e-9)

        val none = checkNotNull(parseEasing("steps(3, jump-none)"))
        assertEquals(0.0, none.ease(0.1), 1e-9)
        assertEquals(0.5, none.ease(0.5), 1e-9)
        assertEquals(1.0, none.ease(0.9), 1e-9)

        val both = checkNotNull(parseEasing("steps(3, jump-both)"))
        assertEquals(0.25, both.ease(0.0), 1e-9)
        assertEquals(0.5, both.ease(0.4), 1e-9)
        assertEquals(1.0, both.ease(1.0), 1e-9)

        assertEquals(1.0, checkNotNull(parseEasing("step-start")).ease(0.01), 1e-9)
        assertEquals(0.0, checkNotNull(parseEasing("step-end")).ease(0.99), 1e-9)
        assertNull(parseEasing("steps(0)"))
        assertNull(parseEasing("steps(1, jump-none)"))
        assertFalse(parseEasing("steps(2, end)") == null)
    }

    @Test
    fun `selectors weigh ids over classes over types`() {
        fun weight(text: String): Int = checkNotNull(parseSelector(text)) { text }.specificity
        assertEquals(0, weight("*"))
        assertEquals(1, weight("rect"))
        assertEquals(Selector.CLASS_WEIGHT, weight(".a"))
        assertEquals(Selector.CLASS_WEIGHT + 1, weight("rect.a"))
        assertEquals(Selector.ID_WEIGHT, weight("#a"))
        assertEquals(Selector.ID_WEIGHT + Selector.CLASS_WEIGHT + 2, weight("g #a rect.b"))
        assertTrue(weight(".a.b.c") > weight("rect.a"))
    }

    @Test
    fun `selectors nobody can match are refused rather than guessed at`() {
        assertNull(parseSelector("rect:hover"))
        assertNull(parseSelector("a::before"))
        assertNull(parseSelector(""))
        assertNull(parseSelector(".a > "))
    }
}
