package org.kineograph.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.kineograph.ui.KineographTheme
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi")
class SwitchRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `TalkBack can tell whether a setting is on, and hears it change`() {
        var checked by mutableStateOf(false)
        compose.setContent {
            KineographTheme(darkTheme = false) { SwitchRow("Dynamic color", checked) { checked = it } }
        }
        compose.onNodeWithText("Dynamic color").assertIsOff().performClick()
        compose.onNodeWithText("Dynamic color").assertIsOn().performClick()
        compose.onNodeWithText("Dynamic color").assertIsOff()
    }
}
