package dev.voir.cache.core.model

/**
 * Sanitized result of one manager-level persistent retention pass.
 *
 * @property rowsRemoved Exact number of rows removed when maintenance completed.
 * @property payloadBytesRemoved Encoded payload bytes removed when supported by the backend.
 * @property persistenceStatus Availability outcome for the persistent backend.
 */
public data class CachePruneReport public constructor(
    public val rowsRemoved: Long,
    public val payloadBytesRemoved: Long?,
    public val persistenceStatus: CachePersistenceStatus,
) {
    init {
        require(rowsRemoved >= 0) { "Pruned row count must not be negative." }
        require(payloadBytesRemoved == null || payloadBytesRemoved >= 0) {
            "Pruned payload byte count must be null or non-negative."
        }
    }
}
