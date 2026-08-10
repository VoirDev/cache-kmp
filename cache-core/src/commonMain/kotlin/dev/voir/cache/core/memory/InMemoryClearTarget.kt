package dev.voir.cache.core.memory

import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition

/** Internal type-erased clearing contract used by the memory registry. */
internal interface InMemoryClearTarget {
    /** Namespace owned exclusively by this resource store. */
    val namespace: CacheNamespace

    /** Configured maximum retained entries for this namespace. */
    val entryLimit: Int

    /** Returns current retained entry occupancy. */
    suspend fun entryCount(): Int

    /**
     * Removes entries belonging to [partition].
     *
     * @param partition Isolation boundary to clear.
     */
    suspend fun clearPartition(partition: CachePartition)

    /** Removes every entry owned by this resource store. */
    suspend fun clearAll()
}
