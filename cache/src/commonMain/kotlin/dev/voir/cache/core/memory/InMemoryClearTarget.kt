package dev.voir.cache.core.memory

import dev.voir.cache.core.key.CacheNamespace

/** Internal type-erased clearing contract used by the memory registry. */
internal interface InMemoryClearTarget {
    /** Namespace owned exclusively by this resource store. */
    val namespace: CacheNamespace

    /** Removes every entry owned by this resource store. */
    suspend fun clearAll()
}
