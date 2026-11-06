package org.kineograph.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per project, for fast listing in the gallery. The manifest on disk stays the source of truth, so
 * this table can always be rebuilt from it.
 */
@Entity(tableName = "projects")
public data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val width: Int,
    val height: Int,
    val fps: Int,
    @ColumnInfo(name = "frame_count") val frameCount: Int,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    val folder: String? = null,
    @ColumnInfo(name = "trashed_at") val trashedAt: Long? = null,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long = 0,
)
