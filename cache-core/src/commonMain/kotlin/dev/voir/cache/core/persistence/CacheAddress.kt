package dev.voir.cache.core.persistence

import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheKey

/**
 * Stable storage address for one encoded cache entry.
 *
 * @property namespace Resource and serialization-contract identity.
 * @property partition Tenant, session, or environment isolation boundary.
 * @property key Normalized identity within [partition].
 * @property family Optional normalized family metadata for typed bulk operations.
 */
@ExperimentalCacheStoreApi
public data class CacheAddress public constructor(
    public val namespace: CacheNamespace,
    public val partition: CachePartition,
    public val key: String,
    public val family: String? = null,
) {
    init {
        require(key.isNotBlank()) { "Persistent cache address key must not be blank." }
        require(family == null || family.isNotBlank()) {
            "Persistent cache address family must be null or non-blank."
        }
    }

    /**
     * Converts this persistent address to its storage-neutral key portion.
     *
     * @return Encoded partition and key without the resource namespace.
     */
    public fun encodedKey(): EncodedCacheKey = EncodedCacheKey(partition, key, family)
}
