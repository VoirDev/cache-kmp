package dev.voir.cache.file

import android.content.Context
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import dev.voir.cache.core.file.FileCachePolicy
import dev.voir.cache.core.file.FileContentMetadata
import dev.voir.cache.core.file.FileStoreLimits
import dev.voir.cache.core.file.store.FileCacheStore
import dev.voir.cache.core.file.store.FileStoreAddress
import dev.voir.cache.core.file.store.FileStoreCommitRequest
import dev.voir.cache.core.file.store.FileStoreCommitted
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.model.CacheMetadata
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidFileCacheStoreTest {
    @Test
    fun streams_reopens_and_recovers_in_the_application_cache_directory() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directoryName = "cache-kmp-file-${System.nanoTime()}"
        val root = File(context.cacheDir, directoryName)
        var store: FileCacheStore? = null
        try {
            store = createStore(context, directoryName)
            val committed = assertIs<FileStoreCommitted>(store.write(byteArrayOf(1, 2, 3)))
            assertNotNull(store.read(ADDRESS))
            val lease = assertNotNull(store.acquire(committed.record))
            assertTruePathInside(root, File(lease.absolutePath))
            assertContentEquals(byteArrayOf(1, 2, 3), File(lease.absolutePath).readBytes())
            lease.close()
            store.close()

            store = createStore(context, directoryName)
            assertEquals(committed.record.generation, store.read(ADDRESS)?.generation)
            store.close()

            val orphan = File(root, "data/${"a".repeat(32)}.entry")
            val staging = File(root, "data/${"b".repeat(32)}.part")
            orphan.writeBytes(byteArrayOf(4))
            staging.writeBytes(byteArrayOf(5))
            store = createStore(context, directoryName)
            assertFalse(orphan.exists())
            assertFalse(staging.exists())
            assertNotNull(store.read(ADDRESS))
        } finally {
            try {
                store?.close()
            } catch (_: Throwable) {
                // Preserve the test failure.
            }
            root.deleteRecursively()
        }
    }

    @Test
    fun root_lock_outlives_store_close_until_the_last_lease_is_released() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directoryName = "cache-kmp-file-${System.nanoTime()}"
        val root = File(context.cacheDir, directoryName)
        var store: FileCacheStore? = null
        var reopened: FileCacheStore? = null
        try {
            store = createStore(context, directoryName)
            val committed = assertIs<FileStoreCommitted>(store.write(byteArrayOf(1)))
            val lease = assertNotNull(store.acquire(committed.record))
            store.close()

            assertFailsWith<IllegalStateException> { createStore(context, directoryName) }
            lease.close()

            reopened = createStore(context, directoryName)
            assertNotNull(reopened.read(ADDRESS))
        } finally {
            try {
                store?.close()
                reopened?.close()
            } catch (_: Throwable) {
                // Preserve the test failure.
            }
            root.deleteRecursively()
        }
    }
}

private suspend fun createStore(context: Context, directoryName: String): FileCacheStore =
    createAndroidFileCacheStore(
        context = context,
        directoryName = directoryName,
        limits = FileStoreLimits(10, 1_000),
        configuration = FileCacheStoreConfiguration(),
        driver = AndroidSQLiteDriver(),
    )

private suspend fun FileCacheStore.write(bytes: ByteArray):
    dev.voir.cache.core.file.store.FileStoreCommitResult {
    val session = startWrite(ADDRESS, POLICY.maxEntryBytes)
    session.write(bytes)
    return commit(
        FileStoreCommitRequest(
            address = ADDRESS,
            staged = session.finish(),
            content = FileContentMetadata("application/octet-stream"),
            metadata = CacheMetadata(TEST_INSTANT, expiresAt = null),
            policy = POLICY,
            writtenAt = TEST_INSTANT,
        )
    )
}

private fun assertTruePathInside(root: File, content: File) {
    check(content.absolutePath.startsWith(root.absolutePath + File.separator))
}

private val ADDRESS: FileStoreAddress = FileStoreAddress(
    namespace = CacheNamespace("files"),
    partition = CachePartition("account"),
    key = "avatar",
)
private val POLICY: FileCachePolicy = FileCachePolicy(100, 10, 1_000)
private val TEST_INSTANT: Instant = Instant.fromEpochSeconds(1_000)
