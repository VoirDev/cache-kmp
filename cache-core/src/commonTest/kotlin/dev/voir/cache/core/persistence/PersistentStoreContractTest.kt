package dev.voir.cache.core.persistence

import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.model.CacheMetadata
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Instant

/** Verifies construction invariants for the advanced encoded persistent-store contracts. */
@OptIn(ExperimentalCacheStoreApi::class)
class PersistentStoreContractTest {
    private val instant = Instant.parse("2026-08-11T10:00:00Z")

    /** Verifies persistent addresses reject blank normalized keys. */
    @Test
    fun `persistent address rejects blank key`() {
        assertFailsWith<IllegalArgumentException> {
            CacheAddress(
                namespace = CacheNamespace("contract.v1"),
                partition = CachePartition("test"),
                key = " ",
            )
        }
    }

    /** Verifies encoded records require a positive codec payload version. */
    @Test
    fun `persistent record rejects non-positive payload version`() {
        assertFailsWith<IllegalArgumentException> {
            PersistedCacheRecord(
                address = CacheAddress(
                    namespace = CacheNamespace("contract.v1"),
                    partition = CachePartition("test"),
                    key = "entry",
                ),
                payload = byteArrayOf(),
                payloadVersion = 0,
                metadata = CacheMetadata(instant, null),
                writtenAt = instant,
                lastAccessedAt = instant,
            )
        }
    }

    /** Verifies pruning bounds and results reject negative values. */
    @Test
    fun `persistent pruning rejects invalid bounds and counts`() {
        assertFailsWith<IllegalArgumentException> {
            PruneRequest(maxEntriesPerNamespace = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            PruneRequest(maxTotalEntries = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            PruneRequest(maxTotalPayloadBytes = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            PruneResult(rowsRemoved = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            PruneResult(rowsRemoved = 0, bytesRemoved = -1)
        }
    }
}
