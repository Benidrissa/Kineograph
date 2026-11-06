package org.kineograph.engine.compositor

import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import org.kineograph.model.BlendMode

/** Applies a layer blend mode to a paint, using the richer API where it exists. */
internal fun Paint.applyLayerBlend(mode: BlendMode) {
    if (mode == BlendMode.NORMAL) {
        xfermode = null
        return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        blendMode =
            when (mode) {
                BlendMode.NORMAL -> android.graphics.BlendMode.SRC_OVER
                BlendMode.MULTIPLY -> android.graphics.BlendMode.MULTIPLY
                BlendMode.SCREEN -> android.graphics.BlendMode.SCREEN
                BlendMode.OVERLAY -> android.graphics.BlendMode.OVERLAY
                BlendMode.ADD -> android.graphics.BlendMode.PLUS
                BlendMode.DARKEN -> android.graphics.BlendMode.DARKEN
                BlendMode.LIGHTEN -> android.graphics.BlendMode.LIGHTEN
                BlendMode.COLOR_DODGE -> android.graphics.BlendMode.COLOR_DODGE
                BlendMode.COLOR_BURN -> android.graphics.BlendMode.COLOR_BURN
                BlendMode.DIFFERENCE -> android.graphics.BlendMode.DIFFERENCE
            }
    } else {
        xfermode =
            when (mode) {
                BlendMode.MULTIPLY -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)

                BlendMode.SCREEN -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)

                BlendMode.OVERLAY -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY)

                BlendMode.ADD -> PorterDuffXfermode(PorterDuff.Mode.ADD)

                BlendMode.DARKEN -> PorterDuffXfermode(PorterDuff.Mode.DARKEN)

                BlendMode.LIGHTEN -> PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)

                // These three have no equivalent before API 29, so the layer draws normally there.
                BlendMode.NORMAL, BlendMode.COLOR_DODGE, BlendMode.COLOR_BURN, BlendMode.DIFFERENCE -> null
            }
    }
}
