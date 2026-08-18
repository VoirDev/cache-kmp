package dev.voir.cache.room.internal

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import dev.voir.cache.room.internal.dao.CacheEntryDao
import dev.voir.cache.room.internal.entity.CacheEntryEntity

/** Internal Room database containing the generic cache table. */
@Database(
    entities = [CacheEntryEntity::class],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(CacheDatabaseConstructor::class)
internal abstract class CacheDatabase : RoomDatabase() {
    /** Returns the DAO that owns all persistent cache operations. */
    abstract fun cacheEntryDao(): CacheEntryDao
}
