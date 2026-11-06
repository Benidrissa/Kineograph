package org.kineograph.data.manifest

import org.kineograph.data.files.AtomicFile
import org.kineograph.model.Project
import java.io.File
import java.io.IOException

/** A project read from disk, and how much trouble it took. */
public data class LoadedProject(
    val project: Project,
    /** The newest manifest was unusable, so an older good one was used. */
    val recoveredFromBackup: Boolean,
    /** How many cels were dropped because their image files were missing. */
    val missingCels: Int,
)

/**
 * Saves a project's manifest crash-safely. A new revision goes through three steps:
 *
 *  1. write `manifest.tmp` completely and flush it to disk;
 *  2. rename the current `manifest.json` to `manifest.prev.json`;
 *  3. rename `manifest.tmp` to `manifest.json`.
 *
 * Each rename is atomic, so at any instant a complete manifest exists under at least one of these names, and
 * [load] tries them newest first. Cel files never change once written, which means an old manifest
 * still describes the project exactly as it was.
 */
public class ManifestStore(private val directory: File) {
    private val current get() = File(directory, CURRENT)
    private val previous get() = File(directory, PREVIOUS)
    private val temporary get() = File(directory, TEMPORARY)

    @Throws(IOException::class)
    public fun write(project: Project) {
        directory.mkdirs()
        val encoded = ManifestCodec.encode(project).toByteArray(Charsets.UTF_8)
        AtomicFile.writeDurably(temporary, encoded)
        if (current.exists()) AtomicFile.move(current, previous)
        AtomicFile.move(temporary, current)
    }

    /**
     * Reads the newest usable manifest and drops any cel whose image is gone. [celExists] checks for an image.
     * Returns null when there is nothing usable at all.
     */
    public fun load(celExists: (String) -> Boolean): LoadedProject? {
        // A temporary file only survives if a crash hit between steps 2 and 3, when it is the newest complete copy.
        val candidates = listOf(current to false, temporary to false, previous to true)
        for ((file, isBackup) in candidates) {
            val project = readOrNull(file) ?: continue
            val (kept, missing) = withoutMissingCels(project, celExists)
            return LoadedProject(kept, recoveredFromBackup = isBackup || (file === temporary), missingCels = missing)
        }
        return null
    }

    /** Whether the newest manifest was written by a newer version of the app. */
    public fun needsNewerApp(): Boolean {
        val text =
            try {
                if (current.exists()) current.readText() else return false
            } catch (_: IOException) {
                return false
            }
        return try {
            ManifestCodec.decode(text)
            false
        } catch (e: ManifestException) {
            e.message?.contains("newer version") == true
        }
    }

    /** The cel files referred to by the backup manifest, which must be kept in case it is ever needed. */
    public fun filesReferencedByBackup(): Set<String> = readOrNull(previous)?.referencedFiles().orEmpty()

    private fun readOrNull(file: File): Project? {
        if (!file.isFile) return null
        return try {
            ManifestCodec.decode(file.readText())
        } catch (_: IOException) {
            null
        } catch (_: ManifestException) {
            null
        }
    }

    private fun withoutMissingCels(project: Project, celExists: (String) -> Boolean): Pair<Project, Int> {
        val kept = project.cels.filterValues { cel -> cel.file?.let(celExists) == true }
        val missing = project.cels.size - kept.size
        return (if (missing == 0) project else project.copy(cels = kept)) to missing
    }

    private companion object {
        const val CURRENT = "manifest.json"
        const val PREVIOUS = "manifest.prev.json"
        const val TEMPORARY = "manifest.tmp"
    }
}
