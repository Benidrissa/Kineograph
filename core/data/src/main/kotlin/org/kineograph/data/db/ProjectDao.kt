package org.kineograph.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
public interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updated_at DESC")
    public fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    public suspend fun get(id: String): ProjectEntity?

    @Query("SELECT id FROM projects")
    public suspend fun allIds(): List<String>

    @Query("SELECT COUNT(*) FROM projects")
    public suspend fun count(): Int

    @Upsert
    public suspend fun upsert(entity: ProjectEntity)

    @Query("UPDATE projects SET name = :name, updated_at = :updatedAt WHERE id = :id")
    public suspend fun rename(id: String, name: String, updatedAt: Long)

    @Query("UPDATE projects SET trashed_at = :trashedAt WHERE id = :id")
    public suspend fun setTrashed(id: String, trashedAt: Long?)

    @Query("SELECT id FROM projects WHERE trashed_at IS NOT NULL AND trashed_at < :before")
    public suspend fun trashedBefore(before: Long): List<String>

    @Query("DELETE FROM projects WHERE id = :id")
    public suspend fun delete(id: String)

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM projects")
    public fun observeTotalSize(): Flow<Long>
}
