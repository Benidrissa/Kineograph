package org.kineograph.media.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.kineograph.engine.compositor.BackgroundPainter
import org.kineograph.engine.compositor.FlattenOptions
import org.kineograph.engine.compositor.FrameFlattener
import org.kineograph.engine.session.CelStore
import org.kineograph.media.gif.GifEncoder
import org.kineograph.media.gif.GifTiming
import org.kineograph.media.video.H264Encoder
import org.kineograph.media.video.PlatformEncoder
import org.kineograph.media.video.VideoFrameRate
import org.kineograph.media.video.VideoSizeResolver
import org.kineograph.media.video.videoBitrate
import org.kineograph.model.Background
import org.kineograph.model.Project
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** How an export finished. */
public data class ExportResult(
    val file: File,
    val width: Int,
    val height: Int,
    /** How many frames the file contains. */
    val frameCount: Long,
    /** The device could not handle the size asked for, so it was reduced. */
    val wasReduced: Boolean,
)

/** Thrown when a device simply has no way of doing an export. The message is not shown to users directly. */
public class ExportNotSupportedException(message: String) : IOException(message)

/**
 * Turns a project into a video or GIF. Frames are read from disk one at a time, so memory use does not grow with
 * the length of the animation. Nothing is ever drawn on top of the artwork: no watermark, logo or credit.
 */
public class ProjectExporter(private val store: CelStore) {
    /** Reports progress in 0..1. Suspends so that cancelling the job stops the export promptly. */
    public suspend fun export(project: Project, config: ExportConfig, output: File, onProgress: (Float) -> Unit): ExportResult =
        when (config.format) {
            ExportFormat.MP4 -> exportVideo(project, config, output, onProgress)
            ExportFormat.GIF -> exportGif(project, config, output, onProgress)
        }

    private fun scaledSize(project: Project, scale: Float): Pair<Int, Int> =
        (project.width * scale).roundToInt().coerceAtLeast(1) to (project.height * scale).roundToInt().coerceAtLeast(1)

    private fun backgroundImage(project: Project): Bitmap? = (project.background as? Background.Image)?.let { store.read(it.file) }

    private suspend fun exportVideo(project: Project, config: ExportConfig, output: File, onProgress: (Float) -> Unit): ExportResult {
        val limits = PlatformEncoder.avcLimits() ?: throw ExportNotSupportedException("This device has no H.264 encoder")
        val (wantedWidth, wantedHeight) = scaledSize(project, config.scale)
        val plan = ExportPlan.of(project.frames, project.fps, config, VideoFrameRate::ticksMultiplier)
        val size =
            VideoSizeResolver.resolve(wantedWidth, wantedHeight, plan.encodedFps, limits)
                ?: throw ExportNotSupportedException("The encoder cannot handle ${wantedWidth}x$wantedHeight")

        val frame = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        val flattener = FrameFlattener(store)
        val background = backgroundImage(project)
        val contentScale = size.contentWidth.toFloat() / project.width
        val offsetX = (size.width - size.contentWidth) / 2f
        val offsetY = (size.height - size.contentHeight) / 2f
        val options = FlattenOptions(includeBackground = config.includeBackground)
        val letterbox = letterboxColor(project)

        val encoder =
            H264Encoder(
                output,
                size.width,
                size.height,
                plan.encodedFps,
                videoBitrate(size.width, size.height, plan.encodedFps, config.videoQuality),
            )
        var drawnSlot = -1
        try {
            for (tick in 0 until plan.totalTicks) {
                currentCoroutineContext().ensureActive()
                val planned = plan.tickAt(tick)
                // A frame held on screen does not need drawing again.
                if (planned.slotIndex != drawnSlot) {
                    drawFrame(
                        canvas,
                        frame,
                        flattener,
                        project,
                        planned.slotIndex,
                        config,
                        background,
                        contentScale,
                        offsetX,
                        offsetY,
                        options,
                        letterbox,
                    )
                    drawnSlot = planned.slotIndex
                }
                encoder.addFrame(frame, planned.presentationUs)
                onProgress((tick + 1).toFloat() / plan.totalTicks)
            }
            encoder.finish()
        } finally {
            encoder.release()
        }
        return ExportResult(output, size.width, size.height, plan.totalTicks, size.wasReduced)
    }

    private fun drawFrame(
        canvas: Canvas,
        frame: Bitmap,
        flattener: FrameFlattener,
        project: Project,
        slotIndex: Int,
        config: ExportConfig,
        background: Bitmap?,
        contentScale: Float,
        offsetX: Float,
        offsetY: Float,
        options: FlattenOptions,
        letterbox: Int,
    ) {
        frame.eraseColor(letterbox)
        val checkpoint = canvas.save()
        canvas.translate(offsetX, offsetY)
        flattener.flatten(project, frameIdAt(project, slotIndex, config), canvas, contentScale, background, options)
        canvas.restoreToCount(checkpoint)
    }

    private fun frameIdAt(project: Project, slotIndex: Int, config: ExportConfig): String {
        val start = (config.rangeStart ?: 0).coerceIn(0, project.frames.lastIndex)
        return project.frames[(start + slotIndex).coerceAtMost(project.frames.lastIndex)].id
    }

    /** Video has no transparency, so anything see-through has to sit on a solid colour. */
    private fun letterboxColor(project: Project): Int =
        when (val background = project.background) {
            is Background.Color -> background.argb or OPAQUE
            is Background.Paper -> BackgroundPainter.paperColor(background.kind)
            is Background.Image, Background.Transparent -> Color.WHITE
        }

    private suspend fun exportGif(project: Project, config: ExportConfig, output: File, onProgress: (Float) -> Unit): ExportResult {
        val (width, height) = scaledSize(project, config.scale)
        // A looping GIF repeats by itself; only a GIF that stops needs its repeats written out.
        val plan = ExportPlan.of(project.frames, project.fps, config)
        val singlePass = ExportPlan.of(project.frames, project.fps, config.copy(repeatCount = 1))
        val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        val flattener = FrameFlattener(store)
        val background = backgroundImage(project)
        val scale = width.toFloat() / project.width
        val options = FlattenOptions(includeBackground = config.includeBackground)
        val pixels = IntArray(width * height)
        val playCount = if (config.gifLoopForever) 0 else plan.repeatCount
        var written = 0L
        val total = singlePass.totalTicks

        BufferedOutputStream(FileOutputStream(output), STREAM_BUFFER).use { stream ->
            val encoder = GifEncoder(stream, width, height, playCount, config.gifMaxColors, config.gifDither)
            val timing = GifTiming(singlePass.fps)
            var drawnSlot = -1
            for (tick in 0 until total) {
                currentCoroutineContext().ensureActive()
                val planned = singlePass.tickAt(tick)
                if (planned.slotIndex != drawnSlot) {
                    frame.eraseColor(Color.TRANSPARENT)
                    flattener.flatten(project, frameIdAt(project, planned.slotIndex, config), canvas, scale, background, options)
                    frame.getPixels(pixels, 0, width, 0, 0, width, height)
                    drawnSlot = planned.slotIndex
                }
                val isLast = tick == total - 1
                val delay = if (isLast) max(timing.nextDelay(), timing.finalDelay()) else timing.nextDelay()
                if (delay > 0) {
                    encoder.addFrame(pixels, delay)
                    written++
                }
                onProgress((tick + 1).toFloat() / total)
            }
            encoder.finish()
        }
        return ExportResult(output, width, height, written, wasReduced = false)
    }

    private companion object {
        const val OPAQUE = 0xFF000000.toInt()
        const val STREAM_BUFFER = 64 * 1024
    }
}
