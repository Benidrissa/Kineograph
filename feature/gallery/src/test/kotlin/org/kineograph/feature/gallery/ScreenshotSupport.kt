package org.kineograph.feature.gallery

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import java.io.File

/** Saves what a test sees, for looking at by eye. Set KG_SCREENSHOT_DIR to turn it on. */
internal fun SemanticsNodeInteraction.saveScreenshot(name: String) {
    val directory = System.getenv("KG_SCREENSHOT_DIR") ?: return
    File(directory).mkdirs()
    File(directory, "$name.png").outputStream().use { captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
}
