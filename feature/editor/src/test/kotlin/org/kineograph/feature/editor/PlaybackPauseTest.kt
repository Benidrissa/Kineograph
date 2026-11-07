package org.kineograph.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineExceptionHandler
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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

    /** A red picture, which fails the first [failures] times it is asked for, as a thrown error or as no image. Cels are blue. */
    private class TightStore(
        private val failures: Int,
        private val throwing: Boolean = true,
        private val gone: Boolean = false,
    ) : CelStore {
        val backgroundReads = AtomicInteger()
        private val picture = solid(Color.RED)
        private val cel = solid(Color.BLUE)

        override fun write(image: Bitmap): String = "cels/one.png"

        override fun read(file: String, sampleSize: Int): Bitmap? {
            if (file != BACKGROUND_FILE) return cel
            if (backgroundReads.incrementAndGet() > failures) return picture
            if (throwing) throw OutOfMemoryError("Test")
            return null
        }

        override fun isGone(file: String): Boolean = gone

        override suspend fun awaitWrites() = Unit

        override fun trimMemory() = Unit

        private fun solid(color: Int) = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    }

    /** Holds every read of [HELD_FILE] until told to go on, with cels of that file red and any others blue. */
    private class HoldingStore : CelStore {
        val holding = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        private val red = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        private val blue = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

        override fun write(image: Bitmap): String = "cels/one.png"

        override fun read(file: String, sampleSize: Int): Bitmap {
            if (file != HELD_FILE) return blue
            holding.countDown()
            letGo.await(WAIT_MS, TimeUnit.MILLISECONDS)
            return red
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

    /** Plays with [tight] until two frames have shown and [until] holds, and returns what the last one had in its far corner. */
    private fun playBackground(tight: TightStore, until: () -> Boolean = { true }): Int {
        val uncaught = AtomicReference<Throwable?>()
        val guarded = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> uncaught.set(e) })
        val playback =
            PlaybackController(guarded, tight, playbackBudgetBytes = 1024L * 1024, isLowRam = false, nanoTime = {
                SystemClock.uptimeMillis() * 1_000_000
            })
        val frames = CountingDisplay()

        playback.start(project.copy(background = Background.Image(BACKGROUND_FILE)), 0, PlaybackMode.LOOP, 1f, frames)
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (frames.shown < 2 || !until()) {
            check(System.currentTimeMillis() < deadline) { "No frames were shown" }
            runFor(16)
            Thread.sleep(2)
        }
        val corner = checkNotNull(frames.last).getPixel(7, 7)
        playback.stop()
        guarded.cancel()

        assertEquals(null, uncaught.get())
        return corner
    }

    @Test
    fun `memory running out on the background picture is survived, and the picture is read once it fits`() {
        val tight = TightStore(failures = 2)

        assertEquals(Color.RED, playBackground(tight) { tight.backgroundReads.get() > 2 })
    }

    @Test
    fun `no image from the background picture while the file is there means memory, so it is read again`() {
        val tight = TightStore(failures = 2, throwing = false)

        assertEquals(Color.RED, playBackground(tight) { tight.backgroundReads.get() > 2 })
    }

    @Test
    fun `workers share one copy of the background picture`() {
        val tight = TightStore(failures = 0)

        assertEquals(Color.RED, playBackground(tight))
        assertEquals(1, tight.backgroundReads.get())
    }

    @Test
    fun `a background picture that keeps failing is given up on, and frames go on without it`() {
        val tight = TightStore(failures = Int.MAX_VALUE, throwing = false)

        assertEquals(Color.WHITE, playBackground(tight))
        val reads = tight.backgroundReads.get()
        runFor(100)
        Thread.sleep(100)
        assertEquals(reads, tight.backgroundReads.get())
        assertTrue(reads in 2..8)
    }

    @Test
    fun `a background picture that has gone is not asked for twice`() {
        val tight = TightStore(failures = Int.MAX_VALUE, throwing = false, gone = true)

        assertEquals(Color.WHITE, playBackground(tight))
        assertEquals(1, tight.backgroundReads.get())
    }

    @Test
    fun `a worker still on a frame when playback stops does not leave it for the next start`() {
        val holding = HoldingStore()
        val shown = CopyOnWriteArrayList<Int>()
        val frames =
            object : PlaybackDisplay {
                override val supportsHardwareBitmaps = false

                override fun setPlaybackFrame(frame: Bitmap?) {
                    if (frame != null) shown += frame.getPixel(0, 0)
                }
            }
        val playback =
            PlaybackController(scope, holding, playbackBudgetBytes = 1024L * 1024, isLowRam = false, nanoTime = {
                SystemClock.uptimeMillis() * 1_000_000
            })
        val withCels = { file: String -> project.copy(cels = project.cels.mapValues { (_, cel) -> cel.copy(file = file) }) }

        playback.start(withCels(HELD_FILE), 0, PlaybackMode.LOOP, 1f, frames)
        assertTrue(holding.holding.await(WAIT_MS, TimeUnit.MILLISECONDS))
        playback.stop()
        playback.start(withCels("cels/other.png"), 0, PlaybackMode.LOOP, 1f, frames)
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (shown.size < 2) {
            check(System.currentTimeMillis() < deadline) { "No frames were shown" }
            runFor(16)
            Thread.sleep(2)
        }
        holding.letGo.countDown()
        Thread.sleep(200)
        // Twice round the animation, so that any slot it could have landed on comes up.
        runFor(2 * FRAMES * FRAME_MS)
        playback.stop()

        assertFalse(shown.contains(Color.RED))
    }

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
        const val BACKGROUND_FILE = "media/background.png"
        const val HELD_FILE = "cels/held.png"
        const val READ_MS = 40L
    }
}
