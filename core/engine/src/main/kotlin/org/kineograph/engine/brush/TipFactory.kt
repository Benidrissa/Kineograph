package org.kineograph.engine.brush

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One tip image. [diameter] is the size of the shape itself; the image has a margin around it. */
public class Tip(public val bitmap: Bitmap, public val diameter: Float) {
    /** How much larger the image is than the shape, to scale the destination rectangle by. */
    public val imageToShape: Float = bitmap.width / diameter
}

/**
 * Builds the alpha masks stamps are made of, and caches them.
 *
 * Tips come in sizes about 19% apart. A stamp uses the nearest size and scales it slightly, which keeps
 * edges clean: scaling one big image far down with bilinear filtering would alias badly on thin lines.
 */
public object TipFactory {
    /**
     * Computes the alpha mask of a tip, [size] pixels square. Pure, so it can be tested without a device.
     *
     * A tip whose narrow side is only a few pixels is measured by true coverage, because sampling just the centre of each
     * pixel misses a shape that small altogether: a 1.2 px disc falls between the pixel centres and comes out blank.
     */
    public fun alphaMask(shape: TipShape, size: Int, diameter: Float, hardness: Float, roundness: Float, grain: Float): ByteArray {
        val mask = ByteArray(size * size)
        val center = size / 2f
        val radius = maxOf(diameter / 2f, 0.5f)
        val squash = roundness.coerceIn(0.05f, 1f)
        val hardnessClamped = hardness.coerceIn(0f, 1f)
        val isThin = radius * 2f * squash < THIN_TIP_PX
        // For sampling at pixel centres, the soft zone is at least one pixel wide, so even a hard edge is anti-aliased.
        // A band that narrow is centred on the true edge; if it sat entirely inside, hard brushes would come out too thin.
        val isHardEdge = (1f - hardnessClamped) * radius <= 1f
        val edgeRadius = if (isHardEdge) radius + 0.5f else radius
        val softZone = maxOf(1f / edgeRadius, 1f - hardnessClamped).coerceAtMost(1f)
        for (py in 0 until size) {
            for (px in 0 until size) {
                val coverage =
                    when {
                        shape == TipShape.SQUARE -> {
                            1f
                        }

                        isThin -> {
                            supersampledCoverage(px, py, center, radius, squash, 1f - hardnessClamped)
                        }

                        else -> {
                            val dx = px + 0.5f - center
                            val dy = (py + 0.5f - center) / squash
                            edgeProfile(sqrt(dx * dx + dy * dy) / edgeRadius, softZone)
                        }
                    }
                val textured = if (grain > 0f && coverage > 0f) coverage * (1f - grain * (1f - noise(px, py))) else coverage
                mask[py * size + px] = (textured * 255f + 0.5f).toInt().toByte()
            }
        }
        return mask
    }

    /** How much of one pixel the shape covers, by averaging a grid of points across it. */
    private fun supersampledCoverage(px: Int, py: Int, center: Float, radius: Float, squash: Float, softZone: Float): Float {
        var total = 0f
        for (j in 0 until SUBSAMPLES) {
            val dy = (py + (j + 0.5f) / SUBSAMPLES - center) / squash
            for (i in 0 until SUBSAMPLES) {
                val dx = px + (i + 0.5f) / SUBSAMPLES - center
                total += edgeProfile(sqrt(dx * dx + dy * dy) / radius, softZone)
            }
        }
        return total / (SUBSAMPLES * SUBSAMPLES)
    }

    /** Opacity at a distance from the centre, where 1 is the edge of the shape. */
    private fun edgeProfile(distance: Float, softZone: Float): Float {
        if (distance >= 1f) return 0f
        if (softZone <= 0f) return 1f
        val solidUntil = 1f - softZone
        if (distance <= solidUntil) return 1f
        val t = (1f - distance) / softZone
        return t * t * (3f - 2f * t)
    }

    private const val THIN_TIP_PX = 8f
    private const val SUBSAMPLES = 8

    /** Stable value noise in 0..1, mixing fine speckle with a coarser tooth like paper's. */
    private fun noise(x: Int, y: Int): Float {
        val fine = hash(x, y)
        val coarse = hash(x shr 1, y shr 1)
        return fine * 0.55f + coarse * 0.45f
    }

    private fun hash(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }
}

/** Thread-safe cache of [TipSet]s, shared between the engine thread and the renderer. */
public class TipCache {
    private val sets = ConcurrentHashMap<TipKey, TipSet>()

    public fun tipsFor(spec: BrushSpec): TipSet {
        val key = TipKey(spec.tip, spec.hardness, spec.roundness, spec.grain, spec.antiAlias)
        return sets.getOrPut(key) { TipSet(key) }
    }

    /** Drops every tip, for when memory runs low. Tips are rebuilt on demand. */
    public fun clear() {
        sets.clear()
    }

    internal data class TipKey(val shape: TipShape, val hardness: Float, val roundness: Float, val grain: Float, val antiAlias: Boolean)

    /** All the sizes of one tip design, built the first time each size is needed. */
    public class TipSet internal constructor(private val key: TipKey) {
        private val tips = AtomicReferenceArray<Tip?>(BUCKETS)

        public fun forDiameter(diameter: Float): Tip {
            val index = bucketFor(diameter)
            tips.get(index)?.let { return it }
            val built = build(BASE.pow(index))
            tips.compareAndSet(index, null, built)
            return tips.get(index) ?: built
        }

        private fun build(diameter: Float): Tip {
            val size = ceil(diameter).toInt() + MARGIN_PX
            val mask = TipFactory.alphaMask(key.shape, size, diameter, key.hardness, key.roundness, key.grain)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(padded(mask, size, bitmap.rowBytes)))
            return Tip(bitmap, diameter)
        }

        private fun padded(mask: ByteArray, size: Int, rowBytes: Int): ByteArray {
            if (rowBytes == size) return mask
            val out = ByteArray(rowBytes * size)
            for (row in 0 until size) mask.copyInto(out, row * rowBytes, row * size, (row + 1) * size)
            return out
        }

        private companion object {
            const val BASE = 1.19f
            const val BUCKETS = 48
            const val MARGIN_PX = 2
            val LOG_BASE = ln(BASE)

            fun bucketFor(diameter: Float): Int = (ln(maxOf(diameter, 1f)) / LOG_BASE).roundToInt().coerceIn(0, BUCKETS - 1)
        }
    }
}
