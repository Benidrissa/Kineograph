package org.kineograph.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kineograph.engine.session.DrawingSession
import org.kineograph.engine.session.MemoryBudget
import org.kineograph.media.svg.SvgLimits
import org.kineograph.media.svg.SvgParser
import org.kineograph.media.svg.SvgWarning
import org.kineograph.media.svgimport.SvgFit
import org.kineograph.media.svgimport.SvgImportOutcome
import org.kineograph.media.svgimport.SvgImportPlace
import org.kineograph.media.svgimport.SvgImporter
import org.kineograph.model.Background
import org.kineograph.model.PaperKind
import org.kineograph.model.Project
import org.kineograph.model.newProject
import org.kineograph.ui.KineographTheme
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

private const val ANIMATED =
    """<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 100 100">
      <rect width="100" height="100" fill="#ffe0aa"/>
      <circle cx="20" cy="50" r="16" fill="#0f6e6e"><animate attributeName="cx" values="20;80;20" dur="2s" repeatCount="indefinite"/></circle>
    </svg>"""

private const val ONCE =
    """<svg xmlns="http://www.w3.org/2000/svg" width="64" height="32"><rect width="20" height="20" fill="#b23a1e">
    <animate attributeName="x" from="0" to="40" dur="1.5s" fill="freeze"/></rect></svg>"""

private const val STATIC =
    """<svg xmlns="http://www.w3.org/2000/svg" width="300" height="150"><circle cx="150" cy="75" r="60" fill="#b23a1e"/></svg>"""

private const val WITH_WARNINGS =
    """<svg xmlns="http://www.w3.org/2000/svg" width="300" height="150">
      <script>alert(1)</script>
      <foreignObject width="10" height="10"/>
      <filter id="f"><feGaussianBlur stdDeviation="2"/></filter>
      <rect width="300" height="150" fill="#7fd6d2" filter="url(#f)"/>
      <circle cx="150" cy="75" r="60" fill="#0f6e6e"><animate attributeName="r" to="10" dur="1s" begin="click"/></circle>
    </svg>"""

private fun SemanticsNodeInteraction.saveScreenshot(name: String) {
    val directory = System.getenv("KG_SCREENSHOT_DIR") ?: return
    File(directory).mkdirs()
    File(directory, "$name.png").outputStream().use { captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
}

private const val WAIT_MILLIS = 30_000L

/** The image only exists once the first frame has been drawn, away from the main thread. */
private fun ComposeContentTestRule.awaitPreview(description: String) {
    waitUntil(WAIT_MILLIS) { onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty() }
    waitForIdle()
}

/** The sheet on a phone 360 dp wide at the normal font size. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi")
class SvgImportSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val project = newProject("Mine", 1280, 720, 12, Background.Paper(PaperKind.WHITE), "Layer 1", now = 0)

    private fun file(text: String, name: String = "bouncing ball.svg"): SvgFile = SvgFile(SvgParser.parse(text), name, 14_000)

    private var imported = 0
    private var dismissed = 0
    private var currentChoices: SvgImportChoices? = null

    private fun show(
        file: SvgFile,
        dark: Boolean = false,
        canDraw: Boolean = true,
        problem: SvgImportOutcome? = null,
        start: SvgImportChoices = SvgImportChoices.startingFor(file),
        reduceMotion: Boolean = true,
        targetProject: Project = project,
    ) {
        var choices by mutableStateOf(start)
        currentChoices = start
        compose.setContent {
            KineographTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize()) {
                    Box {
                        SvgImportForm(
                            file = file,
                            project = targetProject,
                            frameNumber = 3,
                            canDrawOnActiveLayer = canDraw,
                            choices = choices,
                            problem = problem,
                            backgroundImage = null,
                            onChange = {
                                choices = it
                                currentChoices = it
                            },
                            onImport = { imported++ },
                            onDismiss = { dismissed++ },
                            reduceMotion = reduceMotion,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The preview draws away from the main thread, so a picture of the sheet has to wait for it. */
    private fun awaitPreview() {
        compose.awaitPreview("Preview of the picture on your canvas")
    }

    private fun previewPixels(): Bitmap = compose.onNodeWithTag("svg_import_preview").captureToImage().asAndroidBitmap()

    // ----- What it shows -----

    @Test
    fun `an animated file says so, for how long, and that it loops`() {
        show(file(ANIMATED))
        compose.onNodeWithText("bouncing ball.svg").assertIsDisplayed()
        compose.onNodeWithTag("svg_import_motion").assertTextContains("Animated, 2.0 s, loops")
        compose.onNodeWithText("512 × 512 · 14 kB", substring = true, ignoreCase = true).assertIsDisplayed()
    }

    @Test
    fun `a file that plays once says that instead`() {
        show(file(ONCE))
        compose.onNodeWithTag("svg_import_motion").assertTextContains("Animated, 1.5 s, plays once")
    }

    @Test
    fun `a static file is a single image, with no choice about it`() {
        show(file(STATIC))
        compose.onNodeWithTag("svg_import_motion").assertTextContains("Still picture")
        compose.onNodeWithTag("svg_import_as").assertDoesNotExist()
        compose.onNodeWithTag("svg_import_duration").assertDoesNotExist()
        compose.onNodeWithTag("svg_import_moment").assertDoesNotExist()
        compose.onNodeWithTag("svg_import_where").assertIsDisplayed()
    }

    @Test
    fun `an animation shows its length in frames at the project's rate`() {
        show(file(ANIMATED))
        compose.onNodeWithTag("svg_import_frames").assertTextContains("2.0 s at 12 fps = 24 frames")
    }

    @Test
    fun `a project with another rate gets another count`() {
        show(file(ANIMATED), targetProject = project.copy(fps = 24))
        compose.onNodeWithTag("svg_import_frames").assertTextContains("2.0 s at 24 fps = 48 frames")
    }

    @Test
    fun `one frame is singular`() {
        show(file(ANIMATED), start = SvgImportChoices(asAnimation = true, durationText = "0.08"))
        compose.onNodeWithTag("svg_import_frames").assertTextContains("0.08 s at 12 fps = 1 frame")
    }

    @Test
    fun `the duration can be edited, and the text follows`() {
        show(file(ANIMATED))
        compose.onNodeWithTag("svg_import_duration").performTextClearance()
        compose.onNodeWithTag("svg_import_duration").performTextInput("3,5")
        compose.waitForIdle()
        compose.onNodeWithTag("svg_import_frames").assertTextContains("3.5 s at 12 fps = 42 frames")
        assertEquals("3,5", currentChoices?.durationText)
    }

    @Test
    fun `the suggested duration can be brought back exactly`() {
        show(file(ANIMATED))
        compose.onNodeWithTag("svg_import_duration_reset").assertDoesNotExist()
        compose.onNodeWithTag("svg_import_duration").performTextClearance()
        compose.onNodeWithTag("svg_import_duration").performTextInput("3")
        compose.waitForIdle()
        compose.onNodeWithTag("svg_import_duration_reset").performClick()
        compose.waitForIdle()
        assertEquals(null, currentChoices?.durationText)
        compose.onNodeWithTag("svg_import_frames").assertTextContains("2.0 s at 12 fps = 24 frames")
    }

    @Test
    fun `an unusable duration explains itself and holds the button back`() {
        show(file(ANIMATED))
        compose.onNodeWithTag("svg_import_duration").performTextClearance()
        compose.waitForIdle()
        compose.onNodeWithTag("svg_import_button").assertIsNotEnabled()
        compose.onNodeWithText("Enter a duration in seconds, more than 0.").assertExists()
        compose.onNodeWithTag("svg_import_duration").performTextInput("0")
        compose.waitForIdle()
        compose.onNodeWithTag("svg_import_button").assertIsNotEnabled()
        compose.onNodeWithTag("svg_import_duration").performTextInput(".5")
        compose.waitForIdle()
        compose.onNodeWithTag("svg_import_button").assertIsEnabled()
    }

    @Test
    fun `a big import gets a gentle warning and is still allowed`() {
        show(file(ANIMATED), start = SvgImportChoices(asAnimation = true, durationText = "100"))
        compose.onNodeWithTag("svg_import_large").performScrollTo().assertTextContains("big import", substring = true)
        compose.onNodeWithTag("svg_import_button").assertIsEnabled()
    }

    @Test
    fun `a normal import gets no such warning`() {
        show(file(ANIMATED))
        compose.onNodeWithTag("svg_import_large").assertDoesNotExist()
    }

    @Test
    fun `what counts as big depends on the frames and on the phone`() {
        assertFalse(isLargeImport(24, 1280, 720, HEAP))
        assertTrue(isLargeImport(1000, 64, 64, HEAP))
        assertTrue(isLargeImport(100, 3840, 2160, HEAP))
        assertFalse(isLargeImport(100, 1280, 720, HEAP))
        assertTrue(isLargeImport(100, 1280, 720, HEAP / 64))
        assertEquals(24L * 100 * 100 * 4 / 8, likelyStorageBytes(24, 100, 100))
    }

    @Test
    fun `seconds read naturally in each language`() {
        assertEquals("2.0", formatSeconds(2.0, Locale.ENGLISH))
        assertEquals("2,0", formatSeconds(2.0, Locale.FRENCH))
        assertEquals("1.75", formatSeconds(1.75, Locale.ENGLISH))
        assertEquals("0.33", formatSeconds(1.0 / 3.0, Locale.ENGLISH))
        assertEquals("100000.0", formatSeconds(100_000.0, Locale.ENGLISH))
    }

    // ----- Choices -----

    @Test
    fun `an animation always goes on a new layer from the current frame`() {
        show(file(ANIMATED))
        compose.onNodeWithTag("svg_import_animation_where").assertTextContains("New layer, starting at frame 3")
        compose.onNodeWithTag("svg_import_where").assertDoesNotExist()
        compose.onNodeWithTag("svg_import_moment").assertDoesNotExist()
    }

    @Test
    fun `switching to a single image offers the moment and where it goes`() {
        show(file(ANIMATED))
        compose.onNodeWithTag("svg_import_as").performClick()
        compose.onNodeWithTag("svg_import_as_image").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(false, currentChoices?.asAnimation)
        compose.onNodeWithTag("svg_import_duration").assertDoesNotExist()
        compose.onNodeWithTag("svg_import_moment").assertExists()
        compose.onNodeWithTag("svg_import_where").assertExists()
    }

    @Test
    fun `the slider picks the moment, up to the end of the animation`() {
        val animated = file(ANIMATED)
        show(animated, start = SvgImportChoices.startingFor(animated).copy(asAnimation = false))
        compose.onNode(hasContentDescription("Moment")).performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
        compose.waitForIdle()
        assertEquals(1.5, currentChoices?.momentSeconds ?: 0.0, 1e-3)
        compose.onNodeWithText("1.5 s").assertIsDisplayed()
        compose.onNode(hasContentDescription("Moment")).performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        compose.waitForIdle()
        assertEquals(2.0, currentChoices?.momentSeconds ?: 0.0, 1e-3)
    }

    @Test
    fun `where a single image goes defaults to a new layer and can change`() {
        show(file(STATIC))
        assertEquals(SvgImportPlace.NEW_LAYER, currentChoices?.place)
        compose.onNodeWithTag("svg_import_where").performClick()
        compose.onNodeWithTag("svg_import_where_new_layer").assertIsDisplayed()
        compose.onNodeWithTag("svg_import_where_current_layer").assertIsDisplayed()
        compose.onNodeWithTag("svg_import_where_background").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(SvgImportPlace.BACKGROUND, currentChoices?.place)
    }

    @Test
    fun `fit defaults to fitting inside and offers the others`() {
        show(file(STATIC))
        assertEquals(SvgFit.FIT_INSIDE, currentChoices?.fit)
        compose.onNodeWithTag("svg_import_fit").performScrollTo().performClick()
        compose.onNodeWithTag("svg_import_fit_fit_inside").assertIsDisplayed()
        compose.onNodeWithTag("svg_import_fit_fill_canvas").assertIsDisplayed()
        compose.onNodeWithTag("svg_import_fit_original_size").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(SvgFit.ORIGINAL_SIZE, currentChoices?.fit)
    }

    @Test
    fun `the buttons do what they say`() {
        show(file(STATIC))
        compose.onNodeWithTag("svg_import_button").performClick()
        compose.onNodeWithTag("svg_import_dismiss").performClick()
        assertEquals(1, imported)
        assertEquals(1, dismissed)
    }

    // ----- Warnings and problems -----

    @Test
    fun `warnings from the file are in plain words`() {
        show(file(WITH_WARNINGS))
        compose.onNodeWithText("Scripts don't run here", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Filter effects such as blurs and shadows were skipped.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Web content embedded in the file was skipped.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Animations that wait for a click", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a locked current layer gets the usual message as soon as it is chosen`() {
        val file = file(STATIC)
        show(file, canDraw = false, start = SvgImportChoices.startingFor(file).copy(place = SvgImportPlace.CURRENT_LAYER))
        compose.onNodeWithTag("svg_import_problem").performScrollTo().assertTextContains("This layer is hidden or locked", substring = true)
    }

    @Test
    fun `other places do not mind a locked layer`() {
        show(file(STATIC), canDraw = false)
        compose.onNodeWithTag("svg_import_problem").assertDoesNotExist()
    }

    @Test
    fun `running out of memory or storage is explained where the choices can be changed`() {
        show(file(STATIC), problem = SvgImportOutcome.OUT_OF_MEMORY)
        compose.onNodeWithTag("svg_import_problem").performScrollTo().assertTextContains("enough memory", substring = true)
    }

    @Test
    fun `running out of storage says what to do`() {
        show(file(STATIC), problem = SvgImportOutcome.OUT_OF_STORAGE)
        compose.onNodeWithTag("svg_import_problem").performScrollTo().assertTextContains("Free up some space", substring = true)
    }

    // ----- Layout and accessibility -----

    @Test
    fun `Import is on screen without scrolling, even with a long list of warnings`() {
        show(file(WITH_WARNINGS))
        compose.onNodeWithTag("svg_import_button").assertIsDisplayed()
        compose.onNodeWithTag("svg_import_dismiss").assertIsDisplayed()
    }

    @Test
    fun `Import is on screen for a full animated form`() {
        show(file(ANIMATED))
        compose.onNodeWithTag("svg_import_button").assertIsDisplayed()
        compose.onNodeWithTag("svg_import_fit").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("svg_import_button").assertIsDisplayed()
    }

    @Test
    fun `the controls are big enough to touch`() {
        show(file(ANIMATED))
        val minimum = with(compose.density) { 48.dp.roundToPx() }
        for (tag in listOf("svg_import_button", "svg_import_dismiss", "svg_import_as", "svg_import_duration", "svg_import_fit")) {
            val size = compose.onNodeWithTag(tag).fetchSemanticsNode().size
            assertTrue("$tag is ${size.width} by ${size.height}", size.width >= minimum && size.height >= minimum)
        }
    }

    @Test
    fun `so are the controls of a single image`() {
        val animated = file(ANIMATED)
        show(animated, start = SvgImportChoices.startingFor(animated).copy(asAnimation = false))
        val minimum = with(compose.density) { 48.dp.roundToPx() }
        for (tag in listOf("svg_import_moment", "svg_import_where")) {
            val size = compose.onNodeWithTag(tag).fetchSemanticsNode().size
            assertTrue("$tag is ${size.width} by ${size.height}", size.width >= minimum && size.height >= minimum)
        }
    }

    @Test
    fun `TalkBack can tell what the preview and the moment are`() {
        val animated = file(ANIMATED)
        show(animated, start = SvgImportChoices.startingFor(animated).copy(asAnimation = false))
        awaitPreview()
        compose.onNode(hasContentDescription("Moment")).assertExists()
    }

    // ----- The preview -----

    @Test
    fun `the preview shows the picture on the project's background, at the chosen fit`() {
        show(file(STATIC))
        awaitPreview()
        val inside = previewPixels()
        // The picture is a red circle in the middle of the white canvas.
        assertEquals(Color.WHITE, inside.getPixel(inside.width / 8, inside.height / 2))
        assertTrue(Color.red(inside.getPixel(inside.width / 2, inside.height / 2)) > 150)
        assertTrue(Color.green(inside.getPixel(inside.width / 2, inside.height / 2)) < 100)
    }

    @Test
    fun `a changed fit shows up in the preview`() {
        val file = file(STATIC)
        show(file, start = SvgImportChoices.startingFor(file).copy(fit = SvgFit.ORIGINAL_SIZE))
        awaitPreview()
        // At its original size the circle is small on a canvas this big, so a point off to the side of it stays white.
        val point = previewPixels().let { it.getPixel(it.width * 3 / 8, it.height / 2) }
        assertEquals(Color.WHITE, point)
    }

    @Test
    fun `with animations turned off, the preview holds still`() {
        show(file(ANIMATED), reduceMotion = true)
        awaitPreview()
        val first = previewPixels()
        compose.mainClock.advanceTimeBy(1_000)
        Thread.sleep(400)
        compose.waitForIdle()
        assertTrue(first.sameAs(previewPixels()))
    }

    @Test
    fun `otherwise it plays`() {
        show(file(ANIMATED), reduceMotion = false)
        awaitPreview()
        val first = previewPixels()
        compose.waitUntil(WAIT_MILLIS) { !first.sameAs(previewPixels()) }
    }

    @Test
    fun `a preview plays the moments that would be imported, then starts again`() {
        val motion = PreviewMotion.Playing(startSeconds = 1.0, tickCount = 4, fps = 2)
        assertEquals(0, motion.tickAt(0))
        assertEquals(1, motion.tickAt(500_000_000L))
        assertEquals(3, motion.tickAt(1_999_000_000L))
        assertEquals(0, motion.tickAt(2_000_000_000L))
        assertEquals(1.0, motion.secondsAt(0), 1e-9)
        assertEquals(2.5, motion.secondsAt(3), 1e-9)
        assertEquals(500L, motion.millisToNextTick(0))
        assertEquals(100L, motion.millisToNextTick(400_000_000L))
    }

    @Test
    fun `what the preview does follows the choices`() {
        val animated = file(ANIMATED)
        val choices = SvgImportChoices.startingFor(animated)
        assertEquals(PreviewMotion.Playing(0.0, 24, 12), previewMotion(animated, choices, 12, reduceMotion = false))
        assertEquals(PreviewMotion.Still(1.0), previewMotion(animated, choices, 12, reduceMotion = true))
        assertEquals(PreviewMotion.Still(0.5), previewMotion(animated, choices.copy(asAnimation = false, momentSeconds = 0.5), 12, false))
        // While what was typed is not yet a length of time, the preview carries on with the suggestion.
        assertEquals(PreviewMotion.Playing(0.0, 24, 12), previewMotion(animated, choices.copy(durationText = ""), 12, reduceMotion = false))
        assertEquals(PreviewMotion.Still(0.0), previewMotion(file(STATIC), SvgImportChoices.startingFor(file(STATIC)), 12, false))
    }

    // ----- To look at, in both themes -----

    @Test
    fun `screenshots of an animated file`() {
        show(file(ANIMATED))
        awaitPreview()
        compose.onRoot().saveScreenshot("svg_import_animated_light")
    }

    @Test
    fun `screenshots of an animated file, dark`() {
        show(file(ANIMATED), dark = true)
        awaitPreview()
        compose.onRoot().saveScreenshot("svg_import_animated_dark")
    }

    @Test
    fun `screenshots of a single image with warnings`() {
        val animated = file(WITH_WARNINGS, "a rather long file name that will not fit on one line at all.svg")
        show(animated)
        awaitPreview()
        compose.onRoot().saveScreenshot("svg_import_static_light")
    }

    @Test
    fun `screenshots of a single image with warnings, dark`() {
        val animated = file(WITH_WARNINGS, "a rather long file name that will not fit on one line at all.svg")
        show(animated, dark = true)
        awaitPreview()
        compose.onRoot().saveScreenshot("svg_import_static_dark")
    }

    @Test
    fun `screenshots of a single image taken from an animation`() {
        val animated = file(ANIMATED)
        show(animated, start = SvgImportChoices.startingFor(animated).copy(asAnimation = false, momentSeconds = 1.0))
        awaitPreview()
        compose.onRoot().saveScreenshot("svg_import_moment_light")
    }

    @Test
    fun `screenshots of a single image taken from an animation, dark`() {
        val animated = file(ANIMATED)
        show(animated, dark = true, start = SvgImportChoices.startingFor(animated).copy(asAnimation = false, momentSeconds = 1.0))
        awaitPreview()
        compose.onRoot().saveScreenshot("svg_import_moment_dark")
    }

    private fun screenshotMenu(tag: String, dark: Boolean, name: String) {
        show(file(STATIC), dark = dark)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(isPopup()).saveScreenshot(name)
    }

    @Test
    fun `screenshots of the fit menu`() {
        screenshotMenu("svg_import_fit", dark = false, name = "svg_import_fit_menu_light")
    }

    @Test
    fun `screenshots of the fit menu, dark`() {
        screenshotMenu("svg_import_fit", dark = true, name = "svg_import_fit_menu_dark")
    }

    @Test
    fun `screenshots of the where menu`() {
        screenshotMenu("svg_import_where", dark = false, name = "svg_import_where_menu_light")
    }

    @Test
    fun `screenshots of the where menu, dark`() {
        screenshotMenu("svg_import_where", dark = true, name = "svg_import_where_menu_dark")
    }

    @Test
    fun `what drawing finds out is added to the warnings`() {
        val body = (0 until 1000).joinToString("") { """<rect width="5" height="5"/>""" }
        val heavy = """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10">$body</svg>"""
        val tooMuch = SvgParser.parse(heavy, SvgLimits.forDevice().copy(maxRenderWork = 1L))
        assertFalse(SvgWarning.LIMIT_REACHED in tooMuch.warnings)
        show(SvgFile(tooMuch, "heavy.svg", 1), targetProject = project.copy(width = 10, height = 10))
        compose.waitUntil(WAIT_MILLIS) {
            compose.onAllNodes(hasTestTag("svg_import_warning_limit_reached")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("svg_import_warning_limit_reached").performScrollTo().assertIsDisplayed()
    }

    private companion object {
        const val HEAP = 256L * 1024 * 1024
    }
}

/** The running parts of an import, as they appear in the sheet's host. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi")
class SvgImportProgressTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private val project = newProject("Mine", 1280, 720, 12, Background.Paper(PaperKind.WHITE), "Layer 1", now = 0)

    private fun controller(session: DrawingSession): SvgImportController =
        SvgImportController(
            scope = CoroutineScope(Dispatchers.Default),
            importer = SvgImporter(session, TestStore(), { error("not used") }),
            holdFiles = {},
            releaseFiles = {},
            onMessage = {},
        )

    private fun session(): DrawingSession =
        DrawingSession(project, TestStore(), MemoryBudget.forMemoryClass(256, false), temp.newFolder())

    @Test
    fun `a running import shows progress and a cancel that works, in the dialog it runs under`() {
        val target = session()
        val controller = controller(target)
        val file = SvgFile(SvgParser.parse(ANIMATED), "ball.svg", 1)
        compose.setContent {
            KineographTheme(darkTheme = false) {
                SvgImportHost(
                    controller,
                    SvgImportState.Running(file, SvgImportChoices.startingFor(file), 6, 24),
                    project,
                    project.frames[0].id,
                    true,
                    null,
                )
            }
        }
        compose.waitForIdle()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithTag("svg_import_progress").assertIsDisplayed()
        compose.onNodeWithText("Frame 6 of 24").assertIsDisplayed()
        compose.onNodeWithTag("svg_import_cancel_running").assertIsDisplayed()
        val minimum = with(compose.density) { 48.dp.roundToPx() }
        assertTrue(
            compose
                .onNodeWithTag("svg_import_cancel_running")
                .fetchSemanticsNode()
                .size.height >= minimum,
        )
        target.close()
    }

    @Test
    fun `the real bottom sheet opens with Import on screen`() {
        val target = session()
        val controller = controller(target)
        val file = SvgFile(SvgParser.parse(ANIMATED), "ball.svg", 1)
        compose.setContent {
            KineographTheme(darkTheme = true) {
                SvgImportHost(
                    controller,
                    SvgImportState.Ready(file, SvgImportChoices.startingFor(file)),
                    project,
                    project.frames[0].id,
                    true,
                    null,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("svg_import_button").assertIsDisplayed()
        target.close()
    }
}

/** The sheet in French, which runs longer than English on a narrow phone and writes numbers differently. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "fr-w360dp-h740dp-xhdpi")
class SvgImportSheetFrenchTest {
    @get:Rule
    val compose = createComposeRule()

    private val project = newProject("Mine", 1280, 720, 12, Background.Paper(PaperKind.WHITE), "Calque 1", now = 0)

    @Test
    fun `the words and the numbers are French, and Import is still on screen`() {
        val file = SvgFile(SvgParser.parse(ANIMATED), "balle.svg", 14_000)
        compose.setContent {
            KineographTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize()) {
                    SvgImportForm(
                        file = file,
                        project = project,
                        frameNumber = 3,
                        canDrawOnActiveLayer = true,
                        choices = SvgImportChoices.startingFor(file),
                        problem = null,
                        backgroundImage = null,
                        onChange = {},
                        onImport = {},
                        onDismiss = {},
                        reduceMotion = true,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Importer un SVG").assertIsDisplayed()
        compose.onNodeWithTag("svg_import_motion").assertTextContains("Animé, 2,0 s, en boucle")
        compose.onNodeWithTag("svg_import_frames").assertTextContains("2,0 s à 12 i/s = 24 images")
        compose.onNodeWithTag("svg_import_button").assertIsDisplayed()
        compose.onNodeWithText("Importer").assertIsDisplayed()
        compose.awaitPreview("Aperçu de l’image sur votre canevas")
        compose.onRoot().saveScreenshot("svg_import_french")
    }
}
