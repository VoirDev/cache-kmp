package dev.voir.cache.core.persistence

import dev.voir.cache.core.createCacheManager
import dev.voir.cache.core.config.CacheManagerConfig
import dev.voir.cache.core.diagnostics.CacheOperationKind
import dev.voir.cache.core.diagnostics.CacheOperationOutcome
import dev.voir.cache.core.fixture.FakeCacheClock
import dev.voir.cache.core.fixture.FakePersistentCacheStore
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.model.CacheMetadata
import dev.voir.cache.core.model.CachePersistenceStatus
import dev.voir.cache.core.policy.PersistentRetentionPolicy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Verifies manager-level retention policy translation, reports, and sanitized statistics. */
@OptIn(ExperimentalCacheStoreApi::class)
class PersistentRetentionManagerTest {
    private val now = Instant.fromEpochSeconds(100)

    /** Verifies configured retention uses the injected clock and returns exact removal counts. */
    @Test
    fun `configured retention derives cutoff from cache clock`() = runTest {
        val expired = record("alpha.v1", "expired", "old", expiresAt = 89)
        val backend = FakePersistentCacheStore(listOf(expired))
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = backend,
            clock = FakeCacheClock(now),
            config = CacheManagerConfig(
                persistentRetention = PersistentRetentionPolicy(
                    expiredEntryGrace = 10.seconds,
                    maxEntriesPerNamespace = 5,
                    maxTotalEntries = 20,
                    maxTotalPayloadBytes = 1_024,
                ),
            ),
        )

        val report = manager.prune()

        assertEquals(CachePersistenceStatus.AVAILABLE, report.persistenceStatus)
        assertEquals(1, report.rowsRemoved)
        assertEquals(3, report.payloadBytesRemoved)
        val request = requireNotNull(backend.lastPruneRequest)
        assertEquals(Instant.fromEpochSeconds(90), request.expiredBefore)
        assertEquals(5, request.maxEntriesPerNamespace)
        assertEquals(20, request.maxTotalEntries)
        assertEquals(1_024, request.maxTotalPayloadBytes)
        val statistics = manager.statistics()
        assertEquals(1, statistics.operationCounts[CacheOperationKind.PRUNE])
        assertEquals(1, statistics.outcomeCounts[CacheOperationOutcome.COMMITTED])
        assertEquals(1, statistics.evictions)
        manager.close()
    }

    /** Verifies one-shot policy can disable expiry independently of manager defaults. */
    @Test
    fun `explicit retention policy overrides configured defaults`() = runTest {
        val backend = FakePersistentCacheStore()
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = backend,
            clock = FakeCacheClock(now),
        )

        manager.prune(
            PersistentRetentionPolicy(
                removeExpired = false,
                maxTotalEntries = 7,
            ),
        )

        val request = requireNotNull(backend.lastPruneRequest)
        assertNull(request.expiredBefore)
        assertEquals(7, request.maxTotalEntries)
        manager.close()
    }

    /** Verifies statistics expose namespace aggregates without addresses or payloads. */
    @Test
    fun `storage statistics report exact sanitized occupancy`() = runTest {
        val backend = FakePersistentCacheStore(
            listOf(
                record("alpha.v1", "one", "abc"),
                record("alpha.v1", "two", "de"),
                record("beta.v1", "three", "f"),
            ),
        )
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = backend,
            clock = FakeCacheClock(now),
        )

        val statistics = manager.storageStatistics()

        assertEquals(CachePersistenceStatus.AVAILABLE, statistics.persistenceStatus)
        assertEquals(3, statistics.totalRows)
        assertEquals(6, statistics.totalPayloadBytes)
        assertEquals(2, statistics.namespaces.getValue(CacheNamespace("alpha.v1")).rows)
        assertEquals(5, statistics.namespaces.getValue(CacheNamespace("alpha.v1")).payloadBytes)
        assertEquals(1, statistics.namespaces.getValue(CacheNamespace("beta.v1")).rows)
        assertEquals(now, statistics.capturedAt)
        assertEquals(true, statistics.isExact)
        val operationStatistics = manager.statistics()
        assertEquals(3, operationStatistics.persistentRows)
        assertEquals(6, operationStatistics.persistentPayloadBytes)
        assertEquals(true, operationStatistics.persistentValuesExact)
        manager.close()
    }

    /** Verifies memory-only and failed backends have distinguishable non-throwing outcomes. */
    @Test
    fun `maintenance reports absent and failed persistence explicitly`() = runTest {
        val memoryOnly = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            clock = FakeCacheClock(now),
        )
        assertEquals(CachePersistenceStatus.NOT_CONFIGURED, memoryOnly.prune().persistenceStatus)
        val emptyStatistics = memoryOnly.storageStatistics()
        assertEquals(CachePersistenceStatus.NOT_CONFIGURED, emptyStatistics.persistenceStatus)
        assertEquals(0, emptyStatistics.totalRows)
        memoryOnly.close()

        val failedBackend = FakePersistentCacheStore().also { backend ->
            backend.failPrunes = true
            backend.failStatistics = true
        }
        val degraded = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = failedBackend,
            clock = FakeCacheClock(now),
        )
        val failedPrune = degraded.prune()
        val failedStatistics = degraded.storageStatistics()
        assertEquals(CachePersistenceStatus.UNAVAILABLE, failedPrune.persistenceStatus)
        assertEquals(CachePersistenceStatus.UNAVAILABLE, failedStatistics.persistenceStatus)
        assertNull(failedStatistics.totalRows)
        degraded.close()
    }

    /** Creates one opaque record with deterministic storage timestamps. */
    private fun record(
        namespace: String,
        key: String,
        payload: String,
        expiresAt: Long? = null,
    ): PersistedCacheRecord = PersistedCacheRecord(
        address = CacheAddress(
            namespace = CacheNamespace(namespace),
            partition = CachePartition("test"),
            key = key,
        ),
        payload = payload.encodeToByteArray(),
        payloadVersion = 1,
        metadata = CacheMetadata(
            validatedAt = Instant.fromEpochSeconds(1),
            expiresAt = expiresAt?.let(Instant::fromEpochSeconds),
        ),
        writtenAt = Instant.fromEpochSeconds(2),
        lastAccessedAt = Instant.fromEpochSeconds(3),
    )
}
