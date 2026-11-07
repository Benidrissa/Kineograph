package org.kineograph.media.svg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Compares our renderer with a real browser. The reference pictures are what Chromium drew for the same file at the same moment:
 * `tools/svg-reference` regenerates them, and says how to look at a difference.
 *
 * A failure here is a bug in our renderer until shown otherwise. If a difference really cannot be fixed, the fixture goes in
 * `reference/known-differences.txt` with its reason, instead of the limits being loosened for everything.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SvgBrowserComparisonTest(private val name: String) {
    @Test
    fun `looks the way it does in a browser`() {
        val moment = checkNotNull(moments[name]) { "no moment called $name" }
        val reference = loadReference(name)
        assertNotNull("no reference picture for $name: run tools/svg-reference", reference)
        reference ?: return
        val ours = Bitmap.createBitmap(reference.width, reference.height, Bitmap.Config.ARGB_8888)
        val document = SvgParser.parse(fixture(moment.fixture))
        val finished = document.render(Canvas(ours), moment.time, RectF(0f, 0f, moment.size, moment.size))
        val difference = comparePictures(ours, reference)
        val isClose = if (moment.hasLetters) difference.isCloseWithLetters else difference.isClose
        val knownReason = knownDifference(moment)
        val verdict =
            when {
                !finished -> "unfinished"
                isClose && knownReason != null -> "stale-known"
                isClose -> "ok"
                knownReason != null -> "known"
                else -> "FAIL"
            }
        report(name, verdict, difference, ours, reference)
        assertTrue("$name stopped drawing early", finished)
        assumeTrue("$name is a known difference: $knownReason", knownReason == null)
        assertTrue("$name differs from the browser: $difference", isClose)
    }

    private fun report(name: String, verdict: String, difference: PictureDifference, ours: Bitmap, reference: Bitmap) {
        val folder = System.getenv(OUTPUT_VARIABLE)?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        folder.mkdirs()
        File(folder, "$verdict-$name.png").outputStream().use { sideBySide(ours, reference).compress(Bitmap.CompressFormat.PNG, 100, it) }
        synchronized(lock) {
            File(folder, "report.tsv").appendText(
                "$verdict\t$name\t%.3f\t%.4f\t%d\n".format(
                    difference.meanDifference,
                    difference.strayShare * 100,
                    difference.solidBlocks,
                ),
            )
        }
    }

    private fun loadReference(name: String): Bitmap? {
        val stream = javaClass.getResourceAsStream("/svg/reference/$name.png") ?: return null
        val options = BitmapFactory.Options()
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        return stream.use { BitmapFactory.decodeStream(it, null, options) }
    }

    /** A line can name a whole fixture or one of its moments, whichever is true of the difference. */
    private fun knownDifference(moment: Moment): String? = knownDifferences[name] ?: knownDifferences[moment.fixture.removeSuffix(".svg")]

    private class Moment(val fixture: String, val time: Double, val size: Float, val hasLetters: Boolean)

    companion object {
        /** When set, a side-by-side picture of each comparison and a table of the results are written to this folder. */
        private const val OUTPUT_VARIABLE = "SVG_COMPARE_DIR"

        private val lock = Any()

        private fun lines(resource: String): List<String> {
            val stream = checkNotNull(SvgBrowserComparisonTest::class.java.getResourceAsStream(resource)) { "missing $resource" }
            return stream
                .bufferedReader()
                .readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
        }

        private val moments: Map<String, Moment> by lazy {
            val table = LinkedHashMap<String, Moment>()
            for (line in lines("/svg/reference/moments.txt")) {
                val parts = line.split(Regex("\\s+"))
                val fixture = parts[0]
                val size = parts.firstOrNull { it.startsWith("size=") }?.substringAfter('=')?.toFloat() ?: 200f
                val hasLetters = "letters" in parts
                for (time in parts.drop(1).filter { it[0].isDigit() }) {
                    table["${fixture.removeSuffix(".svg")}@$time"] = Moment(fixture, time.toDouble(), size, hasLetters)
                }
            }
            table
        }

        private val knownDifferences: Map<String, String> by lazy {
            lines("/svg/reference/known-differences.txt").associate { line ->
                val parts = line.split(Regex("\\s+"), limit = 2)
                parts[0] to parts.getOrElse(1) { "" }
            }
        }

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = moments.keys.map { arrayOf<Any>(it) }
    }
}
