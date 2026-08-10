package dev.voir.cache.core.diagnostics

import kotlin.time.Duration

/**
 * Optional structured details attached to a non-sensitive [CacheEvent].
 *
 * @property correlationId Manager-local opaque operation identifier.
 * @property phase Start or terminal phase.
 * @property operation Stable cache operation kind.
 * @property origin Known operation origin when applicable.
 * @property outcome Terminal classification; absent on start events.
 * @property totalDuration End-to-end duration on completion events.
 * @property sourceDuration Time spent in the consumer source loader when applicable.
 * @property persistenceDuration Time spent in a persistent backend operation when applicable.
 * @property codecDuration Time spent encoding or decoding when separately measured.
 * @property persistenceOperation Persistent sub-operation kind when applicable.
 * @property singleFlightStatus Whether source work was started or joined.
 * @property waiterCount Number of current waiters, without caller identity.
 * @property evictionCause Sanitized eviction reason when applicable.
 * @property affectedCount Sanitized number of affected rows or entries when known.
 * @property memoryEntryCount Current namespace memory occupancy when known.
 * @property memoryEntryLimit Configured namespace memory limit when known.
 * @property persistentRowCount Whole-store persistent rows when sampled by the operation.
 * @property persistentPayloadBytes Whole-store encoded payload bytes when supported.
 * @property persistentValuesExact Whether sampled persistent occupancy is exact.
 */
public data class CacheOperationDetails public constructor(
    public val correlationId: Long,
    public val phase: CacheOperationPhase,
    public val operation: CacheOperationKind,
    public val origin: CacheOperationOrigin? = null,
    public val outcome: CacheOperationOutcome? = null,
    public val totalDuration: Duration? = null,
    public val sourceDuration: Duration? = null,
    public val persistenceDuration: Duration? = null,
    public val codecDuration: Duration? = null,
    public val persistenceOperation: CachePersistenceOperation? = null,
    public val singleFlightStatus: CacheSingleFlightStatus? = null,
    public val waiterCount: Int? = null,
    public val evictionCause: CacheEvictionCause? = null,
    public val affectedCount: Long? = null,
    public val memoryEntryCount: Int? = null,
    public val memoryEntryLimit: Int? = null,
    public val persistentRowCount: Long? = null,
    public val persistentPayloadBytes: Long? = null,
    public val persistentValuesExact: Boolean? = null,
) {
    init {
        require(correlationId > 0) { "Cache correlationId must be positive." }
        require(waiterCount == null || waiterCount > 0) {
            "Cache waiterCount must be null or greater than zero."
        }
        require(affectedCount == null || affectedCount >= 0) {
            "Cache affectedCount must be null or non-negative."
        }
        require(memoryEntryCount == null || memoryEntryCount >= 0) {
            "Cache memoryEntryCount must be null or non-negative."
        }
        require(memoryEntryLimit == null || memoryEntryLimit > 0) {
            "Cache memoryEntryLimit must be null or greater than zero."
        }
        require(persistentRowCount == null || persistentRowCount >= 0) {
            "Cache persistentRowCount must be null or non-negative."
        }
        require(persistentPayloadBytes == null || persistentPayloadBytes >= 0) {
            "Cache persistentPayloadBytes must be null or non-negative."
        }
        listOf(sourceDuration, persistenceDuration, codecDuration).forEach { duration ->
            require(duration == null || !duration.isNegative()) {
                "Cache component durations must be null or non-negative."
            }
        }
        if (phase == CacheOperationPhase.STARTED) {
            require(outcome == null) { "Started cache operation must not have an outcome." }
            require(totalDuration == null) {
                "Started cache operation must not have a total duration."
            }
        } else {
            require(outcome != null) { "Completed cache operation must have an outcome." }
            require(totalDuration != null && !totalDuration.isNegative()) {
                "Completed cache operation must have a non-negative total duration."
            }
        }
    }
}
