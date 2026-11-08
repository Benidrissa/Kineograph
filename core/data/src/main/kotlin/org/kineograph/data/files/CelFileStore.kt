package org.kineograph.data.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.kineograph.engine.session.CelStore
import org.kineograph.engine.vector.RecordedStroke
import org.kineograph.engine.vector.StrokeFiles
import org.kineograph.model.IntRect
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * Keeps cel images as files in a project's `cels/` directory.
 *
 *  - **Writes** return a name at once and finish in the background. The image stays readable in memory until
 *    it is safely on disk. A failed write keeps the image in memory and is retried, so a full disk loses nothing.
 *  - **Reads** check, in order: images still being written, the compressed in-memory cache, then the disk.
 *    The cache holds encoded bytes rather than bitmaps, which is what lets many frames fit in little memory.
 *  - **Format** is lossless WebP where available (API 30+) and PNG before that. PNG also takes what is too long for WebP.
 */
public class CelFileStore(
    private val projectDirectory: File,
    compressedCacheBytes: Long,
    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_WRITES),
    maxQueuedBytes: Long = MAX_QUEUED_BYTES,
    private val encoder: (Bitmap, String) -> ByteArray = ::encodeCel,
) : CelStore {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val pending = ConcurrentHashMap<String, Bitmap>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val failed = ConcurrentHashMap<String, FailedWrite>()
    private val strokeJobs: MutableSet<Job> = ConcurrentHashMap.newKeySet()

    /** Images given up on while still being written, for the write to clear away behind itself when it ends. */
    private val givenUp = HashSet<String>()

    /** Held for the moment an image passes between [failed], [jobs] and [givenUp], so that giving up on it cannot fall in between them. */
    private val settling = Any()

    @Volatile
    private var closed = false

    /** Limits queued images, so a huge merge cannot hold hundreds of full-size bitmaps in memory at once. */
    private val queueSlots = Semaphore(MAX_QUEUED_IMAGES)

    /** Eight images are nothing on a small canvas and most of the phone's memory on a huge one, so what they weigh is limited too. They wait in turn, or the largest might never get in. */
    private val queueKilobytes = (maxQueuedBytes / BYTES_PER_KILOBYTE).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    private val queueRoom = Semaphore(queueKilobytes, true)

    private val cache =
        object : LruCache<String, ByteArray>(compressedCacheBytes.coerceIn(MIN_CACHE_BYTES, Int.MAX_VALUE.toLong()).toInt()) {
            override fun sizeOf(key: String, value: ByteArray): Int = value.size
        }

    override fun write(image: Bitmap): String {
        val canUseWebP = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && maxOf(image.width, image.height) <= WEBP_MAX_EDGE
        val extension = if (canUseWebP) "webp" else "png"
        val name = "$CEL_DIRECTORY/${UUID.randomUUID()}.$extension"
        pending[name] = image
        takeQueueRoom(image)
        enqueue(name, image)
        return name
    }

    /** Waits for room in the queue. Whatever holds it is a write that ends soon, even once the store has closed, so this cannot wait for ever. */
    private fun takeQueueRoom(image: Bitmap) {
        queueSlots.acquireUninterruptibly()
        queueRoom.acquireUninterruptibly(queueWeight(image))
    }

    private fun giveBackQueueRoom(image: Bitmap) {
        queueRoom.release(queueWeight(image))
        queueSlots.release()
    }

    /** An image larger than the whole queue goes through on its own. */
    private fun queueWeight(image: Bitmap): Int = (image.allocationByteCount / BYTES_PER_KILOBYTE).toInt().coerceIn(1, queueKilobytes)

    /** A job that is cancelled before it starts never runs its body, so what has to happen whatever comes of it happens on completion. */
    private fun enqueue(name: String, image: Bitmap) {
        val job = scope.launch(start = CoroutineStart.LAZY) { writeOut(name, image) }
        jobs[name] = job
        job.invokeOnCompletion {
            giveBackQueueRoom(image)
            // Someone may already be retrying this image, and then it is that write that has the last word.
            val wasGivenUp =
                synchronized(settling) {
                    jobs.remove(name, job)
                    !jobs.containsKey(name) && givenUp.remove(name)
                }
            if (wasGivenUp) forget(name)
        }
        job.start()
    }

    private fun writeOut(name: String, image: Bitmap) {
        try {
            val encoded = encoder(image, name)
            AtomicFile.write(File(projectDirectory, name), encoded)
            cache.put(name, encoded)
            pending.remove(name)
        } catch (_: IOException) {
            // Most likely the disk is full. The image stays in memory and awaitWrites will report it.
            noteFailure(name, image, outOfMemory = false)
        } catch (_: OutOfMemoryError) {
            // Encoding grows a buffer here, and an error nobody catches on this thread would end the whole app. It is a failed write like
            // any other: the image is kept and tried again, and giving up what is cached is the best chance of the next try working.
            cache.evictAll()
            noteFailure(name, image, outOfMemory = true)
        }
    }

    /** An image nobody wants any more must not make the next save fail. */
    private fun noteFailure(name: String, image: Bitmap, outOfMemory: Boolean) {
        synchronized(settling) {
            if (name !in givenUp) failed[name] = FailedWrite(image, outOfMemory)
        }
    }

    override fun read(file: String, sampleSize: Int): Bitmap? {
        pending[file]?.let { return it }
        val bytes = cache.get(file) ?: readFromDisk(file) ?: return null
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun readFromDisk(file: String): ByteArray? {
        val bytes =
            try {
                val target = resolve(file) ?: return null
                target.readBytes()
            } catch (_: IOException) {
                return null
            }
        cache.put(file, bytes)
        return bytes
    }

    /** Refuses names that climb out of the project directory, which a damaged or hostile manifest might contain. */
    private fun resolve(file: String): File? {
        val base = projectDirectory.canonicalFile
        val target = File(base, file).canonicalFile
        return if (target.path.startsWith(base.path + File.separator)) target else null
    }

    /** Whether an image is available, in memory or on disk. */
    public fun exists(file: String): Boolean = pending.containsKey(file) || resolve(file)?.isFile == true

    override fun isGone(file: String): Boolean {
        if (pending.containsKey(file) || cache.get(file) != null) return false
        return try {
            val target = resolve(file) ?: return true
            // Unlike a failed exists check, this only says yes when the system really reported no such file.
            Files.notExists(target.toPath())
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Waits for every queued write. Retries earlier failures once, and throws if any image still is not on disk. Each failure speaks for itself, so
     * this tells what is wrong now, whoever else is waiting, and however it went before. A full disk speaks first when both happened, as that is the one
     * thing the caller can do something about at once. A store that has closed will not write what it still holds, which is a failure like any other.
     */
    @Throws(IOException::class)
    override suspend fun awaitWrites() {
        for ((name, write) in failed.entries.snapshot()) {
            takeQueueRoom(write.image)
            val retrying =
                synchronized(settling) {
                    failed.remove(name)?.also { enqueue(name, it.image) } != null
                }
            if (!retrying) giveBackQueueRoom(write.image)
        }
        jobs.values.snapshot().joinAll()
        val failures = failed.values.snapshot()
        if (failures.isNotEmpty()) {
            if (failures.all { it.outOfMemory }) throw OutOfMemoryError("${failures.size} images could not be encoded")
            throw IOException("${failures.size} images could not be saved")
        }
        if (closed && pending.isNotEmpty()) throw IOException("${pending.size} images were left unsaved when the project closed")
    }

    override fun trimMemory() {
        cache.evictAll()
    }

    /** Lists the cel files currently on disk, by the name used in manifests. */
    public fun listFiles(): Set<String> =
        File(projectDirectory, CEL_DIRECTORY)
            .listFiles()
            ?.filter {
                it.isFile && !it.name.startsWith(".")
            }?.mapTo(HashSet()) { "$CEL_DIRECTORY/${it.name}" }
            .orEmpty()

    /**
     * Deletes every file in [candidates] that is not in [keep]. List the candidates *before* working out what to
     * keep: a file created in between then cannot be mistaken for garbage.
     */
    public fun delete(candidates: Set<String>, keep: Set<String>) {
        for (name in candidates) {
            if (name in keep || pending.containsKey(name)) continue
            resolve(name)?.delete()
            sidecarOf(name)?.delete()
            cache.remove(name)
        }
        deleteOrphanSidecars()
    }

    /**
     * A drawing's strokes are written apart from it, so a drawing that never reached the disk, or was deleted while its strokes were on the
     * way, can leave them behind. Nothing reads strokes without their drawing, so these are only taking space.
     */
    private fun deleteOrphanSidecars() {
        val drawings =
            File(projectDirectory, CEL_DIRECTORY)
                .list()
                .orEmpty()
                .mapTo(HashSet()) { it.substringBeforeLast('.') }
        pending.keys.mapTo(drawings) { File(it).name.substringBeforeLast('.') }
        val sidecars = File(projectDirectory, VECTOR_DIRECTORY).listFiles() ?: return
        for (sidecar in sidecars) {
            if (sidecar.isFile && sidecar.name.removeSuffix(VECTOR_SUFFIX) !in drawings && !sidecar.name.startsWith(".")) sidecar.delete()
        }
    }

    override fun keepStrokes(file: String, celBounds: IntRect, strokes: List<RecordedStroke>) {
        if (closed) return
        val target = sidecarOf(file) ?: return
        val job =
            scope.launch {
                try {
                    val bytes = StrokeFiles.encode(strokes, celBounds) ?: return@launch
                    if (bytes.size > MAX_SIDECAR_BYTES) return@launch
                    target.parentFile?.mkdirs()
                    // Not flushed to storage: losing strokes in a power cut costs only in-betweens, and these are written often.
                    val temporary = File(target.parentFile, ".${target.name}.tmp")
                    try {
                        temporary.writeBytes(bytes)
                        AtomicFile.move(temporary, target)
                    } finally {
                        temporary.delete()
                    }
                } catch (_: IOException) {
                    // Strokes give way first: the drawing is safe either way.
                } catch (_: OutOfMemoryError) {
                    cache.evictAll()
                } catch (_: RuntimeException) {
                    // Nothing here may end the app over strokes.
                }
            }
        strokeJobs += job
        job.invokeOnCompletion { strokeJobs.remove(job) }
    }

    override fun keptStrokes(file: String, celBounds: IntRect): List<RecordedStroke>? =
        try {
            sidecarOf(file)
                ?.takeIf { it.isFile && it.length() <= MAX_SIDECAR_BYTES }
                ?.readBytes()
                ?.let { StrokeFiles.decode(it, celBounds) }
        } catch (_: IOException) {
            null
        } catch (_: OutOfMemoryError) {
            cache.evictAll()
            null
        } catch (_: RuntimeException) {
            null
        }

    /** Waits until the strokes asked for so far have been written, or given up on. For tests, and for leaving cleanly. */
    public suspend fun awaitKeptStrokes() {
        strokeJobs.toTypedArray().toList().joinAll()
    }

    /** Where [file]'s strokes are kept: `vectors/<name>.kgv` beside `cels/<name>.<ext>`. */
    private fun sidecarOf(file: String): File? {
        val stem = File(file).name.substringBeforeLast('.')
        if (stem.isEmpty()) return null
        return resolve("$VECTOR_DIRECTORY/$stem$VECTOR_SUFFIX")
    }

    override suspend fun discard(files: Collection<String>) {
        val underWay = ArrayList<Job>()
        for (name in files) {
            synchronized(settling) {
                jobs[name]?.let { job ->
                    givenUp += name
                    underWay += job
                }
            }
            forget(name)
        }
        // One still waiting its turn never starts, and one already writing clears up after itself, which is what there is to wait for.
        underWay.forEach { it.cancel() }
        underWay.joinAll()
    }

    private fun forget(name: String) {
        synchronized(settling) { failed.remove(name) }
        pending.remove(name)
        cache.remove(name)
        try {
            sidecarOf(name)?.delete()
            resolve(name)?.delete()
        } catch (_: IOException) {
            // The next sweep gets another go at it. Nobody is waiting to hear about this, and an error on this path would end the app.
        }
    }

    /**
     * What a collection being changed by other threads holds at this moment. Kotlin's `toList` looks at the size first and then, for a single
     * item, asks for it, and by then a write finishing can have taken it away.
     */
    private fun <T> Collection<T>.snapshot(): List<T> = ArrayList(this)

    /** Stops background work. Call after a final [awaitWrites]. Anything else still waiting for this store gets its answer, and no write it asks for later happens. */
    public fun close() {
        closed = true
        scope.cancel()
    }

    private class FailedWrite(val image: Bitmap, val outOfMemory: Boolean)

    private companion object {
        const val CEL_DIRECTORY = "cels"
        const val VECTOR_DIRECTORY = "vectors"
        const val VECTOR_SUFFIX = ".kgv"

        /** Far more than a drawing's strokes come to. Anything larger is not read, so a damaged or hostile file cannot fill memory. */
        const val MAX_SIDECAR_BYTES = 32L * 1024 * 1024
        const val MAX_CONCURRENT_WRITES = 2
        const val MAX_QUEUED_IMAGES = 8

        /** Takes every image of a full HD canvas, and about two of a 4K one. Two are being written at most, so a longer queue would not get it done sooner. */
        const val MAX_QUEUED_BYTES = 64L * 1024 * 1024
        const val BYTES_PER_KILOBYTE = 1024L
        const val MIN_CACHE_BYTES = 4L * 1024 * 1024

        /** The WebP format has only 14 bits for each edge. An image past this could never be saved as one. */
        const val WEBP_MAX_EDGE = 16383
    }
}

/** For lossless WebP, quality means effort. Low effort stays close to PNG's size at many times the speed. */
private const val WEBP_EFFORT = 20
private const val PNG_QUALITY_IGNORED = 100

@Throws(IOException::class)
private fun encodeCel(image: Bitmap, name: String): ByteArray {
    val output = ByteArrayOutputStream(maxOf(1024, image.byteCount / 8))
    val succeeded =
        if (name.endsWith(".webp") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            image.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, WEBP_EFFORT, output)
        } else {
            image.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY_IGNORED, output)
        }
    if (!succeeded) throw IOException("Could not encode $name")
    return output.toByteArray()
}
