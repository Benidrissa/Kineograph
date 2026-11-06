package org.kineograph.feature.editor

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kineograph.data.ToolbarSide
import org.kineograph.engine.session.DrawingSession
import org.kineograph.engine.session.MemoryBudget
import org.kineograph.feature.editor.canvas.CanvasTool
import org.kineograph.model.Background
import org.kineograph.model.PaperKind
import org.kineograph.model.newProject
import org.kineograph.ui.KineographTheme
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

private fun SemanticsNodeInteraction.saveScreenshot(name: String) {
    val directory = System.getenv("KG_SCREENSHOT_DIR") ?: return
    File(directory).mkdirs()
    File(directory, "$name.png").outputStream().use { captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi")
class FrameStripUiTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var session: DrawingSession
    private val store = TestStore()

    @Before
    fun setUp() {
        val project = newProject("My animation", 1280, 720, 12, Background.Paper(PaperKind.WHITE), "Layer 1", now = 0)
        session = DrawingSession(project, store, MemoryBudget.forMemoryClass(256, false), temp.newFolder())
        runBlocking { session.awaitIdle() }
    }

    @After
    fun tearDown() {
        session.close()
    }

    private fun settle() {
        runBlocking { session.awaitIdle() }
        compose.waitForIdle()
    }

    private fun showBottomBar(wide: Boolean = false, onPlay: () -> Unit = {}, onLayers: () -> Unit = {}) {
        val thumbnails = ThumbnailLoader(store, 4 * 1024 * 1024)
        compose.setContent {
            val state by session.state.collectAsState()
            KineographTheme(darkTheme = false) {
                Surface {
                    EditorBottomBar(state, session, thumbnails, wide, onLayers, onPlay)
                }
            }
        }
        settle()
    }

    private val frames get() = session.state.value.project.frames

    @Test
    fun `frames are numbered and the current one is selected`() {
        showBottomBar()
        session.addFrame()
        session.addFrame()
        settle()
        compose.onNodeWithTag("frame_0").assertIsDisplayed()
        compose.onNodeWithTag("frame_1").assertIsDisplayed()
        compose.onNodeWithTag("frame_2").assert(isSelected())
        compose.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun `the add button adds a frame after the current one`() {
        showBottomBar()
        compose.onNodeWithTag("add_frame").performClick()
        settle()
        assertEquals(2, frames.size)
        assertEquals(frames[1].id, session.state.value.frameId)
    }

    @Test
    fun `tapping a frame selects it`() {
        showBottomBar()
        session.addFrame()
        settle()
        val first = frames[0].id
        compose.onNodeWithTag("frame_0").performClick()
        settle()
        assertEquals(first, session.state.value.frameId)
    }

    @Test
    fun `a long press opens the frame menu`() {
        showBottomBar()
        compose.onNodeWithTag("frame_0").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Duplicate").assertIsDisplayed()
        compose.onNodeWithText("Insert before").assertIsDisplayed()
        compose.onNodeWithText("Delete frame").assertIsDisplayed()
    }

    @Test
    fun `the frame menu duplicates`() {
        showBottomBar()
        compose.onNodeWithTag("frame_0").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Duplicate").performClick()
        settle()
        assertEquals(2, frames.size)
    }

    @Test
    fun `the frame menu inserts before`() {
        showBottomBar()
        val original = frames[0].id
        compose.onNodeWithTag("frame_0").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Insert before").performClick()
        settle()
        assertEquals(original, frames[1].id)
    }

    @Test
    fun `the frame menu deletes`() {
        showBottomBar()
        session.addFrame()
        settle()
        compose.onNodeWithTag("frame_1").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Delete frame").performClick()
        settle()
        assertEquals(1, frames.size)
    }

    @Test
    fun `TalkBack can reorder through named actions`() {
        showBottomBar()
        session.addFrame()
        settle()
        val first = frames[0].id
        compose.onNodeWithTag("frame_0").performSemanticsActionNamed("Move later")
        settle()
        assertEquals(first, frames[1].id)
    }

    private fun SemanticsNodeInteraction.performSemanticsActionNamed(label: String) {
        val node = fetchSemanticsNode()
        val action = node.config[SemanticsActions.CustomActions].first { it.label == label }
        compose.runOnUiThread { action.action() }
    }

    @Test
    fun `undo and redo buttons follow the history`() {
        showBottomBar()
        compose.onNodeWithTag("undo_button").assertIsNotEnabled()
        compose.onNodeWithTag("add_frame").performClick()
        settle()
        compose.onNodeWithTag("undo_button").assertIsEnabled()
        compose.onNodeWithTag("redo_button").assertIsNotEnabled()
        compose.onNodeWithTag("undo_button").performClick()
        settle()
        assertEquals(1, frames.size)
        compose.onNodeWithTag("redo_button").assertIsEnabled().performClick()
        settle()
        assertEquals(2, frames.size)
    }

    @Test
    fun `phones hide secondary actions in a menu, tablets show them`() {
        showBottomBar(wide = false)
        compose.onNode(hasContentDescription("Copy frame")).assertDoesNotExist()
        compose.onNode(hasContentDescription("More actions")).performClick()
        settle()
        compose.onNodeWithText("Copy frame").assertIsDisplayed()
    }

    @Test
    fun `the wide layout shows everything in a row`() {
        showBottomBar(wide = true)
        compose.onNode(hasContentDescription("Copy frame")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Paste frame")).assertIsNotEnabled()
    }

    @Test
    fun `copy then paste makes a new frame`() {
        showBottomBar(wide = true)
        compose.onNode(hasContentDescription("Copy frame")).performClick()
        settle()
        compose.onNode(hasContentDescription("Paste frame")).assertIsEnabled().performClick()
        settle()
        assertEquals(2, frames.size)
    }

    @Test
    fun `play and layers call back`() {
        var played = 0
        var layers = 0
        showBottomBar(onPlay = { played++ }, onLayers = { layers++ })
        compose.onNodeWithTag("play_button").performClick()
        compose.onNodeWithTag("layers_button").performClick()
        assertEquals(1, played)
        assertEquals(1, layers)
    }

    /** Lays out the whole editor around a stand-in for the canvas, to check the layout by eye. */
    @Test
    fun `the editor chrome fits on a small phone`() {
        val thumbnails = ThumbnailLoader(store, 4 * 1024 * 1024)
        repeat(5) { session.addFrame() }
        settle()
        compose.setContent {
            val state by session.state.collectAsState()
            KineographTheme(darkTheme = false) {
                Surface {
                    Column(Modifier.fillMaxSize()) {
                        EditorTopBar(state, {}, {}, {}, {}, {}, {})
                        Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                            EditorToolBar(ToolState(), ToolbarSide.LEFT, {}, {}, {})
                            QuickSliders(ToolState().copy(tool = CanvasTool.BRUSH), ToolbarSide.LEFT, {}, {})
                            Box(Modifier.background(Color.White).fillMaxWidth().background(Color.White))
                        }
                        EditorBottomBar(state, session, thumbnails, false, {}, {})
                    }
                }
            }
        }
        settle()
        compose.onNodeWithTag("tool_brush").assertIsDisplayed()
        compose.onNodeWithTag("tool_color").assertIsDisplayed()
        compose.onNodeWithTag("frame_strip").assertIsDisplayed()
        compose.onNodeWithTag("play_button").assertIsDisplayed()
        compose.onNodeWithTag("export_button").assertIsDisplayed()
        compose.onRoot().saveScreenshot("editor_phone")
    }

    /** Touch targets must be at least 48 dp. */
    @Test
    fun `buttons meet the minimum touch target`() {
        showBottomBar(wide = true)
        for (tag in listOf("undo_button", "redo_button", "add_frame", "play_button", "layers_button")) {
            val size = compose.onNodeWithTag(tag).fetchSemanticsNode().size
            val minimum = with(compose.density) { 48.dp.roundToPx() }
            assertEquals(true, size.width >= minimum && size.height >= minimum)
        }
    }
}
