package org.kineograph.engine.vector

import org.kineograph.engine.brush.StrokeRecording
import org.kineograph.model.IntRect
import java.io.ByteArrayOutputStream

/** Turns a drawing's strokes into the bytes of its vector file and back, in the format [VectorCodec] describes. */
public object StrokeFiles {
    /** The file for [strokes] of a drawing at [celBounds], or null if one of them cannot be stored. */
    public fun encode(strokes: List<RecordedStroke>, celBounds: IntRect): ByteArray? {
        val out = ByteArrayOutputStream()
        val encoder = VectorFileEncoder(out, celBounds)
        for ((index, stroke) in strokes.withIndex()) {
            // A stroke from a drawing that has since moved keeps its samples as they were, which this format cannot say.
            if (stroke.offsetX != 0 || stroke.offsetY != 0) return null
            if (!encoder.addStroke(index + 1L, recordingOf(stroke), stroke.colorArgb, stroke.clip, stroke.clip)) return null
        }
        encoder.finish()
        return out.toByteArray()
    }

    /**
     * The strokes in [bytes], for a drawing now at [celBounds], or null if the file is damaged, too large, from a newer app, for a drawing of
     * another size, or holds anything other than strokes.
     */
    public fun decode(bytes: ByteArray, celBounds: IntRect): List<RecordedStroke>? {
        val strokes = ArrayList<RecordedStroke>()
        var other = false
        try {
            VectorCodec.decodeFile(
                bytes,
                celBounds,
                object : VectorVisitor {
                    override fun onStroke(stroke: VectorStroke) {
                        strokes +=
                            RecordedStroke(
                                stroke.spec,
                                stroke.seed,
                                stroke.colorArgb,
                                stroke.samples.copyOf(stroke.count * stroke.stride),
                                stroke.count,
                                stroke.stride,
                                stroke.clip ?: stroke.bounds,
                                stroke.offsetX,
                                stroke.offsetY,
                            )
                    }

                    override fun onGap(gap: VectorGap) {
                        other = true
                    }
                },
            )
        } catch (_: VectorFormatException) {
            return null
        }
        return if (other) null else strokes
    }

    /** The same samples as a recording, which is what the encoder takes. */
    private fun recordingOf(stroke: RecordedStroke): StrokeRecording {
        val bytes = stroke.samples.size.toLong() * Float.SIZE_BYTES * 2 + RECORDING_SPARE_BYTES
        val recording = StrokeRecording(stroke.spec, stroke.seed, false, bytes)
        val hasTilt = stroke.stride == StrokeRecording.STRIDE_WITH_TILT
        for (i in 0 until stroke.count) {
            val at = i * stroke.stride
            val tilt = if (hasTilt) stroke.samples[at + 3] else 0f
            val orientation = if (hasTilt) stroke.samples[at + 4] else 0f
            recording.add(stroke.samples[at], stroke.samples[at + 1], stroke.samples[at + 2], tilt, orientation, 0L)
        }
        return recording
    }

    private const val RECORDING_SPARE_BYTES = 1024L
}
