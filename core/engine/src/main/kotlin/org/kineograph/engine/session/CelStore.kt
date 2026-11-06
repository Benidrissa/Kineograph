package org.kineograph.engine.session

import android.graphics.Bitmap

/**
 * Where cel images live. The engine knows nothing about paths or file formats. Names returned by
 * [write] are immutable: a name always means the same pixels, so edits always produce a new name.
 */
public interface CelStore {
    /**
     * Queues [image] to be saved and returns its name at once. The image must not be changed afterwards.
     * [read] finds it straight away, even before it reaches the disk.
     */
    public fun write(image: Bitmap): String

    /** Decodes an image, optionally reduced by a power-of-two [sampleSize]. Returns null if it cannot be read. */
    public fun read(file: String, sampleSize: Int = 1): Bitmap?

    /** Suspends until every queued [write] has reached the disk. */
    public suspend fun awaitWrites()

    /** Frees caches, for when memory runs low. */
    public fun trimMemory()
}
