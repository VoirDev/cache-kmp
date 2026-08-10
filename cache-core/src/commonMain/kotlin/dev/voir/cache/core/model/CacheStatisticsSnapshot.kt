package dev.voir.cache.core.model

import dev.voir.cache.core.diagnostics.CacheOperationKind
import dev.voir.cache.core.diagnostics.CacheOperationOutcome
import dev.voir.cache.core.key.CacheNamespace

/** Current and peak process-memory occupancy for one namespace. */
public data class CacheMemoryStatistics public constructor(
    public val currentEntries: Int,
    public val peakEntries: Int,
    public val configuredLimit: Int,
) {
    init {
        require(currentEntries >= 0) { "Current cache entries must not be negative." }
        require(peakEntries >= currentEntries) {
            "Peak cache entries must be greater than or equal to current entries."
        }
        require(configuredLimit > 0) { "Configured cache entry limit must be positive." }
    }
}

/**
 * Immutable manager-level aggregate derived from sanitized cache events.
 *
 * Counts use one definition for every consumer. A hit is a served memory or persistent value;
 * source work is counted separately by committed, failed, and discarded terminal outcomes.
 */
public data class CacheStatisticsSnapshot public constructor(
    public val memoryHits: Long,
    public val persistentHits: Long,
    public val misses: Long,
    public val freshSkips: Long,
    public val staleLoads: Long,
    public val forcedLoads: Long,
    public val sourceCommitted: Long,
    public val sourceFailures: Long,
    public val discardedResults: Long,
    public val singleFlightJoins: Long,
    public val evictions: Long,
    public val operationCounts: Map<CacheOperationKind, Long>,
    public val outcomeCounts: Map<CacheOperationOutcome, Long>,
    public val memory: Map<CacheNamespace, CacheMemoryStatistics>,
    public val persistentRows: Long?,
    public val persistentPayloadBytes: Long?,
    public val persistentValuesExact: Boolean?,
) {
    init {
        require(
            listOf(
                memoryHits,
                persistentHits,
                misses,
                freshSkips,
                staleLoads,
                forcedLoads,
                sourceCommitted,
                sourceFailures,
                discardedResults,
                singleFlightJoins,
                evictions,
            ).all { count -> count >= 0 }
        ) { "Cache statistics counters must not be negative." }
        require(operationCounts.values.all { count -> count >= 0 }) {
            "Cache operation counters must not be negative."
        }
        require(outcomeCounts.values.all { count -> count >= 0 }) {
            "Cache outcome counters must not be negative."
        }
        require(persistentRows == null || persistentRows >= 0) {
            "Cached persistent row count must be null or non-negative."
        }
        require(persistentPayloadBytes == null || persistentPayloadBytes >= 0) {
            "Cached persistent payload bytes must be null or non-negative."
        }
    }

    /** Served cache-tier hits divided by hits plus misses, or `null` before any lookup. */
    public val hitRate: Double?
        get() {
            val hits = memoryHits + persistentHits
            val attempts = hits + misses
            return if (attempts == 0L) null else hits.toDouble() / attempts.toDouble()
        }
}
