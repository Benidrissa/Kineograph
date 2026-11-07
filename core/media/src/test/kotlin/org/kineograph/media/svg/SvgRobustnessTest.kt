package org.kineograph.media.svg

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.Random
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SvgRobustnessTest {
    /** These tests are about not hanging, so a hang has to fail one test and name it, instead of stalling the whole build. */
    @get:Rule
    val timeout: Timeout = Timeout
        .builder()
        .withTimeout(120, TimeUnit.SECONDS)
        .withLookingForStuckThread(true)
        .build()

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun assertRefused(reason: SvgParseException.Reason, block: () -> Unit) {
        try {
            block()
            fail("expected a refusal")
        } catch (e: SvgParseException) {
            assertEquals(e.message, reason, e.reason)
        }
    }

    private val tiny =
        """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect width="100" height="100" fill="red"/></svg>"""

    @Test
    fun `plain bytes parse`() {
        parse(tiny).frame().assertPixel(50, 50, RED)
        SvgParser.parse(tiny.toByteArray()).frame().assertPixel(50, 50, RED)
        SvgParser.parse(ByteArrayInputStream(tiny.toByteArray())).frame().assertPixel(50, 50, RED)
    }

    @Test
    fun `svgz is told apart by its magic bytes`() {
        SvgParser.parse(gzip(tiny.toByteArray())).frame().assertPixel(50, 50, RED)
        SvgParser.parse(ByteArrayInputStream(gzip(tiny.toByteArray()))).frame().assertPixel(50, 50, RED)
    }

    @Test
    fun `a fixture as svgz plays through the stream entry point`() {
        val document = SvgParser.parse(ByteArrayInputStream(gzip(fixture("bounce-smil.svg"))))
        assertTrue(document.animation.isAnimated)
    }

    @Test
    fun `damaged gzip is refused`() {
        val packed = gzip(tiny.toByteArray())
        packed[packed.size / 2] = (packed[packed.size / 2] + 1).toByte()
        packed[packed.size / 2 + 1] = (packed[packed.size / 2 + 1] + 7).toByte()
        assertRefused(SvgParseException.Reason.CORRUPT_COMPRESSION) { SvgParser.parse(packed) }
    }

    @Test
    fun `truncated gzip is refused`() {
        val packed = gzip(tiny.toByteArray())
        assertRefused(SvgParseException.Reason.CORRUPT_COMPRESSION) { SvgParser.parse(packed.copyOf(packed.size / 2)) }
    }

    @Test
    fun `just the magic bytes is refused`() {
        assertRefused(SvgParseException.Reason.CORRUPT_COMPRESSION) { SvgParser.parse(byteArrayOf(0x1F, 0x8B.toByte())) }
    }

    @Test
    fun `a compression bomb stops at the limit`() {
        val bomb = gzip(ByteArray(8 * 1024 * 1024))
        val limits = SvgLimits.forDevice().copy(maxInputBytes = 1024 * 1024)
        assertRefused(SvgParseException.Reason.TOO_LARGE) { SvgParser.parse(bomb, limits) }
    }

    @Test
    fun `an oversized plain stream stops at the limit`() {
        val limits = SvgLimits.forDevice().copy(maxInputBytes = 16)
        assertRefused(SvgParseException.Reason.TOO_LARGE) { SvgParser.parse(ByteArrayInputStream(tiny.toByteArray()), limits) }
    }

    @Test
    fun `a stream that fails to read is reported`() {
        val failing =
            object : InputStream() {
                override fun read(): Int = throw IOException("disk went away")
            }
        assertRefused(SvgParseException.Reason.READ_FAILED) { SvgParser.parse(failing) }
    }

    @Test
    fun `empty input is refused`() {
        assertRefused(SvgParseException.Reason.EMPTY) { SvgParser.parse(ByteArray(0)) }
        assertRefused(SvgParseException.Reason.EMPTY) { SvgParser.parse("") }
        assertRefused(SvgParseException.Reason.EMPTY) { SvgParser.parse("   \n ") }
        assertRefused(SvgParseException.Reason.EMPTY) { SvgParser.parse("   \n ".toByteArray()) }
    }

    @Test
    fun `things that are not xml are refused`() {
        assertRefused(SvgParseException.Reason.MALFORMED) { SvgParser.parse("hello") }
        assertRefused(SvgParseException.Reason.MALFORMED) { SvgParser.parse("{\"not\": \"xml\"}") }
    }

    @Test
    fun `broken xml is refused`() {
        val broken =
            listOf(
                "<svg",
                "<svg><rect",
                "<svg><rect></svg>",
                "<svg></rect></svg>",
                "<svg><rect x=1/></svg>",
                "<svg>",
            )
        for (text in broken) {
            try {
                SvgParser.parse(text)
                fail("should have refused $text")
            } catch (e: SvgParseException) {
                assertEquals(text, SvgParseException.Reason.MALFORMED, e.reason)
            }
        }
    }

    @Test
    fun `xml that is not svg is refused`() {
        assertRefused(SvgParseException.Reason.NOT_SVG) { SvgParser.parse("<html><body/></html>") }
        assertRefused(SvgParseException.Reason.NOT_SVG) { SvgParser.parse("""<svg xmlns="http://example.org/other"/>""") }
    }

    @Test
    fun `random bytes only ever give the one exception`() {
        val random = Random(1234)
        repeat(200) {
            val bytes = ByteArray(random.nextInt(300))
            random.nextBytes(bytes)
            try {
                SvgParser.parse(bytes)
            } catch (_: SvgParseException) {
                // The one acceptable answer.
            }
        }
    }

    @Test
    fun `a good file damaged at random never gets past the one exception or crashes a render`() {
        val source = String(fixture("kitchen-sink.svg"), Charsets.UTF_8)
        val random = Random(99)
        val alphabet = " <>/\"'=#;:().-0159xyzabc&!%"
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val destination = RectF(0f, 0f, 32f, 32f)
        var parsed = 0
        repeat(400) {
            val chars = source.toCharArray()
            repeat(1 + random.nextInt(8)) {
                chars[random.nextInt(chars.size)] = alphabet[random.nextInt(alphabet.length)]
            }
            val document =
                try {
                    SvgParser.parse(String(chars))
                } catch (_: SvgParseException) {
                    null
                }
            if (document != null) {
                parsed++
                document.render(canvas, random.nextDouble() * 5, destination)
            }
        }
        assertTrue("only $parsed of 400 parsed, so this test did not reach rendering", parsed > 40)
    }

    @Test
    fun `a good file cut short anywhere is refused or draws`() {
        val source = fixture("kitchen-sink.svg")
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val destination = RectF(0f, 0f, 16f, 16f)
        for (length in 0 until source.size step 37) {
            try {
                SvgParser.parse(source.copyOf(length)).render(canvas, 0.5, destination)
            } catch (_: SvgParseException) {
                // Fine.
            }
        }
    }

    @Test
    fun `the kitchen sink fixture draws at every moment without trouble`() {
        val document = SvgParser.parse(fixture("kitchen-sink.svg"))
        assertTrue(document.animation.isAnimated)
        assertTrue(document.frame(0.0, 200, 200).paintedPixels() > 1000)
        for (t in listOf(0.0, 0.1, 0.5, 1.0, 1.7, 3.0, 10.0, 1000.0)) {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            assertTrue(document.render(Canvas(bitmap), t, RectF(0f, 0f, 64f, 64f)))
        }
    }

    @Test
    fun `a billion laughs does nothing, since doctype processing is off`() {
        val laughs = StringBuilder("<!DOCTYPE svg [<!ENTITY a0 \"hahahahaha\">")
        for (i in 1..10) laughs.append("<!ENTITY a$i \"" + "&a${i - 1};".repeat(10) + "\">")
        laughs.append("]><svg xmlns=\"http://www.w3.org/2000/svg\"><text>&a10;</text></svg>")
        val started = System.nanoTime()
        try {
            SvgParser.parse(laughs.toString())
        } catch (_: SvgParseException) {
            // Refusing it is fine too. What matters is that it does not hang or run out of memory.
        }
        assertTrue((System.nanoTime() - started) / 1_000_000_000.0 < 20.0)
    }

    @Test
    fun `an external entity is never read`() {
        val secret =
            """<!DOCTYPE svg [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <svg xmlns="http://www.w3.org/2000/svg"><text font-size="20" y="20">&x;</text></svg>"""
        try {
            val document = SvgParser.parse(secret)
            assertEquals(0, document.frame().paintedPixels())
        } catch (_: SvgParseException) {
            // Equally fine.
        }
    }

    @Test
    fun `a plain doctype is fine`() {
        val text =
            """<?xml version="1.0"?>
            <!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd">
            $tiny"""
        parse(text).frame().assertPixel(50, 50, RED)
    }

    @Test
    fun `an illustrator style file with entities and a switch draws`() {
        val document = SvgParser.parse(fixture("illustrator-style.svg"))
        document.frame().assertPixel(50, 50, RED)
    }

    @Test
    fun `undeclared prefixes are forgiven`() {
        val document =
            parse(
                """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">
                <defs><rect id="a" width="100" height="100" fill="red"/></defs><use xlink:href="#a"/></svg>""",
            )
        document.frame().assertPixel(50, 50, RED)
    }

    @Test
    fun `a file with no namespace at all still draws`() {
        parse("""<svg width="100" height="100"><rect width="100" height="100" fill="red"/></svg>""").frame().assertPixel(50, 50, RED)
    }

    @Test
    fun `a prefixed svg namespace works`() {
        val document =
            parse(
                """<s:svg xmlns:s="http://www.w3.org/2000/svg" width="100" height="100"><s:rect width="100" height="100" fill="red"/></s:svg>""",
            )
        document.frame().assertPixel(50, 50, RED)
    }

    @Test
    fun `elements from other namespaces are skipped, children and all`() {
        val document =
            parse(
                """<svg xmlns="http://www.w3.org/2000/svg" xmlns:i="http://inkscape.org" width="100" height="100">
                <rect width="100" height="100" fill="red" i:label="x"/>
                <i:thing><rect width="100" height="100" fill="#00f"/></i:thing></svg>""",
            )
        document.frame().assertPixel(50, 50, RED)
    }

    @Test
    fun `whitespace and a byte order mark before the declaration are forgiven`() {
        parse(" \n<?xml version=\"1.0\"?>$tiny").frame().assertPixel(50, 50, RED)
        SvgParser.parse("  \n<?xml version=\"1.0\"?>$tiny".toByteArray()).frame().assertPixel(50, 50, RED)
        SvgParser.parse(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + tiny.toByteArray()).frame().assertPixel(50, 50, RED)
    }

    @Test
    fun `another encoding is honoured through its declaration`() {
        val text =
            """<?xml version="1.0" encoding="ISO-8859-1"?><svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" id="caf${"é"}"><rect id="caf${"é"}2" width="100" height="100" fill="red"/></svg>"""
        SvgParser.parse(text.toByteArray(Charsets.ISO_8859_1)).frame().assertPixel(50, 50, RED)
        val utf16 = text.replace("ISO-8859-1", "UTF-16").toByteArray(Charsets.UTF_16)
        SvgParser.parse(utf16).frame().assertPixel(50, 50, RED)
    }

    @Test
    fun `trailing junk after the root is ignored`() {
        parse("$tiny this is not xml <<<").frame().assertPixel(50, 50, RED)
    }

    @Test
    fun `an unknown entity is harmless`() {
        val document = parse(svg("""<rect fill='&nosuch;' width="100" height="100"/><text>a&nosuch;b</text>"""))
        assertTrue(SvgWarning.INVALID_CONTENT_IGNORED in document.warnings)
        document.frame().assertPixel(50, 50, BLACK)
    }

    @Test
    fun `html entities are forgiven`() {
        val document = parse(svg("""<text x="0" y="50" font-size="20">a&nbsp;b</text><rect fill="red" width="10" height="10"/>"""))
        document.frame().assertPixel(5, 5, RED)
    }

    @Test
    fun `scripts never run and warn`() {
        val document =
            parse(
                svg(
                    """<script>document.getElementById('r').setAttribute('fill', '#00f')</script>
                    <rect id="r" width="100" height="100" fill="red"/>""",
                ),
            )
        assertTrue(SvgWarning.SCRIPT_IGNORED in document.warnings)
        assertFalse(document.animation.isAnimated)
        document.frame().assertPixel(50, 50, RED)
    }

    @Test
    fun `motion made by a script imports as the static picture`() {
        val document = SvgParser.parse(fixture("scripted.svg"))
        assertTrue(SvgWarning.SCRIPT_IGNORED in document.warnings)
        assertFalse(document.animation.isAnimated)
        document.frame().assertPixel(50, 50, RED)
        assertSamePixels(document.frame(0.0), document.frame(3.0))
    }

    @Test
    fun `event handlers warn and do nothing`() {
        val document = parse(svg("""<rect onclick="alert(1)" width="100" height="100" fill="red"/>""", attributes = """onload="init()""""))
        assertTrue(SvgWarning.SCRIPT_IGNORED in document.warnings)
    }

    @Test
    fun `a javascript link warns`() {
        val document = parse(svg("""<a href="javascript:alert(1)"><rect width="100" height="100" fill="red"/></a>"""))
        assertTrue(SvgWarning.SCRIPT_IGNORED in document.warnings)
        document.frame().assertPixel(50, 50, RED)
    }

    @Test
    fun `a foreignObject is skipped with a warning`() {
        val document =
            parse(
                svg(
                    """<foreignObject width="100" height="100"><div xmlns="http://www.w3.org/1999/xhtml">hi</div></foreignObject>
                    <rect width="10" height="10" fill="red"/>""",
                ),
            )
        assertTrue(SvgWarning.FOREIGN_OBJECT_IGNORED in document.warnings)
        document.frame().assertPixel(5, 5, RED)
    }

    @Test
    fun `external use warns and draws nothing`() {
        val document = parse(svg("""<use href="other.svg#x"/>"""))
        assertTrue(SvgWarning.EXTERNAL_REFERENCE_IGNORED in document.warnings)
        assertEquals(0, document.frame().paintedPixels())
    }

    @Test
    fun `external stylesheets warn`() {
        val document = parse("""<?xml-stylesheet href="https://example.org/a.css"?>$tiny""")
        assertTrue(SvgWarning.EXTERNAL_REFERENCE_IGNORED in document.warnings)
    }

    @Test
    fun `a clean file has no warnings`() {
        assertTrue(parse(tiny).warnings.isEmpty())
    }

    @Test
    fun `an animation that freezes raises no warning`() {
        val document = parse(svg("""<rect width="100" height="100"><animate attributeName="x" to="10" dur="1s" fill="freeze"/></rect>"""))
        assertTrue(document.warnings.toString(), document.warnings.isEmpty())
    }

    @Test
    fun `every kind of animation element is free of false warnings`() {
        val document =
            parse(
                svg(
                    """<path id="p" d="M0 0 L10 10"/>
                    <rect width="100" height="100">
                        <animateTransform attributeName="transform" type="rotate" from="0" to="90" dur="1s" fill="remove" additive="sum"/>
                        <animateMotion dur="1s" rotate="auto" fill="freeze"><mpath href="#p"/></animateMotion>
                        <set attributeName="fill" to="red" begin="1s" fill="freeze"/>
                        <discard begin="9s"/>
                    </rect>""",
                ),
            )
        assertTrue(document.warnings.toString(), document.warnings.isEmpty())
    }

    @Test
    fun `a link to somewhere else is not an external reference`() {
        val document = parse(svg("""<a href="https://example.org" target="_blank"><rect width="100" height="100"/></a>"""))
        assertTrue(document.warnings.toString(), document.warnings.isEmpty())
    }

    @Test
    fun `the fixtures made to be clean have no warnings`() {
        assertEquals(emptySet<SvgWarning>(), SvgParser.parse(fixture("bounce-smil.svg")).warnings)
        assertEquals(emptySet<SvgWarning>(), SvgParser.parse(fixture("spinner-css.svg")).warnings)
        assertEquals(setOf(SvgWarning.FOREIGN_OBJECT_IGNORED), SvgParser.parse(fixture("illustrator-style.svg")).warnings)
    }

    @Test
    fun `the kitchen sink fixture warns only about the filter it really uses`() {
        assertEquals(setOf(SvgWarning.FILTER_IGNORED), SvgParser.parse(fixture("kitchen-sink.svg")).warnings)
    }

    @Test
    fun `text on a path draws its words, and with no path it draws nothing`() {
        val document =
            parse(
                svg(
                    """<defs><path id="p" d="M0 50 H100"/></defs>
                    <text font-size="20" y="50"><textPath href="#p">Hello</textPath></text>""",
                ),
            )
        assertEquals(emptySet<SvgWarning>(), document.warnings)
        assertTrue(document.frame().paintedPixels() > 0)
        val lost = parse(svg("""<text font-size="20" y="50"><textPath href="#nothing">Hello</textPath></text>"""))
        assertEquals(0, lost.frame().paintedPixels())
    }

    @Test
    fun `blend modes warn`() {
        assertTrue(SvgWarning.UNSUPPORTED_FEATURE_IGNORED in parse(svg("""<rect style="mix-blend-mode: multiply"/>""")).warnings)
    }

    @Test
    fun `a shape we know clips, and one we do not warns and clips nothing`() {
        val circle = parse(svg("""<rect width="100" height="100" fill="red" style="clip-path: circle(10%)"/>"""))
        assertEquals(emptySet<SvgWarning>(), circle.warnings)
        circle.frame().assertPixel(5, 5, CLEAR)
        circle.frame().assertPixel(50, 50, RED)
        val unknown = parse(svg("""<rect width="100" height="100" fill="red" style="clip-path: star(5)"/>"""))
        assertTrue(SvgWarning.UNSUPPORTED_FEATURE_IGNORED in unknown.warnings)
        unknown.frame().assertPixel(5, 5, RED)
    }

    @Test
    fun `extremely deep nesting does not overflow the stack`() {
        val depth = 200_000
        val text =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\">" + "<g>".repeat(depth) +
                "<rect width=\"100\" height=\"100\" fill=\"red\"/>" + "</g>".repeat(depth) + "</svg>"
        val document = parse(text)
        assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
        document.frame()
    }

    @Test
    fun `what comes before something too deep still draws`() {
        val depth = 200_000
        val text =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><rect width=\"100\" height=\"100\" fill=\"red\"/>" +
                "<g>".repeat(depth) + "</g>".repeat(depth) + "</svg>"
        val document = parse(text)
        assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
        document.frame().assertPixel(50, 50, RED)
    }

    @Test
    fun `depth up to the limit draws`() {
        val depth = 400
        val text =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\">" + "<g>".repeat(depth) +
                "<rect width=\"100\" height=\"100\" fill=\"red\"/>" + "</g>".repeat(depth) + "</svg>"
        val document = parse(text)
        assertFalse(SvgWarning.LIMIT_REACHED in document.warnings)
        document.frame().assertPixel(50, 50, RED)
    }

    @Test
    fun `element counts stop at the limit, with a warning`() {
        val limits = SvgLimits.forDevice().copy(maxElements = 3)
        val document = SvgParser.parse(
            svg("""<rect width="50" height="100" fill="red"/><rect x="50" width="50" height="100" fill="red"/><rect/><rect/>"""),
            limits,
        )
        assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
        document.frame().assertPixel(25, 50, RED)
        document.frame().assertPixel(75, 50, RED)
    }

    @Test
    fun `use that refers to itself draws nothing and does not hang`() {
        assertEquals(0, drawn("""<use id="a" href="#a"/>""").paintedPixels())
    }

    @Test
    fun `use in a cycle does not hang`() {
        assertEquals(0, drawn("""<use id="a" href="#b"/><use id="b" href="#a"/>""").paintedPixels())
    }

    @Test
    fun `use of its own ancestor does not hang and draws the rest`() {
        val f = drawn("""<g id="a"><rect width="10" height="10" fill="red"/><use href="#a" x="20"/></g>""")
        f.assertPixel(5, 5, RED)
    }

    @Test
    fun `use of the root does not hang`() {
        val document =
            parse(
                """<svg id="r" xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect width="10" height="10" fill="red"/><use href="#r"/></svg>""",
            )
        document.frame().assertPixel(5, 5, RED)
    }

    @Test
    fun `a symbol that uses itself does not hang`() {
        val f = drawn("""<symbol id="s"><rect width="10" height="10" fill="red"/><use href="#s" x="20"/></symbol><use href="#s"/>""")
        f.assertPixel(5, 5, RED)
    }

    @Test
    fun `multiplying use stops at the limit and warns`() {
        // Ten levels of ten references is ten billion rectangles. Without a cap this would never come back.
        val body = StringBuilder("""<defs><rect id="l0" width="1" height="1" fill="red"/>""")
        for (level in 1..10) {
            body.append("""<g id="l$level">""")
            repeat(10) { body.append("""<use href="#l${level - 1}"/>""") }
            body.append("</g>")
        }
        body.append("""</defs><use href="#l10"/>""")
        val limits = SvgLimits.forDevice().copy(maxRenderedNodes = 100_000)
        val document = SvgParser.parse(svg(body.toString()), limits)
        assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val complete = document.render(Canvas(bitmap), 0.0, RectF(0f, 0f, 10f, 10f))
        assertFalse(complete)
    }

    @Test
    fun `a bounding box through multiplying use does not run away either`() {
        val body = StringBuilder("""<defs><rect id="l0" width="1" height="1" fill="red"/>""")
        for (level in 1..12) {
            body.append("""<g id="l$level" fill="#0${level % 10}0">""")
            repeat(6) { body.append("""<use href="#l${level - 1}" fill="red"/>""") }
            body.append("</g>")
        }
        body.append("""</defs><g style="transform-box: fill-box; transform: rotate(1deg)"><use href="#l12"/></g>""")
        val limits = SvgLimits.forDevice().copy(maxRenderedNodes = 50_000)
        val document = SvgParser.parse(svg(body.toString()), limits)
        document.frame()
    }

    @Test
    fun `numbers that overflow do not break drawing`() {
        val f =
            drawn(
                """<rect x="1e308" y="-1e308" width="1e308" height="1e308" fill="red" stroke="red" stroke-width="1e308"/>
                <circle r="1e300" cx="1e300"/><path d="M1e300 1e300 L-1e300 1e300 A1e300 1e300 0 0 1 0 0 Z" stroke="red"/>
                <rect width="10" height="10" fill="red" transform="scale(1e-300)"/>
                <rect width="10" height="10" fill="red" transform="scale(1e300) rotate(1e300)"/>
                <rect width="10" height="10" fill="red" transform="matrix(0 0 0 0 0 0)"/>
                <text font-size="1e20" x="0" y="0">A</text><text font-size="0">A</text><text font-size="1e-10">A</text>
                <line x1="0" y1="0" x2="100" y2="100" stroke="red" stroke-dasharray="1e-20 1e-20"/>
                <line x1="0" y1="0" x2="100" y2="100" stroke="red" stroke-dasharray="1e300"/>
                <rect width="10" height="10" stroke-miterlimit="-5" stroke="red"/>""",
            )
        f.getPixel(0, 0)
    }

    @Test
    fun `broken references everywhere draw whatever is left`() {
        val f =
            drawn(
                """<rect width="10" height="10" fill="url(#none)" stroke="url(#none)" mask="url(#none)" clip-path="url(#none)" marker-start="url(#none)"/>
                <path d="M0 0 L10 10" marker-start="url(#none)" marker-end="url(#g)"/>
                <defs><linearGradient id="g" href="#none"/></defs>
                <rect fill="url(#g)" width="10" height="10"/>
                <use href="#g"/>
                <rect width="100" height="100" fill="url(#r) red"/>
                <text fill="url(#none)">a<tspan fill="url(#none)">b</tspan></text>""",
            )
        f.assertPixel(50, 50, RED)
    }

    @Test
    fun `references to the wrong kind of element are ignored`() {
        val f =
            drawn(
                """<defs><rect id="r"/><mask id="m"/><linearGradient id="g"><stop stop-color="red"/></linearGradient></defs>
                <rect width="100" height="100" fill="url(#r) red" clip-path="url(#m)" mask="url(#r)" marker-start="url(#g)"/>""",
            )
        f.assertPixel(50, 50, RED)
    }

    @Test
    fun `a pattern too large for memory leaves the rest of the picture alone`() {
        val f =
            drawn(
                """<defs><pattern id="p" width="1e9" height="1e9" patternUnits="userSpaceOnUse"><rect width="10" height="10" fill="#00f"/></pattern></defs>
                <rect width="50" height="100" fill="url(#p)"/><rect x="50" width="50" height="100" fill="red"/>""",
            )
        f.assertPixel(75, 50, RED)
    }

    @Test
    fun `an embedded svg that nests too deeply is cut off`() {
        var nested = """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><rect width="10" height="10" fill="red"/></svg>"""
        repeat(8) {
            val uri = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(nested.toByteArray())
            nested = """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><image href="$uri" width="10" height="10"/></svg>"""
        }
        val document = parse(nested)
        assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
        document.frame()
    }

    @Test
    fun `an embedded svg that is broken warns`() {
        val uri = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString("<svg".toByteArray())
        val document = parse(svg("""<image href="$uri" width="10" height="10"/>"""))
        assertTrue(SvgWarning.INVALID_CONTENT_IGNORED in document.warnings)
        document.frame()
    }

    @Test
    fun `an animation chain built to spin the solver does not hang`() {
        val body = StringBuilder()
        // Every element restarts from the next one's start and end, which doubles the times at every step.
        for (i in 0 until 40) {
            val next = (i + 1) % 40
            body.append(
                """<animate id="a$i" attributeName="opacity" to="1" begin="0s;a$next.end;a$next.begin+1s;a$next.end-1s" dur="1s"/>""",
            )
        }
        val started = System.nanoTime()
        val document = parse(svg("""<rect width="100" height="100">$body</rect>"""))
        document.frame(12345.0)
        assertTrue((System.nanoTime() - started) / 1_000_000_000.0 < 60.0)
    }

    @Test
    fun `an animation that begins earlier than itself does not hang`() {
        val document =
            parse(
                svg(
                    """<rect width="100" height="100"><animate id="a" attributeName="opacity" to="1" begin="0s;a.begin-1s" dur="1s"/></rect>""",
                ),
            )
        document.frame(1.0)
    }

    @Test
    fun `a very long list of times is handled`() {
        val times = (0 until 5000).joinToString(";") { "$it.5s" }
        val document =
            parse(
                svg(
                    """<rect width="100" height="100"><animate attributeName="opacity" from="0" to="1" begin="$times" dur="0.25s"/></rect>""",
                ),
            )
        document.frame(100.0)
    }

    @Test
    fun `an empty destination is not a problem`() {
        val document = parse(tiny)
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        assertTrue(document.render(Canvas(bitmap), 0.0, RectF(5f, 5f, 5f, 5f)))
        assertTrue(document.render(Canvas(bitmap), 0.0, RectF(5f, 5f, 2f, 2f)))
    }

    @Test
    fun `a zero or negative size is not a problem`() {
        parse("""<svg xmlns="http://www.w3.org/2000/svg" width="0" height="0"><rect width="10" height="10"/></svg>""").frame()
        parse("""<svg xmlns="http://www.w3.org/2000/svg" width="-5" height="-5"><rect width="10" height="10"/></svg>""").frame()
        parse("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 0 0"><rect width="10" height="10"/></svg>""").frame()
        parse("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 -1 5"><rect width="10" height="10"/></svg>""").frame()
        parse("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="nonsense"><rect width="10" height="10"/></svg>""").frame()
    }

    @Test
    fun `the canvas comes back as it was`() {
        val document = SvgParser.parse(fixture("kitchen-sink.svg"))
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val before = canvas.saveCount
        document.render(canvas, 1.0, RectF(0f, 0f, 32f, 32f))
        assertEquals(before, canvas.saveCount)
    }

    @Test
    fun `limits come from the device`() {
        val small = SvgLimits.forDevice(64L * 1024 * 1024)
        val big = SvgLimits.forDevice(512L * 1024 * 1024)
        assertTrue(big.maxInputBytes > small.maxInputBytes)
        assertTrue(big.maxElements > small.maxElements)
        assertEquals(32L * 1024 * 1024, small.maxInputBytes)
    }

    @Test
    fun `several threads can draw one document`() {
        val document = SvgParser.parse(fixture("bounce-smil.svg"))
        val problems = AtomicInteger()
        val threads =
            List(4) { index ->
                Thread {
                    try {
                        repeat(40) { document.frame(index * 0.1 + it * 0.05) }
                    } catch (_: Throwable) {
                        problems.incrementAndGet()
                    }
                }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(0, problems.get())
    }

    private fun SvgDocument.frame(time: Double, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        render(Canvas(bitmap), time, RectF(0f, 0f, width.toFloat(), height.toFloat()))
        return bitmap
    }
}
