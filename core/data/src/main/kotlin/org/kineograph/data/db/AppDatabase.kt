package org.kineograph.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The project index. Version history lives in `core/data/schemas`. A change of schema needs a new version number and
 * a migration, tested in the data module's instrumented tests.
 */
@Database(entities = [ProjectEntity::class], version = 1, exportSchema = true)
public abstract class AppDatabase : RoomDatabase() {
    public abstract fun projects(): ProjectDao

    public companion object {
        private const val NAME = "kineograph.db"

        public fun open(context: Context): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, NAME).build()
    }
}

/** Opens the project index. This keeps Room an implementation detail of this module. */
public fun openProjectDao(context: Context): ProjectDao = AppDatabase.open(context).projects()
