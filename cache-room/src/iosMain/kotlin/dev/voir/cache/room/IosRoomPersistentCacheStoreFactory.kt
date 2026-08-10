package dev.voir.cache.room

import androidx.room3.Room
import dev.voir.cache.core.persistence.ExperimentalCacheStoreApi
import dev.voir.cache.core.persistence.PersistentCacheStore
import dev.voir.cache.room.internal.CacheDatabase
import dev.voir.cache.room.internal.buildRoomPersistentCacheStore

/**
 * Creates an iOS Room persistent cache store at an explicit absolute [databasePath].
 *
 * The caller owns choosing an application documents or support directory, ensuring its parent
 * directory exists, and closing the returned store.
 *
 * @param databasePath Absolute path of the SQLite database file.
 * @param configuration Platform-independent Room execution settings.
 * @return Persistent cache store backed by the selected database.
 */
@ExperimentalCacheStoreApi
public fun createIosRoomPersistentCacheStore(
    databasePath: String,
    configuration: RoomCacheStoreConfiguration = RoomCacheStoreConfiguration(),
): PersistentCacheStore {
    require(databasePath.startsWith('/')) { "Room cache database path must be absolute." }
    return buildRoomPersistentCacheStore(
        builder = Room.databaseBuilder<CacheDatabase>(name = databasePath),
        configuration = configuration,
    )
}
