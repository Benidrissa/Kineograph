package org.kineograph.data

import android.content.ContentResolver
import android.database.SQLException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.kineograph.data.db.ProjectDao
import org.kineograph.data.db.ProjectEntity
import org.kineograph.data.files.AtomicFile
import org.kineograph.data.files.CelFileStore
import org.kineograph.data.files.ProjectFiles
import org.kineograph.data.manifest.LoadedProject
import org.kineograph.data.manifest.ManifestStore
import org.kineograph.engine.compositor.FlattenOptions
import org.kineograph.engine.compositor.FrameFlattener
import org.kineograph.model.Background
import org.kineograph.model.Project
import org.kineograph.model.Timeline
import org.kineograph.model.newId
import org.kineograph.model.newProject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/** A project opened for editing, with the store holding its images. */
public class OpenedProject(public val loaded: LoadedProject, public val store: CelFileStore)

/**
 * Everything to do with stored projects, for screens and for the editor's autosave. Whatever goes wrong in storage,
 * files or index alike, reaches callers as an [IOException], so they only have one thing to handle.
 */
public class ProjectRepository(
    private val dao: ProjectDao,
    private val files: ProjectFiles,
    private val compressedCacheBytes: Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    public val projects: Flow<List<ProjectSummary>> =
        dao.observeAll().map { rows ->
            rows.map { row -> ProjectSummary.from(row, files.thumbnail(row.id).takeIf { it.isFile }) }
        }

    public val totalSizeBytes: Flow<Long> = dao.observeTotalSize()

    private val closing = ConcurrentHashMap<String, Job>()

    /** Copies being put together right now, which start-up must not take for leftovers. */
    private val copiesInProgress: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun manifestStore(id: String) = ManifestStore(files.projectDirectory(id))

    /** Runs a call on the index, which fails with an unchecked exception that nothing would otherwise expect. */
    private inline fun <T> index(call: () -> T): T =
        try {
            call()
        } catch (e: SQLException) {
            throw IOException("The project index could not be used", e)
        }

    /**
     * Reads a project from its own directory. Its manifest can name a different project: earlier builds copied a
     * project's manifests along with its files, so a duplicate's backup can carry the original's id. Saves go to
     * the directory named by the id, so the id is corrected here or they would overwrite the original.
     */
    private fun loadFromDirectory(id: String, store: CelFileStore): LoadedProject? {
        val loaded = manifestStore(id).load(store::exists) ?: return null
        return if (files.projectDirectory(loaded.project.id) == files.projectDirectory(id)) {
            loaded
        } else {
            loaded.copy(project = loaded.project.copy(id = id))
        }
    }

    /**
     * Records that an editor is saving this project and shutting down in the background. Anything that reads the project
     * waits for this, so that reopening it straight away cannot see an older state and then overwrite the newer one.
     */
    public fun trackClosing(id: String, job: Job) {
        closing[id] = job
        job.invokeOnCompletion { closing.remove(id, job) }
    }

    private suspend fun awaitClosed(id: String) {
        closing[id]?.join()
    }

    /** Creates the project on disk and in the index. If that fails, nothing is left behind, including an imported background. */
    @Throws(IOException::class)
    public suspend fun create(
        name: String,
        width: Int,
        height: Int,
        fps: Int,
        background: Background,
        layerName: String,
        id: String = newId(),
    ): Project =
        withContext(io) {
            val project = newProject(name, width, height, fps, background, layerName, now = clock(), id = id)
            try {
                persist(project)
            } catch (e: IOException) {
                files.projectDirectory(id).deleteRecursively()
                throw e
            }
            project
        }

    /**
     * Copies a picture into a project as its background, scaled down to what the canvas can show. Returns the
     * background to use, or null if the picture could not be read.
     */
    public suspend fun importBackgroundImage(
        projectId: String,
        uri: Uri,
        resolver: ContentResolver,
        canvasWidth: Int,
        canvasHeight: Int,
    ): Background.Image? =
        withContext(io) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                var sample = 1
                val longest = max(canvasWidth, canvasHeight)
                while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longest) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val image = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return@withContext null
                val output = ByteArrayOutputStream()
                image.compress(lossyFormat(), BACKGROUND_QUALITY, output)
                val name = "media/background-${newId()}.webp"
                AtomicFile.write(File(files.projectDirectory(projectId), name), output.toByteArray())
                Background.Image(name)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
        }

    /** Reads a project for editing. Returns null if nothing usable is on disk. */
    public suspend fun open(id: String): OpenedProject? =
        withContext(io) {
            awaitClosed(id)
            val store = CelFileStore(files.projectDirectory(id), compressedCacheBytes)
            val loaded = loadFromDirectory(id, store)
            if (loaded == null) {
                store.close()
                null
            } else {
                OpenedProject(loaded, store)
            }
        }

    /** Whether a project cannot be opened only because it came from a newer version of the app. */
    public suspend fun needsNewerApp(id: String): Boolean = withContext(io) { manifestStore(id).needsNewerApp() }

    /** Makes a project durable: images first, then the manifest that refers to them, then the index. */
    @Throws(IOException::class)
    public suspend fun save(project: Project, store: CelFileStore) {
        withContext(io) {
            store.awaitWrites()
            persist(project.copy(updatedAt = clock()))
        }
    }

    private suspend fun persist(project: Project) {
        manifestStore(project.id).write(project)
        updateIndex(project)
    }

    private suspend fun updateIndex(project: Project) {
        index {
            val previous = dao.get(project.id)
            dao.upsert(
                ProjectEntity(
                    id = project.id,
                    name = project.name,
                    createdAt = project.createdAt,
                    updatedAt = project.updatedAt,
                    width = project.width,
                    height = project.height,
                    fps = project.fps,
                    frameCount = project.frames.size,
                    durationMs = Timeline.durationMs(project.frames, project.fps),
                    folder = previous?.folder,
                    trashedAt = previous?.trashedAt,
                    sizeBytes = files.sizeOf(project.id),
                ),
            )
        }
    }

    /** Draws the thumbnail shown in the gallery, from the first frame that has something on it. */
    public suspend fun updateThumbnail(project: Project, store: CelFileStore) {
        withContext(io) {
            try {
                val flattener = FrameFlattener(store)
                val frame =
                    project.frames.firstOrNull { flattener.hasContent(project, it.id, FlattenOptions()) } ?: project.frames.first()
                val scale = THUMBNAIL_EDGE.toFloat() / max(project.width, project.height)
                val width = (project.width * scale).toInt().coerceAtLeast(1)
                val height = (project.height * scale).toInt().coerceAtLeast(1)
                val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val backgroundImage = (project.background as? Background.Image)?.let { store.read(it.file) }
                flattener.flatten(project, frame.id, Canvas(image), scale, backgroundImage, FlattenOptions(forEditor = true))
                val output = ByteArrayOutputStream()
                image.compress(lossyFormat(), THUMBNAIL_QUALITY, output)
                AtomicFile.write(files.thumbnail(project.id), output.toByteArray())
            } catch (_: IOException) {
                // A thumbnail is a nicety: the project itself is safe.
            } catch (_: OutOfMemoryError) {
                // Same as above.
            }
        }
    }

    /**
     * Deletes cel files no longer needed. [candidates] must be listed before [keep] is worked out, so a file
     * created in between cannot be mistaken for garbage. Files any manifest on disk refers to always stay, whatever
     * [keep] says: the session moves on as soon as it is edited, but until the next save those manifests are all that
     * reopening the project can use. If a manifest cannot be read, nothing is deleted, as it could need anything.
     */
    public suspend fun sweep(projectId: String, store: CelFileStore, candidates: Set<String>, keep: Set<String>) {
        withContext(io) {
            val saved = manifestStore(projectId).filesReferencedByManifests() ?: return@withContext
            store.delete(candidates, keep + saved)
        }
    }

    @Throws(IOException::class)
    public suspend fun rename(id: String, name: String) {
        withContext(io) {
            awaitClosed(id)
            val store = CelFileStore(files.projectDirectory(id), MIN_STORE_CACHE)
            try {
                val loaded = loadFromDirectory(id, store) ?: return@withContext
                persist(loaded.project.copy(name = name, updatedAt = clock()))
            } finally {
                store.close()
            }
        }
    }

    /**
     * Copies a whole project under a new name. Because cel files are immutable, a plain copy of its files is enough. The
     * copy is put together elsewhere with a manifest of its own, then appears in one rename. Before that, a process death
     * can only leave a staging directory, never something that looks like a project. Returns null if it failed.
     */
    public suspend fun duplicate(id: String, newName: String): String? =
        withContext(io) {
            awaitClosed(id)
            val store = CelFileStore(files.projectDirectory(id), MIN_STORE_CACHE)
            try {
                val loaded = loadFromDirectory(id, store) ?: return@withContext null
                val copyId = newId()
                val now = clock()
                val copy = loaded.project.copy(id = copyId, name = newName, createdAt = now, updatedAt = now)
                val target = files.projectDirectory(copyId)
                try {
                    publishCopy(id, copy)
                    updateIndex(copy)
                    copyId
                } catch (e: IOException) {
                    target.deleteRecursively()
                    throw e
                }
            } catch (_: IOException) {
                null
            } finally {
                store.close()
            }
        }

    private fun publishCopy(sourceId: String, copy: Project) {
        val staging = files.stagingDirectory(copy.id)
        // Noted before the directory exists, so start-up can never see the directory without this.
        copiesInProgress += copy.id
        try {
            copyFilesExceptManifests(files.projectDirectory(sourceId), staging)
            ManifestStore(staging).write(copy)
            files.projectDirectory(copy.id).parentFile?.mkdirs()
            AtomicFile.move(staging, files.projectDirectory(copy.id))
        } catch (e: IOException) {
            staging.deleteRecursively()
            throw e
        } finally {
            copiesInProgress -= copy.id
        }
    }

    /** Copying the source's manifests would put a project on disk that claims to be the original. */
    private fun copyFilesExceptManifests(source: File, target: File) {
        target.mkdirs()
        source.listFiles().orEmpty().filterNot { ManifestStore.isManifestFile(it.name) }.forEach { entry ->
            entry.copyRecursively(File(target, entry.name), overwrite = true)
        }
    }

    @Throws(IOException::class)
    public suspend fun moveToTrash(id: String) {
        withContext(io) { index { dao.setTrashed(id, clock()) } }
    }

    @Throws(IOException::class)
    public suspend fun restoreFromTrash(id: String) {
        withContext(io) { index { dao.setTrashed(id, null) } }
    }

    /**
     * Deletes a project for good. The files go first, and a row the index cannot drop is left for the next start-up to
     * remove: on a full disk the database may be unable to write, and deleting is how the user gets the space back.
     */
    public suspend fun deletePermanently(id: String) {
        withContext(io) {
            awaitClosed(id)
            files.projectDirectory(id).deleteRecursively()
            try {
                index { dao.delete(id) }
            } catch (_: IOException) {
                // See above: reconcile removes a row whose files have gone.
            }
        }
    }

    /**
     * Run at startup. Puts the index back in step with the files (the files win), and empties old items
     * from the trash. One project's trouble does not stop the rest: whatever fails is left for the next start-up.
     */
    @Throws(IOException::class)
    public suspend fun reconcile() {
        withContext(io) {
            files.listStagingIds().filterNot(copiesInProgress::contains).forEach { files.stagingDirectory(it).deleteRecursively() }
            // The index is read first. A row implies its files were already there, so a project being created right now
            // cannot be taken for a row with no files.
            val indexed = index { dao.allIds() }.toSet()
            val onDisk = files.listProjectIds().toSet()
            (indexed - onDisk).forEach { index { dao.delete(it) } }
            (onDisk - indexed).forEach { id ->
                try {
                    adoptDirectory(id)
                } catch (_: IOException) {
                    // Tried again at the next start-up.
                }
            }
            index { dao.trashedBefore(clock() - TRASH_RETENTION_MS) }.forEach { deletePermanently(it) }
        }
    }

    /** Deals with a directory that has no row in the index. */
    private suspend fun adoptDirectory(directoryName: String) {
        val store = CelFileStore(files.projectDirectory(directoryName), MIN_STORE_CACHE)
        try {
            val loaded = manifestStore(directoryName).load(store::exists)
            when {
                loaded == null -> {
                    // Nothing ever finished being saved here. A damaged or newer manifest does not count: it can still mean something.
                    if (!manifestStore(directoryName).hasManifest() && isAbandoned(directoryName)) {
                        files.projectDirectory(directoryName).deleteRecursively()
                    }
                }

                files.projectDirectory(loaded.project.id) == files.projectDirectory(directoryName) -> {
                    // Only the index was missing, so the manifests stay as they are.
                    updateIndex(loaded.project)
                }

                manifestStore(loaded.project.id).load { true } != null -> {
                    // A copy cut short by an earlier build that copied manifests. The original is still there and this adds nothing.
                    files.projectDirectory(directoryName).deleteRecursively()
                }

                else -> {
                    // The original has gone, so this may be all that is left of it.
                    persist(loaded.project.copy(id = directoryName))
                }
            }
        } finally {
            store.close()
        }
    }

    /** Files can arrive before a project's first manifest. Waiting a while means this cannot catch a project being created. */
    private fun isAbandoned(directoryName: String): Boolean {
        val latest = files.projectDirectory(directoryName).walkTopDown().maxOfOrNull { it.lastModified() } ?: return true
        return clock() - latest > ORPHAN_AGE_MS
    }

    /**
     * Whether the app has no projects at all, the cue to add the sample on first run. A directory that never got a
     * manifest does not count, so a first launch cut short before the sample was finished can try again.
     */
    @Throws(IOException::class)
    public suspend fun isEmpty(): Boolean =
        withContext(io) { index { dao.count() } == 0 && files.listProjectIds().none { manifestStore(it).hasManifest() } }

    /** Saves a project written outside the editor, such as the built-in sample. */
    @Throws(IOException::class)
    public suspend fun saveNew(project: Project, store: CelFileStore) {
        try {
            save(project, store)
            updateThumbnail(project, store)
        } finally {
            store.close()
        }
    }

    /** Removes the files of a new project that could not be finished. */
    public suspend fun deleteFiles(id: String) {
        withContext(io) { files.projectDirectory(id).deleteRecursively() }
    }

    /** Creates a store for building a brand-new project's files. */
    public fun storeFor(id: String): CelFileStore = CelFileStore(files.projectDirectory(id), MIN_STORE_CACHE)

    @Suppress("DEPRECATION")
    private fun lossyFormat(): Bitmap.CompressFormat = if (Build.VERSION.SDK_INT >=
        Build.VERSION_CODES.R
    ) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        Bitmap.CompressFormat.WEBP
    }

    private companion object {
        const val THUMBNAIL_EDGE = 480
        const val THUMBNAIL_QUALITY = 80
        const val BACKGROUND_QUALITY = 90
        const val MIN_STORE_CACHE = 1024L * 1024
        const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        const val ORPHAN_AGE_MS = 60L * 60 * 1000
    }
}
