package org.kineograph.engine.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import org.kineograph.engine.brush.BoundsAccumulator
import org.kineograph.engine.brush.BrushSpec
import org.kineograph.engine.brush.StampBuffer
import org.kineograph.engine.brush.StampPainter
import org.kineograph.engine.brush.StrokeEngine
import org.kineograph.engine.brush.StrokeMerge
import org.kineograph.engine.brush.StrokeRecording
import org.kineograph.engine.brush.TipCache
import org.kineograph.model.IntRect
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

/**
 * Works a layer out again from its strokes, through the same engine, stamps and merge that drew them, onto [layer]. That covers [region] of
 * the canvas, and strokes land in the order they are given.
 *
 * Tips come from a [TipCache] of its own by default, because the session's is shared with the renderer and has no limit. Holds two bitmaps
 * of [region]'s size, so this belongs off the main and engine threads. Used by one thread.
 */
public class StrokeReplay(public val region: IntRect, tips: TipCache = TipCache()) {
    /** What the strokes so far come to. */
    public val layer: Bitmap = Bitmap.createBitmap(region.width, region.height, Bitmap.Config.ARGB_8888)

    private val buffer = Bitmap.createBitmap(region.width, region.height, Bitmap.Config.ARGB_8888)
    private val layerCanvas = Canvas(layer)
    private val bufferCanvas = Canvas(buffer)
    private val painter = StampPainter(tips)
    private val merge = StrokeMerge()
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val stamps = StampBuffer(STAMPS_PER_PIECE)
    private val bounds = BoundsAccumulator()

    /** Lands one stroke as it was read from a file. */
    public fun replay(stroke: VectorStroke) {
        replay(
            stroke.spec,
            stroke.seed,
            stroke.colorArgb,
            stroke.samples,
            stroke.count,
            stroke.stride,
            stroke.clip,
            stroke.offsetX,
            stroke.offsetY,
        )
    }

    /**
     * Lands one stroke from its samples, which may be samples no stroke ever had, such as a mix of two strokes. Its stamps go [offsetX] and
     * [offsetY] further than the samples say, to where they are on the canvas. They only reach pixels inside [clip], in canvas coordinates,
     * which is as far as they could reach when the stroke was drawn, if it ran past the surface.
     */
    public fun replay(
        spec: BrushSpec,
        seed: Long,
        colorArgb: Int,
        samples: FloatArray,
        count: Int,
        stride: Int,
        clip: IntRect? = null,
        offsetX: Int = 0,
        offsetY: Int = 0,
    ) {
        if (count <= 0) return
        val checkpoint = bufferCanvas.save()
        bufferCanvas.translate(-region.left.toFloat(), -region.top.toFloat())
        if (clip != null) bufferCanvas.clipRect(clip.left, clip.top, clip.right, clip.bottom)
        bufferCanvas.translate(offsetX.toFloat(), offsetY.toFloat())
        bounds.clear()
        stamps.clear()
        val engine = StrokeEngine(spec, seed)
        val hasTilt = stride == StrokeRecording.STRIDE_WITH_TILT
        for (i in 0 until count) {
            val at = i * stride
            val tilt = if (hasTilt) samples[at + 3] else 0f
            val orientation = if (hasTilt) samples[at + 4] else 0f
            if (i == 0) {
                engine.begin(samples[at], samples[at + 1], samples[at + 2], tilt, orientation, stamps)
            } else {
                engine.add(samples[at], samples[at + 1], samples[at + 2], tilt, orientation, stamps)
            }
            if (stamps.count >= STAMPS_PER_PIECE) paintStamps(spec, colorArgb)
        }
        engine.finish(stamps)
        paintStamps(spec, colorArgb)
        bufferCanvas.restoreToCount(checkpoint)
        land(spec, clip, offsetX, offsetY)
    }

    /** Frees the bitmaps. Nothing can be replayed after this, and [layer] has gone too. */
    public fun release() {
        layer.recycle()
        buffer.recycle()
    }

    private fun paintStamps(spec: BrushSpec, colorArgb: Int) {
        if (stamps.count == 0) return
        val run = stamps.snapshot()
        stamps.clear()
        painter.paint(bufferCanvas, spec, colorArgb, run)
        bounds.add(run)
    }

    private fun land(spec: BrushSpec, clip: IntRect?, offsetX: Int, offsetY: Int) {
        if (bounds.isEmpty) return
        val rounded = bounds.roundedOut()
        var reach = IntRect(rounded[0], rounded[1], rounded[2], rounded[3]).translated(offsetX, offsetY).intersect(region)
        if (clip != null) reach = reach.intersect(clip)
        if (reach.isEmpty) return
        val rect = reach.translated(-region.left, -region.top)
        merge.merge(layerCanvas, buffer, spec, rect)
        bufferCanvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), clearPaint)
    }

    private companion object {
        const val STAMPS_PER_PIECE = 4096
    }
}

/**
 * How far a replayed layer is from a cel's real pixels, over every pixel that has anything in either. A pixel's difference is the largest
 * difference of its four channels, premultiplied, so the colour under a nearly clear pixel, which nobody can see, cannot count. Each number
 * is in steps of 1/255.
 */
public class ReplayDifference(public val pixels: Long, public val mean: Double, public val p999: Int, public val max: Int) {
    /** Whether this is close enough to say the strokes describe the cel. */
    public val isWithinTolerance: Boolean get() = mean <= TOLERANCE_MEAN && p999 <= TOLERANCE_P999 && max <= TOLERANCE_MAX

    override fun toString(): String = "mean %.3f, p99.9 %d, max %d over %d pixels".format(mean, p999, max, pixels)

    public companion object {
        /*
         * The tolerance. Saved strokes replay exactly, so all that is left is rounding: where a layer sits changes a pixel by 1 at the
         * most, and so does going through a file. These leave room for that to add up. docs/decisions.md has the numbers.
         */
        public const val TOLERANCE_MEAN: Double = 0.25
        public const val TOLERANCE_P999: Int = 2
        public const val TOLERANCE_MAX: Int = 4

        /**
         * Compares a cel at ([celLeft], [celTop]) with a layer at ([layerLeft], [layerTop]). Anything outside either picture counts as
         * empty.
         */
        public fun between(cel: Bitmap, celLeft: Int, celTop: Int, layer: Bitmap, layerLeft: Int, layerTop: Int): ReplayDifference {
            val a = premultiplied(cel)
            val b = premultiplied(layer)
            val histogram = LongArray(HISTOGRAM_SIZE)
            val left = minOf(celLeft, layerLeft)
            val top = minOf(celTop, layerTop)
            val right = max(celLeft + cel.width, layerLeft + layer.width)
            val bottom = max(celTop + cel.height, layerTop + layer.height)
            var touched = 0L
            var total = 0L
            for (y in top until bottom) {
                for (x in left until right) {
                    val at = offsetIn(cel, x - celLeft, y - celTop)
                    val bt = offsetIn(layer, x - layerLeft, y - layerTop)
                    if (at < 0 && bt < 0) continue
                    var largest = 0
                    var any = false
                    for (channel in 0 until Int.SIZE_BYTES) {
                        val first = if (at < 0) 0 else a[at + channel].toInt() and 0xFF
                        val second = if (bt < 0) 0 else b[bt + channel].toInt() and 0xFF
                        if (first != 0 || second != 0) any = true
                        largest = max(largest, abs(first - second))
                    }
                    if (any) {
                        touched++
                        total += largest
                        histogram[largest]++
                    }
                }
            }
            return summarize(histogram, touched, total)
        }

        private fun summarize(histogram: LongArray, touched: Long, total: Long): ReplayDifference {
            if (touched == 0L) return ReplayDifference(0L, 0.0, 0, 0)
            var seen = 0L
            var percentile = -1
            var largest = 0
            for (value in histogram.indices) {
                if (histogram[value] == 0L) continue
                seen += histogram[value]
                largest = value
                if (percentile < 0 && seen * PERCENTILE_BASE >= touched * PERCENTILE_WANTED) percentile = value
            }
            return ReplayDifference(touched, total.toDouble() / touched, percentile, largest)
        }

        private fun offsetIn(image: Bitmap, x: Int, y: Int): Int =
            if (x in 0 until image.width && y in 0 until image.height) y * image.rowBytes + x * Int.SIZE_BYTES else -1

        /**
         * The bytes as stored. Reading with getPixels would undo the premultiplying, and nearly clear pixels would then count for far too
         * much.
         */
        private fun premultiplied(image: Bitmap): ByteArray {
            val pixels = ByteBuffer.allocate(image.rowBytes * image.height)
            image.copyPixelsToBuffer(pixels)
            return pixels.array()
        }

        private const val HISTOGRAM_SIZE = 256
        private const val PERCENTILE_BASE = 1000L
        private const val PERCENTILE_WANTED = 999L
    }
}
