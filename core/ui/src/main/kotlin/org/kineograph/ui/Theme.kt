package org.kineograph.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// The app's own palette: inky teal with a warm paper-and-amber accent, like a flipbook drawn on old paper.
private val Teal30 = Color(0xFF0B5454)
private val Teal40 = Color(0xFF0F6E6E)
private val Teal80 = Color(0xFF7FD6D2)
private val Teal90 = Color(0xFFBFF0ED)
private val Amber40 = Color(0xFF8A5A00)
private val Amber80 = Color(0xFFF3BE5F)
private val Amber90 = Color(0xFFFFE0AA)
private val Coral40 = Color(0xFFB23A1E)
private val Coral80 = Color(0xFFFFB4A0)
private val Coral90 = Color(0xFFFFDBD1)
private val Paper = Color(0xFFFBF8F1)
private val PaperVariant = Color(0xFFECE6D9)
private val Ink = Color(0xFF1B1C1A)
private val InkVariant = Color(0xFF4A4A44)
private val Night = Color(0xFF14171A)
private val NightSurface = Color(0xFF1C2024)
private val NightVariant = Color(0xFF2C3238)
private val Mist = Color(0xFFE3E6E8)
private val MistVariant = Color(0xFFBEC4C9)

private val LightColors =
    lightColorScheme(
        primary = Teal40,
        onPrimary = Color.White,
        primaryContainer = Teal90,
        onPrimaryContainer = Teal30,
        secondary = Amber40,
        onSecondary = Color.White,
        secondaryContainer = Amber90,
        onSecondaryContainer = Color(0xFF2E1D00),
        tertiary = Coral40,
        onTertiary = Color.White,
        tertiaryContainer = Coral90,
        onTertiaryContainer = Color(0xFF3C0B00),
        background = Paper,
        onBackground = Ink,
        surface = Paper,
        onSurface = Ink,
        surfaceVariant = PaperVariant,
        onSurfaceVariant = InkVariant,
        surfaceContainer = Color(0xFFF4F0E6),
        surfaceContainerHigh = Color(0xFFEFEADF),
        surfaceContainerHighest = Color(0xFFE9E3D6),
        surfaceContainerLow = Color(0xFFF8F4EB),
        outline = Color(0xFF75756C),
        outlineVariant = Color(0xFFCFC9BB),
    )

private val DarkColors =
    darkColorScheme(
        primary = Teal80,
        onPrimary = Color(0xFF003737),
        primaryContainer = Teal30,
        onPrimaryContainer = Teal90,
        secondary = Amber80,
        onSecondary = Color(0xFF4A3000),
        secondaryContainer = Color(0xFF684700),
        onSecondaryContainer = Amber90,
        tertiary = Coral80,
        onTertiary = Color(0xFF611600),
        tertiaryContainer = Color(0xFF8A2A10),
        onTertiaryContainer = Coral90,
        background = Night,
        onBackground = Mist,
        surface = Night,
        onSurface = Mist,
        surfaceVariant = NightVariant,
        onSurfaceVariant = MistVariant,
        surfaceContainer = NightSurface,
        surfaceContainerHigh = Color(0xFF23282D),
        surfaceContainerHighest = Color(0xFF2B3136),
        surfaceContainerLow = Color(0xFF181B1F),
        outline = Color(0xFF899096),
        outlineVariant = Color(0xFF41474D),
    )

@Composable
public fun KineographTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors: ColorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> {
                DarkColors
            }

            else -> {
                LightColors
            }
        }
    MaterialTheme(colorScheme = colors, content = content)
}
