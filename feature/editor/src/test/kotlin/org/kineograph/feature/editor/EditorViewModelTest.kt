package org.kineograph.feature.editor

import android.content.Context
import android.graphics.Color
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kineograph.data.ProjectRepository
import org.kineograph.data.SettingsRepository
import org.kineograph.data.db.openProjectDao
import org.kineograph.data.files.ProjectFiles
import org.kineograph.data.manifest.ManifestCodec
import org.kineograph.engine.brush.BrushSpec
import org.kineograph.engine.brush.PressureCurve
import org.kineograph.engine.brush.StampBuffer
import org.kineograph.engine.brush.StrokeEngine
import org.kineograph.engine.session.DrawingSession
import org.kineograph.engine.session.MemoryBudget
import org.kineograph.media.export.ExportManager
import org.kineograph.model.Background
import org.kineograph.model.Project
import org.kineograph.ui.KineographTheme
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi")
class EditorViewModelTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = ViewModelStore()
    private lateinit var files: ProjectFiles
    private lateinit var repository: ProjectRepository

    @Before
    fun setUp() {
        try {
            WorkManager.initialize(context, Configuration.Builder().build())
        } catch (_: IllegalStateException) {
            // Already set up by an earlier test in this process.
        }
        files = ProjectFiles(temp.newFolder(), temp.newFolder())
        repository = ProjectRepository(openProjectDao(context), files, 8L * 1024 * 1024)
    }

    @After
    fun tearDown() {
        store.clear()
        appScope.cancel()
    }

    private fun create(width: Int = 200, height: Int = 100): Project =
        runBlocking { repository.create("Test", width, height, 12, Background.Transparent, "Layer 1") }

    private fun viewModelFor(id: String): EditorViewModel {
        val dependencies =
            EditorDependencies(
                repository,
                SettingsRepository(context),
                MemoryBudget.forMemoryClass(256, isLowRam = false),
                ExportManager(context),
                files::spillDirectory,
                appScope,
            )
        val factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = EditorViewModel(dependencies, id) as T
            }
        return ViewModelProvider(store, factory)[EditorViewModel::class.java]
    }

    private fun EditorViewModel.awaitReady(): EditorLoadState.Ready {
        waitUntil("the editor to open") { load.value !is EditorLoadState.Loading }
        return load.value as EditorLoadState.Ready
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun stroke(session: DrawingSession, y: Float = 50f) {
        val spec = BrushSpec(id = "t", sizePx = 10f, sizeCurve = PressureCurve.FLAT)
        val out = StampBuffer()
        StrokeEngine(spec, 1L).run {
            begin(20f, y, 1f, 0f, 0f, out)
            add(100f, y, 1f, 0f, 0f, out)
            finish(out)
        }
        session.beginStroke(spec, Color.BLACK)
        session.addStamps(out.snapshot())
        session.endStroke { }
    }

    private fun nextMessage(viewModel: EditorViewModel): EditorMessage = runBlocking { withTimeout(WAIT_MS) { viewModel.messages.first() } }

    private fun manifestOf(id: String) = File(files.projectDirectory(id), "manifest.json")

    /** Nothing can be written over a directory, which is the nearest thing here to a disk with no space left. */
    private fun fillDisk(id: String) {
        File(files.projectDirectory(id), "manifest.tmp/keep").mkdirs()
    }

    private fun freeDisk(id: String) {
        File(files.projectDirectory(id), "manifest.tmp").deleteRecursively()
    }

    @Test
    fun `a recovered project's warning reaches a screen that only starts listening once the editor is open`() {
        val project = create()
        runBlocking { repository.rename(project.id, "Saved twice, so there is a backup") }
        manifestOf(project.id).writeText("{ damaged")
        val viewModel = viewModelFor(project.id)
        viewModel.awaitReady()
        assertEquals(EditorMessage.RECOVERED_FROM_BACKUP, nextMessage(viewModel))
    }

    @Test
    fun `missing drawings are mentioned to a screen that only starts listening once the editor is open`() {
        val project = create()
        val first = viewModelFor(project.id)
        val ready = first.awaitReady()
        stroke(ready.session)
        assertTrue(runBlocking { ready.autosaver.saveNow() })
        store.clear()
        runBlocking { repository.open(project.id) }?.store?.close()
        File(files.projectDirectory(project.id), "cels").deleteRecursively()

        val viewModel = viewModelFor(project.id)
        viewModel.awaitReady()
        assertEquals(EditorMessage.MISSING_DRAWINGS, nextMessage(viewModel))
    }

    @Test
    fun `leaving saves first and then goes`() {
        val project = create()
        val viewModel = viewModelFor(project.id)
        val ready = viewModel.awaitReady()
        stroke(ready.session)
        var left = false
        viewModel.requestLeave { left = true }
        waitUntil("leaving") { left }
        assertEquals(1, ManifestCodec.decode(manifestOf(project.id).readText()).cels.size)
    }

    @Test
    fun `leaving is refused when the changes cannot be saved, and the user is asked`() {
        val project = create()
        val viewModel = viewModelFor(project.id)
        val ready = viewModel.awaitReady()
        stroke(ready.session)
        fillDisk(project.id)
        var left = false
        viewModel.requestLeave { left = true }
        waitUntil("the save to fail") { viewModel.unsavedOnLeave.value }
        assertFalse(left)
        assertFalse(viewModel.leaving.value)
        assertEquals(EditorMessage.SAVE_FAILED, nextMessage(viewModel))
    }

    @Test
    fun `saving is tried again on return, for when space was freed in the meantime`() {
        val project = create()
        val viewModel = viewModelFor(project.id)
        val ready = viewModel.awaitReady()
        stroke(ready.session)
        fillDisk(project.id)
        viewModel.requestLeave { }
        waitUntil("the save to fail") { viewModel.unsavedOnLeave.value }

        freeDisk(project.id)
        viewModel.retryFailedSave()
        waitUntil("the save to work") { !viewModel.unsavedOnLeave.value }
        assertFalse(ready.autosaver.saveFailed.value)
        assertEquals(1, ManifestCodec.decode(manifestOf(project.id).readText()).cels.size)
    }

    @Test
    fun `discarding leaves without saving what could not be saved`() {
        val project = create()
        val before = manifestOf(project.id).readText()
        val viewModel = viewModelFor(project.id)
        val ready = viewModel.awaitReady()
        stroke(ready.session)
        fillDisk(project.id)
        viewModel.requestLeave { }
        waitUntil("the save to fail") { viewModel.unsavedOnLeave.value }

        var left = false
        viewModel.discardAndLeave { left = true }
        assertTrue(left)
        freeDisk(project.id)
        store.clear()
        waitUntil("the editor to close") { runBlocking { repository.open(project.id) }?.also { it.store.close() } != null }
        assertEquals("the save must not happen, even now there is room", before, manifestOf(project.id).readText())
    }

    @Test
    fun `discarding in the real app saves nothing on its way off the screen`() {
        val project = create()
        val before = manifestOf(project.id).readText()
        val viewModel = viewModelFor(project.id)
        val ready = viewModel.awaitReady()
        // The same navigation as the app. Going back drives the screen through ON_STOP while it is still composed.
        compose.setContent {
            KineographTheme(darkTheme = false) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "gallery") {
                    composable("gallery") { Text("Gallery") }
                    composable("editor") {
                        EditorRoute(viewModel, wideLayout = false, onBack = dropUnlessResumed { nav.popBackStack() })
                    }
                }
                LaunchedEffect(Unit) { nav.navigate("editor") }
            }
        }
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("canvas_host").fetchSemanticsNodes().isNotEmpty() }

        stroke(ready.session)
        fillDisk(project.id)
        compose.onNodeWithContentDescription("Back").performClick()
        waitUntil("the save to fail") { viewModel.unsavedOnLeave.value }
        // By now the failure has passed: space was freed, so anything that still tries to save would succeed.
        freeDisk(project.id)
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("Discard changes").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Discard changes").performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("canvas_host").fetchSemanticsNodes().isEmpty() }
        assertEquals("nothing may be written on the way out", before, manifestOf(project.id).readText())

        store.clear()
        waitUntil("the editor to close") { runBlocking { repository.open(project.id) }?.also { it.store.close() } != null }
        assertEquals("the save must not happen when it closes either", before, manifestOf(project.id).readText())
    }

    @Test
    fun `after discarding, going to the background or going back does not bring saving back`() {
        val project = create()
        val before = manifestOf(project.id).readText()
        val viewModel = viewModelFor(project.id)
        val ready = viewModel.awaitReady()
        stroke(ready.session)
        fillDisk(project.id)
        viewModel.requestLeave { }
        waitUntil("the save to fail") { viewModel.unsavedOnLeave.value }
        freeDisk(project.id)

        viewModel.discardAndLeave { }
        viewModel.saveBeforeBackground()
        assertEquals(before, manifestOf(project.id).readText())
        var leftAgain = false
        viewModel.requestLeave { leftAgain = true }
        assertTrue("the answer stands, so there is nothing to wait for or ask", leftAgain)
        assertFalse(viewModel.unsavedOnLeave.value)
        viewModel.retryFailedSave()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, manifestOf(project.id).readText())
    }

    @Test
    fun `going to the background has the changes on disk by the time the main thread carries on`() {
        val project = create()
        val viewModel = viewModelFor(project.id)
        val ready = viewModel.awaitReady()
        stroke(ready.session)
        viewModel.saveBeforeBackground()
        assertEquals(1, ManifestCodec.decode(manifestOf(project.id).readText()).cels.size)
        assertEquals(1, runBlocking { ready.session.snapshot() }.cels.size)
    }

    @Test
    fun `going to the background with nothing changed writes nothing`() {
        val project = create()
        val before = manifestOf(project.id).readText()
        val viewModel = viewModelFor(project.id)
        viewModel.awaitReady()
        viewModel.saveBeforeBackground()
        assertEquals(before, manifestOf(project.id).readText())
    }

    @Test
    fun `a canvas too large to open offers a smaller one instead of a dead screen`() {
        val project = create(width = HUGE, height = HUGE)
        val viewModel = viewModelFor(project.id)
        val ready = viewModel.awaitReady()
        assertTrue("known before the editor appears, so no dead canvas shows first", ready.session.state.value.openFailed)

        var left = false
        compose.setContent {
            KineographTheme(darkTheme = false) { EditorRoute(viewModel, wideLayout = false) { left = true } }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("canvas_too_large").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()
        compose.onNodeWithText("Use a smaller canvas (${HUGE / 2} × ${HUGE / 2})").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        assertTrue(left)
    }

    @Test
    fun `a smaller canvas opens, and the brushes are scaled to suit it`() {
        val project = create(width = HUGE, height = HUGE)
        val viewModel = viewModelFor(project.id)
        val ready = viewModel.awaitReady()
        viewModel.retryOpen(200, 100)
        waitUntil("the canvas to open") { ready.session.state.value.ready }
        assertFalse(ready.session.state.value.openFailed)
        assertEquals(200, ready.session.state.value.project.width)
        assertEquals(ToolState().scaledForCanvas(200, 100).sizes, viewModel.tools.value.sizes)
        assertNull(ready.session.scene?.floating)
    }

    @Test
    fun `failing saves show for as long as they last, not just once`() {
        val project = create()
        val ready = viewModelFor(project.id).awaitReady()
        var failing by mutableStateOf(false)
        compose.setContent {
            KineographTheme(darkTheme = false) {
                EditorTopBar(ready.session.state.value, {}, {}, {}, {}, {}, {}, saveFailed = failing)
            }
        }
        compose.onNodeWithTag("not_saved").assertDoesNotExist()
        failing = true
        compose.waitForIdle()
        compose.onNodeWithText("Not saved").assertIsDisplayed()
    }

    /** Lays out the top bar at a font size, and checks that the warning has all the room it needs. */
    private fun assertNotSavedIsReadable(fontScale: Float) {
        val project = create()
        val state = viewModelFor(project.id)
            .awaitReady()
            .session.state.value
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                KineographTheme(darkTheme = false) {
                    EditorTopBar(state, {}, {}, {}, {}, {}, {}, saveFailed = true)
                }
            }
        }
        compose.waitForIdle()
        val node = compose.onNodeWithTag("not_saved").fetchSemanticsNode()
        val layouts = ArrayList<TextLayoutResult>()
        node.config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(layouts)
        val layout = layouts.single()
        assertFalse("cut off: ${node.size}", layout.didOverflowHeight)
        for (line in 0 until layout.lineCount) {
            assertTrue("line $line runs past ${node.size.width} px", layout.getLineRight(line) <= node.size.width)
        }
        val density = context.resources.displayMetrics.density
        // The style's 16 sp line height.
        assertTrue("only ${node.size.height} px tall", node.size.height >= 16 * fontScale * density)
        assertEquals("the whole text is laid out", layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
        val words = layout.layoutInput.text
            .split(' ')
            .size
        assertTrue("more lines than words means a word was broken up: ${layout.lineCount}", layout.lineCount <= words)
    }

    @Test
    fun `the not saved warning is not cut off at twice the font size`() {
        assertNotSavedIsReadable(fontScale = 2f)
    }

    @Test
    @Config(qualifiers = "+fr")
    fun `the longer French warning is not cut off at twice the font size either`() {
        assertNotSavedIsReadable(fontScale = 2f)
    }

    @Test
    @Config(qualifiers = "+fr")
    fun `the French warning still fits between the buttons just past the normal font size`() {
        assertNotSavedIsReadable(fontScale = 1.15f)
    }

    @Test
    fun `the unsaved changes dialog offers staying, freeing space and discarding`() {
        var choice = ""
        compose.setContent {
            KineographTheme(darkTheme = false) {
                UnsavedChangesDialog(onStay = { choice = "stay" }, onFreeSpace = { choice = "space" }, onDiscard = { choice = "discard" })
            }
        }
        compose.onNodeWithText("Stay").performClick()
        assertEquals("stay", choice)
        compose.onNodeWithText("Free up space").performClick()
        assertEquals("space", choice)
        compose.onNodeWithText("Discard changes").performClick()
        assertEquals("discard", choice)
    }

    private companion object {
        const val WAIT_MS = 20_000L

        /** Far more than any test machine can allocate twice over, so opening a canvas this size fails for real. */
        const val HUGE = 100_000
    }
}
