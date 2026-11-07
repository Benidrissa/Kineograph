package org.kineograph.feature.editor

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
import org.kineograph.model.PlaybackMode
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

    private fun SemanticsNodeInteraction.assertAtLeast48Dp(): SemanticsNodeInteraction {
        val size = fetchSemanticsNode().size
        val minimum = with(compose.density) { 48.dp.roundToPx() }
        assertTrue("$size", size.width >= minimum && size.height >= minimum)
        return this
    }

    /** Touch targets must be at least 48 dp. */
    @Test
    fun `buttons meet the minimum touch target`() {
        showBottomBar(wide = true)
        for (tag in listOf("undo_button", "redo_button", "add_frame", "play_button", "layers_button")) {
            compose.onNodeWithTag(tag).assertAtLeast48Dp()
        }
    }

    @Test
    fun `a frame cell on a tall canvas still meets it`() {
        session.resizeCanvas(1080, 1920)
        settle()
        showBottomBar()
        compose.onNodeWithTag("frame_0").assertAtLeast48Dp()
    }

    // ----- Tapping and TalkBack on the frame strip -----

    @Test
    fun `selecting a frame works without touch`() {
        showBottomBar()
        session.addFrame()
        settle()
        val first = frames[0].id
        compose.onNodeWithTag("frame_0").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        assertEquals(first, session.state.value.frameId)
    }

    @Test
    fun `a frame cell is one thing to TalkBack, which says how long a held frame lasts`() {
        session.setHold(frames[0].id, 3)
        settle()
        showBottomBar()
        val node = compose.onNodeWithTag("frame_0")
        node.assertContentDescriptionEquals("Frame 1 of 1, shown 3 times as long")
        assertEquals("Select this frame", node.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        assertEquals(
            "the number and the badge are in the same node",
            listOf(
                "1",
                "×3",
            ),
            node.fetchSemanticsNode().config[SemanticsProperties.Text].map {
                it.text
            },
        )
    }

    // ----- Dragging a frame -----

    /**
     * Holds on a cell until the long press fires. The strip's clock runs for as long as a frame is being dragged, so nothing would
     * ever be idle if the test clock ran by itself. It goes by hand from here until [lift].
     */
    private fun pressAndHold(tag: String, at: TouchInjectionScope.() -> Offset = { center }) {
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(tag).performTouchInput { down(at()) }
        compose.mainClock.advanceTimeBy(1000)
    }

    /** Moves the finger by [by] pixels along the strip, in two steps, since the first only gets it past the menu's slop. */
    private fun moveAlong(by: Float) {
        compose.onNodeWithTag("frame_strip").performTouchInput { moveBy(Offset(by, 0f)) }
        compose.mainClock.advanceTimeBy(100)
    }

    private fun lift() {
        compose.onNodeWithTag("frame_strip").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        settle()
    }

    private val stepPx get() = with(compose.density) { 104.dp.toPx() - 0.4f } // A 1280 x 720 cell is 99.6 dp wide, then the gap.

    @Test
    fun `a frame can be dragged past where the last frame was when it was first touched`() {
        showBottomBar()
        session.addFrame()
        settle()
        val second = frames[1].id
        compose.onNodeWithTag("frame_1").performClick()
        settle()
        // Added after frame 2, so it is now further along than the gesture on that cell has ever known about.
        session.addFrame()
        session.selectFrame(second)
        settle()
        assertEquals(3, frames.size)

        pressAndHold("frame_1")
        moveAlong(stepPx)
        lift()
        assertEquals(2, frames.indexOfFirst { it.id == second })
    }

    @Test
    fun `a drag that ends where it started does not reorder or open the menu`() {
        showBottomBar()
        session.addFrame()
        settle()
        val order = frames.map { it.id }
        pressAndHold("frame_0")
        moveAlong(60f)
        moveAlong(-60f)
        lift()
        assertEquals(order, frames.map { it.id })
        compose.onNodeWithText("Duplicate").assertDoesNotExist()
    }

    @Test
    fun `holding a frame at the end of the strip carries it past what fitted on screen, as one undo step`() {
        repeat(19) { session.addFrame() }
        session.selectFrame(frames[0].id)
        settle()
        showBottomBar()
        val moved = frames[0].id
        val strip = compose.onNodeWithTag("frame_strip").fetchSemanticsNode().boundsInRoot
        val start = compose
            .onNodeWithTag("frame_0")
            .fetchSemanticsNode()
            .boundsInRoot.center.x

        pressAndHold("frame_0")
        // To just short of the strip's end.
        moveAlong(strip.right - with(compose.density) { 20.dp.toPx() } - start)
        // A finger held still sends nothing, so this is only the strip's own clock.
        compose.mainClock.advanceTimeBy(3000)
        val dragged = compose.onNodeWithTag("frame_0").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "it has stayed under the finger, by the strip's end: $dragged in $strip",
            dragged.width > 0f && dragged.right >= strip.right - 1f,
        )
        lift()

        val landed = frames.indexOfFirst { it.id == moved }
        assertTrue("one drag used to manage only 3 places, and got to $landed", landed > 3)
        session.undo()
        settle()
        assertEquals("all of that in one step", moved, frames[0].id)
    }

    @Test
    fun `a long press near the end of the strip opens the menu without scrolling it`() {
        repeat(19) { session.addFrame() }
        session.selectFrame(frames[0].id)
        settle()
        showBottomBar()
        val order = frames.map { it.id }
        // Near the right edge of the strip, with more frames to scroll to.
        val viewport = compose.onNodeWithTag("frame_strip").fetchSemanticsNode().boundsInRoot
        val cell = compose.onNodeWithTag("frame_2").fetchSemanticsNode().boundsInRoot
        pressAndHold("frame_2") { Offset(viewport.right - cell.left - 4f, centerY) }
        compose.mainClock.advanceTimeBy(1000)
        lift()
        compose.onNodeWithText("Duplicate").assertIsDisplayed()
        assertEquals(order, frames.map { it.id })
        compose.onNodeWithTag("frame_0").assertExists()
    }

    // ----- The tool bar -----

    private fun showToolBar(
        tools: ToolState = ToolState(),
        onSelect: (CanvasTool) -> Unit = {},
        onOpenColor: () -> Unit = {},
        onSideChange: (ToolbarSide) -> Unit = {},
    ) {
        compose.setContent {
            KineographTheme(darkTheme = false) {
                Surface {
                    Box(Modifier.fillMaxSize()) { EditorToolBar(tools, ToolbarSide.LEFT, onSideChange, onSelect, onOpenColor) }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the handle meets the minimum touch target, as much when the bar is open as when not`() {
        showToolBar()
        compose.onNodeWithTag("toolbar_handle").assertAtLeast48Dp().performClick()
        compose.onNodeWithTag("tool_brush").assertDoesNotExist()
        compose.onNodeWithTag("toolbar_handle").assertAtLeast48Dp()
    }

    @Test
    fun `TalkBack can move the tool bar to the other side, which otherwise takes a drag`() {
        var side: ToolbarSide? = null
        showToolBar(onSideChange = { side = it })
        compose.onNodeWithTag("toolbar_handle").performSemanticsActionNamed("Move the tool bar to the other side")
        assertEquals(ToolbarSide.RIGHT, side)
    }

    @Test
    @Config(qualifiers = "w740dp-h360dp-xhdpi")
    fun `on a phone in landscape every tool can be scrolled to, at full size, and the handle stays`() {
        val thumbnails = ThumbnailLoader(store, 4 * 1024 * 1024)
        var color = 0
        // The whole editor, because it is what is left over from the bars that is too short.
        compose.setContent {
            val state by session.state.collectAsState()
            KineographTheme(darkTheme = false) {
                Surface {
                    Column(Modifier.fillMaxSize()) {
                        EditorTopBar(state, {}, {}, {}, {}, {}, {})
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            EditorToolBar(ToolState(), ToolbarSide.LEFT, {}, {}, onOpenColor = { color++ })
                        }
                        EditorBottomBar(state, session, thumbnails, true, {}, {})
                    }
                }
            }
        }
        settle()
        val handle = compose.onNodeWithTag("toolbar_handle").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("tool_brush", "tool_eraser", "tool_lasso", "tool_fill", "tool_color")) {
            compose
                .onNodeWithTag(tag)
                .performScrollTo()
                .assertIsDisplayed()
                .assertAtLeast48Dp()
        }
        compose.onNodeWithTag("tool_color").performClick()
        assertEquals(1, color)
        assertEquals(handle, compose.onNodeWithTag("toolbar_handle").fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun `TalkBack can tell which tool is on, and that tapping it again opens its options`() {
        showToolBar()
        val brush = compose.onNodeWithTag("tool_brush").assertIsSelected()
        assertEquals("Open tool options", brush.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        val eraser = compose.onNodeWithTag("tool_eraser").assertIsNotSelected()
        assertEquals(null, eraser.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
    }

    @Test
    fun `a selected tool with no options does not promise any, and its tap still works`() {
        var tapped: CanvasTool? = null
        showToolBar(tools = ToolState(tool = CanvasTool.LASSO), onSelect = { tapped = it })
        val lasso = compose.onNodeWithTag("tool_lasso").assertIsSelected()
        assertEquals(null, lasso.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        compose.onNodeWithTag("tool_brush").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(CanvasTool.BRUSH, tapped)
    }

    @Test
    fun `a button that is not a toggle tells TalkBack nothing about being selected`() {
        showBottomBar()
        assertFalse(
            compose
                .onNodeWithTag("undo_button")
                .fetchSemanticsNode()
                .config
                .contains(SemanticsProperties.Selected),
        )
    }

    // ----- The quick sliders -----

    @Test
    @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun `the quick sliders leave the rest of a tall canvas's edge for strokes`() {
        compose.setContent {
            KineographTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize()) { QuickSliders(ToolState(), ToolbarSide.LEFT, {}, {}) }
            }
        }
        compose.waitForIdle()
        val size = compose.onNodeWithTag("slider_size").fetchSemanticsNode().boundsInRoot
        val opacity = compose.onNodeWithTag("slider_opacity").fetchSemanticsNode().boundsInRoot
        assertTrue("from ${size.top} to ${opacity.bottom}", with(compose.density) { (opacity.bottom - size.top).toDp() } <= 400.dp)
        compose.onNodeWithTag("slider_size").assertAtLeast48Dp()
    }

    // ----- The playback bar -----

    private fun showPlaybackBar(state: PlaybackState = PlaybackState(isPlaying = true), onPause: () -> Unit = {}, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                KineographTheme(darkTheme = false) {
                    Surface { PlaybackBar(session.state.value.project, state, {}, onPause, { _: PlaybackMode -> }, {}) }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the playback bar has a pause that turns into a resume`() {
        var pauses = 0
        showPlaybackBar(onPause = { pauses++ })
        compose
            .onNodeWithTag("pause_button")
            .assertContentDescriptionEquals("Pause")
            .assertIsEnabled()
            .performClick()
        assertEquals(1, pauses)
    }

    @Test
    fun `a paused bar offers to resume`() {
        showPlaybackBar(PlaybackState(isPlaying = true, isPaused = true))
        compose.onNodeWithTag("pause_button").assertContentDescriptionEquals("Resume").assertIsEnabled()
    }

    @Test
    fun `there is nothing to pause on until a frame is up`() {
        showPlaybackBar(PlaybackState(isPlaying = true, isPreparing = true))
        compose.onNodeWithTag("pause_button").assertIsNotEnabled()
    }

    @Test
    fun `the frame rate buttons say what they change, not just a symbol`() {
        showPlaybackBar()
        compose.onNodeWithTag("fps_down").assertContentDescriptionEquals("Decrease frames per second").assertAtLeast48Dp()
        compose.onNodeWithTag("fps_up").assertContentDescriptionEquals("Increase frames per second").assertAtLeast48Dp()
        assertFalse(
            compose
                .onNodeWithTag("fps_up")
                .fetchSemanticsNode()
                .config
                .contains(SemanticsProperties.Text),
        )
    }

    @Test
    fun `at the slowest rate there is no going lower`() {
        session.setFps(1)
        settle()
        showPlaybackBar()
        compose.onNodeWithTag("fps_down").assertIsNotEnabled()
        compose.onNodeWithTag("fps_up").assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "w320dp-h533dp-xhdpi")
    fun `every playback control stays on screen on a small phone at the largest font size`() {
        showPlaybackBar(fontScale = 2f)
        for (tag in listOf("stop_button", "pause_button", "fps_down", "fps_up")) {
            compose.onNodeWithTag(tag).assertIsDisplayed().assertAtLeast48Dp()
        }
    }

    // ----- Sheets -----

    private fun showSheetContent(fontScale: Float = 1f, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                KineographTheme(darkTheme = false) { Surface { content() } }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a switch tells TalkBack whether it is on`() {
        var checked by mutableStateOf(false)
        showSheetContent { LabelledSwitch("Grid", checked) { checked = it } }
        compose.onNodeWithText("Grid").assertIsOff().performClick()
        compose.onNodeWithText("Grid").assertIsOn()
    }

    @Test
    fun `a long switch label is not cut off at the largest font size`() {
        val label = "Simulate pressure when drawing with a finger"
        showSheetContent(fontScale = 2f) { LabelledSwitch(label, true) { } }
        val row = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        assertTrue("$row", with(compose.density) { row.height.toDp() } > 48.dp)
    }

    @Test
    fun `a short switch label still leaves a full touch target`() {
        showSheetContent { LabelledSwitch("Grid", true) { } }
        compose.onNodeWithText("Grid").assertAtLeast48Dp()
    }

    private fun showLayers() {
        val thumbnails = ThumbnailLoader(store, 4 * 1024 * 1024)
        compose.setContent {
            val state by session.state.collectAsState()
            KineographTheme(darkTheme = false) { LayersSheet(state, session, thumbnails) { } }
        }
        settle()
    }

    private fun layerNames() = session.state.value.project.layers
        .map { it.name }

    private fun withLayers(vararg names: String) {
        names.forEach { session.addLayer(it) }
        settle()
    }

    @Test
    fun `the layer handle meets the minimum touch target`() {
        showLayers()
        compose.onNodeWithTag("layer_handle_Layer 1", useUnmergedTree = true).assertAtLeast48Dp()
    }

    @Test
    fun `TalkBack can reorder layers, and says where each one is`() {
        withLayers("B", "C")
        showLayers()
        assertEquals(listOf("Layer 1", "B", "C"), layerNames())
        compose.onNodeWithTag("layer_row_B").assertContentDescriptionEquals("B, 2 of 3").performSemanticsActionNamed("Move up")
        settle()
        assertEquals(listOf("Layer 1", "C", "B"), layerNames())
        compose.onNodeWithTag("layer_row_B").assertContentDescriptionEquals("B, 1 of 3").performSemanticsActionNamed("Move down")
        settle()
        assertEquals(listOf("Layer 1", "B", "C"), layerNames())
    }

    @Test
    fun `the actions on a layer's row move that layer however often it is used`() {
        withLayers("B", "C")
        showLayers()
        // Taken once and used twice: the row has to stay with its layer, as TalkBack's focus does.
        val row = compose.onNodeWithTag("layer_row_Layer 1")
        row.performSemanticsActionNamed("Move up")
        settle()
        row.performSemanticsActionNamed("Move up")
        settle()
        assertEquals(listOf("B", "C", "Layer 1"), layerNames())
    }

    @Test
    fun `a layer at the end of the stack only offers the way it can go`() {
        withLayers("B")
        showLayers()
        fun actions(tag: String) = compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .map { it.label }
        assertEquals(listOf("Move down"), actions("layer_row_B"))
        assertEquals(listOf("Move up"), actions("layer_row_Layer 1"))
    }

    @Test
    fun `dragging a layer lands on the row it was let go over, counting the gaps between rows`() {
        withLayers("B", "C", "D", "E")
        showLayers()
        val rowPitch = with(compose.density) { (64.dp + 12.dp).toPx() }
        compose.onNodeWithTag("layer_handle_E", useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(Offset(0f, viewConfiguration.touchSlop + 1f))
            moveBy(Offset(0f, rowPitch * 3))
            up()
        }
        settle()
        assertEquals("three rows down the list, not four", listOf("Layer 1", "E", "B", "C", "D"), layerNames())
    }
}
