package org.kineograph.data.files

import android.content.Context
import java.io.File

/** Where everything lives on disk. All of it is app-private storage. */
public class ProjectFiles(private val root: File, private val cacheRoot: File) {
    public constructor(context: Context) : this(File(context.filesDir, "projects"), context.cacheDir)

    public fun projectDirectory(projectId: String): File = File(root, safeName(projectId))

    public fun thumbnail(projectId: String): File = File(projectDirectory(projectId), THUMBNAIL_NAME)

    /** A scratch directory for one open session's undo data. */
    public fun spillDirectory(projectId: String): File = File(cacheRoot, "history/${safeName(projectId)}")

    /** Every project directory present on disk. */
    public fun listProjectIds(): List<String> = root
        .listFiles()
        ?.filter { it.isDirectory }
        ?.map { it.name }
        .orEmpty()

    /** The total size of a project's files in bytes. */
    public fun sizeOf(projectId: String): Long = projectDirectory(projectId).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Stops an identifier from ever naming a path outside the projects directory. */
    private fun safeName(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "_" }.let {
        if (it == "." ||
            it == ".."
        ) {
            "_"
        } else {
            it
        }
    }

    private companion object {
        const val THUMBNAIL_NAME = "thumb.webp"
    }
}
