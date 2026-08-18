package dev.voir.cache

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** Verifies request behavior, bounds, isolation, and lifecycle through the public facade only. */
class CacheBehaviorJvmTest {
    /** Verifies a forced refresh reloads and commits even while the entry is fresh. */
    @Test
    fun `forced refresh always invokes the loader`() = withManager { manager ->
        var calls = 0
        val values = cacheScope<String, String>("behavior.forced") { expiresAfter = 1.hours }

        assertEquals("value-1", manager.cache(values, "key") { "value-${++calls}" })
        val forced = manager.cache(
            scope = values,
            key = "key",
            overrides = CacheOverrides(refresh = CacheRefresh.ALWAYS),
        ) { "value-${++calls}" }

        assertEquals("value-2", forced)
        assertEquals("value-2", manager.cache(values, "key") { "value-${++calls}" })
        assertEquals(2, calls)
    }

    /** Verifies distinct partitions never share an entry for the same serialized key. */
    @Test
    fun `partitions isolate entries with equal keys`() = withManager { manager ->
        val values = cacheScope<AccountKey, String>("behavior.partitions") {
            expiresAfter = 1.hours
            partitionBy { key -> key.accountId }
        }

        val first = manager.cache(values, AccountKey("one", "entry")) { "one" }
        val second = manager.cache(values, AccountKey("two", "entry")) { "two" }

        assertEquals("one", first)
        assertEquals("two", second)
        assertEquals("one", manager.cache(values, AccountKey("one", "entry")) { "reloaded" })
    }

    /** Verifies the memory bound evicts and a persistent entry is restored from disk afterwards. */
    @Test
    fun `evicted persistent entries rehydrate without the loader`() = withManager { manager ->
        var calls = 0
        val values = cacheScope<String, String>("behavior.rehydrate") {
            storage = CacheStorage.PERSISTENT
            expiresAfter = 1.hours
            maxMemoryEntries = 1
        }

        assertEquals("value-first", manager.cache(values, "first") { "value-first".also { calls++ } })
        assertEquals(
            "value-second",
            manager.cache(values, "second") { "value-second".also { calls++ } },
        )

        assertEquals("value-first", manager.cache(values, "first") { "reloaded".also { calls++ } })
        assertEquals(2, calls)
    }

    /** Verifies a memory-only entry evicted by the bound is reloaded from the source. */
    @Test
    fun `evicted memory entries reload from the source`() = withManager { manager ->
        var calls = 0
        val values = cacheScope<String, String>("behavior.memory-bound") {
            expiresAfter = 1.hours
            maxMemoryEntries = 1
        }

        manager.cache(values, "first") { "value-${++calls}" }
        manager.cache(values, "second") { "value-${++calls}" }
        manager.cache(values, "first") { "value-${++calls}" }

        assertEquals(3, calls)
    }

    /** Verifies one caller's cancellation cannot cancel shared work for another caller. */
    @Test
    fun `cancelling one caller leaves the shared loader running`() = withManager { manager ->
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val values = cacheScope<String, String>("behavior.cancellation")

        val cancelled = launch {
            manager.cache(values, "key") {
                calls += 1
                started.complete(Unit)
                release.await()
                "value"
            }
        }
        started.await()
        val remaining = async { manager.cache(values, "key") { "unexpected" } }
        runCurrent()

        cancelled.cancelAndJoin()
        release.complete(Unit)

        assertEquals("value", remaining.await())
        assertEquals(1, calls)
    }

    /** Verifies an expired persistent entry reloads instead of being restored after a restart. */
    @Test
    fun `expired persistent entries reload after restart`() = runTest {
        val root = createTempDirectory("cache-kmp-behavior-expiry-")
        val options = CacheOptions(
            namespace = "behavior.expiry",
            storage = CacheStorage.PERSISTENT,
            expiresAfter = Duration.ZERO,
        )
        try {
            CacheManager(root, this).also { manager ->
                assertEquals("first", manager.cache("key", options) { "first" })
                manager.close()
            }

            CacheManager(root, this).also { manager ->
                assertEquals("second", manager.cache("key", options) { "second" })
                manager.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /** Verifies clearing removes memory, persistent, and file data, and caching resumes after it. */
    @Test
    fun `clearing removes every storage kind`() = withManager { manager ->
        val values = cacheScope<String, String>("behavior.clear.values") {
            storage = CacheStorage.PERSISTENT
            expiresAfter = 1.hours
        }
        val files = cacheFileScope<String>("behavior.clear.files") { expiresAfter = 1.hours }
        manager.cache(values, "key") { "value" }
        manager.cacheFile(files, "key") { sink -> sink.write(byteArrayOf(1)) }.close()

        manager.clearAll()

        var valueCalls = 0
        var fileCalls = 0
        assertEquals("reloaded", manager.cache(values, "key") { "reloaded".also { valueCalls++ } })
        manager.cacheFile(files, "key") { sink ->
            fileCalls += 1
            sink.write(byteArrayOf(2))
        }.close()
        assertEquals(1, valueCalls)
        assertEquals(1, fileCalls)
    }

    /** Verifies a file larger than the per-entry bound reports a stable failure reason. */
    @Test
    fun `oversized files report ENTRY_TOO_LARGE`() = withManager { manager ->
        val files = cacheFileScope<String>("behavior.file.too-large") {
            maxEntryBytes = 4
            maxBytes = 64
        }

        val failure = assertFailsWith<CacheFileException> {
            manager.cacheFile(files, "key") { sink -> sink.write(ByteArray(8)) }
        }

        assertEquals(CacheFileFailureReason.ENTRY_TOO_LARGE, failure.reason)
    }

    /** Verifies an open lease blocks eviction and the new request reports capacity exhaustion. */
    @Test
    fun `leases block eviction and report CAPACITY_UNAVAILABLE`() = withManager { manager ->
        val files = cacheFileScope<String>("behavior.file.capacity") {
            maxEntryBytes = 8
            maxEntries = 1
            maxBytes = 8
        }
        val leased = manager.cacheFile(files, "first") { sink -> sink.write(ByteArray(8)) }

        try {
            val failure = assertFailsWith<CacheFileException> {
                manager.cacheFile(files, "second") { sink -> sink.write(ByteArray(8)) }
            }
            assertEquals(CacheFileFailureReason.CAPACITY_UNAVAILABLE, failure.reason)
        } finally {
            leased.close()
        }
    }

    /** Verifies a failed file loader falls back to the previous generation when allowed. */
    @Test
    fun `file loader failure falls back to stale content`() = withManager { manager ->
        val files = cacheFileScope<String>("behavior.file.stale") { expiresAfter = Duration.ZERO }
        manager.cacheFile(files, "key") { sink -> sink.write(byteArrayOf(1, 2)) }.close()

        val fallback = manager.cacheFile(files, "key") { error("download failed") }
        try {
            assertContentEquals(byteArrayOf(1, 2), Files.readAllBytes(Path.of(fallback.path)))
        } finally {
            fallback.close()
        }

        assertFailsWith<IllegalStateException> {
            manager.cacheFile(
                scope = files,
                key = "key",
                overrides = CacheFileOverrides(useStaleOnError = false),
            ) { error("download failed") }
        }
    }

    /** Verifies a closed manager is idempotent and rejects later cache requests. */
    @Test
    fun `closed manager rejects later requests`() = runTest {
        val root = createTempDirectory("cache-kmp-behavior-close-")
        try {
            val manager = CacheManager(root, this)
            manager.close()
            manager.close()

            assertFailsWith<IllegalStateException> {
                manager.cache("key", CacheOptions(namespace = "behavior.closed")) { "value" }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /** Verifies a value cache and a file cache cannot share one namespace. */
    @Test
    fun `value and file caches cannot share a namespace`() = withManager { manager ->
        manager.cache("key", CacheOptions(namespace = "behavior.kind")) { "value" }

        assertFailsWith<IllegalArgumentException> {
            manager.cacheFile("key", CacheFileOptions(namespace = "behavior.kind")) { sink ->
                sink.write(byteArrayOf(1))
            }
        }
    }

    /** Verifies a blank scope name is rejected where it is declared rather than where it is used. */
    @Test
    fun `blank scope names are rejected`() {
        assertFailsWith<IllegalArgumentException> { cacheScope<String, String>(" ") }
        assertFailsWith<IllegalArgumentException> { cacheFileScope<String>(" ") }
    }

    /** Verifies a blank derived partition is rejected before a request reaches storage. */
    @Test
    fun `blank derived partitions are rejected`() = withManager { manager ->
        val values = cacheScope<AccountKey, String>("behavior.blank-partition") {
            partitionBy { key -> key.accountId }
        }

        assertFailsWith<IllegalArgumentException> {
            manager.cache(values, AccountKey(" ", "entry")) { "value" }
        }
    }

    /** Verifies concurrent requests for distinct addresses load independently. */
    @Test
    fun `distinct addresses load concurrently`() = withManager { manager ->
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val values = cacheScope<String, String>("behavior.concurrent")

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            manager.cache(values, "first") {
                firstStarted.complete(Unit)
                secondStarted.await()
                "first"
            }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            manager.cache(values, "second") {
                secondStarted.complete(Unit)
                firstStarted.await()
                "second"
            }
        }
        yield()

        assertEquals("first", first.await())
        assertEquals("second", second.await())
        assertTrue(firstStarted.isCompleted && secondStarted.isCompleted)
    }

    private fun withManager(test: suspend TestScope.(CacheManager) -> Unit): Unit = runTest {
        val root = createTempDirectory("cache-kmp-behavior-")
        val manager = CacheManager(root, this)
        try {
            test(manager)
        } finally {
            manager.close()
            root.toFile().deleteRecursively()
        }
    }

    @Serializable
    private data class AccountKey(val accountId: String, val value: String)
}
