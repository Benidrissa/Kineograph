package org.kineograph.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Choreographer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.kineograph.engine.compositor.FlattenOptions
import org.kineograph.engine.compositor.FrameFlattener
import org.kineograph.engine.session.CelStore
import org.kineograph.feature.editor.canvas.PlaybackDisplay
import org.kineograph.model.Background
import org.kineograph.model.PlaybackMode
import org.kineograph.model.Project
import org.kineograph.model.Timeline
import org.kineograph.model.TimelineIndex
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/** What playback is doing. */
internal data class PlaybackState(
    val isPlaying: Boolean = false,
    val isPreparing: Boolean = false,
    val slotIndex: Int = 0,
    /** Stays playing as far as the rest of the editor goes, holding one frame on the full-screen preview. */
    val isPaused: Boolean = false,
)

/**
 * Where the playhead is and which way it is about to go. Decoding threads read this, so these travel as one thing and
 * can never disagree with each other.
 */
internal data class Playhead(val slot: Int, val reversed: Boolean = false, val pingPong: Boolean = false)

/**
 * Which frames to have ready, in order of when they will be needed. Going by slot number only works when play keeps going
 * forward: ping-pong comes back through the frames it just played, so those are the ones to keep, and the frames at
 * the far end of the animation are not coming up next just because the numbers wrap around to them.
 */
internal object PlaybackWindow {
    /** The first slot in the window around [head] for which [wanted] says yes. */
    fun firstWanted(head: Playhead, slotCount: Int, capacity: Int, wanted: (Int) -> Boolean): Int? {
        val size = min(capacity, slotCount)
        val slot = head.slot
        if (!head.pingPong) {
            for (offset in 0 until size) {
                val next = (slot + offset) % slotCount
                if (wanted(next)) return next
            }
            return null
        }
        // Near a turn, the frames still to come are those up to the end and then the same ones on the way back. The window
        // stays on one set of slots across a turn, so nothing is thrown out and decoded again because the direction changed.
        if (head.reversed) {
            val high = max(slot, size - 1)
            for (next in slot downTo high - size + 1) {
                if (wanted(next)) return next
            }
            for (next in slot + 1..high) {
                if (wanted(next)) return next
            }
        } else {
            val low = min(slot, slotCount - size)
            for (next in slot..low + size - 1) {
                if (wanted(next)) return next
            }
            for (next in slot - 1 downTo low) {
                if (wanted(next)) return next
            }
        }
        return null
    }

    /** How many frames from now [slot] will next be on screen. The highest is the one to throw out. */
    fun timeUntilShown(slot: Int, head: Playhead, slotCount: Int): Int =
        when {
            !head.pingPong -> (slot - head.slot).mod(slotCount)
            head.reversed -> if (slot <= head.slot) head.slot - slot else head.slot + slot
            slot >= head.slot -> slot - head.slot
            else -> (slotCount - 1 - head.slot) + (slotCount - 1 - slot)
        }
}

/**
 * Plays the animation. The clock runs on Choreographer and never waits for anything: a frame not decoded yet is
 * skipped rather than slowing the animation down, so timing is always right. Frames are flattened ahead of the
 * playhead on background threads, at the size they are shown, and kept as hardware bitmaps where possible, so they
 * take no room on the heap.
 *
 * Only as many frames as the memory budget allows stay cached. If that is the whole animation, then after one pass
 * no more decoding is needed; if not, the cache becomes a window that slides along with the playhead.
 */
internal class PlaybackController(
    private val scope: CoroutineScope,
    private val store: CelStore,
    private val playbackBudgetBytes: Long,
    private val isLowRam: Boolean,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> get() = _state

    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }
    private val cache = ConcurrentHashMap<Int, Bitmap>()
    private val inFlight = ConcurrentHashMap.newKeySet<Int>()
    private val jobs = ArrayList<Job>()

    private var display: PlaybackDisplay? = null
    private var project: Project? = null
    private var index: TimelineIndex? = null
    private var mode = PlaybackMode.LOOP
    private var fps = 12
    private var clockStartNanos = 0L
    private var shownSlot = -1
    private var startTick = 0
    private var running = false
    private var clockRunning = false

    /** Decoding waits on this, so that a held frame costs nothing however long it is held. */
    private val paused = MutableStateFlow(false)

    @Volatile
    private var capacity = 1

    @Volatile
    private var head = Playhead(0)

    private val frameCallback =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return
                onFrame(frameTimeNanos)
                if (running) choreographer.postFrameCallback(this)
            }
        }

    /** Starts playing [target] from [startSlot], with frames scaled by [displayScale]. Call on the main thread. */
    fun start(target: Project, startSlot: Int, playbackMode: PlaybackMode, displayScale: Float, targetDisplay: PlaybackDisplay) {
        stop()
        val timeline = TimelineIndex(target.frames)
        project = target
        index = timeline
        display = targetDisplay
        mode = playbackMode
        fps = target.fps
        startTick = timeline.startTick(startSlot)
        head = Playhead(startSlot, pingPong = playbackMode == PlaybackMode.PING_PONG)
        shownSlot = -1
        running = true
        clockRunning = false

        val scale = displayScale.coerceIn(MIN_SCALE, 1f)
        val width = max(1, (target.width * scale).toInt())
        val height = max(1, (target.height * scale).toInt())
        val frameBytes = width.toLong() * height * BYTES_PER_PIXEL
        capacity = (playbackBudgetBytes / frameBytes).coerceIn(MIN_CACHED_FRAMES.toLong(), MAX_CACHED_FRAMES.toLong()).toInt()
        val prerollFrames = min(min(capacity, target.frames.size), PREROLL_FRAMES)

        val workerCount = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, if (isLowRam) 2 else 3)
        repeat(workerCount) {
            jobs +=
                scope.launch(
                    Dispatchers.Default,
                ) { decodeLoop(target, timeline, width, height, scale, targetDisplay.supportsHardwareBitmaps) }
        }
        _state.value = PlaybackState(isPlaying = true, isPreparing = true, slotIndex = startSlot)
        jobs +=
            scope.launch {
                // Give decoding a head start so the first moments are not all skipped frames, but never wait long.
                var waited = 0L
                while (isActive && waited < MAX_PREROLL_WAIT_MS && cache.size < prerollFrames) {
                    delay(PREROLL_POLL_MS)
                    waited += PREROLL_POLL_MS
                }
                if (running) {
                    clockStartNanos = nanoTime()
                    clockRunning = true
                    _state.value = _state.value.copy(isPreparing = false)
                    choreographer.postFrameCallback(frameCallback)
                }
            }
    }

    /** Changes the speed from now on, without jumping to a different frame. */
    fun setFps(newFps: Int, nowNanos: Long = nanoTime()) {
        if (!running || !clockRunning) {
            fps = newFps
            return
        }
        foldClock(nowNanos)
        clockStartNanos = nowNanos
        fps = newFps
    }

    /** Moves the time the clock has run so far into [startTick], so the clock can start again from now without a jump. */
    private fun foldClock(nowNanos: Long) {
        startTick += ((nowNanos - clockStartNanos) * fps / NANOS_PER_SECOND).toInt()
    }

    /** Holds on the frame showing, or lets it go on. Waits until there is a frame to hold. */
    fun togglePause(nowNanos: Long = nanoTime()) {
        if (!running) return
        if (paused.value) {
            clockStartNanos = nowNanos
            clockRunning = true
            setPaused(false)
            choreographer.removeFrameCallback(frameCallback)
            choreographer.postFrameCallback(frameCallback)
        } else if (clockRunning && shownSlot >= 0) {
            foldClock(nowNanos)
            clockRunning = false
            choreographer.removeFrameCallback(frameCallback)
            setPaused(true)
        }
    }

    private fun setPaused(value: Boolean) {
        paused.value = value
        _state.value = _state.value.copy(isPaused = value)
    }

    fun setMode(playbackMode: PlaybackMode) {
        mode = playbackMode
        head = head.copy(pingPong = playbackMode == PlaybackMode.PING_PONG, reversed = false)
    }

    fun stop() {
        running = false
        clockRunning = false
        paused.value = false
        choreographer.removeFrameCallback(frameCallback)
        jobs.forEach { it.cancel() }
        jobs.clear()
        display?.setPlaybackFrame(null)
        display = null
        cache.clear()
        inFlight.clear()
        project = null
        index = null
        _state.value = PlaybackState()
    }

    private fun onFrame(frameTimeNanos: Long) {
        val timeline = index ?: return
        val elapsed = (frameTimeNanos - clockStartNanos).coerceAtLeast(0L) * fps / NANOS_PER_SECOND + startTick
        val tick = Timeline.playbackTick(elapsed, timeline.totalTicks, mode)
        if (tick == null) {
            stop()
            return
        }
        val slot = timeline.slotAtTick(tick)
        // This comes from the clock rather than from which way the slots moved, because a held frame does not move.
        val pingPong = mode == PlaybackMode.PING_PONG
        head = Playhead(slot, pingPong && Timeline.isPingPongReversed(elapsed, timeline.totalTicks), pingPong)
        if (slot != shownSlot) {
            val frame = cache[slot]
            if (frame != null) {
                shownSlot = slot
                display?.setPlaybackFrame(frame)
                _state.value = _state.value.copy(slotIndex = slot)
            }
        }
    }

    private suspend fun decodeLoop(target: Project, timeline: TimelineIndex, width: Int, height: Int, scale: Float, hardware: Boolean) {
        val flattener = FrameFlattener(store)
        val background = (target.background as? Background.Image)?.let { store.read(it.file) }
        val options = FlattenOptions(forEditor = true, includeReferenceLayers = true)
        var scratch: Bitmap? = null
        var scratchCanvas: Canvas? = null
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            paused.first { !it }
            val slot = nextSlotToDecode(timeline.slotCount)
            if (slot == null) {
                delay(IDLE_POLL_MS)
                continue
            }
            try {
                val surface = scratch ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { scratch = it }
                val canvas = scratchCanvas ?: Canvas(surface).also { scratchCanvas = it }
                surface.eraseColor(0)
                flattener.flatten(target, target.frames[slot].id, canvas, scale, background, options)
                val finished = if (hardware) {
                    surface.copy(
                        Bitmap.Config.HARDWARE,
                        false,
                    ) ?: surface.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    surface.copy(Bitmap.Config.ARGB_8888, false)
                }
                if (finished != null) {
                    evictIfFull(slot, timeline.slotCount)
                    cache[slot] = finished
                }
            } catch (_: OutOfMemoryError) {
                // Plays on with what fits.
                capacity = max(MIN_CACHED_FRAMES, capacity / 2)
                evictIfFull(slot, timeline.slotCount)
                delay(IDLE_POLL_MS)
            } finally {
                inFlight.remove(slot)
            }
        }
    }

    /** The next frame to be shown that is neither ready nor being worked on, if any is wanted. */
    private fun nextSlotToDecode(slotCount: Int): Int? =
        PlaybackWindow.firstWanted(head, slotCount, capacity) { slot -> !cache.containsKey(slot) && inFlight.add(slot) }

    private fun evictIfFull(incoming: Int, slotCount: Int) {
        while (cache.size >= capacity) {
            val current = head
            // Throw out what would be needed last.
            val victim =
                cache.keys.filter { it != incoming }.maxByOrNull { PlaybackWindow.timeUntilShown(it, current, slotCount) } ?: return
            cache.remove(victim)
        }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val BYTES_PER_PIXEL = 4L
        const val MIN_SCALE = 0.05f
        const val MIN_CACHED_FRAMES = 4
        const val MAX_CACHED_FRAMES = 2000
        const val PREROLL_FRAMES = 12
        const val MAX_PREROLL_WAIT_MS = 800L
        const val PREROLL_POLL_MS = 16L
        const val IDLE_POLL_MS = 16L
    }
}
