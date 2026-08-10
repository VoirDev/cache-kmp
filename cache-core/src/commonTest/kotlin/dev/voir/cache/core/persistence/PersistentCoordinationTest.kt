package dev.voir.cache.core.persistence

import dev.voir.cache.core.CachedResource
import dev.voir.cache.core.createCacheManager
import dev.voir.cache.core.config.CacheSpec
import dev.voir.cache.core.config.cacheSpec
import dev.voir.cache.core.codec.CacheCodec
import dev.voir.cache.core.diagnostics.CacheEvent
import dev.voir.cache.core.diagnostics.CacheOperationKind
import dev.voir.cache.core.diagnostics.CacheOperationOutcome
import dev.voir.cache.core.diagnostics.CacheOperationPhase
import dev.voir.cache.core.diagnostics.CachePersistenceOperation
import dev.voir.cache.core.fixture.FakeCacheClock
import dev.voir.cache.core.fixture.FakePersistentCacheStore
import dev.voir.cache.core.fixture.MigratingTestStringCodec
import dev.voir.cache.core.fixture.TestStringCodec
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.model.CacheActivity
import dev.voir.cache.core.model.CacheFreshness
import dev.voir.cache.core.model.CacheMetadata
import dev.voir.cache.core.model.CacheSuccess
import dev.voir.cache.core.model.FetchSuccess
import dev.voir.cache.core.policy.FreshnessPolicy
import dev.voir.cache.core.policy.MemoryPolicy
import dev.voir.cache.core.policy.NeverExpire
import dev.voir.cache.core.storage.persistent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Verifies lazy hydration, two-tier ordering, degradation, and manager-owned store lifecycle. */
@OptIn(ExperimentalCacheStoreApi::class, ExperimentalCoroutinesApi::class)
class PersistentCoordinationTest {
    private val now = Instant.parse("2026-08-11T10:00:00Z")
    private val partition = CachePartition("test")

    /** Verifies fresh persistent data hydrates memory and skips the source loader. */
    @Test
    fun `fresh persistent value skips source loading`() = runTest {
        val namespace = "persistence.fresh.v1"
        val backend = FakePersistentCacheStore(
            listOf(record(namespace, "entry", "disk", metadata(now)))
        )
        var fetchCount = 0
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = backend,
            clock = FakeCacheClock(now),
        )
        val resource = resource(manager, namespace) {
            fetchCount += 1
            FetchSuccess("source")
        }

        val result = assertIs<CacheSuccess<String>>(resource.get("entry"))

        assertEquals("disk", result.value)
        assertEquals(0, fetchCount)
        assertEquals(1, backend.readCount)
        manager.close()
        assertTrue(backend.isClosed)
    }

    /** Verifies stale disk data remains visible while exactly one source refresh runs. */
    @Test
    fun `stale persistent value remains visible during shared refresh`() = runTest {
        val namespace = "persistence.stale.v1"
        val staleMetadata = CacheMetadata(
            validatedAt = now - 10.minutes,
            expiresAt = now - 5.minutes,
        )
        val backend = FakePersistentCacheStore(
            listOf(record(namespace, "entry", "stale", staleMetadata))
        )
        val sourceStarted = CompletableDeferred<Unit>()
        val sourceRelease = CompletableDeferred<Unit>()
        var fetchCount = 0
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = backend,
            clock = FakeCacheClock(now),
        )
        val resource = resource(manager, namespace) {
            fetchCount += 1
            sourceStarted.complete(Unit)
            sourceRelease.await()
            FetchSuccess("fresh")
        }

        val first = async { resource.get("entry") }
        sourceStarted.await()
        val second = async { resource.get("entry") }
        runCurrent()

        val refreshing = resource.current("entry")
        assertEquals("stale", refreshing.value)
        assertEquals(CacheFreshness.STALE, refreshing.freshness)
        assertEquals(CacheActivity.REFRESHING, refreshing.activity)
        assertEquals(1, fetchCount)

        sourceRelease.complete(Unit)
        assertEquals("fresh", assertIs<CacheSuccess<String>>(first.await()).value)
        assertEquals("fresh", assertIs<CacheSuccess<String>>(second.await()).value)
        assertEquals("fresh", backend.record(address(namespace, "entry"))?.payload?.decodeToString())
        manager.close()
    }

    /** Verifies an authoritative empty value remains distinguishable from a missing row. */
    @Test
    fun `authoritative empty value survives manager recreation`() = runTest {
        val namespace = "persistence.empty.v1"
        val firstBackend = FakePersistentCacheStore()
        val firstManager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = firstBackend,
            clock = FakeCacheClock(now),
        )
        val firstResource = resource(firstManager, namespace) { FetchSuccess("unused") }
        firstResource.put("entry", "")
        val persisted = requireNotNull(firstBackend.record(address(namespace, "entry")))
        firstManager.close()

        var fetchCount = 0
        val secondBackend = FakePersistentCacheStore(listOf(persisted))
        val secondManager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = secondBackend,
            clock = FakeCacheClock(now),
        )
        val secondResource = resource(secondManager, namespace) {
            fetchCount += 1
            FetchSuccess("source")
        }

        assertEquals("", assertIs<CacheSuccess<String>>(secondResource.get("entry")).value)
        assertEquals(0, fetchCount)
        secondManager.close()
    }

    /** Verifies concurrent memory reads await one shared persistent read. */
    @Test
    fun `concurrent reads join one persistent hydration`() = runTest {
        val namespace = "persistence.single-flight.v1"
        val backend = FakePersistentCacheStore(
            listOf(record(namespace, "entry", "disk", metadata(now)))
        )
        backend.readRelease = CompletableDeferred()
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = backend,
            clock = FakeCacheClock(now),
        )
        val resource = resource(manager, namespace) { FetchSuccess("unused") }

        val first = async { resource.current("entry") }
        backend.readStarted.await()
        val second = async { resource.current("entry") }
        runCurrent()

        assertEquals(1, backend.readCount)
        backend.readRelease?.complete(Unit)
        assertEquals("disk", first.await().value)
        assertEquals("disk", second.await().value)
        manager.close()
    }

    /** Verifies a persistent value can hydrate again after bounded-memory eviction. */
    @Test
    fun `evicted persistent value can hydrate again`() = runTest {
        val namespace = "persistence.rehydrate.v1"
        val backend = FakePersistentCacheStore(
            listOf(
                record(namespace, "first", "first-disk", metadata(now)),
                record(namespace, "second", "second-disk", metadata(now)),
            )
        )
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = backend,
            clock = FakeCacheClock(now),
        )
        val resource = resource(
            manager = manager,
            namespace = namespace,
            maxEntries = 1,
        ) { FetchSuccess("unused") }

        assertEquals("first-disk", resource.current("first").value)
        assertEquals("second-disk", resource.current("second").value)
        assertEquals("first-disk", resource.current("first").value)

        assertEquals(3, backend.readCount)
        manager.close()
    }

    /** Verifies a delayed persistent result cannot replace a newer authoritative write. */
    @Test
    fun `late hydration cannot overwrite newer memory value`() = runTest {
        val namespace = "persistence.hydration-guard.v1"
        val backend = FakePersistentCacheStore(
            listOf(record(namespace, "entry", "old-disk", metadata(now)))
        )
        backend.readRelease = CompletableDeferred()
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = backend,
            clock = FakeCacheClock(now),
        )
        val resource = resource(manager, namespace) { FetchSuccess("unused") }
        val hydration = async { resource.current("entry") }
        backend.readStarted.await()

        val write = async { resource.put("entry", "new-memory") }
        runCurrent()
        backend.readRelease?.complete(Unit)
        hydration.await()
        write.await()

        assertEquals("new-memory", resource.current("entry").value)
        assertEquals(
            "new-memory",
            backend.record(address(namespace, "entry"))?.payload?.decodeToString(),
        )
        manager.close()
    }

    /** Verifies source success uses the same metadata instance for memory and persistence. */
    @Test
    fun `source success shares one metadata instance across tiers`() = runTest {
        val namespace = "persistence.metadata.v1"
        val backend = FakePersistentCacheStore()
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = backend,
            clock = FakeCacheClock(now),
        )
        val resource = resource(manager, namespace) { FetchSuccess("source") }

        resource.refresh("entry")

        val memoryMetadata = requireNotNull(resource.current("entry").metadata)
        val persistedMetadata = requireNotNull(
            backend.record(address(namespace, "entry"))?.metadata
        )
        assertSame(memoryMetadata, persistedMetadata)
        manager.close()
    }

    /** Verifies persistent read failure degrades to the normal source and memory path. */
    @Test
    fun `persistent read failure falls back to source`() = runTest {
        val namespace = "persistence.read-failure.v1"
        val backend = FakePersistentCacheStore().also { store -> store.failReads = true }
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = backend,
            clock = FakeCacheClock(now),
        )
        val resource = resource(manager, namespace) { FetchSuccess("source") }

        assertEquals("source", assertIs<CacheSuccess<String>>(resource.get("entry")).value)
        assertEquals("source", resource.current("entry").value)
        manager.close()
    }

    /** Verifies persistent write failure cannot turn source success into failure. */
    @Test
    fun `persistent write failure preserves successful memory value`() = runTest {
        val namespace = "persistence.write-failure.v1"
        val backend = FakePersistentCacheStore().also { store -> store.failWrites = true }
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = backend,
            clock = FakeCacheClock(now),
        )
        val resource = resource(manager, namespace) { FetchSuccess("source") }

        assertEquals("source", assertIs<CacheSuccess<String>>(resource.refresh("entry")).value)
        assertEquals("source", resource.current("entry").value)
        assertEquals(1, backend.writeCount)
        val statistics = manager.statistics()
        assertEquals(1, statistics.sourceCommitted)
        assertEquals(
            1,
            statistics.outcomeCounts[CacheOperationOutcome.PERSISTENCE_DEGRADED_SUCCESS],
        )
        assertEquals(1, statistics.outcomeCounts[CacheOperationOutcome.UNAVAILABLE])
        manager.close()
    }

    /** Verifies failed writes cannot expose an older disk value after memory eviction. */
    @Test
    fun `failed write suppresses stale disk value after eviction`() = runTest {
        val namespace = "persistence.failed-write-eviction.v1"
        val backend = FakePersistentCacheStore(
            listOf(
                record(namespace, "entry", "old-disk", metadata(now)),
                record(namespace, "other", "other-disk", metadata(now)),
            )
        )
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = backend,
            clock = FakeCacheClock(now),
        )
        val resource = resource(
            manager = manager,
            namespace = namespace,
            maxEntries = 1,
        ) { FetchSuccess("unused") }
        assertEquals("old-disk", resource.current("entry").value)
        backend.failWrites = true

        resource.put("entry", "new-memory")
        assertEquals("other-disk", resource.current("other").value)

        assertNull(resource.current("entry").value)
        assertEquals(2, backend.readCount)
        manager.close()
    }

    /** Verifies mutation failures keep memory authoritative for the manager session. */
    @Test
    fun `persistent mutation failures do not restore cleared memory`() = runTest {
        val namespace = "persistence.mutation-failure.v1"
        val backend = FakePersistentCacheStore(
            listOf(record(namespace, "entry", "disk", metadata(now)))
        )
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = backend,
            clock = FakeCacheClock(now),
        )
        val resource = resource(manager, namespace) { FetchSuccess("unused") }
        assertEquals("disk", resource.current("entry").value)

        backend.failInvalidations = true
        resource.invalidate("entry")
        assertEquals(CacheFreshness.STALE, resource.current("entry").freshness)

        backend.failRemovals = true
        resource.remove("entry")
        assertNull(resource.current("entry").value)

        resource.put("second", "memory")
        backend.failClears = true
        resource.clear()
        assertNull(resource.current("second").value)
        manager.close()
    }

    /** Verifies put, update, invalidate, and remove coordinate memory and persistence. */
    @Test
    fun `exact mutations update both storage tiers`() = runTest {
        val namespace = "persistence.mutations.v1"
        val backend = FakePersistentCacheStore()
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = backend,
            clock = FakeCacheClock(now),
        )
        val resource = resource(manager, namespace) { FetchSuccess("unused") }
        val address = address(namespace, "entry")

        resource.put("entry", "first")
        assertEquals("first", backend.record(address)?.payload?.decodeToString())

        assertTrue(resource.update("entry") { value -> "$value-updated" })
        assertEquals("first-updated", backend.record(address)?.payload?.decodeToString())

        resource.invalidate("entry")
        val memoryInvalidation = resource.current("entry").metadata?.invalidatedAt
        assertEquals(memoryInvalidation, backend.record(address)?.metadata?.invalidatedAt)

        resource.remove("entry")
        assertNull(resource.current("entry").value)
        assertNull(backend.record(address))
        manager.close()
    }

    /** Verifies namespace, partition, and global clears keep their documented scope. */
    @Test
    fun `bulk clears remove only their persistent scope`() = runTest {
        val firstNamespace = "persistence.clear-first.v1"
        val secondNamespace = "persistence.clear-second.v1"
        val backend = FakePersistentCacheStore()
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = backend,
            clock = FakeCacheClock(now),
        )
        val first = resource(manager, firstNamespace) { FetchSuccess("unused") }
        val second = resource(manager, secondNamespace) { FetchSuccess("unused") }
        first.put("entry", "first")
        second.put("entry", "second")

        first.clear()
        assertNull(backend.record(address(firstNamespace, "entry")))
        assertEquals("second", backend.record(address(secondNamespace, "entry"))?.payload?.decodeToString())

        manager.clear(partition)
        assertNull(backend.record(address(secondNamespace, "entry")))

        second.put("new-entry", "new")
        manager.clearAll()
        assertNull(backend.record(address(secondNamespace, "new-entry")))
        manager.close()
    }

    /** Verifies failed partition clearing suppresses unseen stale rows for the session. */
    @Test
    fun `failed partition clear cannot rehydrate old persistent row`() = runTest {
        val namespace = "persistence.partition-clear.v1"
        val backend = FakePersistentCacheStore(
            listOf(record(namespace, "unseen", "disk", metadata(now)))
        ).also { store -> store.failClears = true }
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = backend,
            clock = FakeCacheClock(now),
        )
        val resource = resource(manager, namespace) { FetchSuccess("unused") }

        manager.clear(partition)

        assertNull(resource.current("unseen").value)
        assertEquals(0, backend.readCount)
        manager.close()
    }

    /** Verifies observation hydrates persistence but never invokes the source loader. */
    @Test
    fun `observation hydrates persistent value without source loading`() = runTest {
        val namespace = "persistence.observe.v1"
        val backend = FakePersistentCacheStore(
            listOf(record(namespace, "entry", "disk", metadata(now)))
        )
        var fetchCount = 0
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = backend,
            clock = FakeCacheClock(now),
        )
        val resource = resource(manager, namespace) {
            fetchCount += 1
            FetchSuccess("source")
        }

        val hydrated = resource.observe("entry").first { snapshot -> snapshot.value != null }

        assertEquals("disk", hydrated.value)
        assertEquals(0, fetchCount)
        manager.close()
    }

    /** Verifies hydration reports correlated read and codec timings without raw addresses. */
    @Test
    fun `hydration emits sanitized persistence operation details`() = runTest {
        val namespace = "persistence.structured-hydration.v1"
        val backend = FakePersistentCacheStore(
            listOf(record(namespace, "private-entry", "private-payload", metadata(now)))
        )
        val events = mutableListOf<CacheEvent>()
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = backend,
            clock = FakeCacheClock(now),
            eventListener = events::add,
        )
        val resource = resource(manager, namespace) { FetchSuccess("unused") }

        assertEquals("private-payload", resource.current("private-entry").value)
        manager.close()

        val hydration = events.filter { event ->
            event.operation?.operation == CacheOperationKind.OBSERVE_HYDRATION
        }
        assertEquals(2, hydration.size)
        val started = hydration.single { event ->
            event.operation?.phase == CacheOperationPhase.STARTED
        }
        val completed = hydration.single { event ->
            event.operation?.phase == CacheOperationPhase.COMPLETED
        }
        val completedDetails = assertNotNull(completed.operation)
        assertEquals(started.operation?.correlationId, completedDetails.correlationId)
        assertEquals(CachePersistenceOperation.READ, completedDetails.persistenceOperation)
        assertEquals(CacheOperationOutcome.COMMITTED, completedDetails.outcome)
        assertNotNull(completedDetails.persistenceDuration)
        assertNotNull(completedDetails.codecDuration)
        assertEquals(1, manager.statistics().persistentHits)
        hydration.forEach { event ->
            assertTrue(!event.toString().contains("private-entry"))
            assertTrue(!event.toString().contains("private-payload"))
        }
    }

    /** Verifies memory-only inspection leaves a cold persistent address untouched. */
    @Test
    fun `peek does not start persistent hydration`() = runTest {
        val namespace = "persistence.peek.v1"
        val backend = FakePersistentCacheStore(
            listOf(record(namespace, "entry", "disk", metadata(now)))
        )
        var fetchCount = 0
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = backend,
            clock = FakeCacheClock(now),
        )
        val resource = resource(manager, namespace) {
            fetchCount += 1
            FetchSuccess("source")
        }

        val memoryOnly = resource.peek("entry")

        assertNull(memoryOnly.value)
        assertEquals(0, backend.readCount)
        assertEquals(0, fetchCount)
        assertEquals("disk", resource.current("entry").value)
        assertEquals(1, backend.readCount)
        assertEquals(0, fetchCount)
        manager.close()
    }

    /** Verifies corrupt payloads are evicted and treated as a source-loadable miss. */
    @Test
    fun `corrupt persistent payload is evicted before source loading`() = runTest {
        val namespace = "persistence.corruption.v1"
        val corrupt = record(namespace, "entry", "bad", metadata(now)).copy(payloadVersion = 2)
        val backend = FakePersistentCacheStore(listOf(corrupt))
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = backend,
            clock = FakeCacheClock(now),
        )
        val resource = resource(manager, namespace) { FetchSuccess("source") }

        assertEquals("source", assertIs<CacheSuccess<String>>(resource.get("entry")).value)
        assertEquals(
            "source",
            backend.record(address(namespace, "entry"))?.payload?.decodeToString(),
        )
        manager.close()
    }

    /** Verifies a supported historical payload is rewritten once without changing metadata. */
    @Test
    fun `supported old payload is rewritten once with current version`() = runTest {
        val namespace = "persistence.migration.v2"
        val originalMetadata = CacheMetadata(
            validatedAt = now - 10.minutes,
            expiresAt = null,
            invalidatedAt = now - 5.minutes,
        )
        val original = record(namespace, "entry", "legacy", originalMetadata)
        val firstBackend = FakePersistentCacheStore(listOf(original))
        val firstManager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = firstBackend,
            clock = FakeCacheClock(now),
        )
        val firstResource = resource(
            manager = firstManager,
            namespace = namespace,
            codec = MigratingTestStringCodec(),
        ) { FetchSuccess("unused") }

        assertEquals("legacy", firstResource.current("entry").value)
        val rewritten = requireNotNull(firstBackend.record(address(namespace, "entry")))
        assertEquals(2, rewritten.payloadVersion)
        assertEquals("v2:legacy", rewritten.payload.decodeToString())
        assertEquals(original.metadata, rewritten.metadata)
        assertEquals(original.writtenAt, rewritten.writtenAt)
        assertEquals(original.lastAccessedAt, rewritten.lastAccessedAt)
        assertEquals(1, firstBackend.writeCount)
        firstManager.close()

        val secondBackend = FakePersistentCacheStore(listOf(rewritten))
        val secondManager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = secondBackend,
            clock = FakeCacheClock(now),
        )
        val secondResource = resource(
            manager = secondManager,
            namespace = namespace,
            codec = MigratingTestStringCodec(),
        ) { FetchSuccess("unused") }

        assertEquals("legacy", secondResource.current("entry").value)
        assertEquals(0, secondBackend.writeCount)
        secondManager.close()
    }

    /** Verifies malformed current payloads are evicted without starting observation source work. */
    @Test
    fun `malformed payload is evicted without breaking observation`() = runTest {
        val namespace = "persistence.malformed.v2"
        val malformed = record(namespace, "entry", "malformed", metadata(now)).copy(
            payloadVersion = 2,
        )
        val backend = FakePersistentCacheStore(listOf(malformed))
        backend.readRelease = CompletableDeferred()
        var fetchCount = 0
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = backend,
            clock = FakeCacheClock(now),
        )
        val resource = resource(
            manager = manager,
            namespace = namespace,
            codec = MigratingTestStringCodec(),
        ) {
            fetchCount += 1
            FetchSuccess("source")
        }

        val observation = async {
            resource.observe("entry")
                .dropWhile { snapshot -> snapshot.activity != CacheActivity.HYDRATING }
                .first { snapshot -> snapshot.activity == CacheActivity.IDLE }
        }
        backend.readStarted.await()
        backend.readRelease?.complete(Unit)
        val settled = observation.await()

        assertNull(settled.value)
        assertNull(backend.record(address(namespace, "entry")))
        assertEquals(0, fetchCount)
        manager.close()
    }

    /** Verifies oversized stored and newly encoded payloads never remain on disk. */
    @Test
    fun `payload size limit is enforced on reads and writes`() = runTest {
        val namespace = "persistence.payload-limit.v1"
        val oversized = record(namespace, "stored", "12345", metadata(now))
        val backend = FakePersistentCacheStore(listOf(oversized))
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            persistentStore = backend,
            clock = FakeCacheClock(now),
        )
        val resource = resource(
            manager = manager,
            namespace = namespace,
            maxPayloadBytes = 4,
        ) { FetchSuccess("unused") }

        assertNull(resource.current("stored").value)
        assertNull(backend.record(address(namespace, "stored")))

        resource.put("new", "12345")
        assertEquals("12345", resource.current("new").value)
        assertNull(backend.record(address(namespace, "new")))
        assertEquals(0, backend.writeCount)
        manager.close()
    }

    /**
     * Creates one persistent string resource using the supplied loader.
     *
     * @param manager Manager that owns the returned resource.
     * @param namespace Stable namespace unique within [manager].
     * @param freshness Freshness policy applied to source writes.
     * @param maxEntries Memory bound used by eviction and rehydration scenarios.
     * @param codec Payload codec used by persistent storage.
     * @param maxPayloadBytes Maximum encoded payload size accepted by persistence.
     * @param fetch Deterministic source loader bound to the resource.
     * @return Persistent string resource used by the test.
     */
    private fun resource(
        manager: dev.voir.cache.core.CacheManager,
        namespace: String,
        freshness: FreshnessPolicy = NeverExpire,
        maxEntries: Int = 8,
        codec: CacheCodec<String> = TestStringCodec(),
        maxPayloadBytes: Int = 1_048_576,
        fetch: suspend (String) -> FetchSuccess<String>,
    ): CachedResource<String, String, String> = manager.resource(
        spec = spec(namespace, freshness, maxEntries, codec, maxPayloadBytes),
        fetch = fetch,
        mapUnexpectedFailure = { throwable -> throwable.message ?: "unexpected" },
    )

    /**
     * Creates a persistent string resource specification.
     *
     * @param namespace Stable resource and codec identity.
     * @param freshness Freshness policy applied to validated values.
     * @param maxEntries Per-resource memory entry bound.
     * @param codec Payload codec used by persistent storage.
     * @param maxPayloadBytes Maximum encoded payload size accepted by persistence.
     * @return Typed string specification using the deterministic test codec.
     */
    private fun spec(
        namespace: String,
        freshness: FreshnessPolicy,
        maxEntries: Int,
        codec: CacheCodec<String> = TestStringCodec(),
        maxPayloadBytes: Int = 1_048_576,
    ): CacheSpec<String, String> = cacheSpec(
        namespace = namespace,
        key = { key -> EncodedCacheKey(partition, key) },
        freshness = freshness,
        storage = persistent(codec, maxPayloadBytes),
        memory = MemoryPolicy(maxEntries = maxEntries),
    )

    /**
     * Creates a complete persistent address for [namespace] and [key].
     *
     * @param namespace Resource namespace owning the record.
     * @param key Normalized key inside the shared test partition.
     * @return Complete encoded persistent address.
     */
    private fun address(namespace: String, key: String): CacheAddress = CacheAddress(
        namespace = CacheNamespace(namespace),
        partition = partition,
        key = key,
    )

    /**
     * Creates one encoded persistent test record.
     *
     * @param namespace Resource namespace owning the record.
     * @param key Normalized identity inside the test partition.
     * @param value String value encoded as UTF-8 payload bytes.
     * @param metadata Validation metadata stored with the payload.
     * @return Complete version-one persistent record.
     */
    private fun record(
        namespace: String,
        key: String,
        value: String,
        metadata: CacheMetadata,
    ): PersistedCacheRecord = PersistedCacheRecord(
        address = address(namespace, key),
        payload = value.encodeToByteArray(),
        payloadVersion = 1,
        metadata = metadata,
        writtenAt = metadata.validatedAt,
        lastAccessedAt = metadata.validatedAt,
    )

    /**
     * Creates never-expiring metadata validated at [validatedAt].
     *
     * @param validatedAt Authoritative validation instant.
     * @return Metadata without expiration or invalidation.
     */
    private fun metadata(validatedAt: Instant): CacheMetadata = CacheMetadata(
        validatedAt = validatedAt,
        expiresAt = null,
    )
}
