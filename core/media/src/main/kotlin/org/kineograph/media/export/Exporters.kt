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
import org.kineograph.media.gif.GifSizes
import org.kineograph.media.gif.GifTiming
import org.kineograph.media.video.H264Encoder
import org.kineograph.media.video.PlatformEncoder
import org.kineograph.media.video.VideoFrameRate
import org.kineograph.media.video.VideoLayout
import org.kineograph.media.video.VideoSizeResolver
import org.kineograph.media.video.videoBitrate
import org.kineograph.model.Background
import org.kineograph.model.Project
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/** How an export finished. */
public data class ExportResult(
    val file: File,
    val width: Int,
    val height: Int,
    /** How many frames the file contains. */
    val frameCount: Long,
    /** The size asked for could not be encoded, so it was reduced. */
    val wasReduced: Boolean,
    /** The video is a different shape from the picture, so bars fill the difference. */
    val wasPadded: Boolean = false,
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

    private fun backgroundImage(project: Project): Bitmap? = (project.background as? Background.Image)?.let { store.read(it.file) }

    private suspend fun exportVideo(project: Project, config: ExportConfig, output: File, onProgress: (Float) -> Unit): ExportResult {
        val limits = PlatformEncoder.avcLimits() ?: throw ExportNotSupportedException("This device has no H.264 encoder")
        val (wantedWidth, wantedHeight) = config.requestedSize(project.width, project.height)
        val plan = ExportPlan.of(project.frames, project.fps, config, VideoFrameRate::ticksMultiplier)
        val layout =
            VideoSizeResolver.layout(project.width, project.height, wantedWidth, wantedHeight, plan.encodedFps, limits)
                ?: throw ExportNotSupportedException("The encoder cannot handle ${wantedWidth}x$wantedHeight")

        val frame = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        val flattener = FrameFlattener(store)
        val background = backgroundImage(project)
        val options = FlattenOptions(includeBackground = config.includeBackground)
        val fill = videoFillColor(project.background, config.includeBackground)

        val encoder =
            H264Encoder(
                output,
                layout.width,
                layout.height,
                plan.encodedFps,
                videoBitrate(layout.width, layout.height, plan.encodedFps, config.videoQuality),
            )
        var drawnSlot = -1
        try {
            for (tick in 0 until plan.totalTicks) {
                currentCoroutineContext().ensureActive()
                val planned = plan.tickAt(tick)
                // A frame held on screen does not need drawing again.
                if (planned.slotIndex != drawnSlot) {
                    val frameId = frameIdAt(project, planned.slotIndex, config)
                    drawVideoFrame(frame, canvas, flattener, project, frameId, layout, fill, background, options)
                    drawnSlot = planned.slotIndex
                }
                encoder.addFrame(frame, planned.presentationUs)
                onProgress((tick + 1).toFloat() / plan.totalTicks)
            }
            encoder.finish()
        } finally {
            encoder.release()
        }
        return ExportResult(output, layout.width, layout.height, plan.totalTicks, layout.wasReduced, layout.wasPadded)
    }

    private fun frameIdAt(project: Project, slotIndex: Int, config: ExportConfig): String {
        val start = (config.rangeStart ?: 0).coerceIn(0, project.frames.lastIndex)
        return project.frames[(start + slotIndex).coerceAtMost(project.frames.lastIndex)].id
    }

    private suspend fun exportGif(project: Project, config: ExportConfig, output: File, onProgress: (Float) -> Unit): ExportResult {
        val (wantedWidth, wantedHeight) = config.requestedSize(project.width, project.height)
        val size = GifSizes.fit(wantedWidth, wantedHeight)
        val width = size.width
        val height = size.height
        // A looping GIF repeats by itself; only a GIF that stops needs its repeats written out.
        val plan = ExportPlan.of(project.frames, project.fps, config)
        val singlePass = ExportPlan.of(project.frames, project.fps, config.copy(repeatCount = 1))
        val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        val flattener = FrameFlattener(store)
        val background = backgroundImage(project)
        val scale = min(width.toFloat() / project.width, height.toFloat() / project.height)
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
        return ExportResult(output, width, height, written, size.wasReduced)
    }

    private companion object {
        const val STREAM_BUFFER = 64 * 1024
    }
}

/** Draws one frame of a video: the fill, which leaves the bars, and the picture where [layout] says it goes. */
internal fun drawVideoFrame(
    frame: Bitmap,
    canvas: Canvas,
    flattener: FrameFlattener,
    project: Project,
    frameId: String,
    layout: VideoLayout,
    fill: Int,
    background: Bitmap?,
    options: FlattenOptions,
) {
    frame.eraseColor(fill)
    val checkpoint = canvas.save()
    canvas.translate(layout.offsetX.toFloat(), layout.offsetY.toFloat())
    flattener.flatten(project, frameId, canvas, layout.pictureScale, background, options)
    canvas.restoreToCount(checkpoint)
}

/**
 * What a video's frames start from, which also colours any bars. Video has no transparency, so anything see-through has
 * to sit on something solid.
 *
 * Leaving the background out must make a difference, so the background's own colour will not do, but it still decides
 * between plain white and black. Light lines drawn on dark paper would vanish on white, and the point of the option
 * is to get the drawing by itself.
 */
internal fun videoFillColor(background: Background, includeBackground: Boolean): Int {
    val color =
        when (background) {
            is Background.Color -> background.argb or OPAQUE
            is Background.Paper -> BackgroundPainter.paperColor(background.kind)
            is Background.Image, Background.Transparent -> Color.WHITE
        }
    return when {
        includeBackground -> color
        isDark(color) -> Color.BLACK
        else -> Color.WHITE
    }
}

private fun isDark(argb: Int): Boolean {
    val red = (argb shr 16) and 0xFF
    val green = (argb shr 8) and 0xFF
    val blue = argb and 0xFF
    return (RED_WEIGHT * red + GREEN_WEIGHT * green + BLUE_WEIGHT * blue) / WEIGHTS < MID_GREY
}

private const val OPAQUE = 0xFF000000.toInt()
private const val RED_WEIGHT = 299
private const val GREEN_WEIGHT = 587
private const val BLUE_WEIGHT = 114
private const val WEIGHTS = 1000
private const val MID_GREY = 128
