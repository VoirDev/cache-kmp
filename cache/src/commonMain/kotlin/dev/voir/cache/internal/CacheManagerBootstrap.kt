package dev.voir.cache.internal

import dev.voir.cache.CacheManager
import dev.voir.cache.CacheManagerOptions
import dev.voir.cache.core.config.CacheManagerConfig
import dev.voir.cache.core.file.FileRetentionPolicy
import dev.voir.cache.core.file.store.FileCacheStore
import dev.voir.cache.core.internal.DefaultCacheManager
import dev.voir.cache.core.persistence.PersistentCacheStore
import dev.voir.cache.core.policy.PersistentRetentionPolicy
import dev.voir.cache.core.time.SystemCacheClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Builds the unified engine and completes internal startup maintenance before exposing it. */
internal suspend fun bootstrapCacheManager(
    scope: CoroutineScope,
    persistentStore: PersistentCacheStore,
    fileStore: FileCacheStore,
    options: CacheManagerOptions,
): CacheManager {
    val engine: DefaultCacheManager = try {
        DefaultCacheManager(
            coroutineContext = scope.coroutineContext,
            persistentStore = persistentStore,
            fileStore = fileStore,
            config = CacheManagerConfig(
                persistentRetention = PersistentRetentionPolicy(
                    maxTotalEntries = options.persistentMaxEntries,
                    maxTotalPayloadBytes = options.persistentMaxBytes,
                ),
            ),
            fileRetention = FileRetentionPolicy(),
            clock = SystemCacheClock,
        )
    } catch (failure: Throwable) {
        closeAfterBootstrapFailure(persistentStore, fileStore, failure)
    }
    return try {
        engine.runMaintenance()
        CacheManager(engine)
    } catch (failure: Throwable) {
        withContext(NonCancellable) {
            try {
                engine.close()
            } catch (_: Throwable) {
                // Preserve the startup failure.
            }
        }
        throw failure
    }
}

/** Closes a partially initialized persistent store while preserving [failure]. */
internal suspend fun closeAfterBootstrapFailure(
    store: PersistentCacheStore,
    failure: Throwable,
): Nothing {
    withContext(NonCancellable) {
        try {
            store.close()
        } catch (_: Throwable) {
            // Preserve the bootstrap failure.
        }
    }
    throw failure
}

/** Closes a partially initialized file store while preserving [failure]. */
internal suspend fun closeAfterBootstrapFailure(
    store: FileCacheStore,
    failure: Throwable,
): Nothing {
    withContext(NonCancellable) {
        try {
            store.close()
        } catch (_: Throwable) {
            // Preserve the bootstrap failure.
        }
    }
    throw failure
}

/** Closes both stores when engine construction fails before ownership can be transferred. */
private suspend fun closeAfterBootstrapFailure(
    persistentStore: PersistentCacheStore,
    fileStore: FileCacheStore,
    failure: Throwable,
): Nothing {
    withContext(NonCancellable) {
        try {
            fileStore.close()
        } catch (_: Throwable) {
            // Preserve the bootstrap failure.
        }
        try {
            persistentStore.close()
        } catch (_: Throwable) {
            // Preserve the bootstrap failure.
        }
    }
    throw failure
}
