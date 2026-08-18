package dev.voir.cache

import android.content.Context
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.voir.cache.core.file.FileStoreLimits
import dev.voir.cache.file.FileCacheStoreConfiguration
import dev.voir.cache.file.createAndroidFileCacheStore
import dev.voir.cache.internal.bootstrapCacheManager
import dev.voir.cache.internal.closeAfterBootstrapFailure
import dev.voir.cache.room.RoomCacheStoreConfiguration
import dev.voir.cache.room.createAndroidRoomPersistentCacheStore
import kotlinx.coroutines.CoroutineScope

/**
 * Creates one Android cache manager and all of its storage under the application context.
 *
 * The Room database name, filesystem directory, drivers, recovery, and startup maintenance are
 * internal implementation details.
 */
public suspend fun CacheManager(
    context: Context,
    scope: CoroutineScope,
    options: CacheManagerOptions = CacheManagerOptions(),
): CacheManager = createAndroidCacheManager(
    context = context,
    scope = scope,
    options = options,
    persistentDriver = BundledSQLiteDriver(),
    fileDriver = BundledSQLiteDriver(),
)

/** Driver-injectable bootstrap used only by Android host tests. */
internal suspend fun createAndroidCacheManager(
    context: Context,
    scope: CoroutineScope,
    options: CacheManagerOptions,
    persistentDriver: SQLiteDriver,
    fileDriver: SQLiteDriver,
): CacheManager {
    val applicationContext = context.applicationContext ?: context
    val persistentStore = createAndroidRoomPersistentCacheStore(
        context = applicationContext,
        databaseName = DATABASE_NAME,
        configuration = RoomCacheStoreConfiguration(),
        driver = persistentDriver,
    )
    val fileStore = try {
        createAndroidFileCacheStore(
            context = applicationContext,
            directoryName = FILE_DIRECTORY_NAME,
            limits = FileStoreLimits(
                maxTotalEntries = options.fileMaxEntries,
                maxTotalBytes = options.fileMaxBytes,
            ),
            configuration = FileCacheStoreConfiguration(),
            driver = fileDriver,
        )
    } catch (failure: Throwable) {
        closeAfterBootstrapFailure(persistentStore, failure)
    }
    return bootstrapCacheManager(scope, persistentStore, fileStore, options)
}

private const val DATABASE_NAME: String = "cache-kmp.db"
private const val FILE_DIRECTORY_NAME: String = "cache-kmp"
