package org.kineograph.feature.editor

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import org.kineograph.feature.editor.canvas.KineographCanvasView
import org.kineograph.feature.editor.canvas.PlaybackDisplay
import org.kineograph.media.export.ExportManager
import org.kineograph.model.Background
import org.kineograph.model.Project
import org.kineograph.ui.KineographTheme
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

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

    private fun viewModelFor(
        id: String,
        backgroundFailed: Boolean = false,
        savedState: SavedStateHandle = SavedStateHandle(),
    ): EditorViewModel {
        val dependencies =
            EditorDependencies(
                repository,
                SettingsRepository(context),
                MemoryBudget.forMemoryClass(256, isLowRam = false),
                ExportManager(context, SettingsRepository(context)),
                files::spillDirectory,
                appScope,
                { _, _ -> },
            )
        val factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    EditorViewModel(dependencies, id, backgroundFailed, savedState) as T
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
    fun `resizing the canvas in the editor fits the new size, so strokes land under the finger`() {
        val project = create(width = 200, height = 100)
        val viewModel = viewModelFor(project.id)
        val session = viewModel.awaitReady().session
        lateinit var root: View
        compose.setContent {
            root = LocalView.current
            KineographTheme(darkTheme = false) { EditorRoute(viewModel, wideLayout = false) { } }
        }
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("canvas_host").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val canvas = root.findViewWithTag<KineographCanvasView>("canvas")
        val holder = shadowOf(canvas.getChildAt(0) as SurfaceView).fakeSurfaceHolder
        compose.runOnIdle { holder.callbacks.toList().forEach { it.surfaceCreated(holder) } }

        session.resizeCanvas(400, 200)
        waitUntil("the resize") { session.state.value.project.width == 400 }
        compose.waitForIdle()
        compose.runOnIdle {
            val x = canvas.width / 2f
            val y = canvas.height / 2f
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(0L, 0L, action, x, y, 0)
                canvas.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        val bounds = checkNotNull(
            runBlocking { session.snapshot() }
                .cels.values
                .single()
                .bounds,
        )
        assertTrue("the middle of the view is the middle of the new canvas: $bounds", 200 in bounds.left..bounds.right)
        assertTrue("the middle of the view is the middle of the new canvas: $bounds", 100 in bounds.top..bounds.bottom)
    }

    // ----- Colours -----

    @Test
    fun `many changes of colour on the way to one are one recent colour`() {
        val viewModel = viewModelFor(create().id)
        viewModel.awaitReady()
        repeat(20) { viewModel.pickColor(0xFF000000.toInt() or it) }
        assertTrue(
            "nothing settled on yet",
            viewModel.tools.value.recentColors
                .isEmpty(),
        )
        viewModel.commitColor()
        assertEquals(listOf(0xFF000000.toInt() or 19), viewModel.tools.value.recentColors)
    }

    @Test
    fun `a colour is opaque however it arrives, as a tool's opacity is its own`() {
        val viewModel = viewModelFor(create().id)
        viewModel.awaitReady()
        viewModel.pickColor(0x80123456.toInt())
        assertEquals(0xFF123456.toInt(), viewModel.tools.value.colorArgb)
    }

    @Test
    fun `tapping the active tool leads to its options, and from there to the colour`() {
        val viewModel = viewModelFor(create().id)
        viewModel.awaitReady()
        compose.setContent {
            KineographTheme(darkTheme = false) { EditorRoute(viewModel, wideLayout = false) { } }
        }
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("canvas_host").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tool_brush").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("options_color").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("options_color").assertDoesNotExist()
        compose.onNodeWithContentDescription("Color: hue ring", substring = true).assertExists()
    }

    // ----- Pictures that cannot be used -----

    /** Nothing can decode this, as with a format the device does not know. */
    private val notAPicture: Uri by lazy {
        Uri.fromFile(temp.newFile("not-a-picture.jpg").apply { writeText("not a picture") })
    }

    /** Messages as the screen gets them, which leaves the main thread free for the work that leads to them. */
    private fun messagesFrom(viewModel: EditorViewModel): List<EditorMessage> {
        val messages = CopyOnWriteArrayList<EditorMessage>()
        appScope.launch { viewModel.messages.collect { messages += it } }
        return messages
    }

    @Test
    fun `a picture that cannot be read leaves the background alone, and says so`() {
        val project = create()
        val viewModel = viewModelFor(project.id)
        val ready = viewModel.awaitReady()
        val messages = messagesFrom(viewModel)
        viewModel.setBackgroundImage(notAPicture, context.contentResolver)
        waitUntil("the message") { messages.isNotEmpty() }
        assertEquals(listOf(EditorMessage.BACKGROUND_NOT_CHANGED), messages)
        assertEquals(project.background, ready.session.state.value.project.background)
    }

    @Test
    fun `a project created without its picture says so where the user ends up`() {
        val viewModel = viewModelFor(create().id, backgroundFailed = true)
        viewModel.awaitReady()
        assertEquals(EditorMessage.BACKGROUND_NOT_USED, nextMessage(viewModel))
    }

    @Test
    fun `that is not said again when the system brings the screen back after killing the app`() {
        val id = create().id
        val saved = SavedStateHandle()
        val first = viewModelFor(id, backgroundFailed = true, savedState = saved)
        first.awaitReady()
        assertEquals(EditorMessage.BACKGROUND_NOT_USED, nextMessage(first))
        store.clear()

        // The arguments come back the same, and so does what was saved, but nothing else does.
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        val second = viewModelFor(id, backgroundFailed = true, savedState = restored)
        second.awaitReady()
        second.notify(EditorMessage.LAYER_LOCKED)
        assertEquals("the first message is not about any picture", EditorMessage.LAYER_LOCKED, nextMessage(second))
    }

    @Test
    fun `that is only said about a project just created`() {
        val viewModel = viewModelFor(create().id)
        viewModel.awaitReady()
        viewModel.notify(EditorMessage.LAYER_LOCKED)
        assertEquals("the first message is not about any picture", EditorMessage.LAYER_LOCKED, nextMessage(viewModel))
    }

    // ----- Project settings -----

    private fun showProjectSettings(viewModel: EditorViewModel) {
        compose.setContent {
            KineographTheme(darkTheme = false) { EditorRoute(viewModel, wideLayout = false) { } }
        }
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("canvas_host").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Project settings").performClick()
        compose.waitForIdle()
    }

    private fun typeSize(width: String, height: String) {
        compose.onNodeWithTag("project_width").performTextReplacement(width)
        compose.onNodeWithTag("project_height").performTextReplacement(height)
        compose.waitForIdle()
    }

    private fun applySize() = compose.onNodeWithTag("project_apply_size").performScrollTo()

    private fun availableMemoryIs(bytes: Long) {
        val info = ActivityManager.MemoryInfo().apply { availMem = bytes }
        shadowOf(context.getSystemService(ActivityManager::class.java)).setMemoryInfo(info)
    }

    @Test
    fun `a size past the old limit of 8192 can be applied`() {
        val viewModel = viewModelFor(create().id)
        val session = viewModel.awaitReady().session
        showProjectSettings(viewModel)
        typeSize("9000", "10")
        compose.onNodeWithTag("canvas_size_note").assertDoesNotExist()
        applySize().assertIsEnabled().performClick()
        waitUntil("the resize") { session.state.value.project.width == 9000 }
    }

    @Test
    fun `a size that is no size says why Apply is off`() {
        val viewModel = viewModelFor(create().id)
        viewModel.awaitReady()
        showProjectSettings(viewModel)
        for ((width, height) in listOf("0" to "100", "" to "100", "100" to "")) {
            typeSize(width, height)
            applySize().assertIsNotEnabled()
            compose.onNodeWithText("Enter a width and height of at least 1.").assertExists()
        }
    }

    @Test
    fun `the size it already is has nothing to apply and nothing wrong with it`() {
        availableMemoryIs(1)
        val viewModel = viewModelFor(create().id)
        viewModel.awaitReady()
        showProjectSettings(viewModel)
        applySize().assertIsNotEnabled()
        compose.onNodeWithTag("canvas_size_note").assertDoesNotExist()
    }

    @Test
    fun `a size too big for what is free says why and offers one that fits`() {
        availableMemoryIs(64L * 1024 * 1024)
        val viewModel = viewModelFor(create().id)
        val session = viewModel.awaitReady().session
        showProjectSettings(viewModel)
        typeSize("20000", "10000")
        applySize().assertIsNotEnabled()
        compose.onNodeWithTag("canvas_size_note").assertExists()
        compose.onNodeWithTag("canvas_size_use").performScrollTo().performClick()
        compose.waitForIdle()
        applySize().assertIsEnabled().performClick()
        waitUntil("the resize") { session.state.value.project.width != 200 }
        val project = session.state.value.project
        assertEquals("the same shape", 2.0, project.width.toDouble() / project.height, 0.01)
        assertTrue("$project", project.width.toLong() * project.height * 8 <= 64L * 1024 * 1024)
    }

    @Test
    fun `a large size warns, and still applies`() {
        availableMemoryIs(1024L * 1024)
        val viewModel = viewModelFor(create().id)
        viewModel.awaitReady()
        showProjectSettings(viewModel)
        typeSize("300", "300")
        applySize().assertIsEnabled()
        compose.onNodeWithText("A canvas this large needs a lot of memory and may run slowly on this device.").assertExists()
    }

    @Test
    fun `a resize that cannot be allocated changes nothing, and the message shows over the open sheet`() {
        val project = create()
        val viewModel = viewModelFor(project.id)
        val session = viewModel.awaitReady().session
        showProjectSettings(viewModel)
        session.resizeCanvas(HUGE, HUGE)
        runBlocking { session.awaitIdle() }
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText("size was not changed", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(200, session.state.value.project.width)
        assertFalse(session.state.value.canUndo)
        // Still on the sheet, and the drawing can go on.
        applySize().assertExists()
        assertTrue(session.state.value.ready)
        assertFalse(session.state.value.openFailed)
    }

    @Test
    fun `failing saves show for as long as they last, not just once`() {
        val project = create()
        val ready = viewModelFor(project.id).awaitReady()
        var failing by mutableStateOf(false)
        val state = ready.session.state.value
        compose.setContent {
            KineographTheme(darkTheme = false) {
                EditorTopBar(state, {}, {}, {}, {}, {}, {}, saveFailed = failing)
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

    // ----- Playback and leaving the app -----

    private class FakeDisplay : PlaybackDisplay {
        override val supportsHardwareBitmaps = false

        override fun setPlaybackFrame(frame: Bitmap?) = Unit
    }

    /** A session with [frames] frames, showing the last of them. */
    private fun EditorLoadState.Ready.withFrames(frames: Int): String {
        repeat(frames - 1) { session.addFrame() }
        waitUntil("the frames to arrive") { session.state.value.project.frames.size == frames }
        return session.state.value.frameId
    }

    /** The real screen, on a lifecycle the test moves by hand, because the effect that goes with leaving the app is what is at stake. */
    private inner class ShownEditor(viewModel: EditorViewModel) : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry

        init {
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides this) {
                    KineographTheme(darkTheme = false) { EditorRoute(viewModel, wideLayout = false) { } }
                }
            }
            compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("canvas_host").fetchSemanticsNodes().isNotEmpty() }
        }

        /** Another window taking the focus, as in multi-window. */
        fun pause() {
            registry.currentState = Lifecycle.State.STARTED
        }

        /** Home, or another app. */
        fun leaveTheApp() {
            registry.currentState = Lifecycle.State.CREATED
        }
    }

    /** What the view model has going on, of which playback's clock and decoding are a part for as long as it plays. */
    private fun EditorViewModel.workInProgress(): Int = viewModelScope.coroutineContext.job.children
        .count()

    /**
     * Runs the main thread one task at a time until [playing] is true, or for as long as [forMs] if that comes first. Once
     * playback's clock is going each frame asks for the next, and [waitUntil]'s way of emptying the queue would never come back.
     */
    private fun PlaybackController.runMainThreadUntilPlaying(forMs: Long = WAIT_MS) {
        val deadline = System.currentTimeMillis() + forMs
        while (!state.value.isPlaying && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).runOneTask()
            Thread.sleep(1)
        }
    }

    private fun PlaybackController.awaitPlaying() {
        runMainThreadUntilPlaying()
        assertTrue("playback did not start", state.value.isPlaying)
    }

    @Test
    fun `leaving the app while nothing is playing stays on the same frame`() {
        val viewModel = viewModelFor(create().id)
        val ready = viewModel.awaitReady()
        val frame = ready.withFrames(8)
        assertNotEquals(
            ready.session.state.value.project.frames
                .first()
                .id,
            frame,
        )
        val editor = ShownEditor(viewModel)

        editor.leaveTheApp()
        waitUntil("the session to settle") { !ready.session.state.value.busy }

        assertEquals(frame, ready.session.state.value.frameId)
    }

    @Test
    fun `leaving the app during playback stops it, and everything that was working for it`() {
        val viewModel = viewModelFor(create().id)
        val ready = viewModel.awaitReady()
        ready.withFrames(4)
        val controller = checkNotNull(viewModel.playback)
        val editor = ShownEditor(viewModel)
        waitUntil("the session to settle") { !ready.session.state.value.busy }
        val idle = viewModel.workInProgress()

        viewModel.startPlayback(FakeDisplay(), 1f)
        controller.awaitPlaying()
        assertTrue("the premise: playback has work going on", viewModel.workInProgress() > idle)
        editor.leaveTheApp()

        assertFalse(controller.state.value.isPlaying)
        waitUntil("the decoding to stop") { viewModel.workInProgress() <= idle }
    }

    @Test
    fun `leaving the app while playback is still getting ready means it never starts`() {
        val viewModel = viewModelFor(create().id)
        val ready = viewModel.awaitReady()
        ready.withFrames(4)
        val controller = checkNotNull(viewModel.playback)
        val editor = ShownEditor(viewModel)

        viewModel.startPlayback(FakeDisplay(), 1f)
        // Nothing has had a chance to run on the main thread yet, so the start is still waiting for the save.
        assertFalse(controller.state.value.isPlaying)
        editor.leaveTheApp()

        controller.runMainThreadUntilPlaying(forMs = 100)
        assertFalse(controller.state.value.isPlaying)
    }

    @Test
    fun `only pausing leaves playback going, because in multi-window it is still on screen`() {
        val viewModel = viewModelFor(create().id)
        val ready = viewModel.awaitReady()
        ready.withFrames(4)
        val controller = checkNotNull(viewModel.playback)
        val editor = ShownEditor(viewModel)

        viewModel.startPlayback(FakeDisplay(), 1f)
        controller.awaitPlaying()
        editor.pause()

        assertTrue(controller.state.value.isPlaying)
    }

    @Test
    fun `stopping when nothing is playing does not move the editor to the first frame`() {
        val viewModel = viewModelFor(create().id)
        val ready = viewModel.awaitReady()
        val frame = ready.withFrames(8)

        viewModel.stopPlayback()
        waitUntil("the session to settle") { !ready.session.state.value.busy }

        assertEquals(frame, ready.session.state.value.frameId)
    }

    // ----- Going to another project -----

    @Test
    fun `going to another project saves first and then goes there`() {
        val project = create()
        val viewModel = viewModelFor(project.id)
        val ready = viewModel.awaitReady()
        stroke(ready.session)
        var left = false
        viewModel.requestLeaveFor(OTHER_PROJECT) { left = true }
        waitUntil("leaving") { left }
        assertEquals(OTHER_PROJECT, viewModel.leavingFor)
        assertEquals(1, ManifestCodec.decode(manifestOf(project.id).readText()).cels.size)
    }

    @Test
    fun `asking for another project while Back is still saving changes where Back goes`() {
        val viewModel = viewModelFor(create().id)
        stroke(viewModel.awaitReady().session)
        var destination: String? = null
        var left = false
        // This is what the screen's own way of leaving does.
        viewModel.requestLeave {
            destination = viewModel.leavingFor
            left = true
        }
        viewModel.requestLeaveFor(OTHER_PROJECT) { throw AssertionError("Only one of them may close the screen") }
        waitUntil("leaving") { left }
        assertEquals(OTHER_PROJECT, destination)
    }

    /** An editor on a disk that has filled up, on which something has just asked for [OTHER_PROJECT]. */
    private fun askedAboutOtherProject(project: Project): EditorViewModel {
        val viewModel = viewModelFor(project.id)
        stroke(viewModel.awaitReady().session)
        fillDisk(project.id)
        viewModel.requestLeaveFor(OTHER_PROJECT) { throw AssertionError("It must not close while its changes are not saved") }
        waitUntil("the save to fail") { viewModel.unsavedOnLeave.value }
        return viewModel
    }

    @Test
    fun `going to another project asks first when the changes cannot be saved`() {
        val project = create()
        val before = manifestOf(project.id).readText()
        val viewModel = askedAboutOtherProject(project)
        assertFalse(viewModel.leaving.value)
        assertEquals("the work is still there to be saved", before, manifestOf(project.id).readText())
        assertEquals(EditorMessage.SAVE_FAILED, nextMessage(viewModel))
    }

    @Test
    fun `discarding goes on to the project that was asked for`() {
        val viewModel = askedAboutOtherProject(create())
        var destination: String? = null
        viewModel.discardAndLeave { destination = viewModel.leavingFor }
        assertEquals(OTHER_PROJECT, destination)
    }

    @Test
    fun `staying means staying, so going back later is only going back`() {
        val viewModel = askedAboutOtherProject(create())
        viewModel.stayAfterFailedSave()
        assertFalse(viewModel.unsavedOnLeave.value)
        assertNull(viewModel.leavingFor)
    }

    @Test
    fun `space being freed answers the question by staying, as it does for Back`() {
        val project = create()
        val viewModel = askedAboutOtherProject(project)
        freeDisk(project.id)
        viewModel.retryFailedSave()
        waitUntil("the save to work") { !viewModel.unsavedOnLeave.value }
        assertNull(viewModel.leavingFor)
    }

    @Test
    fun `the save on coming back to the app does not stop a leave that is on its way`() {
        val project = create()
        val viewModel = viewModelFor(project.id)
        val ready = viewModel.awaitReady()
        stroke(ready.session)
        fillDisk(project.id)
        assertFalse("the premise: an earlier save failed", runBlocking { ready.autosaver.saveNow() })
        freeDisk(project.id)

        // A tap on a notification brings the app back, so these two arrive together.
        viewModel.retryFailedSave()
        var left = false
        viewModel.requestLeaveFor(OTHER_PROJECT) { left = true }
        waitUntil("leaving") { left }
        waitUntil("the retry to finish") { !ready.autosaver.saveFailed.value }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(OTHER_PROJECT, viewModel.leavingFor)
    }

    @Test
    fun `the project already open is not left, and the last thing asked is what counts`() {
        val project = create()
        val viewModel = askedAboutOtherProject(project)
        viewModel.requestLeaveFor(project.id) { throw AssertionError("It is already on this project") }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(viewModel.unsavedOnLeave.value)
        assertNull(viewModel.leavingFor)
    }

    @Test
    fun `going to another project stops playback on the way`() {
        val viewModel = viewModelFor(create().id)
        val ready = viewModel.awaitReady()
        ready.withFrames(4)
        val controller = checkNotNull(viewModel.playback)
        viewModel.startPlayback(FakeDisplay(), 1f)
        controller.awaitPlaying()

        var left = false
        viewModel.requestLeaveFor(OTHER_PROJECT) { left = true }

        assertFalse(controller.state.value.isPlaying)
        waitUntil("leaving") { left }
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
        const val OTHER_PROJECT = "the other project"

        /** Far more than any test machine can allocate twice over, so opening a canvas this size fails for real. */
        const val HUGE = 100_000
    }
}
