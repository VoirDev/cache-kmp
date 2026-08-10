package dev.voir.cache.core.persistence

import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheFamily
import kotlin.time.Instant

/**
 * Advanced storage SPI operating only on encoded addresses, payloads, and cache metadata.
 *
 * Runtime failures are isolated by the cache manager so memory and source loading remain usable.
 */
@ExperimentalCacheStoreApi
public interface PersistentCacheStore {
    /**
     * Returns the record stored at [address].
     *
     * @param address Exact encoded address to read.
     * @return Stored record, or `null` when the address is absent.
     */
    public suspend fun read(address: CacheAddress): PersistedCacheRecord?

    /**
     * Atomically inserts or replaces [record].
     *
     * @param record Complete opaque payload and metadata to store.
     */
    public suspend fun write(record: PersistedCacheRecord)

    /**
     * Marks [address] invalid without removing its payload.
     *
     * @param address Exact encoded address to invalidate.
     * @param invalidatedAt Authoritative invalidation instant shared with memory.
     */
    public suspend fun invalidate(address: CacheAddress, invalidatedAt: Instant)

    /** Marks every record in [family] under [namespace] invalid and returns affected rows. */
    public suspend fun invalidateFamily(
        namespace: CacheNamespace,
        family: EncodedCacheFamily,
        invalidatedAt: Instant,
    ): Long

    /**
     * Removes exactly [address].
     *
     * @param address Exact encoded address to remove.
     */
    public suspend fun remove(address: CacheAddress)

    /** Removes every record in [family] under [namespace] and returns affected rows. */
    public suspend fun removeFamily(
        namespace: CacheNamespace,
        family: EncodedCacheFamily,
    ): Long

    /**
     * Removes every record belonging to [namespace].
     *
     * @param namespace Resource namespace to clear without affecting sibling namespaces.
     */
    public suspend fun clearNamespace(namespace: CacheNamespace)

    /**
     * Removes every record belonging to [partition] across namespaces.
     *
     * @param partition Isolation boundary to clear globally.
     */
    public suspend fun clearPartition(partition: CachePartition)

    /** Removes all records owned by this store. */
    public suspend fun clearAll()

    /**
     * Applies persistent retention maintenance.
     *
     * @param request Expiration and row-count limits selected by the manager.
     * @return Number of rows and optional payload bytes removed.
     */
    public suspend fun prune(request: PruneRequest): PruneResult

    /** Returns a sanitized exact or estimated occupancy snapshot. */
    public suspend fun statistics(): PersistentStoreStatistics

    /** Releases resources owned by this store. This operation must be idempotent. */
    public suspend fun close()
}
