package dev.voir.cache.core

import dev.voir.cache.core.config.FamilyCacheSpec
import dev.voir.cache.core.config.familyCacheSpec
import dev.voir.cache.core.diagnostics.CacheEvent
import dev.voir.cache.core.diagnostics.CacheOperationKind
import dev.voir.cache.core.fixture.FakePersistentCacheStore
import dev.voir.cache.core.fixture.TestStringCodec
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheFamily
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.model.CacheDiscarded
import dev.voir.cache.core.model.CacheFreshness
import dev.voir.cache.core.model.CachePersistenceStatus
import dev.voir.cache.core.model.CacheSuccess
import dev.voir.cache.core.model.FetchSuccess
import dev.voir.cache.core.persistence.CacheAddress
import dev.voir.cache.core.persistence.ExperimentalCacheStoreApi
import dev.voir.cache.core.policy.MemoryPolicy
import dev.voir.cache.core.policy.NeverExpire
import dev.voir.cache.core.storage.memoryOnly
import dev.voir.cache.core.storage.persistent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifies typed family scope, persistence, revision safety, and diagnostic privacy. */
@OptIn(ExperimentalCacheStoreApi::class)
class FamilyInvalidationTest {
    /** Family invalidation retains matching values as stale and leaves every other scope fresh. */
    @Test
    fun `invalidate family is exact across partition namespace and family`() = runTest {
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = memorySpec("family.invalidate.v1"),
            fetch = { key -> FetchSuccess(key.item) },
            mapUnexpectedFailure = ::failure,
        )
        val siblingNamespace = manager.resource(
            spec = memorySpec("family.invalidate.sibling.v1"),
            fetch = { key -> FetchSuccess(key.item) },
            mapUnexpectedFailure = ::failure,
        )
        val a1 = FamilyKey("tenant-a", "account-1", "one")
        val a2 = FamilyKey("tenant-a", "account-1", "two")
        val otherFamily = FamilyKey("tenant-a", "account-2", "three")
        val otherPartition = FamilyKey("tenant-b", "account-1", "four")
        listOf(a1, a2, otherFamily, otherPartition).forEach { key -> resource.put(key, key.item) }
        siblingNamespace.put(a1, "sibling")

        val result = resource.invalidateFamily(FamilyId("tenant-a", "account-1"))

        assertEquals(2, result.memoryEntriesAffected)
        assertEquals(0, result.persistentRowsAffected)
        assertEquals(CachePersistenceStatus.NOT_CONFIGURED, result.persistenceStatus)
        assertEquals(CacheFreshness.STALE, resource.peek(a1).freshness)
        assertEquals(CacheFreshness.STALE, resource.peek(a2).freshness)
        assertEquals(CacheFreshness.FRESH, resource.peek(otherFamily).freshness)
        assertEquals(CacheFreshness.FRESH, resource.peek(otherPartition).freshness)
        assertEquals(CacheFreshness.FRESH, siblingNamespace.peek(a1).freshness)
        assertEquals(0, resource.removeFamily(FamilyId("tenant-a", "missing")).memoryEntriesAffected)
        manager.close()
    }

    /** Family removal returns exact counts and durably removes only matching rows. */
    @Test
    fun `remove family deletes memory and persistent rows`() = runTest {
        val backend = FakePersistentCacheStore()
        val manager = createCacheManager(backgroundScope.coroutineContext, backend)
        val resource = manager.resource(
            spec = persistentSpec("family.remove.v1"),
            fetch = { key -> FetchSuccess(key.item) },
            mapUnexpectedFailure = ::failure,
        )
        val first = FamilyKey("tenant", "account-1", "one")
        val second = FamilyKey("tenant", "account-1", "two")
        val unrelated = FamilyKey("tenant", "account-2", "three")
        listOf(first, second, unrelated).forEach { key -> resource.put(key, key.item) }

        val invalidated = resource.invalidateFamily(FamilyId("tenant", "account-1"))
        assertEquals(2, invalidated.memoryEntriesAffected)
        assertEquals(2, invalidated.persistentRowsAffected)
        assertEquals(CacheFreshness.STALE, resource.peek(first).freshness)
        assertNotNull(
            backend.record(address("family.remove.v1", first))?.metadata?.invalidatedAt
        )

        val result = resource.removeFamily(FamilyId("tenant", "account-1"))

        assertEquals(2, result.memoryEntriesAffected)
        assertEquals(2, result.persistentRowsAffected)
        assertEquals(CachePersistenceStatus.AVAILABLE, result.persistenceStatus)
        assertNull(resource.peek(first).value)
        assertNull(resource.peek(second).value)
        assertEquals("three", resource.peek(unrelated).value)
        assertNull(backend.record(address("family.remove.v1", first)))
        assertNull(backend.record(address("family.remove.v1", second)))
        assertNotNull(backend.record(address("family.remove.v1", unrelated)))
        manager.close()
    }

    /** Persistent family failure keeps memory authoritative and blocks stale rehydration. */
    @Test
    fun `failed family removal degrades without restoring old persistent rows`() = runTest {
        val backend = FakePersistentCacheStore()
        val manager = createCacheManager(backgroundScope.coroutineContext, backend)
        val resource = manager.resource(
            spec = persistentSpec("family.remove-failure.v1"),
            fetch = { key -> FetchSuccess(key.item) },
            mapUnexpectedFailure = ::failure,
        )
        val key = FamilyKey("tenant", "account", "one")
        resource.put(key, "cached")
        backend.failRemovals = true

        val result = resource.removeFamily(FamilyId("tenant", "account"))

        assertEquals(1, result.memoryEntriesAffected)
        assertNull(result.persistentRowsAffected)
        assertEquals(CachePersistenceStatus.UNAVAILABLE, result.persistenceStatus)
        assertNull(resource.current(key).value)
        assertNotNull(backend.record(address("family.remove-failure.v1", key)))
        manager.close()
    }

    /** Older work in the selected family is discarded while unrelated work still commits. */
    @Test
    fun `family removal supersedes only matching source work`() = runTest {
        val selectedStarted = CompletableDeferred<Unit>()
        val unrelatedStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = memorySpec("family.race.v1"),
            fetch = { key ->
                if (key.family == "account-1") selectedStarted.complete(Unit)
                else unrelatedStarted.complete(Unit)
                release.await()
                FetchSuccess("loaded-${key.item}")
            },
            mapUnexpectedFailure = ::failure,
        )
        val selected = FamilyKey("tenant", "account-1", "one")
        val unrelated = FamilyKey("tenant", "account-2", "two")
        val selectedLoad = async { resource.get(selected) }
        val unrelatedLoad = async { resource.get(unrelated) }
        selectedStarted.await()
        unrelatedStarted.await()

        resource.removeFamily(FamilyId("tenant", "account-1"))
        release.complete(Unit)

        assertIs<CacheDiscarded<String, String>>(selectedLoad.await())
        assertEquals("loaded-two", assertIs<CacheSuccess<String>>(unrelatedLoad.await()).value)
        assertNull(resource.peek(selected).value)
        assertEquals("loaded-two", resource.peek(unrelated).value)
        manager.close()
    }

    /** Diagnostics expose counts and fingerprints but never the encoded family value. */
    @Test
    fun `family diagnostics are count based and privacy safe`() = runTest {
        val events = mutableListOf<CacheEvent>()
        val manager = createCacheManager(
            backgroundScope.coroutineContext,
            eventListener = events::add,
        )
        val resource = manager.resource(
            spec = memorySpec("family.diagnostics.v1"),
            fetch = { FetchSuccess("unused") },
            mapUnexpectedFailure = ::failure,
        )
        resource.put(FamilyKey("tenant", "private-account", "one"), "value")

        resource.removeFamily(FamilyId("tenant", "private-account"))
        manager.close()

        val completion = events.single { event ->
            event.operation?.operation == CacheOperationKind.REMOVE_FAMILY &&
                event.operation.outcome != null
        }
        assertEquals(1, completion.operation?.affectedCount)
        assertFalse(completion.toString().contains("private-account"))
        assertTrue(completion.keyFingerprint == null)
    }

    private fun memorySpec(namespace: String): FamilyCacheSpec<FamilyKey, FamilyId, String> =
        familySpec(namespace, persistent = false)

    private fun persistentSpec(namespace: String): FamilyCacheSpec<FamilyKey, FamilyId, String> =
        familySpec(namespace, persistent = true)

    private fun familySpec(
        namespace: String,
        persistent: Boolean,
    ): FamilyCacheSpec<FamilyKey, FamilyId, String> = familyCacheSpec(
        namespace = namespace,
        key = { key -> EncodedCacheKey(CachePartition(key.partition), key.item) },
        familyOf = { key -> FamilyId(key.partition, key.family) },
        family = { family ->
            EncodedCacheFamily(CachePartition(family.partition), family.value)
        },
        freshness = NeverExpire,
        storage = if (persistent) persistent(TestStringCodec()) else memoryOnly(),
        memory = MemoryPolicy(maxEntries = 16),
    )

    private fun address(namespace: String, key: FamilyKey): CacheAddress = CacheAddress(
        namespace = CacheNamespace(namespace),
        partition = CachePartition(key.partition),
        key = key.item,
        family = key.family,
    )

    private fun failure(throwable: Throwable): String = throwable.message ?: "unexpected"
}

private data class FamilyKey(val partition: String, val family: String, val item: String)

private data class FamilyId(val partition: String, val value: String)
