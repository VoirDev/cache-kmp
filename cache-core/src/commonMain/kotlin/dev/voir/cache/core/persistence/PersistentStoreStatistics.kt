package dev.voir.cache.core.persistence

import dev.voir.cache.core.key.CacheNamespace

/** Occupancy reported by a persistent backend for one namespace. */
@ExperimentalCacheStoreApi
public data class PersistentStoreNamespaceStatistics public constructor(
    public val rows: Long,
    public val payloadBytes: Long?,
) {
    init {
        require(rows >= 0) { "Persistent store namespace rows must not be negative." }
        require(payloadBytes == null || payloadBytes >= 0) {
            "Persistent store namespace payload bytes must be null or non-negative."
        }
    }
}

/** Storage-neutral occupancy snapshot returned by a persistent backend. */
@ExperimentalCacheStoreApi
public data class PersistentStoreStatistics public constructor(
    public val totalRows: Long,
    public val totalPayloadBytes: Long?,
    public val namespaces: Map<CacheNamespace, PersistentStoreNamespaceStatistics>,
    public val isExact: Boolean,
) {
    init {
        require(totalRows >= 0) { "Persistent store total rows must not be negative." }
        require(totalPayloadBytes == null || totalPayloadBytes >= 0) {
            "Persistent store total payload bytes must be null or non-negative."
        }
    }
}
