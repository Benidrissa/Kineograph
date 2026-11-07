package org.kineograph.feature.editor

import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kineograph.engine.session.CelStore
import org.kineograph.feature.editor.canvas.PlaybackDisplay
import org.kineograph.model.Background
import org.kineograph.model.Cel
import org.kineograph.model.CelKey
import org.kineograph.model.IntRect
import org.kineograph.model.PlaybackMode
import org.kineograph.model.Project
import org.kineograph.model.ProjectEdits
import org.kineograph.model.newProject
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The clock here is the main looper's, which only moves when told to, so these go by frames shown and not by time passing. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class PlaybackPauseTest {
    private class CountingDisplay : PlaybackDisplay {
        var last: Bitmap? = null
        var shown = 0

        override val supportsHardwareBitmaps = false

        override fun setPlaybackFrame(frame: Bitmap?) {
            if (frame != null) shown++
            last = frame
        }
    }

    /** Takes its time, so that decoding is still under way when the test pauses, and counts what it is asked for. */
    private class SlowStore : CelStore {
        val reads = AtomicInteger()
        private val image = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        override fun write(image: Bitmap): String = "cels/one.png"

        override fun read(file: String, sampleSize: Int): Bitmap {
            reads.incrementAndGet()
            Thread.sleep(READ_MS)
            return image
        }

        override fun isGone(file: String): Boolean = false

        override suspend fun awaitWrites() = Unit

        override fun trimMemory() = Unit
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store = SlowStore()
    private val display = CountingDisplay()
    private lateinit var controller: PlaybackController

    /** Each frame has a drawing to read, and there are few enough that the cache keeps them all. */
    private val project: Project =
        (1 until FRAMES)
            .fold(newProject("Test", 8, 8, FPS, Background.Transparent, "Layer 1", now = 0)) { project, _ ->
                ProjectEdits.addFrameAfter(project, project.frames.last().id, "f${project.frames.size}")
            }.let { project ->
                val layer = project.layers.single().id
                project.copy(
                    cels = project.frames.associate { CelKey(layer, it.id) to Cel(layer, it.id, "cels/one.png", IntRect(0, 0, 1, 1)) },
                )
            }

    @Before
    fun setUp() {
        // By default frames come one straight after another with no time passing, which would never let a test get anywhere.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        controller =
            PlaybackController(scope, store, playbackBudgetBytes = 1024L * 1024, isLowRam = false, nanoTime = {
                SystemClock.uptimeMillis() * 1_000_000
            })
    }

    @After
    fun tearDown() {
        controller.stop()
        scope.cancel()
    }

    /** Moves the main looper's clock on. Background threads decode in real time, so they get a moment too. */
    private fun runFor(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(left, 16)
            shadowOf(Looper.getMainLooper()).idleFor(step, TimeUnit.MILLISECONDS)
            left -= step
        }
    }

    /** Starts playing, and waits for it to be showing frames. */
    private fun start() {
        controller.start(project, 0, PlaybackMode.LOOP, 1f, display)
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (display.shown < 2) {
            check(System.currentTimeMillis() < deadline) { "No frames were shown" }
            runFor(16)
            Thread.sleep(2)
        }
    }

    private val slot get() = controller.state.value.slotIndex

    @Test
    fun `a long canvas is decoded at the size it is shown, not at some larger minimum`() {
        controller.start(project.copy(width = 30_000, height = 1_000), 0, PlaybackMode.LOOP, 0.036f, display)
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (display.last == null) {
            check(System.currentTimeMillis() < deadline) { "No frames were shown" }
            runFor(16)
            Thread.sleep(2)
        }
        val frame = checkNotNull(display.last)
        assertEquals(1080f, frame.width.toFloat(), 1f)
        assertEquals(36f, frame.height.toFloat(), 1f)
    }

    @Test
    fun `pausing holds the frame, for as long as it lasts`() {
        start()
        controller.togglePause()
        assertTrue(controller.state.value.isPaused)
        assertTrue("the editor stays hidden and Back still stops it", controller.state.value.isPlaying)
        val held = slot
        val shown = display.shown
        runFor(2000)
        assertEquals(held, slot)
        assertEquals(shown, display.shown)
        assertEquals("the frame stays up", true, display.last != null)
    }

    @Test
    fun `resuming goes on from the frame it held, and not from where the time has got to`() {
        start()
        controller.togglePause()
        val held = slot
        runFor(FRAME_MS * FRAMES / 2)
        controller.togglePause()
        assertFalse(controller.state.value.isPaused)
        runFor(FRAME_MS)
        val next = (slot - held).mod(FRAMES)
        assertTrue("from $held to $slot", next in 0..2)
    }

    @Test
    fun `playing goes on after a resume`() {
        start()
        controller.togglePause()
        runFor(500)
        controller.togglePause()
        val shown = display.shown
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (display.shown == shown) {
            check(System.currentTimeMillis() < deadline) { "Nothing more was shown" }
            runFor(FRAME_MS)
            Thread.sleep(2)
        }
    }

    @Test
    fun `changing the speed while paused does not move the frame, and counts from the resume`() {
        start()
        controller.togglePause()
        val held = slot
        runFor(1000)
        controller.setFps(1)
        assertEquals(held, slot)
        controller.togglePause()
        runFor(FRAME_MS)
        assertTrue("at 1 fps, that was too soon for it to have gone anywhere: from $held to $slot", (slot - held).mod(FRAMES) <= 1)
    }

    @Test
    fun `a held frame costs nothing, even with frames still to decode`() {
        start()
        controller.togglePause()
        // Whatever was being decoded at that moment finishes.
        Thread.sleep(READ_MS * 5)
        val reads = store.reads.get()
        assertTrue("the premise: decoding had not finished yet, at $reads of $FRAMES", reads < FRAMES)
        Thread.sleep(READ_MS * 10)
        assertEquals(reads, store.reads.get())

        controller.togglePause()
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (store.reads.get() == reads) {
            check(System.currentTimeMillis() < deadline) { "Decoding did not start again" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `pausing before there is a frame does nothing`() {
        controller.start(project, 0, PlaybackMode.LOOP, 1f, display)
        assertTrue(controller.state.value.isPreparing)
        controller.togglePause()
        assertFalse(controller.state.value.isPaused)
    }

    @Test
    fun `stopping a paused animation leaves nothing paused for the next one`() {
        start()
        controller.togglePause()
        controller.stop()
        assertFalse(controller.state.value.isPlaying)
        assertFalse(controller.state.value.isPaused)
        controller.togglePause()
        assertFalse("nothing to pause", controller.state.value.isPaused)
        start()
        assertFalse(controller.state.value.isPaused)
    }

    private companion object {
        const val FRAMES = 24
        const val FPS = 12
        const val FRAME_MS = 1000L / FPS
        const val WAIT_MS = 20_000L
        const val READ_MS = 40L
    }
}
