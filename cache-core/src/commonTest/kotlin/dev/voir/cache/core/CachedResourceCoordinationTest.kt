package dev.voir.cache.core

import dev.voir.cache.core.config.CacheSpec
import dev.voir.cache.core.config.cacheSpec
import dev.voir.cache.core.diagnostics.CacheEvent
import dev.voir.cache.core.diagnostics.CacheEventType
import dev.voir.cache.core.diagnostics.CacheEvictionCause
import dev.voir.cache.core.diagnostics.CacheOperationKind
import dev.voir.cache.core.diagnostics.CacheOperationOutcome
import dev.voir.cache.core.diagnostics.CacheOperationPhase
import dev.voir.cache.core.diagnostics.CacheSingleFlightStatus
import dev.voir.cache.core.fixture.FakeCacheClock
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.model.CacheActivity
import dev.voir.cache.core.model.CacheDiscarded
import dev.voir.cache.core.model.CacheFailure
import dev.voir.cache.core.model.CacheFreshness
import dev.voir.cache.core.model.CacheSuccess
import dev.voir.cache.core.model.FetchFailure
import dev.voir.cache.core.model.FetchSuccess
import dev.voir.cache.core.policy.ExpireAfter
import dev.voir.cache.core.policy.FreshnessPolicy
import dev.voir.cache.core.policy.MemoryPolicy
import dev.voir.cache.core.policy.NeverExpire
import dev.voir.cache.core.storage.memoryOnly
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Verifies loading, single-flight, stale-data, revision, and diagnostic behavior. */
@OptIn(ExperimentalCoroutinesApi::class)
class CachedResourceCoordinationTest {
    private val initialTime = Instant.parse("2026-08-10T10:00:00Z")

    /** Verifies fresh memory data returns immediately without invoking the loader. */
    @Test
    fun `get skips source loading for fresh memory data`() = runTest {
        var fetchCount = 0
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = spec("coordination.fresh.v1"),
            fetch = {
                fetchCount += 1
                FetchSuccess("source")
            },
            mapUnexpectedFailure = ::message,
        )
        resource.put("entry", "memory")

        val result = assertIs<CacheSuccess<String>>(resource.get("entry"))

        assertEquals("memory", result.value)
        assertEquals(CacheFreshness.FRESH, result.freshness)
        assertEquals(0, fetchCount)
        manager.close()
    }

    /** Verifies process-local inspection neither hydrates nor invokes the source loader. */
    @Test
    fun `peek returns memory state without source loading`() = runTest {
        var fetchCount = 0
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = spec("coordination.peek.v1"),
            fetch = {
                fetchCount += 1
                FetchSuccess("source")
            },
            mapUnexpectedFailure = ::message,
        )
        resource.put("entry", "memory")

        val snapshot = resource.peek("entry")

        assertEquals("memory", snapshot.value)
        assertEquals(0, fetchCount)
        manager.close()
    }

    /** Verifies explicit refresh invokes the source even when memory is fresh. */
    @Test
    fun `refresh forces source loading for fresh memory data`() = runTest {
        var fetchCount = 0
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = spec("coordination.forced.v1"),
            fetch = {
                fetchCount += 1
                FetchSuccess("source")
            },
            mapUnexpectedFailure = ::message,
        )
        resource.put("entry", "memory")

        val result = assertIs<CacheSuccess<String>>(resource.refresh("entry"))

        assertEquals("source", result.value)
        assertEquals(1, fetchCount)
        assertEquals("source", resource.current("entry").value)
        manager.close()
    }

    /** Verifies a completed flight is removed so a later refresh starts new work. */
    @Test
    fun `completed source work can run again`() = runTest {
        var fetchCount = 0
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = spec("coordination.completed-flight.v1"),
            fetch = {
                fetchCount += 1
                FetchSuccess("source-$fetchCount")
            },
            mapUnexpectedFailure = ::message,
        )

        assertEquals("source-1", assertIs<CacheSuccess<String>>(resource.refresh("entry")).value)
        assertEquals("source-2", assertIs<CacheSuccess<String>>(resource.refresh("entry")).value)
        assertEquals(2, fetchCount)
        manager.close()
    }

    /** Verifies concurrent callers for one address await exactly one source invocation. */
    @Test
    fun `equal addresses join one source request`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var fetchCount = 0
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = spec("coordination.single-flight.v1"),
            fetch = {
                fetchCount += 1
                started.complete(Unit)
                release.await()
                FetchSuccess("shared")
            },
            mapUnexpectedFailure = ::message,
        )

        val first = async { resource.get("entry") }
        started.await()
        val second = async { resource.refresh("entry") }
        runCurrent()

        assertEquals(1, fetchCount)
        release.complete(Unit)
        assertEquals("shared", assertIs<CacheSuccess<String>>(first.await()).value)
        assertEquals("shared", assertIs<CacheSuccess<String>>(second.await()).value)
        val statistics = manager.statistics()
        assertEquals(1, statistics.sourceCommitted)
        assertEquals(1, statistics.singleFlightJoins)
        assertEquals(1, statistics.operationCounts[CacheOperationKind.GET])
        assertEquals(1, statistics.operationCounts[CacheOperationKind.REFRESH])
        manager.close()
    }

    /** Verifies unrelated addresses are not serialized by the source coordinator. */
    @Test
    fun `different addresses load concurrently`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = spec("coordination.concurrent.v1"),
            fetch = { key ->
                if (key == "first") firstStarted.complete(Unit) else secondStarted.complete(Unit)
                release.await()
                FetchSuccess(key)
            },
            mapUnexpectedFailure = ::message,
        )

        val first = async { resource.get("first") }
        val second = async { resource.get("second") }
        firstStarted.await()
        secondStarted.await()

        release.complete(Unit)
        assertEquals("first", assertIs<CacheSuccess<String>>(first.await()).value)
        assertEquals("second", assertIs<CacheSuccess<String>>(second.await()).value)
        manager.close()
    }

    /** Verifies cancellation of one caller cannot cancel manager-owned shared work. */
    @Test
    fun `cancelled waiter does not cancel shared source work`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var fetchCount = 0
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = spec("coordination.waiter-cancellation.v1"),
            fetch = {
                fetchCount += 1
                started.complete(Unit)
                release.await()
                FetchSuccess("loaded")
            },
            mapUnexpectedFailure = ::message,
        )
        val cancelledWaiter = async { resource.get("entry") }
        started.await()
        val remainingWaiter = async { resource.get("entry") }
        runCurrent()

        cancelledWaiter.cancelAndJoin()
        release.complete(Unit)

        assertEquals("loaded", assertIs<CacheSuccess<String>>(remainingWaiter.await()).value)
        assertEquals(1, fetchCount)
        manager.close()
    }

    /** Verifies stale data stays visible during refresh and after a typed source failure. */
    @Test
    fun `stale value remains visible while refresh fails`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val clock = FakeCacheClock(initialTime)
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            clock = clock,
        )
        val resource = manager.resource(
            spec = spec(
                namespace = "coordination.stale.v1",
                freshness = ExpireAfter(5.minutes),
            ),
            fetch = {
                started.complete(Unit)
                release.await()
                FetchFailure("offline")
            },
            mapUnexpectedFailure = ::message,
        )
        resource.put("entry", "cached")
        clock.advanceBy(5.minutes)
        val refresh = async { resource.get("entry") }
        started.await()

        val refreshing = resource.current("entry")
        assertEquals("cached", refreshing.value)
        assertEquals(CacheFreshness.STALE, refreshing.freshness)
        assertEquals(CacheActivity.REFRESHING, refreshing.activity)

        release.complete(Unit)
        val failure = assertIs<CacheFailure<String, String>>(refresh.await())
        assertEquals("offline", failure.error)
        assertEquals("cached", failure.staleValue)
        val settled = resource.current("entry")
        assertEquals("cached", settled.value)
        assertEquals("offline", settled.refreshError)
        assertEquals(CacheActivity.IDLE, settled.activity)
        manager.close()
    }

    /** Verifies loader exceptions are mapped while cancellation remains outside the mapper. */
    @Test
    fun `unexpected loader exception is mapped to typed failure`() = runTest {
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = spec("coordination.mapping.v1"),
            fetch = { error("network exploded") },
            mapUnexpectedFailure = { throwable -> "mapped:${throwable.message}" },
        )

        val result = assertIs<CacheFailure<String, String>>(resource.get("entry"))

        assertEquals("mapped:network exploded", result.error)
        assertNull(result.staleValue)
        manager.close()
    }

    /** Verifies an authoritative update wins over a source result started earlier. */
    @Test
    fun `update cannot be overwritten by an older source result`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = spec("coordination.update-guard.v1"),
            fetch = {
                started.complete(Unit)
                release.await()
                FetchSuccess("late")
            },
            mapUnexpectedFailure = ::message,
        )
        resource.put("entry", "old")
        val refresh = async { resource.refresh("entry") }
        started.await()

        assertTrue(resource.update("entry") { "updated" })
        release.complete(Unit)
        val discarded = assertIs<CacheDiscarded<String, String>>(refresh.await())

        assertEquals("updated", resource.current("entry").value)
        assertEquals("updated", discarded.currentSnapshot.value)
        assertNull(discarded.sourceError)
        manager.close()
    }

    /** Verifies an authoritative put wins over a source result started earlier. */
    @Test
    fun `put cannot be overwritten by an older source result`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = spec("coordination.put-guard.v1"),
            fetch = {
                started.complete(Unit)
                release.await()
                FetchSuccess("late")
            },
            mapUnexpectedFailure = ::message,
        )
        val refresh = async { resource.refresh("entry") }
        started.await()

        resource.put("entry", "authoritative")
        release.complete(Unit)
        val discarded = assertIs<CacheDiscarded<String, String>>(refresh.await())

        assertEquals("authoritative", resource.current("entry").value)
        assertEquals("authoritative", discarded.currentSnapshot.value)
        assertEquals(1, manager.statistics().discardedResults)
        manager.close()
    }

    /** Verifies invalidation wins over a source result started earlier. */
    @Test
    fun `invalidate cannot be overwritten by an older source result`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = spec("coordination.invalidate-guard.v1"),
            fetch = {
                started.complete(Unit)
                release.await()
                FetchSuccess("late")
            },
            mapUnexpectedFailure = ::message,
        )
        resource.put("entry", "old")
        val refresh = async { resource.refresh("entry") }
        started.await()

        resource.invalidate("entry")
        release.complete(Unit)
        val discarded = assertIs<CacheDiscarded<String, String>>(refresh.await())

        val snapshot = resource.current("entry")
        assertEquals("old", snapshot.value)
        assertEquals(CacheFreshness.STALE, snapshot.freshness)
        assertEquals(snapshot, discarded.currentSnapshot)
        manager.close()
    }

    /** Verifies a source failure superseded by a write is classified as discarded. */
    @Test
    fun `late source failure returns discarded authoritative snapshot`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = spec("coordination.failure-guard.v1"),
            fetch = {
                started.complete(Unit)
                release.await()
                FetchFailure("late-error")
            },
            mapUnexpectedFailure = ::message,
        )
        val refresh = async { resource.refresh("entry") }
        started.await()

        resource.put("entry", "authoritative")
        release.complete(Unit)
        val discarded = assertIs<CacheDiscarded<String, String>>(refresh.await())

        assertEquals("authoritative", discarded.currentSnapshot.value)
        assertNull(discarded.currentSnapshot.refreshError)
        assertEquals("late-error", discarded.sourceError)
        manager.close()
    }

    /** Verifies every waiter on one losing source flight receives the same terminal result. */
    @Test
    fun `single-flight waiters share discarded result classification`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = spec("coordination.discarded-single-flight.v1"),
            fetch = {
                started.complete(Unit)
                release.await()
                FetchSuccess("late")
            },
            mapUnexpectedFailure = ::message,
        )
        val first = async { resource.get("entry") }
        started.await()
        val second = async { resource.refresh("entry") }
        runCurrent()

        resource.put("entry", "authoritative")
        release.complete(Unit)
        val firstResult = assertIs<CacheDiscarded<String, String>>(first.await())
        val secondResult = assertIs<CacheDiscarded<String, String>>(second.await())

        assertEquals(firstResult, secondResult)
        assertEquals("authoritative", firstResult.currentSnapshot.value)
        manager.close()
    }

    /** Verifies a cross-namespace partition clear guards every matching late result. */
    @Test
    fun `partition clear cannot be overwritten by older source results`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = spec("coordination.partition-guard.v1"),
            fetch = {
                started.complete(Unit)
                release.await()
                FetchSuccess("late")
            },
            mapUnexpectedFailure = ::message,
        )
        val refresh = async { resource.refresh("entry") }
        started.await()

        manager.clear(CachePartition("test"))
        release.complete(Unit)
        val discarded = assertIs<CacheDiscarded<String, String>>(refresh.await())

        assertNull(resource.current("entry").value)
        assertNull(discarded.currentSnapshot.value)
        manager.close()
    }

    /** Verifies namespace clearing produces an absent discarded result for older source work. */
    @Test
    fun `namespace clear returns discarded result for older source work`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = spec("coordination.namespace-guard.v1"),
            fetch = {
                started.complete(Unit)
                release.await()
                FetchSuccess("late")
            },
            mapUnexpectedFailure = ::message,
        )
        val refresh = async { resource.refresh("entry") }
        started.await()

        resource.clear()
        release.complete(Unit)
        val discarded = assertIs<CacheDiscarded<String, String>>(refresh.await())

        assertNull(discarded.currentSnapshot.value)
        assertNull(resource.peek("entry").value)
        manager.close()
    }

    /** Verifies global clearing produces an absent discarded result for older source work. */
    @Test
    fun `global clear returns discarded result for older source work`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = spec("coordination.global-guard.v1"),
            fetch = {
                started.complete(Unit)
                release.await()
                FetchSuccess("late")
            },
            mapUnexpectedFailure = ::message,
        )
        val refresh = async { resource.refresh("entry") }
        started.await()

        manager.clearAll()
        release.complete(Unit)
        val discarded = assertIs<CacheDiscarded<String, String>>(refresh.await())

        assertNull(discarded.currentSnapshot.value)
        assertNull(resource.peek("entry").value)
        manager.close()
    }

    /** Verifies listener exceptions never alter a successful cache operation. */
    @Test
    fun `diagnostic listener failure cannot fail cache operation`() = runTest {
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            eventListener = { throw IllegalStateException("listener failed") },
        )
        val resource = manager.resource(
            spec = spec("coordination.listener-failure.v1"),
            fetch = { FetchSuccess("loaded") },
            mapUnexpectedFailure = ::message,
        )

        assertEquals("loaded", assertIs<CacheSuccess<String>>(resource.get("entry")).value)
        runCurrent()
        manager.close()
    }

    /** Verifies single-flight diagnostics contain fingerprints instead of raw address values. */
    @Test
    fun `single-flight join emits non-sensitive diagnostic`() = runTest {
        val events = mutableListOf<CacheEvent>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            eventListener = events::add,
        )
        val resource = manager.resource(
            spec = spec("coordination.diagnostics.v1"),
            fetch = {
                started.complete(Unit)
                release.await()
                FetchSuccess("loaded")
            },
            mapUnexpectedFailure = ::message,
        )
        val first = async { resource.get("private-key") }
        started.await()
        val second = async { resource.get("private-key") }
        runCurrent()
        release.complete(Unit)
        first.await()
        second.await()
        runCurrent()

        val joined = events.single { event ->
            event.type == CacheEventType.SINGLE_FLIGHT_JOIN
        }
        val fingerprint = assertNotNull(joined.keyFingerprint)
        assertFalse(fingerprint.isBlank())
        assertFalse(fingerprint.contains("private-key"))
        assertEquals("coordination.diagnostics.v1", joined.namespace?.value)
        manager.close()
    }

    /** Verifies start and completion events are ordered, correlated, timed, and sanitized. */
    @Test
    fun `structured source events are correlated and privacy safe`() = runTest {
        val events = mutableListOf<CacheEvent>()
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            eventListener = events::add,
        )
        val resource = manager.resource(
            spec = spec("coordination.structured-events.v1"),
            fetch = { FetchSuccess("payload-that-must-stay-private") },
            mapUnexpectedFailure = ::message,
        )

        resource.get("raw-private-key")
        manager.close()

        assertEquals(events.map { it.sequence }.sorted(), events.map { it.sequence })
        val operationEvents = events.filter { event ->
            event.operation?.operation == CacheOperationKind.GET
        }
        assertEquals(2, operationEvents.size)
        val started = operationEvents.single { event ->
            event.operation?.phase == CacheOperationPhase.STARTED
        }
        val completed = operationEvents.single { event ->
            event.operation?.phase == CacheOperationPhase.COMPLETED
        }
        val completedDetails = assertNotNull(completed.operation)
        assertEquals(started.operation?.correlationId, completedDetails.correlationId)
        assertTrue(started.sequence < completed.sequence)
        assertEquals(CacheOperationOutcome.COMMITTED, completedDetails.outcome)
        assertEquals(CacheSingleFlightStatus.STARTED, completedDetails.singleFlightStatus)
        assertNotNull(completedDetails.sourceDuration)
        assertNotNull(completedDetails.totalDuration)
        assertEquals(1, completedDetails.memoryEntryCount)
        assertEquals(8, completedDetails.memoryEntryLimit)
        operationEvents.forEach { event ->
            assertFalse(event.toString().contains("raw-private-key"))
            assertFalse(event.toString().contains("payload-that-must-stay-private"))
        }
    }

    /** Verifies manager metrics use stable hit, miss, source, outcome, and occupancy definitions. */
    @Test
    fun `statistics aggregate cache outcomes and memory occupancy`() = runTest {
        var shouldFail = false
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = spec("coordination.statistics.v1"),
            fetch = { key ->
                if (shouldFail) FetchFailure("offline") else FetchSuccess("value-$key")
            },
            mapUnexpectedFailure = ::message,
        )

        resource.get("first")
        resource.get("first")
        shouldFail = true
        resource.get("second")

        val statistics = manager.statistics()
        assertEquals(1, statistics.memoryHits)
        assertEquals(2, statistics.misses)
        assertEquals(1, statistics.freshSkips)
        assertEquals(1, statistics.sourceCommitted)
        assertEquals(1, statistics.sourceFailures)
        assertEquals(1, statistics.outcomeCounts[CacheOperationOutcome.COMMITTED])
        assertEquals(1, statistics.outcomeCounts[CacheOperationOutcome.FRESH_SKIP])
        assertEquals(1, statistics.outcomeCounts[CacheOperationOutcome.SOURCE_FAILURE])
        assertEquals(1.0 / 3.0, statistics.hitRate)
        val memory = statistics.memory.getValue(
            dev.voir.cache.core.key.CacheNamespace("coordination.statistics.v1")
        )
        assertEquals(2, memory.currentEntries)
        assertEquals(2, memory.peakEntries)
        assertEquals(8, memory.configuredLimit)
        manager.close()
    }

    /** Verifies memory LRU eviction reports a sanitized cause and updates occupancy metrics. */
    @Test
    fun `memory eviction is observable without exposing the evicted key`() = runTest {
        val events = mutableListOf<CacheEvent>()
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            eventListener = events::add,
        )
        val resource = manager.resource(
            spec = cacheSpec(
                namespace = "coordination.eviction.v1",
                key = { key: String -> EncodedCacheKey(CachePartition("test"), key) },
                freshness = NeverExpire,
                storage = memoryOnly(),
                memory = MemoryPolicy(maxEntries = 2),
            ),
            fetch = { FetchSuccess("unused") },
            mapUnexpectedFailure = ::message,
        )

        resource.put("secret-first", "one")
        resource.put("second", "two")
        resource.put("third", "three")
        manager.close()

        val eviction = events.single { event ->
            event.operation?.evictionCause == CacheEvictionCause.MEMORY_LRU
        }
        assertEquals(CacheOperationOutcome.EVICTED, eviction.operation?.outcome)
        assertFalse(eviction.toString().contains("secret-first"))
        val statistics = manager.statistics()
        assertEquals(1, statistics.evictions)
        val memory = statistics.memory.getValue(
            dev.voir.cache.core.key.CacheNamespace("coordination.eviction.v1")
        )
        assertEquals(2, memory.currentEntries)
        assertEquals(2, memory.peakEntries)
    }

    /** Verifies bulk clear updates current occupancy while preserving the observed peak. */
    @Test
    fun `global clear updates memory occupancy statistics`() = runTest {
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = spec("coordination.clear-statistics.v1"),
            fetch = { FetchSuccess("unused") },
            mapUnexpectedFailure = ::message,
        )
        resource.put("entry", "value")

        manager.clearAll()

        val memory = manager.statistics().memory.getValue(
            dev.voir.cache.core.key.CacheNamespace("coordination.clear-statistics.v1")
        )
        assertEquals(0, memory.currentEntries)
        assertEquals(1, memory.peakEntries)
        assertEquals(1, manager.statistics().operationCounts[CacheOperationKind.CLEAR_ALL])
        manager.close()
    }

    /**
     * Creates one memory-only string resource specification for coordination tests.
     *
     * @param namespace Stable namespace unique within the test manager.
     * @param freshness Freshness policy applied to test values.
     * @return Typed string resource specification.
     */
    private fun spec(
        namespace: String,
        freshness: FreshnessPolicy = NeverExpire,
    ): CacheSpec<String, String> = cacheSpec(
        namespace = namespace,
        key = { key -> EncodedCacheKey(CachePartition("test"), key) },
        freshness = freshness,
        storage = memoryOnly(),
        memory = MemoryPolicy(maxEntries = 8),
    )

    /**
     * Maps a throwable to a stable test error string.
     *
     * @param throwable Unexpected loader failure.
     * @return Failure message or a deterministic fallback.
     */
    private fun message(throwable: Throwable): String = throwable.message ?: "unexpected"
}
