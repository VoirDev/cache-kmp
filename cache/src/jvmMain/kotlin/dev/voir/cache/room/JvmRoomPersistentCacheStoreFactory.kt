package dev.voir.cache.room

import androidx.room3.Room
import dev.voir.cache.core.persistence.PersistentCacheStore
import dev.voir.cache.room.internal.CacheDatabase
import dev.voir.cache.room.internal.buildRoomPersistentCacheStore
import java.nio.file.Files
import java.nio.file.Path

/**
 * Creates a JVM Room persistent cache store at an explicit absolute [databasePath].
 *
 * Missing parent directories are created. The caller owns selecting a durable application data
 * location and closing the returned store.
 *
 * @param databasePath Absolute path of the SQLite database file.
 * @param configuration Platform-independent Room execution settings.
 * @return Persistent cache store backed by the selected database.
 */
internal fun createJvmRoomPersistentCacheStore(
    databasePath: Path,
    configuration: RoomCacheStoreConfiguration = RoomCacheStoreConfiguration(),
): PersistentCacheStore {
    require(databasePath.isAbsolute) { "Room cache database path must be absolute." }
    databasePath.parent?.let { parent -> Files.createDirectories(parent) }
    return buildRoomPersistentCacheStore(
        builder = Room.databaseBuilder<CacheDatabase>(name = databasePath.toString()),
        configuration = configuration,
    )
}
