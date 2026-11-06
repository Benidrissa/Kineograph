package org.kineograph.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.kineograph.data.db.AppDatabase

/**
 * Checks the stored schema. Version 1 is the first, so there is no migration to run yet; each new version must add a
 * test here that opens the previous version with its data and migrates it.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
        )

    @Test
    fun versionOneHoldsAProject() {
        helper.createDatabase(DATABASE, 1).use { db ->
            db.execSQL(
                "INSERT INTO projects " +
                    "(id, name, created_at, updated_at, width, height, fps, frame_count, duration_ms, folder, trashed_at, size_bytes) " +
                    "VALUES ('a', 'Ball', 1, 2, 1280, 720, 12, 12, 1000, NULL, NULL, 0)",
            )
            db.query("SELECT name, fps FROM projects WHERE id = 'a'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("Ball", cursor.getString(0))
                assertEquals(12, cursor.getInt(1))
            }
        }
    }

    private companion object {
        const val DATABASE = "migration-test"
    }
}
