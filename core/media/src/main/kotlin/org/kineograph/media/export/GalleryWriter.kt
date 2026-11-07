package org.kineograph.media.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Puts finished exports where the rest of the phone can find them. */
public object GalleryWriter {
    private const val ALBUM = "Kineograph"

    /** Whether saving to the gallery needs the storage permission, which is only so before Android 10. */
    public val needsStoragePermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /**
     * Saves [file] to Movies/Kineograph or Pictures/Kineograph, and returns its new address. Saving always adds a new
     * entry: an earlier export of a drawing that has changed since may be the only copy of that version. It copies the
     * whole file, so it must not run on the main thread.
     */
    @Throws(IOException::class)
    public fun save(context: Context, file: File, format: ExportFormat): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(context, file, format)
        } else {
            saveToPublicDirectory(context, file, format)
        }

    /**
     * The name for a new gallery entry. Exports are named after the project, and Android only tries about 30 alternatives
     * to a name that is taken before it gives up, so the time goes in to keep them from colliding in the first place.
     */
    internal fun galleryName(file: File, nowMs: Long): String {
        val time = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(nowMs))
        return "${file.nameWithoutExtension} $time.${file.extension}"
    }

    private fun saveWithMediaStore(context: Context, file: File, format: ExportFormat): Uri {
        val resolver = context.contentResolver
        val isVideo = format == ExportFormat.MP4
        val collection =
            if (isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, galleryName(file, System.currentTimeMillis()))
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType(format))
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/" + ALBUM,
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        // The media provider's own failures arrive here as all sorts of unchecked exceptions, thrown across from its
        // process. They all mean the same thing to the user, so they must not get past as crashes.
        val uri =
            try {
                resolver.insert(collection, values)
            } catch (e: RuntimeException) {
                throw IOException("Could not create a gallery entry", e)
            } ?: throw IOException("Could not create a gallery entry")
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("Could not open the gallery entry")
            stream.use { output -> file.inputStream().use { it.copyTo(output) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: IOException) {
            deleteQuietly(context, uri)
            throw e
        } catch (e: RuntimeException) {
            deleteQuietly(context, uri)
            throw IOException("Could not finish the gallery entry", e)
        }
    }

    /** A half-written entry would use up space for days. Failing to remove it must not hide why the save failed. */
    private fun deleteQuietly(context: Context, uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: RuntimeException) {
            // Android clears out abandoned entries by itself in the end.
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToPublicDirectory(context: Context, file: File, format: ExportFormat): Uri {
        val root = if (format == ExportFormat.MP4) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val directory = File(Environment.getExternalStoragePublicDirectory(root), ALBUM)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create $directory")
        val target = copyToNewFile(file, directory, galleryName(file, System.currentTimeMillis()))
        // The scan outlives the calling screen, so it must not hold on to one.
        MediaScannerConnection.scanFile(context.applicationContext, arrayOf(target.path), arrayOf(mimeType(format)), null)
        return target.toUri()
    }

    /**
     * Copies [file] into [directory] under [name], or a numbered variation of it, and never over anything already there.
     * The name is claimed before the copy starts, so two saves at once cannot pick the same one, and a failed copy only
     * ever removes the file it made itself.
     */
    @Throws(IOException::class)
    internal fun copyToNewFile(file: File, directory: File, name: String): File {
        val base = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.')
        val target =
            generateSequence(0) { it + 1 }
                .map { number -> File(directory, if (number == 0) name else "$base ($number).$extension") }
                .first { it.createNewFile() }
        try {
            file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        } catch (e: IOException) {
            target.delete()
            throw e
        }
        return target
    }

    /** A link to [file] that other apps may read, for sharing or opening. */
    public fun shareableUri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    public fun mimeType(format: ExportFormat): String =
        when (format) {
            ExportFormat.MP4 -> "video/mp4"
            ExportFormat.GIF -> "image/gif"
        }

    public fun extension(format: ExportFormat): String =
        when (format) {
            ExportFormat.MP4 -> "mp4"
            ExportFormat.GIF -> "gif"
        }
}
