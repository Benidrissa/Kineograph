package org.kineograph.app

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.kineograph.data.manifest.ManifestCodec
import org.kineograph.media.export.ExportWorker
import org.kineograph.model.Background
import org.kineograph.model.Project
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The app, except that what its start-up provider does in real life, and nothing does here, has to come before it starts. */
class TestKineographApp : KineographApp() {
    override fun onCreate() {
        try {
            WorkManager.initialize(this, Configuration.Builder().build())
        } catch (_: IllegalStateException) {
            // Already set up by an earlier test in this process.
        }
        super.onCreate()
    }
}

/** What happens when something outside the app asks for a project, which is the job of the activity and its navigation. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi", application = TestKineographApp::class)
class MainActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: KineographApp get() = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun create(name: String): Project =
        runBlocking { app.projectRepository.create(name, 200, 100, 12, Background.Transparent, "Layer 1") }

    private fun requestFor(id: String) = Intent(app, MainActivity::class.java).putExtra(ExportWorker.EXTRA_PROJECT_ID, id)

    private fun launch(intent: Intent) {
        scenario = ActivityScenario.launch(intent)
        compose.waitForIdle()
    }

    /** A tap on a notification while the app is already open. */
    private fun tapNotificationFor(id: String) {
        checkNotNull(scenario).onActivity { InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(it, requestFor(id)) }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForEditorOn(project: Project) {
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("canvas_host").fetchSemanticsNodes().isNotEmpty() }
        waitForText(project.name)
    }

    private fun addFrame() {
        compose.onNodeWithTag("add_frame").performClick()
        compose.waitForIdle()
    }

    private fun manifestFile(project: Project) = File(app.files.projectDirectory(project.id), "manifest.json")

    private fun framesOnDisk(project: Project) = ManifestCodec.decode(manifestFile(project).readText()).frames.size

    /** Nothing can be written over a directory, which is the nearest thing here to a disk with no space left. */
    private fun fillDisk(project: Project) {
        File(app.files.projectDirectory(project.id), "manifest.tmp/keep").mkdirs()
    }

    private fun freeDisk(project: Project) {
        File(app.files.projectDirectory(project.id), "manifest.tmp").deleteRecursively()
    }

    /** The editor on [open], with a change that cannot be saved, after a tap on a notification about [other]. */
    private fun askedWhileGoingTo(open: Project, other: Project) {
        launch(requestFor(open.id))
        waitForEditorOn(open)
        addFrame()
        fillDisk(open)
        tapNotificationFor(other.id)
        waitForText(UNSAVED_TITLE)
    }

    @Test
    fun `a notification opens its project`() {
        val project = create("First")
        launch(requestFor(project.id))
        waitForEditorOn(project)
    }

    @Test
    fun `what another app sends instead of a project is ignored`() {
        for (value in listOf("a/b", "", "../..", "a%2Fb", "x?y=1")) {
            launch(requestFor(value))
            compose.waitForIdle()
            compose.onNodeWithTag("canvas_host").assertDoesNotExist()
            checkNotNull(scenario).close()
        }
    }

    @Test
    fun `what another app sends while a project is open does not close it`() {
        val project = create("First")
        launch(requestFor(project.id))
        waitForEditorOn(project)
        for (value in listOf("a/b", "")) tapNotificationFor(value)
        compose.waitForIdle()
        compose.onNodeWithText(project.name).assertIsDisplayed()
    }

    @Test
    fun `a notification about the project already open leaves it alone`() {
        val project = create("First")
        launch(requestFor(project.id))
        waitForEditorOn(project)
        addFrame()
        tapNotificationFor(project.id)
        compose.waitForIdle()
        compose.onNodeWithText(project.name).assertIsDisplayed()
        compose.onNodeWithText(UNSAVED_TITLE).assertDoesNotExist()
    }

    @Test
    fun `a notification about another project saves this one and goes there`() {
        val first = create("First")
        val second = create("Second")
        launch(requestFor(first.id))
        waitForEditorOn(first)
        addFrame()
        tapNotificationFor(second.id)
        waitForEditorOn(second)
        compose.onNodeWithText(first.name).assertDoesNotExist()
        assertEquals(2, framesOnDisk(first))
    }

    @Test
    fun `a notification about another project asks before losing what could not be saved`() {
        val first = create("First")
        val second = create("Second")
        askedWhileGoingTo(first, second)
        compose.onNodeWithTag("canvas_host").assertExists()
        assertEquals(1, framesOnDisk(first))
    }

    @Test
    fun `discarding goes on to the other project`() {
        val first = create("First")
        val second = create("Second")
        askedWhileGoingTo(first, second)
        compose.onNodeWithText("Discard changes").performClick()
        waitForEditorOn(second)
        compose.onNodeWithText(first.name).assertDoesNotExist()
        assertEquals("the user said to throw it away", 1, framesOnDisk(first))
    }

    @Test
    fun `staying keeps the work, and going back afterwards goes back rather than to the other project`() {
        val first = create("First")
        val second = create("Second")
        askedWhileGoingTo(first, second)
        compose.onNodeWithText("Stay").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(first.name).assertIsDisplayed()

        freeDisk(first)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("canvas_host").fetchSemanticsNodes().isEmpty() }
        assertEquals(2, framesOnDisk(first))
        compose.onNodeWithText(second.name).assertIsDisplayed()
        assertNotEquals("the gallery lists both", 0, compose.onAllNodesWithText(first.name).fetchSemanticsNodes().size)
    }

    private companion object {
        const val WAIT_MS = 20_000L
        const val UNSAVED_TITLE = "Your latest changes aren't saved"
    }
}
