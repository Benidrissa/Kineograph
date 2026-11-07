package org.kineograph.media.svg

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * Files made to hang or exhaust the phone, as a security review found them, each with what used to happen to it. A hang has to fail one
 * test and name it, instead of stalling the build. Tests that are about the allowance itself lower it, so that they run for seconds and
 * not minutes: whether a file is priced about right shows just as well in how long that smaller allowance takes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SvgHostileFileTest {
    @get:Rule
    val timeout: Timeout = Timeout
        .builder()
        .withTimeout(240, TimeUnit.SECONDS)
        .withLookingForStuckThread(true)
        .build()

    private val device = SvgLimits.forDevice()

    /** An allowance of about a second, which a file made of expensive things should go through in about that long. */
    private val small = device.copy(maxRenderWork = 100_000_000L, maxParseWork = 100_000_000L)

    /**
     * The machine these run on is shared, so this is well above what any of them takes, and more so on CI, whose runners are slower than a
     * developer's machine and have no timings of their own yet. Hanging is what it is there to catch.
     */
    private fun <T> quickly(seconds: Double = 60.0, block: () -> T): T {
        val limit = if (System.getenv("CI") != null) seconds * CI_ALLOWANCE else seconds
        val started = System.nanoTime()
        val result = block()
        val taken = (System.nanoTime() - started) / 1e9
        assertTrue("took $taken seconds", taken < limit)
        return result
    }

    private fun wrap(body: String, attributes: String = """width="100" height="100""""): String =
        """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" $attributes>$body</svg>"""

    private fun SvgDocument.draw(size: Int = 100, time: Double = 0.0): Pair<Bitmap, Boolean> {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val complete = render(Canvas(bitmap), time, RectF(0f, 0f, size.toFloat(), size.toFloat()))
        return bitmap to complete
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /** `(fill or anything else that must not have been lost)`: a red square across the whole picture, last in the file. */
    private val redLast = """<rect width="100" height="100" fill="red"/>"""

    private fun assertRedLast(document: SvgDocument) {
        document.draw().first.assertPixel(50, 50, RED)
    }

    // Used to double at each step of nine levels: 64^8 replacements, still running after 30 seconds from a 4 KB file.
    @Test
    fun `variables that multiply through an empty fallback come back at once`() {
        val style = StringBuilder("svg{--a0:var(--none,);")
        for (level in 1..8) {
            style.append("--a$level:")
            repeat(64) { style.append("var(--a${level - 1})") }
            style.append(";")
        }
        style.append("fill:var(--a8)}")
        val document = quickly(20.0) { parse(wrap("<style>$style</style>$redLast")) }
        assertRedLast(document)
    }

    @Test
    fun `variables that double in length stop with a warning`() {
        val style = StringBuilder("svg{--a0:x;")
        for (level in 1..60) style.append("--a$level:var(--a${level - 1})var(--a${level - 1});")
        style.append("stroke:var(--a60)}")
        val document = quickly { parse(wrap("<style>$style</style>$redLast")) }
        assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
        assertRedLast(document)
    }

    @Test
    fun `variables that refer to each other are a loop and fall back`() {
        val document =
            quickly(20.0) {
                parse(
                    wrap(
                        """<style>rect{--a:var(--b);--b:var(--a);fill:var(--a,red)}</style><rect width="100" height="100"/>""",
                    ),
                )
            }
        document.draw().first.assertPixel(50, 50, RED)
    }

    @Test
    fun `variables that do ordinary things still work`() {
        val many = (0 until 200).joinToString(" ") { "var(--d)" }
        val f =
            drawn(
                """<style>rect{--c:red;--d:1 1;--e:var(--c);fill:var(--e);stroke-dasharray:$many}</style>""" +
                    """<rect width="100" height="100"/>""",
            )
        f.assertPixel(50, 50, RED)
    }

    // Used to go through every ancestor at every part: still running after 30 seconds from 500 bytes.
    @Test
    fun `a selector with many parts over deep nesting does not backtrack`() {
        val parts = "g ".repeat(8)
        val nesting = "<g>".repeat(50) + redLast + "</g>".repeat(50)
        quickly(20.0) { parse(wrap("<style>z $parts{fill:blue}</style>$nesting")) }
        val longer = "g ".repeat(39)
        val deep = "<g>".repeat(400) + redLast + "</g>".repeat(400)
        val document = quickly(20.0) { parse(wrap("<style>z $longer{fill:blue}</style>$deep")) }
        assertRedLast(document)
    }

    // Used to go through every earlier sibling at every part: still running after 30 seconds from 7 KB.
    @Test
    fun `a selector of sibling parts does not backtrack`() {
        val document =
            quickly(20.0) { parse(wrap("<style>z ~ g ~ g ~ g {fill:blue}</style>" + "<g/>".repeat(2000) + redLast)) }
        assertRedLast(document)
    }

    @Test
    fun `cutting selector searches short never loses a real match`() {
        val rect = """<rect class="c" width="100" height="100"/>"""
        val red = "{fill:red}</style>"
        // The nearest fitting ancestor is not the one that works, so a search has to carry on past it.
        drawn(
            """<style>.a > .b .c $red<g class="a"><g class="b"><g class="x"><g class="b">$rect</g></g></g></g>""",
        ).assertPixel(50, 50, RED)
        drawn("""<style>.a .b > .c $red<g class="a"><g class="x"><g class="b">$rect</g></g></g>""").assertPixel(50, 50, RED)
        drawn("""<style>.a + .b .c $red<g><g class="a"/><g class="b"><g class="b">$rect</g></g></g>""").assertPixel(50, 50, RED)
        // Here the earlier siblings differ in what comes before them.
        drawn("""<style>.a + .b ~ .c $red<g class="x"/><g class="a"/><g class="b"/><g class="b"/>$rect""").assertPixel(50, 50, RED)
        drawn("""<style>.a ~ .b ~ .c $red<g class="b"/><g class="a"/><g class="x"/><g class="b"/>$rect""").assertPixel(50, 50, RED)
        // A later ancestor can have the sibling that a nearer one lacks.
        drawn("""<style>.a ~ .b .c $red<g><g class="a"/><g class="b"><g><g class="b">$rect</g></g></g></g>""").assertPixel(50, 50, RED)
        drawn("""<style>.a > .b ~ .c $red<g class="a"><g class="b"/><g/>$rect</g>""").assertPixel(50, 50, RED)
        // And these are not matches, however close they come.
        assertEquals(CLEAR, drawn("""<style>.a ~ .b ~ .c $red<g class="b"/><g class="a"/>$rect""").getPixel(50, 50) and 0x00FFFFFF)
    }

    @Test
    fun `a selector past the limit is ignored with a warning, and the rest still applies`() {
        val text = "g ".repeat(1000)
        val document = quickly(20.0) { parse(wrap("""<style>$text{fill:blue} rect{fill:red}</style><rect width="100" height="100"/>""")) }
        assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
        document.draw().first.assertPixel(50, 50, RED)
    }

    // Used to take 9.89 seconds for 20000 digits, and four times that for each doubling.
    @Test
    fun `a very long number next to rem takes no time`() {
        val document = quickly(20.0) { parse(wrap("""<rect x="${"1".repeat(2_000_000)} rem" width="100" height="100" fill="red"/>""")) }
        assertNotNull(document)
        assertEquals("1".repeat(1_000_000) + " rem", resolveRem("1".repeat(1_000_000) + " rem", 10.0))
    }

    @Test
    fun `rem is read the same as it always was`() {
        assertEquals("20.0px", resolveRem("2rem", 10.0))
        assertEquals("-5.0px", resolveRem("-.5REM", 10.0))
        assertEquals("15.0px", resolveRem("+1.5rem", 10.0))
        assertEquals("1.25.0px", resolveRem("1.2.5rem", 10.0))
        assertEquals("1000.0px", resolveRem("1e2rem", 10.0))
        assertEquals("5 rem", resolveRem("5 rem", 10.0))
        assertEquals("5remx", resolveRem("5remx", 10.0))
        assertEquals("5rem_", resolveRem("5rem_", 10.0))
        assertEquals("1.rem", resolveRem("1.rem", 10.0))
        assertEquals("1erem", resolveRem("1erem", 10.0))
        assertEquals("x10.0px y10.0px", resolveRem("x1rem y1rem", 10.0))
        assertEquals("translate(10.0px,20.0px) 3em", resolveRem("translate(1rem,2rem) 3em", 10.0))
    }

    /** The hand-written readers replaced regular expressions, which are kept here to say what they have to do on short text. */
    private fun randomText(random: Random, pieces: List<String>, length: Int): String {
        val out = StringBuilder()
        repeat(random.nextInt(length) + 1) { out.append(pieces[random.nextInt(pieces.size)]) }
        return out.toString()
    }

    @Test
    fun `rem reads exactly as the regular expression did`() {
        val pattern = Regex("""([+-]?(?:[0-9]+(?:\.[0-9]+)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?)rem\b""", RegexOption.IGNORE_CASE)
        val random = Random(11)
        val pieces = listOf("1", "2", "0", ".", "+", "-", "e", "E", "rem", "REM", "r", "em", "x", "_", " ", ",", "(", ")")
        repeat(200_000) {
            val text = randomText(random, pieces, 10)
            val expected =
                pattern.replace(text) { match ->
                    val value = match.groupValues[1].toDoubleOrNull() ?: return@replace match.value
                    "${value * 10.0}px"
                }
            assertEquals(text, expected, resolveRem(text, 10.0))
        }
    }

    @Test
    fun `sync references read exactly as the regular expressions did`() {
        val beginEnd = Regex("""^(.+?)\.(beginEvent|endEvent|begin|end)\s*(?:([+-])\s*(.+))?$""")
        val repeat = Regex("""^(.+?)\.repeat\(\s*(\d+)\s*\)\s*(?:([+-])\s*(.+))?$""")
        val random = Random(12)
        val pieces =
            listOf(
                "a",
                "b",
                ".",
                "..",
                "begin",
                "end",
                "beginEvent",
                "endEvent",
                "Event",
                ".end",
                ".begin",
                ".repeat(",
                "repeat(",
                "(",
                ")",
                "1",
                "2",
                "s",
                "+",
                "-",
                " ",
                "\n",
                "\t",
            )
        repeat(200_000) {
            val text = randomText(random, pieces, 9).trim()
            if (text.isEmpty()) return@repeat
            val asRepeat =
                repeat.find(text)?.let { match ->
                    val (id, count, sign, value) = match.destructured
                    "$id|REPEAT|$count|$sign|$value"
                }
            val asEvent =
                beginEnd.find(text)?.let { match ->
                    val (id, kind, sign, value) = match.destructured
                    "$id|${if (kind.startsWith("begin")) "BEGIN" else "END"}||$sign|$value"
                }
            val actual = readSyncReference(text)?.run { "$id|$kind|${this.repeat}|$sign|$offset" }
            assertEquals(text, asRepeat ?: asEvent, actual)
        }
    }

    @Test
    fun `media features read exactly as the regular expression did`() {
        val pattern = Regex("""\(\s*([a-z-]+)\s*(?::\s*([^)]*?)\s*)?\)""")
        val random = Random(13)
        val pieces = listOf("(", ")", "a", "b-", "color", ":", " ", "  ", "\t", "1px", "and", ",")
        repeat(200_000) {
            val text = randomText(random, pieces, 8)
            val match = pattern.find(text)?.takeIf { it.range.first == 0 }
            val feature = readMediaFeature(text, 0)
            assertEquals(text, match?.groupValues?.get(1), feature?.name)
            assertEquals(text, match?.groupValues?.get(2), feature?.value)
            assertEquals(text, match?.let { it.range.last + 1 }, feature?.end)
        }
    }

    /** What a selector means, by trying everything, which is what the real search is not allowed to do. */
    private fun bruteForce(selector: Selector, el: SvgElement, index: Int, work: WorkMeter): Boolean {
        if (!selector.parts[index].matches(el, work)) return false
        if (index == 0) return true
        val siblings = el.parent?.children.orEmpty()
        val earlier = siblings.subList(0, el.siblingIndex.coerceAtMost(siblings.size))
        return when (selector.combinators[index - 1]) {
            ' ' -> generateSequence(el.parent) { it.parent }.any { bruteForce(selector, it, index - 1, work) }
            '>' -> el.parent?.let { bruteForce(selector, it, index - 1, work) } == true
            '+' -> earlier.lastOrNull()?.let { bruteForce(selector, it, index - 1, work) } == true
            else -> earlier.any { bruteForce(selector, it, index - 1, work) }
        }
    }

    @Test
    fun `selector searches cut short agree with trying everything`() {
        val random = Random(14)
        val classes = listOf("a", "b", "c")

        fun randomGroup(depth: Int): String {
            val out = StringBuilder()
            repeat(random.nextInt(4)) {
                val name = classes[random.nextInt(classes.size)]
                out.append("""<g class="$name">""")
                if (depth > 0) out.append(randomGroup(depth - 1))
                out.append("</g>")
            }
            return out.toString()
        }

        fun everyElement(el: SvgElement, into: MutableList<SvgElement>) {
            into += el
            for (child in el.children) everyElement(child, into)
        }
        val work = WorkMeter(Long.MAX_VALUE / 2, 3600.0, null)
        var matches = 0
        repeat(300) {
            val document = parse(wrap(randomGroup(4)))
            val elements = ArrayList<SvgElement>()
            everyElement(document.model.root, elements)
            repeat(40) {
                val text = StringBuilder()
                val parts = random.nextInt(5) + 1
                for (i in 0 until parts) {
                    if (i > 0) text.append(listOf(" ", " > ", " + ", " ~ ")[random.nextInt(4)])
                    text.append(if (random.nextInt(5) == 0) "*" else "." + classes[random.nextInt(classes.size)])
                }
                val selector = checkNotNull(parseSelector(text.toString()))
                for (element in elements) {
                    val expected = bruteForce(selector, element, selector.parts.lastIndex, work)
                    assertEquals(text.toString(), expected, selector.matches(element, work))
                    if (expected) matches++
                }
            }
        }
        // Worthless if it never finds anything.
        assertTrue(matches > 1000)
    }

    // Used to be cubic in the spaces: still running after 30 seconds from 2 KB.
    @Test
    fun `spaces in a media query are not a problem`() {
        val spaces = " ".repeat(2_000_000)
        quickly(20.0) { parse(wrap("<style>@media all ) (a:${spaces}x {a{}}</style>$redLast")) }
        quickly(20.0) { parse(wrap("""<style media="all ) (a:${spaces}x"></style>$redLast""")) }
    }

    // Used to copy the rest of the text for each feature: still running after 60 seconds from 1.3 MB.
    @Test
    fun `a media query with very many features is not a problem`() {
        val document =
            quickly(
                20.0,
            ) { parse(wrap("<style>@media ${"(color) ".repeat(200_000)}{rect{fill:red}}</style><rect width=\"100\" height=\"100\"/>")) }
        document.draw().first.assertPixel(50, 50, RED)
    }

    @Test
    fun `media queries are read the same as they always were`() {
        val context = MediaContext(200.0, 100.0)
        assertTrue(mediaMatches("( min-width :   100px   )", context))
        assertTrue(mediaMatches("screen and (min-width:100px) and (color)", context))
        assertTrue(mediaMatches("(color)and (hover)", context))
        assertFalse(mediaMatches("screen and", context))
        assertFalse(mediaMatches("(color", context))
        assertFalse(mediaMatches("(color) junk", context))
        assertFalse(mediaMatches("(COLOR2)", context))
        assertTrue(mediaMatches("not print and (color)", context))
    }

    // Used to take 18.79 seconds for 39 KB, and over a minute for 100 KB.
    @Test
    fun `odd line breaks in a begin value take no time`() {
        val spaces = " ".repeat(2_000_000)
        val first = """<animate attributeName="x" to="1" dur="1s" begin="a.end+${spaces}x&#10;y"/>"""
        val second = """<animate attributeName="x" to="1" dur="1s" begin="${"a.end+b".repeat(400_000)}&#10;x"/>"""
        val third = """<animate attributeName="x" to="1" dur="1s" begin="a.repeat(1)+$spaces x&#10;y"/>"""
        val document = quickly(30.0) { parse(wrap("<rect>$first$second$third</rect>$redLast")) }
        assertRedLast(document)
    }

    @Test
    fun `sync references are read the same as they always were`() {
        fun read(text: String): String? = readSyncReference(text)?.run { "$id|$kind|$repeat|$sign|$offset" }
        assertEquals("a|END|||", read("a.end"))
        assertEquals("a|BEGIN|||", read("a.begin"))
        assertEquals("a|BEGIN|||", read("a.beginEvent"))
        assertEquals("a|END|||", read("a.endEvent"))
        assertEquals("a|END||+|1s", read("a.end+1s"))
        assertEquals("a|END||-|1s", read("a.end - 1s"))
        assertEquals("a|END||+|1s", read("a.end +\n 1s"))
        assertEquals("a.b|END|||", read("a.b.end"))
        assertEquals("a.end|END|||", read("a.end.end"))
        assertEquals("my-id|BEGIN||+|2.5s", read("my-id.begin+2.5s"))
        assertEquals("a|REPEAT|3|+|1s", read("a.repeat(3)+1s"))
        assertEquals("a|REPEAT|3|||".dropLast(1), read("a.repeat( 3 )"))
        assertNull(read("a.click"))
        assertNull(read("a.mouseover+1s"))
        assertNull(read(".end"))
        assertNull(read("a.end+"))
        assertNull(read("a.endx"))
        assertNull(read("a\n.end"))
        assertNull(read("a.end+1\ns"))
    }

    // Used to go on for over a minute in the parser's own loop, where nothing can stop it.
    @Test
    fun `an element with too many attributes is refused before the parser sees them`() {
        val attributes = """a="" """.repeat(200_000)
        for (input in listOf(wrap("", attributes).toByteArray(), wrap("", attributes).toByteArray(Charsets.UTF_16BE))) {
            try {
                quickly(20.0) { SvgParser.parse(input) }
                fail("expected a refusal")
            } catch (e: SvgParseException) {
                // UTF-16 with no mark and no declaration is not something a file can be read as at all, so it is refused as broken.
                assertTrue(
                    e.reason.toString(),
                    e.reason == SvgParseException.Reason.TOO_COMPLEX || e.reason == SvgParseException.Reason.MALFORMED,
                )
            }
        }
        val marked = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + wrap("", attributes).toByteArray(Charsets.UTF_16BE)
        try {
            quickly(20.0) { SvgParser.parse(marked) }
            fail("expected a refusal")
        } catch (e: SvgParseException) {
            assertEquals(SvgParseException.Reason.TOO_COMPLEX, e.reason)
        }
        try {
            quickly(20.0) { SvgParser.parse(wrap("", attributes)) }
            fail("expected a refusal")
        } catch (e: SvgParseException) {
            assertEquals(SvgParseException.Reason.TOO_COMPLEX, e.reason)
        }
    }

    @Test
    fun `only real attributes count against that limit`() {
        val equals = "=".repeat(100_000)
        val fine =
            wrap(
                """<!-- $equals --><title>$equals</title><?pi $equals?><text x="0" y="50"><![CDATA[$equals]]></text>
                <rect width="100" height="100" fill="red" data-x="$equals" data-y='$equals'/>""",
            )
        parse(fine).draw().first.assertPixel(50, 50, RED)
        val many = (0 until 10_000).joinToString(" ") { """data-$it="$it"""" }
        parse(wrap("""<rect width="100" height="100" fill="red" $many/>""")).draw().first.assertPixel(50, 50, RED)
    }

    // Used to take 17.82 seconds for 2 MB, joining the text once for every entity.
    @Test
    fun `text made of very many pieces takes no time`() {
        val document = quickly(30.0) { parse(wrap("""<text font-size="1">${"&amp;".repeat(4_000_000)}</text>""")) }
        assertNotNull(document)
        val style = "<style>" + "/**/<![CDATA[ ]]>".repeat(200_000) + "rect{fill:red}</style>" + """<rect width="100" height="100"/>"""
        quickly(30.0) { parse(wrap(style)) }.draw().first.assertPixel(50, 50, RED)
    }

    @Test
    fun `text around other elements stays in its place`() {
        val f = drawn("""<text x="0" y="50" font-size="40">a&amp;b<tspan>c</tspan>&lt;<![CDATA[d]]><title>not this</title>e</text>""")
        assertTrue(f.paintedPixels() > 0)
    }

    // Each used to still be running after 60 seconds, from between 58 KB and 1.3 MB.
    @Test
    fun `a universal rule listed thousands of times over thousands of elements stops`() {
        val document =
            quickly { SvgParser.parse(wrap("<style>${"*,".repeat(10_000)}*{fill:red}</style>" + "<a/>".repeat(10_000) + redLast), small) }
        assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
        assertRedLast(document)
    }

    @Test
    fun `counting siblings of a type is only done once`() {
        // Every second rect, and only those, turns blue. That the last of them lands on this one shows the count is right.
        val document = quickly(30.0) { parse(wrap("<style>rect:nth-of-type(2n){fill:blue}</style>" + "<rect/>".repeat(40_001) + redLast)) }
        assertFalse(SvgWarning.LIMIT_REACHED in document.warnings)
        document.draw().first.assertPixel(50, 50, BLUE)
        assertEquals(
            BLUE,
            drawn("""<style>rect:nth-last-of-type(2){fill:blue}</style><rect width="100" height="100"/><rect/>""").getPixel(50, 50),
        )
    }

    @Test
    fun `thousands of variables on thousands of elements stop`() {
        val many = (0 until 20_000).joinToString(";") { "--a$it:1" }
        val document = quickly { SvgParser.parse(wrap("<style>*{$many}</style>" + "<a/>".repeat(20_000) + redLast), small) }
        assertRedLast(document)
    }

    @Test
    fun `keyframes multiplied by their own blocks stop`() {
        val keys = "0%,".repeat(10_000)
        val values = "x:1;".repeat(10_000)
        val document =
            quickly { SvgParser.parse(wrap("<style>@keyframes a{${keys}0%{$values}} svg{animation:a 1s}</style>$redLast"), small) }
        assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
        assertRedLast(document)
    }

    @Test
    fun `long lists of animations are not split again for every entry`() {
        val names = "a,".repeat(100_000) + "a"
        val times = "1s,".repeat(100_000) + "1s"
        val easings = "linear,".repeat(100_000) + "linear"
        val style =
            "@keyframes a{from{x:1px}to{x:2px}} svg{animation-name:$names;animation-duration:$times;" +
                "animation-timing-function:$easings;animation-delay:$times}"
        val document = quickly { parse(wrap("<style>$style</style>$redLast")) }
        assertNotNull(document)
    }

    @Test
    fun `a long shape on every element is only read once`() {
        val polygon = "polygon(" + "0 0,".repeat(50_000) + "0 0)"
        val document = quickly { parse(wrap("<style>a{clip-path:$polygon}</style>" + "<a/>".repeat(262_000))) }
        assertNotNull(document)
    }

    // Used to compare every begin with every end, 4.3 billion times for each element.
    @Test
    fun `an end list as long as its begin list takes no time`() {
        val body = StringBuilder("""<animate id="a0" attributeName="x" to="1" dur="1s" begin="0;1"/>""")
        for (i in 1..16) {
            val step = 1 shl i
            body.append("""<animate id="a$i" attributeName="x" to="1" dur="1s" begin="a${i - 1}.begin;a${i - 1}.begin+$step"/>""")
        }
        repeat(61) { body.append("""<animate attributeName="x" to="1" dur="1s" begin="a16.begin" end="a16.begin-10000000;indefinite"/>""") }
        val document = quickly { parse(wrap("<rect>$body</rect>$redLast")) }
        assertRedLast(document)
    }

    @Test
    fun `many references to a very long list of times stop`() {
        val body = StringBuilder("""<animate id="a0" attributeName="x" to="1" dur="1s" begin="0;1"/>""")
        for (i in 1..16) {
            val step = 1 shl i
            body.append("""<animate id="a$i" attributeName="x" to="1" dur="1s" begin="a${i - 1}.begin;a${i - 1}.begin+$step"/>""")
        }
        val entries = (0 until 40).joinToString(";") { "a16.begin+$it" }
        repeat(80) { body.append("""<animate attributeName="x" to="1" dur="1s" begin="$entries"/>""") }
        val document = quickly { parse(wrap("<rect>$body</rect>$redLast")) }
        assertRedLast(document)
        val cut = quickly { SvgParser.parse(wrap("<rect>$body</rect>$redLast"), small) }
        assertTrue(SvgWarning.LIMIT_REACHED in cut.warnings)
    }

    @Test
    fun `an animation that keeps moving itself earlier gives up without using everything up`() {
        val spinning = """<rect width="100" height="100"><animate id="a" attributeName="x" to="1" begin="0s;a.begin-1s" dur="1s"/></rect>"""
        val document = quickly(20.0) { parse(wrap(spinning + redLast)) }
        assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
        assertRedLast(document)
        val many = (0 until 100_000).joinToString("") { """<animate attributeName="x" to="1" begin="$it" dur="1s"/>""" }
        val fine = parse(wrap("""<rect width="100" height="100">$many</rect>"""))
        assertFalse(SvgWarning.LIMIT_REACHED in fine.warnings)
    }

    // Used to give every image the full size again, at each of four levels: minutes from a file of 10 KB.
    @Test
    fun `pictures nested in pictures share one allowance`() {
        val comment = "a".repeat(32 * 1024 * 1024)
        val inner = gzip("""<svg xmlns="http://www.w3.org/2000/svg"><!--$comment--></svg>""".toByteArray())
        val uri = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(inner)
        val level1 = wrap("""<image href="$uri"/>""".repeat(32))
        val outerUri = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(gzip(level1.toByteArray()))
        val file = gzip(wrap("""<image href="$outerUri"/>""".repeat(32) + redLast).toByteArray())
        val document = quickly { SvgParser.parse(file) }
        assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
        assertRedLast(document)
    }

    @Test
    fun `an ordinary picture nested in a picture still draws`() {
        val inner = wrap(redLast)
        val uri = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(gzip(inner.toByteArray()))
        val document = parse(wrap("""<image href="$uri" width="100" height="100"/>""".repeat(10)))
        assertTrue(document.warnings.isEmpty())
        val (bitmap, complete) = document.draw()
        assertTrue(complete)
        bitmap.assertPixel(50, 50, RED)
    }

    // Used to ignore the node allowance altogether, which is a trillion paths from 6 KB.
    @Test
    fun `clip paths that multiply are worked out once`() {
        val body = StringBuilder("""<defs><clipPath id="c0"><rect width="100" height="100"/></clipPath>""")
        for (i in 1..12) {
            body.append("""<clipPath id="c$i">""")
            repeat(10) { body.append("""<rect width="100" height="100" clip-path="url(#c${i - 1})"/>""") }
            body.append("</clipPath>")
        }
        body.append("""</defs><rect width="100" height="100" fill="red" clip-path="url(#c12)"/>""")
        val document = parse(wrap(body.toString()))
        quickly { document.draw() }
    }

    @Test
    fun `clip paths that all refer to each other do not hang`() {
        val body = StringBuilder("<defs>")
        for (i in 0 until 14) {
            body.append("""<clipPath id="c$i">""")
            for (j in 0 until 14) if (j != i) body.append("""<rect width="100" height="100" clip-path="url(#c$j)"/>""")
            body.append("</clipPath>")
        }
        body.append("""</defs><rect width="100" height="100" fill="red" clip-path="url(#c0)"/>$redLast""")
        val (bitmap, _) = quickly { parse(wrap(body.toString())).draw() }
        bitmap.assertPixel(50, 50, RED)
    }

    /** Ten levels of ten references: ten billion of whatever the leaf is, from next to nothing. */
    private fun bomb(leaf: String, levels: Int = 10): String {
        val body = StringBuilder("""<defs><g id="l0">$leaf</g>""")
        for (i in 1..levels) {
            body.append("""<g id="l$i">""")
            repeat(10) { body.append("""<use href="#l${i - 1}"/>""") }
            body.append("</g>")
        }
        return body.append("""</defs><use href="#l$levels"/>""").toString()
    }

    private fun assertCutShort(document: SvgDocument, size: Int = 100) {
        val (bitmap, complete) = quickly { document.draw(size) }
        assertFalse(complete)
        assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
        // What was drawn before it is still there.
        bitmap.assertPixel(size / 2, size / 2, RED)
    }

    private val redFirst = """<rect width="10000" height="10000" fill="red"/>"""

    // Used to take 75 seconds for one frame, from a 1 KB file.
    @Test
    fun `a frame of far too many rectangles stops and keeps what it drew`() {
        assertCutShort(SvgParser.parse(wrap(redFirst + bomb("""<rect width="1" height="1" fill="#0f0"/>""")), small))
    }

    @Test
    fun `the default allowance holds too`() {
        assertCutShort(
            SvgParser.parse(
                wrap(redFirst + bomb("""<rect width="1" height="1" fill="#0f0"/>"""), """width="1000" height="1000""""),
                device,
            ),
            1000,
        )
    }

    @Test
    fun `group opacity does not get around the allowance`() {
        val leaf = """<g opacity=".5"><rect width="1" height="1"/><rect width="1" height="1"/></g>"""
        assertCutShort(SvgParser.parse(wrap(redFirst + bomb(leaf), """width="1000" height="1000""""), small), 1000)
    }

    @Test
    fun `masks do not get around the allowance`() {
        val leaf = """<mask id="m"><rect width="1" height="1" fill="#fff"/></mask><rect width="1" height="1" mask="url(#m)"/>"""
        assertCutShort(SvgParser.parse(wrap(redFirst + bomb(leaf), """width="1000" height="1000""""), small), 1000)
    }

    @Test
    fun `masks, patterns and markers that all refer to each other stop`() {
        fun everyOther(tag: String, attributes: String, reference: (Int) -> String): String {
            val out = StringBuilder("<defs>")
            for (i in 0 until 14) {
                out.append("""<$tag id="x$i" $attributes>""")
                for (j in 0 until 14) if (j != i) out.append(reference(j))
                out.append("</$tag>")
            }
            return out.append("</defs>").toString()
        }
        val masks = everyOther("mask", "") { """<rect width="100" height="100" fill="#fff" mask="url(#x$it)"/>""" }
        val patterns =
            everyOther(
                "pattern",
                """width="50" height="50" patternUnits="userSpaceOnUse"""",
            ) { """<rect width="50" height="50" fill="url(#x$it)"/>""" }
        val markers =
            everyOther(
                "marker",
                """markerWidth="5" markerHeight="5"""",
            ) { """<path d="M0 0L1 1" stroke="#000" marker-start="url(#x$it)"/>""" }
        assertCutShort(SvgParser.parse(wrap(redFirst + masks + """<rect width="50" height="50" mask="url(#x0)"/>"""), small))
        // Each tile is kept once it is drawn, which already takes the multiplying out of these.
        quickly { SvgParser.parse(wrap(redFirst + patterns + """<rect width="50" height="50" fill="url(#x0)"/>"""), small).draw() }
        assertCutShort(SvgParser.parse(wrap(redFirst + markers + """<path d="M0 0L1 1" stroke="#000" marker-start="url(#x0)"/>"""), small))
    }

    @Test
    fun `transforms added up one by one stop`() {
        val track = """<animateTransform attributeName="transform" type="translate" from="0" to="1" dur="1s" additive="sum"/>"""
        val document =
            quickly { SvgParser.parse(wrap(redFirst + """<rect width="10" height="10">${track.repeat(100_000)}</rect>"""), small) }
        assertCutShort(document)
    }

    @Test
    fun `the same file stops in the same place every time`() {
        val document = SvgParser.parse(wrap(bomb("""<rect width="1" height="1" fill="#0f0" transform="translate(50)"/>""")), small)
        assertSamePixels(document.draw().first, document.draw().first)
    }

    /** Nothing about it is a warning yet: it is allowed all it asks for, which is a hundred million rectangles. */
    private fun slowFrame(): SvgDocument =
        SvgParser.parse(
            wrap(redFirst + bomb("""<rect width="1" height="1" fill="#0f0"/>""", levels = 8), """width="1000" height="1000""""),
            device.copy(maxRenderWork = 100_000_000_000L, maxRenderSeconds = 600.0, maxRenderedNodes = Int.MAX_VALUE),
        )

    // Used to ignore an interrupt for all of a 40 second test.
    @Test
    fun `interrupting the thread stops a frame soon after`() {
        val document = slowFrame()
        val result = AtomicReference<Boolean>()
        val started = CountDownLatch(1)
        val thread =
            Thread {
                val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
                started.countDown()
                result.set(document.render(Canvas(bitmap), 0.0, RectF(0f, 0f, 1000f, 1000f)))
            }
        thread.start()
        started.await()
        Thread.sleep(200)
        thread.interrupt()
        thread.join(10_000)
        assertFalse(thread.isAlive)
        assertEquals(false, result.get())
        // Being asked to stop is not the file being too much.
        assertFalse(SvgWarning.LIMIT_REACHED in document.warnings)
    }

    @Test
    fun `asking to stop stops a frame soon after`() {
        val document = slowFrame()
        val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        val calls = AtomicInteger()
        val complete = quickly(10.0) { document.render(Canvas(bitmap), 0.0, RectF(0f, 0f, 1000f, 1000f)) { calls.incrementAndGet() > 10 } }
        assertFalse(complete)
        bitmap.assertPixel(500, 500, RED)
    }

    @Test
    fun `cancelling a coroutine stops a frame soon after`() {
        val document = slowFrame()
        val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        val finishedNormally = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val started = CountDownLatch(1)
        runBlocking {
            val job =
                launch(Dispatchers.Default) {
                    started.countDown()
                    try {
                        document.renderCancellable(Canvas(bitmap), 0.0, RectF(0f, 0f, 1000f, 1000f))
                        finishedNormally.set(true)
                    } catch (e: CancellationException) {
                        cancelled.set(true)
                        throw e
                    }
                }
            started.await()
            Thread.sleep(200)
            quickly(10.0) {
                job.cancel()
                runBlocking { job.join() }
            }
        }
        assertTrue(cancelled.get())
        assertFalse(finishedNormally.get())
        bitmap.assertPixel(500, 500, RED)
    }

    @Test
    fun `a stopped frame does not have to wait for its turn`() {
        val document = slowFrame()
        val first =
            Thread {
                val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
                document.render(Canvas(bitmap), 0.0, RectF(0f, 0f, 1000f, 1000f))
            }
        first.start()
        Thread.sleep(200)
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val stop = AtomicBoolean(false)
        val second =
            Thread {
                document.render(Canvas(bitmap), 0.0, RectF(0f, 0f, 10f, 10f)) { stop.get() }
            }
        second.start()
        Thread.sleep(100)
        stop.set(true)
        second.join(10_000)
        assertFalse(second.isAlive)
        first.interrupt()
        first.join(10_000)
    }

    // Used to take 52 seconds and a gigabyte, in one native call, from 200 bytes.
    @Test
    fun `dashes too short to see are not drawn one by one`() {
        val line =
            """<path d="M0 0C100 0 100 100 0 100" fill="none" stroke="red" """ +
                """stroke-width="3" stroke-linecap="round" stroke-dasharray=".0002 .0002"/>"""
        val document = parse(wrap(line))
        val (bitmap, complete) = quickly(10.0) { document.draw() }
        assertFalse(complete)
        assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
        assertTrue(bitmap.paintedPixels() > 0)
        quickly(
            20.0,
        ) { parse(wrap("""<defs>$line</defs>""".replace("<path", """<path id="p"""") + """<use href="#p"/>""".repeat(10))).draw() }
    }

    @Test
    fun `dashes of a usable size are all still there`() {
        val document = parse(wrap("""<line x1="0" y1="50" x2="100" y2="50" stroke="red" stroke-width="10" stroke-dasharray="10 10"/>"""))
        val (bitmap, complete) = document.draw()
        assertTrue(complete)
        bitmap.assertPixel(5, 50, RED)
        assertEquals(CLEAR, bitmap.getPixel(15, 50))
    }

    private fun scribble(segments: Int, range: Int, seed: Long = 1L): String {
        val random = Random(seed)
        val out = StringBuilder("M0 0")
        repeat(segments) {
            out
                .append('L')
                .append(random.nextInt(range))
                .append(' ')
                .append(random.nextInt(range))
        }
        return out.toString()
    }

    // Used to still be running after 120 seconds, both as a fill and as a stroke, in one native call.
    @Test
    fun `a scribble of a million long lines is left out and the rest draws`() {
        val d = scribble(1_000_000, 1000)
        for (paint in listOf("""fill="#00f"""", """fill="none" stroke="#00f" stroke-linejoin="round" stroke-linecap="round"""")) {
            val document =
                quickly {
                    parse(
                        wrap("""<path d="$d" $paint/><rect width="1000" height="1000" fill="red"/>""", """width="1000" height="1000""""),
                    )
                }
            val (bitmap, complete) = quickly(30.0) { document.draw(1000) }
            assertFalse(complete)
            assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
            bitmap.assertPixel(500, 500, RED)
        }
    }

    @Test
    fun `a long winding line of a million short pieces still draws`() {
        val random = Random(1)
        val d = StringBuilder("M500 500")
        var x = 500
        var y = 500
        repeat(1_000_000) {
            x = (x + random.nextInt(7) - 3).coerceIn(0, 1000)
            y = (y + random.nextInt(7) - 3).coerceIn(0, 1000)
            d
                .append('L')
                .append(x)
                .append(' ')
                .append(y)
        }
        val document = parse(wrap("""<path d="$d" fill="red" stroke="red"/>""", """width="1000" height="1000""""))
        val (_, complete) = quickly(30.0) { document.draw(1000) }
        assertTrue(complete)
    }

    // Used to take half a minute at a thousand segments and four gigabytes at five thousand, on every frame.
    @Test
    fun `combining huge clip paths is not attempted`() {
        for (segments in listOf(2000, 5000)) {
            val clip =
                """<defs><clipPath id="c"><path d="${scribble(
                    segments,
                    100,
                    1,
                )}"/><path d="${scribble(segments, 100, 2)}"/></clipPath></defs>
                <rect width="100" height="100" fill="red" clip-path="url(#c)"/>"""
            val document = parse(wrap(clip))
            val (bitmap, complete) = quickly(30.0) { document.draw() }
            assertFalse(complete)
            assertTrue(bitmap.paintedPixels() > 0)
        }
    }

    @Test
    fun `clip paths that do not touch can have any number of points`() {
        val circle = { cx: Int ->
            (0 until 1000).joinToString("L", "M") { i -> "${cx + 5 * cos(i / 159.0)} ${50 + 40 * sin(i / 159.0)}" } +
                "Z"
        }
        val clip =
            """<defs><clipPath id="c"><path d="${circle(20)}"/><path d="${circle(40)}"/><path d="${circle(60)}"/></clipPath></defs>
            <rect width="100" height="100" fill="red" clip-path="url(#c)"/>"""
        val (bitmap, complete) = parse(wrap(clip)).draw()
        assertTrue(complete)
        bitmap.assertPixel(40, 50, RED)
        assertEquals(CLEAR, bitmap.getPixel(30, 50))
    }

    @Test
    fun `combining ordinary clip paths still works`() {
        val clip =
            """<defs><clipPath id="c"><rect width="50" height="100"/><rect x="25" width="25" height="100"/></clipPath></defs>
            <rect width="100" height="100" fill="red" clip-path="url(#c)"/>"""
        val (bitmap, complete) = parse(wrap(clip)).draw()
        assertTrue(complete)
        bitmap.assertPixel(25, 50, RED)
        assertEquals(CLEAR, bitmap.getPixel(75, 50))
    }

    // Used to take 50 seconds for one frame of 5000 letters, measuring all of them again for each one.
    @Test
    fun `long text on a path takes no time`() {
        val text = """<defs><path id="p" d="M0 50H100000000"/></defs><text font-size="1"><textPath href="#p">${"a".repeat(
            10_000,
        )}</textPath></text>"""
        val document = parse(wrap(text))
        quickly(30.0) { document.draw() }
    }

    // Used to grow by 16 MB with every frame, out of sight of anything that could catch it.
    @Test
    fun `an animated letter spacing does not make the cache grow`() {
        val text =
            """<text font-size="10" y="50" letter-spacing="1">""" +
                """<animate attributeName="letter-spacing" from="0" to="1000" dur="1000s"/>${"abcd".repeat(
                    5000,
                )}</text>"""
        val limit = 64L * 1024 * 1024
        val document = SvgParser.parse(wrap(text), device.copy(maxGlyphCacheBytes = limit))
        quickly {
            for (i in 0 until 100) {
                document.draw(time = i * 0.04)
                assertTrue(document.model.glyphs.heldBytes <= limit)
            }
        }
        // At 20 KB of outlines for each of those letters this is about three frames.
        assertTrue(
            document.model.glyphs.entryCount
                .toString(),
            document.model.glyphs.entryCount < 10,
        )
    }

    @Test
    fun `text the same on every frame stays in the cache`() {
        val document = parse(wrap("""<text font-size="10" y="50"><animate attributeName="x" from="0" to="50" dur="10s"/>hello</text>"""))
        for (i in 0 until 30) document.draw(time = i * 0.1)
        assertEquals(1, document.model.glyphs.entryCount)
    }

    // Used to go up to 7 GB of native memory, which no error warns of, from 4 KB of .svgz.
    @Test
    fun `text with far too many letters is cut short with a warning`() {
        val text = """<text font-size="1" y="50">${"a".repeat(4_000_000)}</text>"""
        val document = parse(wrap(text))
        val (bitmap, complete) = quickly { document.draw() }
        assertFalse(complete)
        assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
        assertTrue(bitmap.paintedPixels() > 0)
        assertTrue(document.model.glyphs.heldBytes <= device.maxGlyphCacheBytes)
    }

    // Used to make nine million paths, so that a thread with nothing to do with this ran out of memory.
    @Test
    fun `text with too many decorations leaves memory for everyone else`() {
        val xs = (0 until 3000).joinToString(" ")
        val lines = "underline ".repeat(3000)
        val nesting = (0 until 400).joinToString("") { """<tspan text-decoration="underline line-through overline">""" }
        val text =
            """<text font-size="1" y="50" x="$xs" text-decoration="$lines">${"a".repeat(3000)}</text>
            <text font-size="1" y="50" x="$xs">$nesting${"a".repeat(3000)}${"</tspan>".repeat(400)}</text>"""
        val document = parse(wrap(text, """width="1000" height="1000""""))
        val problem = AtomicReference<Throwable?>()
        val stop = AtomicBoolean(false)
        val other =
            Thread {
                try {
                    val kept = ArrayList<ByteArray>()
                    while (!stop.get()) {
                        kept += ByteArray(1024)
                        if (kept.size > 1000) kept.clear()
                    }
                } catch (e: OutOfMemoryError) {
                    problem.set(e)
                }
            }
        other.start()
        quickly { document.draw(1000) }
        stop.set(true)
        other.join()
        assertNull(problem.get())
    }

    // Used to take 55 seconds and 13 GB for 200 shapes, with a tile of 67 MB for each.
    @Test
    fun `shapes with one pattern share one tile`() {
        val pattern =
            """<defs><pattern id="p" width="1e9" height="1e9" patternUnits="userSpaceOnUse">""" +
                """<rect width="1e9" height="1e9" fill="#00f"/></pattern></defs>"""
        val document = parse(wrap(pattern + """<rect width="100" height="100" fill="url(#p)"/>""".repeat(2000)))
        val (bitmap, _) = quickly(30.0) { document.draw() }
        bitmap.assertPixel(50, 50, BLUE)
    }

    @Test
    fun `patterns nested by reference stay within the memory allowed`() {
        val body =
            StringBuilder(
                """<defs><pattern id="p0" width="1e9" height="1e9" patternUnits="userSpaceOnUse">""" +
                    """<rect width="1e9" height="1e9" fill="#00f"/></pattern>""",
            )
        for (i in 1..200) {
            body.append(
                """<pattern id="p$i" width="1e9" height="1e9" patternUnits="userSpaceOnUse">""" +
                    """<rect width="1e9" height="1e9" fill="url(#p${i - 1})"/></pattern>""",
            )
        }
        body.append("""</defs><rect width="1000" height="1000" fill="url(#p200)"/><rect width="1000" height="1000" fill="red"/>""")
        val limits = device.copy(maxLiveRasterBytes = 64L * 1024 * 1024)
        val document = SvgParser.parse(wrap(body.toString(), """width="1000" height="1000""""), limits)
        val (bitmap, complete) = quickly { document.draw(1000) }
        assertFalse(complete)
        assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
        bitmap.assertPixel(500, 500, RED)
    }

    /** A real picture of nothing but zeros, which takes the same to unpack as any other of its size and compresses to almost nothing. */
    private fun bigPng(side: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        fun chunk(type: String, data: ByteArray) {
            val typeBytes = type.toByteArray()
            val crc = CRC32()
            crc.update(typeBytes)
            crc.update(data)
            fun int(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
            out.write(int(data.size))
            out.write(typeBytes)
            out.write(data)
            out.write(int(crc.value.toInt()))
        }

        fun int(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
        chunk("IHDR", int(side) + int(side) + byteArrayOf(8, 0, 0, 0, 0))
        val packed = ByteArrayOutputStream()
        DeflaterOutputStream(packed, Deflater(9)).use { deflater ->
            val row = ByteArray(side + 1)
            repeat(side) { deflater.write(row) }
        }
        chunk("IDAT", packed.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    // Used to keep all of them, 256 MB each, for as long as the document lived.
    @Test
    fun `images are held only up to a total`() {
        val uri = "data:image/png;base64," + Base64.getEncoder().encodeToString(bigPng(4096))
        val images = """<image width="100" height="100" href="$uri"/>""".repeat(8)
        val limits = device.copy(maxDecodedImagePixels = 20_000_000L, maxImagePixels = 17_000_000L)
        val document = SvgParser.parse(wrap(images), limits)
        val (_, complete) = quickly { document.draw() }
        assertTrue(complete)
        assertTrue(document.model.resources.images.pixels <= 20_000_000L)
    }

    @Test
    fun `an image too big for its limit is decoded smaller and keeps its size`() {
        val uri = "data:image/png;base64," + Base64.getEncoder().encodeToString(bigPng(1024))
        val document = SvgParser.parse(
            wrap("""<image x="0" y="0" href="$uri"/>""", """width="2000" height="2000""""),
            device.copy(maxImagePixels = 300_000L),
        )
        val (bitmap, complete) = document.draw(2000)
        assertTrue(complete)
        // Black, up to the size the file says it has, and nothing past that. Decoded at half size, it would have stopped half way.
        bitmap.assertPixel(1000, 1000, BLACK)
        assertEquals(CLEAR, bitmap.getPixel(1100, 1100))
    }

    @Test
    fun `an image that takes too long to decode is left out with a warning`() {
        val uri = "data:image/png;base64," + Base64.getEncoder().encodeToString(bigPng(2048))
        val images = """<image width="100" height="100" href="$uri"/>"""
        val document = SvgParser.parse(wrap(images + redLast), device.copy(maxRenderWork = 1_000_000L, maxDecodedImagePixels = 5_000_000L))
        val (bitmap, complete) = document.draw()
        assertFalse(complete)
        bitmap.assertPixel(50, 50, RED)
    }

    // Used to take 3.41 seconds a frame for 20000 links, going through the chain again for every shape.
    @Test
    fun `a long chain of gradients stops at a limit`() {
        val body = StringBuilder("<defs>")
        for (i in 0 until 200_000) body.append("""<linearGradient id="g$i" href="#g${i + 1}"/>""")
        body.append("""<linearGradient id="g200000"><stop stop-color="red"/><stop offset="1" stop-color="blue"/></linearGradient></defs>""")
        body.append("""<rect width="100" height="100" fill="url(#g0)"/>""".repeat(2000))
        val document = quickly { parse(wrap(body.toString())) }
        val (_, complete) = quickly(30.0) { document.draw() }
        assertFalse(complete)
        assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
    }

    @Test
    fun `a short chain of gradients still works`() {
        val f =
            drawn(
                """<defs><linearGradient id="a" href="#b"/><linearGradient id="b" href="#c"/>
                <linearGradient id="c"><stop stop-color="red"/><stop offset="1" stop-color="red"/></linearGradient></defs>
                <rect width="100" height="100" fill="url(#a)"/>""",
            )
        f.assertPixel(50, 50, RED)
    }

    // Used to take 37 seconds for one frame.
    @Test
    fun `a gradient with very many stops stays usable`() {
        val stops = (0 until 200_000).joinToString("") { """<stop offset="${it / 200_000.0}" stop-color="red"/>""" }
        val document = parse(
            wrap(
                """<defs><linearGradient id="g">$stops</linearGradient></defs>""" +
                    """<rect width="100" height="100" fill="url(#g)"/>""".repeat(100),
            ),
        )
        val (bitmap, complete) = quickly(30.0) { document.draw() }
        assertFalse(complete)
        bitmap.assertPixel(50, 50, RED)
    }

    // Used to reach 1650 MB of native memory at this size, and would have gone on to 8 GB at 2000 by 2000.
    @Test
    fun `layers inside layers stop at the memory allowed`() {
        val deep =
            """<g opacity=".5">""".repeat(300) + """<rect width="1000" height="1000"/><rect width="500" height="500"/>""" +
                "</g>".repeat(300)
        val limits = device.copy(maxLiveRasterBytes = 16L * 1024 * 1024)
        val document = SvgParser.parse(wrap(redFirst + deep, """width="1000" height="1000""""), limits)
        val (bitmap, complete) = quickly { document.draw(1000) }
        assertFalse(complete)
        assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
        bitmap.assertPixelNear(750, 750, RED, 8)
    }

    @Test
    fun `a layer that only holds a lone shape takes only the room of that shape`() {
        // Thousands of these used to take a canvas-sized layer each, which ran out the allowance on a perfectly ordinary map.
        val shapes = """<rect x="10" y="10" width="2" height="2" fill="red" stroke="red" opacity=".5"/>""".repeat(5000)
        val document = parse(wrap(shapes, """width="1000" height="1000""""))
        val (_, complete) = quickly(30.0) { document.draw(1000) }
        assertTrue(complete)
    }

    @Test
    fun `a layer around a lone shape keeps all of its stroke`() {
        val miter = """<path d="M20 80L50 10L80 80" fill="#00f" stroke="red" stroke-width="10" stroke-miterlimit="10" opacity=".999"/>"""
        val plain = """<path d="M20 80L50 10L80 80" fill="#00f" stroke="red" stroke-width="10" stroke-miterlimit="10"/>"""
        assertSamePixels(drawn(plain), drawn(miter))
        val capped =
            """<line x1="20" y1="50" x2="80" y2="50" stroke="red" stroke-width="30" """ +
                """stroke-linecap="square" marker-end="url(#none)" fill="#00f" opacity=".999"/>"""
        assertSamePixels(drawn(capped.replace(""" opacity=".999"""", "")), drawn(capped))
    }

    @Test
    fun `a mask only takes the room of what it can show`() {
        val shapes =
            """<mask id="m"><rect width="100" height="100" fill="#fff"/></mask>""" +
                """<rect width="2" height="2" fill="red" mask="url(#m)"/>""".repeat(5000)
        val document = parse(wrap(shapes, """width="1000" height="1000""""))
        val (bitmap, complete) = quickly(30.0) { document.draw(1000) }
        assertTrue(complete)
        bitmap.assertPixel(1, 1, RED)
    }

    @Test
    fun `a few layers inside layers draw as they should`() {
        val f = drawn("""<g opacity=".5"><g opacity=".5"><rect width="100" height="100" fill="red"/></g></g>""")
        f.assertPixelNear(50, 50, android.graphics.Color.argb(64, 255, 0, 0), 2)
    }

    // Used to lose everything after a shape with one of these numbers in its gradient.
    @Test
    fun `an odd number in a gradient loses nothing else`() {
        val two = """<stop stop-color="red"/><stop offset="1" stop-color="red"/>"""
        for (gradient in listOf(
            """<radialGradient id="g" r="1e-50">$two</radialGradient>""",
            """<radialGradient id="g" cx="1e50">$two</radialGradient>""",
            """<radialGradient id="g" fx="1e50">$two</radialGradient>""",
            """<radialGradient id="g" fr="1e50">$two</radialGradient>""",
            """<linearGradient id="g" gradientUnits="userSpaceOnUse" x2="1e50">$two</linearGradient>""",
            """<linearGradient id="g" gradientUnits="userSpaceOnUse" x2="calc(1e308px*10*0)">$two</linearGradient>""",
            """<linearGradient id="g" gradientUnits="userSpaceOnUse" x1="1e50" x2="1e50" y2="1e-50">$two</linearGradient>""",
        )) {
            val document =
                parse(
                    wrap(
                        """<defs>$gradient</defs><rect width="10" height="10" fill="url(#g)"/>""" +
                            """<rect x="50" width="50" height="100" fill="red"/>""",
                    ),
                )
            val (bitmap, complete) = document.draw()
            assertTrue(gradient, complete)
            bitmap.assertPixel(75, 50, RED)
        }
    }

    // Used to throw while drawing, which lost everything after it.
    @Test
    fun `key points that do not pair with key times switch the animation off`() {
        for (attributes in listOf(
            """calcMode="linear" keyPoints="0;.2;.5;1" keyTimes="0;.1"""",
            """calcMode="linear" keyPoints="0;.2;.5;1" keyTimes=""""",
            """calcMode="discrete" keyPoints="0;.2;.5;1" keyTimes="0;.1"""",
        )) {
            val motion = """<rect width="10" height="10" fill="#00f"><animateMotion dur="1s" path="M0 0H100" $attributes/></rect>"""
            val document = parse(wrap("""$motion<rect x="50" width="50" height="100" fill="red"/>"""))
            assertTrue(attributes, SvgWarning.INVALID_CONTENT_IGNORED in document.warnings)
            val (bitmap, complete) = document.draw(time = 0.5)
            assertTrue(complete)
            bitmap.assertPixel(5, 5, BLUE)
            bitmap.assertPixel(75, 50, RED)
        }
    }

    @Test
    fun `whatever goes wrong with one element loses only that element`() {
        val shape = """<g transform="translate(1000)" opacity=".5"><rect width="10" height="10" fill="#00f" stroke="#0f0"/></g>"""
        val document = parse(wrap(shape + redLast))
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        var thrown = false
        val canvas =
            object : Canvas(bitmap) {
                override fun drawPath(path: Path, paint: Paint) {
                    if (!thrown) {
                        thrown = true
                        throw IllegalArgumentException("as a shader does for a number it cannot take")
                    }
                    super.drawPath(path, paint)
                }
            }
        val before = canvas.saveCount
        assertFalse(document.render(canvas, 0.0, RectF(0f, 0f, 100f, 100f)))
        assertTrue(thrown)
        assertEquals(before, canvas.saveCount)
        assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
        // In its right place and at full strength, so neither the transform nor the layer of what failed was left behind.
        bitmap.assertPixel(50, 50, RED)
    }

    private fun sizes(attributes: String, body: String = ""): Pair<Float, Float> {
        val document = parse(wrap(body, attributes))
        return document.width to document.height
    }

    // Used to come out as infinity or zero, for the next caller to size a bitmap with.
    @Test
    fun `the size a document reports can always be used`() {
        for ((attributes, description) in listOf(
            """width="1e50" height="1e50"""" to "huge",
            """width="0" height="0"""" to "zero",
            """viewBox="0 0 1e-50 10"""" to "tiny box",
            """viewBox="0 0 1e50 1e50"""" to "huge box",
            """width="1e308" viewBox="0 0 1 1e308"""" to "extreme",
            """width="1e308in" height="1e308in"""" to "past infinity",
        )) {
            val (width, height) = sizes(attributes)
            assertTrue("$description $width", width.isFinite() && width >= 1f && width <= SvgDocument.MAX_REPORTED_SIZE)
            assertTrue("$description $height", height.isFinite() && height >= 1f && height <= SvgDocument.MAX_REPORTED_SIZE)
        }
        assertEquals(800f to 600f, sizes("""width="800" height="600""""))
        val (wide, tall) = sizes("""width="4e6" height="2e6"""")
        assertEquals(2.0f, wide / tall, 0.001f)
    }

    private fun info(body: String): SvgAnimationInfo = parse(wrap("""<rect width="10" height="10">$body</rect>""")).animation

    private fun assertUsable(animation: SvgAnimationInfo) {
        assertTrue(animation.toString(), animation.durationSeconds.isFinite() && animation.durationSeconds in 0.0..MAX_TIME_SECONDS)
        assertTrue(animation.toString(), animation.loopStartSeconds.isFinite() && animation.loopStartSeconds in 0.0..MAX_TIME_SECONDS)
    }

    @Test
    fun `the times a document reports can always be used`() {
        assertUsable(info("""<animate attributeName="x" from="0" to="10" dur="1e308"/>"""))
        assertUsable(info("""<animate attributeName="x" from="0" to="10" dur="1e308h"/>"""))
        assertUsable(info("""<animate attributeName="x" from="0" to="10" dur="1s" begin="1e308"/>"""))
        val tiny =
            """<animate id="a" attributeName="x" to="1" dur="1e-320" begin="0;b.end"/>""" +
                """<animate id="b" attributeName="x" to="1" dur="1e-320" begin="a.end"/>"""
        val shortLoop = info(tiny)
        assertUsable(shortLoop)
        assertTrue(shortLoop.loopsForever)
        assertTrue(shortLoop.durationSeconds >= 0.001)
        val long =
            """<animate id="a" attributeName="x" to="1" dur="1e20" begin="0;b.end"/>""" +
                """<animate id="b" attributeName="x" to="1" dur="1e20" begin="a.end"/>"""
        assertUsable(info(long))
        assertUsable(
            parse(wrap("""<style>@keyframes k{to{x:1px}} rect{animation:k 1e308s 1e308 linear}</style><rect/>""")).animation,
        )
        // Drawing at any moment has to cope with these too.
        parse(wrap("""<rect width="10" height="10">$tiny</rect>""")).draw(time = 5.0)
    }

    @Test
    fun `numbers past infinity count as mistakes`() {
        assertNull(parseClock("1e308h"))
        assertNull(parseClock("1e308:0"))
        assertNull(parseAngle("1e308turn"))
        assertNull(parseLengthOrNull("1e308in"))
        assertNull(parseLengthOrNull("calc(1e308px*10*0)"))
        assertNull(parseLengthOrNull("calc(1e308px*10)"))
    }

    // Used to come back cut short on a small stack, and from there could have overflowed inside native code, which cannot be caught.
    @Test
    fun `chains of references stay well inside a small stack`() {
        fun chain(links: Int): String {
            val body = StringBuilder("<defs>")
            for (i in 0 until links) body.append("""<g id="u$i"><rect width="1" height="1"/><use href="#u${i + 1}"/></g>""")
            return body.append("""</defs><rect width="100" height="100" fill="red"/><use href="#u0"/>""").toString()
        }
        for (links in listOf(100, 500, 8000)) {
            val failure = AtomicReference<Throwable?>()
            val result = AtomicReference<Boolean>()
            val thread =
                Thread(null, {
                    try {
                        val document = parse(wrap(chain(links)))
                        assertEquals(links * 2 > device.maxDepth, SvgWarning.LIMIT_REACHED in document.warnings)
                        val (bitmap, complete) = document.draw()
                        bitmap.assertPixel(50, 50, RED)
                        result.set(complete)
                    } catch (e: Throwable) {
                        failure.set(e)
                    }
                }, "small-stack", halfStack)
            thread.start()
            thread.join()
            failure.get()?.let { throw AssertionError("at $links links", it) }
            assertEquals("at $links links", links * 2 <= device.maxDepth, result.get())
        }
    }

    /** Half of what a thread on a phone gets, so that as much again stays free for the native drawing code at the bottom of it. */
    private val halfStack = 512L * 1024

    @Test
    fun `nesting down to the limit stays well inside a small stack`() {
        val deep = "<g>".repeat(500) + redLast + "</g>".repeat(500)
        val failure = AtomicReference<Throwable?>()
        val thread =
            Thread(null, {
                try {
                    val (bitmap, complete) = parse(wrap(deep)).draw()
                    assertTrue(complete)
                    bitmap.assertPixel(50, 50, RED)
                } catch (e: Throwable) {
                    failure.set(e)
                }
            }, "small-stack", halfStack)
        thread.start()
        thread.join()
        failure.get()?.let { throw AssertionError(it) }
    }

    // Used to say all was well while the picture inside had been cut short.
    @Test
    fun `a picture inside a picture shares the frame it is in`() {
        val inner = wrap("""<g id="r"><rect width="100" height="100" fill="red"/></g>""" + """<use href="#r"/>""".repeat(1000))
        val uri = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(inner.toByteArray())
        val image = """<defs><image id="i" width="100" height="100" href="$uri"/></defs>""" + """<use href="#i"/>""".repeat(50)
        val document = SvgParser.parse(wrap(image), device.copy(maxRenderedNodes = 100))
        val (_, complete) = document.draw()
        assertFalse(complete)
        assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
    }

    // Used to come out negative, get past the check on size, and lose everything after it.
    @Test
    fun `dash lists with a huge common multiple flip instead`() {
        val from = "1 ".repeat(70_002)
        val to = "1 ".repeat(70_004)
        val style = """<style>@keyframes d{from{stroke-dasharray:$from}to{stroke-dasharray:$to}}#l{animation:d 1s}</style>"""
        val document = parse(wrap("""$style<line id="l" x2="50" stroke="#00f"/><rect x="50" width="50" height="100" fill="red"/>"""))
        val (bitmap, complete) = document.draw(time = 0.5)
        assertTrue(complete)
        bitmap.assertPixel(75, 50, RED)
    }

    @Test
    fun `things nested inside themselves in a value stop at a sensible depth`() {
        val brackets = "calc(" + "(".repeat(100_000) + "1px" + ")".repeat(100_000) + ")"
        val mixes = "color-mix(in srgb, ".repeat(10_000) + "red" + ", red)".repeat(10_000)
        val fallbacks = "url(#a) ".repeat(100_000) + "red"
        val nots = ":not(".repeat(10_000) + "a" + ")".repeat(10_000)
        val media = "@media all{".repeat(10_000) + "rect{fill:blue}" + "}".repeat(10_000)
        val body =
            """<style>$nots{fill:blue}$media</style>""" +
                """<rect x="$brackets" fill="$mixes" stroke="$fallbacks" width="100" height="100"/>$redLast"""
        val document = quickly { parse(wrap(body)) }
        assertRedLast(document)
    }

    @Test
    fun `very many classes on one element are not a problem`() {
        val classes = "a ".repeat(2_000_000)
        val document = quickly { parse(wrap("""<style>.a{fill:red}</style><rect class="$classes" width="100" height="100"/>""")) }
        document.draw().first.assertPixel(50, 50, RED)
    }

    // ----- From the review of the merged import -----

    private fun polygonText(points: Int): String =
        buildString {
            append("polygon(")
            repeat(points) { index ->
                if (index > 0) append(',')
                append("0 0")
            }
            append(')')
        }

    private fun heapInUse(): Long {
        val runtime = Runtime.getRuntime()
        repeat(2) { System.gc() }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    // Each track kept its own reading of the same long text, at drawing time, for free: 498 of 512 MB, and it never went away.
    @Test
    fun `one long animated clip shape on many elements is read once, and each mix of it is paid for`() {
        val shape = polygonText(200_000)
        val css = "@keyframes k{from{clip-path:$shape}to{clip-path:$shape}} rect{animation:k 1s infinite linear}"
        val rects = "<rect width='10' height='10'/>".repeat(300)
        val before = heapInUse()
        val document = quickly(120.0) { parse(wrap("<style>$css</style>$rects")) }
        val (_, complete) = quickly(120.0) { document.draw(time = 0.25) }
        val held = heapInUse() - before
        assertFalse("nothing like that much mixing fits in one frame", complete)
        assertTrue("the picture holds on to ${held / (1024 * 1024)} MB", held < Runtime.getRuntime().maxMemory() / 4)
        assertEquals(100f, document.width, 0f)
    }

    @Test
    fun `mixing a long clip shape costs more the longer it is, on every frame`() {
        fun costWith(points: Int): Long {
            val shape = polygonText(points)
            val css = "@keyframes k{from{clip-path:$shape}to{clip-path:$shape}} rect{animation:k 1s infinite linear}"
            val document = parse(wrap("<style>$css</style><rect id='r' width='10' height='10'/>"))
            val tracks = checkNotNull(
                document.model.byId["r"]
                    ?.tracks
                    ?.get("clip-path"),
            )
            return tracks.single().evaluationCost
        }
        assertTrue(costWith(10_000) >= 10 * costWith(100))
        assertTrue(costWith(10_000) >= 10_000 * WorkCost.SHAPE_POINT_MIXED)
    }

    @Test
    fun `many short animated clip shapes hold no more than one reading of each`() {
        val shape = polygonText(2_000)
        val css = "@keyframes k{from{clip-path:$shape}to{clip-path:$shape}} rect{animation:k 1s infinite linear}"
        val rects = "<rect width='10' height='10'/>".repeat(20_000)
        val before = heapInUse()
        val document = quickly(120.0) { SvgParser.parse(wrap("<style>$css</style>$rects"), small) }
        quickly(60.0) { document.draw(time = 0.25) }
        val held = heapInUse() - before
        assertTrue("the picture holds on to ${held / (1024 * 1024)} MB", held < Runtime.getRuntime().maxMemory() / 4)
        assertEquals(100f, document.width, 0f)
    }

    @Test
    fun `a clip shape that is animated still moves`() {
        val document =
            parse(
                wrap(
                    """<style>@keyframes k{from{clip-path:inset(0 100% 0 0)}to{clip-path:inset(0)}} rect{animation:k 1s linear}</style>
                    <rect width="100" height="100" fill="red"/>""",
                ),
            )
        assertEquals(0, android.graphics.Color.alpha(document.draw(time = 0.25).first.getPixel(50, 50)))
        document.draw(time = 0.75).first.assertPixel(50, 50, RED)
    }

    // The loop over rules inside a rule never looked to see whether the allowance had run out, and each pass says the whole selector again: 560 seconds and counting.
    @Test
    fun `nested rules that say a long selector again and again stop soon`() {
        val parent = List(21_000) { "a" }.joinToString(",")
        val css = ":is($parent){" + "b{}".repeat(200_000) + "}"
        val document = quickly(60.0) { parse(wrap("<style>$css</style>$redLast")) }
        assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
        assertRedLast(document)
        assertTrue("selectors must not stay in memory", heapInUse() < Runtime.getRuntime().maxMemory() / 2)
    }

    @Test
    fun `conditions inside a rule that say a long selector again and again stop soon too`() {
        val parent = List(21_000) { "a" }.joinToString(",")
        val css = ":is($parent){" + "@media all{}".repeat(100_000) + "}"
        val document = quickly(60.0) { parse(wrap("<style>$css</style>$redLast")) }
        assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
        assertRedLast(document)
    }

    // Past the length limit, each of these made the whole parent again before finding that out, with nothing to pay.
    @Test
    fun `nested rules under a selector already past the limit do not cost anything to refuse`() {
        val parent = List(33_000) { "a" }.joinToString(",")
        val css = ":is($parent){" + "b{}".repeat(300_000) + "}\n"
        val document = quickly(30.0) { parse(wrap("<style>$css</style>".repeat(4) + redLast)) }
        assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
        assertRedLast(document)
    }

    @Test
    fun `what a stylesheet says before its selectors filled the memory still counts`() {
        val parent = List(21_000) { "a" }.joinToString(",")
        val css = "rect{fill:#0000ff}:is($parent){" + "b{}".repeat(200_000) + "}rect{fill:#00ff00}"
        val document = quickly(60.0) { parse(wrap("<style>$css</style><rect width='100' height='100'/>")) }
        document.draw().first.assertPixel(50, 50, android.graphics.Color.BLUE)
    }

    // Each element read the whole path again on its way to the cache, which it could have read from: all 60 seconds, for what the first one found out.
    @Test
    fun `a long offset path on many elements is read once`() {
        val path = "M0 0" + " L1 1".repeat(200_000)
        val rects = "<rect width='10' height='10'/>".repeat(30_000)
        quickly(40.0) { parse(wrap("<style>rect{offset-path:path('$path');offset-distance:50%}</style>$rects")) }
    }

    @Test
    fun `a long value that is new text on every element stops long before the clock does`() {
        val long = "min(" + List(100_000) { "1px" }.joinToString(",") + ")"
        val rects = (1..20_000).joinToString("") { "<rect style='--i:${it}px' height='10'/>" }
        val document = quickly(50.0) { parse(wrap("<style>rect{--w:var(--i);width:min(var(--w),$long)}</style>$rects")) }
        assertTrue(SvgWarning.LIMIT_REACHED in document.warnings)
    }

    // Unlike any other long list, this one could fill the heap before anything looked, and that refused the whole file.
    @Test
    fun `a colour matrix with millions of numbers loses only the filter`() {
        val values = "1 ".repeat(28_000_000)
        val document =
            quickly(120.0) {
                parse(
                    wrap(
                        """<filter id="f"><feColorMatrix values="$values"/></filter><rect width="100" height="100" fill="red" filter="url(#f)"/>""",
                    ),
                )
            }
        document.draw().first.assertPixel(50, 50, RED)
        assertTrue(SvgWarning.FILTER_IGNORED in document.warnings)
    }

    @Test
    fun `reading stops as soon as nobody is waiting for it`() {
        val parent = List(1_000) { "a" }.joinToString(",")
        val css = ":is($parent){fill:red}".repeat(2_000)
        val text = wrap("<style>$css</style>$redLast").toByteArray()
        // What it has by then is not much, and nobody is going to look at it.
        quickly(10.0) { SvgParser.parse(java.io.ByteArrayInputStream(text), device, shouldStop = { true }) }
    }

    /** The other side of all of the above. No limit may get in the way of a file that someone really made. */
    @Test
    fun `a very large real drawing is left alone`() {
        val random = Random(7)
        val style = StringBuilder("<style>")
        for (i in 0 until 500) {
            style.append(".c$i{fill:#${"%06x".format(random.nextInt(0xFFFFFF))};stroke:#000;stroke-width:.5}")
            style.append("g.l${i % 20} .c$i, path.c$i:not(.x){opacity:.9}")
        }
        style.append("@media (min-width:100px){rect{--a:1;fill:var(--b,#fff)}}</style>")
        val body = StringBuilder(style)
        body.append(
            """<defs><linearGradient id="g"><stop stop-color="#fff"/><stop offset="1" stop-color="#000"/></linearGradient>
            <clipPath id="c"><rect width="1000" height="1000"/></clipPath>
            <pattern id="p" width="10" height="10" patternUnits="userSpaceOnUse"><circle cx="5" cy="5" r="3"/></pattern>
            <path id="m" d="M0 0L1 1L2 0Z"/></defs>""",
        )
        val elements = 100_000
        for (group in 0 until 20) {
            body.append("""<g class="l$group" clip-path="url(#c)">""")
            repeat(elements / 20) { index ->
                val x = random.nextInt(1000)
                val y = random.nextInt(1000)
                val d = StringBuilder("M$x $y")
                repeat(20) {
                    d
                        .append('l')
                        .append(random.nextInt(11) - 5)
                        .append(' ')
                        .append(random.nextInt(11) - 5)
                }
                val fill = when (index % 100) {
                    0 -> """fill="url(#g)""""
                    1 -> """fill="url(#p)""""
                    else -> ""
                }
                body.append("""<path class="c${index % 500}" d="${d}z" $fill/>""")
            }
            body.append("</g>")
        }
        body.append("""<text x="10" y="50" font-size="20" style="font-family:sans-serif">${"The quick brown fox. ".repeat(100)}</text>""")
        val text = wrap(body.toString(), """width="1080" height="1080" viewBox="0 0 1000 1000"""")
        val document = quickly { parse(text) }
        assertTrue(document.warnings.toString(), document.warnings.isEmpty())
        val (bitmap, complete) = quickly { document.draw(1080) }
        assertTrue(complete)
        assertTrue(document.warnings.isEmpty())
        assertTrue(bitmap.paintedPixels() > 1000)
    }

    private companion object {
        const val CI_ALLOWANCE = 3.0
    }
}
