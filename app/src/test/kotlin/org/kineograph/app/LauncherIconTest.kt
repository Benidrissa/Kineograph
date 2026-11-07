package org.kineograph.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.hypot

/** Draws the themed icon the way a launcher gets it, with real Skia. A launcher only reads the alpha, so that is what is checked. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LauncherIconTest {
    private val themedLayer: Bitmap by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val icon = checkNotNull(ContextCompat.getDrawable(context, R.mipmap.ic_launcher)) as AdaptiveIconDrawable
        val layer = checkNotNull(icon.monochrome)
        Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { bitmap ->
            layer.setBounds(0, 0, SIZE, SIZE)
            layer.draw(Canvas(bitmap))
        }
    }

    /** The alpha at a point in the icon's 108 units. */
    private fun alphaAt(x: Float, y: Float): Int = Color.alpha(themedLayer.getPixel((x * SCALE).toInt(), (y * SCALE).toInt()))

    @Test
    fun `the ball and the floor show as holes in the front page`() {
        assertEquals("page, beside the ball", 255, alphaAt(34f, 56f))
        assertEquals("ball", 0, alphaAt(50f, 59f))
        assertEquals("page, between the ball and the floor", 255, alphaAt(50f, 68.5f))
        assertEquals("floor", 0, alphaAt(50f, 71.25f))
        assertEquals("page, under the floor", 255, alphaAt(50f, 75f))
    }

    @Test
    fun `the pages behind show as separate slivers`() {
        assertEquals("back page", 255, alphaAt(60f, 31f))
        assertEquals("gap", 0, alphaAt(60f, 33.25f))
        assertEquals("middle page", 255, alphaAt(60f, 35f))
        assertEquals("gap", 0, alphaAt(60f, 37.25f))
        assertEquals("front page", 255, alphaAt(60f, 39f))
    }

    @Test
    fun `the whole mark stays inside the part of the icon every mask shows`() {
        var furthest = 0f
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                if (Color.alpha(themedLayer.getPixel(x, y)) > 0) {
                    furthest = maxOf(furthest, hypot(x + 0.5f - SIZE / 2f, y + 0.5f - SIZE / 2f) / SCALE)
                }
            }
        }
        assertTrue("reaches $furthest units from the centre", furthest <= SAFE_RADIUS)
    }

    private companion object {
        const val SCALE = 4
        const val SIZE = 108 * SCALE

        /** The adaptive icon's safe zone is a circle 66 units across. The corner of the mark just touches it, which does not show. */
        const val SAFE_RADIUS = 33.5f
    }
}
