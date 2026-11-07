package org.kineograph.engine.brush

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import org.kineograph.model.IntRect

/**
 * Lands a finished stroke on its layer. There is one of these so that anything that works a drawing out again from its strokes
 * merges exactly as drawing did. Each thread uses its own instance, because the [Paint] here is not thread-safe.
 */
public class StrokeMerge {
    private val paint = Paint()
    private val eraseMode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

    /**
     * Draws [buffer], the stroke's stamps at full strength, onto [target] at the stroke's opacity. An eraser's stamps cut paint away
     * instead. Only [rect] is touched. [buffer] has the same size and position as what [target] draws on.
     */
    public fun merge(target: Canvas, buffer: Bitmap, spec: BrushSpec, rect: IntRect) {
        paint.alpha = (spec.opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        paint.xfermode = if (spec.isEraser) eraseMode else null
        val checkpoint = target.save()
        target.clipRect(rect.left, rect.top, rect.right, rect.bottom)
        target.drawBitmap(buffer, 0f, 0f, paint)
        target.restoreToCount(checkpoint)
    }
}
