package dev.voir.cache.file.internal

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import dev.voir.cache.file.internal.dao.FileEntryDao
import dev.voir.cache.file.internal.entity.FileEntryEntity

/** Durable logical index; file content is intentionally excluded from this database. */
@Database(
    entities = [FileEntryEntity::class],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(FileIndexDatabaseConstructor::class)
internal abstract class FileIndexDatabase : RoomDatabase() {
    abstract fun fileEntryDao(): FileEntryDao
}
