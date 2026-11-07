package org.kineograph.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test

/** Holds both themes to the WCAG contrast minimums, so controls cannot quietly become invisible again. */
class ThemeContrastTest {
    private val body = 4.5f
    private val control = 3f

    private fun contrast(a: Color, b: Color): Float {
        val light = maxOf(a.luminance(), b.luminance())
        val dark = minOf(a.luminance(), b.luminance())
        return (light + 0.05f) / (dark + 0.05f)
    }

    private fun check(name: String, scheme: ColorScheme) {
        val text =
            listOf(
                "onSurface" to (scheme.onSurface to scheme.surface),
                "onSurfaceVariant" to (scheme.onSurfaceVariant to scheme.surface),
                "onSurfaceVariant on the variant" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
                "onPrimary" to (scheme.onPrimary to scheme.primary),
                "onPrimaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                "onSecondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
                "onSurface on the highest container" to (scheme.onSurface to scheme.surfaceContainerHighest),
            )
        text.forEach { (what, pair) ->
            assertTrue("$name $what is only ${contrast(pair.first, pair.second)}:1", contrast(pair.first, pair.second) >= body)
        }
        val controls =
            listOf(
                "primary" to (scheme.primary to scheme.surface),
                "primary on the sheet" to (scheme.primary to scheme.surfaceContainerLow),
                "outline" to (scheme.outline to scheme.surface),
            )
        controls.forEach { (what, pair) ->
            assertTrue("$name $what is only ${contrast(pair.first, pair.second)}:1", contrast(pair.first, pair.second) >= control)
        }
    }

    private fun checkSliderTrack(name: String, scheme: ColorScheme) {
        val behind =
            listOf(
                "the screen" to scheme.surface,
                "a sheet" to scheme.surfaceContainerLow,
                "a dialog" to scheme.surfaceContainerHigh,
                "the quick sliders' panel" to scheme.surfaceColorAtElevation(3.dp),
            )
        behind.forEach { (what, colour) ->
            val ratio = contrast(scheme.onSurface.copy(alpha = TRACK_ALPHA).compositeOver(colour), colour)
            assertTrue("$name slider track on $what is only $ratio:1", ratio >= control)
        }
    }

    @Test
    fun `the unfilled part of a slider stands out in both themes, wherever a slider sits`() {
        checkSliderTrack("light", LightColors)
        checkSliderTrack("dark", DarkColors)
    }

    @Test
    fun `the light theme is readable`() {
        check("light", LightColors)
    }

    @Test
    fun `the dark theme is readable`() {
        check("dark", DarkColors)
    }

    @Test
    fun `chip borders in the dark theme stand out from the sheet`() {
        assertTrue(contrast(DarkColors.outlineVariant, DarkColors.surface) >= control)
    }
}
