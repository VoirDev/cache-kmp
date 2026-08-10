package dev.voir.cache.core

import dev.voir.cache.core.config.CacheManagerConfig
import dev.voir.cache.core.config.CacheSpec
import dev.voir.cache.core.config.cacheSpec
import dev.voir.cache.core.diagnostics.CacheEventType
import dev.voir.cache.core.diagnostics.CacheOperationKind
import dev.voir.cache.core.diagnostics.CacheOperationOutcome
import dev.voir.cache.core.fixture.FakePersistentCacheStore
import dev.voir.cache.core.fixture.TestStringCodec
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.model.FetchSuccess
import dev.voir.cache.core.persistence.ExperimentalCacheStoreApi
import dev.voir.cache.core.policy.MemoryPolicy
import dev.voir.cache.core.policy.NeverExpire
import dev.voir.cache.core.storage.memoryOnly
import dev.voir.cache.core.storage.persistent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Verifies manager ownership, shutdown, registration, and address validation. */
@OptIn(ExperimentalCacheStoreApi::class)
class CacheManagerLifecycleTest {
    /** Verifies close cancels active source work and rejects every later command. */
    @Test
    fun `close cancels source work and rejects later commands`() = runTest {
        val started = CompletableDeferred<Unit>()
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = spec("lifecycle.close.v1"),
            fetch = {
                started.complete(Unit)
                awaitCancellation()
            },
            mapUnexpectedFailure = ::message,
        )
        val refresh = async { resource.refresh("entry") }
        started.await()

        manager.close()

        assertTrue(refresh.isCancelled)
        assertEquals(1, manager.statistics().outcomeCounts[CacheOperationOutcome.CANCELLED])
        assertEquals(1, manager.statistics().operationCounts[CacheOperationKind.CLOSE])
        assertFailsWith<IllegalStateException> { resource.peek("entry") }
        assertFailsWith<IllegalStateException> { resource.current("entry") }
        assertFailsWith<IllegalStateException> { resource.observe("entry") }
        assertFailsWith<IllegalStateException> {
            manager.resource(
                spec = spec("lifecycle.after-close.v1"),
                fetch = { FetchSuccess("unused") },
                mapUnexpectedFailure = ::message,
            )
        }
    }

    /** Verifies manager close is idempotent and cannot cancel the host's parent job. */
    @Test
    fun `close is idempotent and preserves parent job`() = runTest {
        val parent = Job(currentCoroutineContext()[Job])
        val manager = createCacheManager(currentCoroutineContext() + parent)

        manager.close()
        manager.close()

        assertTrue(parent.isActive)
        assertFalse(parent.children.any { child -> child.isActive })
        parent.cancel()
    }

    /** Verifies concurrent close callers share one store close and one terminal result. */
    @Test
    fun `concurrent close callers wait for one shutdown`() = runTest {
        val backend = FakePersistentCacheStore().apply {
            closeRelease = CompletableDeferred()
        }
        val manager = createCacheManager(backgroundScope.coroutineContext, backend)

        val first = async { manager.close() }
        backend.closeStarted.await()
        val second = async { manager.close() }
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)

        assertNotNull(backend.closeRelease).complete(Unit)
        first.await()
        second.await()

        assertEquals(1, backend.closeCount)
        assertTrue(backend.isClosed)
    }

    /** Verifies cancellation of the initiating caller cannot abandon manager cleanup. */
    @Test
    fun `cancelled close caller does not cancel shutdown`() = runTest {
        val backend = FakePersistentCacheStore().apply {
            closeRelease = CompletableDeferred()
        }
        val manager = createCacheManager(backgroundScope.coroutineContext, backend)

        val closer = launch { manager.close() }
        backend.closeStarted.await()
        closer.cancel()
        assertFalse(closer.isCompleted)

        assertNotNull(backend.closeRelease).complete(Unit)
        closer.join()
        manager.close()

        assertEquals(1, backend.closeCount)
        assertTrue(backend.isClosed)
    }

    /** Verifies close waits for an already admitted persistent write before closing its backend. */
    @Test
    fun `close drains admitted persistent write`() = runTest {
        val backend = FakePersistentCacheStore().apply {
            writeRelease = CompletableDeferred()
        }
        val manager = createCacheManager(backgroundScope.coroutineContext, backend)
        val resource = manager.resource(
            spec = persistentSpec("lifecycle.write.v1"),
            fetch = { FetchSuccess("unused") },
            mapUnexpectedFailure = ::message,
        )

        val write = async { resource.put("entry", "value") }
        backend.writeStarted.await()
        val close = async { manager.close() }
        assertFalse(close.isCompleted)
        assertFalse(backend.closeStarted.isCompleted)

        assertNotNull(backend.writeRelease).complete(Unit)
        write.await()
        close.await()

        assertEquals(1, backend.writeCount)
        assertEquals(1, backend.closeCount)
        assertTrue(backend.isClosed)
    }

    /** Verifies shutdown cancels manager-owned persistent hydration before closing its backend. */
    @Test
    fun `close cancels active hydration`() = runTest {
        val backend = FakePersistentCacheStore().apply {
            readRelease = CompletableDeferred()
        }
        val manager = createCacheManager(backgroundScope.coroutineContext, backend)
        val resource = manager.resource(
            spec = persistentSpec("lifecycle.hydration.v1"),
            fetch = { FetchSuccess("unused") },
            mapUnexpectedFailure = ::message,
        )
        val hydration = async { resource.current("entry") }
        backend.readStarted.await()

        manager.close()

        assertTrue(hydration.isCancelled)
        assertEquals(1, backend.closeCount)
        assertTrue(backend.isClosed)
    }

    /** Verifies commands are rejected as soon as shutdown starts, not only after it finishes. */
    @Test
    fun `commands during close are rejected`() = runTest {
        val backend = FakePersistentCacheStore().apply {
            closeRelease = CompletableDeferred()
        }
        val manager = createCacheManager(backgroundScope.coroutineContext, backend)
        val resource = manager.resource(
            spec = spec("lifecycle.closing.v1"),
            fetch = { FetchSuccess("unused") },
            mapUnexpectedFailure = ::message,
        )

        val close = async { manager.close() }
        backend.closeStarted.await()

        assertFailsWith<IllegalStateException> { resource.current("entry") }
        assertFailsWith<IllegalStateException> { manager.clearAll() }
        assertNotNull(backend.closeRelease).complete(Unit)
        close.await()
    }

    /** Verifies every close caller receives the same persistent shutdown failure. */
    @Test
    fun `persistent close failure is shared and manager remains terminal`() = runTest {
        val backend = FakePersistentCacheStore().apply {
            failClose = true
            closeRelease = CompletableDeferred()
        }
        val manager = createCacheManager(backgroundScope.coroutineContext, backend)

        val first = async {
            assertFailsWith<IllegalStateException> { manager.close() }.message
        }
        backend.closeStarted.await()
        val second = async {
            assertFailsWith<IllegalStateException> { manager.close() }.message
        }
        assertNotNull(backend.closeRelease).complete(Unit)

        assertEquals("Injected persistent close failure.", first.await())
        assertEquals("Injected persistent close failure.", second.await())
        assertEquals(1, backend.closeCount)
        assertEquals(1, manager.statistics().outcomeCounts[CacheOperationOutcome.UNAVAILABLE])
        assertFailsWith<IllegalStateException> { manager.clearAll() }
    }

    /** Verifies close drains listener delivery and makes the lifecycle event terminal. */
    @Test
    fun `close drains diagnostic listener`() = runTest {
        val events = mutableListOf<CacheEventType>()
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            eventListener = { event -> events += event.type },
        )

        manager.close()

        assertEquals(CacheEventType.LIFECYCLE_CLOSE, events.last())
    }

    /** Verifies the coroutine-native owner recreates managers across host lifecycles. */
    @Test
    fun `restartable owner serializes start and stop`() = runTest {
        val created = mutableListOf<CacheManager>()
        val owner = RestartableCacheManagerOwner {
            createCacheManager(backgroundScope.coroutineContext).also(created::add)
        }

        val first = owner.start()
        assertSame(first, owner.start())
        assertSame(first, owner.current())
        owner.stop()
        assertNull(owner.current())
        assertFailsWith<IllegalStateException> { first.clearAll() }

        val second = owner.start()
        assertTrue(first !== second)
        assertEquals(2, created.size)
        owner.close()
        owner.close()
        assertNull(owner.current())
    }

    /** Verifies cancellation cannot leave a detached owner manager running. */
    @Test
    fun `cancelled owner stop still completes manager shutdown`() = runTest {
        val backend = FakePersistentCacheStore().apply {
            closeRelease = CompletableDeferred()
        }
        val owner = RestartableCacheManagerOwner {
            createCacheManager(backgroundScope.coroutineContext, backend)
        }
        owner.start()

        val stop = launch { owner.stop() }
        backend.closeStarted.await()
        stop.cancel()
        assertNotNull(backend.closeRelease).complete(Unit)
        stop.join()

        assertTrue(backend.isClosed)
        assertNull(owner.current())
    }

    /** Verifies a manager binds only one loader and store to a namespace. */
    @Test
    fun `duplicate resource namespace is rejected`() = runTest {
        val manager = createCacheManager(backgroundScope.coroutineContext)
        manager.resource(
            spec = spec("lifecycle.duplicate.v1"),
            fetch = { FetchSuccess("first") },
            mapUnexpectedFailure = ::message,
        )

        assertFailsWith<IllegalArgumentException> {
            manager.resource(
                spec = spec("lifecycle.duplicate.v1"),
                fetch = { FetchSuccess("second") },
                mapUnexpectedFailure = ::message,
            )
        }
        manager.close()
    }

    /** Verifies manager limits are applied after application key encoding. */
    @Test
    fun `encoded address limits are enforced`() = runTest {
        val manager = createCacheManager(
            coroutineContext = backgroundScope.coroutineContext,
            config = CacheManagerConfig(
                maxNamespaceLength = 16,
                maxPartitionLength = 4,
                maxKeyLength = 4,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            manager.resource(
                spec = spec("namespace-is-too-long"),
                fetch = { FetchSuccess("unused") },
                mapUnexpectedFailure = ::message,
            )
        }

        val resource = manager.resource(
            spec = cacheSpec(
                namespace = "limits.v1",
                key = { key: String ->
                    EncodedCacheKey(CachePartition("partition-too-long"), key)
                },
                freshness = NeverExpire,
                storage = memoryOnly(),
                memory = MemoryPolicy(maxEntries = 2),
            ),
            fetch = { FetchSuccess("unused") },
            mapUnexpectedFailure = ::message,
        )
        assertFailsWith<IllegalArgumentException> { resource.current("key") }
        manager.close()
    }

    /** Verifies persistent resources fail fast until a manager store is configured. */
    @Test
    fun `persistent resource without store is rejected`() = runTest {
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val persistentSpec = cacheSpec(
            namespace = "lifecycle.persistent.v1",
            key = { key: String -> EncodedCacheKey(CachePartition("test"), key) },
            freshness = NeverExpire,
            storage = persistent(TestStringCodec()),
            memory = MemoryPolicy(maxEntries = 2),
        )

        assertFailsWith<IllegalArgumentException> {
            manager.resource(
                spec = persistentSpec,
                fetch = { FetchSuccess("unused") },
                mapUnexpectedFailure = ::message,
            )
        }
        manager.close()
    }

    /**
     * Creates a default memory-only string specification.
     *
     * @param namespace Stable namespace unique within the test manager.
     * @return Typed string resource specification.
     */
    private fun spec(namespace: String): CacheSpec<String, String> = cacheSpec(
        namespace = namespace,
        key = { key -> EncodedCacheKey(CachePartition("test"), key) },
        freshness = NeverExpire,
        storage = memoryOnly(),
        memory = MemoryPolicy(maxEntries = 4),
    )

    /** Creates a persistent string specification for lifecycle ordering tests. */
    private fun persistentSpec(namespace: String): CacheSpec<String, String> = cacheSpec(
        namespace = namespace,
        key = { key -> EncodedCacheKey(CachePartition("test"), key) },
        freshness = NeverExpire,
        storage = persistent(TestStringCodec()),
        memory = MemoryPolicy(maxEntries = 4),
    )

    /**
     * Maps a throwable into a stable test error.
     *
     * @param throwable Unexpected loader failure.
     * @return Failure message or a deterministic fallback.
     */
    private fun message(throwable: Throwable): String = throwable.message ?: "unexpected"
}
