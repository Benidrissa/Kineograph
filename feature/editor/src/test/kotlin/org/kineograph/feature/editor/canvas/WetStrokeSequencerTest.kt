package org.kineograph.feature.editor.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.random.Random

/**
 * The rules for what reaches the low-latency library, checked against a model of how it queues work. The real library needs a device,
 * and JVM tests never reach it. The model follows the source of CanvasFrontBufferedRenderer 1.0.4, in particular:
 *
 *  - commit() counts up. Only the first of several starts straight away. A later one starts when the buffer before it is released.
 *  - Starting a commit forgets all queued front paints, and hands them to the scene draw, which ignores them here.
 *  - Front paints only leave the queue when no commit is counted.
 *  - The first paint after a commit's buffer is released clears the front layer.
 */
class WetStrokeSequencerTest {
    private data class Stamp(val strokeId: Long, val number: Int)

    private enum class RenderTask { PAINT, SCENE_DRAW, HIDE_FRONT, CLEAR_FRONT }

    private class LibraryModel : FrontBufferLayers {
        lateinit var sequencer: WetStrokeSequencer<Stamp>
        val finishedStrokes = HashSet<Long>()
        val renderThread = ArrayDeque<RenderTask>()
        val mainThread = ArrayDeque<() -> Unit>()
        val front = ArrayList<Stamp>()
        var frontVisible = false
        var commitCount = 0
        var queuedPaints = 0
        var flushedPaints = 0
        var pendingClear = true
        var pendingReleases = 0
        var scenesDrawn = 0
        var cancels = 0
        var sceneGeneration = 0
        var drawnGeneration = 0

        override fun requestFrontPaint() {
            queuedPaints++
            if (commitCount == 0) flush()
        }

        override fun commit() {
            if (commitCount++ == 0) startCommit()
        }

        override fun cancel() {
            cancels++
            queuedPaints = 0
            flushedPaints = 0
            renderThread.removeAll { it == RenderTask.PAINT || it == RenderTask.CLEAR_FRONT }
            renderThread.addLast(RenderTask.HIDE_FRONT)
            renderThread.addLast(RenderTask.CLEAR_FRONT)
        }

        private fun flush() {
            while (flushedPaints < queuedPaints) {
                flushedPaints++
                renderThread.addLast(RenderTask.PAINT)
            }
        }

        private fun startCommit() {
            renderThread.removeAll { it == RenderTask.PAINT || it == RenderTask.CLEAR_FRONT }
            queuedPaints = 0
            flushedPaints = 0
            renderThread.addLast(RenderTask.SCENE_DRAW)
        }

        fun runRenderTask() {
            when (renderThread.removeFirst()) {
                RenderTask.PAINT -> {
                    paint()
                }

                RenderTask.SCENE_DRAW -> {
                    drawScene()
                }

                RenderTask.HIDE_FRONT -> {
                    frontVisible = false
                }

                RenderTask.CLEAR_FRONT -> {
                    front.clear()
                    frontVisible = true
                }
            }
        }

        private fun paint() {
            if (pendingClear) {
                pendingClear = false
                front.clear()
            }
            for (stamp in sequencer.takeCommands()) {
                assertFalse("a stamp of a finished stroke was painted: $stamp", stamp.strokeId in finishedStrokes)
                assertTrue("two strokes are mixed on the front layer: $front and $stamp", front.all { it.strokeId == stamp.strokeId })
                front += stamp
            }
            frontVisible = true
        }

        private fun drawScene() {
            sequencer.onSceneDrawStarted()
            scenesDrawn++
            drawnGeneration = sceneGeneration
            sequencer.onSceneDrawFinished()
            frontVisible = false
            pendingReleases++
        }

        fun releaseBuffer() {
            pendingReleases--
            pendingClear = true
            commitCount = max(commitCount - 1, 0)
            if (commitCount != 0) mainThread.addLast { startCommit() } else flush()
        }
    }

    private val library = LibraryModel()
    private val sequencer = WetStrokeSequencer<Stamp>(library).also { library.sequencer = it }
    private val engineThread = ArrayDeque<() -> Unit>()
    private var nextStamp = 0

    private fun stamp(strokeId: Long): Stamp = Stamp(strokeId, nextStamp++).also { sequencer.add(strokeId, it) }

    private fun eraserStamp(strokeId: Long): Stamp {
        val stamp = Stamp(strokeId, nextStamp++)
        engineThread.addLast { sequencer.add(strokeId, stamp) }
        return stamp
    }

    /** Lifts the pen: the commit is left to the engine, in turn. */
    private fun penUp(strokeId: Long) {
        engineThread.addLast { engineCommit(strokeId) }
    }

    private fun engineCommit(strokeId: Long) {
        sequencer.commitStroke(strokeId)
        library.finishedStrokes += strokeId
    }

    private fun cancel(strokeId: Long) {
        sequencer.cancelStroke(strokeId)
        library.finishedStrokes += strokeId
    }

    private fun sceneChanged() {
        library.sceneGeneration++
        sequencer.requestRedraw()
    }

    private fun runEngine() {
        while (engineThread.isNotEmpty()) runEngineTask()
    }

    /** Does only the engine's next job, so a test can look at what the layers show in between. */
    private fun runEngineTask() {
        engineThread.removeFirst()()
    }

    private fun runRenderThread() {
        while (library.renderThread.isNotEmpty()) library.runRenderTask()
    }

    /** Lets everything finish, whatever order it happens in. */
    private fun settle() {
        while (
            engineThread.isNotEmpty() ||
            library.renderThread.isNotEmpty() ||
            library.mainThread.isNotEmpty() ||
            library.pendingReleases > 0
        ) {
            runEngine()
            runRenderThread()
            while (library.mainThread.isNotEmpty()) library.mainThread.removeFirst()()
            if (library.pendingReleases > 0) library.releaseBuffer()
        }
    }

    @Test
    fun `stamps go to the front layer straight away when nothing else is happening`() {
        val id = sequencer.beginStroke()
        val first = stamp(id)
        runRenderThread()
        assertEquals(listOf(first), library.front)
        assertTrue(library.frontVisible)
    }

    @Test
    fun `a redraw under way when the pen lands holds stamps back, then shows all of them`() {
        sceneChanged()
        val id = sequencer.beginStroke()
        val stamps = listOf(stamp(id), stamp(id))
        runRenderThread()
        assertTrue("nothing may be painted that the redraw would then hide", library.front.isEmpty())
        settle()
        assertEquals(stamps, library.front)
        assertTrue(library.frontVisible)
    }

    @Test
    fun `the next stroke is held back until the last one has its commit, and then appears whole and alone`() {
        val first = sequencer.beginStroke()
        val firstStamps = listOf(stamp(first))
        runRenderThread()
        penUp(first)

        val second = sequencer.beginStroke()
        val paintsAsked = library.queuedPaints
        val secondStamps = listOf(stamp(second), stamp(second))
        assertEquals("there is nothing the library can do for this stroke yet", paintsAsked, library.queuedPaints)
        runRenderThread()
        assertEquals("the finished stroke stays on show until its pixels are merged", firstStamps, library.front)
        assertTrue(library.frontVisible)
        assertEquals(0, library.scenesDrawn)

        runEngine()
        assertEquals(1, library.commitCount)
        settle()
        assertEquals(1, library.scenesDrawn)
        assertEquals(secondStamps, library.front)
    }

    @Test
    fun `stamps survive a commit that had to wait behind another one, which takes the library's whole queue`() {
        sceneChanged()
        runRenderThread()
        assertEquals(1, library.scenesDrawn)
        sceneChanged()
        assertEquals("the second commit is counted and waiting", 2, library.commitCount)

        val id = sequencer.beginStroke()
        val stamps = listOf(stamp(id), stamp(id), stamp(id))
        library.releaseBuffer()
        assertEquals("the requests to paint are about to be lost", 3, library.queuedPaints)
        library.mainThread.removeFirst()()
        assertEquals(0, library.queuedPaints)

        settle()
        assertEquals(stamps, library.front)
        assertTrue(library.frontVisible)
    }

    @Test
    fun `a stamp reported after the stroke's commit is never painted`() {
        val id = sequencer.beginStroke()
        stamp(id)
        runRenderThread()
        engineCommit(id)
        stamp(id)
        settle()
        assertFalse("the front layer must not come back after the commit hid it", library.frontVisible)
        assertEquals(1, library.scenesDrawn)
    }

    @Test
    fun `eraser stamps all reach the front layer before the commit, because the engine reports them in order`() {
        val id = sequencer.beginStroke()
        val stamps = listOf(eraserStamp(id), eraserStamp(id))
        penUp(id)
        runRenderThread()
        assertTrue("nothing can be painted until the engine has reported it", library.front.isEmpty())

        runEngineTask()
        runRenderThread()
        assertEquals(stamps.take(1), library.front)
        runEngineTask()
        runRenderThread()
        assertEquals(stamps, library.front)
        assertTrue(library.frontVisible)
        assertEquals("the commit is still behind them on the engine", 0, library.commitCount)

        runEngineTask()
        assertEquals(1, library.commitCount)
        runRenderThread()
        assertEquals(1, library.scenesDrawn)
        assertFalse("the merged stroke has replaced the wet one", library.frontVisible)
        settle()
        assertFalse("the front layer must not come back once the commit is done", library.frontVisible)
    }

    @Test
    fun `eraser stamps the render thread had no time for give way to the commit and never come back`() {
        val id = sequencer.beginStroke()
        eraserStamp(id)
        eraserStamp(id)
        penUp(id)
        repeat(2) { runEngineTask() }
        assertEquals("both are waiting to be painted", 2, library.queuedPaints)

        runEngineTask()
        assertEquals("the commit took the requests to paint them", 0, library.queuedPaints)
        settle()
        assertEquals(1, library.scenesDrawn)
        assertTrue(library.front.isEmpty())
        assertFalse(library.frontVisible)
    }

    @Test
    fun `an eraser stamp reported after a cancel is dropped, so the area does not look erased`() {
        val id = sequencer.beginStroke()
        eraserStamp(id)
        cancel(id)
        settle()
        assertTrue("the cancel leaves an empty layer", library.front.isEmpty())
    }

    @Test
    fun `what was left of the last stroke does not turn up at the start of the next`() {
        val first = sequencer.beginStroke()
        stamp(first)
        // Not painted yet when the commit arrives, as happens with a last move queued behind a frame still being drawn.
        engineCommit(first)
        val second = sequencer.beginStroke()
        val stamps = listOf(stamp(second))
        settle()
        assertEquals(stamps, library.front)
    }

    /** The pen's button: the ink's commit goes to the engine, and the eraser's stamps follow it there, all from one touch event. */
    @Test
    fun `ink split by the pen's button stays until it is merged, then the erasing appears whole and alone`() {
        val ink = sequencer.beginStroke()
        val inkStamps = listOf(stamp(ink))
        runRenderThread()
        penUp(ink)
        val erasing = sequencer.beginStroke()
        val erased = listOf(eraserStamp(erasing), eraserStamp(erasing))
        runRenderThread()
        assertEquals("nothing vanishes before the merge", inkStamps, library.front)
        assertEquals(0, library.scenesDrawn)
        settle()
        assertEquals(1, library.scenesDrawn)
        assertEquals(erased, library.front)
        assertTrue(library.frontVisible)
    }

    @Test
    fun `erasing split by the pen's button lets the ink through once its commit is out`() {
        val erasing = sequencer.beginStroke()
        eraserStamp(erasing)
        penUp(erasing)
        val ink = sequencer.beginStroke()
        val inkStamps = listOf(stamp(ink), stamp(ink))
        runRenderThread()
        assertTrue("the ink waits behind the erasing, whose stamps are still on the engine", library.front.isEmpty())
        settle()
        assertEquals(inkStamps, library.front)
        assertTrue(library.frontVisible)
    }

    @Test
    fun `a touch dropped for the pen's button clears the front layer, and the erasing still shows`() {
        val touch = sequencer.beginStroke()
        stamp(touch)
        runRenderThread()
        cancel(touch)
        val erasing = sequencer.beginStroke()
        val erased = listOf(eraserStamp(erasing))
        settle()
        assertEquals(erased, library.front)
        assertTrue(library.frontVisible)
    }

    @Test
    fun `a fetch left over from a finished stroke finds nothing`() {
        val id = sequencer.beginStroke()
        stamp(id)
        engineCommit(id)
        assertTrue(sequencer.takeCommands().isEmpty())
    }

    @Test
    fun `a redraw asked for during a stroke waits, and the stroke's commit covers it`() {
        val id = sequencer.beginStroke()
        val stamps = listOf(stamp(id))
        runRenderThread()
        sceneChanged()
        settle()
        assertEquals("redrawing now would hide the stroke", 0, library.scenesDrawn)
        assertEquals(stamps, library.front)

        penUp(id)
        settle()
        assertEquals("one draw is all it takes", 1, library.scenesDrawn)
        assertEquals(library.sceneGeneration, library.drawnGeneration)
    }

    @Test
    fun `a redraw asked for during a stroke is honoured after a cancel`() {
        val id = sequencer.beginStroke()
        stamp(id)
        runRenderThread()
        sceneChanged()
        cancel(id)
        settle()
        assertEquals(1, library.cancels)
        assertEquals(1, library.scenesDrawn)
        assertEquals(library.sceneGeneration, library.drawnGeneration)
    }

    @Test
    fun `a cancel with no redraw waiting only clears the front layer`() {
        val id = sequencer.beginStroke()
        stamp(id)
        runRenderThread()
        cancel(id)
        settle()
        assertEquals(0, library.scenesDrawn)
        assertTrue(library.front.isEmpty())
    }

    @Test
    fun `cancelling a stroke still held back leaves the older one showing until its commit`() {
        val first = sequencer.beginStroke()
        val shown = listOf(stamp(first))
        runRenderThread()
        penUp(first)
        val second = sequencer.beginStroke()
        stamp(second)
        sceneChanged()
        cancel(second)
        assertEquals(0, library.cancels)
        assertEquals(0, library.commitCount)
        runRenderThread()
        assertEquals(shown, library.front)
        assertTrue(library.frontVisible)

        settle()
        assertEquals("the older stroke's commit also covers the redraw", 1, library.scenesDrawn)
        assertEquals(library.sceneGeneration, library.drawnGeneration)
        assertFalse(library.frontVisible)
    }

    @Test
    fun `redraw requests merge until the draw starts, and one after that still counts`() {
        repeat(5) { sceneChanged() }
        assertEquals(1, library.commitCount)
        runRenderThread()
        sceneChanged()
        assertEquals(2, library.commitCount)
        settle()
        assertEquals(2, library.scenesDrawn)
        assertEquals(library.sceneGeneration, library.drawnGeneration)
    }

    @Test
    fun `a commit already issued counts as the redraw until its draw starts`() {
        val id = sequencer.beginStroke()
        engineCommit(id)
        sceneChanged()
        assertEquals(1, library.commitCount)
        settle()
        assertEquals(1, library.scenesDrawn)
        assertEquals(library.sceneGeneration, library.drawnGeneration)
    }

    @Test
    fun `nothing reaches the library after closing`() {
        val id = sequencer.beginStroke()
        stamp(id)
        runRenderThread()
        sequencer.close()
        library.queuedPaints = 0
        stamp(id)
        sceneChanged()
        sequencer.commitStroke(id)
        sequencer.cancelStroke(id)
        sequencer.commitStroke(sequencer.beginStroke())
        assertEquals(0, library.commitCount)
        assertEquals(0, library.cancels)
        assertEquals(0, library.queuedPaints)
        assertTrue(library.renderThread.isEmpty())
        assertTrue(sequencer.isClosed)
    }

    /**
     * The real proof of the rules. Touches, the engine, the render thread and the system's buffer releases all run at their own pace,
     * so this tries a great many orders. The model itself fails if a finished stroke paints or two strokes share the front layer.
     */
    @Test
    fun `no order of events loses a stamp or a redraw`() {
        for (seed in 0 until 3000) checkRandomOrder(seed)
    }

    private fun checkRandomOrder(seed: Int) {
        val library = LibraryModel()
        val sequencer = WetStrokeSequencer<Stamp>(library).also { library.sequencer = it }
        val engine = ArrayDeque<() -> Unit>()
        val random = Random(seed)
        val expected = HashMap<Long, MutableList<Stamp>>()
        var active: Long? = null
        var activeIsEraser = false
        var number = 0

        fun addStamp(id: Long, eraser: Boolean) {
            val stamp = Stamp(id, number++)
            expected.getValue(id) += stamp
            if (eraser) engine.addLast { sequencer.add(id, stamp) } else sequencer.add(id, stamp)
        }

        fun penUp(id: Long) {
            engine.addLast {
                sequencer.commitStroke(id)
                library.finishedStrokes += id
            }
        }

        repeat(random.nextInt(10, 120)) {
            val current = active
            when (random.nextInt(9)) {
                0 -> {
                    if (current == null) {
                        val id = sequencer.beginStroke()
                        expected[id] = ArrayList()
                        active = id
                        activeIsEraser = random.nextBoolean()
                    }
                }

                1, 2 -> {
                    if (current != null) addStamp(current, activeIsEraser)
                }

                3 -> {
                    if (current != null) {
                        penUp(current)
                        active = null
                    }
                }

                4 -> {
                    if (current != null && random.nextInt(3) == 0) {
                        sequencer.cancelStroke(current)
                        library.finishedStrokes += current
                        active = null
                    }
                }

                5 -> {
                    library.sceneGeneration++
                    sequencer.requestRedraw()
                }

                6 -> {
                    if (engine.isNotEmpty()) engine.removeFirst()()
                }

                7 -> {
                    if (library.renderThread.isNotEmpty()) library.runRenderTask()
                }

                8 -> {
                    if (library.mainThread.isNotEmpty()) library.mainThread.removeFirst()()
                    if (library.pendingReleases > 0 && random.nextBoolean()) library.releaseBuffer()
                }
            }
        }

        fun settleAll() {
            while (engine.isNotEmpty() || library.renderThread.isNotEmpty() || library.mainThread.isNotEmpty() ||
                library.pendingReleases > 0
            ) {
                while (engine.isNotEmpty()) engine.removeFirst()()
                while (library.renderThread.isNotEmpty()) library.runRenderTask()
                while (library.mainThread.isNotEmpty()) library.mainThread.removeFirst()()
                if (library.pendingReleases > 0) library.releaseBuffer()
            }
        }

        settleAll()
        active?.let { id ->
            val stamps = expected.getValue(id)
            if (stamps.isEmpty()) {
                assertTrue("seed $seed: old ink was left showing", !library.frontVisible || library.front.isEmpty())
            } else {
                assertEquals("seed $seed: the stroke under way must be on show in full", stamps, library.front)
                assertTrue("seed $seed: the stroke under way must be visible", library.frontVisible)
            }
            penUp(id)
            settleAll()
        }
        assertEquals("seed $seed: a redraw was lost", library.sceneGeneration, library.drawnGeneration)
        assertTrue("seed $seed: wet ink was left showing", !library.frontVisible || library.front.isEmpty())
        assertEquals("seed $seed: a commit never finished", 0, library.commitCount)
    }
}
