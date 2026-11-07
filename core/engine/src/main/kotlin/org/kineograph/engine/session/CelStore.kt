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

    /**
     * Suspends until every queued [write] has reached the disk. Throws [java.io.IOException] if any could not, or [OutOfMemoryError] if that was
     * because memory ran out, since the thread that ran into it cannot tell anyone by itself.
     */
    public suspend fun awaitWrites()

    /** Frees caches, for when memory runs low. */
    public fun trimMemory()

    /**
     * Gives up on images that nothing will ever refer to, such as those of an import that ran out of storage. The sweep would get to the files
     * in time, but the space is needed now, and a write that keeps failing would keep making every later save fail along with it. When this
     * returns, none of them is in memory, on disk or on its way there, even if someone else was waiting for those writes. It can take as long as
     * a write, and one delete for each file, so it does not belong on the main thread.
     */
    public suspend fun discard(files: Collection<String>) {}
}
