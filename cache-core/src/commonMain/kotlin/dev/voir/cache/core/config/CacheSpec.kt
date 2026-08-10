package dev.voir.cache.core.config

import dev.voir.cache.core.key.CacheKeyEncoder
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.policy.FreshnessPolicy
import dev.voir.cache.core.policy.MemoryPolicy
import dev.voir.cache.core.storage.CacheStorage

/**
 * Immutable configuration for one typed resource family.
 *
 * @param K Domain-owned resource key type.
 * @param V Non-null cached value type.
 * @property namespace Stable resource and serialization-contract identity.
 * @property keyEncoder Encoder that isolates domain keys from cache internals.
 * @property freshness Policy controlling source revalidation.
 * @property storage Storage tiers used by the resource.
 * @property memory Per-resource in-memory retention policy.
 */
public data class CacheSpec<K : Any, V : Any> public constructor(
    public val namespace: CacheNamespace,
    public val keyEncoder: CacheKeyEncoder<K>,
    public val freshness: FreshnessPolicy,
    public val storage: CacheStorage<V>,
    public val memory: MemoryPolicy,
)

