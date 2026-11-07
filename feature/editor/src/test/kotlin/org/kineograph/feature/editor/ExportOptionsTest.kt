package org.kineograph.feature.editor

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.kineograph.media.export.ExportConfig
import org.kineograph.media.export.ExportFormat
import org.kineograph.media.export.ExportStatus
import org.kineograph.media.video.EncoderLimits
import org.kineograph.model.Background
import org.kineograph.model.FrameSlot
import org.kineograph.model.Project
import org.kineograph.model.newProject
import org.kineograph.ui.KineographTheme
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w480dp-h4000dp-xhdpi")
class ExportOptionsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun project(width: Int = 1280, height: Int = 720, frames: Int = 1, background: Background = Background.Transparent): Project {
        val base = newProject("Test", width, height, 12, background, "Layer", 0L)
        return base.copy(frames = List(frames) { FrameSlot("f$it") })
    }

    private var exported: ExportConfig? = null

    @Before
    fun setUp() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun show(project: Project) {
        compose.setContent {
            KineographTheme(darkTheme = false) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    ExportOptions(project) { exported = it }
                }
            }
        }
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun export(): ExportConfig {
        tap("start_export")
        compose.waitForIdle()
        return checkNotNull(exported)
    }

    private fun size(text: String) {
        compose.onNodeWithTag("export_size").assertTextContains("Size: $text")
    }

    private fun note() = compose.onAllNodesWithTag("export_size_note").fetchSemanticsNodes()

    // ----- The vertical preset -----

    @Test
    fun `the vertical preset on a landscape project makes a vertical video and says how`() {
        show(project())
        tap("preset_VERTICAL")
        size("1080 × 1920")
        val config = export()
        assertEquals(1080, config.frameWidth)
        assertEquals(1920, config.frameHeight)
        assertEquals(ExportFormat.MP4, config.format)
    }

    @Test
    fun `the vertical preset on a square project does not make a bigger square`() {
        show(project(1080, 1080))
        tap("preset_VERTICAL")
        size("1080 × 1920")
        assertEquals(1f, export().scale)
    }

    @Test
    fun `choosing a scale afterwards goes back to the picture's own shape`() {
        show(project())
        tap("preset_VERTICAL")
        tap("scale_1.5")
        size("1920 × 1080")
        assertNull(export().frameWidth)
    }

    @Test
    fun `another preset afterwards goes back to the picture's own shape`() {
        show(project())
        tap("preset_VERTICAL")
        tap("preset_YOUTUBE")
        size("1280 × 720")
        assertNull(export().frameWidth)
    }

    @Test
    fun `switching to a GIF shows the size the GIF will really be, and says it is not vertical`() {
        show(project())
        tap("preset_VERTICAL")
        tap("format_gif")
        size("1080 × 608")
        compose.onNodeWithText("A GIF takes your picture's own shape, so this one won't be vertical.").assertExists()
        tap("format_mp4")
        size("1080 × 1920")
    }

    // ----- Choosing any size -----

    @Test
    fun `a small canvas can go well past four times its size`() {
        show(project(64, 64))
        compose.onNodeWithTag("export_longest_edge").performScrollTo().performTextInput("1024")
        size("1024 × 1024")
        assertEquals(16f, export().scale)
    }

    @Test
    fun `a typed size replaces the vertical frame`() {
        show(project())
        tap("preset_VERTICAL")
        compose.onNodeWithTag("export_longest_edge").performScrollTo().performTextInput("640")
        size("640 × 360")
        assertNull(export().frameWidth)
    }

    @Test
    fun `something other than a size changes nothing`() {
        show(project())
        val field = compose.onNodeWithTag("export_longest_edge").performScrollTo()
        field.performTextInput("abc")
        size("1280 × 720")
        field.performTextReplacement("0")
        size("1280 × 720")
        field.performTextReplacement("99999999999999")
        size("1280 × 720")
        assertEquals(1f, export().scale)
    }

    @Test
    fun `a typed size is cleared by choosing a scale`() {
        show(project())
        compose.onNodeWithTag("export_longest_edge").performScrollTo().performTextInput("640")
        tap("scale_2.0")
        size("2560 × 1440")
        assertEquals(
            "",
            compose
                .onNodeWithTag("export_longest_edge")
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text,
        )
        assertEquals(2f, export().scale)
    }

    @Test
    fun `the scale for a longest edge is worked out from the longer side`() {
        assertEquals(8f, scaleForLongestEdge("512", 64, 64))
        assertEquals(0.5f, scaleForLongestEdge("640", 720, 1280))
        assertNull(scaleForLongestEdge("", 64, 64))
        assertNull(scaleForLongestEdge("0", 64, 64))
        assertNull(scaleForLongestEdge("99999999999", 64, 64))
    }

    @Test
    fun `the vertical preset's scale fits the frame`() {
        assertEquals(0.84375f, ExportPreset.VERTICAL.scaleFor(1280, 720), 0.0001f)
        assertEquals(1f, ExportPreset.VERTICAL.scaleFor(1080, 1080), 0.0001f)
        assertEquals(1f, ExportPreset.VERTICAL.scaleFor(1080, 1920), 0.0001f)
        assertEquals(1.5f, ExportPreset.YOUTUBE.scaleFor(853, 480), 0.01f)
    }

    // ----- What it says about the size -----

    private class FakeLimits(
        override val maxWidth: Int = 4096,
        override val maxHeight: Int = 4096,
        override val widthAlignment: Int = 2,
        override val heightAlignment: Int = 2,
        override val minWidth: Int = 1,
        override val minHeight: Int = 1,
    ) : EncoderLimits {
        override fun isSizeSupported(width: Int, height: Int) =
            width in minWidth..maxWidth && height in minHeight..maxHeight && width % widthAlignment == 0 && height % heightAlignment == 0

        override fun areSizeAndRateSupported(width: Int, height: Int, fps: Double) = isSizeSupported(width, height)
    }

    private fun videoConfig(project: Project, scale: Float = 1f) = ExportConfig(project.id, ExportFormat.MP4, scale = scale)

    @Test
    fun `a size the encoder can do needs no note`() {
        val project = project()
        assertEquals(emptyList<ExportSizeNote>(), exportSizeNotes(project, videoConfig(project), null, FakeLimits()))
    }

    @Test
    fun `a size the encoder cannot do is mentioned before the export starts, with what will happen instead`() {
        val project = project(64, 64)
        val notes = exportSizeNotes(project, videoConfig(project, 100f), null, FakeLimits(maxWidth = 1920, maxHeight = 1088))
        assertEquals(1, notes.size)
        val note = notes.single() as ExportSizeNote.Reduced
        assertEquals(note.width, note.height)
        assertTrue(note.height <= 1088)
    }

    @Test
    fun `bars for the encoder's sake are mentioned before the export starts`() {
        val project = project(1920, 1080)
        val notes = exportSizeNotes(project, videoConfig(project), null, FakeLimits(widthAlignment = 16, heightAlignment = 16))
        assertEquals(listOf<ExportSizeNote>(ExportSizeNote.Padded(1920, 1088)), notes)
    }

    @Test
    fun `an encoder's minimum size is mentioned, and is not a failure`() {
        val project = project(64, 64)
        val notes = exportSizeNotes(project, videoConfig(project), null, FakeLimits(minWidth = 96, minHeight = 96))
        assertEquals(listOf<ExportSizeNote>(ExportSizeNote.Padded(96, 96)), notes)
    }

    @Test
    fun `no encoder means saying so`() {
        val project = project()
        assertEquals(listOf<ExportSizeNote>(ExportSizeNote.Unsupported), exportSizeNotes(project, videoConfig(project), null, null))
    }

    @Test
    fun `a GIF never depends on the encoder`() {
        val project = project()
        val config = ExportConfig(project.id, ExportFormat.GIF)
        assertEquals(emptyList<ExportSizeNote>(), exportSizeNotes(project, config, null, null))
    }

    @Test
    fun `a GIF too large for the format is mentioned`() {
        val project = project(8192, 1)
        val notes = exportSizeNotes(project, ExportConfig(project.id, ExportFormat.GIF, scale = 10f), null, null)
        assertEquals(listOf<ExportSizeNote>(ExportSizeNote.GifReduced(65_535, 8)), notes)
    }

    @Test
    fun `the note for the vertical frame gives the real numbers`() {
        val project = project()
        val config = videoConfig(project).copy(frameWidth = 1080, frameHeight = 1920)
        val notes = exportSizeNotes(project, config, VERTICAL_FRAME, FakeLimits())
        assertEquals(listOf<ExportSizeNote>(ExportSizeNote.FitsInFrame(1080, 1920, 1080, 608)), notes)
    }

    @Test
    fun `a picture already that shape needs no note for the vertical frame`() {
        val project = project(1080, 1920)
        val config = videoConfig(project).copy(frameWidth = 1080, frameHeight = 1920)
        assertEquals(emptyList<ExportSizeNote>(), exportSizeNotes(project, config, VERTICAL_FRAME, FakeLimits()))
    }

    @Test
    fun `the vertical frame on a capped encoder mentions both things`() {
        val project = project()
        val config = videoConfig(project).copy(frameWidth = 1080, frameHeight = 1920)
        val notes = exportSizeNotes(project, config, VERTICAL_FRAME, FakeLimits(maxWidth = 1920, maxHeight = 1088))
        assertEquals(2, notes.size)
        assertTrue(notes[0] is ExportSizeNote.Reduced)
        assertTrue(notes[1] is ExportSizeNote.FitsInFrame)
    }

    @Test
    fun `a portrait picture stays vertical as a GIF, so there is nothing to warn about`() {
        val project = project(768, 1024)
        val config = ExportConfig(project.id, ExportFormat.GIF)
        assertEquals(emptyList<ExportSizeNote>(), exportSizeNotes(project, config, VERTICAL_FRAME, null))
    }

    // ----- Exact frames -----

    private fun valueOf(tag: String) = compose
        .onNodeWithTag(tag)
        .fetchSemanticsNode()
        .config[SemanticsProperties.Text]
        .joinToString()

    @Test
    fun `frame two of three hundred can be chosen`() {
        show(project(frames = 300))
        tap("export_first_up")
        compose.onNodeWithTag("export_range").assertTextContains("Frames 2 to 300")
        val config = export()
        assertEquals(1, config.rangeStart)
        assertEquals(299, config.rangeEnd)
    }

    @Test
    fun `only the last frame can be left out of a long animation`() {
        show(project(frames = 300))
        tap("export_last_down")
        val config = export()
        assertEquals(0, config.rangeStart)
        assertEquals(298, config.rangeEnd)
    }

    @Test
    fun `moving the other end of the slider keeps an exact frame where it was put`() {
        show(project(frames = 300))
        tap("export_first_up")
        val thumbs =
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        // The end of the range is the second thumb.
        thumbs[1].performSemanticsAction(SemanticsActions.SetProgress) { it(250f) }
        compose.waitForIdle()
        val config = export()
        assertEquals("the first frame must not have moved to the nearest stop", 1, config.rangeStart)
        assertEquals(250, config.rangeEnd)
    }

    @Test
    fun `the ends cannot cross or leave the animation`() {
        show(project(frames = 3))
        compose.onNodeWithTag("export_first_down").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("export_last_up").performScrollTo().assertIsNotEnabled()
        tap("export_first_up")
        tap("export_first_up")
        compose.onNodeWithTag("export_first_up").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("export_last_down").performScrollTo().assertIsNotEnabled()
        assertEquals("3", valueOf("export_first_value"))
        tap("export_first_down")
        compose.onNodeWithTag("export_last_down").performScrollTo().assertIsEnabled()
    }

    @Test
    fun `stepping the range stays inside it`() {
        assertEquals(0f..5f, moveRangeStart(0f..5f, -1))
        assertEquals(5f..5f, moveRangeStart(5f..5f, 1))
        assertEquals(1f..5f, moveRangeStart(0f..5f, 1))
        assertEquals(2f..2f, moveRangeEnd(2f..2f, -1, 9))
        assertEquals(0f..9f, moveRangeEnd(0f..9f, 1, 9))
        assertEquals(0f..4f, moveRangeEnd(0f..5f, -1, 9))
    }

    @Test
    fun `every frame has a stop, or there are no stops to get pulled onto`() {
        assertEquals(0, rangeSliderSteps(1))
        assertEquals(1, rangeSliderSteps(2))
        assertEquals(100, rangeSliderSteps(101))
        assertEquals(0, rangeSliderSteps(102))
        assertEquals(0, rangeSliderSteps(299))
    }

    @Test
    fun `steppers can be told apart by someone who cannot see them`() {
        show(project(frames = 10))
        for (description in listOf(
            "First frame: one earlier",
            "First frame: one later",
            "Last frame: one earlier",
            "Last frame: one later",
        )) {
            compose
                .onNode(
                    SemanticsMatcher("has description $description") { node ->
                        node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
                    },
                    useUnmergedTree = true,
                ).assertExists()
        }
    }

    // ----- The background switch -----

    @Test
    fun `leaving out the background for a video says what the drawing will go on`() {
        show(project(background = Background.Color(0xFF102030.toInt())))
        compose.onNode(hasTestTag("export_video_fill_note")).assertDoesNotExist()
        compose.onNodeWithText("Include the background").performScrollTo().performClick()
        compose.onNode(hasTestTag("export_video_fill_note")).assertExists()
        assertFalse(export().includeBackground)
    }

    @Test
    fun `a GIF can be see-through, so there is nothing to explain`() {
        show(project(background = Background.Color(0xFF102030.toInt())))
        compose.onNodeWithText("Include the background").performScrollTo().performClick()
        tap("format_gif")
        compose.onNode(hasTestTag("export_video_fill_note")).assertDoesNotExist()
    }

    @Test
    fun `a project with no background has no switch, and video still explains itself`() {
        show(project(background = Background.Transparent))
        compose.onNodeWithText("Include the background").assertDoesNotExist()
        compose.onNode(hasTestTag("export_video_fill_note")).assertExists()
    }

    // ----- How it looks -----

    private fun SemanticsNodeInteraction.saveScreenshot(name: String) {
        val directory = System.getenv("KG_SCREENSHOT_DIR") ?: return
        File(directory).mkdirs()
        File(directory, "$name.png").outputStream().use { captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `the options read in both themes`() {
        var dark by mutableStateOf(false)
        val long = project(frames = 300, background = Background.Color(0xFF102030.toInt()))
        compose.setContent {
            KineographTheme(darkTheme = dark) {
                Surface {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        ExportOptions(long) { exported = it }
                    }
                }
            }
        }
        tap("preset_VERTICAL")
        compose.onNodeWithText("Include the background").performClick()
        compose.waitForIdle()
        compose.onRoot().saveScreenshot("export_options_light")
        dark = true
        compose.waitForIdle()
        compose.onRoot().saveScreenshot("export_options_dark")
        compose.onNode(hasTestTag("export_video_fill_note")).assertExists()
    }

    @Test
    fun `the result reads in both themes`() {
        var dark by mutableStateOf(false)
        compose.setContent {
            KineographTheme(darkTheme = dark) {
                ExportDialog(ExportUiState(finished(wasReduced = true, wasPadded = true), saving = true), actions)
            }
        }
        compose.waitForIdle()
        compose.onNode(isDialog()).saveScreenshot("export_result_light")
        dark = true
        compose.waitForIdle()
        compose.onNode(isDialog()).saveScreenshot("export_result_dark")
        compose.onNodeWithTag("export_saving").assertExists()
    }

    // ----- The result -----

    private var dismissed = 0
    private var saves = 0
    private var shown = 0
    private var handedOff = 0

    private val actions =
        ExportDialogActions(
            onCancel = {},
            onDismiss = { dismissed++ },
            onSave = { saves++ },
            onHandedOff = { handedOff++ },
            onResultShown = { shown++ },
        )

    private fun finished(wasReduced: Boolean = false, wasPadded: Boolean = false) =
        ExportStatus.Finished(File("/nowhere/out.gif"), ExportFormat.GIF, 100, 100, wasReduced, wasPadded)

    private fun showDialog(state: ExportUiState) {
        compose.setContent {
            KineographTheme(darkTheme = false) {
                ExportDialog(state, actions)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `while saving, the dialog cannot be closed and cannot start a second save`() {
        showDialog(ExportUiState(finished(), saving = true))
        compose.onNodeWithTag("export_saving").assertExists()
        compose.onNodeWithTag("export_save").assertIsNotEnabled()
        compose.onNodeWithTag("export_done").assertIsNotEnabled()
    }

    @Test
    fun `saving is handed to whoever keeps the state`() {
        showDialog(ExportUiState(finished()))
        compose.onNodeWithTag("export_save").performClick()
        assertEquals(1, saves)
    }

    @Test
    fun `once saved it says so, and cannot be saved again`() {
        showDialog(ExportUiState(finished(), saved = true))
        compose.onNodeWithText("Saved to your gallery.").assertExists()
        compose.onNodeWithTag("export_save").assertIsNotEnabled()
        compose.onNodeWithTag("export_done").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `a failed save says so and can be tried again`() {
        showDialog(ExportUiState(finished(), saveFailed = true))
        compose.onNodeWithText("Could not save to the gallery. You can still share it.").assertExists()
        compose.onNodeWithTag("export_save").assertIsEnabled()
    }

    @Test
    fun `bars are mentioned along with a reduced size, each by itself`() {
        showDialog(ExportUiState(finished(wasReduced = true, wasPadded = true)))
        compose.onNodeWithText("Bars were added around your picture so it keeps its shape at this video size.").assertExists()
        compose.onNodeWithText("The size you chose was too large to encode, so it was reduced to the largest that works.").assertExists()
    }

    @Test
    fun `no bars means no mention of bars`() {
        showDialog(ExportUiState(finished()))
        compose.onNodeWithText("Bars were added around your picture so it keeps its shape at this video size.").assertDoesNotExist()
    }

    @Test
    fun `seeing the result is noted, so its notification can go`() {
        showDialog(ExportUiState(finished()))
        assertEquals(1, shown)
    }

    @Test
    fun `a failure's result is noted too`() {
        showDialog(ExportUiState(ExportStatus.Failed("storage")))
        assertEquals(1, shown)
        compose.onNodeWithText("There isn't enough storage to finish. Free up some space and try again.").assertExists()
    }

    @Test
    fun `a file Android cleared is explained`() {
        showDialog(ExportUiState(ExportStatus.Failed("file_gone")))
        compose.onNodeWithText("Android cleared the finished file to make room before it was saved. Please export again.").assertExists()
    }
}
