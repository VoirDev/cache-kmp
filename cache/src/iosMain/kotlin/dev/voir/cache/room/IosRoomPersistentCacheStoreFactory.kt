@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.voir.cache.room

import androidx.room3.Room
import dev.voir.cache.core.persistence.PersistentCacheStore
import dev.voir.cache.room.internal.CacheDatabase
import dev.voir.cache.room.internal.buildRoomPersistentCacheStore
import platform.Foundation.NSFileManager

/**
 * Creates an iOS Room persistent cache store at an explicit absolute [databasePath].
 *
 * The parent directory is created when missing. The caller owns choosing an application documents
 * or support directory and closing the returned store.
 *
 * @param databasePath Absolute path of the SQLite database file.
 * @param configuration Platform-independent Room execution settings.
 * @return Persistent cache store backed by the selected database.
 */
internal fun createIosRoomPersistentCacheStore(
    databasePath: String,
    configuration: RoomCacheStoreConfiguration = RoomCacheStoreConfiguration(),
): PersistentCacheStore {
    require(databasePath.startsWith('/')) { "Room cache database path must be absolute." }
    createParentDirectory(databasePath)
    return buildRoomPersistentCacheStore(
        builder = Room.databaseBuilder<CacheDatabase>(name = databasePath),
        configuration = configuration,
    )
}

/** Creates the directory that will contain the database file when it does not exist yet. */
private fun createParentDirectory(databasePath: String) {
    val parent = databasePath.substringBeforeLast('/', missingDelimiterValue = "")
    if (parent.isEmpty() || NSFileManager.defaultManager.fileExistsAtPath(parent)) return
    check(
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = parent,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    ) { "Could not create the Room cache database directory." }
}
