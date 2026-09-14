package dev.voir.cache.core.persistence

import kotlin.time.Instant

/**
 * Storage-neutral maintenance limits applied by [PersistentCacheStore.prune].
 *
 * @property expiredBefore Optional cutoff for entries whose expiration is older than this instant.
 * @property maxTotalEntries Optional row bound for the complete store.
 * @property maxTotalPayloadBytes Optional encoded-payload byte bound for the complete store.
 */
internal data class PruneRequest(
    val expiredBefore: Instant? = null,
    val maxTotalEntries: Int? = null,
    val maxTotalPayloadBytes: Long? = null,
) {
    init {
        require(maxTotalEntries == null || maxTotalEntries > 0) {
            "Persistent maxTotalEntries must be null or greater than zero."
        }
        require(maxTotalPayloadBytes == null || maxTotalPayloadBytes > 0) {
            "Persistent maxTotalPayloadBytes must be null or greater than zero."
        }
    }
}
