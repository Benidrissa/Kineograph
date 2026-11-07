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
internal data class PlaybackState(val isPlaying: Boolean = false, val isPreparing: Boolean = false, val slotIndex: Int = 0)

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
    private var capacity = 1

    @Volatile
    private var playhead = 0

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
        playhead = startSlot
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
                    clockStartNanos = System.nanoTime()
                    clockRunning = true
                    _state.value = _state.value.copy(isPreparing = false)
                    choreographer.postFrameCallback(frameCallback)
                }
            }
    }

    /** Changes the speed from now on, without jumping to a different frame. */
    fun setFps(newFps: Int, nowNanos: Long = System.nanoTime()) {
        if (!running || !clockRunning) {
            fps = newFps
            return
        }
        val elapsed = (nowNanos - clockStartNanos) * fps / NANOS_PER_SECOND
        startTick += elapsed.toInt()
        clockStartNanos = nowNanos
        fps = newFps
    }

    fun setMode(playbackMode: PlaybackMode) {
        mode = playbackMode
    }

    fun stop() {
        running = false
        clockRunning = false
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
        playhead = slot
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

    /** The first frame ahead of the playhead that is neither ready nor being worked on, if any is wanted. */
    private fun nextSlotToDecode(slotCount: Int): Int? {
        val start = playhead
        val lookAhead = min(capacity, slotCount)
        for (offset in 0 until lookAhead) {
            val slot = (start + offset) % slotCount
            if (!cache.containsKey(slot) && inFlight.add(slot)) return slot
        }
        return null
    }

    private fun evictIfFull(incoming: Int, slotCount: Int) {
        while (cache.size >= capacity) {
            val start = playhead
            // Throw out what would be needed last: the frame furthest ahead in playing order.
            val victim = cache.keys.filter { it != incoming }.maxByOrNull { (it - start).mod(slotCount) } ?: return
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
