package dev.voir.cache.core.config

import dev.voir.cache.core.key.CacheFamilyEncoder
import dev.voir.cache.core.key.CacheKeyEncoder
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.paging.PagedDuplicatePolicy
import dev.voir.cache.core.policy.FreshnessPolicy
import dev.voir.cache.core.policy.PagedMemoryPolicy

/** Family-aware memory-only configuration for one accumulated paged resource. */
public data class FamilyPagedCacheSpec<Q : Any, F : Any, C : Any, I : Any, ID : Any> public constructor(
    public val namespace: CacheNamespace,
    public val queryEncoder: CacheKeyEncoder<Q>,
    public val familyOf: (Q) -> F,
    public val familyEncoder: CacheFamilyEncoder<F>,
    public val itemId: (I) -> ID,
    public val duplicatePolicy: PagedDuplicatePolicy,
    public val freshness: FreshnessPolicy,
    public val memory: PagedMemoryPolicy,
)
