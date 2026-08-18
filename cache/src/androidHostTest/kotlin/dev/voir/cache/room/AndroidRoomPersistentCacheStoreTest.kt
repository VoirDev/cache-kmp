package dev.voir.cache.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.model.CacheMetadata
import dev.voir.cache.core.persistence.CacheAddress
import dev.voir.cache.core.persistence.PersistedCacheRecord
import dev.voir.cache.core.persistence.PruneRequest
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/** Verifies Android database creation and reopen behavior with an application context. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidRoomPersistentCacheStoreTest {
    /** Verifies an Android store survives close and reopen in the application database directory. */
    @Test
    fun `database reopens and clears on android host`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "cache-kmp-${System.nanoTime()}.db"
        val record = PersistedCacheRecord(
            address = CacheAddress(
                namespace = CacheNamespace("android-smoke.v1"),
                partition = CachePartition("host"),
                key = "entry",
            ),
            payload = byteArrayOf(2, 4, 6, 8),
            payloadVersion = 3,
            metadata = CacheMetadata(
                validatedAt = Instant.parse("2026-08-11T10:00:00.123456789Z"),
                expiresAt = Instant.parse("2026-08-12T10:00:00.987654321Z"),
            ),
            writtenAt = Instant.parse("2026-08-11T10:00:01.111111111Z"),
            lastAccessedAt = Instant.parse("2026-08-11T10:00:02.222222222Z"),
        )

        try {
            val firstStore = createAndroidRoomPersistentCacheStore(
                context = context,
                databaseName = databaseName,
                configuration = RoomCacheStoreConfiguration(),
                driver = AndroidSQLiteDriver(),
            )
            firstStore.write(record)
            firstStore.close()

            val reopenedStore = createAndroidRoomPersistentCacheStore(
                context = context,
                databaseName = databaseName,
                configuration = RoomCacheStoreConfiguration(),
                driver = AndroidSQLiteDriver(),
            )
            val restored = assertNotNull(reopenedStore.read(record.address))
            assertEquals(record.address, restored.address)
            assertContentEquals(record.payload, restored.payload)
            assertEquals(record.payloadVersion, restored.payloadVersion)
            assertEquals(record.metadata, restored.metadata)
            assertEquals(record.writtenAt, restored.writtenAt)
            assertEquals(record.lastAccessedAt, restored.lastAccessedAt)

            val older = PersistedCacheRecord(
                address = record.address.copy(key = "older"),
                payload = byteArrayOf(9, 9),
                payloadVersion = record.payloadVersion,
                metadata = record.metadata,
                writtenAt = Instant.parse("2026-08-11T09:00:01Z"),
                lastAccessedAt = Instant.parse("2026-08-11T09:00:02Z"),
            )
            reopenedStore.write(older)
            assertNotNull(reopenedStore.read(older.address))
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
            context.deleteDatabase(databaseName)
        }
    }
}
