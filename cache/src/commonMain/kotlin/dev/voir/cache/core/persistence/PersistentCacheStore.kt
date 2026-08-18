package dev.voir.cache.core.persistence

/**
 * Storage SPI operating only on encoded addresses, payloads, and cache metadata.
 *
 * Runtime failures are isolated by the cache manager so memory and source loading remain usable.
 */
internal interface PersistentCacheStore {
    /**
     * Returns the record stored at [address].
     *
     * @param address Exact encoded address to read.
     * @return Stored record, or `null` when the address is absent.
     */
    suspend fun read(address: CacheAddress): PersistedCacheRecord?

    /**
     * Atomically inserts or replaces [record].
     *
     * @param record Complete opaque payload and metadata to store.
     */
    suspend fun write(record: PersistedCacheRecord)

    /**
     * Removes exactly [address].
     *
     * @param address Exact encoded address to remove.
     */
    suspend fun remove(address: CacheAddress)

    /** Removes all records owned by this store. */
    suspend fun clearAll()

    /**
     * Applies persistent retention maintenance.
     *
     * @param request Expiration and row-count limits selected by the manager.
     * @return Number of rows and payload bytes removed.
     */
    suspend fun prune(request: PruneRequest): PruneResult

    /** Releases resources owned by this store. This operation must be idempotent. */
    suspend fun close()
}
