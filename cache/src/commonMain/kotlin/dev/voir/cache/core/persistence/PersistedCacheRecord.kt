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
internal class PersistedCacheRecord(
    val address: CacheAddress,
    val payload: ByteArray,
    val payloadVersion: Int,
    val metadata: CacheMetadata,
    val writtenAt: Instant,
    val lastAccessedAt: Instant,
) {
    init {
        require(payloadVersion > 0) { "Persistent payloadVersion must be greater than zero." }
    }
}
