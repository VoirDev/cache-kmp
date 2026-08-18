package dev.voir.cache.core.persistence

import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition

/**
 * Stable storage address for one encoded cache entry.
 *
 * @property namespace Resource and serialization-contract identity.
 * @property partition Tenant, session, or environment isolation boundary.
 * @property key Normalized identity within [partition].
 */
internal data class CacheAddress(
    val namespace: CacheNamespace,
    val partition: CachePartition,
    val key: String,
) {
    init {
        require(key.isNotBlank()) { "Persistent cache address key must not be blank." }
    }
}
