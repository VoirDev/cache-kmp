package dev.voir.cache.core.internal

import dev.voir.cache.core.diagnostics.CacheEvent
import dev.voir.cache.core.diagnostics.CacheEventListener
import dev.voir.cache.core.diagnostics.CacheEventType
import dev.voir.cache.core.diagnostics.CacheEvictionCause
import dev.voir.cache.core.diagnostics.CacheOperationDetails
import dev.voir.cache.core.diagnostics.CacheOperationKind
import dev.voir.cache.core.diagnostics.CacheOperationOrigin
import dev.voir.cache.core.diagnostics.CacheOperationOutcome
import dev.voir.cache.core.diagnostics.CacheOperationPhase
import dev.voir.cache.core.diagnostics.CachePersistenceOperation
import dev.voir.cache.core.diagnostics.CacheSingleFlightStatus
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.model.CacheStatisticsSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Non-blocking ordered diagnostic delivery and manager-level sanitized statistics reduction.
 *
 * Events are reduced synchronously through immutable atomics, then offered to one unbounded channel
 * consumed outside cache operation locks. Listener latency and failures cannot change cache state.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class CacheEventEmitter(
    coroutineContext: CoroutineContext,
    private val listener: CacheEventListener?,
    private val fingerprinter: CacheKeyFingerprinter,
) {
    private val publicationGate = AtomicBoolean(false)
    private val sequence = AtomicLong(0L)
    private val correlation = AtomicLong(0L)
    private val reducer = CacheStatisticsReducer()
    private val deliveryJob = SupervisorJob(coroutineContext[Job])
    private val deliveryScope = CoroutineScope(coroutineContext + deliveryJob)
    private val channel = Channel<CacheEvent>(Channel.UNLIMITED)
    private val worker = listener?.let { target ->
        deliveryScope.launch {
            for (event in channel) {
                runCatching { target.onEvent(event) }
            }
        }
    }

    /** Emits one legacy categorical event with privacy-safe address fingerprinting. */
    fun emit(
        type: CacheEventType,
        namespace: CacheNamespace? = null,
        key: EncodedCacheKey? = null,
    ) {
        publish(
            CacheEvent(
                type = type,
                namespace = namespace,
                keyFingerprint = key?.let(fingerprinter::fingerprint),
            )
        )
    }

    /** Starts one correlated structured operation and returns its opaque internal token. */
    fun start(
        operation: CacheOperationKind,
        namespace: CacheNamespace? = null,
        key: EncodedCacheKey? = null,
        origin: CacheOperationOrigin? = null,
        persistenceOperation: CachePersistenceOperation? = null,
        singleFlightStatus: CacheSingleFlightStatus? = null,
        waiterCount: Int? = null,
    ): CacheOperationToken {
        val token = CacheOperationToken(
            correlationId = next(correlation),
            operation = operation,
            namespace = namespace,
            key = key,
            origin = origin,
            persistenceOperation = persistenceOperation,
            started = TimeSource.Monotonic.markNow(),
        )
        publish(
            token.event(
                type = CacheEventType.OPERATION_STARTED,
                phase = CacheOperationPhase.STARTED,
                singleFlightStatus = singleFlightStatus,
                waiterCount = waiterCount,
            )
        )
        return token
    }

    /** Completes one correlated operation with sanitized timing and occupancy fields. */
    fun complete(
        token: CacheOperationToken,
        outcome: CacheOperationOutcome,
        sourceDuration: Duration? = null,
        persistenceDuration: Duration? = null,
        codecDuration: Duration? = null,
        singleFlightStatus: CacheSingleFlightStatus? = null,
        waiterCount: Int? = null,
        evictionCause: CacheEvictionCause? = null,
        affectedCount: Long? = null,
        memoryEntryCount: Int? = null,
        memoryEntryLimit: Int? = null,
        persistentRowCount: Long? = null,
        persistentPayloadBytes: Long? = null,
        persistentValuesExact: Boolean? = null,
    ) {
        publish(
            token.event(
                type = CacheEventType.OPERATION_COMPLETED,
                phase = CacheOperationPhase.COMPLETED,
                outcome = outcome,
                totalDuration = token.started.elapsedNow(),
                sourceDuration = sourceDuration,
                persistenceDuration = persistenceDuration,
                codecDuration = codecDuration,
                singleFlightStatus = singleFlightStatus,
                waiterCount = waiterCount,
                evictionCause = evictionCause,
                affectedCount = affectedCount,
                memoryEntryCount = memoryEntryCount,
                memoryEntryLimit = memoryEntryLimit,
                persistentRowCount = persistentRowCount,
                persistentPayloadBytes = persistentPayloadBytes,
                persistentValuesExact = persistentValuesExact,
            )
        )
    }

    /** Returns the current immutable manager-level metrics aggregate. */
    fun statistics(): CacheStatisticsSnapshot = reducer.snapshot()

    /** Emits categorical and structured memory-LRU eviction diagnostics. */
    fun memoryEviction(
        namespace: CacheNamespace,
        key: EncodedCacheKey,
        remainingEntries: Int,
        configuredLimit: Int,
    ) {
        emit(CacheEventType.EVICTION, namespace, key)
        val operation = start(
            operation = CacheOperationKind.EVICTION,
            namespace = namespace,
            key = key,
            origin = CacheOperationOrigin.MEMORY,
        )
        complete(
            token = operation,
            outcome = CacheOperationOutcome.EVICTED,
            evictionCause = CacheEvictionCause.MEMORY_LRU,
            affectedCount = 1,
            memoryEntryCount = remainingEntries,
            memoryEntryLimit = configuredLimit,
        )
    }

    /** Delivers all queued events followed by the terminal close event and stops the worker. */
    suspend fun close() {
        emit(CacheEventType.LIFECYCLE_CLOSE)
        channel.close()
        worker?.join()
        deliveryJob.complete()
    }

    /** Assigns sequence, reduces metrics, and offers non-blocking listener delivery. */
    private fun publish(event: CacheEvent) {
        serializePublication {
            val numbered = numbered(event)
            reducer.record(numbered)
            if (listener != null) channel.trySend(numbered)
        }
    }

    private fun numbered(event: CacheEvent): CacheEvent = event.copy(sequence = next(sequence))

    private fun CacheOperationToken.event(
        type: CacheEventType,
        phase: CacheOperationPhase,
        outcome: CacheOperationOutcome? = null,
        totalDuration: Duration? = null,
        sourceDuration: Duration? = null,
        persistenceDuration: Duration? = null,
        codecDuration: Duration? = null,
        singleFlightStatus: CacheSingleFlightStatus? = null,
        waiterCount: Int? = null,
        evictionCause: CacheEvictionCause? = null,
        affectedCount: Long? = null,
        memoryEntryCount: Int? = null,
        memoryEntryLimit: Int? = null,
        persistentRowCount: Long? = null,
        persistentPayloadBytes: Long? = null,
        persistentValuesExact: Boolean? = null,
    ): CacheEvent = CacheEvent(
        type = type,
        namespace = namespace,
        keyFingerprint = key?.let(fingerprinter::fingerprint),
        operation = CacheOperationDetails(
            correlationId = correlationId,
            phase = phase,
            operation = operation,
            origin = origin,
            outcome = outcome,
            totalDuration = totalDuration,
            sourceDuration = sourceDuration,
            persistenceDuration = persistenceDuration,
            codecDuration = codecDuration,
            persistenceOperation = persistenceOperation,
            singleFlightStatus = singleFlightStatus,
            waiterCount = waiterCount,
            evictionCause = evictionCause,
            affectedCount = affectedCount,
            memoryEntryCount = memoryEntryCount,
            memoryEntryLimit = memoryEntryLimit,
            persistentRowCount = persistentRowCount,
            persistentPayloadBytes = persistentPayloadBytes,
            persistentValuesExact = persistentValuesExact,
        ),
    )

    private fun next(counter: AtomicLong): Long {
        while (true) {
            val current = counter.load()
            val updated = current + 1
            if (counter.compareAndSet(current, updated)) return updated
        }
    }

    /** Keeps sequence assignment and queue insertion atomic without invoking listener code. */
    private inline fun <T> serializePublication(block: () -> T): T {
        while (!publicationGate.compareAndSet(expectedValue = false, newValue = true)) {
            // Publication is non-suspending and bounded; another producer releases immediately.
        }
        return try {
            block()
        } finally {
            publicationGate.store(false)
        }
    }
}

/** Internal state needed to correlate and time one operation without exposing raw addresses. */
internal data class CacheOperationToken(
    val correlationId: Long,
    val operation: CacheOperationKind,
    val namespace: CacheNamespace?,
    val key: EncodedCacheKey?,
    val origin: CacheOperationOrigin?,
    val persistenceOperation: CachePersistenceOperation?,
    val started: TimeMark,
)
