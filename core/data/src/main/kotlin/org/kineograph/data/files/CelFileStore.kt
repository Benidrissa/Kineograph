package org.kineograph.data.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.kineograph.engine.session.CelStore
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
 *  - **Format** is lossless WebP where available (API 30+) and PNG before that.
 */
public class CelFileStore(
    private val projectDirectory: File,
    compressedCacheBytes: Long,
    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_WRITES),
) : CelStore {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val pending = ConcurrentHashMap<String, Bitmap>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val failed = ConcurrentHashMap<String, Bitmap>()

    /** Limits queued images, so a huge merge cannot hold hundreds of full-size bitmaps in memory at once. */
    private val queueSlots = Semaphore(MAX_QUEUED_IMAGES)

    private val cache =
        object : LruCache<String, ByteArray>(compressedCacheBytes.coerceIn(MIN_CACHE_BYTES, Int.MAX_VALUE.toLong()).toInt()) {
            override fun sizeOf(key: String, value: ByteArray): Int = value.size
        }

    override fun write(image: Bitmap): String {
        val extension = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "webp" else "png"
        val name = "$CEL_DIRECTORY/${UUID.randomUUID()}.$extension"
        pending[name] = image
        queueSlots.acquireUninterruptibly()
        enqueue(name, image)
        return name
    }

    private fun enqueue(name: String, image: Bitmap) {
        jobs[name] =
            scope.launch {
                try {
                    val encoded = encode(image, name)
                    AtomicFile.write(File(projectDirectory, name), encoded)
                    cache.put(name, encoded)
                    pending.remove(name)
                    failed.remove(name)
                } catch (_: IOException) {
                    // Most likely the disk is full. The image stays in memory and awaitWrites will report it.
                    failed[name] = image
                } catch (_: OutOfMemoryError) {
                    // Same outcome: the image is kept and tried again later, now with the cache out of the way.
                    cache.evictAll()
                    failed[name] = image
                } finally {
                    jobs.remove(name)
                    queueSlots.release()
                }
            }
    }

    @Throws(IOException::class)
    private fun encode(image: Bitmap, name: String): ByteArray {
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

    /** Waits for every queued write. Retries earlier failures once, and throws if any image still is not on disk. */
    @Throws(IOException::class)
    override suspend fun awaitWrites() {
        failed.keys.toList().forEach { name ->
            val image = failed.remove(name) ?: return@forEach
            queueSlots.acquireUninterruptibly()
            enqueue(name, image)
        }
        jobs.values.toList().joinAll()
        if (failed.isNotEmpty()) throw IOException("${failed.size} images could not be saved")
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
            cache.remove(name)
        }
    }

    /** Stops background work. Call after a final [awaitWrites]. */
    public fun close() {
        scope.cancel()
    }

    private companion object {
        const val CEL_DIRECTORY = "cels"
        const val MAX_CONCURRENT_WRITES = 2
        const val MAX_QUEUED_IMAGES = 8
        const val MIN_CACHE_BYTES = 4L * 1024 * 1024

        /** For lossless WebP, quality means effort. Low effort stays close to PNG's size at many times the speed. */
        const val WEBP_EFFORT = 20
        const val PNG_QUALITY_IGNORED = 100
    }
}
