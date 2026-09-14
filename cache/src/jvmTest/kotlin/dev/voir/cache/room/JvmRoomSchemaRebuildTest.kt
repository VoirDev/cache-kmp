package dev.voir.cache.room

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.model.CacheMetadata
import dev.voir.cache.core.persistence.CacheAddress
import dev.voir.cache.core.persistence.PersistedCacheRecord
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

/**
 * Verifies that a cache database from an incompatible library version is rebuilt.
 *
 * Cached values are disposable, so a schema this library cannot read must become an empty cache
 * rather than a failure that prevents the application from creating a manager at all. This covers
 * both directions: a database written by an older release and one written by a newer release the
 * user has since rolled back from.
 */
class JvmRoomSchemaRebuildTest {
    @Test
    fun `a database from another schema version is rebuilt instead of failing`() = runTest {
        val root = createTempDirectory("cache-kmp-rebuild-")
        val databasePath = root.resolve("cache.db")
        try {
            BundledSQLiteDriver().open(databasePath.toString()).use { connection ->
                connection.execSQL("PRAGMA user_version = 99")
                connection.execSQL(
                    "CREATE TABLE cache_entry (namespace TEXT NOT NULL PRIMARY KEY, legacy TEXT)"
                )
                connection.execSQL("INSERT INTO cache_entry VALUES ('old', 'row')")
            }

            val store = createJvmRoomPersistentCacheStore(databasePath)
            try {
                val record = PersistedCacheRecord(
                    address = CacheAddress(
                        namespace = CacheNamespace("rebuild.v1"),
                        partition = CachePartition("one"),
                        key = "entry",
                    ),
                    payload = byteArrayOf(7),
                    payloadVersion = 1,
                    metadata = CacheMetadata(
                        validatedAt = Instant.parse("2026-08-11T10:00:00Z"),
                        expiresAt = null,
                    ),
                    writtenAt = Instant.parse("2026-08-11T10:00:00Z"),
                    lastAccessedAt = Instant.parse("2026-08-11T10:00:00Z"),
                )
                store.write(record)

                assertContentEquals(
                    byteArrayOf(7),
                    assertNotNull(store.read(record.address)).payload,
                )
            } finally {
                store.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
