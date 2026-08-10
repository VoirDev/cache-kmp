package dev.voir.cache.core.model

/**
 * Sanitized affected counts produced by one typed family mutation.
 *
 * @property memoryEntriesAffected Retained memory entries changed atomically.
 * @property persistentRowsAffected Backend rows changed, or `null` after backend failure.
 * @property persistenceStatus Availability of the configured persistent tier.
 */
public data class CacheFamilyMutationResult public constructor(
    public val memoryEntriesAffected: Int,
    public val persistentRowsAffected: Long?,
    public val persistenceStatus: CachePersistenceStatus,
) {
    init {
        require(memoryEntriesAffected >= 0) { "Affected memory entries must not be negative." }
        require(persistentRowsAffected == null || persistentRowsAffected >= 0) {
            "Affected persistent rows must be null or non-negative."
        }
    }
}
