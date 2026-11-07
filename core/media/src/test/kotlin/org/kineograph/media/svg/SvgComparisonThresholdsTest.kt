package org.kineograph.media.svg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A comparison that everything passes proves nothing. These make our picture wrong on purpose, in each way the browser comparison is
 * there to catch, and check that it does. They also check that what is not wrong gets through.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SvgComparisonThresholdsTest {
    private fun reference(name: String): Bitmap {
        val stream = checkNotNull(javaClass.getResourceAsStream("/svg/reference/$name.png")) { "no reference for $name" }
        return stream.use { checkNotNull(BitmapFactory.decodeStream(it)) }
    }

    private fun drawn(source: ByteArray, time: Double = 0.0, shiftX: Float = 0f, shiftY: Float = 0f): Bitmap {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(shiftX, shiftY)
        SvgParser.parse(source).render(canvas, time, RectF(0f, 0f, 200f, 200f))
        return bitmap
    }

    private fun assertCaught(what: String, ours: Bitmap, reference: Bitmap) {
        val difference = comparePictures(ours, reference)
        assertFalse("$what got through: $difference", difference.isClose)
    }

    @Test
    fun `the same picture passes, with nothing to report`() {
        val picture = reference("shape-rect@0")
        val difference = comparePictures(picture, picture)
        assertTrue(difference.isClose)
        assertEquals(0.0, difference.meanDifference, 0.0)
        assertEquals(0.0, difference.strayShare, 0.0)
        assertEquals(0, difference.solidBlocks)
    }

    @Test
    fun `what actually gets drawn passes`() {
        for (name in listOf("shape-rect", "path-arc-flags", "grad-linear-bbox", "clip-units")) {
            val difference = comparePictures(drawn(fixture("$name.svg")), reference("$name@0"))
            assertTrue("$name: $difference", difference.isClose)
        }
    }

    @Test
    fun `a shape two pixels out of place is caught`() {
        for (name in listOf("shape-rect", "path-arc-flags", "stroke-joins", "use-basic", "transform-list")) {
            assertCaught("$name moved two pixels", drawn(fixture("$name.svg"), shiftX = 2f), reference("$name@0"))
        }
    }

    @Test
    fun `a whole picture a pixel out of place is caught`() {
        assertCaught("a picture moved one pixel", drawn(fixture("shape-rect.svg"), shiftY = 1f), reference("shape-rect@0"))
    }

    @Test
    fun `a moment slightly off is caught`() {
        // The shapes in this fixture cross the picture in two seconds, so a tenth of a second is about eight pixels.
        val source = fixture("smil-animate-number.svg")
        assertCaught("a moment 0.1 s late", drawn(source, time = 1.1), reference("smil-animate-number@1"))
        assertCaught("a moment 0.1 s early", drawn(source, time = 0.9), reference("smil-animate-number@1"))
        val css = fixture("css-anim-transform.svg")
        assertCaught("a CSS moment 0.1 s late", drawn(css, time = 1.1), reference("css-anim-transform@1"))
    }

    @Test
    fun `a colour on one shape that is slightly off is caught`() {
        // One 50 by 30 rectangle out of a picture full of shapes, off by one sixth of the range in one channel.
        val source = String(fixture("shape-rect.svg")).replace("#cc3333", "#a33333").toByteArray()
        assertCaught("one wrong colour", drawn(source), reference("shape-rect@0"))
    }

    @Test
    fun `one small shape missing is caught`() {
        val source = String(
            fixture("shape-circle-ellipse.svg"),
        ).replace("""<ellipse cx="50" cy="170" rx="30" fill="#33aa33"/>""", "").toByteArray()
        assertCaught("a missing ellipse", drawn(source), reference("shape-circle-ellipse@0"))
    }

    @Test
    fun `one shape with the wrong outline is caught`() {
        val source = String(fixture("shape-rect.svg")).replace("""rx="8" fill="#3366cc"""", """rx="2" fill="#3366cc"""").toByteArray()
        assertCaught("a smaller corner radius", drawn(source), reference("shape-rect@0"))
    }

    @Test
    fun `a line of text that is missing is caught, for all that it covers so little`() {
        val reference = reference("text-anchor@0")
        val source = String(fixture("text-anchor.svg")).replace(">Middle<", "><").toByteArray()
        assertFalse(comparePictures(drawn(source), reference).isCloseWithLetters)
    }
}
