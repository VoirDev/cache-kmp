package dev.voir.cache.core.policy

import kotlin.time.Duration

/**
 * Storage-neutral limits applied by manager retention maintenance.
 *
 * @property removeExpired Whether records older than the expiration cutoff should be removed.
 * @property expiredEntryGrace Time retained after expiration before a record becomes pruneable.
 * @property maxTotalEntries Optional row bound applied to the complete persistent store.
 * @property maxTotalPayloadBytes Optional encoded-payload byte bound for the complete store.
 */
internal data class PersistentRetentionPolicy(
    val removeExpired: Boolean = true,
    val expiredEntryGrace: Duration = Duration.ZERO,
    val maxTotalEntries: Int? = null,
    val maxTotalPayloadBytes: Long? = null,
) {
    init {
        require(expiredEntryGrace.isFinite() && !expiredEntryGrace.isNegative()) {
            "Persistent expiredEntryGrace must be finite and non-negative."
        }
        require(maxTotalEntries == null || maxTotalEntries > 0) {
            "Persistent maxTotalEntries must be null or greater than zero."
        }
        require(maxTotalPayloadBytes == null || maxTotalPayloadBytes > 0) {
            "Persistent maxTotalPayloadBytes must be null or greater than zero."
        }
    }
}
