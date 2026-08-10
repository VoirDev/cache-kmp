package dev.voir.cache.core.persistence

import kotlin.time.Instant

/**
 * Storage-neutral maintenance limits applied by [PersistentCacheStore.prune].
 *
 * @property expiredBefore Optional cutoff for entries whose expiration is older than this instant.
 * @property maxEntriesPerNamespace Optional row bound for each namespace.
 * @property maxTotalEntries Optional row bound for the complete store.
 * @property maxTotalPayloadBytes Optional encoded-payload byte bound for the complete store.
 */
@ExperimentalCacheStoreApi
public data class PruneRequest public constructor(
    public val expiredBefore: Instant? = null,
    public val maxEntriesPerNamespace: Int? = null,
    public val maxTotalEntries: Int? = null,
    public val maxTotalPayloadBytes: Long? = null,
) {
    init {
        require(maxEntriesPerNamespace == null || maxEntriesPerNamespace > 0) {
            "Persistent maxEntriesPerNamespace must be null or greater than zero."
        }
        require(maxTotalEntries == null || maxTotalEntries > 0) {
            "Persistent maxTotalEntries must be null or greater than zero."
        }
        require(maxTotalPayloadBytes == null || maxTotalPayloadBytes > 0) {
            "Persistent maxTotalPayloadBytes must be null or greater than zero."
        }
    }
}
