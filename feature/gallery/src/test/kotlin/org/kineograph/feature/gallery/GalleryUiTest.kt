package org.kineograph.feature.gallery

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.kineograph.data.ProjectSummary
import org.kineograph.model.Background
import org.kineograph.model.PaperKind
import org.kineograph.ui.KineographTheme
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi")
class GalleryUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun summary(name: String, id: String = name, trashed: Boolean = false) =
        ProjectSummary(
            id = id,
            name = name,
            createdAt = 0,
            updatedAt = 0,
            width = 1280,
            height = 720,
            fps = 12,
            frameCount = 12,
            durationMs = 2000,
            folder = null,
            isTrashed = trashed,
            sizeBytes = 2048,
            thumbnail = null,
        )

    private val noActions =
        GalleryActions(
            onOpen = {},
            onCreate = {},
            onRename = {
                _,
                _,
                ->
            },
            onDuplicate = { _, _ -> },
            onTrash = {},
            onRestore = {},
            onDeleteForever = {},
            onQuery = {},
            onSort = {},
            onFilter = {},
            onOpenSettings = {},
        )

    private fun show(state: GalleryUiState, actions: GalleryActions = noActions) {
        compose.setContent {
            KineographTheme(darkTheme = false) {
                GalleryScreen(state, canEncode = { _, _ -> false }, actions = actions)
            }
        }
    }

    private fun loaded(vararg projects: ProjectSummary) = GalleryUiState(
        all = projects.toList(),
        isLoaded = true,
        nextDefaultNumber =
            projects.size + 1,
    )

    @Test
    fun `a card shows the name, duration, frame count and frame rate`() {
        show(loaded(summary("Bouncing Ball")))
        compose.onNodeWithText("Bouncing Ball").assertIsDisplayed()
        compose.onNodeWithText("00:02 · 12 frames").assertIsDisplayed()
        compose.onNodeWithText("12 fps").assertIsDisplayed()
        compose.onRoot().saveScreenshot("gallery")
    }

    @Test
    fun `the empty gallery explains what to do`() {
        show(GalleryUiState(isLoaded = true))
        compose.onNodeWithText("No projects yet. Tap New to draw your first animation.").assertIsDisplayed()
    }

    @Test
    fun `the new project sheet opens from the button`() {
        show(GalleryUiState(isLoaded = true))
        compose.onNodeWithTag("new_project_button").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("new_project_name").assertTextContains("My animation 1")
    }

    @Test
    fun `opening a card from the menu offers rename, duplicate and delete`() {
        show(loaded(summary("Mine")))
        compose.onNodeWithText("Mine").assertIsDisplayed()
        compose.onNode(hasContentDescription("More actions for Mine")).performClick()
        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.onNodeWithText("Duplicate").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test
    fun `deleting moves to trash and offers undo`() {
        var trashed: String? = null
        var restored: String? = null
        val actions =
            GalleryActions(
                onOpen = {},
                onCreate = {},
                onRename = { _, _ -> },
                onDuplicate = { _, _ -> },
                onTrash = { trashed = it },
                onRestore = { restored = it },
                onDeleteForever = {},
                onQuery = {},
                onSort = {},
                onFilter = {},
                onOpenSettings = {},
            )
        show(loaded(summary("Mine")), actions)
        compose.onNode(hasContentDescription("More actions for Mine")).performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        assertEquals("Mine", trashed)
        compose.onNodeWithText("Moved to trash").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals("Mine", restored)
    }

    // ----- New project form -----

    private fun showForm(canEncode: (Int, Int) -> Boolean = { _, _ -> false }, onCreate: (NewProjectRequest) -> Unit = {}) {
        compose.setContent {
            KineographTheme(darkTheme = false) {
                NewProjectForm(defaultNumber = 3, canEncode = canEncode, onCreate = onCreate)
            }
        }
    }

    @Test
    fun `the form starts with the specified defaults`() {
        var created: NewProjectRequest? = null
        showForm(onCreate = { created = it })
        compose.onRoot().saveScreenshot("new_project")
        compose.onNodeWithTag("new_project_name").assertTextContains("My animation 3")
        compose.onNodeWithTag("create_project").performScrollTo().performClick()
        val request = created ?: error("nothing created")
        assertEquals("My animation 3", request.name)
        assertEquals(1280, request.width)
        assertEquals(720, request.height)
        assertEquals(12, request.fps)
        assertEquals(Background.Paper(PaperKind.WHITE), request.background)
    }

    @Test
    fun `the helper text explains the frame rate`() {
        showForm()
        compose.onNodeWithTag("fps_helper").performScrollTo().assertTextContains("You need 12 drawings for 1 second of animation")
    }

    @Test
    fun `presets set the canvas size`() {
        var created: NewProjectRequest? = null
        showForm(onCreate = { created = it })
        compose.onNodeWithTag("canvas_size_dropdown").performClick()
        compose.onNodeWithTag("preset_vertical").performClick()
        compose.onNodeWithTag("create_project").performScrollTo().performClick()
        assertEquals(1080, created?.width)
        assertEquals(1920, created?.height)
    }

    @Test
    fun `4K is only offered if the encoder supports it`() {
        showForm(canEncode = { w, _ -> w < 3000 })
        compose.onNodeWithTag("canvas_size_dropdown").performClick()
        compose.onNodeWithTag("preset_hd").assertIsDisplayed()
        compose.onNodeWithTag("preset_uhd_4k").assertDoesNotExist()
    }

    @Test
    fun `4K appears when the encoder supports it`() {
        showForm(canEncode = { _, _ -> true })
        compose.onNodeWithTag("canvas_size_dropdown").performClick()
        compose.onNodeWithTag("preset_uhd_4k").assertExists()
    }

    @Test
    fun `a custom size is used and a bad one blocks creation with an explanation`() {
        var created: NewProjectRequest? = null
        showForm(onCreate = { created = it })
        compose.onNodeWithTag("canvas_size_dropdown").performClick()
        compose.onNodeWithTag("preset_custom").performScrollTo().performClick()
        compose.onNodeWithText("Width").performScrollTo().performTextClearance()
        compose.onNodeWithText("Width").performTextInput("640")
        compose.onNodeWithText("Height").performTextClearance()
        compose.onNodeWithText("Height").performTextInput("99999")
        compose.waitForIdle()
        compose.onNodeWithTag("create_project").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Enter a width and height from 1 to 8192.").assertExists()
        compose.onNodeWithText("Height").performTextClearance()
        compose.onNodeWithText("Height").performTextInput("480")
        compose.waitForIdle()
        compose
            .onNodeWithTag("create_project")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(640, created?.width)
        assertEquals(480, created?.height)
    }

    @Test
    fun `a blank name blocks creation`() {
        showForm()
        compose.onNodeWithTag("new_project_name").performTextClearance()
        compose.waitForIdle()
        compose.onNodeWithTag("create_project").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `the background choice carries through`() {
        var created: NewProjectRequest? = null
        showForm(onCreate = { created = it })
        compose.onNodeWithTag("background_dropdown").performClick()
        compose.onNodeWithTag("background_transparent").performScrollTo().performClick()
        compose.onNodeWithTag("create_project").performScrollTo().performClick()
        assertEquals(Background.Transparent, created?.background)
    }

    @Test
    fun `papers can be chosen`() {
        var created: NewProjectRequest? = null
        showForm(onCreate = { created = it })
        compose.onNodeWithTag("background_dropdown").performClick()
        compose.onNodeWithTag("background_paper_dots").performClick()
        compose.onNodeWithTag("create_project").performScrollTo().performClick()
        assertTrue(created?.background == Background.Paper(PaperKind.DOTS))
    }

    @Test
    fun `choosing from a drop-down closes it and shows the choice`() {
        showForm()
        compose.onNodeWithTag("background_dropdown").assertTextContains("White")
        compose.onNodeWithTag("background_dropdown").performClick()
        compose.onNodeWithText("Dark paper for bright drawings").assertIsDisplayed()
        compose.onNodeWithTag("background_paper_dark").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Dark paper for bright drawings").assertDoesNotExist()
        compose.onNodeWithTag("background_dropdown").assertTextContains("Dark")
    }

    @Test
    fun `canvas options are grouped and explain themselves`() {
        showForm()
        compose.onNodeWithTag("canvas_size_dropdown").assertTextContains("HD 1280×720")
        compose.onNodeWithTag("canvas_size_dropdown").performClick()
        compose.onNodeWithText("Social").assertExists()
        compose.onNodeWithText("TikTok, Reels, Shorts, WhatsApp Status").assertExists()
        compose.onNodeWithTag("preset_square").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("canvas_size_dropdown").assertTextContains("Square 1080×1080")
    }

    @Test
    fun `the whole form fits on a phone without scrolling`() {
        showForm()
        compose.onNodeWithTag("create_project").assertIsDisplayed()
        compose.onNodeWithTag("project_preview").assertIsDisplayed()
    }

    /** Lays the sheet out in both themes, with its menus open, to check by eye. */
    @Test
    fun `the form in both themes with its menus open`() {
        var dark by mutableStateOf(false)
        compose.setContent {
            KineographTheme(darkTheme = dark) {
                Surface {
                    NewProjectForm(defaultNumber = 3, canEncode = { _, _ -> true }, onCreate = {})
                }
            }
        }
        for (isDark in listOf(false, true)) {
            dark = isDark
            compose.waitForIdle()
            val theme = if (isDark) "dark" else "light"
            compose.onRoot().saveScreenshot("new_project_$theme")
            compose.onNodeWithTag("background_dropdown").performClick()
            compose.waitForIdle()
            compose.onNode(isPopup()).saveScreenshot("new_project_background_menu_$theme")
            compose.onNodeWithTag("background_paper_white").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("canvas_size_dropdown").performClick()
            compose.waitForIdle()
            compose.onNode(isPopup()).saveScreenshot("new_project_canvas_menu_$theme")
            compose.onNodeWithTag("preset_hd").performClick()
            compose.waitForIdle()
        }
    }
}
