package org.kineograph.feature.editor

import org.kineograph.engine.brush.BrushPresets
import org.kineograph.engine.brush.BrushSpec
import org.kineograph.feature.editor.canvas.CanvasTool
import org.kineograph.feature.editor.canvas.CanvasToolConfig
import kotlin.math.ln
import kotlin.math.pow

/** The tools as the user has set them. Saved in the ViewModel, so it survives everything but leaving the project. */
internal data class ToolState(
    val tool: CanvasTool = CanvasTool.BRUSH,
    val brushId: String = BrushPresets.PEN.id,
    val sizes: Map<String, Float> = emptyMap(),
    val opacities: Map<String, Float> = emptyMap(),
    val colorArgb: Int = 0xFF1B1C1A.toInt(),
    val recentColors: List<Int> = emptyList(),
    val smoothing: Int = 25,
    val simulateFingerPressure: Boolean = true,
    val fillTolerance: Int = 32,
    val fillGapClose: Int = 4,
    val fillSampleAllLayers: Boolean = false,
) {
    private val activeId: String get() = if (tool == CanvasTool.ERASER) BrushPresets.ERASER.id else brushId

    private fun preset(id: String): BrushSpec =
        BrushPresets.DRAWING.firstOrNull { it.id == id } ?: if (id == BrushPresets.ERASER.id) BrushPresets.ERASER else BrushPresets.PEN

    /** The active brush's size in canvas pixels. */
    val activeSize: Float get() = sizes[activeId] ?: preset(activeId).sizePx

    /** The active brush's opacity, 0..1. */
    val activeOpacity: Float get() = opacities[activeId] ?: preset(activeId).opacity

    fun withActiveSize(size: Float): ToolState = copy(sizes = sizes + (activeId to size.coerceIn(MIN_SIZE, MAX_SIZE)))

    fun withActiveOpacity(opacity: Float): ToolState = copy(opacities = opacities + (activeId to opacity.coerceIn(0.01f, 1f)))

    /** Scales every default brush size to suit the canvas, so a pen is not a blob on pixel art or a hair on 4K. */
    fun scaledForCanvas(width: Int, height: Int): ToolState {
        val factor = minOf(width, height) / REFERENCE_CANVAS_EDGE
        val all = BrushPresets.DRAWING + BrushPresets.ERASER
        return copy(sizes = all.associate { it.id to (it.sizePx * factor).coerceIn(MIN_SIZE, MAX_SIZE) })
    }

    fun toCanvasConfig(): CanvasToolConfig =
        CanvasToolConfig(
            tool = tool,
            brush = spec(brushId),
            eraser = spec(BrushPresets.ERASER.id),
            colorArgb = colorArgb,
            smoothing = smoothing,
            simulateFingerPressure = simulateFingerPressure,
            fillColorArgb = withOpacity(colorArgb, opacities[FILL_ID] ?: 1f),
            fillTolerance = fillTolerance,
            fillGapClose = fillGapClose,
            fillSampleAllLayers = fillSampleAllLayers,
        )

    private fun spec(id: String): BrushSpec {
        val base = preset(id)
        return base.copy(sizePx = sizes[id] ?: base.sizePx, opacity = opacities[id] ?: base.opacity)
    }

    private fun withOpacity(argb: Int, opacity: Float): Int =
        (argb and 0xFFFFFF) or ((opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24)

    /** Adds a colour to the recents, newest first. */
    fun withRecent(argb: Int): ToolState = copy(recentColors = (listOf(argb) + recentColors.filterNot { it == argb }).take(MAX_RECENT))

    companion object {
        const val MIN_SIZE = 1f
        const val MAX_SIZE = 400f
        private const val MAX_RECENT = 12
        private const val REFERENCE_CANVAS_EDGE = 720f
        private const val FILL_ID = "fill"

        /** Size sliders are logarithmic, since the difference between 2 and 4 pixels matters as much as 200 and 400. */
        fun sliderToSize(position: Float): Float = MIN_SIZE * (MAX_SIZE / MIN_SIZE).pow(position.coerceIn(0f, 1f))

        fun sizeToSlider(size: Float): Float = (ln(size.coerceIn(MIN_SIZE, MAX_SIZE) / MIN_SIZE) / ln(MAX_SIZE / MIN_SIZE))
    }
}
