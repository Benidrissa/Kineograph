package org.kineograph.media.export

import android.content.Context
import java.io.File
import java.util.UUID

/** Where finished exports wait to be saved or shared. */
internal object ExportFiles {
    private const val DIRECTORY = "exports"

    fun root(context: Context): File = File(context.cacheDir, DIRECTORY)

    /**
     * Each export has a directory of its own. Another app may still be reading an earlier export of this project through
     * the link it was given, and two projects can share a name, so a new export must never write over an old one. The
     * file itself keeps its friendly name. WorkManager keeps the id when it runs an export again, so a second attempt
     * only replaces its own first attempt.
     */
    fun directoryFor(context: Context, id: UUID): File = File(root(context), id.toString())

    /** Removes the directory of a finished export, whose only file is [file]. */
    fun delete(file: File) {
        val directory = file.parentFile
        if (directory == null || directory.name == DIRECTORY) file.delete() else directory.deleteRecursively()
    }

    /**
     * Removes everything last changed before [cutoffMs], except what is in [keep]. It goes by age so that a link already
     * given to another app keeps working for a while, even after the user has moved on.
     */
    fun prune(root: File, keep: Set<String>, cutoffMs: Long) {
        root.listFiles()?.forEach { entry ->
            // A directory's own time does not change when the file inside it grows.
            if (entry.name !in keep && entry.walkTopDown().maxOf { it.lastModified() } < cutoffMs) entry.deleteRecursively()
        }
    }
}
