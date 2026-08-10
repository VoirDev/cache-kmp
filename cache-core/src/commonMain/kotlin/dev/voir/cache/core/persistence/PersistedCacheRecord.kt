package dev.voir.cache.core.persistence

import dev.voir.cache.core.model.CacheMetadata
import kotlin.time.Instant

/**
 * Opaque value and metadata stored atomically by a [PersistentCacheStore].
 *
 * @property address Stable entry address.
 * @property payload Opaque codec-produced value bytes.
 * @property payloadVersion Positive codec format version used for [payload].
 * @property metadata Authoritative validation metadata shared with the memory tier.
 * @property writtenAt Instant at which this persistent record was produced.
 * @property lastAccessedAt Approximate persistent retention access instant.
 */
@ExperimentalCacheStoreApi
public data class PersistedCacheRecord public constructor(
    public val address: CacheAddress,
    public val payload: ByteArray,
    public val payloadVersion: Int,
    public val metadata: CacheMetadata,
    public val writtenAt: Instant,
    public val lastAccessedAt: Instant,
) {
    init {
        require(payloadVersion > 0) { "Persistent payloadVersion must be greater than zero." }
    }
}
