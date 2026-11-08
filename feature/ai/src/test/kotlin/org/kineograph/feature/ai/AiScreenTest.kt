package org.kineograph.feature.ai

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextContains
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
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AiScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val asked = ArrayList<String>()
    private val backends = ArrayList<AiBackend>()
    private var imports = 0
    private var deletes = 0

    private val actions =
        AiActions(
            onImport = { imports++ },
            onCancelImport = {},
            onDelete = { deletes++ },
            onBackend = { backends += it },
            onAsk = { asked += it },
            onStop = {},
        )

    private fun show(state: AiState) {
        compose.setContent { MaterialTheme { AiContent(state, onBack = {}, actions = actions) } }
    }

    private val installed = AiState(model = InstalledModel("gemma-4-E2B-it.litertlm", 2_580_000_000))

    @Test
    fun `without a model, the screen offers to import one`() {
        show(AiState())

        compose.onNodeWithTag("ai_import").performScrollTo().performClick()

        assertEquals(1, imports)
        compose.onNodeWithTag("ai_ask").assertDoesNotExist()
    }

    @Test
    fun `with a model, it can be asked something`() {
        show(installed)

        compose.onNodeWithTag("ai_model_name").assertTextContains("gemma-4-E2B-it.litertlm")
        compose.onNodeWithTag("ai_ask").performScrollTo().performClick()

        assertEquals(listOf("Describe a bouncing ball animation in three short steps."), asked)
    }

    @Test
    fun `deleting asks first`() {
        show(installed)

        compose.onNodeWithTag("ai_delete").performScrollTo().performClick()
        assertEquals(0, deletes)
        compose.onNodeWithTag("ai_delete_confirm").performClick()

        assertEquals(1, deletes)
    }

    @Test
    fun `the backend is picked from a drop-down`() {
        show(installed)

        compose.onNodeWithTag("ai_backend").performScrollTo().performClick()
        compose.onNodeWithTag("ai_backend_gpu").performScrollTo().performClick()

        assertEquals(listOf(AiBackend.GPU), backends)
    }

    @Test
    fun `a refused file says what to look for`() {
        show(AiState(importProblem = ImportOutcome.NotAModel))

        compose.onNodeWithTag("ai_import_problem").performScrollTo().assertTextContains(".litertlm", substring = true)
    }

    @Test
    fun `the reply and how fast it came are shown`() {
        show(installed.copy(reply = "Drop, squash, bounce.", timings = AiTimings(4.2, 0.8, 23.4)))

        compose.onNodeWithTag("ai_reply").performScrollTo().assertTextContains("Drop, squash, bounce.")
        compose.onNodeWithTag("ai_timings").performScrollTo().assertTextContains("23.4 tokens per second", substring = true)
        assertTrue(asked.isEmpty())
    }
}
