package dev.voir.cache.room

import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.model.CacheMetadata
import dev.voir.cache.core.persistence.CacheAddress
import dev.voir.cache.core.persistence.PersistedCacheRecord
import dev.voir.cache.core.persistence.PersistentCacheStore
import dev.voir.cache.core.persistence.PruneRequest
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/** Verifies Room persistence, isolation, maintenance, and lifecycle behavior on the JVM. */
class JvmRoomPersistentCacheStoreTest {
    /** Verifies unrelated namespaces and exact metadata survive closing and reopening Room. */
    @Test
    fun `records and metadata survive database reopen`() = runTest {
        withJvmStore { databasePath, firstStore ->
            val profile = record(
                namespace = "profile.v1",
                partition = "user-a",
                key = "current",
                payload = byteArrayOf(1, 2, 3),
                validatedAt = Instant.parse("2026-08-11T10:00:00.123456789Z"),
                expiresAt = Instant.parse("2026-08-11T11:00:00.987654321Z"),
                writtenAt = Instant.parse("2026-08-11T10:00:01.111111111Z"),
                lastAccessedAt = Instant.parse("2026-08-11T10:05:01.222222222Z"),
            )
            val catalog = record(
                namespace = "catalog.v3",
                partition = "production",
                key = "all",
                payload = byteArrayOf(9, 8),
                validatedAt = Instant.parse("2026-08-10T09:00:00.333333333Z"),
                expiresAt = null,
                writtenAt = Instant.parse("2026-08-10T09:00:01.444444444Z"),
                lastAccessedAt = Instant.parse("2026-08-10T09:01:01.555555555Z"),
            )

            firstStore.write(profile)
            firstStore.write(catalog)
            firstStore.close()

            val reopenedStore = createJvmRoomPersistentCacheStore(databasePath)
            try {
                assertRecordEquals(profile, assertNotNull(reopenedStore.read(profile.address)))
                assertRecordEquals(catalog, assertNotNull(reopenedStore.read(catalog.address)))
            } finally {
                reopenedStore.close()
            }
        }
    }

    /** Verifies exact removal and whole-store clearing affect only their intended rows. */
    @Test
    fun `removal and clearing affect only their intended rows`() = runTest {
        withJvmStore { _, store ->
            val kept = record(namespace = "alpha.v1", partition = "one", key = "kept")
            val removed = record(namespace = "alpha.v1", partition = "one", key = "removed")
            store.write(kept)
            store.write(removed)

            store.remove(removed.address)

            assertNotNull(store.read(kept.address))
            assertNull(store.read(removed.address))

            store.clearAll()

            assertNull(store.read(kept.address))
        }
    }

    /** Verifies a write at an existing address replaces its payload rather than duplicating it. */
    @Test
    fun `write replaces an existing address`() = runTest {
        withJvmStore { _, store ->
            val address = CacheAddress(
                namespace = CacheNamespace("alpha.v1"),
                partition = CachePartition("one"),
                key = "entry",
            )
            store.write(record(address = address, payload = byteArrayOf(1)))
            store.write(record(address = address, payload = byteArrayOf(2, 2)))

            assertContentEquals(byteArrayOf(2, 2), assertNotNull(store.read(address)).payload)
        }
    }

    /** Verifies pruning removes expired rows and reports their exact payload cost. */
    @Test
    fun `pruning removes expired rows`() = runTest {
        withJvmStore { _, store ->
            val validatedAt = Instant.parse("2026-08-11T10:00:00Z")
            val expired = record(
                namespace = "alpha.v1",
                partition = "one",
                key = "expired",
                payload = byteArrayOf(1, 2, 3, 4),
                validatedAt = validatedAt,
                expiresAt = validatedAt,
            )
            val live = record(
                namespace = "alpha.v1",
                partition = "one",
                key = "live",
                validatedAt = validatedAt,
                expiresAt = null,
            )
            store.write(expired)
            store.write(live)

            val result = store.prune(PruneRequest(expiredBefore = validatedAt.plusSeconds(1)))

            assertEquals(1, result.rowsRemoved)
            assertEquals(4, result.bytesRemoved)
            assertNull(store.read(expired.address))
            assertNotNull(store.read(live.address))
        }
    }

    /** Verifies whole-store row and byte bounds retain the most recently used rows. */
    @Test
    fun `pruning enforces whole-store bounds`() = runTest {
        withJvmStore { _, store ->
            val base = Instant.parse("2026-08-11T10:00:00Z")
            repeat(5) { index ->
                store.write(
                    record(
                        namespace = "alpha.v1",
                        partition = "one",
                        key = "entry-$index",
                        payload = ByteArray(10),
                        validatedAt = base,
                        expiresAt = null,
                        writtenAt = base.plusSeconds(index.toLong()),
                        lastAccessedAt = base.plusSeconds(index.toLong()),
                    )
                )
            }

            val byCount = store.prune(PruneRequest(maxTotalEntries = 3))

            assertEquals(2, byCount.rowsRemoved)
            assertNull(store.read(address("alpha.v1", "one", "entry-0")))
            assertNotNull(store.read(address("alpha.v1", "one", "entry-4")))

            val byBytes = store.prune(PruneRequest(maxTotalPayloadBytes = 10))

            assertEquals(2, byBytes.rowsRemoved)
            assertNotNull(store.read(address("alpha.v1", "one", "entry-4")))
        }
    }

    /** Verifies close is idempotent and every later operation is rejected. */
    @Test
    fun `closed store rejects later operations`() = runTest {
        withJvmStore { _, store ->
            store.close()
            store.close()

            assertFailsWith<IllegalStateException> { store.read(address("a.v1", "one", "entry")) }
            assertFailsWith<IllegalStateException> { store.clearAll() }
        }
    }

    /** Verifies a relative database path is rejected before Room is opened. */
    @Test
    fun `relative database path is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            createJvmRoomPersistentCacheStore(Path.of("relative/cache.db"))
        }
    }

    private suspend fun withJvmStore(
        test: suspend (Path, PersistentCacheStore) -> Unit,
    ) {
        val root = createTempDirectory("cache-kmp-room-")
        val databasePath = root.resolve("cache.db")
        val store = createJvmRoomPersistentCacheStore(databasePath)
        try {
            test(databasePath, store)
        } finally {
            try {
                store.close()
            } catch (_: Throwable) {
                // The test may have closed the store deliberately.
            }
            root.toFile().deleteRecursively()
            check(!Files.exists(root))
        }
    }

    private fun address(namespace: String, partition: String, key: String): CacheAddress =
        CacheAddress(
            namespace = CacheNamespace(namespace),
            partition = CachePartition(partition),
            key = key,
        )

    private fun record(
        namespace: String = "alpha.v1",
        partition: String = "one",
        key: String = "entry",
        address: CacheAddress = address(namespace, partition, key),
        payload: ByteArray = byteArrayOf(1),
        validatedAt: Instant = Instant.parse("2026-08-11T10:00:00Z"),
        expiresAt: Instant? = null,
        writtenAt: Instant = validatedAt,
        lastAccessedAt: Instant = validatedAt,
    ): PersistedCacheRecord = PersistedCacheRecord(
        address = address,
        payload = payload,
        payloadVersion = 1,
        metadata = CacheMetadata(validatedAt = validatedAt, expiresAt = expiresAt),
        writtenAt = writtenAt,
        lastAccessedAt = lastAccessedAt,
    )

    private fun assertRecordEquals(expected: PersistedCacheRecord, actual: PersistedCacheRecord) {
        assertEquals(expected.address, actual.address)
        assertContentEquals(expected.payload, actual.payload)
        assertEquals(expected.payloadVersion, actual.payloadVersion)
        assertEquals(expected.metadata, actual.metadata)
        assertEquals(expected.writtenAt, actual.writtenAt)
        assertEquals(expected.lastAccessedAt, actual.lastAccessedAt)
    }

    private fun Instant.plusSeconds(seconds: Long): Instant =
        this + kotlin.time.Duration.parse("${seconds}s")
}
