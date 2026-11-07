package org.kineograph.engine.history

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Compresses pixel patches. Transparent areas and flat colour shrink to almost nothing. */
public object PatchCodec {
    /**
     * Compresses [pixels], feeding them through a small scratch buffer. A second full-size copy of a large patch is
     * often more than a low-memory phone has left, and then the patch could never shrink or spill to disk.
     */
    public fun compress(pixels: IntArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED, true)
        try {
            val scratch = ByteArray(SCRATCH_BYTES)
            val scratchPixels = ByteBuffer.wrap(scratch).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            val output = ByteArrayOutputStream(maxOf(64, pixels.size / 8))
            val chunk = ByteArray(16 * 1024)
            var next = 0
            while (next < pixels.size) {
                val count = minOf(scratchPixels.capacity(), pixels.size - next)
                scratchPixels.clear()
                scratchPixels.put(pixels, next, count)
                next += count
                deflater.setInput(scratch, 0, count * Int.SIZE_BYTES)
                while (!deflater.needsInput()) output.write(chunk, 0, deflater.deflate(chunk))
            }
            deflater.finish()
            while (!deflater.finished()) output.write(chunk, 0, deflater.deflate(chunk))
            return output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** Restores what [compress] produced. Damaged or shortened data throws, so it can never pass for transparent pixels. */
    @Throws(DataFormatException::class)
    public fun decompress(data: ByteArray, pixelCount: Int): IntArray {
        val raw = ByteArray(pixelCount * Int.SIZE_BYTES)
        val inflater = Inflater(true)
        var offset = 0
        try {
            inflater.setInput(data)
            while (offset < raw.size && !inflater.finished()) {
                val read = inflater.inflate(raw, offset, raw.size - offset)
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                offset += read
            }
        } finally {
            inflater.end()
        }
        if (offset < raw.size) throw DataFormatException("A patch was shorter than expected")
        val pixels = IntArray(pixelCount)
        ByteBuffer
            .wrap(raw)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asIntBuffer()
            .get(pixels)
        return pixels
    }

    private const val SCRATCH_BYTES = 64 * 1024
}

/**
 * Pixels saved for undo. A patch moves down this ladder as memory gets tight: raw pixels right after
 * the edit, then compressed bytes in memory, then compressed bytes in a file.
 *
 * Undo needs the old pixels back at once even if compression has not finished, so every state can
 * always be read. All methods are synchronized because a background task compresses while the engine
 * thread may read.
 */
public class PatchPayload(pixels: IntArray) {
    private val pixelCount = pixels.size
    private var raw: IntArray? = pixels
    private var compressed: ByteArray? = null
    private var spillFile: File? = null

    /** Bytes held in memory. */
    @get:Synchronized
    public val memoryBytes: Long
        get() = (raw?.size?.toLong() ?: 0L) * 4 + (compressed?.size?.toLong() ?: 0L)

    /** Bytes held on disk. */
    @get:Synchronized
    public val diskBytes: Long
        get() = spillFile?.length() ?: 0L

    @get:Synchronized
    public val isCompressed: Boolean
        get() = raw == null

    @get:Synchronized
    public val isSpilled: Boolean
        get() = spillFile != null

    /**
     * Reads the pixels back, whichever state they are in. Returns null if they have gone: spilled patches live in the
     * cache directory, and the system may empty that whenever storage runs low.
     */
    @Synchronized
    public fun readOrNull(): IntArray? {
        raw?.let { return it.copyOf() }
        return try {
            val data = compressed ?: spillFile?.readBytes() ?: return IntArray(pixelCount)
            PatchCodec.decompress(data, pixelCount)
        } catch (_: IOException) {
            null
        } catch (_: DataFormatException) {
            null
        }
    }

    /** Replaces the contents. Undo swaps a patch's pixels with what was on the canvas, so the same patch then serves redo. */
    @Synchronized
    public fun replace(pixels: IntArray) {
        require(pixels.size == pixelCount) { "A patch cannot change size" }
        raw = pixels
        compressed = null
        spillFile?.delete()
        spillFile = null
    }

    /** Compresses raw pixels. Slow work happens outside the lock so that undo is never kept waiting. */
    public fun compress() {
        val source = synchronized(this) { raw } ?: return
        val result = PatchCodec.compress(source)
        synchronized(this) {
            // Skip if the patch was swapped while compressing.
            if (raw === source) {
                compressed = result
                raw = null
            }
        }
    }

    /** Moves the patch to a file, freeing its memory. Returns false if that failed, leaving it in memory. */
    @Synchronized
    public fun spillTo(file: File): Boolean {
        if (spillFile != null) return true
        return try {
            val data = compressed ?: raw?.let(PatchCodec::compress) ?: return false
            file.parentFile?.mkdirs()
            file.writeBytes(data)
            spillFile = file
            compressed = null
            raw = null
            true
        } catch (_: IOException) {
            file.delete()
            false
        } catch (_: OutOfMemoryError) {
            // This often runs because memory has already run out, and a patch that stays in memory is still a good patch.
            file.delete()
            false
        }
    }

    /** Frees everything this patch holds. */
    @Synchronized
    public fun discard() {
        raw = null
        compressed = null
        spillFile?.delete()
        spillFile = null
    }
}
