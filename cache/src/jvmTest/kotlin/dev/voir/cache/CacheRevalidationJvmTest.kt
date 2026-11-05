package dev.voir.cache

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** Verifies that freshness and retention are separate, through the public facade only. */
class CacheRevalidationJvmTest {
    /** Verifies a stale persistent value outlives a restart and is observed before anything loads. */
    @Test
    fun `stale persistent values survive a restart`() = withRoot { root ->
        val values = cacheScope<String, String>("revalidation.restart") {
            storage = CacheStorage.PERSISTENT
            expiresAfter = 1.days
            revalidateAfter = Duration.ZERO
        }
        CacheManager(root, this).also { manager ->
            assertEquals("first", manager.cache(values, "key") { "first" })
            manager.close()
        }

        CacheManager(root, this).also { manager ->
            val restored = manager.observe(values, "key").first { it.hasValue }
            assertEquals("first", restored.value)
            assertTrue(restored.isStale)

            assertEquals("second", manager.cache(values, "key") { "second" })
            manager.close()
        }
    }

    /** Verifies the current state is read after restoration, without loading. */
    @Test
    fun `current state is read after a restart without loading`() = withRoot { root ->
        val values = cacheScope<String, String>("revalidation.current") {
            storage = CacheStorage.PERSISTENT
            expiresAfter = 1.days
            revalidateAfter = Duration.ZERO
        }
        CacheManager(root, this).also { manager ->
            manager.cache(values, "kept") { "value" }
            manager.close()
        }

        CacheManager(root, this).also { manager ->
            val kept = manager.current(values, "kept")
            val absent = manager.current(values, "absent")

            assertEquals("value", kept.value)
            assertTrue(kept.isStale)
            assertNull(absent.value)
            manager.close()
        }
    }

    /** Verifies a value within its freshness is reused, and one past it loads again. */
    @Test
    fun `values reload once past their freshness`() = withManager { manager ->
        var calls = 0
        val fresh = cacheScope<String, String>("revalidation.fresh") { revalidateAfter = 1.hours }
        val stale = cacheScope<String, String>("revalidation.stale") { revalidateAfter = Duration.ZERO }

        manager.cache(fresh, "key") { "fresh-${++calls}" }
        manager.cache(fresh, "key") { "fresh-${++calls}" }
        manager.cache(stale, "key") { "stale-${++calls}" }
        val reloaded = manager.cache(stale, "key") { "stale-${++calls}" }

        assertEquals("stale-3", reloaded)
        assertEquals(3, calls)
    }

    /** Verifies a failed revalidation answers the stale value it keeps. */
    @Test
    fun `failed revalidation answers the stale value`() = withManager { manager ->
        val values = cacheScope<String, String>("revalidation.stale-on-error") {
            expiresAfter = 1.days
            revalidateAfter = Duration.ZERO
        }
        manager.cache(values, "key") { "kept" }

        val answered = manager.cache(values, "key") { error("offline") }

        assertEquals("kept", answered)
    }

    /** Verifies observed metadata reports when a value turns stale apart from when it expires. */
    @Test
    fun `metadata reports staleness and expiration`() = withManager { manager ->
        val values = cacheScope<String, String>("revalidation.metadata") { revalidateAfter = 1.hours }
        manager.cache(values, "key") { "value" }

        val metadata = assertNotNull(manager.observe(values, "key").first { it.hasValue }.metadata)

        assertNull(metadata.expiresAt)
        assertEquals(metadata.validatedAt + 1.hours, metadata.staleAt)
    }

    /** Verifies a per-call freshness applies to the value that call commits. */
    @Test
    fun `revalidateAfter override applies to the committed value`() = withManager { manager ->
        var calls = 0
        val values = cacheScope<String, String>("revalidation.override") {
            expiresAfter = 1.days
            revalidateAfter = 1.hours
        }

        manager.cache(
            scope = values,
            key = "key",
            overrides = CacheOverrides(refresh = CacheRefresh.ALWAYS, revalidateAfter = Duration.ZERO),
        ) { "value-${++calls}" }
        val reloaded = manager.cache(values, "key") { "value-${++calls}" }

        assertEquals("value-2", reloaded)
    }

    /** Verifies shortening only the expiration also shortens a longer freshness. */
    @Test
    fun `freshness never outlasts the expiration`() = withManager { manager ->
        var calls = 0
        val values = cacheScope<String, String>("revalidation.capped") {
            expiresAfter = 1.days
            revalidateAfter = 1.hours
        }

        manager.cache(values, "key", CacheOverrides(expiresAfter = Duration.ZERO)) { "value-${++calls}" }
        manager.cache(values, "key") { "value-${++calls}" }

        assertEquals(2, calls)
    }

    /**
     * Runs [test] with a temporary manager root, deleted afterwards.
     *
     * @param test The scenario, given the root.
     */
    private fun withRoot(test: suspend TestScope.(java.nio.file.Path) -> Unit): Unit = runTest {
        val root = createTempDirectory("cache-kmp-revalidation-")
        try {
            test(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Runs [test] with a manager over a temporary root, closed and deleted afterwards.
     *
     * @param test The scenario, given the manager.
     */
    private fun withManager(test: suspend TestScope.(CacheManager) -> Unit): Unit = withRoot { root ->
        val manager = CacheManager(root, this)
        try {
            test(manager)
        } finally {
            manager.close()
        }
    }
}
