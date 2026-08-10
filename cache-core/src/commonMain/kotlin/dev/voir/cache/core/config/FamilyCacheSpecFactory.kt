package dev.voir.cache.core.config

import dev.voir.cache.core.key.CacheFamilyEncoder
import dev.voir.cache.core.key.CacheKeyEncoder
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.policy.FreshnessPolicy
import dev.voir.cache.core.policy.MemoryPolicy
import dev.voir.cache.core.storage.CacheStorage

/**
 * Creates a family-aware resource with typed exact-key and family-key boundaries.
 *
 * The cache derives family metadata for each exact key and verifies that both encoders select the
 * same partition.
 */
public fun <K : Any, F : Any, V : Any> familyCacheSpec(
    namespace: String,
    key: CacheKeyEncoder<K>,
    familyOf: (K) -> F,
    family: CacheFamilyEncoder<F>,
    freshness: FreshnessPolicy,
    storage: CacheStorage<V>,
    memory: MemoryPolicy,
): FamilyCacheSpec<K, F, V> = FamilyCacheSpec(
    namespace = CacheNamespace(namespace),
    keyEncoder = key,
    familyOf = familyOf,
    familyEncoder = family,
    freshness = freshness,
    storage = storage,
    memory = memory,
)
