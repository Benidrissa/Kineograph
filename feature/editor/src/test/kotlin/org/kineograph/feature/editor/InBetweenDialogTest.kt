package org.kineograph.feature.editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.kineograph.engine.vector.InBetweenOutcome
import org.kineograph.engine.vector.InBetweenResult
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class InBetweenDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the count goes up and down, but not below one`() {
        var state by mutableStateOf(InBetweenState.Asking("f1", frameNumber = 3))
        compose.setContent {
            MaterialTheme {
                InBetweenAskDialog(state, onCount = { state = state.copy(count = it.coerceAtLeast(1)) }, onAdd = {}, onDismiss = {})
            }
        }
        compose.onNodeWithTag("in_betweens_count").assertTextEquals("1")
        compose.onNodeWithTag("in_betweens_fewer").assertIsNotEnabled()

        compose.onNodeWithTag("in_betweens_more").performClick()
        compose.onNodeWithTag("in_betweens_more").performClick()

        compose.onNodeWithTag("in_betweens_count").assertTextEquals("3")
    }

    @Test
    fun `a refusal says why, with the numbers`() {
        val problem = InBetweenResult(InBetweenOutcome.STROKE_COUNTS_DIFFER, frame = 3, first = 4, second = 6)
        compose.setContent {
            MaterialTheme { InBetweenAskDialog(InBetweenState.Asking("f1", 3, problem = problem), {}, {}, {}) }
        }

        compose.onNodeWithTag("in_betweens_problem").performScrollTo().assertTextContains("(4 and 6)", substring = true)
    }

    @Test
    fun `add asks for the in-betweens`() {
        var added = false
        compose.setContent {
            MaterialTheme { InBetweenAskDialog(InBetweenState.Asking("f1", 1), {}, onAdd = { added = true }, onDismiss = {}) }
        }

        compose.onNodeWithTag("in_betweens_add").performClick()

        assertTrue(added)
    }
}
