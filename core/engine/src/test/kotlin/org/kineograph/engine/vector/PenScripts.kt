package org.kineograph.engine.vector

import org.kineograph.engine.brush.StampRun
import org.kineograph.engine.brush.StrokeBuilder
import org.kineograph.engine.brush.StrokeOptions
import java.util.Random
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/** One input sample, as the view hands it to a [StrokeBuilder]. */
internal class PenSample(val x: Float, val y: Float, val pressure: Float, val tilt: Float, val orientation: Float, val timeMs: Long)

/** A stroke to draw. [zoom] only turns canvas positions into the screen positions that fake finger pressure goes by. */
internal class PenScript(
    val name: String,
    val samples: List<PenSample>,
    val options: StrokeOptions = StrokeOptions(),
    val isFinger: Boolean = false,
    val zoom: Float = 1f,
) {
    /** Feeds the builder as a view would, handing over finished stamps every few samples, as events arrive. */
    fun play(builder: StrokeBuilder, onStamps: (StampRun) -> Unit = {}) {
        val first = samples.first()
        builder.begin(first.x, first.y, first.x * zoom, first.y * zoom, first.pressure, first.tilt, first.orientation, first.timeMs)
        for (i in 1 until samples.size) {
            val s = samples[i]
            builder.add(s.x, s.y, s.x * zoom, s.y * zoom, s.pressure, s.tilt, s.orientation, s.timeMs)
            if (i % SAMPLES_PER_EVENT == 0) drain(builder, onStamps)
        }
        builder.finish()
        drain(builder, onStamps)
    }

    private fun drain(builder: StrokeBuilder, onStamps: (StampRun) -> Unit) {
        if (builder.stamps.count == 0) return
        onStamps(builder.stamps.snapshot())
        builder.stamps.clear()
    }

    private companion object {
        const val SAMPLES_PER_EVENT = 4
    }
}

/**
 * Scripted strokes on a canvas of about 480 by 320. They are synthetic: a smooth path, plus an assumed hand tremor of about ten hertz, plus
 * assumed digitizer noise. No real pen has been measured, so what they say about real bytes and real error stops at those assumptions.
 */
internal object PenScripts {
    private val STYLUS_APP_DEFAULTS = StrokeOptions(smoothing = 25, stringLengthPx = 24f, pressureSmoothing = 20)
    private val FINGER_APP_DEFAULTS = StrokeOptions(smoothing = 25, stringLengthPx = 24f, simulatePressure = true, fastSpeedPxPerMs = 4f)

    /** Arcs and turns at a normal drawing speed, about 700 px/s. */
    fun curve(seed: Long = 1L, options: StrokeOptions = StrokeOptions()): PenScript =
        hand("curve", seed, hz = 240, seconds = 2.5, options) { t ->
            floatArrayOf(
                240f + 190f * sin(2f * PI.toFloat() * 1.25f * t + 0.3f),
                160f + 120f * sin(2f * PI.toFloat() * 2f * t),
            )
        }

    /**
     * A careful line, about 25 px/s, so a tenth of a pixel per sample. Noise is as large as the movement here, which is hard on a
     * quantizer.
     */
    fun slow(seed: Long = 2L, options: StrokeOptions = StrokeOptions()): PenScript =
        hand("slow", seed, hz = 240, seconds = 8.0, options) { t -> floatArrayOf(140f + 200f * t, 120f + 60f * t) }

    /** A fast flick: 420 px in an eighth of a second, at a lower report rate, so samples are far apart. */
    fun flick(seed: Long = 3L, options: StrokeOptions = StrokeOptions()): PenScript =
        hand("flick", seed, hz = 120, seconds = 0.125, options) { t -> floatArrayOf(30f + 420f * t * t, 270f - 200f * t) }

    /** Back and forth over itself, about 800 px/s, the worst case for a stroke that builds up paint. */
    fun scribble(seed: Long = 4L, options: StrokeOptions = StrokeOptions()): PenScript =
        hand("scribble", seed, hz = 240, seconds = 3.0, options) { t ->
            floatArrayOf(
                240f + 120f * sin(2f * PI.toFloat() * 3f * t),
                110f + 80f * t + 25f * sin(2f * PI.toFloat() * 11f * t),
            )
        }

    /** A tap, which is one sample. */
    fun tap(seed: Long = 5L): PenScript =
        PenScript("tap", listOf(PenSample(233.37f, 141.71f, 0.42f + seed % 3 * 0.1f, 0.61f, 0.93f, 1_000L)))

    /** Moves, stops dead for a second while the pressure changes, and moves on. */
    fun hold(seed: Long = 6L, options: StrokeOptions = StrokeOptions()): PenScript {
        val moving = hand("hold", seed, hz = 240, seconds = 0.8, options) { t -> floatArrayOf(100f + 140f * t, 100f + 40f * t) }.samples
        val stop = moving.last()
        val held = List(240) { i ->
            PenSample(
                stop.x,
                stop.y,
                0.3f + 0.6f * i / 240f,
                stop.tilt,
                stop.orientation,
                stop.timeMs + 4L * (i + 1),
            )
        }
        val on =
            hand("hold", seed + 1, hz = 240, seconds = 0.8, options) { t -> floatArrayOf(stop.x + 120f * t, stop.y + 60f * t) }
                .samples
                .map { PenSample(it.x, it.y, it.pressure, it.tilt, it.orientation, it.timeMs + stop.timeMs + 964L) }
        return PenScript("hold", moving + held + on, options)
    }

    /** At the deepest zoom, where a whole stroke fits in six canvas pixels and the noise is a fraction of the smallest step stored. */
    fun zoomedIn(seed: Long = 7L, options: StrokeOptions = StrokeOptions()): PenScript {
        val zoom = 64f
        return hand("zoomed", seed, hz = 240, seconds = 1.5, options, noisePx = 0.1f / zoom, tremorPx = 0.4f / zoom, zoom = zoom) { t ->
            floatArrayOf(240f + 3f * sin(2f * PI.toFloat() * t), 160f + 3f * sin(2f * PI.toFloat() * 1.5f * t))
        }
    }

    /**
     * A finger as the app takes one: fake pressure, the default stabilizer, whole screen pixels at 60 Hz, at a zoom that is not a whole
     * number.
     */
    fun finger(seed: Long = 8L): PenScript {
        val zoom = 1.37f
        val raw =
            hand("finger", seed, hz = 60, seconds = 1.2, FINGER_APP_DEFAULTS, noisePx = 0f, tremorPx = 0.5f, zoom = zoom) { t ->
                floatArrayOf(80f + 320f * t, 160f + 90f * sin(2f * PI.toFloat() * t))
            }
        val snapped = raw.samples.map { PenSample(snap(it.x, zoom), snap(it.y, zoom), 1f, 0f, 0f, it.timeMs) }
        return PenScript("finger", snapped, FINGER_APP_DEFAULTS, isFinger = true, zoom = zoom)
    }

    /** A stylus with the app's default stabilizer and pressure smoothing, which is what most real strokes go through. */
    fun stylusDefaults(seed: Long = 9L): PenScript =
        hand("stylus", seed, hz = 240, seconds = 2.0, STYLUS_APP_DEFAULTS) { t ->
            floatArrayOf(90f + 300f * t, 170f + 100f * sin(2f * PI.toFloat() * 1.5f * t))
        }

    /** Half a minute of slow writing in one stroke, which gives any drift along the path time to add up. */
    fun long(seed: Long = 10L, options: StrokeOptions = StrokeOptions()): PenScript =
        hand("long", seed, hz = 240, seconds = 30.0, options) { t ->
            floatArrayOf(
                240f + 180f * sin(2f * PI.toFloat() * 3f * t),
                160f + 100f * sin(2f * PI.toFloat() * 4.1f * t + 0.7f),
            )
        }

    /** All the single strokes, with no options the caller has to think about. */
    fun all(): List<PenScript> =
        listOf(curve(), slow(), flick(), scribble(), tap(), hold(), zoomedIn(), finger(), stylusDefaults(), long())

    /** A smooth path through [path], from t = 0 to 1, with tremor and digitizer noise on top. */
    fun hand(
        name: String,
        seed: Long,
        hz: Int,
        seconds: Double,
        options: StrokeOptions = StrokeOptions(),
        noisePx: Float = 0.08f,
        tremorPx: Float = 0.25f,
        zoom: Float = 1f,
        path: (Float) -> FloatArray,
    ): PenScript {
        val random = Random(seed)
        val phaseA = random.nextFloat() * 6.28f
        val phaseB = random.nextFloat() * 6.28f
        val count = (hz * seconds).toInt().coerceAtLeast(2)
        val samples =
            List(count) { i ->
                val t = i.toFloat() / (count - 1)
                val at = path(t)
                val time = i.toFloat() / hz
                val tremorX = tremorPx * sin(2f * PI.toFloat() * TREMOR_HZ * time + phaseA)
                val tremorY = tremorPx * sin(2f * PI.toFloat() * (TREMOR_HZ + 1.7f) * time + phaseB)
                PenSample(
                    at[0] + tremorX + (random.nextGaussian() * noisePx).toFloat(),
                    at[1] + tremorY + (random.nextGaussian() * noisePx).toFloat(),
                    (
                        0.5f + 0.35f * sin(
                            2f * PI.toFloat() * 0.7f * time + phaseA,
                        ) + (random.nextGaussian() * 0.004).toFloat()
                    ).coerceIn(0f, 1f),
                    0.6f + 0.25f * sin(2f * PI.toFloat() * 0.4f * time) + (random.nextGaussian() * 0.002).toFloat(),
                    0.9f + 0.4f * sin(2f * PI.toFloat() * 0.3f * time + phaseB) + (random.nextGaussian() * 0.002).toFloat(),
                    1_000L + (time * 1000f).toLong(),
                )
            }
        return PenScript(name, samples, options, zoom = zoom)
    }

    private fun snap(canvas: Float, zoom: Float): Float = floor(canvas * zoom) / zoom

    private const val TREMOR_HZ = 9.3f
}
