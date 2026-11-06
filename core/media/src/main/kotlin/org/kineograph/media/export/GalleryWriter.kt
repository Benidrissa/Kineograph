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

/** Puts finished exports where the rest of the phone can find them. */
public object GalleryWriter {
    private const val ALBUM = "Kineograph"

    /** Whether saving to the gallery needs the storage permission, which is only so before Android 10. */
    public val needsStoragePermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** Saves [file] to Movies/Kineograph or Pictures/Kineograph, and returns its new address. */
    @Throws(IOException::class)
    public fun save(context: Context, file: File, format: ExportFormat): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(context, file, format)
        } else {
            saveToPublicDirectory(context, file, format)
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
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType(format))
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/" + ALBUM,
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = resolver.insert(collection, values) ?: throw IOException("Could not create a gallery entry")
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("Could not open the gallery entry")
            stream.use { output -> file.inputStream().use { it.copyTo(output) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToPublicDirectory(context: Context, file: File, format: ExportFormat): Uri {
        val root = if (format == ExportFormat.MP4) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val directory = File(Environment.getExternalStoragePublicDirectory(root), ALBUM)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create $directory")
        val target = File(directory, file.name)
        file.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(mimeType(format)), null)
        return target.toUri()
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
