package dev.voir.cache.room

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * Platform-independent execution settings for a Room persistent cache store.
 *
 * @property queryCoroutineContext Context used by Room for database queries and transactions.
 */
public data class RoomCacheStoreConfiguration public constructor(
    public val queryCoroutineContext: CoroutineContext = Dispatchers.Default,
)
