package org.kineograph.engine.brush

/** The stamp a brush paints with. */
public enum class TipShape {
    /** A disc, optionally squashed by [BrushSpec.roundness] into a chisel. */
    ROUND,

    /** A disc broken up by paper-like grain. */
    GRAIN,

    /** A square, used by pixel brushes. */
    SQUARE,
}

/** How a finished stroke combines with the layer. */
public enum class StrokeBlend { NORMAL, ERASE }

/**
 * Everything that defines how a brush behaves. Sizes are in canvas pixels.
 *
 * [flow] is how much paint one stamp lays down, so overlapping stamps build up within a stroke.
 * [opacity] caps the finished stroke as a whole, so a stroke crossing itself does not get darker.
 */
public data class BrushSpec(
    val id: String,
    val tip: TipShape = TipShape.ROUND,
    val sizePx: Float,
    /** Distance between stamps, as a fraction of the stamp's diameter. */
    val spacing: Float = 0.1f,
    val opacity: Float = 1f,
    val flow: Float = 1f,
    /** 1 is a crisp edge, 0 fades all the way from the centre. */
    val hardness: Float = 1f,
    /** 1 is a circle; smaller values squash the tip into a chisel. */
    val roundness: Float = 1f,
    val tipAngleDegrees: Float = 0f,
    val sizeCurve: PressureCurve = PressureCurve.ramp(0.35f),
    val flowCurve: PressureCurve = PressureCurve.FLAT,
    /** How much stylus tilt widens and lightens the stroke, 0..1. */
    val tiltShading: Float = 0f,
    /** Random size change per stamp, as a fraction of the size. */
    val sizeJitter: Float = 0f,
    /** Random rotation per stamp, in degrees either way. */
    val angleJitterDegrees: Float = 0f,
    /** Random offset per stamp, as a fraction of the diameter. */
    val scatter: Float = 0f,
    /** Strength of the paper grain in the tip, 0..1. */
    val grain: Float = 0f,
    val antiAlias: Boolean = true,
    val blend: StrokeBlend = StrokeBlend.NORMAL,
) {
    public val isEraser: Boolean get() = blend == StrokeBlend.ERASE

    /** True when stamps never need a rotation, which allows a faster way to draw them. */
    public val isRotationInvariant: Boolean
        get() = roundness >= 1f && angleJitterDegrees == 0f && tiltShading == 0f
}

/** The brushes that ship with the app. Display names come from string resources, looked up by id. */
public object BrushPresets {
    public val PEN: BrushSpec =
        BrushSpec(
            id = "pen",
            sizePx = 6f,
            spacing = 0.08f,
            hardness = 1f,
            sizeCurve = PressureCurve.ramp(0.25f),
        )

    public val PENCIL: BrushSpec =
        BrushSpec(
            id = "pencil",
            tip = TipShape.GRAIN,
            sizePx = 5f,
            spacing = 0.14f,
            opacity = 0.9f,
            flow = 0.55f,
            hardness = 0.75f,
            sizeCurve = PressureCurve.ramp(0.65f),
            flowCurve = PressureCurve.ramp(0.2f),
            tiltShading = 0.8f,
            scatter = 0.04f,
            grain = 0.7f,
        )

    public val MARKER: BrushSpec =
        BrushSpec(
            id = "marker",
            sizePx = 18f,
            spacing = 0.05f,
            opacity = 0.75f,
            hardness = 0.92f,
            roundness = 0.45f,
            tipAngleDegrees = -40f,
            sizeCurve = PressureCurve.ramp(0.85f),
        )

    public val ERASER: BrushSpec =
        BrushSpec(
            id = "eraser",
            sizePx = 24f,
            spacing = 0.08f,
            hardness = 0.9f,
            sizeCurve = PressureCurve.ramp(0.6f),
            blend = StrokeBlend.ERASE,
        )

    /** Drawing brushes, in the order the brush picker shows them. */
    public val DRAWING: List<BrushSpec> = listOf(PEN, PENCIL, MARKER)
}
