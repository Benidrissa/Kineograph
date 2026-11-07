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

/** A [CelStore] that runs out of memory, or fails to find images, whenever a test asks it to. */
class FlakyCelStore(private val inner: FakeCelStore = FakeCelStore()) : CelStore {
    /** How many more calls to [read] run out of memory. */
    val readsOutOfMemory = AtomicInteger()

    /** How many more calls to [read] find nothing. */
    val readsReturningNull = AtomicInteger()

    /** How many more calls to [write] run out of memory. */
    val writesOutOfMemory = AtomicInteger()

    val images: Map<String, Bitmap> get() = inner.images

    override fun write(image: Bitmap): String {
        if (writesOutOfMemory.getAndUpdate { maxOf(0, it - 1) } > 0) throw OutOfMemoryError("Test")
        return inner.write(image)
    }

    override fun read(file: String, sampleSize: Int): Bitmap? {
        if (readsOutOfMemory.getAndUpdate { maxOf(0, it - 1) } > 0) throw OutOfMemoryError("Test")
        if (readsReturningNull.getAndUpdate { maxOf(0, it - 1) } > 0) return null
        return inner.read(file, sampleSize)
    }

    override suspend fun awaitWrites() = inner.awaitWrites()

    override fun trimMemory() = inner.trimMemory()
}
