package org.kineograph.feature.editor

import android.graphics.Rect
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.kineograph.data.ToolbarSide
import org.kineograph.ui.KineographTheme
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Strokes and the tool bar's handle near a screen edge must not be taken as Back, within the 200 dp Android allows per edge. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi")
class EdgeGestureTest {
    @get:Rule
    val compose = createComposeRule()

    private var bandsOn by mutableStateOf(true)
    private var side by mutableStateOf(ToolbarSide.LEFT)
    private lateinit var view: View

    private fun show() {
        compose.setContent {
            view = LocalView.current
            KineographTheme {
                Box(Modifier.fillMaxSize().then(if (bandsOn) Modifier.canvasEdgeBands(LocalDensity.current) else Modifier)) {
                    EditorToolBar(tools = ToolState(), side = side, onSideChange = {}, onSelect = {}, onOpenColor = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private fun rects(): List<Rect> = compose.runOnIdle { view.systemGestureExclusionRects.toList() }

    private fun px(dp: Int) = dp * 2

    /** The most a real device's Back zone could reach is well under this. */
    private val zoneWidth = px(24)

    private fun onLeftEdge(rect: Rect) = rect.left < zoneWidth

    private fun onRightEdge(rect: Rect) = rect.right > view.width - zoneWidth

    private fun List<Rect>.totalHeight() = sumOf { it.height() }

    @Test
    fun `the handle and the canvas bands fit in what Android grants each edge, with the bar on either side`() {
        show()
        for (dock in listOf(ToolbarSide.LEFT, ToolbarSide.RIGHT)) {
            side = dock
            compose.waitForIdle()
            val all = rects()
            assertEquals("the handle and a band per edge: $all", 3, all.size)
            val left = all.filter(::onLeftEdge)
            val right = all.filter(::onRightEdge)
            assertEquals(all, (left + right).distinct().sortedBy { all.indexOf(it) })
            assertTrue("$left", left.totalHeight() <= px(200))
            assertTrue("$right", right.totalHeight() <= px(200))
            assertEquals(if (dock == ToolbarSide.LEFT) 2 else 1, left.size)
            assertEquals(if (dock == ToolbarSide.LEFT) 1 else 2, right.size)
        }
    }

    @Test
    fun `the bands sit at the bottom of the canvas, where Android counts its allowance from`() {
        bandsOn = true
        show()
        val all = rects()
        val bottom = view.height
        assertEquals("both bands reach the canvas's bottom edge", 2, all.count { it.bottom == bottom && it.height() == px(152) })
        val handle = all.single { it.bottom != bottom }
        assertEquals(px(48), handle.width())
        assertEquals(px(48), handle.height())
        assertTrue("the handle's rectangle clears the bands, so they do not overlap", handle.bottom <= bottom - px(152))
    }

    @Test
    fun `without the bands, as during playback, only the handle stays protected`() {
        show()
        assertEquals(3, rects().size)
        bandsOn = false
        compose.waitForIdle()
        val all = rects()
        assertEquals("$all", 1, all.size)
        assertEquals(px(48), all.single().height())
    }
}
