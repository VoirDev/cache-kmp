package dev.voir.cache.core.memory

import dev.voir.cache.core.fixture.FakeCacheClock
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.model.CacheActivity
import dev.voir.cache.core.model.CacheFreshness
import dev.voir.cache.core.policy.ExpireAfter
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

    /** Verifies authoritative put creates one complete value and metadata transition. */
    @Test
    fun `put creates fresh value and metadata`() = runTest {
        val store = newStore()
        val key = key("item")

        store.put(key, "loaded")
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
        store.put(key, "loaded")

        clock.advanceBy(5.minutes)

        assertEquals(CacheFreshness.STALE, store.current(key).freshness)
        assertEquals(CacheFreshness.STALE, store.observe(key).first().freshness)
    }

    /** Verifies a zero TTL creates a usable value that is immediately stale. */
    @Test
    fun `zero TTL is always stale`() = runTest {
        val store = newStore(freshnessPolicy = ExpireAfter(kotlin.time.Duration.ZERO))
        val key = key("item")

        store.put(key, "loaded")

        assertEquals("loaded", store.current(key).value)
        assertEquals(CacheFreshness.STALE, store.current(key).freshness)
    }

    /** Verifies non-expiring data remains fresh as the injected clock advances. */
    @Test
    fun `never-expire remains fresh until invalidated`() = runTest {
        val clock = FakeCacheClock(initialTime)
        val store = newStore(freshnessPolicy = NeverExpire, clock = clock)
        val key = key("item")
        store.put(key, "loaded")

        clock.advanceBy(365.days)

        assertEquals(CacheFreshness.FRESH, store.current(key).freshness)
    }

    /** Verifies update changes only existing data and preserves validation metadata. */
    @Test
    fun `update preserves freshness and metadata`() = runTest {
        val store = newStore()
        val key = key("item")
        store.put(key, "loaded")
        store.invalidate(key)
        val before = store.current(key)

        assertTrue(store.update(key) { value -> "$value:patched" })
        val after = store.current(key)

        assertEquals("loaded:patched", after.value)
        assertEquals(CacheFreshness.STALE, after.freshness)
        assertEquals(before.metadata, after.metadata)
        assertFalse(store.update(key("missing")) { value -> value })
    }

    /** Verifies a throwing transform cannot publish a partial value or metadata change. */
    @Test
    fun `failed transform leaves state unchanged`() = runTest {
        val store = newStore()
        val key = key("item")
        store.put(key, "loaded")
        val before = store.current(key)

        assertFailsWith<IllegalStateException> {
            store.update(key) { error("Transform failed") }
        }

        assertEquals(before, store.current(key))
    }

    /** Verifies invalidation retains data and validation time while recording invalidation. */
    @Test
    fun `invalidate retains value and marks it stale`() = runTest {
        val store = newStore()
        val key = key("item")
        store.put(key, "loaded")

        store.invalidate(key)
        val snapshot = store.current(key)

        assertEquals("loaded", snapshot.value)
        assertEquals(CacheFreshness.STALE, snapshot.freshness)
        assertEquals(initialTime, snapshot.metadata?.validatedAt)
        assertEquals(initialTime, snapshot.metadata?.invalidatedAt)
    }

    /** Verifies removal emits absent state to an already active observer. */
    @Test
    fun `remove publishes absent state`() = runTest {
        val store = newStore()
        val key = key("item")
        store.put(key, "loaded")
        val removed = async(start = CoroutineStart.UNDISPATCHED) {
            store.observe(key).drop(1).first()
        }

        store.remove(key)

        assertEquals(CacheFreshness.ABSENT, removed.await().freshness)
        assertNull(store.current(key).value)
    }

    /** Verifies partition and namespace clearing emit absent state to active observers. */
    @Test
    fun `clear publishes absent state`() = runTest {
        val store = newStore()
        val partitionKey = key("partition-item")
        store.put(partitionKey, "partition")
        val partitionCleared = async(start = CoroutineStart.UNDISPATCHED) {
            store.observe(partitionKey).drop(1).first()
        }

        store.clearPartition(partition)
        assertEquals(CacheFreshness.ABSENT, partitionCleared.await().freshness)

        val namespaceKey = key("namespace-item")
        store.put(namespaceKey, "namespace")
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
            async { store.put(key, "value-$index") }
        }.awaitAll()

        assertEquals(100, store.entryCount())
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
        store.put(first, "first")
        store.put(second, "second")
        store.current(first)

        store.put(third, "third")

        assertEquals("first", store.current(first).value)
        assertNull(store.current(second).value)
        assertEquals("third", store.current(third).value)
        assertEquals(2, store.entryCount())
    }

    /** Verifies an observed entry is retained until its collector detaches. */
    @Test
    fun `active observer pins entry until detachment`() = runTest {
        val store = newStore(maxEntries = 1)
        val observedKey = key("observed")
        val newestKey = key("newest")
        store.put(observedKey, "observed")
        val observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.observe(observedKey).collect()
        }

        store.put(newestKey, "newest")
        assertEquals(2, store.entryCount())

        observer.cancelAndJoin()
        runCurrent()

        assertEquals(1, store.entryCount())
        assertNull(store.current(observedKey).value)
        assertEquals("newest", store.current(newestKey).value)
    }

    /** Verifies manager-owned operation work defers eviction until its pin is released. */
    @Test
    fun `active operation pins entry until completion`() = runTest {
        val store = newStore(maxEntries = 1)
        val activeKey = key("active")
        val newestKey = key("newest")
        store.put(activeKey, "active")
        store.pinOperation(activeKey)

        store.put(newestKey, "newest")
        assertEquals(2, store.entryCount())

        store.unpinOperation(activeKey)

        assertEquals(1, store.entryCount())
        assertNull(store.current(activeKey).value)
        assertEquals("newest", store.current(newestKey).value)
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
        freshnessPolicy: dev.voir.cache.core.policy.FreshnessPolicy = ExpireAfter(5.minutes),
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
