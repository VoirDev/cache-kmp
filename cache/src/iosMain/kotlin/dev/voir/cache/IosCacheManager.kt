package dev.voir.cache

import dev.voir.cache.core.file.FileStoreLimits
import dev.voir.cache.file.createIosFileCacheStore
import dev.voir.cache.internal.bootstrapCacheManager
import dev.voir.cache.internal.closeAfterBootstrapFailure
import dev.voir.cache.room.createIosRoomPersistentCacheStore
import kotlinx.coroutines.CoroutineScope
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/** Creates one iOS cache manager below the application's system caches directory. */
public suspend fun CacheManager(
    scope: CoroutineScope,
    options: CacheManagerOptions = CacheManagerOptions(),
): CacheManager {
    val cachesDirectory = NSSearchPathForDirectoriesInDomains(
        directory = NSCachesDirectory,
        domainMask = NSUserDomainMask,
        expandTilde = true,
    ).firstOrNull() as? String ?: error("Could not resolve the iOS caches directory.")
    return createIosCacheManager(
        rootDirectory = "$cachesDirectory/$OWNED_DIRECTORY_NAME",
        scope = scope,
        options = options,
    )
}

/** Explicit root used only by module tests; production callers use the system caches directory. */
internal suspend fun createIosCacheManager(
    rootDirectory: String,
    scope: CoroutineScope,
    options: CacheManagerOptions,
): CacheManager {
    val fileStore = createIosFileCacheStore(
        rootDirectory = "$rootDirectory/$FILE_DIRECTORY_NAME",
        limits = FileStoreLimits(
            maxTotalEntries = options.fileMaxEntries,
            maxTotalBytes = options.fileMaxBytes,
        ),
    )
    val persistentStore = try {
        createIosRoomPersistentCacheStore("$rootDirectory/$DATABASE_FILE_NAME")
    } catch (failure: Throwable) {
        closeAfterBootstrapFailure(fileStore, failure)
    }
    return bootstrapCacheManager(scope, persistentStore, fileStore, options)
}

private const val OWNED_DIRECTORY_NAME: String = "cache-kmp"
private const val DATABASE_FILE_NAME: String = "cache.db"
private const val FILE_DIRECTORY_NAME: String = "files"
