package org.kineograph.engine

import android.graphics.Bitmap
import org.kineograph.engine.session.CelStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** An in-memory [CelStore]. */
class FakeCelStore : CelStore {
    val images = ConcurrentHashMap<String, Bitmap>()
    private val counter = AtomicInteger()

    override fun write(image: Bitmap): String {
        val name = "cels/${counter.incrementAndGet()}.png"
        images[name] = image
        return name
    }

    override fun read(file: String, sampleSize: Int): Bitmap? = images[file]

    override suspend fun awaitWrites() = Unit

    override fun trimMemory() = Unit
}
