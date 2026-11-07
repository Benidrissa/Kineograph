package org.kineograph.app

import android.Manifest
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.kineograph.feature.editor.PlaybackSlot
import java.io.File
import kotlin.math.abs

/**
 * The Phase 1 acceptance test, run through the real app as a user would:
 *
 *  1. Create a 12 FPS project.
 *  2. Draw on 12 frames with simulated MotionEvents.
 *  3. Turn on onion skin, see that it is on, then play and see that playback moves.
 *  4. Export a GIF and an MP4.
 *  5. Check with MediaExtractor that the MP4 has 12 frames lasting 1.0 s, and that there is no watermark.
 *
 * That no ghosts reach an export is checked pixel for pixel in GifExportTest, where nothing depends on a video encoder's noise.
 */
@RunWith(AndroidJUnit4::class)
class BouncingBallEndToEndTest {
    @get:Rule(order = 0)
    val notifications: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            GrantPermissionRule.grant()
        }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun bouncingBallFromStartToFinish() {
        createProject()
        drawTwelveFrames()
        turnOnOnionSkinAndPlay()

        val gif = export("format_gif", "gif")
        val mp4 = export("format_mp4", "mp4")

        assertGifIsRight(gif)
        assertMp4IsRight(mp4)
    }

    private fun createProject() {
        waitForTag("new_project_button")
        compose.onNodeWithTag("new_project_button").performClick()
        waitForTag("fps_helper")
        // The default is 12 FPS, and the sheet says what that means.
        compose.onNodeWithTag("fps_helper").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("create_project").performScrollTo().performClick()
        waitForTag("canvas_host", LONG_WAIT_MS)
        waitForTag("frame_0", LONG_WAIT_MS)
    }

    private fun drawTwelveFrames() {
        repeat(FRAME_COUNT) { frame ->
            drawBall(frame)
            if (frame < FRAME_COUNT - 1) {
                compose.onNodeWithTag("add_frame").performClick()
                waitForTag("frame_${frame + 1}")
                compose.waitForIdle()
                // Give the engine a moment to switch to the new frame before the next drawing.
                SystemClock.sleep(SETTLE_MS)
            }
        }
    }

    /** Scribbles a ball that falls and rises across the 12 frames, using real touch events on the real view. */
    private fun drawBall(frame: Int) {
        val canvas = findCanvas()
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        val phase = frame / (FRAME_COUNT - 1f)
        val centerX = width * (0.3f + 0.4f * phase)
        val centerY = height * (0.35f + 0.3f * (1f - abs(2f * phase - 1f)))
        val radius = minOf(width, height) * 0.06f
        val start = SystemClock.uptimeMillis()
        var time = start
        val points = 24
        instrumentation.runOnMainSync {
            fun send(action: Int, x: Float, y: Float, at: Long) {
                val event = MotionEvent.obtain(start, at, action, x, y, 1f, 1f, 0, 1f, 1f, 0, 0)
                canvas.dispatchTouchEvent(event)
                event.recycle()
            }
            send(MotionEvent.ACTION_DOWN, centerX + radius, centerY, time)
            for (i in 1..points) {
                time += TOUCH_INTERVAL_MS
                val angle = i * 2.0 * Math.PI / points
                send(
                    MotionEvent.ACTION_MOVE,
                    centerX + (radius * Math.cos(angle)).toFloat(),
                    centerY + (radius * Math.sin(angle)).toFloat(),
                    time,
                )
            }
            send(MotionEvent.ACTION_UP, centerX + radius, centerY, time + TOUCH_INTERVAL_MS)
        }
    }

    private fun turnOnOnionSkinAndPlay() {
        compose.onNodeWithTag("onion_button").performClick()
        // The change goes through the engine thread, which the compose rule does not wait for.
        compose.waitUntil("the onion skin to turn on", DEFAULT_WAIT_MS) {
            compose.onAllNodes(hasTestTag("onion_button") and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
        SystemClock.sleep(SETTLE_MS)
        tapPlayUntilPlaying()
        assertPlaybackMoves()
        compose.onNodeWithTag("stop_button").performClick()
        waitForTag("play_button", LONG_WAIT_MS)
    }

    /**
     * The system can recreate the activity at any moment on a freshly booted emulator, as it did to this test once on API 34. That stops
     * playback, as leaving the screen should, and the play button comes back. A person would tap it again, so this does too.
     */
    private fun tapPlayUntilPlaying() {
        var tries = 0
        while (true) {
            waitForTag("play_button", LONG_WAIT_MS)
            compose.onNodeWithTag("play_button").performClick()
            try {
                waitForTag("stop_button", PLAY_WAIT_MS)
                return
            } catch (e: ComposeTimeoutException) {
                if (++tries == PLAY_TRIES) throw e
            }
        }
    }

    /**
     * Stop only shows that play mode was entered, which happens before the clock starts. Skipping frames on a slow device is
     * correct, so this asks for movement and not for every frame.
     */
    private fun assertPlaybackMoves() {
        val seen = HashSet<Int>()
        compose.waitUntil("playback to go through $SLOTS_TO_SEE frames", LONG_WAIT_MS) {
            compose
                .onAllNodes(hasTestTag("playback_bar"))
                .fetchSemanticsNodes()
                .firstOrNull()
                ?.config
                ?.getOrNull(PlaybackSlot)
                ?.let(seen::add)
            seen.size >= SLOTS_TO_SEE
        }
    }

    private fun export(formatTag: String, extension: String): File {
        compose.onNodeWithTag("export_button").performClick()
        waitForTag(formatTag)
        compose.onNodeWithTag(formatTag).performScrollTo().performClick()
        compose.onNodeWithTag("start_export").performScrollTo().performClick()
        compose.waitUntil(EXPORT_WAIT_MS) {
            compose.onAllNodes(hasTestTag("export_done")).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasTestTag("export_failed")).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "The $extension export failed: see the worker's logs",
            compose.onAllNodes(hasTestTag("export_failed")).fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithTag("export_done").performClick()
        compose.waitForIdle()
        val directory = File(compose.activity.cacheDir, "exports")
        // Each export has a directory of its own.
        val output = directory.walkTopDown().filter { it.isFile && it.extension == extension }.maxByOrNull { it.lastModified() }
        assertTrue("no .$extension in $directory", output != null && output.length() > 0)
        return checkNotNull(output)
    }

    private fun assertGifIsRight(file: File) {
        val data = file.readBytes()
        assertEquals("GIF89a", String(data, 0, 6))
        var frames = 0
        var totalDelay = 0
        var i = HEADER_SIZE
        // The header has no global table; walk the blocks.
        while (i < data.size) {
            when (data[i].toInt() and 0xFF) {
                0x21 -> {
                    val label = data[i + 1].toInt() and 0xFF
                    if (label == 0xF9) {
                        totalDelay += (data[i + 4].toInt() and 0xFF) or ((data[i + 5].toInt() and 0xFF) shl 8)
                    }
                    i += 2
                    i = skipSubBlocks(data, i)
                }

                0x2C -> {
                    frames++
                    val packed = data[i + 9].toInt() and 0xFF
                    i += 10
                    if (packed and 0x80 != 0) i += 3 * (1 shl ((packed and 7) + 1))
                    i++ // LZW minimum code size
                    i = skipSubBlocks(data, i)
                }

                0x3B -> {
                    break
                }

                else -> {
                    error("unexpected GIF block ${data[i]} at $i")
                }
            }
        }
        assertEquals("GIF frames", FRAME_COUNT, frames)
        assertEquals("GIF length in hundredths", 100, totalDelay)
    }

    private fun skipSubBlocks(data: ByteArray, start: Int): Int {
        var i = start
        while (true) {
            val size = data[i].toInt() and 0xFF
            i++
            if (size == 0) return i
            i += size
        }
    }

    private fun assertMp4IsRight(file: File) {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.path)
        var videoTrack = -1
        for (t in 0 until extractor.trackCount) {
            if (extractor.getTrackFormat(t).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) videoTrack = t
        }
        assertTrue("no video track", videoTrack >= 0)
        extractor.selectTrack(videoTrack)
        val format = extractor.getTrackFormat(videoTrack)

        var samples = 0
        val times = ArrayList<Long>()
        while (extractor.sampleTime >= 0) {
            times += extractor.sampleTime
            samples++
            extractor.advance()
        }
        extractor.release()
        assertEquals("MP4 frames", FRAME_COUNT, samples)
        val durationUs = format.getLong(MediaFormat.KEY_DURATION)
        assertTrue("duration was $durationUs us", abs(durationUs - ONE_SECOND_US) <= DURATION_TOLERANCE_US)
        // Each frame must show for one twelfth of a second.
        for (n in 1 until times.size) {
            val step = times[n] - times[n - 1]
            assertTrue("frame $n lasted $step us", abs(step - ONE_SECOND_US / FRAME_COUNT) <= FRAME_STEP_TOLERANCE_US)
        }
        assertNoWatermark(file)
    }

    /**
     * Decodes every frame of the video. With a plain white background, each corner of each frame has to stay plain white,
     * and the drawing must be there. MediaMetadataRetriever is not used, because it returns nothing for some frames on Android 8.
     */
    private fun assertNoWatermark(file: File) {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.path)
        val track = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") ==
                true
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val decoder = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
        decoder.configure(format, null, null, 0)
        decoder.start()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var decoded = 0
        var sawInk = false
        while (!outputDone) {
            if (!inputDone) {
                val input = decoder.dequeueInputBuffer(DECODE_TIMEOUT_US)
                if (input >= 0) {
                    val size = extractor.readSampleData(checkNotNull(decoder.getInputBuffer(input)), 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val output = decoder.dequeueOutputBuffer(info, DECODE_TIMEOUT_US)
            if (output >= 0) {
                if (info.size > 0) {
                    val image = checkNotNull(decoder.getOutputImage(output))
                    if (assertCornersWhiteAndFindInk(image)) sawInk = true
                    image.close()
                    decoded++
                }
                decoder.releaseOutputBuffer(output, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        }
        decoder.stop()
        decoder.release()
        extractor.release()
        assertEquals("frames decoded from the video", FRAME_COUNT, decoded)
        assertTrue("the drawing never appeared in the video", sawInk)
    }

    /** Checks brightness, which is the Y plane of a decoded frame. Returns whether the frame contains dark ink. */
    private fun assertCornersWhiteAndFindInk(image: Image): Boolean {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val w = image.width
        val h = image.height
        fun brightness(x: Int, y: Int): Int = buffer.get(y * plane.rowStride + x * plane.pixelStride).toInt() and 0xFF
        val cornerW = w / CORNER_FRACTION
        val cornerH = h / CORNER_FRACTION
        for (y in 2 until cornerH step CORNER_STEP) {
            for (x in 2 until cornerW step CORNER_STEP) {
                for (value in intArrayOf(
                    brightness(x, y),
                    brightness(w - 1 - x, y),
                    brightness(x, h - 1 - y),
                    brightness(
                        w - 1 - x,
                        h - 1 - y,
                    ),
                )) {
                    assertTrue("expected a white corner, got brightness $value", value > WHITE_THRESHOLD)
                }
            }
        }
        for (y in 0 until h step SAMPLE_STEP) {
            for (x in 0 until w step SAMPLE_STEP) {
                if (brightness(x, y) < INK_THRESHOLD) return true
            }
        }
        return false
    }

    private fun findCanvas(): View {
        var found: View? = null
        instrumentation.runOnMainSync { found = search(compose.activity.window.decorView) }
        return checkNotNull(found) { "no canvas view" }
    }

    private fun search(view: View): View? {
        if (view.tag == "canvas") return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) search(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun waitForTag(tag: String, timeoutMs: Long = DEFAULT_WAIT_MS) {
        compose.waitUntil(timeoutMs) {
            compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val FRAME_COUNT = 12
        const val HEADER_SIZE = 13
        const val ONE_SECOND_US = 1_000_000L
        const val DURATION_TOLERANCE_US = 100_000L
        const val FRAME_STEP_TOLERANCE_US = 2_000L
        const val DEFAULT_WAIT_MS = 15_000L
        const val LONG_WAIT_MS = 60_000L
        const val PLAY_WAIT_MS = 20_000L
        const val PLAY_TRIES = 3
        const val EXPORT_WAIT_MS = 300_000L
        const val SETTLE_MS = 300L
        const val SLOTS_TO_SEE = 3
        const val TOUCH_INTERVAL_MS = 8L
        const val CORNER_FRACTION = 8
        const val CORNER_STEP = 4
        const val SAMPLE_STEP = 4

        // Video stores brightness from 16 (black) to 235 (white), so white decodes to about 235, not 255.
        const val WHITE_THRESHOLD = 225
        const val INK_THRESHOLD = 80
        const val DECODE_TIMEOUT_US = 10_000L
    }
}
