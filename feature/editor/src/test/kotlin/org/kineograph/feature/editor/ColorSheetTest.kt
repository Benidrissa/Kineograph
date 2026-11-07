package org.kineograph.feature.editor

import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.kineograph.engine.brush.BrushPresets
import org.kineograph.feature.editor.canvas.CanvasTool
import org.kineograph.ui.DefaultSwatches
import org.kineograph.ui.KineographTheme
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The picker as the editor uses it, with the same rules for what a colour change and a settled colour do. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi")
class ColorSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private var tools by mutableStateOf(ToolState())
    private var changes = 0

    private fun show() {
        compose.setContent {
            KineographTheme(darkTheme = false) {
                ColorSheet(
                    tools = tools,
                    onColor = { argb ->
                        changes++
                        tools = tools.copy(colorArgb = argb)
                    },
                    onColorCommit = { tools = tools.withRecent(tools.colorArgb) },
                    onOpacity = { tools = tools.withActiveOpacity(it) },
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private val ring get() = compose.onNode(hasContentDescription("Color: hue ring", substring = true))

    private fun swatch(argb: Int) = compose.onNodeWithContentDescription("#%06X".format(argb and 0xFFFFFF))

    @Test
    fun `a swatch changes the colour and leaves the opacity alone`() {
        tools = ToolState(brushId = BrushPresets.MARKER.id)
        val before = tools.activeOpacity
        show()
        swatch(DefaultSwatches[3]).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(DefaultSwatches[3], tools.colorArgb)
        assertEquals(before, tools.activeOpacity, 0f)
    }

    @Test
    fun `the current colour's swatch shows as chosen below full opacity too`() {
        tools = ToolState(brushId = BrushPresets.MARKER.id, colorArgb = DefaultSwatches[3])
        show()
        swatch(DefaultSwatches[3]).assertIsSelected()
    }

    @Test
    fun `under the fill tool a swatch keeps the fill's opacity`() {
        tools = ToolState(tool = CanvasTool.FILL).withActiveOpacity(0.5f)
        show()
        swatch(DefaultSwatches[3]).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(128, tools.toCanvasConfig().fillColorArgb ushr 24)
    }

    @Test
    fun `one drag across the square is one recent colour, however many it passed through`() {
        show()
        ring.performTouchInput {
            down(center)
            repeat(20) { moveBy(Offset(4f, 3f)) }
            up()
        }
        compose.waitForIdle()
        assertTrue("the drag changed the colour along the way: $changes", changes > 5)
        assertEquals(listOf(tools.colorArgb), tools.recentColors)
    }

    @Test
    fun `a tap is one recent colour`() {
        show()
        ring.performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(listOf(tools.colorArgb), tools.recentColors)
    }

    @Test
    fun `a whole hex value is one recent colour, and the digits on the way are none`() {
        show()
        val field = compose.onNode(hasSetTextAction())
        field.performScrollTo().performTextReplacement("E4")
        assertTrue(tools.recentColors.isEmpty())
        field.performTextReplacement("E4572E")
        compose.waitForIdle()
        assertEquals(0xFFE4572E.toInt(), tools.colorArgb)
        assertEquals(listOf(0xFFE4572E.toInt()), tools.recentColors)
    }

    @Test
    fun `the opacity slider is not picking a colour`() {
        show()
        val colour = tools.colorArgb
        compose
            .onNode(hasContentDescription("Opacity"))
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.waitForIdle()
        assertEquals(0.5f, tools.activeOpacity, 0.01f)
        assertEquals("not even shifted by a level, by going through hue and back", colour, tools.colorArgb)
        assertTrue(tools.recentColors.isEmpty())
    }

    @Test
    fun `picking one colour does not cost the other choices`() {
        show()
        swatch(DefaultSwatches[3]).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(DefaultSwatches[3]), tools.recentColors)
        assertEquals("no twin of the one just picked", 1, compose.onAllNodesWithContentDescription("#E4572E").fetchSemanticsNodes().size)
        DefaultSwatches.forEach { swatch(it).assertExists() }
    }

    /** Where on the ring's square to tap for a colour, with the ring's own geometry. */
    private fun tapSquare(saturation: Float, brightness: Float) {
        ring.performTouchInput {
            val half = width.coerceAtMost(height) / 2f * 0.56f
            click(Offset(centerX + half * (saturation * 2f - 1f), centerY + half * (1f - brightness * 2f)))
        }
        compose.waitForIdle()
    }

    @Test
    fun `going back to the colour the sheet opened on works`() {
        tools = ToolState(colorArgb = Color.WHITE)
        show()
        tapSquare(1f, 1f)
        assertEquals("the top right corner of a red square", 0xFFFF0000.toInt(), tools.colorArgb)
        tapSquare(0f, 1f)
        assertEquals("the colour from before the first touch is a colour like any other", Color.WHITE, tools.colorArgb)
        assertEquals(listOf(Color.WHITE, 0xFFFF0000.toInt()), tools.recentColors)
    }

    @Test
    @Config(fontScale = 2f)
    fun `the hex field is not cut off at the largest font size`() {
        show()
        val height = compose
            .onNode(hasSetTextAction())
            .fetchSemanticsNode()
            .boundsInRoot.height
        assertTrue("$height", with(compose.density) { height.toDp() } > 64.dp)
    }

    // ----- The way to the colour sheet from a tool's options -----

    private fun showOptions(onOpenColor: () -> Unit) {
        compose.setContent {
            KineographTheme(darkTheme = false) { ToolOptionsSheet(tools, { tools = it(tools) }, onOpenColor, {}) }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a brush's options lead to the colour`() {
        var opened = 0
        showOptions { opened++ }
        compose.onNodeWithTag("options_color").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `a fill's options lead to the colour, and have its opacity`() {
        var opened = 0
        tools = ToolState(tool = CanvasTool.FILL)
        showOptions { opened++ }
        compose.onNodeWithTag("options_color").performScrollTo().performClick()
        assertEquals(1, opened)
        compose
            .onNode(hasContentDescription("Opacity"))
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.waitForIdle()
        assertEquals(128, tools.toCanvasConfig().fillColorArgb ushr 24)
    }

    @Test
    fun `an eraser has no colour to lead to`() {
        tools = ToolState(tool = CanvasTool.ERASER)
        showOptions { }
        compose.onNodeWithTag("options_color").assertDoesNotExist()
    }
}
