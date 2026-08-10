package dev.voir.cache.core.policy

import kotlin.time.Duration

/**
 * Storage-neutral limits applied when [dev.voir.cache.core.CacheManager.prune] is invoked.
 *
 * The cache never schedules pruning itself. Hosts choose when to run maintenance, while the
 * manager uses its injected cache clock to derive the expiration cutoff.
 *
 * @property removeExpired Whether records older than the expiration cutoff should be removed.
 * @property expiredEntryGrace Time retained after expiration before a record becomes pruneable.
 * @property maxEntriesPerNamespace Optional row bound applied independently to every namespace.
 * @property maxTotalEntries Optional row bound applied to the complete persistent store.
 * @property maxTotalPayloadBytes Optional encoded-payload byte bound for the complete store.
 */
public data class PersistentRetentionPolicy public constructor(
    public val removeExpired: Boolean = true,
    public val expiredEntryGrace: Duration = Duration.ZERO,
    public val maxEntriesPerNamespace: Int? = null,
    public val maxTotalEntries: Int? = null,
    public val maxTotalPayloadBytes: Long? = null,
) {
    init {
        require(expiredEntryGrace.isFinite() && !expiredEntryGrace.isNegative()) {
            "Persistent expiredEntryGrace must be finite and non-negative."
        }
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
