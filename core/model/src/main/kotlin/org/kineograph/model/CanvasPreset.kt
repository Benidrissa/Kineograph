package org.kineograph.model

/** Canvas sizes offered in the New Project sheet. The UI supplies the translated labels by [id]. */
public enum class CanvasPreset(
    public val id: String,
    public val width: Int,
    public val height: Int,
    public val requiresEncoderCheck: Boolean = false,
) {
    HD("hd", 1280, 720),
    FULL_HD("full_hd", 1920, 1080),
    VERTICAL("vertical", 1080, 1920),
    SQUARE("square", 1080, 1080),
    FOUR_THREE("four_three", 1024, 768),
    UHD_4K("uhd_4k", 3840, 2160, requiresEncoderCheck = true),
    PIXEL_64("pixel_64", 64, 64),
    PIXEL_128("pixel_128", 128, 128),
    PIXEL_256("pixel_256", 256, 256),
    ;

    public companion object {
        public val DEFAULT: CanvasPreset = HD

        /** Largest custom edge. A bigger canvas is allowed by nothing but this safety limit on bitmap size. */
        public const val MAX_CUSTOM_EDGE: Int = 8192
    }
}
