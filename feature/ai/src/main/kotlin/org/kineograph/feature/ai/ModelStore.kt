package org.kineograph.feature.ai

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/** The model on this phone: what the file was called when it was imported, and its size in bytes. */
public data class InstalledModel(val name: String, val bytes: Long)

/** A file the person picked, to be copied in as the model. [bytes] is -1 when the provider does not say. */
public class ModelSource(public val name: String, public val bytes: Long, public val open: () -> InputStream?) {
    public companion object {
        /** The file at [uri], as the document provider describes it. */
        public fun of(resolver: ContentResolver, uri: Uri): ModelSource {
            var name: String? = null
            var bytes = -1L
            try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
                        if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) bytes = cursor.getLong(sizeColumn)
                    }
                }
            } catch (_: RuntimeException) {
                // A provider that cannot describe the file can still hand it over. The copy finds its size as it goes.
            }
            if (bytes < 0) {
                bytes =
                    try {
                        resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    } catch (_: Exception) {
                        -1L
                    }.let { if (it == AssetFileDescriptor.UNKNOWN_LENGTH) -1L else it }
            }
            return ModelSource(name ?: uri.lastPathSegment ?: DEFAULT_NAME, bytes) { resolver.openInputStream(uri) }
        }

        private const val DEFAULT_NAME = "model.litertlm"
    }
}

/** How importing a model went. */
public sealed interface ImportOutcome {
    public data class Done(val model: InstalledModel) : ImportOutcome

    /** The file does not start the way a LiteRT-LM model does. */
    public data object NotAModel : ImportOutcome

    public data class NotEnoughSpace(val needed: Long, val free: Long) : ImportOutcome

    public data object Failed : ImportOutcome
}

/**
 * The one model the app holds, in [directory], which should be kept out of backups. A new model is copied in whole beside the old one
 * and only then takes its place, so a failed or cancelled import leaves things as they were.
 */
public class ModelStore(
    private val directory: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val freeBytes: (File) -> Long = { StatFs(it.path).availableBytes },
) {
    /** The model file. LiteRT-LM goes by the name's ending as well as by what is inside. */
    public val file: File get() = File(directory, MODEL_FILE)

    /** Where the engine keeps what it works out once and reuses, such as GPU programs. */
    public val cacheDirectory: File get() = File(directory, CACHE_DIRECTORY)

    private val nameFile: File get() = File(directory, NAME_FILE)
    private val partFile: File get() = File(directory, "$MODEL_FILE$PART_SUFFIX")

    public fun installed(): InstalledModel? {
        val model = file
        if (!model.isFile) return null
        val name =
            try {
                nameFile.readText().trim()
            } catch (_: IOException) {
                ""
            }
        return InstalledModel(name.ifEmpty { MODEL_FILE }, model.length())
    }

    /** Copies [source] in as the model, telling [onProgress] how many bytes have been copied so far. */
    public suspend fun import(source: ModelSource, onProgress: (copied: Long) -> Unit = {}): ImportOutcome =
        withContext(io) {
            directory.mkdirs()
            partFile.delete()
            if (source.bytes > 0) {
                val free = freeBytes(directory)
                if (free < source.bytes + SPARE_BYTES) return@withContext ImportOutcome.NotEnoughSpace(source.bytes + SPARE_BYTES, free)
            }
            try {
                val input = source.open() ?: return@withContext ImportOutcome.Failed
                val copied =
                    input.use { stream ->
                        val header = ByteArray(MAGIC.size)
                        if (readFully(stream, header) < header.size ||
                            !header.contentEquals(MAGIC)
                        ) {
                            return@withContext ImportOutcome.NotAModel
                        }
                        copy(stream, header, onProgress)
                    }
                // The engine keeps what it worked out for the old model, which is no use for a new one.
                cacheDirectory.deleteRecursively()
                if (!partFile.renameTo(file)) throw IOException("The copy could not take the model's place")
                nameFile.writeText(source.name)
                ImportOutcome.Done(InstalledModel(source.name, copied))
            } catch (e: IOException) {
                partFile.delete()
                val free = freeBytes(directory)
                if (free < SPARE_BYTES) ImportOutcome.NotEnoughSpace(maxOf(source.bytes, 0L) + SPARE_BYTES, free) else ImportOutcome.Failed
            } catch (_: SecurityException) {
                partFile.delete()
                ImportOutcome.Failed
            } finally {
                // Cancelled, refused or failed: a part file is never wanted once this returns.
                if (partFile.exists()) partFile.delete()
            }
        }

    private suspend fun copy(input: InputStream, header: ByteArray, onProgress: (Long) -> Unit): Long {
        var copied = 0L
        FileOutputStream(partFile).use { out ->
            out.write(header)
            copied += header.size
            val buffer = ByteArray(BUFFER_BYTES)
            var reported = 0L
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                copied += read
                if (copied - reported >= PROGRESS_STEP_BYTES) {
                    reported = copied
                    onProgress(copied)
                }
            }
            out.fd.sync()
        }
        onProgress(copied)
        return copied
    }

    /** Deletes the model and everything worked out from it. */
    public suspend fun delete(): Unit =
        withContext(io) {
            file.delete()
            partFile.delete()
            nameFile.delete()
            cacheDirectory.deleteRecursively()
        }

    private fun readFully(input: InputStream, into: ByteArray): Int {
        var total = 0
        while (total < into.size) {
            val read = input.read(into, total, into.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    public companion object {
        /** Every LiteRT-LM model file starts with these bytes. */
        internal val MAGIC: ByteArray = "LITERTLM".toByteArray(Charsets.US_ASCII)
        private const val MODEL_FILE = "model.litertlm"
        private const val NAME_FILE = "model.name"
        private const val CACHE_DIRECTORY = "cache"
        private const val PART_SUFFIX = ".part"
        private const val BUFFER_BYTES = 1 shl 20
        private const val PROGRESS_STEP_BYTES = 16L shl 20

        /** Room left over once the model is in, for the engine's cache and for drawing. */
        internal const val SPARE_BYTES = 512L shl 20
    }
}
