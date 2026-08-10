package dev.voir.cache.core.config

import dev.voir.cache.core.key.CacheFamilyEncoder
import dev.voir.cache.core.key.CacheKeyEncoder
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.policy.FreshnessPolicy
import dev.voir.cache.core.policy.MemoryPolicy
import dev.voir.cache.core.storage.CacheStorage

/**
 * Typed family-aware resource configuration.
 *
 * [familyOf] maps every exact key to its typed family. The encoded family partition must match the
 * exact key partition; the cache validates this whenever an address is used.
 *
 * @param K Domain-owned exact key type.
 * @param F Domain-owned family key type.
 * @param V Non-null cached value type.
 * @property namespace Stable resource and serialization-contract identity.
 * @property keyEncoder Exact-key encoder.
 * @property familyOf Extracts the typed family owning one exact key.
 * @property familyEncoder Encodes family operations into exact partitioned metadata.
 * @property freshness Source revalidation policy.
 * @property storage Storage tiers used by this resource.
 * @property memory Per-resource memory retention bound.
 */
public data class FamilyCacheSpec<K : Any, F : Any, V : Any> public constructor(
    public val namespace: CacheNamespace,
    public val keyEncoder: CacheKeyEncoder<K>,
    public val familyOf: (K) -> F,
    public val familyEncoder: CacheFamilyEncoder<F>,
    public val freshness: FreshnessPolicy,
    public val storage: CacheStorage<V>,
    public val memory: MemoryPolicy,
)
