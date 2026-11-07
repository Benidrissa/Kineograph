package org.kineograph.feature.editor

import android.graphics.Bitmap
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
import org.kineograph.engine.session.DrawingSession
import org.kineograph.engine.session.MemoryBudget
import org.kineograph.media.svgimport.SvgFit
import org.kineograph.media.svgimport.SvgImportOutcome
import org.kineograph.media.svgimport.SvgImportPlace
import org.kineograph.media.svgimport.SvgImportRequest
import org.kineograph.media.svgimport.SvgImporter
import org.kineograph.model.Background
import org.kineograph.model.newProject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream

private const val ANIMATED =
    """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect width="20" height="20" fill="red">
    <animate attributeName="x" from="0" to="80" dur="2s" repeatCount="indefinite"/></rect></svg>"""

private const val STATIC =
    """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect width="20" height="20" fill="red"/></svg>"""

/** A bad file must end in a friendly message, whatever shape the badness takes, and a good one must get all the way into the project. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SvgImportControllerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val store = TestStore()
    private lateinit var session: DrawingSession
    private lateinit var controller: SvgImportController
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val messages = ArrayList<EditorMessage>()
    private var holds = 0
    private var releases = 0

    @Before
    fun setUp() {
        val project = newProject("Test", 200, 100, 12, Background.Transparent, "Layer 1", now = 0)
        session = DrawingSession(project, store, MemoryBudget.forMemoryClass(256, false), temp.newFolder())
        val importer = SvgImporter(session, store, { image -> Background.Image(store.write(image.copy(Bitmap.Config.ARGB_8888, false))) })
        controller =
            SvgImportController(
                scope = scope,
                importer = importer,
                holdFiles = { holds++ },
                releaseFiles = { releases++ },
                onMessage = { synchronized(messages) { messages += it } },
            )
    }

    @After
    fun tearDown() {
        scope.cancel()
        session.close()
    }

    private fun waitFor(condition: (SvgImportState) -> Boolean): SvgImportState =
        runBlocking { withTimeout(TIMEOUT_MILLIS) { controller.state.first(condition) } }

    private fun read(text: String, name: String = "ball.svg"): SvgImportState.Ready {
        controller.read({ name }, { ByteArrayInputStream(text.toByteArray()) })
        return waitFor { it is SvgImportState.Ready } as SvgImportState.Ready
    }

    private fun readProblem(open: () -> InputStream?): EditorMessage {
        controller.read({ "bad.svg" }, open)
        waitFor { it == SvgImportState.Idle && synchronized(messages) { messages.isNotEmpty() } }
        return synchronized(messages) { messages.removeAt(0) }
    }

    private fun bytes(text: String): () -> InputStream = { ByteArrayInputStream(text.toByteArray()) }

    @Test
    fun `an animated file starts as an animation`() {
        val ready = read(ANIMATED)
        assertTrue(ready.choices.asAnimation)
        assertEquals("ball.svg", ready.file.name)
        assertEquals(ANIMATED.toByteArray().size.toLong(), ready.file.sizeBytes)
        assertEquals(2.0, ready.choices.durationSeconds(ready.file) ?: 0.0, 1e-9)
    }

    @Test
    fun `a static file can only be a single image`() {
        val ready = read(STATIC)
        assertFalse(ready.choices.asAnimation)
        assertFalse(ready.file.canAnimate)
    }

    @Test
    fun `a file whose animation never starts is treated as a still picture`() {
        val never =
            """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect width="20" height="20">
            <animate attributeName="x" to="80" dur="2s" begin="click"/></rect></svg>"""
        val ready = read(never)
        assertTrue(ready.file.document.animation.isAnimated)
        assertFalse(ready.file.canAnimate)
        assertFalse(ready.choices.asAnimation)
    }

    @Test
    fun `the layer takes its name from the file`() {
        assertEquals("ball", read(STATIC, "ball.svg").file.layerName)
        controller.dismiss()
        assertEquals("BALL", read(STATIC, "BALL.SVG").file.layerName)
        controller.dismiss()
        assertEquals("my.ball", read(STATIC, "my.ball.svgz").file.layerName)
        controller.dismiss()
        assertEquals("no extension", read(STATIC, "no extension").file.layerName)
        controller.dismiss()
        assertEquals(".svg", read(STATIC, ".svg").file.layerName)
    }

    @Test
    fun `bad files each get a friendly message, and the app stays up`() {
        assertEquals(EditorMessage.SVG_EMPTY, readProblem(bytes("")))
        assertEquals(EditorMessage.SVG_DAMAGED, readProblem(bytes("this is not xml at all")))
        assertEquals(EditorMessage.SVG_DAMAGED, readProblem(bytes("<svg xmlns=\"http://www.w3.org/2000/svg\"><rect")))
        assertEquals(EditorMessage.SVG_NOT_SVG, readProblem(bytes("<html><body/></html>")))
        assertEquals(EditorMessage.SVG_DAMAGED, readProblem { ByteArrayInputStream(byteArrayOf(0x1F, 0x8B.toByte(), 1, 2, 3)) })
    }

    @Test
    fun `a document provider failing in any way gets a friendly message`() {
        assertEquals(EditorMessage.SVG_UNREADABLE, readProblem { null })
        assertEquals(EditorMessage.SVG_UNREADABLE, readProblem { throw FileNotFoundException("gone") })
        assertEquals(EditorMessage.SVG_UNREADABLE, readProblem { throw SecurityException("no longer allowed") })
        assertEquals(EditorMessage.SVG_UNREADABLE, readProblem { throw IllegalArgumentException("bad uri") })
    }

    @Test
    fun `a duration can be typed with either decimal mark, and nonsense is not a duration`() {
        assertEquals(2.5, SvgImportChoices.parseSeconds("2.5") ?: 0.0, 1e-9)
        assertEquals(2.5, SvgImportChoices.parseSeconds("2,5") ?: 0.0, 1e-9)
        assertEquals(0.5, SvgImportChoices.parseSeconds(".5") ?: 0.0, 1e-9)
        assertNull(SvgImportChoices.parseSeconds(""))
        assertNull(SvgImportChoices.parseSeconds("0"))
        assertNull(SvgImportChoices.parseSeconds("1.2.3"))
    }

    @Test
    fun `importing holds the files from the sweep, changes the project and goes back to idle`() {
        read(ANIMATED)
        controller.start()
        waitFor { it == SvgImportState.Idle }
        val project = runBlocking { session.snapshot() }
        assertEquals(2, project.layers.size)
        assertEquals("ball", project.layers.last().name)
        assertEquals(24, project.frames.size)
        waitForReleases(1)
        assertEquals(1, holds)
    }

    @Test
    fun `cancelling goes back to the sheet with the same choices, and the project does not change`() {
        val ready = read(ANIMATED)
        val before = runBlocking { session.snapshot() }
        val long = ready.choices.copy(durationText = "1000")
        controller.change(long)
        controller.start()
        waitFor { it is SvgImportState.Running && it.done >= 2 }
        controller.cancel()
        val back = waitFor { it is SvgImportState.Ready } as SvgImportState.Ready
        assertEquals(long, back.choices)
        assertEquals(before, runBlocking { session.snapshot() })
        assertFalse(session.state.value.canUndo)
        waitForReleases(1)
    }

    @Test
    fun `a Cancel too late to stop anything does not say it did, so Import cannot be pressed twice`() {
        read(STATIC)
        // This is as late as it can be: the project has changed, and the surface is reading the result.
        store.onRead = { controller.cancel() }
        controller.start()
        waitFor { it == SvgImportState.Idle }
        store.onRead = null
        val project = runBlocking { session.snapshot() }
        assertEquals(listOf("Layer 1", "ball"), project.layers.map { it.name })
        assertTrue(session.state.value.canUndo)
        waitForReleases(1)
    }

    @Test
    fun `a locked current layer is refused with the sheet still open`() {
        val ready = read(STATIC)
        session.setLayerLocked(session.state.value.layerId, true)
        runBlocking { session.awaitIdle() }
        val before = runBlocking { session.snapshot() }
        controller.change(ready.choices.copy(place = SvgImportPlace.CURRENT_LAYER))
        controller.start()
        val back = waitFor { it is SvgImportState.Ready && it.problem != null } as SvgImportState.Ready
        assertEquals(SvgImportOutcome.LAYER_NOT_DRAWABLE, back.problem)
        assertEquals(before, runBlocking { session.snapshot() })
    }

    @Test
    fun `changing a choice clears the last problem`() {
        val ready = read(STATIC)
        session.setLayerLocked(session.state.value.layerId, true)
        controller.change(ready.choices.copy(place = SvgImportPlace.CURRENT_LAYER))
        controller.start()
        waitFor { it is SvgImportState.Ready && it.problem != null }
        controller.change(ready.choices.copy(place = SvgImportPlace.NEW_LAYER))
        assertNull((controller.state.value as SvgImportState.Ready).problem)
    }

    @Test
    fun `a request is built from the choices`() {
        val ready = read(ANIMATED)
        val animation = ready.choices.copy(fit = SvgFit.FILL_CANVAS).toRequest(ready.file)
        assertEquals(SvgImportRequest.Animation(2.0, SvgFit.FILL_CANVAS, "ball"), animation)
        val image = ready.choices.copy(asAnimation = false, momentSeconds = 0.5, place = SvgImportPlace.BACKGROUND).toRequest(ready.file)
        assertEquals(SvgImportRequest.SingleImage(0.5, SvgImportPlace.BACKGROUND, SvgFit.FIT_INSIDE, "ball"), image)
        assertNull(ready.choices.copy(durationText = "nope").toRequest(ready.file))
    }

    @Test
    fun `a file cannot be opened on top of one already in hand`() {
        read(STATIC, "first.svg")
        controller.read({ "second.svg" }, bytes(STATIC))
        assertEquals("first.svg", (controller.state.value as SvgImportState.Ready).file.name)
    }

    private fun waitForReleases(count: Int) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (releases < count && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(count, releases)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 60_000L
    }
}
