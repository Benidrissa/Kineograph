package org.kineograph.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The app's own icons, drawn by hand on a 24-unit grid as simple strokes. Having them here rather than pulling in an
 * icon library keeps the APK small and the look our own.
 */
public object KgIcons {
    public val Back: ImageVector by lazy { icon("Back", "M15 5 8 12l7 7") }
    public val Forward: ImageVector by lazy { icon("Forward", "M9 5l7 7-7 7") }
    public val Close: ImageVector by lazy { icon("Close", "M6 6l12 12M18 6 6 18") }
    public val Check: ImageVector by lazy { icon("Check", "M5 12.5l4.5 4.5L19 7.5") }
    public val Add: ImageVector by lazy { icon("Add", "M12 5v14M5 12h14") }
    public val Minus: ImageVector by lazy { icon("Minus", "M5 12h14") }
    public val More: ImageVector by lazy { icon("More", "M12 5v.01M12 12v.01M12 19v.01") }
    public val Search: ImageVector by lazy { icon("Search", "M10.5 4a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13zM15.5 15.5 20 20") }
    public val Sort: ImageVector by lazy { icon("Sort", "M4 7h16M7 12h10M10 17h4") }
    public val Settings: ImageVector by lazy { icon("Settings", "M4 7h9M17 7h3M4 17h3M11 17h9M15 5v4M9 15v4") }
    public val Info: ImageVector by lazy { icon("Info", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 11v5M12 8v.01") }
    public val Trash: ImageVector by lazy { icon("Trash", "M5 7h14M10 7V4h4v3M7 7l1 13h8l1-13M10.5 11v5M13.5 11v5") }
    public val Restore: ImageVector by lazy { icon("Restore", "M4 12a8 8 0 1 0 2.5-5.8L4 8.5M4 4v4.5h4.5") }
    public val Rename: ImageVector by lazy { icon("Rename", "M4 20h4L19 9l-4-4L4 16zM13 7l4 4") }
    public val Duplicate: ImageVector by lazy { icon("Duplicate", "M9 9h11v11H9zM5 15H4V4h11v1") }
    public val Copy: ImageVector get() = Duplicate
    public val Paste: ImageVector by lazy { icon("Paste", "M9 5H6v15h12V5h-3M9 3h6v4H9z") }
    public val Clear: ImageVector by lazy { icon("Clear", "M5 19h14M8 15 16 7M11 4l9 9-4 4h-5l-5-5z") }
    public val Share: ImageVector by lazy {
        icon(
            "Share",
            "M18 3a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5zM6 9.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5zM18 16a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5zM8.3 10.8l7.4-4.3M8.3 13.2l7.4 4.3",
        )
    }
    public val Save: ImageVector by lazy { icon("Save", "M12 4v11M7.5 10.5 12 15l4.5-4.5M4 20h16") }
    public val Open: ImageVector by lazy { icon("Open", "M14 4h6v6M20 4l-9 9M18 14v6H4V6h6") }
    public val Export: ImageVector by lazy { icon("Export", "M12 15V4M7.5 8.5 12 4l4.5 4.5M4 14v6h16v-6") }
    public val Image: ImageVector by lazy { icon("Image", "M4 5h16v14H4zM4 16l5-5 4 4 2-2 5 5M15.5 9v.01") }
    public val Camera: ImageVector by lazy { icon("Camera", "M4 8h4l1.5-3h5L16 8h4v11H4zM12 10.5a3 3 0 1 0 0 6 3 3 0 0 0 0-6z") }

    // Tools
    public val Brush: ImageVector by lazy {
        icon("Brush", "M20 4c-5 2-9 6-11 10M9 14c-2 0-4 1-4.5 3-.3 1.3-1 2-1.5 3 3 .5 7 0 7.5-3.5.2-1-.3-2-1.5-2.5z")
    }
    public val Eraser: ImageVector by lazy { icon("Eraser", "M8 20h12M5.5 14.5l9-9.5 5.5 5.5-9.5 9.5H8.5zM10 10l5.5 5.5") }
    public val Lasso: ImageVector by lazy {
        icon("Lasso", "M12 4c-4.5 0-8 2.5-8 6s3.5 6 8 6 8-2.5 8-6-3.5-6-8-6zM8 15.5c-1.5 1-2 2.5-1 3.5s3 .5 3-1-1.5-2-2.5-1")
    }
    public val Fill: ImageVector by lazy {
        icon("Fill", "M11 4l8 8-7 7-8-8zM7.5 7.5 15 12M20 16c-1 1.5-1.5 2.5-1.5 3.3a1.5 1.5 0 0 0 3 0c0-.8-.5-1.8-1.5-3.3z")
    }
    public val Palette: ImageVector by lazy {
        icon(
            "Palette",
            "M12 3a9 9 0 0 0 0 18c1.5 0 2-1 2-2s-.5-1.5-.5-2.5 1-1.5 2-1.5h2.5c1.7 0 3-1.3 3-3C21 6.5 17 3 12 3zM8 8v.01M12.5 7v.01M16.5 9.5v.01M7 12.5v.01",
        )
    }
    public val Handle: ImageVector by lazy { icon("Handle", "M6 9h12M6 15h12") }
    public val Collapse: ImageVector by lazy { icon("Collapse", "M9 5l7 7-7 7") }

    // Timeline and playback
    public val Undo: ImageVector by lazy { icon("Undo", "M8 5 4 9l4 4M4 9h10a5.5 5.5 0 0 1 0 11H9") }
    public val Redo: ImageVector by lazy { icon("Redo", "M16 5l4 4-4 4M20 9H10a5.5 5.5 0 0 0 0 11h5") }
    public val Play: ImageVector by lazy { icon("Play", "M7 4l13 8-13 8z") }
    public val Pause: ImageVector by lazy { icon("Pause", "M8 5v14M16 5v14") }
    public val Stop: ImageVector by lazy { icon("Stop", "M6 6h12v12H6z") }
    public val Loop: ImageVector by lazy { icon("Loop", "M17 3l3 3-3 3M20 6H8a4 4 0 0 0-4 4v1M7 21l-3-3 3-3M4 18h12a4 4 0 0 0 4-4v-1") }
    public val PingPong: ImageVector by lazy { icon("PingPong", "M7 8 3 12l4 4M17 8l4 4-4 4M3 12h18") }
    public val PlayOnce: ImageVector by lazy { icon("PlayOnce", "M4 12h14M13 7l5 5-5 5M21 6v12") }
    public val Layers: ImageVector by lazy { icon("Layers", "M12 3 3 8l9 5 9-5zM3 12.5l9 5 9-5M3 16.5l9 5 9-5") }
    public val Onion: ImageVector by lazy {
        icon("Onion", "M9 4.5a7.5 7.5 0 1 0 0 15 7.5 7.5 0 0 0 0-15zM14.5 5a7.5 7.5 0 0 1 0 14M18 6.5a7.5 7.5 0 0 1 0 11")
    }
    public val Grid: ImageVector by lazy { icon("Grid", "M4 4h16v16H4zM4 9.3h16M4 14.6h16M9.3 4v16M14.6 4v16") }
    public val Eye: ImageVector by lazy {
        icon("Eye", "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12zM12 9.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z")
    }
    public val EyeOff: ImageVector by lazy {
        icon(
            "EyeOff",
            "M4 4l16 16M9.8 5.8A9 9 0 0 1 12 5.5C18 5.5 21.5 12 21.5 12a17 17 0 0 1-3 3.7M6.3 7.3A16 16 0 0 0 2.5 12S6 18.5 12 18.5c1.5 0 2.9-.4 4.1-1",
        )
    }
    public val Lock: ImageVector by lazy { icon("Lock", "M6 11h12v9H6zM8.5 11V8a3.5 3.5 0 0 1 7 0v3") }
    public val Unlock: ImageVector by lazy { icon("Unlock", "M6 11h12v9H6zM8.5 11V8a3.5 3.5 0 0 1 6.8-1.2") }
    public val Merge: ImageVector by lazy { icon("Merge", "M7 3v6l5 5 5-5V3M12 14v7M8.5 17.5 12 21l3.5-3.5") }
    public val MoveUp: ImageVector by lazy { icon("MoveUp", "M12 20V5M6 11l6-6 6 6") }
    public val MoveDown: ImageVector by lazy { icon("MoveDown", "M12 4v15M6 13l6 6 6-6") }
    public val InsertBefore: ImageVector by lazy { icon("InsertBefore", "M14 4h6v16h-6M4 12h7M7.5 8.5v7") }

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector
            .Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                paths.forEach { path ->
                    addPath(
                        pathData = PathParser().parsePathString(path).toNodes(),
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = 2f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }.build()
}
