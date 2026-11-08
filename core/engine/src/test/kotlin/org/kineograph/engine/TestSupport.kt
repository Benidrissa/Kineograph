package org.kineograph.engine

import android.graphics.Bitmap
import org.kineograph.engine.session.CelStore
import org.kineograph.engine.vector.RecordedStroke
import org.kineograph.engine.vector.StrokeFiles
import org.kineograph.model.IntRect
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** An in-memory [CelStore]. */
class FakeCelStore : CelStore {
    val images = ConcurrentHashMap<String, Bitmap>()
    private val counter = AtomicInteger()

    @Volatile
    var failNextReadWithOutOfMemory = false

    override fun write(image: Bitmap): String {
        val name = "cels/${counter.incrementAndGet()}.png"
        images[name] = image
        return name
    }

    override fun read(file: String, sampleSize: Int): Bitmap? {
        if (failNextReadWithOutOfMemory) {
            failNextReadWithOutOfMemory = false
            throw OutOfMemoryError("no room to decode")
        }
        return images[file]
    }

    override fun isGone(file: String): Boolean = !images.containsKey(file)

    override suspend fun awaitWrites() = Unit

    override fun trimMemory() = Unit

    /** Kept strokes, as the bytes of their files, so they go through the real format. */
    val kept = ConcurrentHashMap<String, ByteArray>()

    override fun keepStrokes(file: String, celBounds: IntRect, strokes: List<RecordedStroke>) {
        StrokeFiles.encode(strokes, celBounds)?.let { kept[file] = it }
    }

    override fun keptStrokes(file: String, celBounds: IntRect): List<RecordedStroke>? = kept[file]?.let {
        StrokeFiles.decode(
            it,
            celBounds,
        )
    }
}

/** A [CelStore] that runs out of memory, or fails to find images, whenever a test asks it to. */
class FlakyCelStore(private val inner: FakeCelStore = FakeCelStore()) : CelStore {
    /** How many more calls to [read] run out of memory. */
    val readsOutOfMemory = AtomicInteger()

    /** How many more calls to [read] find nothing. */
    val readsReturningNull = AtomicInteger()

    /** How many more calls to [write] run out of memory. */
    val writesOutOfMemory = AtomicInteger()

    /** Files that run out of memory every time they are read, while everything else reads normally. */
    val filesOutOfMemory: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Files that are still there but read as nothing every time, as when a decode cannot get the memory it needs. */
    val filesReturningNull: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** How many times this was asked to free memory. */
    val trims = AtomicInteger()

    val images: Map<String, Bitmap> get() = inner.images

    /** Removes a file for good, as when it has really gone from the disk. */
    fun lose(file: String) {
        inner.images.remove(file)
    }

    override fun write(image: Bitmap): String {
        if (writesOutOfMemory.getAndUpdate { maxOf(0, it - 1) } > 0) throw OutOfMemoryError("Test")
        return inner.write(image)
    }

    override fun read(file: String, sampleSize: Int): Bitmap? {
        if (readsOutOfMemory.getAndUpdate { maxOf(0, it - 1) } > 0) throw OutOfMemoryError("Test")
        if (readsReturningNull.getAndUpdate { maxOf(0, it - 1) } > 0) return null
        if (file in filesOutOfMemory) throw OutOfMemoryError("Test")
        if (file in filesReturningNull) return null
        return inner.read(file, sampleSize)
    }

    override fun isGone(file: String): Boolean = inner.isGone(file)

    override suspend fun awaitWrites() = inner.awaitWrites()

    override fun trimMemory() {
        trims.incrementAndGet()
        inner.trimMemory()
    }
}
