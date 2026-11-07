package org.kineograph.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kineograph.engine.session.DrawingSession
import org.kineograph.engine.session.MemoryBudget
import org.kineograph.media.svg.SvgAnimationInfo
import org.kineograph.media.svg.SvgLimits
import org.kineograph.media.svg.SvgParser
import org.kineograph.media.svg.SvgWarning
import org.kineograph.media.svgimport.SvgFit
import org.kineograph.media.svgimport.SvgImportOutcome
import org.kineograph.media.svgimport.SvgImportPlace
import org.kineograph.media.svgimport.SvgImportRequest
import org.kineograph.media.svgimport.SvgImporter
import org.kineograph.media.svgimport.SvgSampling
import org.kineograph.media.svgimport.maxImportFrames
import org.kineograph.model.Background
import org.kineograph.model.FrameSlot
import org.kineograph.model.PaperKind
import org.kineograph.model.Project
import org.kineograph.model.ProjectEdits
import org.kineograph.model.newProject
import org.kineograph.ui.KineographTheme
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

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

private const val INTRO =
    """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">
      <style>.a { animation: k 3s linear forwards } .b { animation: k .5s steps(1) infinite } @keyframes k { to { opacity: 0 } }</style>
      <rect class="a" width="50" height="50"/><rect class="b" y="50" width="50" height="50"/>
    </svg>"""

private const val UNEVEN =
    """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect width="50" height="50">
      <animate attributeName="x" to="10" dur="7s" repeatCount="indefinite"/>
      <animate attributeName="y" to="10" dur="11s" repeatCount="indefinite"/>
      <animate attributeName="opacity" to="0" dur="13s" repeatCount="indefinite"/>
    </rect></svg>"""

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
    private var colors: ColorScheme? = null

    private fun show(
        file: SvgFile,
        dark: Boolean = false,
        canDraw: Boolean = true,
        problem: SvgImportOutcome? = null,
        start: SvgImportChoices = SvgImportChoices.startingFor(file),
        reduceMotion: Boolean = true,
        targetProject: Project = project,
        frame: Int = 3,
        freeMemory: Long = 0L,
    ) {
        var choices by mutableStateOf(start)
        currentChoices = start
        compose.setContent {
            KineographTheme(darkTheme = dark) {
                colors = MaterialTheme.colorScheme
                Surface(Modifier.fillMaxSize()) {
                    Box {
                        SvgImportForm(
                            file = file,
                            project = targetProject,
                            frameNumber = frame,
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
                            availableMemory = freeMemory,
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
        compose.onNodeWithTag("svg_import_motion").assertTextContains("Animated, 2.0\u00A0s, loops")
        compose.onNodeWithText("512 × 512 · 14 kB", substring = true, ignoreCase = true).assertIsDisplayed()
    }

    @Test
    fun `a file that plays once says that instead`() {
        show(file(ONCE))
        compose.onNodeWithTag("svg_import_motion").assertTextContains("Animated, 1.5\u00A0s, plays once")
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
        compose.onNodeWithTag("svg_import_frames").assertTextContains("2.0\u00A0s at 12 fps = 24 frames")
    }

    @Test
    fun `a project with another rate gets another count`() {
        show(file(ANIMATED), targetProject = project.copy(fps = 24))
        compose.onNodeWithTag("svg_import_frames").assertTextContains("2.0\u00A0s at 24 fps = 48 frames")
    }

    @Test
    fun `the duration can be edited, and the text follows`() {
        show(file(ANIMATED))
        compose.onNodeWithTag("svg_import_duration").performTextClearance()
        compose.onNodeWithTag("svg_import_duration").performTextInput("3,5")
        compose.waitForIdle()
        compose.onNodeWithTag("svg_import_frames").assertTextContains("3.5\u00A0s at 12 fps = 42 frames")
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
        compose.onNodeWithTag("svg_import_frames").assertTextContains("2.0\u00A0s at 12 fps = 24 frames")
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
    fun `an import that would not fit in what memory is free says so beforehand`() {
        val canvas = project.width.toLong() * project.height * 4
        show(file(ANIMATED), freeMemory = canvas)
        compose
            .onNodeWithTag("svg_import_low_memory")
            .performScrollTo()
            .assertTextContains("low on free memory", substring = true)
    }

    @Test
    fun `an import with room to spare says nothing about memory`() {
        show(file(ANIMATED), freeMemory = 8L * 1024 * 1024 * 1024)
        compose.onNodeWithTag("svg_import_low_memory").assertDoesNotExist()
    }

    @Test
    fun `what an import holds in memory does not grow with the canvas past a set amount waiting`() {
        // A small canvas has all its waiting pictures counted in full, and a huge one has what the store lets wait.
        assertEquals(10L * 100 * 100 * 4, importMemoryBytes(100, 100))
        assertEquals(3L * 6000 * 4000 * 4 + 64L * 1024 * 1024, importMemoryBytes(6000, 4000))
        assertFalse(isShortOfMemoryForImport(6000, 4000, 1024L * 1024 * 1024))
        assertTrue(isShortOfMemoryForImport(6000, 4000, 256L * 1024 * 1024))
        assertFalse("a phone that does not say is not refused anything", isShortOfMemoryForImport(6000, 4000, 0L))
    }

    @Test
    fun `what the project already has counts towards an import being too long`() {
        val few = SvgImportChoices.startingFor(file(ANIMATED))
        assertFalse(few.isTooLong(file(ANIMATED), project))
        val long = few.copy(durationText = (maxImportFrames() / project.fps).toString())
        assertFalse(long.copy(durationText = ((maxImportFrames() - 100) / project.fps).toString()).isTooLong(file(ANIMATED), project))
        var bigger = project
        repeat(200) { bigger = ProjectEdits.addFrameAfter(bigger, null, "frame $it") }
        assertTrue(long.copy(durationText = ((maxImportFrames() - 100) / project.fps).toString()).isTooLong(file(ANIMATED), bigger))
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
        compose.onNodeWithText("1.5\u00A0s").assertIsDisplayed()
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
        compose
            .onNodeWithTag(
                "svg_import_problem",
            ).assertIsDisplayed()
            .assertTextContains("This layer is hidden or locked", substring = true)
    }

    @Test
    fun `other places do not mind a locked layer`() {
        show(file(STATIC), canDraw = false)
        compose.onNodeWithTag("svg_import_problem").assertDoesNotExist()
    }

    @Test
    fun `running out of memory or storage is explained where the choices can be changed`() {
        show(file(STATIC), problem = SvgImportOutcome.OUT_OF_MEMORY)
        compose.onNodeWithTag("svg_import_problem").assertIsDisplayed().assertTextContains("enough memory", substring = true)
    }

    @Test
    fun `running out of storage says what to do`() {
        show(file(STATIC), problem = SvgImportOutcome.OUT_OF_STORAGE)
        compose.onNodeWithTag("svg_import_problem").assertIsDisplayed().assertTextContains("Free up some space", substring = true)
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
        val loop = SvgAnimationInfo(isAnimated = true, durationSeconds = 2.0, loopsForever = true, loopStartSeconds = 1.0)
        val motion = PreviewMotion.Playing(SvgSampling.of(loop, 2.0, 2), null, 2)
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
        val frame = project.frames[0].id
        val playing = previewMotion(animated, choices, project, frame, reduceMotion = false) as PreviewMotion.Playing
        assertEquals(24, playing.tickCount)
        assertEquals(1.0, playing.secondsAt(12), 1e-9)
        assertEquals(PreviewMotion.Still(1.0), previewMotion(animated, choices, project, frame, reduceMotion = true))
        assertEquals(
            PreviewMotion.Still(0.5),
            previewMotion(animated, choices.copy(asAnimation = false, momentSeconds = 0.5), project, frame, false),
        )
        // While what was typed is not yet a length of time, the preview carries on with the suggestion.
        val typing = previewMotion(animated, choices.copy(durationText = ""), project, frame, reduceMotion = false) as PreviewMotion.Playing
        assertEquals(24, typing.tickCount)
        val still = file(STATIC)
        assertEquals(PreviewMotion.Still(0.0), previewMotion(still, SvgImportChoices.startingFor(still), project, frame, false))
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

    private fun screenshotMenu(tag: String, dark: Boolean, name: String, text: String = STATIC) {
        show(file(text), dark = dark)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(isPopup()).saveScreenshot(name)
    }

    @Test
    fun `screenshots of the choice of starting point`() {
        screenshotMenu("svg_import_start_from", dark = false, name = "svg_import_start_menu_light", text = INTRO)
    }

    @Test
    fun `screenshots of the choice of starting point, dark`() {
        screenshotMenu("svg_import_start_from", dark = true, name = "svg_import_start_menu_dark", text = INTRO)
    }

    @Test
    fun `screenshots of a refusal with warnings`() {
        val file = file(WITH_WARNINGS)
        show(file, canDraw = false, start = SvgImportChoices.startingFor(file).copy(place = SvgImportPlace.CURRENT_LAYER))
        awaitPreview()
        compose.onRoot().saveScreenshot("svg_import_refused_light")
    }

    @Test
    fun `screenshots of a refusal with warnings, dark`() {
        val file = file(WITH_WARNINGS)
        show(file, dark = true, canDraw = false, start = SvgImportChoices.startingFor(file).copy(place = SvgImportPlace.CURRENT_LAYER))
        awaitPreview()
        compose.onRoot().saveScreenshot("svg_import_refused_dark")
    }

    @Test
    fun `screenshots of a big import, dark`() {
        show(file(ANIMATED), dark = true, start = SvgImportChoices(asAnimation = true, durationText = "100"))
        awaitPreview()
        compose.onNodeWithTag("svg_import_large").performScrollTo()
        compose.onRoot().saveScreenshot("svg_import_large_dark")
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

    // ----- What was found by trying it on real phones and with TalkBack -----

    @Test
    fun `why an import failed is on screen without scrolling, even with a long list of warnings`() {
        show(file(WITH_WARNINGS), problem = SvgImportOutcome.OUT_OF_MEMORY)
        compose.onNodeWithTag("svg_import_problem").assertIsDisplayed().assertTextContains("enough memory", substring = true)
    }

    @Test
    fun `a refusal that can be seen coming holds the button back`() {
        val file = file(STATIC)
        show(file, canDraw = false, start = SvgImportChoices.startingFor(file).copy(place = SvgImportPlace.CURRENT_LAYER))
        compose.onNodeWithTag("svg_import_problem").assertIsDisplayed()
        compose.onNodeWithTag("svg_import_button").assertIsNotEnabled()
    }

    @Test
    fun `more frames than the device can hold explains itself, in place of a count`() {
        show(file(ANIMATED), start = SvgImportChoices(asAnimation = true, durationText = "9999999999"))
        compose.onNodeWithTag("svg_import_problem").assertIsDisplayed().assertTextContains("more frames than this device", substring = true)
        compose.onNodeWithTag("svg_import_button").assertIsNotEnabled()
        compose.onNodeWithTag("svg_import_frames").assertDoesNotExist()
    }

    @Test
    fun `the count is of the frames the timeline will really show`() {
        // Slots held for two ticks each hold one picture for those two ticks.
        show(file(ANIMATED), targetProject = newProjectWithHolds(slots = 12, hold = 2), frame = 1)
        compose.onNodeWithTag("svg_import_frames").assertTextContains("2.0\u00A0s at 12 fps = 12 frames")
    }

    @Test
    fun `slots that run out before the animation does are followed by new ones`() {
        // Twelve ticks in three slots of four, and then twelve more of their own.
        show(file(ANIMATED), targetProject = newProjectWithHolds(slots = 3, hold = 4), frame = 1)
        compose.onNodeWithTag("svg_import_frames").assertTextContains("2.0\u00A0s at 12 fps = 15 frames")
    }

    private fun newProjectWithHolds(slots: Int, hold: Int): Project =
        project.copy(frames = List(slots) { FrameSlot("slot$it", hold) })

    @Test
    fun `an animation that plays once counts its last moment too`() {
        show(file(ONCE))
        compose.onNodeWithTag("svg_import_frames").assertTextContains("1.5 s at 12 fps = 19 frames")
    }

    @Test
    fun `a single slot held for long enough is one frame, in the singular`() {
        show(file(ANIMATED), targetProject = newProjectWithHolds(slots = 1, hold = 24), frame = 1)
        compose.onNodeWithTag("svg_import_frames").assertTextContains("2.0 s at 12 fps = 1 frame")
    }

    @Test
    fun `numbers show as they were typed`() {
        assertEquals("0.125", formatSeconds(0.125, Locale.ENGLISH))
        assertEquals("0.001", formatSeconds(0.001, Locale.ENGLISH))
        assertEquals("0,00012", formatSeconds(0.00012, Locale.FRENCH))
        assertEquals("1.13", formatSeconds(1.125 + 1.0 / 3e6, Locale.ENGLISH))
        assertEquals("0.001", formatSeconds(0.0012345678912345, Locale.ENGLISH))
    }

    @Test
    fun `an intro gets a choice, kept in unless it is left out`() {
        val intro = file(INTRO)
        show(intro)
        compose.onNodeWithTag("svg_import_motion").assertTextContains("Animated, 3.0 s intro, then a 0.5 s loop")
        compose.onNodeWithTag("svg_import_frames").assertTextContains("3.5 s at 12 fps = 42 frames")
        compose.onNodeWithTag("svg_import_start_from").performClick()
        compose.onNodeWithTag("svg_import_start_loop").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(false, currentChoices?.withIntro)
        compose.onNodeWithTag("svg_import_frames").assertTextContains("0.5 s at 12 fps = 6 frames")
        val request = currentChoices?.toRequest(intro) as SvgImportRequest.Animation
        assertEquals(0.5, request.durationSeconds, 1e-9)
        assertFalse(request.withIntro)
    }

    @Test
    fun `a file with no intro is not asked about one`() {
        show(file(ANIMATED))
        compose.onNodeWithTag("svg_import_start_from").assertDoesNotExist()
    }

    @Test
    fun `a loop that would not join up says so`() {
        show(file(UNEVEN))
        compose.onNodeWithTag("svg_import_motion").assertTextContains("out of step", substring = true)
    }

    @Test
    fun `TalkBack hears which option is chosen in an open drop-down`() {
        show(file(STATIC))
        compose.onNodeWithTag("svg_import_fit").performScrollTo().performClick()
        compose
            .onNodeWithTag("svg_import_fit_fit_inside")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        compose
            .onNodeWithTag("svg_import_fit_fill_canvas")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
    }

    @Test
    fun `TalkBack hears whether a drop-down is open`() {
        show(file(STATIC))
        val closed = compose.onNodeWithTag("svg_import_fit").performScrollTo()
        closed.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        closed.performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("svg_import_fit").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    }

    @Test
    fun `the moment's value is read out with its unit`() {
        val animated = file(ANIMATED)
        show(animated, start = SvgImportChoices.startingFor(animated).copy(asAnimation = false, momentSeconds = 1.5))
        compose
            .onNode(hasContentDescription("Moment"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1.5 s"))
    }

    @Test
    fun `the slider itself is big enough to touch`() {
        val animated = file(ANIMATED)
        show(animated, start = SvgImportChoices.startingFor(animated).copy(asAnimation = false))
        val minimum = with(compose.density) { 48.dp.roundToPx() }
        val height = compose
            .onNode(hasContentDescription("Moment"))
            .fetchSemanticsNode()
            .size.height
        assertTrue("it is $height", height >= minimum)
    }

    @Test
    fun `the unit of a duration is read out as a word`() {
        show(file(ANIMATED))
        val node = compose.onNodeWithTag("svg_import_duration").fetchSemanticsNode()
        assertTrue(
            node.config
                .getOrNull(SemanticsProperties.ContentDescription)
                .orEmpty()
                .contains("seconds"),
        )
    }

    @Test
    fun `a big import is a note and not an error`() {
        show(file(ANIMATED), start = SvgImportChoices(asAnimation = true, durationText = "100"))
        val results = ArrayList<TextLayoutResult>()
        compose
            .onNodeWithTag(
                "svg_import_large",
            ).performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(
            colors?.onSurfaceVariant,
            results
                .single()
                .layoutInput.style.color,
        )
    }

    @Test
    fun `a duration typed in moves the way back on to a line of its own`() {
        show(file(ANIMATED), start = SvgImportChoices(asAnimation = true, durationText = "3"))
        val field = compose.onNodeWithTag("svg_import_duration").fetchSemanticsNode().boundsInRoot
        val back = compose
            .onNodeWithTag("svg_import_duration_reset")
            .performScrollTo()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(back.top >= field.bottom)
    }

    @Test
    fun `until there is a picture the preview says so, and it says so if there will not be one`() {
        val target = project
        compose.setContent {
            KineographTheme {
                Column {
                    SvgPreviewBox(target, null, failed = false)
                    SvgPreviewBox(target, null, failed = true)
                }
            }
        }
        compose.onNodeWithTag("svg_import_preview_loading").assertExists()
        compose.onNode(hasContentDescription("Getting the preview ready")).assertExists()
        compose.onNodeWithTag("svg_import_preview_unavailable").assertTextContains("Preview unavailable")
    }

    @Test
    fun `the preview holds still on a slot that is held, as the result will`() {
        val animated = file(ANIMATED)
        val held = newProjectWithHolds(slots = 12, hold = 2)
        val motion = previewMotion(
            animated,
            SvgImportChoices.startingFor(animated),
            held,
            held.frames[0].id,
            reduceMotion = false,
        ) as PreviewMotion.Playing
        assertEquals(24, motion.tickCount)
        assertEquals(0, motion.shownTick(0))
        assertEquals(0, motion.shownTick(1))
        assertEquals(2, motion.shownTick(2))
        assertEquals(22, motion.shownTick(23))
    }

    @Test
    fun `what is being drawn is never what is on screen, or what just was`() {
        val buffers = PreviewBuffers(100, 100)
        val first = buffers.next()
        // Nothing shown yet, so a draw that was cancelled gives the next one the same buffer.
        assertSame(first, buffers.next())
        buffers.nowShowing(first)
        val second = buffers.next()
        assertNotSame(first, second)
        assertSame(second, buffers.next())
        buffers.nowShowing(second)
        val third = buffers.next()
        assertNotSame(first, third)
        assertNotSame(second, third)
        // However many are cancelled before they show.
        repeat(5) { assertSame(third, buffers.next()) }
        buffers.nowShowing(third)
        assertSame(first, buffers.next())
    }

    @Test
    fun `the preview stops drawing when the app is not on screen`() {
        val owner =
            object : LifecycleOwner {
                val registry = LifecycleRegistry.createUnsafe(this)
                override val lifecycle: Lifecycle get() = registry
            }
        owner.registry.currentState = Lifecycle.State.RESUMED
        val animated = file(ANIMATED)
        val motion = previewMotion(animated, SvgImportChoices.startingFor(animated), project, project.frames[0].id, reduceMotion = false)
        val draws = AtomicInteger()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                KineographTheme {
                    SvgPreview(animated.document, project, null, SvgImportChoices.startingFor(animated), motion) { draws.incrementAndGet() }
                }
            }
        }
        compose.waitUntil(WAIT_MILLIS) { draws.get() >= 2 }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        // Anything already under way gets to finish.
        Thread.sleep(300)
        val whenStopped = draws.get()
        Thread.sleep(400)
        assertEquals(whenStopped, draws.get())
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(WAIT_MILLIS) { draws.get() > whenStopped }
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
            makeRoom = {},
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
        compose.onNodeWithTag("svg_import_progress_text").assertIsDisplayed().assert(hasContentDescription("Frame 6 of 24"))
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
    fun `one picture has no count, and no bar that says it is done`() {
        val target = session()
        val file = SvgFile(SvgParser.parse(ANIMATED), "ball.svg", 1)
        compose.setContent {
            KineographTheme(darkTheme = false) {
                SvgImportHost(
                    controller(target),
                    SvgImportState.Running(file, SvgImportChoices.startingFor(file), 1, 1),
                    project,
                    project.frames[0].id,
                    true,
                    null,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Frame 1 of 1").assertDoesNotExist()
        val range = compose.onNodeWithTag("svg_import_progress").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(ProgressBarRangeInfo.Indeterminate, range)
        target.close()
    }

    @Test
    fun `TalkBack knows what the bar is, and hears of progress every so often`() {
        var progress by mutableStateOf(6)
        compose.setContent {
            KineographTheme(darkTheme = false) {
                SvgBusyDialog("Importing…", "Frame $progress of 100", progress / 100f) {}
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("svg_import_progress").assert(hasContentDescription("Importing…"))
        val text = compose.onNodeWithTag("svg_import_progress_text")
        text.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        text.assert(hasContentDescription("Frame 6 of 100"))
        progress = 7
        compose.waitForIdle()
        compose.onNodeWithTag("svg_import_progress_text").assert(hasContentDescription("Frame 6 of 100"))
        progress = 10
        compose.waitForIdle()
        compose.onNodeWithTag("svg_import_progress_text").assert(hasContentDescription("Frame 10 of 100"))
    }

    @Test
    fun `Cancel takes the real sheet away the way a swipe does`() {
        val target = session()
        val controller = controller(target)
        controller.read({ "ball.svg" }, { ByteArrayInputStream(ANIMATED.toByteArray()) })
        compose.waitUntil(WAIT_MILLIS) { controller.state.value is SvgImportState.Ready }
        compose.setContent {
            KineographTheme(darkTheme = false) {
                val state by controller.state.collectAsState()
                SvgImportHost(controller, state, project, project.frames[0].id, true, null)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("svg_import_dismiss").performClick()
        compose.waitUntil(WAIT_MILLIS) { controller.state.value == SvgImportState.Idle }
        target.close()
    }

    @Test
    fun `the editor's messages show over the sheet, which covers where they normally go`() {
        val target = session()
        val file = SvgFile(SvgParser.parse(WITH_WARNINGS), "ball.svg", 1)
        val messages = SnackbarHostState()
        compose.setContent {
            KineographTheme(darkTheme = false) {
                CompositionLocalProvider(LocalEditorSnackbar provides messages) {
                    SvgImportHost(
                        controller(target),
                        SvgImportState.Ready(file, SvgImportChoices.startingFor(file)),
                        project,
                        project.frames[0].id,
                        true,
                        null,
                    )
                }
                LaunchedEffect(Unit) { messages.showSnackbar("Not saved") }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Not saved").assertExists()
        target.close()
    }

    @Test
    fun `why an import failed is in view on a small phone, in the real sheet`() {
        val target = session()
        val file = SvgFile(SvgParser.parse(WITH_WARNINGS), "ball.svg", 1)
        compose.setContent {
            KineographTheme(darkTheme = false) {
                SvgImportHost(
                    controller(target),
                    SvgImportState.Ready(file, SvgImportChoices.startingFor(file), SvgImportOutcome.OUT_OF_MEMORY),
                    project,
                    project.frames[0].id,
                    true,
                    null,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("svg_import_problem").assertIsDisplayed()
        compose.onNodeWithTag("svg_import_button").assertIsDisplayed()
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
        compose.onNodeWithTag("svg_import_motion").assertTextContains("Animation de 2,0\u00A0s, en boucle")
        compose.onNodeWithTag("svg_import_frames").assertTextContains("2,0\u00A0s à 12 i/s = 24 images")
        compose.onNodeWithTag("svg_import_button").assertIsDisplayed()
        compose.onNodeWithText("Importer").assertIsDisplayed()
        compose.awaitPreview("Aperçu du dessin sur votre canevas")
        compose.onRoot().saveScreenshot("svg_import_french")
    }
}

/** The longest words the sheet has, at the largest text size the system offers, on a narrow phone. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "fr-w360dp-h740dp-xhdpi", fontScale = 2f)
class SvgImportSheetLargeTextTest {
    @get:Rule
    val compose = createComposeRule()

    private val project = newProject("Mine", 1280, 720, 12, Background.Paper(PaperKind.WHITE), "Calque 1", now = 0)

    private fun show(choices: SvgImportChoices, file: SvgFile) {
        compose.setContent {
            KineographTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize()) {
                    SvgImportForm(
                        file = file,
                        project = project,
                        frameNumber = 1,
                        canDrawOnActiveLayer = true,
                        choices = choices,
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
    }

    @Test
    fun `a drop-down's value wraps instead of being cut off in the middle of a letter`() {
        val file = SvgFile(SvgParser.parse(STATIC), "dessin.svg", 1)
        show(SvgImportChoices.startingFor(file), file)
        val results = ArrayList<TextLayoutResult>()
        compose
            .onNodeWithTag(
                "svg_import_fit",
            ).performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        assertTrue("it has ${layout.lineCount} line", layout.lineCount >= 2)
        assertFalse(layout.didOverflowWidth)
    }

    @Test
    fun `the buttons can grow with their words`() {
        val file = SvgFile(SvgParser.parse(STATIC), "dessin.svg", 1)
        show(SvgImportChoices.startingFor(file), file)
        val results = ArrayList<TextLayoutResult>()
        val minimum = with(compose.density) { 48.dp.roundToPx() }
        for (tag in listOf("svg_import_button", "svg_import_dismiss")) {
            val node = compose.onNodeWithTag(tag)
            assertTrue(node.fetchSemanticsNode().size.height >= minimum)
            results.clear()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertFalse("the words of $tag do not fit", results.single().didOverflowHeight)
        }
    }

    @Test
    fun `the way back to the suggestion leaves the field its width`() {
        val file = SvgFile(SvgParser.parse(ANIMATED), "balle.svg", 1)
        show(SvgImportChoices(asAnimation = true, durationText = "3600,25"), file)
        val width = compose
            .onNodeWithTag("svg_import_duration")
            .performScrollTo()
            .fetchSemanticsNode()
            .size.width
        assertTrue(width >= with(compose.density) { 280.dp.roundToPx() })
    }
}
