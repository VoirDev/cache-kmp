package dev.voir.cache.core.config

import dev.voir.cache.core.key.CacheKeyEncoder
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.paging.PagedDuplicatePolicy
import dev.voir.cache.core.policy.FreshnessPolicy
import dev.voir.cache.core.policy.PagedMemoryPolicy

/** Memory-only configuration for one accumulated paged resource. */
public data class PagedCacheSpec<Q : Any, C : Any, I : Any, ID : Any> public constructor(
    public val namespace: CacheNamespace,
    public val queryEncoder: CacheKeyEncoder<Q>,
    public val itemId: (I) -> ID,
    public val duplicatePolicy: PagedDuplicatePolicy,
    public val freshness: FreshnessPolicy,
    public val memory: PagedMemoryPolicy,
)
