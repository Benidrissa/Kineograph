package org.kineograph.data

import org.kineograph.data.db.ProjectEntity
import java.io.File

/** What the gallery shows about a project. */
public data class ProjectSummary(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val width: Int,
    val height: Int,
    val fps: Int,
    val frameCount: Int,
    val durationMs: Long,
    val folder: String?,
    val isTrashed: Boolean,
    val sizeBytes: Long,
    val thumbnail: File?,
) {
    internal companion object {
        fun from(entity: ProjectEntity, thumbnail: File?): ProjectSummary =
            ProjectSummary(
                id = entity.id,
                name = entity.name,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
                width = entity.width,
                height = entity.height,
                fps = entity.fps,
                frameCount = entity.frameCount,
                durationMs = entity.durationMs,
                folder = entity.folder,
                isTrashed = entity.trashedAt != null,
                sizeBytes = entity.sizeBytes,
                thumbnail = thumbnail,
            )
    }
}
