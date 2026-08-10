package dev.voir.cache.room

import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.model.CacheMetadata
import dev.voir.cache.core.persistence.CacheAddress
import dev.voir.cache.core.persistence.ExperimentalCacheStoreApi
import dev.voir.cache.core.persistence.PersistedCacheRecord
import dev.voir.cache.core.persistence.PruneRequest
import kotlinx.coroutines.test.runTest
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/** Verifies database creation, reopen, read, and clear behavior on the iOS simulator. */
@OptIn(ExperimentalCacheStoreApi::class, ExperimentalForeignApi::class)
class IosRoomPersistentCacheStoreTest {
    /** Verifies an iOS simulator store survives close and reopen at an explicit path. */
    @Test
    fun `database reopens and clears on ios simulator`() = runTest {
        val databasePath = "${NSTemporaryDirectory()}cache-kmp-${NSUUID().UUIDString}.db"
        val record = PersistedCacheRecord(
            address = CacheAddress(
                namespace = CacheNamespace("ios-smoke.v1"),
                partition = CachePartition("simulator"),
                key = "entry",
            ),
            payload = byteArrayOf(1, 3, 5, 7),
            payloadVersion = 2,
            metadata = CacheMetadata(
                validatedAt = Instant.parse("2026-08-11T10:00:00.123456789Z"),
                expiresAt = Instant.parse("2026-08-12T10:00:00.987654321Z"),
                invalidatedAt = null,
            ),
            writtenAt = Instant.parse("2026-08-11T10:00:01.111111111Z"),
            lastAccessedAt = Instant.parse("2026-08-11T10:00:02.222222222Z"),
        )

        try {
            val firstStore = createIosRoomPersistentCacheStore(databasePath)
            firstStore.write(record)
            firstStore.close()

            val reopenedStore = createIosRoomPersistentCacheStore(databasePath)
            val restored = assertNotNull(reopenedStore.read(record.address))
            assertEquals(record.address, restored.address)
            assertContentEquals(record.payload, restored.payload)
            assertEquals(record.payloadVersion, restored.payloadVersion)
            assertEquals(record.metadata, restored.metadata)
            assertEquals(record.writtenAt, restored.writtenAt)
            assertEquals(record.lastAccessedAt, restored.lastAccessedAt)

            val older = record.copy(
                address = record.address.copy(key = "older"),
                payload = byteArrayOf(8, 8),
                writtenAt = Instant.parse("2026-08-11T09:00:01Z"),
                lastAccessedAt = Instant.parse("2026-08-11T09:00:02Z"),
            )
            reopenedStore.write(older)
            assertEquals(2, reopenedStore.statistics().totalRows)
            val prune = reopenedStore.prune(PruneRequest(maxTotalEntries = 1))
            assertEquals(1, prune.rowsRemoved)
            assertEquals(2, prune.bytesRemoved)
            assertNull(reopenedStore.read(older.address))
            assertNotNull(reopenedStore.read(record.address))

            reopenedStore.clearAll()
            assertNull(reopenedStore.read(record.address))
            reopenedStore.close()
            reopenedStore.close()
        } finally {
            removeDatabaseFiles(databasePath)
        }
    }

    /**
     * Removes the SQLite database and optional journal sidecars created at [databasePath].
     *
     * @param databasePath Absolute temporary database path.
     */
    private fun removeDatabaseFiles(databasePath: String) {
        listOf(databasePath, "$databasePath-wal", "$databasePath-shm").forEach { path ->
            if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
                NSFileManager.defaultManager.removeItemAtPath(path, error = null)
            }
        }
    }
}
