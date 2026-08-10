package dev.voir.cache.core.config

import dev.voir.cache.core.key.CacheKeyEncoder
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.paging.PagedDuplicatePolicy
import dev.voir.cache.core.policy.FreshnessPolicy
import dev.voir.cache.core.policy.PagedMemoryPolicy

/** Creates one bounded memory-only paged-resource specification. */
public fun <Q : Any, C : Any, I : Any, ID : Any> pagedCacheSpec(
    namespace: String,
    query: CacheKeyEncoder<Q>,
    itemId: (I) -> ID,
    duplicatePolicy: PagedDuplicatePolicy = PagedDuplicatePolicy.REPLACE_EXISTING,
    freshness: FreshnessPolicy,
    memory: PagedMemoryPolicy,
): PagedCacheSpec<Q, C, I, ID> = PagedCacheSpec(
    namespace = CacheNamespace(namespace),
    queryEncoder = query,
    itemId = itemId,
    duplicatePolicy = duplicatePolicy,
    freshness = freshness,
    memory = memory,
)
