package org.kineograph.engine.history

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Compresses pixel patches. Transparent areas and flat colour shrink to almost nothing. */
public object PatchCodec {
    public fun compress(pixels: IntArray): ByteArray {
        val raw = ByteBuffer.allocate(pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        raw.asIntBuffer().put(pixels)
        val deflater = Deflater(Deflater.BEST_SPEED, true)
        try {
            deflater.setInput(raw.array())
            deflater.finish()
            val output = ByteArrayOutputStream(maxOf(64, pixels.size / 8))
            val chunk = ByteArray(16 * 1024)
            while (!deflater.finished()) {
                val written = deflater.deflate(chunk)
                output.write(chunk, 0, written)
            }
            return output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    public fun decompress(data: ByteArray, pixelCount: Int): IntArray {
        val raw = ByteArray(pixelCount * 4)
        val inflater = Inflater(true)
        try {
            inflater.setInput(data)
            var offset = 0
            while (offset < raw.size && !inflater.finished()) {
                val read = inflater.inflate(raw, offset, raw.size - offset)
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                offset += read
            }
        } finally {
            inflater.end()
        }
        val pixels = IntArray(pixelCount)
        ByteBuffer
            .wrap(raw)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asIntBuffer()
            .get(pixels)
        return pixels
    }
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

    /** Reads the pixels back, whichever state they are in. */
    @Synchronized
    public fun read(): IntArray {
        raw?.let { return it.copyOf() }
        val data = compressed ?: spillFile?.readBytes() ?: return IntArray(pixelCount)
        return PatchCodec.decompress(data, pixelCount)
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

    /** Moves the patch to a file, freeing its memory. Returns false if the write failed, leaving it in memory. */
    @Synchronized
    public fun spillTo(file: File): Boolean {
        if (spillFile != null) return true
        val data = compressed ?: raw?.let(PatchCodec::compress) ?: return false
        return try {
            file.parentFile?.mkdirs()
            file.writeBytes(data)
            spillFile = file
            compressed = null
            raw = null
            true
        } catch (_: java.io.IOException) {
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
