package dev.voir.cache.file.internal

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.voir.cache.core.file.FileStoreLimits
import dev.voir.cache.file.FileCacheStoreConfiguration

/** Applies shared Room settings and constructs the filesystem/index coordinator. */
internal fun buildRoomFileCacheStore(
    builder: RoomDatabase.Builder<FileIndexDatabase>,
    fileSystem: FileStoreFileSystem,
    limits: FileStoreLimits,
    configuration: FileCacheStoreConfiguration,
    driver: SQLiteDriver = BundledSQLiteDriver(),
): RoomFileCacheStore {
    val database = builder
        .setDriver(driver)
        .setQueryCoroutineContext(configuration.queryCoroutineContext)
        // The file index is disposable. Rebuilding it orphans content that startup recovery
        // removes, which is preferable to failing manager creation.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
    return RoomFileCacheStore(database, fileSystem, limits, configuration.clock)
}
