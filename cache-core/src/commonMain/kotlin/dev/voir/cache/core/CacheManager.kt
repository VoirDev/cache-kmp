package dev.voir.cache.core

import dev.voir.cache.core.config.CacheSpec
import dev.voir.cache.core.config.FamilyCacheSpec
import dev.voir.cache.core.config.FamilyPagedCacheSpec
import dev.voir.cache.core.config.PagedCacheSpec
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.model.CachePruneReport
import dev.voir.cache.core.model.CacheStatisticsSnapshot
import dev.voir.cache.core.model.FetchResult
import dev.voir.cache.core.model.PersistentCacheStatistics
import dev.voir.cache.core.paging.FetchedPage
import dev.voir.cache.core.paging.PageLoadRequest
import dev.voir.cache.core.policy.PersistentRetentionPolicy

/** Owns registered resources, shared source work, memory state, and lifecycle. */
public interface CacheManager {
    /**
     * Registers one typed resource and permanently binds its source behavior.
     *
     * Only one resource may use a namespace within one manager.
     *
     * @param K Domain-owned resource key type.
     * @param V Non-null cached value type.
     * @param E Application-defined source error type.
     * @param spec Resource policies and stable address encoding.
     * @param fetch Application-owned source loader.
     * @param mapUnexpectedFailure Converts non-cancellation loader exceptions into [E].
     * @return Typed resource handle owned by this manager.
     */
    public fun <K : Any, V : Any, E : Any> resource(
        spec: CacheSpec<K, V>,
        fetch: suspend (K) -> FetchResult<V, E>,
        mapUnexpectedFailure: (Throwable) -> E,
    ): CachedResource<K, V, E>

    /** Registers one family-aware typed resource and permanently binds its source behavior. */
    public fun <K : Any, F : Any, V : Any, E : Any> resource(
        spec: FamilyCacheSpec<K, F, V>,
        fetch: suspend (K) -> FetchResult<V, E>,
        mapUnexpectedFailure: (Throwable) -> E,
    ): FamilyCachedResource<K, F, V, E>

    /** Registers one bounded memory-only accumulated paged resource. */
    public fun <Q : Any, C : Any, I : Any, ID : Any, E : Any> pagedResource(
        spec: PagedCacheSpec<Q, C, I, ID>,
        fetch: suspend (PageLoadRequest<Q, C>) -> FetchResult<FetchedPage<I, C>, E>,
        mapUnexpectedFailure: (Throwable) -> E,
    ): PagedCachedResource<Q, C, I, E>

    /** Registers one family-aware bounded memory-only accumulated paged resource. */
    public fun <Q : Any, F : Any, C : Any, I : Any, ID : Any, E : Any> pagedResource(
        spec: FamilyPagedCacheSpec<Q, F, C, I, ID>,
        fetch: suspend (PageLoadRequest<Q, C>) -> FetchResult<FetchedPage<I, C>, E>,
        mapUnexpectedFailure: (Throwable) -> E,
    ): FamilyPagedCachedResource<Q, F, C, I, E>

    /**
     * Clears one isolation partition across all registered namespaces.
     *
     * @param partition Partition to clear.
     */
    public suspend fun clear(partition: CachePartition)

    /** Clears all memory and configured persistence owned by this manager. */
    public suspend fun clearAll()

    /** Applies the manager's configured persistent retention limits. */
    public suspend fun prune(): CachePruneReport

    /** Applies an explicit one-shot persistent retention policy. */
    public suspend fun prune(policy: PersistentRetentionPolicy): CachePruneReport

    /** Returns a sanitized serialized snapshot of persistent storage occupancy. */
    public suspend fun storageStatistics(): PersistentCacheStatistics

    /** Returns an immutable aggregate of sanitized cache operation metrics. */
    public fun statistics(): CacheStatisticsSnapshot

    /**
     * Cancels manager-owned work, drains admitted commands, clears memory, and closes persistence.
     *
     * Shutdown is terminal and idempotent. Concurrent callers observe the same completion and the
     * same persistent-close failure. Once shutdown starts, every new command is rejected.
     */
    public suspend fun close()
}
