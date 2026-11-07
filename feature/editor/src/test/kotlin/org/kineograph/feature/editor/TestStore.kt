package org.kineograph.feature.editor

import android.graphics.Bitmap
import org.kineograph.engine.session.CelStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** An in-memory [CelStore]. */
internal class TestStore : CelStore {
    private val images = ConcurrentHashMap<String, Bitmap>()
    private val counter = AtomicInteger()

    /** Hears each read, which is how a test gets something to happen right in the middle of the last step of an import. */
    @Volatile
    var onRead: ((String) -> Unit)? = null

    override fun write(image: Bitmap): String {
        val name = "cels/${counter.incrementAndGet()}.png"
        images[name] = image
        return name
    }

    override fun read(file: String, sampleSize: Int): Bitmap? {
        onRead?.invoke(file)
        return images[file]
    }

    override suspend fun awaitWrites() = Unit

    override fun trimMemory() = Unit
}
