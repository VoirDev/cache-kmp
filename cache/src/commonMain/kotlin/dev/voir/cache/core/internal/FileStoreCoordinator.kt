package dev.voir.cache.core.internal

import dev.voir.cache.core.file.store.FileCacheStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes logical file-store changes with the corresponding in-memory revision changes. */
internal class FileStoreCoordinator(
    private val store: FileCacheStore,
) {
    private val mutex = Mutex()

    suspend fun <T> ordered(block: suspend (FileCacheStore) -> T): T =
        mutex.withLock { block(store) }
}
