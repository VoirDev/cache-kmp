package dev.voir.cache.core.config

import dev.voir.cache.core.key.CacheKeyEncoder
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.paging.PagedDuplicatePolicy
import dev.voir.cache.core.policy.FreshnessPolicy
import dev.voir.cache.core.policy.PagedMemoryPolicy

/**
 * Memory-only configuration for one accumulated paged resource.
 *
 * @param Q Domain-owned query type.
 * @param I Non-null accumulated item type.
 * @property namespace Stable resource identity.
 * @property queryEncoder Encoder producing the partition and normalized query identity.
 * @property itemId Stable identity used to deduplicate accumulated items.
 * @property duplicatePolicy Behavior when an incoming item repeats a known identity.
 * @property freshness Policy controlling revalidation of accumulated state.
 * @property memory Finite accumulation bounds.
 */
internal data class PagedCacheSpec<Q : Any, I : Any>(
    val namespace: CacheNamespace,
    val queryEncoder: CacheKeyEncoder<Q>,
    val itemId: (I) -> Any,
    val duplicatePolicy: PagedDuplicatePolicy,
    val freshness: FreshnessPolicy,
    val memory: PagedMemoryPolicy,
)

/** Creates one paged resource specification from a stable string namespace. */
internal fun <Q : Any, I : Any> pagedCacheSpec(
    namespace: String,
    query: CacheKeyEncoder<Q>,
    itemId: (I) -> Any,
    duplicatePolicy: PagedDuplicatePolicy,
    freshness: FreshnessPolicy,
    memory: PagedMemoryPolicy,
): PagedCacheSpec<Q, I> = PagedCacheSpec(
    namespace = CacheNamespace(namespace),
    queryEncoder = query,
    itemId = itemId,
    duplicatePolicy = duplicatePolicy,
    freshness = freshness,
    memory = memory,
)
