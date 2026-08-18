package dev.voir.cache.core.memory

import dev.voir.cache.core.fixture.FakeCacheClock
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.model.CacheActivity
import dev.voir.cache.core.model.CacheFreshness
import dev.voir.cache.core.policy.ExpireAfter
import dev.voir.cache.core.policy.FreshnessPolicy
import dev.voir.cache.core.policy.MemoryPolicy
import dev.voir.cache.core.policy.NeverExpire
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Verifies atomic state transitions, freshness, concurrency, and bounded LRU behavior. */
@OptIn(ExperimentalCoroutinesApi::class)
class InMemoryResourceStoreTest {
    private val initialTime = Instant.parse("2026-08-10T10:00:00Z")
    private val partition = CachePartition("tenant-a")

    /** Verifies an unknown key immediately emits fully absent idle state. */
    @Test
    fun `empty store emits absent state`() = runTest {
        val snapshot = newStore().observe(key("missing")).first()

        assertNull(snapshot.value)
        assertEquals(CacheFreshness.ABSENT, snapshot.freshness)
        assertEquals(CacheActivity.IDLE, snapshot.activity)
        assertNull(snapshot.metadata)
    }

    /** Verifies a committed refresh creates one complete value and metadata transition. */
    @Test
    fun `committed refresh creates fresh value and metadata`() = runTest {
        val store = newStore()
        val key = key("item")

        store.commit(key, "loaded")
        val snapshot = store.current(key)

        assertEquals("loaded", snapshot.value)
        assertEquals(CacheFreshness.FRESH, snapshot.freshness)
        assertEquals(initialTime, snapshot.metadata?.validatedAt)
        assertEquals(initialTime + 5.minutes, snapshot.metadata?.expiresAt)
    }

    /** Verifies expiration is inclusive at the exact TTL boundary and is observable. */
    @Test
    fun `TTL expires at exact boundary`() = runTest {
        val clock = FakeCacheClock(initialTime)
        val store = newStore(clock = clock)
        val key = key("item")
        store.commit(key, "loaded")

        clock.advanceBy(5.minutes)

        assertEquals(CacheFreshness.STALE, store.current(key).freshness)
        assertEquals(CacheFreshness.STALE, store.observe(key).first().freshness)
    }

    /** Verifies a zero TTL creates a usable value that is immediately stale. */
    @Test
    fun `zero TTL is always stale`() = runTest {
        val store = newStore(freshnessPolicy = ExpireAfter(Duration.ZERO))
        val key = key("item")

        store.commit(key, "loaded")

        assertEquals("loaded", store.current(key).value)
        assertEquals(CacheFreshness.STALE, store.current(key).freshness)
    }

    /** Verifies non-expiring data remains fresh as the injected clock advances. */
    @Test
    fun `never-expire remains fresh`() = runTest {
        val clock = FakeCacheClock(initialTime)
        val store = newStore(freshnessPolicy = NeverExpire, clock = clock)
        val key = key("item")
        store.commit(key, "loaded")

        clock.advanceBy(365.days)

        assertEquals(CacheFreshness.FRESH, store.current(key).freshness)
    }

    /** Verifies a failed refresh keeps the previous value and publishes the transient error. */
    @Test
    fun `failed refresh retains value and publishes error`() = runTest {
        val store = newStore()
        val key = key("item")
        store.commit(key, "loaded")

        val refresh = store.beginRefresh(key)
        assertTrue(store.completeRefreshFailure(key, refresh.revision, "boom"))
        store.unpinOperation(key)
        val snapshot = store.current(key)

        assertEquals("loaded", snapshot.value)
        assertEquals("boom", snapshot.refreshError)
        assertEquals(CacheActivity.IDLE, snapshot.activity)
    }

    /** Verifies a late commit loses its revision race against a newer removal. */
    @Test
    fun `superseded commit is rejected`() = runTest {
        val store = newStore()
        val key = key("item")
        val refresh = store.beginRefresh(key)

        store.remove(key)

        assertNull(store.completeRefreshSuccess(key, refresh.revision, "late"))
        store.unpinOperation(key)
        assertNull(store.current(key).value)
    }

    /** Verifies removal emits absent state to an already active observer. */
    @Test
    fun `remove publishes absent state`() = runTest {
        val store = newStore()
        val key = key("item")
        store.commit(key, "loaded")
        val removed = async(start = CoroutineStart.UNDISPATCHED) {
            store.observe(key).drop(1).first()
        }

        store.remove(key)

        assertEquals(CacheFreshness.ABSENT, removed.await().freshness)
        assertNull(store.current(key).value)
    }

    /** Verifies namespace clearing emits absent state to active observers. */
    @Test
    fun `clear publishes absent state`() = runTest {
        val store = newStore()
        val namespaceKey = key("namespace-item")
        store.commit(namespaceKey, "namespace")
        val namespaceCleared = async(start = CoroutineStart.UNDISPATCHED) {
            store.observe(namespaceKey).drop(1).first()
        }

        store.clearAll()

        assertEquals(CacheFreshness.ABSENT, namespaceCleared.await().freshness)
    }

    /** Verifies concurrent writes to different addresses cannot lose committed entries. */
    @Test
    fun `concurrent writes to different keys do not lose entries`() = runTest {
        val store = newStore(maxEntries = 100)
        val keys = (1..100).map { index -> key("item-$index") }

        keys.mapIndexed { index, key ->
            async { store.commit(key, "value-$index") }
        }.awaitAll()

        assertEquals(100, store.retainedKeys.size)
        keys.forEachIndexed { index, key ->
            assertEquals("value-$index", store.current(key).value)
        }
    }

    /** Verifies access order, rather than insertion order, selects the eviction candidate. */
    @Test
    fun `least recently used entry is evicted`() = runTest {
        val store = newStore(maxEntries = 2)
        val first = key("first")
        val second = key("second")
        val third = key("third")
        store.commit(first, "first")
        store.commit(second, "second")
        store.current(first)

        store.commit(third, "third")

        assertEquals("first", store.current(first).value)
        assertNull(store.current(second).value)
        assertEquals("third", store.current(third).value)
        assertEquals(2, store.retainedKeys.size)
    }

    /**
     * Verifies per-address bookkeeping does not outlive the bounded memory tier.
     *
     * A manager that reads far more distinct addresses than it retains must not accumulate
     * revision or access-order state for every address it has ever seen.
     */
    @Test
    fun `evicted addresses release their bookkeeping`() = runTest {
        val store = newStore(maxEntries = 4)

        repeat(200) { index -> store.commit(key("item-$index"), "value-$index") }

        assertEquals(4, store.retainedKeys.size)
        assertEquals(4, store.trackedAddressCount)
    }

    /** Verifies an observed entry is retained until its collector detaches. */
    @Test
    fun `active observer pins entry until detachment`() = runTest {
        val store = newStore(maxEntries = 1)
        val observedKey = key("observed")
        val newestKey = key("newest")
        store.commit(observedKey, "observed")
        val observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.observe(observedKey).collect()
        }

        store.commit(newestKey, "newest")
        assertEquals(2, store.retainedKeys.size)

        observer.cancelAndJoin()
        runCurrent()

        assertEquals(1, store.retainedKeys.size)
        assertNull(store.current(observedKey).value)
        assertEquals("newest", store.current(newestKey).value)
    }

    /** Verifies manager-owned operation work defers eviction until its pin is released. */
    @Test
    fun `active operation pins entry until completion`() = runTest {
        val store = newStore(maxEntries = 1)
        val activeKey = key("active")
        val newestKey = key("newest")
        store.commit(activeKey, "active")
        val refresh = store.beginRefresh(activeKey)

        store.commit(newestKey, "newest")
        assertEquals(2, store.retainedKeys.size)

        store.cancelRefresh(activeKey, refresh.revision)
        store.unpinOperation(activeKey)

        assertEquals(1, store.retainedKeys.size)
        assertNull(store.current(activeKey).value)
        assertEquals("newest", store.current(newestKey).value)
    }

    /** Commits [value] through the ordinary refresh lifecycle. */
    private suspend fun InMemoryResourceStore<String, String>.commit(
        key: EncodedCacheKey,
        value: String,
    ) {
        val refresh = beginRefresh(key)
        completeRefreshSuccess(key, refresh.revision, value)
        unpinOperation(key)
    }

    /**
     * Creates a deterministic store with the selected policies and clock.
     *
     * @param maxEntries Per-resource memory entry bound.
     * @param freshnessPolicy Policy used to create and evaluate metadata.
     * @param clock Mutable deterministic test clock.
     * @return New isolated string resource store.
     */
    private fun newStore(
        maxEntries: Int = 8,
        freshnessPolicy: FreshnessPolicy = ExpireAfter(5.minutes),
        clock: FakeCacheClock = FakeCacheClock(initialTime),
    ): InMemoryResourceStore<String, String> = InMemoryResourceStore(
        namespace = CacheNamespace("test.values.v1"),
        freshnessPolicy = freshnessPolicy,
        memoryPolicy = MemoryPolicy(maxEntries),
        clock = clock,
    )

    /**
     * Creates one encoded key in the shared test partition.
     *
     * @param value Normalized identity inside the partition.
     * @return Complete encoded test key.
     */
    private fun key(value: String): EncodedCacheKey = EncodedCacheKey(partition, value)
}
