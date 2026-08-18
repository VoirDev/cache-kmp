package dev.voir.cache.file

import dev.voir.cache.core.file.FileCachePolicy
import dev.voir.cache.core.file.FileContentMetadata
import dev.voir.cache.core.file.FileStoreLimits
import dev.voir.cache.core.file.store.FileCacheStore
import dev.voir.cache.core.file.store.FileEntryTooLargeException
import dev.voir.cache.core.file.store.FileStoreAddress
import dev.voir.cache.core.file.store.FileStoreCommitRequest
import dev.voir.cache.core.file.store.FileStoreCommitted
import dev.voir.cache.core.file.store.FileStorePruneRequest
import dev.voir.cache.core.file.store.FileStoreRejected
import dev.voir.cache.core.file.store.StoredFileRecord
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.model.CacheMetadata
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Verifies staging, atomic promotion, leases, eviction, and recovery for the JVM file store. */
class JvmFileCacheStoreTest {
    private val writtenAt = Instant.parse("2026-08-11T10:00:00Z")

    /** Verifies streamed content is promoted atomically and read back byte for byte. */
    @Test
    fun `committed content is readable and leasable`() = withStore { _, store ->
        val address = address("documents.v1", "one", "entry")
        val payload = byteArrayOf(1, 2, 3, 4, 5)

        val record = store.commit(address, payload, mediaType = "application/pdf")

        assertEquals(payload.size.toLong(), record.sizeBytes)
        assertEquals("application/pdf", record.content.mediaType)
        val lease = assertNotNull(store.acquire(record))
        try {
            assertContentEquals(payload, Files.readAllBytes(Path.of(lease.absolutePath)))
        } finally {
            lease.close()
            lease.close()
        }
    }

    /** Verifies a replacement generation supersedes the previous content for the same address. */
    @Test
    fun `replacement supersedes the previous generation`() = withStore { _, store ->
        val address = address("documents.v1", "one", "entry")
        val first = store.commit(address, byteArrayOf(1))

        val second = store.commit(address, byteArrayOf(2, 2))

        assertNull(store.acquire(first))
        val lease = assertNotNull(store.acquire(second))
        try {
            assertContentEquals(byteArrayOf(2, 2), Files.readAllBytes(Path.of(lease.absolutePath)))
        } finally {
            lease.close()
        }
    }

    /** Verifies the entry-byte bound is enforced on the streamed bytes themselves. */
    @Test
    fun `oversized streams are rejected while writing`() = withStore { _, store ->
        val session = store.startWrite(address("documents.v1", "one", "entry"), maxEntryBytes = 4)

        session.write(byteArrayOf(1, 2, 3), 0, 3)

        assertFailsWith<FileEntryTooLargeException> { session.write(byteArrayOf(4, 5), 0, 2) }
        session.abort()
    }

    /** Verifies a namespace entry bound evicts the least recently used entry on commit. */
    @Test
    fun `namespace entry bound evicts the least recently used entry`() = withStore { _, store ->
        val policy = FileCachePolicy(maxEntryBytes = 16, maxEntries = 2, maxBytes = 64)
        val first = store.commit(address("documents.v1", "one", "a"), byteArrayOf(1), policy)
        val second = store.commit(address("documents.v1", "one", "b"), byteArrayOf(2), policy)
        store.acquire(second)?.close()

        store.commit(address("documents.v1", "one", "c"), byteArrayOf(3), policy)

        assertNull(store.read(first.address))
        assertNotNull(store.read(second.address))
    }

    /** Verifies an active lease blocks eviction and the commit is rejected rather than corrupted. */
    @Test
    fun `capacity rejection preserves leased content`() = withStore { _, store ->
        val policy = FileCachePolicy(maxEntryBytes = 16, maxEntries = 1, maxBytes = 64)
        val leased = store.commit(address("documents.v1", "one", "a"), byteArrayOf(1), policy)
        val lease = assertNotNull(store.acquire(leased))

        try {
            val rejected = store.attemptCommit(
                address("documents.v1", "one", "b"),
                byteArrayOf(2),
                policy,
            )
            assertIs<FileStoreRejected>(rejected)
            assertNotNull(store.read(leased.address))
        } finally {
            lease.close()
        }
    }

    /** Verifies whole-store bounds evict across namespaces, not only within the committing one. */
    @Test
    fun `whole-store bounds evict across namespaces`() = runTest {
        val root = createTempDirectory("cache-kmp-file-total-")
        val store = createJvmFileCacheStore(
            root,
            FileStoreLimits(maxTotalEntries = 2, maxTotalBytes = 1_024),
        )
        try {
            val first = store.commit(address("alpha.v1", "one", "a"), byteArrayOf(1))
            val second = store.commit(address("beta.v1", "one", "b"), byteArrayOf(2))
            store.acquire(second)?.close()

            val third = store.commit(address("gamma.v1", "one", "c"), byteArrayOf(3))

            assertNull(store.read(first.address))
            assertNotNull(store.read(second.address))
            assertNotNull(store.read(third.address))
        } finally {
            store.close()
            root.toFile().deleteRecursively()
        }
    }

    /** Verifies the whole-store byte bound evicts until the commit fits. */
    @Test
    fun `whole-store byte bound evicts until the commit fits`() = runTest {
        val root = createTempDirectory("cache-kmp-file-bytes-")
        val store = createJvmFileCacheStore(
            root,
            FileStoreLimits(maxTotalEntries = 100, maxTotalBytes = 12),
        )
        val policy = FileCachePolicy(maxEntryBytes = 12, maxEntries = 100, maxBytes = 12)
        try {
            val evicted = (0 until 3).map { index ->
                store.commit(address("alpha.v1", "one", "entry-$index"), ByteArray(4), policy)
            }

            val large = store.commit(address("alpha.v1", "one", "large"), ByteArray(12), policy)

            assertEquals(listOf(true, true, true), evicted.map { store.read(it.address) == null })
            assertNotNull(store.read(large.address))
        } finally {
            store.close()
            root.toFile().deleteRecursively()
        }
    }

    /** Verifies removal and whole-store clearing delete both index rows and physical content. */
    @Test
    fun `removal and clearing delete content`() = withStore { root, store ->
        val removed = store.commit(address("documents.v1", "one", "a"), byteArrayOf(1))
        val cleared = store.commit(address("documents.v1", "one", "b"), byteArrayOf(2))

        store.remove(removed.address)
        assertNull(store.read(removed.address))

        store.clearAll()
        assertNull(store.read(cleared.address))
        assertEquals(emptyList(), contentFiles(root))
    }

    /** Verifies pruning removes expired content and leaves live content untouched. */
    @Test
    fun `pruning removes expired content`() = withStore { _, store ->
        val expired = store.commit(
            address("documents.v1", "one", "expired"),
            byteArrayOf(1, 2),
            expiresAt = writtenAt,
        )
        val live = store.commit(address("documents.v1", "one", "live"), byteArrayOf(3))

        val result = store.prune(
            FileStorePruneRequest(expiredBefore = writtenAt + kotlin.time.Duration.parse("1s"))
        )

        assertEquals(1, result.entriesRemoved)
        assertEquals(2, result.contentBytesRemoved)
        assertNull(store.read(expired.address))
        assertNotNull(store.read(live.address))
    }

    /** Verifies recovery discards interrupted staging files and index rows without content. */
    @Test
    fun `recovery reconciles staging and orphan files`() = withStore { root, store ->
        val record = store.commit(address("documents.v1", "one", "entry"), byteArrayOf(1))
        store.startWrite(address("documents.v1", "one", "staged"), maxEntryBytes = 16)
            .write(byteArrayOf(9), 0, 1)
        Files.delete(Path.of(assertNotNull(store.acquire(record)).absolutePath))

        val report = store.recover()

        assertEquals(1, report.recordsRemoved)
        assertEquals(1, report.stagingFilesRemoved)
        assertNull(store.read(record.address))
        assertTrue(contentFiles(root).isEmpty())
    }

    /** Verifies a second store cannot open a root that another store already owns. */
    @Test
    fun `concurrent stores cannot share one root`() = runTest {
        val root = createTempDirectory("cache-kmp-file-lock-")
        val store = createJvmFileCacheStore(root, limits())
        try {
            assertFailsWith<IllegalStateException> { createJvmFileCacheStore(root, limits()) }
        } finally {
            store.close()
            root.toFile().deleteRecursively()
        }
    }

    /** Verifies close is idempotent and every later operation is rejected. */
    @Test
    fun `closed store rejects later operations`() = withStore { _, store ->
        store.close()
        store.close()

        assertFailsWith<IllegalStateException> {
            store.read(address("documents.v1", "one", "entry"))
        }
    }

    private suspend fun FileCacheStore.commit(
        address: FileStoreAddress,
        payload: ByteArray,
        policy: FileCachePolicy = FileCachePolicy(
            maxEntryBytes = 64,
            maxEntries = 8,
            maxBytes = 256,
        ),
        mediaType: String? = null,
        expiresAt: Instant? = null,
    ): StoredFileRecord {
        val result = attemptCommit(address, payload, policy, mediaType, expiresAt)
        return assertIs<FileStoreCommitted>(result).record
    }

    private suspend fun FileCacheStore.attemptCommit(
        address: FileStoreAddress,
        payload: ByteArray,
        policy: FileCachePolicy = FileCachePolicy(
            maxEntryBytes = 64,
            maxEntries = 8,
            maxBytes = 256,
        ),
        mediaType: String? = null,
        expiresAt: Instant? = null,
    ) = startWrite(address, policy.maxEntryBytes).let { session ->
        session.write(payload, 0, payload.size)
        commit(
            FileStoreCommitRequest(
                address = address,
                staged = session.finish(),
                content = FileContentMetadata(mediaType),
                metadata = CacheMetadata(validatedAt = writtenAt, expiresAt = expiresAt),
                policy = policy,
                writtenAt = writtenAt,
            )
        )
    }

    private fun address(namespace: String, partition: String, key: String): FileStoreAddress =
        FileStoreAddress(
            namespace = CacheNamespace(namespace),
            partition = CachePartition(partition),
            key = key,
        )

    private fun limits(): FileStoreLimits =
        FileStoreLimits(maxTotalEntries = 32, maxTotalBytes = 1_024)

    private fun contentFiles(root: Path): List<Path> =
        Files.list(root.resolve("data")).use { entries -> entries.toList() }

    private fun withStore(test: suspend (Path, FileCacheStore) -> Unit): Unit = runTest {
        val root = createTempDirectory("cache-kmp-file-")
        val store = createJvmFileCacheStore(root, limits())
        try {
            test(root, store)
        } finally {
            try {
                store.close()
            } catch (_: Throwable) {
                // The test may have closed the store deliberately.
            }
            root.toFile().deleteRecursively()
        }
    }
}
