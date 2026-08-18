@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.voir.cache.file

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
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID
import platform.Foundation.NSTemporaryDirectory
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.fsync
import platform.posix.open
import platform.posix.read
import platform.posix.stat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Instant

class IosFileCacheStoreTest {
    @Test
    fun streams_reopens_and_recovers_in_an_explicit_cache_directory() = runTest {
        val root = testRoot()
        var store: FileCacheStore? = null
        try {
            store = createStore(root)
            val committed = assertIs<FileStoreCommitted>(store.write(byteArrayOf(1, 2, 3)))
            val lease = assertNotNull(store.acquire(committed.record))
            assertContentEquals(byteArrayOf(1, 2, 3), readBytes(lease.absolutePath))
            lease.close()
            store.close()

            store = createStore(root)
            assertEquals(committed.record.generation, store.read(ADDRESS)?.generation)
            store.close()

            val orphan = "$root/data/${"a".repeat(32)}.entry"
            val staging = "$root/data/${"b".repeat(32)}.part"
            writeBytes(orphan, byteArrayOf(4))
            writeBytes(staging, byteArrayOf(5))
            store = createStore(root)
            assertFalse(NSFileManager.defaultManager.fileExistsAtPath(orphan))
            assertFalse(NSFileManager.defaultManager.fileExistsAtPath(staging))
            assertNotNull(store.read(ADDRESS))
        } finally {
            closeQuietly(store)
            NSFileManager.defaultManager.removeItemAtPath(root, error = null)
        }
    }

    @Test
    fun root_lock_outlives_store_close_until_the_last_lease_is_released() = runTest {
        val root = testRoot()
        var store: FileCacheStore? = null
        var reopened: FileCacheStore? = null
        try {
            store = createStore(root)
            val committed = assertIs<FileStoreCommitted>(store.write(byteArrayOf(1)))
            val lease = assertNotNull(store.acquire(committed.record))
            store.close()

            assertFailsWith<IllegalStateException> { createStore(root) }
            lease.close()

            reopened = createStore(root)
            assertNotNull(reopened.read(ADDRESS))
        } finally {
            closeQuietly(store)
            closeQuietly(reopened)
            NSFileManager.defaultManager.removeItemAtPath(root, error = null)
        }
    }
}

private suspend fun createStore(root: String): FileCacheStore = createIosFileCacheStore(
    rootDirectory = root,
    limits = FileStoreLimits(10, 1_000),
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

private fun readBytes(path: String): ByteArray {
    val size = memScoped {
        val status = alloc<stat>()
        check(platform.posix.lstat(path, status.ptr) == 0)
        status.st_size.toInt()
    }
    val descriptor = open(path, O_RDONLY)
    check(descriptor >= 0)
    return try {
        ByteArray(size).also { bytes ->
            var offset = 0
            bytes.usePinned { pinned ->
                while (offset < bytes.size) {
                    val count = read(
                        descriptor,
                        pinned.addressOf(offset),
                        (bytes.size - offset).convert(),
                    )
                    check(count > 0)
                    offset += count.toInt()
                }
            }
        }
    } finally {
        close(descriptor)
    }
}

private fun writeBytes(path: String, bytes: ByteArray) {
    val descriptor = open(path, O_CREAT or O_TRUNC or O_WRONLY, 384)
    check(descriptor >= 0)
    try {
        var offset = 0
        bytes.usePinned { pinned ->
            while (offset < bytes.size) {
                val count = platform.posix.write(
                    descriptor,
                    pinned.addressOf(offset),
                    (bytes.size - offset).convert(),
                )
                check(count > 0)
                offset += count.toInt()
            }
        }
        check(fsync(descriptor) == 0)
    } finally {
        close(descriptor)
    }
}

private suspend fun closeQuietly(store: FileCacheStore?) {
    if (store == null) return
    try {
        store.close()
    } catch (_: Throwable) {
        // Test cleanup preserves the assertion failure.
    }
}

private fun testRoot(): String =
    "${NSTemporaryDirectory()}cache-kmp-file-${NSUUID().UUIDString}"

private val ADDRESS: FileStoreAddress = FileStoreAddress(
    namespace = CacheNamespace("files"),
    partition = CachePartition("account"),
    key = "avatar",
)
private val POLICY: FileCachePolicy = FileCachePolicy(100, 10, 1_000)
private val TEST_INSTANT: Instant = Instant.fromEpochSeconds(1_000)
