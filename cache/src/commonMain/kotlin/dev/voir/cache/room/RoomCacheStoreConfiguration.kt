package dev.voir.cache.room

import dev.voir.cache.file.defaultStorageCoroutineContext
import kotlin.coroutines.CoroutineContext

/**
 * Platform-independent execution settings for a Room persistent cache store.
 *
 * @property queryCoroutineContext Context used by Room for database queries and transactions.
 */
internal data class RoomCacheStoreConfiguration(
    val queryCoroutineContext: CoroutineContext = defaultStorageCoroutineContext(),
)
