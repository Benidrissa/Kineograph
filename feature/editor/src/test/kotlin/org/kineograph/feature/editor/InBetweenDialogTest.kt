package org.kineograph.feature.editor

import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.kineograph.engine.brush.BrushPresets
import org.kineograph.engine.vector.InBetweenOutcome
import org.kineograph.engine.vector.InBetweenResult
import org.kineograph.engine.vector.InBetweenSpacing
import org.kineograph.engine.vector.InBetweens
import org.kineograph.engine.vector.RecordedStroke
import org.kineograph.model.IntRect
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class InBetweenDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private fun stroke(y: Float) =
        RecordedStroke(BrushPresets.PEN, 1L, Color.BLACK, floatArrayOf(20f, y, 1f, 180f, y, 1f), 2, 3, IntRect(0, 0, 200, 100))

    private val sketch = InBetweens.sketch(listOf(stroke(20f) to stroke(80f)))

    private fun asking(count: Int = 1) = InBetweenState.Asking("f1", frameNumber = 3, count = count, sketch = sketch)

    @Test
    fun `the count goes up and down, but not below one`() {
        var state by mutableStateOf(asking())
        compose.setContent {
            MaterialTheme {
                InBetweenAskDialog(
                    state,
                    onCount = { state = state.copy(count = it.coerceAtLeast(1)) },
                    onSpacing = {},
                    onAdd = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("in_betweens_count").assertTextEquals("1")
        compose.onNodeWithTag("in_betweens_fewer").assertIsNotEnabled()

        compose.onNodeWithTag("in_betweens_more").performClick()
        compose.onNodeWithTag("in_betweens_more").performClick()

        compose.onNodeWithTag("in_betweens_count").assertTextEquals("3")
    }

    @Test
    fun `a spacing is picked from a drop-down`() {
        var picked: InBetweenSpacing? = null
        compose.setContent {
            MaterialTheme { InBetweenAskDialog(asking(3), {}, onSpacing = { picked = it }, onAdd = {}, onDismiss = {}) }
        }

        compose.onNodeWithTag("in_betweens_spacing").performScrollTo().performClick()
        compose.onNodeWithTag("in_betweens_spacing_slow_both").performScrollTo().performClick()

        assertEquals(InBetweenSpacing.SLOW_BOTH, picked)
    }

    @Test
    fun `the preview says what it shows`() {
        compose.setContent {
            MaterialTheme { InBetweenAskDialog(asking(), {}, {}, {}, {}) }
        }

        compose
            .onNodeWithTag("in_betweens_preview")
            .performScrollTo()
            .assertContentDescriptionContains("frame 3 to frame 4", substring = true)
    }

    @Test
    fun `a refusal found by the check says why, and offers nothing to add`() {
        val problem = InBetweenResult(InBetweenOutcome.STROKE_COUNTS_DIFFER, frame = 3, first = 4, second = 6)
        compose.setContent {
            MaterialTheme { InBetweenAskDialog(InBetweenState.Asking("f1", 3, problem = problem), {}, {}, {}, {}) }
        }

        compose.onNodeWithTag("in_betweens_problem").performScrollTo().assertTextContains("(4 and 6)", substring = true)
        compose.onNodeWithTag("in_betweens_add").assertDoesNotExist()
        compose.onNodeWithTag("in_betweens_ok").assertExists()
    }

    @Test
    fun `add asks for the in-betweens`() {
        var added = false
        compose.setContent {
            MaterialTheme { InBetweenAskDialog(asking(), {}, {}, onAdd = { added = true }, onDismiss = {}) }
        }

        compose.onNodeWithTag("in_betweens_add").performClick()

        assertTrue(added)
    }
}
