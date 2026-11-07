package org.kineograph.engine.vector

import org.kineograph.engine.brush.BrushSpec
import org.kineograph.model.Cel
import org.kineograph.model.IntRect
import org.kineograph.model.Project

/**
 * One finished stroke as the engine was fed it: enough to draw it again, through [StrokeReplay], to the same pixels. [clip] is the area the
 * stroke could reach when it was drawn, in canvas coordinates. Never changed once made, so logs and undo steps can share it.
 */
public class RecordedStroke(
    public val spec: BrushSpec,
    public val seed: Long,
    public val colorArgb: Int,
    public val samples: FloatArray,
    public val count: Int,
    public val stride: Int,
    public val clip: IntRect,
) {
    /** Roughly what this holds in memory. */
    public val bytes: Long get() = samples.size.toLong() * Float.SIZE_BYTES + OVERHEAD_BYTES

    private companion object {
        const val OVERHEAD_BYTES = 160L
    }
}

/**
 * The strokes behind one drawing, oldest first, as a chain that never changes: adding a stroke makes a new log that shares everything
 * before it, so every saved version of a drawing can keep its own log for the cost of one link.
 *
 * A log can be wrong, for example after a fill or a lasso, which change pixels without strokes. Whoever trusts it must first draw it again
 * and compare with the drawing, as [InBetweenMaker] does.
 */
public class StrokeLog private constructor(
    public val last: RecordedStroke?,
    public val previous: StrokeLog?,
    public val size: Int,
) {
    public fun plus(stroke: RecordedStroke): StrokeLog = StrokeLog(stroke, this, size + 1)

    /** The strokes, oldest first. */
    public fun toList(): List<RecordedStroke> {
        val strokes = arrayOfNulls<RecordedStroke>(size)
        var link: StrokeLog? = this
        var index = size - 1
        while (link != null && index >= 0) {
            strokes[index] = link.last
            link = link.previous
            index--
        }
        return strokes.filterNotNull()
    }

    public companion object {
        /** No strokes: what an empty drawing is made of. */
        public val EMPTY: StrokeLog = StrokeLog(null, null, 0)

        /** A log of [strokes], oldest first. */
        public fun of(strokes: List<RecordedStroke>): StrokeLog = strokes.fold(EMPTY) { log, stroke -> log.plus(stroke) }
    }
}

/** A frame's drawing on one layer and the next frame's, from [project], each with its strokes. A null log means they are not known. */
public class StrokeSource(public val project: Project, public val cels: List<Cel?>, public val logs: List<StrokeLog?>)
