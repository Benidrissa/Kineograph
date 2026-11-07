package org.kineograph.data

import android.content.ContentResolver
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

/** Everything to do with stored projects, for screens and for the editor's autosave. */
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

    private fun manifestStore(id: String) = ManifestStore(files.projectDirectory(id))

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

    /** Creates the project on disk and in the index. */
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
            persist(project)
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
                val name = "$MEDIA_DIRECTORY/$BACKGROUND_PREFIX${newId()}.webp"
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

    /**
     * Keeps a finished picture as a project's background. Files under `media/` are not cels, so the sweep of cels never touches them. Unlike a
     * photo, this is usually flat colour with sharp edges, which lossy compression would visibly smear.
     */
    @Throws(IOException::class)
    public suspend fun saveBackgroundImage(projectId: String, image: Bitmap): Background.Image =
        withContext(io) {
            val isWebp = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            val output = ByteArrayOutputStream()
            val format = if (isWebp) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG
            if (!image.compress(format, LOSSLESS_EFFORT, output)) throw IOException("Could not encode the background")
            val name = "$MEDIA_DIRECTORY/$BACKGROUND_PREFIX${newId()}.${if (isWebp) "webp" else "png"}"
            AtomicFile.write(File(files.projectDirectory(projectId), name), output.toByteArray())
            Background.Image(name)
        }

    /** Reads a project for editing. Returns null if nothing usable is on disk. */
    public suspend fun open(id: String): OpenedProject? =
        withContext(io) {
            awaitClosed(id)
            val store = CelFileStore(files.projectDirectory(id), compressedCacheBytes)
            val loaded = manifestStore(id).load(store::exists)
            if (loaded == null) {
                store.close()
                null
            } else {
                OpenedProject(loaded, store)
            }
        }

    /** Whether a project cannot be opened only because it came from a newer version of the app. */
    public suspend fun needsNewerApp(id: String): Boolean = withContext(io) { manifestStore(id).needsNewerApp() }

    /**
     * Deletes background pictures that nothing refers to, such as one that was replaced, or one saved by an import that was then cancelled. Nothing
     * else would, and each can be as large as a whole canvas. Both manifests count, since either one could be what the project reopens from.
     */
    public suspend fun sweepBackgrounds(projectId: String, keep: Set<String>) {
        withContext(io) {
            val store = manifestStore(projectId)
            val wanted = keep + store.backgroundFiles()
            val media = File(files.projectDirectory(projectId), MEDIA_DIRECTORY)
            for (file in media.listFiles().orEmpty()) {
                val name = "$MEDIA_DIRECTORY/${file.name}"
                if (file.isFile && file.name.startsWith(BACKGROUND_PREFIX) && name !in wanted) file.delete()
            }
        }
    }

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
     * created in between cannot be mistaken for garbage.
     */
    public suspend fun sweep(projectId: String, store: CelFileStore, candidates: Set<String>, keep: Set<String>) {
        withContext(io) {
            store.delete(candidates, keep + manifestStore(projectId).filesReferencedByBackup())
        }
    }

    public suspend fun rename(id: String, name: String) {
        withContext(io) {
            awaitClosed(id)
            val store = CelFileStore(files.projectDirectory(id), MIN_STORE_CACHE)
            try {
                val loaded = manifestStore(id).load(store::exists) ?: return@withContext
                persist(loaded.project.copy(name = name, updatedAt = clock()))
            } finally {
                store.close()
            }
        }
    }

    /** Copies a whole project under a new name. Because cel files are immutable, a plain copy of the directory is enough. */
    public suspend fun duplicate(id: String, newName: String): String? =
        withContext(io) {
            awaitClosed(id)
            val store = CelFileStore(files.projectDirectory(id), MIN_STORE_CACHE)
            try {
                val loaded = manifestStore(id).load(store::exists) ?: return@withContext null
                val copyId = newId()
                val target = files.projectDirectory(copyId)
                try {
                    files.projectDirectory(id).copyRecursively(target, overwrite = true)
                    val now = clock()
                    persist(loaded.project.copy(id = copyId, name = newName, createdAt = now, updatedAt = now))
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

    public suspend fun moveToTrash(id: String) {
        withContext(io) { dao.setTrashed(id, clock()) }
    }

    public suspend fun restoreFromTrash(id: String) {
        withContext(io) { dao.setTrashed(id, null) }
    }

    /** Deletes a project for good. */
    public suspend fun deletePermanently(id: String) {
        withContext(io) {
            awaitClosed(id)
            dao.delete(id)
            files.projectDirectory(id).deleteRecursively()
        }
    }

    /**
     * Run at startup. Puts the index back in step with the files (the files win), and empties old items
     * from the trash.
     */
    public suspend fun reconcile() {
        withContext(io) {
            val onDisk = files.listProjectIds().toSet()
            val indexed = dao.allIds().toSet()
            (indexed - onDisk).forEach { dao.delete(it) }
            (onDisk - indexed).forEach { id ->
                val store = CelFileStore(files.projectDirectory(id), MIN_STORE_CACHE)
                try {
                    val loaded = manifestStore(id).load(store::exists)
                    if (loaded != null) persist(loaded.project)
                } finally {
                    store.close()
                }
            }
            dao.trashedBefore(clock() - TRASH_RETENTION_MS).forEach { deletePermanently(it) }
        }
    }

    /** Whether the app has no projects at all, the cue to add the sample on first run. */
    public suspend fun isEmpty(): Boolean = withContext(io) { dao.count() == 0 && files.listProjectIds().isEmpty() }

    /** Saves a project written outside the editor, such as the built-in sample. */
    public suspend fun saveNew(project: Project, store: CelFileStore) {
        save(project, store)
        updateThumbnail(project, store)
        store.close()
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

        /** For lossless formats, quality means how hard to try. A background is written once, so it can try a little harder than a cel. */
        const val LOSSLESS_EFFORT = 50
        const val MIN_STORE_CACHE = 1024L * 1024
        const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        const val MEDIA_DIRECTORY = "media"
        const val BACKGROUND_PREFIX = "background-"
    }
}
