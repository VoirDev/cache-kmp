package dev.voir.cache.testing

import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheFamily
import dev.voir.cache.core.model.CacheMetadata
import dev.voir.cache.core.persistence.CacheAddress
import dev.voir.cache.core.persistence.ExperimentalCacheStoreApi
import dev.voir.cache.core.persistence.PersistedCacheRecord
import dev.voir.cache.core.persistence.PruneRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

/** Verifies the reusable in-memory persistent-store test implementation. */
@OptIn(ExperimentalCacheStoreApi::class)
class InMemoryPersistentCacheStoreTest {
    /** Verifies records round-trip and store counters remain observable. */
    @Test
    fun `records round trip with observable operation counts`() = runTest {
        val store = InMemoryPersistentCacheStore()
        val record = record(key = "one", accessedAt = 10)

        store.write(record)
        val restored = store.read(record.address)

        assertEquals(record.address, restored?.address)
        assertContentEquals(record.payload, restored?.payload)
        assertEquals(1, store.writeCount)
        assertEquals(1, store.readCount)
    }

    /** Verifies injectable failures do not mutate the store. */
    @Test
    fun `write failure is deterministic and leaves no record`() = runTest {
        val store = InMemoryPersistentCacheStore().apply { failWrites = true }
        val record = record(key = "one", accessedAt = 10)

        assertFailsWith<IllegalStateException> { store.write(record) }
        assertNull(store.peek(record.address))
        assertEquals(1, store.writeCount)
    }

    /** Verifies pruning uses expiration and last-access ordering. */
    @Test
    fun `prune removes expired and least recently accessed records`() = runTest {
        val store = InMemoryPersistentCacheStore(
            listOf(
                record(key = "expired", accessedAt = 1, expiresAt = 1),
                record(key = "old", accessedAt = 3),
                record(key = "new", accessedAt = 4),
            ),
        )

        val result = store.prune(
            PruneRequest(
                expiredBefore = Instant.fromEpochSeconds(2),
                maxEntriesPerNamespace = 1,
            ),
        )

        assertEquals(2, result.rowsRemoved)
        assertEquals(listOf("new"), store.records().map { it.address.key })
    }

    /** Verifies whole-store row and byte limits use deterministic most-recent-first retention. */
    @Test
    fun `prune applies total row and payload byte bounds`() = runTest {
        val store = InMemoryPersistentCacheStore(
            listOf(
                record(key = "new", accessedAt = 3),
                record(key = "middle", accessedAt = 2),
                record(key = "old", accessedAt = 1),
            ),
        )

        val result = store.prune(
            PruneRequest(
                maxTotalEntries = 2,
                maxTotalPayloadBytes = 4,
            ),
        )

        assertEquals(2, result.rowsRemoved)
        assertEquals(listOf("new"), store.records().map { it.address.key })
        assertEquals(("middle".length + "old".length).toLong(), result.bytesRemoved)
    }

    /** Verifies exact statistics follow writes, replacement, removal, and pruning. */
    @Test
    fun `statistics report exact namespace and total occupancy`() = runTest {
        val first = record(key = "one", accessedAt = 1)
        val second = record(key = "two", accessedAt = 2)
        val store = InMemoryPersistentCacheStore(listOf(first, second))

        val before = store.statistics()
        assertEquals(2, before.totalRows)
        assertEquals(6, before.totalPayloadBytes)
        assertEquals(2, before.namespaces.getValue(CacheNamespace("testing")).rows)

        store.remove(first.address)
        val after = store.statistics()
        assertEquals(1, after.totalRows)
        assertEquals(3, after.totalPayloadBytes)
        assertEquals(true, after.isExact)
    }

    /** Verifies family operations use exact metadata rather than key prefix matching. */
    @Test
    fun `family operations affect only exact namespace partition and family`() = runTest {
        val selected = record("one", 1, family = "account")
        val alsoSelected = record("two", 2, family = "account")
        val unrelated = record("account-prefix", 3, family = "other")
        val store = InMemoryPersistentCacheStore(listOf(selected, alsoSelected, unrelated))
        val family = EncodedCacheFamily(CachePartition("default"), "account")
        val invalidatedAt = Instant.fromEpochSeconds(10)

        assertEquals(2, store.invalidateFamily(CacheNamespace("testing"), family, invalidatedAt))
        assertEquals(invalidatedAt, store.peek(selected.address)?.metadata?.invalidatedAt)
        assertNull(store.peek(unrelated.address)?.metadata?.invalidatedAt)
        assertEquals(2, store.removeFamily(CacheNamespace("testing"), family))
        assertEquals(listOf("account-prefix"), store.records().map { it.address.key })
    }

    /**
     * Creates a compact persistent record for store tests.
     *
     * @param key Encoded key for the record.
     * @param accessedAt Epoch second used for write and access ordering.
     * @param expiresAt Optional expiration epoch second.
     * @return Fully populated encoded record.
     */
    private fun record(
        key: String,
        accessedAt: Long,
        expiresAt: Long? = null,
        family: String? = null,
    ): PersistedCacheRecord = PersistedCacheRecord(
        address = CacheAddress(
            namespace = CacheNamespace("testing"),
            partition = CachePartition("default"),
            key = key,
            family = family,
        ),
        payload = key.encodeToByteArray(),
        payloadVersion = 1,
        metadata = CacheMetadata(
            validatedAt = Instant.fromEpochSeconds(0),
            expiresAt = expiresAt?.let(Instant::fromEpochSeconds),
        ),
        writtenAt = Instant.fromEpochSeconds(accessedAt),
        lastAccessedAt = Instant.fromEpochSeconds(accessedAt),
    )
}
