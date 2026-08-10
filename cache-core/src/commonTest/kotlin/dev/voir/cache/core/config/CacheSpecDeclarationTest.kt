package dev.voir.cache.core.config

import dev.voir.cache.core.fixture.FakeDomainKey
import dev.voir.cache.core.fixture.FakeDomainValue
import dev.voir.cache.core.fixture.TestStringCodec
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.key.EncodedCacheFamily
import dev.voir.cache.core.policy.ExpireAfter
import dev.voir.cache.core.policy.MemoryPolicy
import dev.voir.cache.core.policy.NeverExpire
import dev.voir.cache.core.storage.MemoryOnlyStorage
import dev.voir.cache.core.storage.PersistentStorage
import dev.voir.cache.core.storage.memoryOnly
import dev.voir.cache.core.storage.persistent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes

/** Demonstrates domain-owned memory-only and persistent resource declarations. */
class CacheSpecDeclarationTest {
    /** Verifies a domain can declare a memory-only resource without persistence dependencies. */
    @Test
    fun `fake domain declares memory-only spec`() {
        val spec = cacheSpec<FakeDomainKey, FakeDomainValue>(
            namespace = "fake.values.memory.v1",
            key = { key -> encodedKey(key) },
            freshness = NeverExpire,
            storage = memoryOnly(),
            memory = MemoryPolicy(maxEntries = 8),
        )

        assertIs<MemoryOnlyStorage<FakeDomainValue>>(spec.storage)
        assertEquals("fake.values.memory.v1", spec.namespace.value)
        assertEquals("item-1", spec.keyEncoder.encode(sampleKey()).value)
    }

    /** Verifies a domain can declare persistent storage through only the core codec contract. */
    @Test
    fun `fake domain declares persistent spec`() {
        val spec = cacheSpec<FakeDomainKey, String>(
            namespace = "fake.values.persistent.v1",
            key = { key -> encodedKey(key) },
            freshness = ExpireAfter(5.minutes),
            storage = persistent(TestStringCodec()),
            memory = MemoryPolicy(maxEntries = 4),
        )

        assertIs<PersistentStorage<String>>(spec.storage)
        assertEquals("tenant-a", spec.keyEncoder.encode(sampleKey()).partition.value)
    }

    /** Verifies exact keys and typed family keys are declared independently. */
    @Test
    fun `fake domain declares family aware spec`() {
        val spec = familyCacheSpec<FakeDomainKey, String, FakeDomainValue>(
            namespace = "fake.values.family.v1",
            key = { key -> encodedKey(key) },
            familyOf = { key -> key.partition },
            family = { family ->
                EncodedCacheFamily(CachePartition(family), "all-values")
            },
            freshness = NeverExpire,
            storage = memoryOnly(),
            memory = MemoryPolicy(maxEntries = 8),
        )

        assertEquals("tenant-a", spec.familyOf(sampleKey()))
        assertEquals(
            "all-values",
            spec.familyEncoder.encode("tenant-a").value,
        )
    }

    /**
     * Encodes a sample domain key without requiring it to implement a cache interface.
     *
     * @param key Fake domain key containing partition and identity fields.
     * @return Stable encoded cache address.
     */
    private fun encodedKey(key: FakeDomainKey): EncodedCacheKey = EncodedCacheKey(
        partition = CachePartition(key.partition),
        value = key.id,
    )

    /**
     * Creates the deterministic key shared by sample assertions.
     *
     * @return Fake domain key with non-sensitive test values.
     */
    private fun sampleKey(): FakeDomainKey = FakeDomainKey(
        partition = "tenant-a",
        id = "item-1",
    )
}
