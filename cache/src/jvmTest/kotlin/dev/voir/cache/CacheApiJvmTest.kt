package dev.voir.cache

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class CacheApiJvmTest {
    @Test
    fun `direct memory cache loads once and returns the cached value`() = withManager { manager ->
        var sourceCalls = 0
        val options = CacheOptions(
            namespace = "api.direct.memory",
            expiresAfter = 1.hours,
        )

        val first = manager.cache("key", options) {
            sourceCalls += 1
            "value"
        }
        val second = manager.cache("key", options) {
            sourceCalls += 1
            "other"
        }

        assertEquals("value", first)
        assertEquals("value", second)
        assertEquals(1, sourceCalls)
    }

    @Test
    fun `direct value observation emits refresh activity and committed data`() =
        withManager { manager ->
            val options = CacheOptions(namespace = "api.observe.direct.value")
            val sourceStarted = CompletableDeferred<Unit>()
            val releaseSource = CompletableDeferred<Unit>()

            val initial = manager.observe<String, String>("key", options).first()
            assertFalse(initial.hasValue)
            assertEquals(CacheFreshness.ABSENT, initial.freshness)

            val refreshing = async(start = CoroutineStart.UNDISPATCHED) {
                manager.observe<String, String>("key", options)
                    .first { state -> state.activity == CacheActivity.REFRESHING }
            }
            val loaded = async {
                manager.cache("key", options) {
                    sourceStarted.complete(Unit)
                    releaseSource.await()
                    "value"
                }
            }

            sourceStarted.await()
            assertTrue(refreshing.await().isLoading)
            releaseSource.complete(Unit)
            assertEquals("value", loaded.await())

            val committed = manager.observe<String, String>("key", options)
                .first { state -> state.value != null }
            assertEquals("value", committed.value)
            assertEquals(CacheFreshness.FRESH, committed.freshness)
            assertEquals(CacheActivity.IDLE, committed.activity)
            assertTrue(committed.hasValue)
            assertFalse(committed.isLoading)
            assertFalse(committed.isStale)
            assertNull(committed.refreshError)
        }

    @Test
    fun `scoped value observation emits updates and clear`() = withManager { manager ->
        val values = cacheScope<String, String>("api.observe.scope.value")
        val firstValue = async(start = CoroutineStart.UNDISPATCHED) {
            manager.observe(values, "key").first { state -> state.value == "value" }
        }

        manager.cache(values, "key") { "value" }
        assertEquals("value", firstValue.await().value)

        val cleared = async(start = CoroutineStart.UNDISPATCHED) {
            manager.observe(values, "key")
                .drop(1)
                .first { state -> state.freshness == CacheFreshness.ABSENT }
        }
        manager.clearAll()

        assertFalse(cleared.await().hasValue)
    }

    @Test
    fun `value observation retains stale data and exposes refresh failure`() =
        withManager { manager ->
            val values = cacheScope<String, String>("api.observe.refresh.failure") {
                expiresAfter = kotlin.time.Duration.ZERO
            }
            manager.cache(values, "key") { "stale" }
            val failed = async(start = CoroutineStart.UNDISPATCHED) {
                manager.observe(values, "key")
                    .first { state -> state.refreshError is SourceFailure }
            }

            val fallback = manager.cache(values, "key") { throw SourceFailure() }
            val failedState = failed.await()

            assertEquals("stale", fallback)
            assertEquals("stale", failedState.value)
            assertTrue(failedState.isStale)
        }

    @Test
    fun `scope expiration can be overridden for one call`() = withManager { manager ->
        var sourceCalls = 0
        val values = cacheScope<String, String>("api.scope.expiration") {
            expiresAfter = 1.hours
        }

        assertEquals("value-1", manager.cache(values, "key") { "value-${++sourceCalls}" })
        assertEquals(
            "value-2",
            manager.cache(
                scope = values,
                key = "key",
                overrides = CacheOverrides(expiresAfter = kotlin.time.Duration.ZERO),
            ) { "value-${++sourceCalls}" },
        )
        assertEquals("value-3", manager.cache(values, "key") { "value-${++sourceCalls}" })
        assertEquals(3, sourceCalls)
    }

    @Test
    fun `persistent direct cache restores serializable values after restart`() = runTest {
        val root = createTempDirectory("cache-kmp-api-persistent-")
        val options = CacheOptions(
            namespace = "api.direct.persistent",
            storage = CacheStorage.PERSISTENT,
            expiresAfter = 1.hours,
        )
        try {
            CacheManager(root, this).also { manager ->
                val loaded = manager.cache(ProfileKey("account", "profile"), options) {
                    Profile("Ada")
                }
                assertEquals(Profile("Ada"), loaded)
                manager.close()
            }

            var restartedCalls = 0
            CacheManager(root, this).also { manager ->
                val restored = manager.cache(ProfileKey("account", "profile"), options) {
                    restartedCalls += 1
                    Profile("Unexpected")
                }
                assertEquals(Profile("Ada"), restored)
                assertEquals(0, restartedCalls)
                manager.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `persistent observation hydrates data after restart without a loader`() = runTest {
        val root = createTempDirectory("cache-kmp-api-observe-persistent-")
        val options = CacheOptions(
            namespace = "api.observe.persistent",
            storage = CacheStorage.PERSISTENT,
            expiresAfter = 1.hours,
        )
        try {
            CacheManager(root, this).also { manager ->
                manager.cache("key", options) { "persisted" }
                manager.close()
            }

            CacheManager(root, this).also { manager ->
                val hydrated = manager.observe<String, String>("key", options)
                    .first { state -> state.value != null }

                assertEquals("persisted", hydrated.value)
                assertEquals(CacheFreshness.FRESH, hydrated.freshness)
                manager.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `stale value fallback is controlled per scoped call`() = withManager { manager ->
        val values = cacheScope<String, String>("api.scope.stale") {
            expiresAfter = 1.hours
        }
        manager.cache(values, "key") { "cached" }

        val fallback = manager.cache(
            scope = values,
            key = "key",
            overrides = CacheOverrides(expiresAfter = kotlin.time.Duration.ZERO),
        ) { throw SourceFailure() }
        assertEquals("cached", fallback)

        assertFailsWith<SourceFailure> {
            manager.cache(
                scope = values,
                key = "key",
                overrides = CacheOverrides(
                    expiresAfter = kotlin.time.Duration.ZERO,
                    useStaleOnError = false,
                ),
            ) { throw SourceFailure() }
        }
    }

    @Test
    fun `direct calls preserve source single flight by serialized address`() = withManager { manager ->
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var sourceCalls = 0
        val options = CacheOptions(namespace = "api.direct.single-flight")

        val first = async {
            manager.cache("key", options) {
                sourceCalls += 1
                started.complete(Unit)
                release.await()
                "first"
            }
        }
        started.await()
        val second = async {
            manager.cache("key", options) {
                sourceCalls += 1
                "second"
            }
        }
        yield()
        release.complete(Unit)

        assertEquals("first", first.await())
        assertEquals("first", second.await())
        assertEquals(1, sourceCalls)
    }

    @Test
    fun `namespace rejects conflicting stable configuration`() = withManager { manager ->
        manager.cache(
            key = "key",
            options = CacheOptions(namespace = "api.conflict", maxMemoryEntries = 1),
        ) { "value" }

        assertFailsWith<IllegalArgumentException> {
            manager.cache(
                key = "key",
                options = CacheOptions(namespace = "api.conflict", maxMemoryEntries = 2),
            ) { "value" }
        }
    }

    @Test
    fun `direct file cache streams leases and restores content after restart`() = runTest {
        val root = createTempDirectory("cache-kmp-api-file-")
        val options = CacheFileOptions(
            namespace = "api.direct.file",
            expiresAfter = 1.hours,
            mediaType = "application/octet-stream",
        )
        val expected = byteArrayOf(1, 2, 3, 4)
        try {
            CacheManager(root, this).also { manager ->
                val file = manager.cacheFile("key", options) { sink ->
                    sink.write(expected)
                }
                assertContentEquals(expected, Files.readAllBytes(Path.of(file.path)))
                assertEquals("application/octet-stream", file.mediaType)
                file.close()
                manager.close()
            }

            var restartedCalls = 0
            CacheManager(root, this).also { manager ->
                val restored = manager.cacheFile("key", options) {
                    restartedCalls += 1
                    error("Fresh file should not reload")
                }
                assertContentEquals(expected, Files.readAllBytes(Path.of(restored.path)))
                assertEquals(0, restartedCalls)
                restored.close()
                manager.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `direct file observation emits safe metadata when content changes`() =
        withManager { manager ->
            val options = CacheFileOptions(
                namespace = "api.observe.direct.file",
                mediaType = "image/png",
            )
            val observed = async(start = CoroutineStart.UNDISPATCHED) {
                manager.observeFile("key", options).first { state -> state.value != null }
            }

            manager.cacheFile("key", options) { sink ->
                sink.write(byteArrayOf(1, 2, 3))
            }.close()

            val state = observed.await()
            assertEquals(CachedFileInfo(sizeBytes = 3, mediaType = "image/png"), state.value)
            assertEquals(CacheFreshness.FRESH, state.freshness)
        }

    @Test
    fun `file scope supports per-call expiration override`() = withManager { manager ->
        var sourceCalls = 0
        val files = cacheFileScope<String>("api.scope.file") {
            expiresAfter = 1.hours
            maxEntryBytes = 32
            maxBytes = 64
        }

        manager.cacheFile(files, "key") { sink ->
            sourceCalls += 1
            sink.write(byteArrayOf(1))
        }.close()
        val refreshed = manager.cacheFile(
            scope = files,
            key = "key",
            overrides = CacheFileOverrides(expiresAfter = kotlin.time.Duration.ZERO),
        ) { sink ->
            sourceCalls += 1
            sink.write(byteArrayOf(2))
        }
        assertContentEquals(byteArrayOf(2), Files.readAllBytes(Path.of(refreshed.path)))
        assertEquals(2, sourceCalls)
        refreshed.close()
    }

    @Test
    fun `scoped file observation emits committed metadata and clear`() = withManager { manager ->
        val files = cacheFileScope<String>("api.observe.scope.file") {
            mediaType = "application/octet-stream"
        }
        val committed = async(start = CoroutineStart.UNDISPATCHED) {
            manager.observeFile(files, "key").first { state -> state.value != null }
        }

        manager.cacheFile(files, "key") { sink -> sink.write(byteArrayOf(1, 2)) }.close()
        assertEquals(2, committed.await().value?.sizeBytes)

        val cleared = async(start = CoroutineStart.UNDISPATCHED) {
            manager.observeFile(files, "key")
                .drop(1)
                .first { state -> state.freshness == CacheFreshness.ABSENT }
        }
        manager.clearAll()

        assertNull(cleared.await().value)
    }

    private fun withManager(
        test: suspend kotlinx.coroutines.test.TestScope.(CacheManager) -> Unit,
    ): Unit = runTest {
        val root = createTempDirectory("cache-kmp-api-")
        val manager = CacheManager(root, this)
        try {
            test(manager)
        } finally {
            manager.close()
            root.toFile().deleteRecursively()
        }
    }

    @Serializable
    private data class ProfileKey(val accountId: String, val value: String)

    @Serializable
    private data class Profile(val name: String)

    private class SourceFailure : IllegalStateException("source failed")
}
