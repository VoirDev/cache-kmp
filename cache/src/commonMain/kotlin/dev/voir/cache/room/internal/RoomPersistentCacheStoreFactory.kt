package dev.voir.cache.room.internal

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.voir.cache.core.persistence.PersistentCacheStore
import dev.voir.cache.room.RoomCacheStoreConfiguration

/**
 * Applies shared driver and coroutine settings to a platform-specific [builder].
 *
 * @param builder Room builder pointing at an application-selected database location.
 * @param configuration Platform-independent store settings.
 * @param driver SQLite driver used to open database connections.
 * @return Open-ready persistent cache store without exposing Room implementation types.
 */
internal fun buildRoomPersistentCacheStore(
    builder: RoomDatabase.Builder<CacheDatabase>,
    configuration: RoomCacheStoreConfiguration,
    driver: SQLiteDriver = BundledSQLiteDriver(),
): PersistentCacheStore {
    val database = builder
        .setDriver(driver)
        .setQueryCoroutineContext(configuration.queryCoroutineContext)
        // Cached values are disposable. A schema the running library cannot read is rebuilt
        // instead of failing manager creation.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
    return RoomPersistentCacheStore(database)
}
