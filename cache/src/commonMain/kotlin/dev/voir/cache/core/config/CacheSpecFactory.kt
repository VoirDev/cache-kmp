package dev.voir.cache.core.config

import dev.voir.cache.core.key.CacheKeyEncoder
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.policy.FreshnessPolicy
import dev.voir.cache.core.policy.MemoryPolicy
import dev.voir.cache.core.storage.CacheStorage

/**
 * Creates a typed resource specification from a string namespace and key encoder.
 *
 * @param K Domain-owned resource key type.
 * @param V Non-null cached value type.
 * @param namespace Stable resource and serialization-contract identity.
 * @param key Encoder that produces the partition and normalized key.
 * @param freshness Policy controlling source revalidation.
 * @param storage Storage tiers used by the resource.
 * @param memory Per-resource in-memory retention policy.
 * @return Validated immutable resource specification.
 */
internal fun <K : Any, V : Any> cacheSpec(
    namespace: String,
    key: CacheKeyEncoder<K>,
    freshness: FreshnessPolicy,
    storage: CacheStorage<V>,
    memory: MemoryPolicy,
): CacheSpec<K, V> = CacheSpec(
    namespace = CacheNamespace(namespace),
    keyEncoder = key,
    freshness = freshness,
    storage = storage,
    memory = memory,
)
