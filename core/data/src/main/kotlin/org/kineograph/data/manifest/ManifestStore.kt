package org.kineograph.data.manifest

import org.kineograph.data.files.AtomicFile
import org.kineograph.model.Background
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
        // After a crash between steps 2 and 3 the temporary file is the newest revision, so it must not be written over.
        if (!current.exists() && readOrNull(temporary) != null) AtomicFile.move(temporary, current)
        try {
            AtomicFile.writeDurably(temporary, encoded)
        } catch (e: IOException) {
            // A half-written leftover would hold on to the very space a full disk is short of.
            temporary.delete()
            throw e
        }
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
            if (!file.isFile) continue
            val project =
                try {
                    ManifestCodec.decode(file.readText())
                } catch (_: IOException) {
                    continue
                } catch (e: ManifestException) {
                    // A newer app's save is not damage. Going back to an older revision would show an older drawing, and the next
                    // saves would push the newer one out for good.
                    if (!isBackup && e.isFromNewerApp) return null
                    continue
                }
            val (kept, missing) = withoutMissingCels(project, celExists)
            return LoadedProject(kept, recoveredFromBackup = isBackup, missingCels = missing)
        }
        return null
    }

    /** Whether a manifest was written by a newer version of the app, which is why [load] may have found nothing to open. */
    public fun needsNewerApp(): Boolean = listOf(current, temporary, previous).any(::isFromNewerApp)

    private fun isFromNewerApp(file: File): Boolean {
        if (!file.isFile) return false
        return try {
            ManifestCodec.decode(file.readText())
            false
        } catch (_: IOException) {
            false
        } catch (e: ManifestException) {
            e.isFromNewerApp
        }
    }

    private val ManifestException.isFromNewerApp get() = message?.contains("newer version") == true

    /**
     * The cel files that any revision on disk refers to, all of which [load] might need, so none may be deleted. Returns
     * null if that cannot be known because a revision could not be read.
     */
    public fun filesReferencedByManifests(): Set<String>? = revisions()?.flatMapTo(HashSet()) { it.referencedFiles() }

    /** The background pictures that any revision on disk shows, on the same terms as [filesReferencedByManifests]. */
    public fun backgroundFiles(): Set<String>? = revisions()?.mapNotNullTo(HashSet()) { (it.background as? Background.Image)?.file }

    /**
     * Everything [load] could end up with, or null if a revision could not be read and so could need anything. Temporary files are
     * left out of that rule: an unreadable one is just an unfinished write that nothing could ever load.
     */
    private fun revisions(): List<Project>? {
        val found = ArrayList<Project>(REVISION_FILES)
        for (file in listOf(current, previous)) {
            if (file.isFile) found += readOrNull(file) ?: return null
        }
        readOrNull(temporary)?.let { found += it }
        return found
    }

    /** Whether any manifest exists that could still mean something. A damaged one counts, but an unfinished first write does not. */
    public fun hasManifest(): Boolean = current.exists() || previous.exists() || readOrNull(temporary) != null || isFromNewerApp(temporary)

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

    public companion object {
        private const val CURRENT = "manifest.json"
        private const val PREVIOUS = "manifest.prev.json"
        private const val TEMPORARY = "manifest.tmp"
        private const val REVISION_FILES = 3

        /** Whether a file directly inside a project's directory is one of its manifest revisions. */
        public fun isManifestFile(name: String): Boolean = name == CURRENT || name == PREVIOUS || name == TEMPORARY
    }
}
