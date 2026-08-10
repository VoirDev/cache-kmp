package dev.voir.cache.core.characterization

import dev.voir.cache.core.fixture.FakeCacheClock
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.memory.InMemoryResourceStore
import dev.voir.cache.core.policy.MemoryPolicy
import dev.voir.cache.core.policy.NeverExpire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/** Verifies the deterministic bound on per-resource memory use. */
class BoundedMemoryCharacterizationTest {
    /**
     * Requires the least-recently-used unpinned entry to be evicted at the configured limit.
     */
    @Test
    fun `least recently used unpinned entry is evicted at limit`() = runTest {
        val store = InMemoryResourceStore<String, String>(
            namespace = CacheNamespace("characterization.values.v1"),
            freshnessPolicy = NeverExpire,
            memoryPolicy = MemoryPolicy(maxEntries = 2),
            clock = FakeCacheClock(Instant.parse("2026-08-10T10:00:00Z")),
        )
        val partition = CachePartition("tenant-a")
        val first = EncodedCacheKey(partition, "first")
        val second = EncodedCacheKey(partition, "second")
        val third = EncodedCacheKey(partition, "third")
        store.put(first, "first")
        store.put(second, "second")
        store.current(first)

        store.put(third, "third")

        assertEquals("first", store.current(first).value)
        assertNull(store.current(second).value)
        assertEquals("third", store.current(third).value)
    }
}
