package dev.voir.cache.core.internal

import dev.voir.cache.core.persistence.PersistentCacheStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orders persistent operations with the memory mutations they mirror.
 *
 * @property store Encoded backend exclusively owned by the manager.
 */
internal class PersistentStoreCoordinator(
    private val store: PersistentCacheStore,
) {
    private val mutex = Mutex()

    /**
     * Runs [block] under the manager-wide persistent ordering lock.
     *
     * @param T Result type produced by [block].
     * @param block Operation that may access the configured store.
     * @return Result produced by [block].
     */
    suspend fun <T> ordered(block: suspend (PersistentCacheStore) -> T): T =
        mutex.withLock { block(store) }
}
