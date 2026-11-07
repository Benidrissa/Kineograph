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

    /**
     * Whether an image has definitely gone for good. A null from [read] cannot say that by itself, because a read error or
     * running out of memory while decoding gives null too. This is false when the image is there and also when that cannot
     * be determined, so a drawing is never written off just because storage was briefly out of reach.
     */
    public fun isGone(file: String): Boolean

    /** Suspends until every queued [write] has reached the disk. */
    public suspend fun awaitWrites()

    /** Frees caches, for when memory runs low. */
    public fun trimMemory()
}
