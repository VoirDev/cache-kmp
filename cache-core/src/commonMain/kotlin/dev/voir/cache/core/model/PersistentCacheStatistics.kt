package dev.voir.cache.core.model

import dev.voir.cache.core.key.CacheNamespace
import kotlin.time.Instant

/** Persistent occupancy for one resource namespace. */
public data class PersistentNamespaceStatistics public constructor(
    public val rows: Long,
    public val payloadBytes: Long?,
) {
    init {
        require(rows >= 0) { "Persistent namespace row count must not be negative." }
        require(payloadBytes == null || payloadBytes >= 0) {
            "Persistent namespace payload bytes must be null or non-negative."
        }
    }
}

/**
 * Sanitized manager-level persistent storage snapshot.
 *
 * Namespace names are public resource identities. Raw partitions, encoded keys, payloads, and
 * backend errors are never included.
 *
 * @property totalRows Total persistent rows, or `null` when the backend was unavailable.
 * @property totalPayloadBytes Total encoded payload bytes when available from the backend.
 * @property namespaces Occupancy grouped by public cache namespace.
 * @property capturedAt Cache-clock instant at which the serialized snapshot completed.
 * @property isExact Whether the backend reports exact rather than estimated values.
 * @property persistenceStatus Availability outcome for the persistent backend.
 */
public data class PersistentCacheStatistics public constructor(
    public val totalRows: Long?,
    public val totalPayloadBytes: Long?,
    public val namespaces: Map<CacheNamespace, PersistentNamespaceStatistics>,
    public val capturedAt: Instant,
    public val isExact: Boolean,
    public val persistenceStatus: CachePersistenceStatus,
) {
    init {
        require(totalRows == null || totalRows >= 0) {
            "Persistent total row count must be null or non-negative."
        }
        require(totalPayloadBytes == null || totalPayloadBytes >= 0) {
            "Persistent total payload bytes must be null or non-negative."
        }
        require(persistenceStatus != CachePersistenceStatus.AVAILABLE || totalRows != null) {
            "Available persistent statistics must include a total row count."
        }
    }
}
