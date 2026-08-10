package dev.voir.cache.core.config

import dev.voir.cache.core.key.CacheFamilyEncoder
import dev.voir.cache.core.key.CacheKeyEncoder
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.paging.PagedDuplicatePolicy
import dev.voir.cache.core.policy.FreshnessPolicy
import dev.voir.cache.core.policy.PagedMemoryPolicy

/** Creates one bounded family-aware memory-only paged-resource specification. */
public fun <Q : Any, F : Any, C : Any, I : Any, ID : Any> familyPagedCacheSpec(
    namespace: String,
    query: CacheKeyEncoder<Q>,
    familyOf: (Q) -> F,
    family: CacheFamilyEncoder<F>,
    itemId: (I) -> ID,
    duplicatePolicy: PagedDuplicatePolicy = PagedDuplicatePolicy.REPLACE_EXISTING,
    freshness: FreshnessPolicy,
    memory: PagedMemoryPolicy,
): FamilyPagedCacheSpec<Q, F, C, I, ID> = FamilyPagedCacheSpec(
    namespace = CacheNamespace(namespace),
    queryEncoder = query,
    familyOf = familyOf,
    familyEncoder = family,
    itemId = itemId,
    duplicatePolicy = duplicatePolicy,
    freshness = freshness,
    memory = memory,
)
