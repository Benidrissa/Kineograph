package org.kineograph.media.svg

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Promises that hold for every picture, checked on every fixture. The browser comparison looks at chosen moments, one at a time, so it
 * cannot see whether an imported loop closes up, or whether a frame depends on the one before it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SvgFixtureSweepTest {
    private val fixtures: List<String> by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/svg/reference/moments.txt"))
        stream
            .bufferedReader()
            .readLines()
            .map {
                it
                    .substringBefore(
                        '#',
                    ).trim()
            }.filter { it.isNotEmpty() }
            .map { it.substringBefore(' ') }
    }

    private fun SvgDocument.pixels(time: Double): IntArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        render(Canvas(bitmap), time, RectF(0f, 0f, 64f, 64f))
        val out = IntArray(64 * 64)
        bitmap.getPixels(out, 0, 64, 0, 0, 64, 64)
        bitmap.recycle()
        return out
    }

    /** Rounding can move a colour by a step or two, and an edge by a little more. What counts is that nothing visibly jumps. */
    private fun closeEnough(a: IntArray, b: IntArray): Boolean {
        var total = 0L
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            if (x == y) continue
            for (shift in intArrayOf(0, 8, 16, 24)) {
                total += kotlin.math.abs((x ushr shift and 0xFF) - (y ushr shift and 0xFF))
            }
        }
        return total.toDouble() / (a.size * 4) < 0.75
    }

    private fun failures(check: (String, SvgDocument) -> String?): List<String> {
        val out = ArrayList<String>()
        for (name in fixtures) {
            val document = SvgParser.parse(fixture(name))
            check(name, document)?.let { out += it }
        }
        return out
    }

    @Test
    fun `a suggested loop closes up seamlessly`() {
        val problems =
            failures { name, document ->
                val info = document.animation
                if (!info.loopsForever || info.durationSeconds <= 0.0) return@failures null
                val start = info.loopStartSeconds
                // Starting a little way in as well as at the start, since a loop can line up at one moment and not at another.
                val samples = doubleArrayOf(start, start + info.durationSeconds * 0.37)
                val broken = samples.any { !closeEnough(document.pixels(it), document.pixels(it + info.durationSeconds)) }
                if (broken) "$name does not repeat after ${info.durationSeconds}s from ${info.loopStartSeconds}s" else null
            }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `a picture that ends has stopped changing by its suggested duration`() {
        val problems =
            failures { name, document ->
                val info = document.animation
                if (!info.isAnimated || info.loopsForever) return@failures null
                val end = info.durationSeconds
                val still =
                    closeEnough(document.pixels(end), document.pixels(end + 7.3)) &&
                        closeEnough(document.pixels(end), document.pixels(end + 100.0))
                if (still) null else "$name is still changing after ${info.durationSeconds}s"
            }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `a frame does not depend on the frame before it`() {
        val problems =
            failures { name, document ->
                if (!document.animation.isAnimated) return@failures null
                val times = doubleArrayOf(0.0, 0.7, 1.3, 2.9)
                val forwards = times.map { document.pixels(it) }
                // Backwards this time, starting from a frame far away, and with the first one repeated right after itself.
                document.pixels(50.0)
                val backwards = times.reversed().map { document.pixels(it) }.reversed()
                val differs = times.indices.any { !forwards[it].contentEquals(backwards[it]) }
                if (differs) "$name draws differently depending on the order of frames" else null
            }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
