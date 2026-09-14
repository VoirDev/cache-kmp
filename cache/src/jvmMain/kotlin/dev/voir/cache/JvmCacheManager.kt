package dev.voir.cache

import dev.voir.cache.core.file.FileStoreLimits
import dev.voir.cache.file.createJvmFileCacheStore
import dev.voir.cache.internal.bootstrapCacheManager
import dev.voir.cache.internal.closeAfterBootstrapFailure
import dev.voir.cache.room.createJvmRoomPersistentCacheStore
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

/**
 * Creates one JVM cache manager below an application-owned absolute [rootDirectory].
 *
 * The manager owns only its internal `cache-kmp` child and never treats [rootDirectory] itself as
 * disposable cache content.
 */
public suspend fun CacheManager(
    rootDirectory: Path,
    scope: CoroutineScope,
    options: CacheManagerOptions = CacheManagerOptions(),
): CacheManager {
    require(rootDirectory.isAbsolute) { "Cache rootDirectory must be absolute." }
    val normalizedRoot = rootDirectory.normalize()
    require(normalizedRoot.parent != null && normalizedRoot.fileName != null) {
        "Cache rootDirectory must not be a filesystem root."
    }
    val ownedRoot = normalizedRoot.resolve(OWNED_DIRECTORY_NAME)
    val persistentStore = createJvmRoomPersistentCacheStore(
        ownedRoot.resolve(DATABASE_FILE_NAME),
    )
    val fileStore = try {
        createJvmFileCacheStore(
            rootDirectory = ownedRoot.resolve(FILE_DIRECTORY_NAME),
            limits = FileStoreLimits(
                maxTotalEntries = options.fileMaxEntries,
                maxTotalBytes = options.fileMaxBytes,
            ),
        )
    } catch (failure: Throwable) {
        closeAfterBootstrapFailure(persistentStore, failure)
    }
    return bootstrapCacheManager(scope, persistentStore, fileStore, options)
}

private const val OWNED_DIRECTORY_NAME: String = "cache-kmp"
private const val DATABASE_FILE_NAME: String = "cache.db"
private const val FILE_DIRECTORY_NAME: String = "files"
