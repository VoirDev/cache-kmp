package dev.voir.cache.core.internal

import dev.voir.cache.core.diagnostics.CacheEvent
import dev.voir.cache.core.diagnostics.CacheEventType
import dev.voir.cache.core.diagnostics.CacheOperationKind
import dev.voir.cache.core.diagnostics.CacheOperationOutcome
import dev.voir.cache.core.diagnostics.CacheOperationPhase
import dev.voir.cache.core.diagnostics.CacheSingleFlightStatus
import dev.voir.cache.core.model.CacheMemoryStatistics
import dev.voir.cache.core.model.CacheStatisticsSnapshot
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Lock-free immutable reducer for manager-level diagnostic aggregates. */
@OptIn(ExperimentalAtomicApi::class)
internal class CacheStatisticsReducer {
    private val state = AtomicReference(emptySnapshot())

    /** Applies one sanitized event without invoking consumer code. */
    fun record(event: CacheEvent) {
        while (true) {
            val current = state.load()
            val updated = current.record(event)
            if (state.compareAndSet(current, updated)) return
        }
    }

    /** Returns the current immutable aggregate. */
    fun snapshot(): CacheStatisticsSnapshot = state.load()

    private fun CacheStatisticsSnapshot.record(event: CacheEvent): CacheStatisticsSnapshot {
        var next = when (event.type) {
            CacheEventType.MEMORY_HIT -> copy(memoryHits = memoryHits + 1)
            CacheEventType.PERSISTENT_HIT -> copy(persistentHits = persistentHits + 1)
            CacheEventType.MISS -> copy(misses = misses + 1)
            CacheEventType.FRESH_SKIP -> copy(freshSkips = freshSkips + 1)
            CacheEventType.STALE_REFRESH -> copy(staleLoads = staleLoads + 1)
            CacheEventType.FORCED_REFRESH -> copy(forcedLoads = forcedLoads + 1)
            CacheEventType.SINGLE_FLIGHT_JOIN -> copy(singleFlightJoins = singleFlightJoins + 1)
            CacheEventType.EVICTION, CacheEventType.CORRUPTION_EVICTION ->
                copy(evictions = evictions + 1)
            else -> this
        }
        val details = event.operation ?: return next
        if (details.phase != CacheOperationPhase.COMPLETED) return next

        val operations = next.operationCounts.increment(details.operation)
        val outcome = checkNotNull(details.outcome)
        val outcomes = next.outcomeCounts.increment(outcome)
        next = next.copy(
            sourceCommitted = next.sourceCommitted +
                if (
                    details.operation.isSourceOperation() &&
                    details.singleFlightStatus != CacheSingleFlightStatus.JOINED &&
                    (
                        outcome == CacheOperationOutcome.COMMITTED ||
                            outcome == CacheOperationOutcome.PERSISTENCE_DEGRADED_SUCCESS
                    )
                ) 1 else 0,
            sourceFailures = next.sourceFailures +
                if (
                    details.singleFlightStatus != CacheSingleFlightStatus.JOINED &&
                    outcome == CacheOperationOutcome.SOURCE_FAILURE
                ) 1 else 0,
            discardedResults = next.discardedResults +
                if (
                    details.singleFlightStatus != CacheSingleFlightStatus.JOINED &&
                    outcome == CacheOperationOutcome.DISCARDED
                ) 1 else 0,
            evictions = next.evictions +
                if (details.operation == CacheOperationKind.PRUNE) {
                    details.affectedCount ?: 0L
                } else {
                    0L
                },
            operationCounts = operations,
            outcomeCounts = outcomes,
        )

        val namespace = event.namespace
        val entryCount = details.memoryEntryCount
        val entryLimit = details.memoryEntryLimit
        if (namespace != null && entryCount != null && entryLimit != null) {
            val previous = next.memory[namespace]
            val occupancy = CacheMemoryStatistics(
                currentEntries = entryCount,
                peakEntries = maxOf(previous?.peakEntries ?: 0, entryCount),
                configuredLimit = entryLimit,
            )
            next = next.copy(memory = next.memory + (namespace to occupancy))
        }
        if (details.persistentRowCount != null) {
            next = next.copy(
                persistentRows = details.persistentRowCount,
                persistentPayloadBytes = details.persistentPayloadBytes,
                persistentValuesExact = details.persistentValuesExact,
            )
        }
        return next
    }

    private fun CacheOperationKind.isSourceOperation(): Boolean =
        this == CacheOperationKind.GET ||
            this == CacheOperationKind.REFRESH ||
            this == CacheOperationKind.PAGED_ENSURE_LOADED ||
            this == CacheOperationKind.PAGED_REFRESH ||
            this == CacheOperationKind.PAGED_LOAD_NEXT

    private fun <K> Map<K, Long>.increment(key: K): Map<K, Long> =
        this + (key to (getOrElse(key) { 0L } + 1L))

    private companion object {
        fun emptySnapshot(): CacheStatisticsSnapshot = CacheStatisticsSnapshot(
            memoryHits = 0,
            persistentHits = 0,
            misses = 0,
            freshSkips = 0,
            staleLoads = 0,
            forcedLoads = 0,
            sourceCommitted = 0,
            sourceFailures = 0,
            discardedResults = 0,
            singleFlightJoins = 0,
            evictions = 0,
            operationCounts = emptyMap(),
            outcomeCounts = emptyMap(),
            memory = emptyMap(),
            persistentRows = null,
            persistentPayloadBytes = null,
            persistentValuesExact = null,
        )
    }
}
