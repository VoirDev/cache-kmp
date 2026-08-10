package dev.voir.cache.core.memory

import dev.voir.cache.core.fixture.FakeCacheClock
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.policy.MemoryPolicy
import dev.voir.cache.core.policy.NeverExpire
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

/** Verifies namespace, partition, and global clearing across typed resource stores. */
class InMemoryCacheRegistryTest {
    private val firstNamespace = CacheNamespace("first.values.v1")
    private val secondNamespace = CacheNamespace("second.values.v1")
    private val firstPartition = CachePartition("tenant-a")
    private val secondPartition = CachePartition("tenant-b")

    /** Verifies a namespace clear cannot affect another registered resource family. */
    @Test
    fun `namespace clear is isolated`() = runTest {
        val registry = InMemoryCacheRegistry()
        val firstStore = newStore<String>(firstNamespace)
        val secondStore = newStore<Int>(secondNamespace)
        registry.register(firstStore)
        registry.register(secondStore)
        firstStore.put(key(firstPartition, "item"), "first")
        secondStore.put(key(firstPartition, "item"), 2)

        registry.clear(firstNamespace)

        assertNull(firstStore.current(key(firstPartition, "item")).value)
        assertEquals(2, secondStore.current(key(firstPartition, "item")).value)
    }

    /** Verifies partition clearing crosses namespaces without crossing partition boundaries. */
    @Test
    fun `partition clear is scoped across namespaces`() = runTest {
        val registry = InMemoryCacheRegistry()
        val firstStore = newStore<String>(firstNamespace)
        val secondStore = newStore<Int>(secondNamespace)
        registry.register(firstStore)
        registry.register(secondStore)
        firstStore.put(key(firstPartition, "first"), "remove")
        firstStore.put(key(secondPartition, "first"), "keep")
        secondStore.put(key(firstPartition, "second"), 1)
        secondStore.put(key(secondPartition, "second"), 2)

        registry.clear(firstPartition)

        assertNull(firstStore.current(key(firstPartition, "first")).value)
        assertEquals("keep", firstStore.current(key(secondPartition, "first")).value)
        assertNull(secondStore.current(key(firstPartition, "second")).value)
        assertEquals(2, secondStore.current(key(secondPartition, "second")).value)
    }

    /** Verifies global clear removes all entries from every registered namespace. */
    @Test
    fun `global clear removes all entries`() = runTest {
        val registry = InMemoryCacheRegistry()
        val firstStore = newStore<String>(firstNamespace)
        val secondStore = newStore<Int>(secondNamespace)
        registry.register(firstStore)
        registry.register(secondStore)
        firstStore.put(key(firstPartition, "first"), "first")
        secondStore.put(key(secondPartition, "second"), 2)

        registry.clearAll()

        assertEquals(0, firstStore.entryCount())
        assertEquals(0, secondStore.entryCount())
    }

    /** Verifies duplicate namespace registration fails before stores can compete. */
    @Test
    fun `duplicate namespace registration is rejected`() = runTest {
        val registry = InMemoryCacheRegistry()
        registry.register(newStore<String>(firstNamespace))

        assertFailsWith<IllegalArgumentException> {
            registry.register(newStore<Int>(firstNamespace))
        }
    }

    /**
     * Creates a typed resource store for [namespace].
     *
     * @param V Non-null value type owned by the store.
     * @param namespace Stable resource namespace under test.
     * @return New isolated resource store.
     */
    private fun <V : Any> newStore(
        namespace: CacheNamespace,
    ): InMemoryResourceStore<V, String> = InMemoryResourceStore(
        namespace = namespace,
        freshnessPolicy = NeverExpire,
        memoryPolicy = MemoryPolicy(maxEntries = 8),
        clock = FakeCacheClock(Instant.parse("2026-08-10T10:00:00Z")),
    )

    /**
     * Creates an encoded key in [partition].
     *
     * @param partition Isolation boundary owning the key.
     * @param value Normalized identity inside [partition].
     * @return Complete encoded test key.
     */
    private fun key(partition: CachePartition, value: String): EncodedCacheKey =
        EncodedCacheKey(partition, value)
}
