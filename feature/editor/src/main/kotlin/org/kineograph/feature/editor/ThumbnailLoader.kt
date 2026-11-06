package org.kineograph.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kineograph.engine.compositor.FlattenOptions
import org.kineograph.engine.compositor.FrameFlattener
import org.kineograph.engine.session.CelStore
import org.kineograph.model.Background
import org.kineograph.model.Project
import kotlin.math.max

/**
 * Makes the small pictures in the frame strip and layer list. All the work happens on background threads, limited so
 * it can never starve drawing. A thumbnail's cache key is made from the cel files it shows, so it refreshes by itself when
 * a drawing changes, and frames that share a drawing share a thumbnail.
 */
internal class ThumbnailLoader(private val store: CelStore, maxBytes: Int) {
    private val cache =
        object : LruCache<String, ImageBitmap>(maxBytes) {
            override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * BYTES_PER_PIXEL
        }
    private val dispatcher = Dispatchers.Default.limitedParallelism(MAX_CONCURRENT)
    private var backgroundFile: String? = null
    private var backgroundImage: Bitmap? = null

    /** The key that identifies what a frame's thumbnail would show. */
    fun frameKey(project: Project, frameId: String, edgePx: Int): String =
        buildString {
            append(edgePx).append(':').append(project.background.hashCode())
            for (layer in project.layers) {
                if (!layer.visible) continue
                val cel = project.cel(layer.id, frameId) ?: continue
                append('|').append(cel.file).append('@').append(layer.opacity)
            }
        }

    /** The key that identifies what one layer's thumbnail would show. */
    fun layerKey(
        project: Project,
        frameId: String,
        layerId: String,
        edgePx: Int,
    ): String = "L$edgePx:${project.cel(layerId, frameId)?.file}"

    fun cached(key: String): ImageBitmap? = cache.get(key)

    suspend fun loadFrame(project: Project, frameId: String, edgePx: Int): ImageBitmap? {
        val key = frameKey(project, frameId, edgePx)
        return cache.get(key) ?: render(project, frameId, edgePx, key, FlattenOptions(forEditor = true))
    }

    suspend fun loadLayer(project: Project, frameId: String, layerId: String, edgePx: Int): ImageBitmap? {
        val key = layerKey(project, frameId, layerId, edgePx)
        return cache.get(key) ?: render(project, frameId, edgePx, key, FlattenOptions(includeBackground = false, onlyLayerId = layerId))
    }

    private suspend fun render(project: Project, frameId: String, edgePx: Int, key: String, options: FlattenOptions): ImageBitmap? =
        withContext(dispatcher) {
            try {
                val scale = edgePx.toFloat() / max(project.width, project.height)
                val width = (project.width * scale).toInt().coerceAtLeast(1)
                val height = (project.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                FrameFlattener(store).flatten(project, frameId, Canvas(bitmap), scale, backgroundOf(project), options)
                val image = bitmap.asImageBitmap()
                cache.put(key, image)
                image
            } catch (_: OutOfMemoryError) {
                cache.evictAll()
                null
            }
        }

    private fun backgroundOf(project: Project): Bitmap? {
        val file = (project.background as? Background.Image)?.file ?: return null
        synchronized(this) {
            if (file != backgroundFile) {
                backgroundFile = file
                backgroundImage = store.read(file, BACKGROUND_SAMPLE)
            }
            return backgroundImage
        }
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4
        const val MAX_CONCURRENT = 2
        const val BACKGROUND_SAMPLE = 4
    }
}
