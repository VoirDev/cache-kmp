package dev.voir.cache.room

import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheFamily
import dev.voir.cache.core.model.CacheMetadata
import dev.voir.cache.core.persistence.CacheAddress
import dev.voir.cache.core.persistence.ExperimentalCacheStoreApi
import dev.voir.cache.core.persistence.PersistedCacheRecord
import dev.voir.cache.core.persistence.PersistentCacheStore
import dev.voir.cache.core.persistence.PruneRequest
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/** Verifies Room persistence, isolation, maintenance, and lifecycle behavior on the JVM. */
@OptIn(ExperimentalCacheStoreApi::class)
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
                payloadVersion = 2,
                validatedAt = Instant.parse("2026-08-11T10:00:00.123456789Z"),
                expiresAt = Instant.parse("2026-08-11T11:00:00.987654321Z"),
                invalidatedAt = Instant.parse("2026-08-11T10:30:00.000000007Z"),
                writtenAt = Instant.parse("2026-08-11T10:00:01.111111111Z"),
                lastAccessedAt = Instant.parse("2026-08-11T10:05:01.222222222Z"),
            )
            val catalog = record(
                namespace = "catalog.v3",
                partition = "production",
                key = "all",
                payload = byteArrayOf(9, 8),
                payloadVersion = 7,
                validatedAt = Instant.parse("2026-08-10T09:00:00.333333333Z"),
                expiresAt = null,
                invalidatedAt = null,
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

    /** Verifies invalidation and every clear scope affect only their intended rows. */
    @Test
    fun `invalidate and clear operations preserve unrelated rows`() = runTest {
        withJvmStore { _, store ->
            val namespaceA = CacheNamespace("alpha.v1")
            val namespaceB = CacheNamespace("beta.v1")
            val partitionOne = CachePartition("one")
            val partitionTwo = CachePartition("two")
            val alphaOne = record(namespaceA.value, partitionOne.value, "entry", byteArrayOf(1))
            val alphaTwo = record(namespaceA.value, partitionTwo.value, "entry", byteArrayOf(2))
            val betaOne = record(namespaceB.value, partitionOne.value, "entry", byteArrayOf(3))
            val betaTwo = record(namespaceB.value, partitionTwo.value, "entry", byteArrayOf(4))
            listOf(alphaOne, alphaTwo, betaOne, betaTwo).forEach { record -> store.write(record) }

            val invalidatedAt = Instant.parse("2026-08-11T12:00:00.000000009Z")
            store.invalidate(betaOne.address, invalidatedAt)
            assertEquals(invalidatedAt, assertNotNull(store.read(betaOne.address)).metadata.invalidatedAt)
            assertNull(assertNotNull(store.read(betaTwo.address)).metadata.invalidatedAt)

            store.clearNamespace(namespaceA)
            assertNull(store.read(alphaOne.address))
            assertNull(store.read(alphaTwo.address))
            assertNotNull(store.read(betaOne.address))
            assertNotNull(store.read(betaTwo.address))

            store.clearPartition(partitionOne)
            assertNull(store.read(betaOne.address))
            assertNotNull(store.read(betaTwo.address))

            store.clearAll()
            assertNull(store.read(betaTwo.address))
        }
    }

    /** Verifies family metadata and exact family mutations survive a database restart. */
    @Test
    fun `family invalidate and remove are durable across reopen`() = runTest {
        withJvmStore { databasePath, firstStore ->
            val selectedOne = record("family.v1", "tenant", "one", byteArrayOf(1), family = "a")
            val selectedTwo = record("family.v1", "tenant", "two", byteArrayOf(2), family = "a")
            val otherFamily = record("family.v1", "tenant", "three", byteArrayOf(3), family = "b")
            val otherNamespace = record("other.v1", "tenant", "four", byteArrayOf(4), family = "a")
            listOf(selectedOne, selectedTwo, otherFamily, otherNamespace).forEach { record ->
                firstStore.write(record)
            }
            val family = EncodedCacheFamily(CachePartition("tenant"), "a")
            val invalidatedAt = Instant.parse("2026-08-11T12:00:00.000000005Z")

            assertEquals(
                2,
                firstStore.invalidateFamily(CacheNamespace("family.v1"), family, invalidatedAt),
            )
            firstStore.close()

            val reopened = createJvmRoomPersistentCacheStore(databasePath)
            try {
                assertEquals(
                    invalidatedAt,
                    assertNotNull(reopened.read(selectedOne.address)).metadata.invalidatedAt,
                )
                assertEquals(
                    invalidatedAt,
                    assertNotNull(reopened.read(selectedTwo.address)).metadata.invalidatedAt,
                )
                assertNull(assertNotNull(reopened.read(otherFamily.address)).metadata.invalidatedAt)
                assertNull(assertNotNull(reopened.read(otherNamespace.address)).metadata.invalidatedAt)
                assertEquals(2, reopened.removeFamily(CacheNamespace("family.v1"), family))
                assertNull(reopened.read(selectedOne.address))
                assertNull(reopened.read(selectedTwo.address))
                assertNotNull(reopened.read(otherFamily.address))
                assertNotNull(reopened.read(otherNamespace.address))
            } finally {
                reopened.close()
            }
        }
    }

    /** Verifies pruning removes expired and least-recent rows per namespace with exact counts. */
    @Test
    fun `pruning applies expiration and per namespace limits`() = runTest {
        withJvmStore { _, store ->
            val expired = record(
                namespace = "alpha.v1",
                partition = "p1",
                key = "expired",
                payload = byteArrayOf(1),
                expiresAt = Instant.parse("2026-08-11T09:59:59.999999999Z"),
                lastAccessedAt = Instant.parse("2026-08-11T09:00:00Z"),
            )
            val leastRecent = record(
                namespace = "alpha.v1",
                partition = "p2",
                key = "least-recent",
                payload = byteArrayOf(2, 2, 2),
                expiresAt = null,
                lastAccessedAt = Instant.parse("2026-08-11T10:00:00Z"),
            )
            val mostRecent = record(
                namespace = "alpha.v1",
                partition = "p3",
                key = "most-recent",
                payload = byteArrayOf(3, 3),
                expiresAt = null,
                lastAccessedAt = Instant.parse("2026-08-11T11:00:00Z"),
            )
            val otherNamespace = record(
                namespace = "beta.v1",
                partition = "p1",
                key = "independent",
                payload = byteArrayOf(4, 4, 4, 4),
                expiresAt = null,
                lastAccessedAt = Instant.parse("2026-08-11T08:00:00Z"),
            )
            listOf(expired, leastRecent, mostRecent, otherNamespace).forEach { record ->
                store.write(record)
            }

            val result = store.prune(
                PruneRequest(
                    expiredBefore = Instant.parse("2026-08-11T10:00:00Z"),
                    maxEntriesPerNamespace = 1,
                )
            )

            assertEquals(2, result.rowsRemoved)
            assertEquals(4, result.bytesRemoved)
            assertNull(store.read(expired.address))
            assertNull(store.read(leastRecent.address))
            assertNotNull(store.read(mostRecent.address))
            assertNotNull(store.read(otherNamespace.address))
        }
    }

    /** Verifies whole-store row and payload limits share deterministic retention ordering. */
    @Test
    fun `pruning applies total row and payload byte limits`() = runTest {
        withJvmStore { _, store ->
            val newest = record(
                namespace = "alpha.v1",
                partition = "p1",
                key = "newest",
                payload = byteArrayOf(1, 1, 1),
                expiresAt = null,
                writtenAt = Instant.fromEpochSeconds(30),
                lastAccessedAt = Instant.fromEpochSeconds(30),
            )
            val tiedAlpha = record(
                namespace = "alpha.v1",
                partition = "p2",
                key = "tied",
                payload = byteArrayOf(2, 2, 2, 2),
                expiresAt = null,
                writtenAt = Instant.fromEpochSeconds(20),
                lastAccessedAt = Instant.fromEpochSeconds(20),
            )
            val tiedBeta = record(
                namespace = "beta.v1",
                partition = "p1",
                key = "tied",
                payload = byteArrayOf(3, 3),
                expiresAt = null,
                writtenAt = Instant.fromEpochSeconds(20),
                lastAccessedAt = Instant.fromEpochSeconds(20),
            )
            listOf(tiedBeta, tiedAlpha, newest).forEach { record -> store.write(record) }

            val result = store.prune(
                PruneRequest(
                    maxTotalEntries = 2,
                    maxTotalPayloadBytes = 5,
                ),
            )

            assertEquals(2, result.rowsRemoved)
            assertEquals(6, result.bytesRemoved)
            assertNotNull(store.read(newest.address))
            assertNull(store.read(tiedAlpha.address))
            assertNull(store.read(tiedBeta.address))
        }
    }

    /** Verifies SQLite reports exact total and namespace occupancy. */
    @Test
    fun `statistics report exact row and payload occupancy`() = runTest {
        withJvmStore { _, store ->
            val alphaOne = record("alpha.v1", "p1", "one", byteArrayOf(1, 2, 3))
            val alphaTwo = record("alpha.v1", "p2", "two", byteArrayOf(4, 5))
            val beta = record("beta.v1", "p1", "one", byteArrayOf(6))
            listOf(alphaOne, alphaTwo, beta).forEach { record -> store.write(record) }

            val statistics = store.statistics()

            assertEquals(3, statistics.totalRows)
            assertEquals(6, statistics.totalPayloadBytes)
            assertEquals(2, statistics.namespaces.getValue(CacheNamespace("alpha.v1")).rows)
            assertEquals(5, statistics.namespaces.getValue(CacheNamespace("alpha.v1")).payloadBytes)
            assertEquals(1, statistics.namespaces.getValue(CacheNamespace("beta.v1")).rows)
            assertEquals(true, statistics.isExact)

            store.write(alphaOne.copy(payload = byteArrayOf(9)))
            store.remove(beta.address)
            val updated = store.statistics()
            assertEquals(2, updated.totalRows)
            assertEquals(3, updated.totalPayloadBytes)
            assertEquals(3, updated.namespaces.getValue(CacheNamespace("alpha.v1")).payloadBytes)
            assertNull(updated.namespaces[CacheNamespace("beta.v1")])
        }
    }

    /** Verifies close is idempotent and operations fail predictably after termination. */
    @Test
    fun `close is idempotent and rejects later operations`() = runTest {
        withJvmStore { _, store ->
            store.close()
            store.close()

            val failure = assertFailsWith<IllegalStateException> {
                store.read(CacheAddress(CacheNamespace("closed.v1"), CachePartition("test"), "key"))
            }
            assertEquals("Persistent cache store is closed.", failure.message)
        }
    }

    /**
     * Creates a temporary explicit database and reliably closes and removes it after [testBody].
     *
     * @param testBody Test operation receiving the database path and open store.
     */
    private suspend fun withJvmStore(
        testBody: suspend (databasePath: java.nio.file.Path, store: PersistentCacheStore) -> Unit,
    ) {
        val directory = Files.createTempDirectory("cache-kmp-room-test")
        val databasePath = directory.resolve("cache.db")
        val store = createJvmRoomPersistentCacheStore(databasePath)
        try {
            testBody(databasePath, store)
        } finally {
            store.close()
            directory.toFile().deleteRecursively()
        }
    }

    /**
     * Creates a persistent record fixture with independently configurable storage fields.
     *
     * @param namespace Resource namespace.
     * @param partition Isolation partition.
     * @param key Encoded cache key.
     * @param payload Opaque stored bytes.
     * @param payloadVersion Codec format version.
     * @param validatedAt Validation instant.
     * @param expiresAt Optional expiration instant.
     * @param invalidatedAt Optional invalidation instant.
     * @param writtenAt Persistent write instant.
     * @param lastAccessedAt Approximate retention-access instant.
     * @return Complete persistent record fixture.
     */
    private fun record(
        namespace: String,
        partition: String,
        key: String,
        payload: ByteArray,
        family: String? = null,
        payloadVersion: Int = 1,
        validatedAt: Instant = Instant.parse("2026-08-11T08:00:00Z"),
        expiresAt: Instant? = Instant.parse("2026-08-12T08:00:00Z"),
        invalidatedAt: Instant? = null,
        writtenAt: Instant = Instant.parse("2026-08-11T08:00:01Z"),
        lastAccessedAt: Instant = Instant.parse("2026-08-11T08:00:02Z"),
    ): PersistedCacheRecord = PersistedCacheRecord(
        address = CacheAddress(
            CacheNamespace(namespace),
            CachePartition(partition),
            key,
            family,
        ),
        payload = payload,
        payloadVersion = payloadVersion,
        metadata = CacheMetadata(validatedAt, expiresAt, invalidatedAt),
        writtenAt = writtenAt,
        lastAccessedAt = lastAccessedAt,
    )

    /**
     * Compares two records including the structural contents of their byte arrays.
     *
     * @param expected Record expected from the database.
     * @param actual Record returned by the database.
     */
    private fun assertRecordEquals(expected: PersistedCacheRecord, actual: PersistedCacheRecord) {
        assertEquals(expected.address, actual.address)
        assertContentEquals(expected.payload, actual.payload)
        assertEquals(expected.payloadVersion, actual.payloadVersion)
        assertEquals(expected.metadata, actual.metadata)
        assertEquals(expected.writtenAt, actual.writtenAt)
        assertEquals(expected.lastAccessedAt, actual.lastAccessedAt)
    }
}
