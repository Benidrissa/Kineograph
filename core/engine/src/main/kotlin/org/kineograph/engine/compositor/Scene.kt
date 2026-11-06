package org.kineograph.engine.compositor

import android.graphics.Bitmap
import android.graphics.Path
import org.kineograph.engine.selection.SelectionTransform
import org.kineograph.model.Background
import org.kineograph.model.BlendMode
import org.kineograph.model.GridSettings

/** One image to draw at a position in canvas coordinates. */
public class LayerDraw(
    public val bitmap: Bitmap,
    public val left: Int,
    public val top: Int,
    public val opacity: Float,
    public val blendMode: BlendMode,
) {
    /** Whether any of this image lies past the canvas edge. */
    public fun overflows(canvasWidth: Int, canvasHeight: Int): Boolean =
        left < 0 || top < 0 || left + bitmap.width > canvasWidth || top + bitmap.height > canvasHeight
}

/** An onion-skin ghost: a flattened neighbouring frame, stretched over the canvas. */
public class GhostDraw(public val bitmap: Bitmap, public val opacity: Float, public val tint: Int?)

/** The stroke being drawn, kept apart from the layer until it is finished. */
public class WetDraw(
    public val buffer: Bitmap,
    public val left: Int,
    public val top: Int,
    public val opacity: Float,
    public val erase: Boolean,
)

/** Pixels lifted by the lasso. [source] in the transform is where [bitmap] came from, in canvas coordinates. */
public class FloatingDraw(public val bitmap: Bitmap, public val outline: Path, public val transform: SelectionTransform)

/**
 * An immutable description of what the canvas shows, which the renderers draw. The session publishes a
 * fresh one after each change, so a renderer on another thread always sees a consistent set of layers.
 */
public class Scene(
    public val canvasWidth: Int,
    public val canvasHeight: Int,
    public val background: Background,
    public val backgroundImage: Bitmap?,
    public val ghosts: List<GhostDraw>,
    public val below: List<LayerDraw>,
    public val active: LayerDraw?,
    public val activeOverflows: Boolean,
    public val above: List<LayerDraw>,
    public val wet: WetDraw?,
    public val floating: FloatingDraw?,
    public val grid: GridSettings,
    public val generation: Long,
)
