package dev.voir.cache

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** Verifies accumulated page caching, deduplication, and refresh invalidation. */
class CachePagedJvmTest {
    /** Verifies pages accumulate into one list and the cursor advances. */
    @Test
    fun `pages accumulate across requests`() = withManager { manager ->
        val feed = feedScope("paged.accumulate")
        val source = FakeFeed(
            pages = listOf(
                page(items = listOf("a", "b"), next = "c1"),
                page(items = listOf("c", "d"), next = "c2"),
                page(items = listOf("e"), next = null),
            ),
        )

        val first = manager.cachePages(feed, Query("all")) { cursor -> source.load(cursor) }
        val second = manager.cachePages(feed, Query("all"), CachePages.NEXT) { cursor ->
            source.load(cursor)
        }
        val third = manager.cachePages(feed, Query("all"), CachePages.NEXT) { cursor ->
            source.load(cursor)
        }

        assertEquals(listOf("a", "b"), first.items)
        assertEquals(listOf("a", "b", "c", "d"), second.items)
        assertEquals(listOf("a", "b", "c", "d", "e"), third.items)
        assertEquals(3, third.pageCount)
        assertEquals(CachePageBoundary.SOURCE_END, third.boundary)
        assertFalse(third.canLoadNext)
    }

    /** Verifies a fresh accumulation is reused without invoking the loader. */
    @Test
    fun `fresh accumulation is reused`() = withManager { manager ->
        val feed = feedScope("paged.reuse")
        var calls = 0

        manager.cachePages(feed, Query("all")) { calls += 1; page(listOf("a"), next = "c1") }
        val reused = manager.cachePages(feed, Query("all")) {
            calls += 1
            page(listOf("z"), next = null)
        }

        assertEquals(listOf("a"), reused.items)
        assertEquals(1, calls)
    }

    /**
     * Verifies a refresh drops later pages.
     *
     * This is the duplicate-entry case: after the source inserts an item at the top, the cursors
     * held by pages two and three describe a window that has shifted. Keeping them would repeat
     * items that the reloaded first page already contains.
     */
    @Test
    fun `refresh discards pages loaded from superseded cursors`() = withManager { manager ->
        val feed = feedScope("paged.refresh")
        val source = FakeFeed(
            pages = listOf(
                page(items = listOf("a", "b"), next = "c1"),
                page(items = listOf("c", "d"), next = "c2"),
            ),
        )
        manager.cachePages(feed, Query("all")) { cursor -> source.load(cursor) }
        val accumulated = manager.cachePages(feed, Query("all"), CachePages.NEXT) { cursor ->
            source.load(cursor)
        }
        assertEquals(listOf("a", "b", "c", "d"), accumulated.items)

        // The source inserted "new" at the top, so every stored cursor now points one item later.
        source.replace(
            listOf(
                page(items = listOf("new", "a"), next = "c1"),
                page(items = listOf("b", "c"), next = "c2"),
            )
        )
        val refreshed = manager.cachePages(feed, Query("all"), CachePages.REFRESH) { cursor ->
            source.load(cursor)
        }

        assertEquals(listOf("new", "a"), refreshed.items)
        assertEquals(1, refreshed.pageCount)
        assertEquals("c1", refreshed.nextCursor)
        assertTrue(refreshed.canLoadNext)
    }

    /** Verifies an overlapping appended page cannot introduce a duplicate item. */
    @Test
    fun `overlapping pages are deduplicated by item identity`() = withManager { manager ->
        val feed = feedScope("paged.dedup")
        val source = FakeFeed(
            pages = listOf(
                page(items = listOf("a", "b"), next = "c1"),
                page(items = listOf("b", "c"), next = null),
            ),
        )

        manager.cachePages(feed, Query("all")) { cursor -> source.load(cursor) }
        val appended = manager.cachePages(feed, Query("all"), CachePages.NEXT) { cursor ->
            source.load(cursor)
        }

        assertEquals(listOf("a", "b", "c"), appended.items)
    }

    /** Verifies a stale accumulation reloads the first page and drops later pages. */
    @Test
    fun `stale accumulation reloads only the first page`() = withManager { manager ->
        val feed = feedScope("paged.stale", expiresAfter = Duration.ZERO)
        val source = FakeFeed(
            pages = listOf(
                page(items = listOf("a"), next = "c1"),
                page(items = listOf("b"), next = "c2"),
            ),
        )
        manager.cachePages(feed, Query("all")) { cursor -> source.load(cursor) }
        manager.cachePages(feed, Query("all"), CachePages.NEXT) { cursor -> source.load(cursor) }

        val reloaded = manager.cachePages(feed, Query("all")) { cursor -> source.load(cursor) }

        assertEquals(listOf("a"), reloaded.items)
        assertEquals(1, reloaded.pageCount)
    }

    /** Verifies the page bound stops accumulation and is reported. */
    @Test
    fun `page bound stops accumulation`() = withManager { manager ->
        val feed = feedScope("paged.max-pages", maxPages = 2)
        val source = FakeFeed(
            pages = listOf(
                page(items = listOf("a"), next = "c1"),
                page(items = listOf("b"), next = "c2"),
                page(items = listOf("c"), next = "c3"),
            ),
        )
        manager.cachePages(feed, Query("all")) { cursor -> source.load(cursor) }
        val second = manager.cachePages(feed, Query("all"), CachePages.NEXT) { cursor ->
            source.load(cursor)
        }
        assertEquals(CachePageBoundary.MAX_PAGES, second.boundary)

        val blocked = manager.cachePages(feed, Query("all"), CachePages.NEXT) { cursor ->
            source.load(cursor)
        }

        assertEquals(listOf("a", "b"), blocked.items)
        assertEquals(2, source.calls)
    }

    /** Verifies the per-query item bound truncates accumulation and is reported. */
    @Test
    fun `item bound truncates accumulation`() = withManager { manager ->
        val feed = feedScope("paged.max-items", maxItemsPerQuery = 3)
        val source = FakeFeed(
            pages = listOf(
                page(items = listOf("a", "b"), next = "c1"),
                page(items = listOf("c", "d"), next = "c2"),
            ),
        )
        manager.cachePages(feed, Query("all")) { cursor -> source.load(cursor) }

        val appended = manager.cachePages(feed, Query("all"), CachePages.NEXT) { cursor ->
            source.load(cursor)
        }

        assertEquals(listOf("a", "b", "c"), appended.items)
        assertEquals(CachePageBoundary.MAX_ITEMS_PER_QUERY, appended.boundary)
    }

    /** Verifies a failed append keeps the accumulated list and reports the error separately. */
    @Test
    fun `append failure retains accumulated items`() = withManager { manager ->
        val feed = feedScope("paged.append-failure")
        val source = FakeFeed(pages = listOf(page(items = listOf("a"), next = "c1")))
        manager.cachePages(feed, Query("all")) { cursor -> source.load(cursor) }

        val state = manager.cachePages(feed, Query("all"), CachePages.NEXT) {
            error("append failed")
        }

        assertEquals(listOf("a"), state.items)
        assertTrue(state.appendError is IllegalStateException)
        assertNull(state.refreshError)
        assertFalse(state.isLoading)
    }

    /** Verifies a first-page failure with nothing accumulated is rethrown to the caller. */
    @Test
    fun `first page failure without accumulated items is rethrown`() = withManager { manager ->
        val feed = feedScope("paged.first-failure")

        assertFailsWith<IllegalStateException> {
            manager.cachePages(feed, Query("all")) { error("load failed") }
        }
    }

    /** Verifies disabling stale fallback makes every load failure observable to the caller. */
    @Test
    fun `disabled stale fallback rethrows an append failure`() = withManager { manager ->
        val feed = cachePagedScope<Query, String, String>("paged.no-stale") {
            expiresAfter = 1.hours
            useStaleOnError = false
            identifyBy { item -> item }
        }
        manager.cachePages(feed, Query("all")) { page(listOf("a"), next = "c1") }

        assertFailsWith<IllegalStateException> {
            manager.cachePages(feed, Query("all"), CachePages.NEXT) { error("append failed") }
        }
    }

    /** Verifies distinct queries accumulate independently. */
    @Test
    fun `queries accumulate independently`() = withManager { manager ->
        val feed = feedScope("paged.queries")

        val all = manager.cachePages(feed, Query("all")) { page(listOf("a"), next = null) }
        val mine = manager.cachePages(feed, Query("mine")) { page(listOf("m"), next = null) }

        assertEquals(listOf("a"), all.items)
        assertEquals(listOf("m"), mine.items)
    }

    /** Verifies equal queries share one in-flight page load. */
    @Test
    fun `equal queries share one page load`() = withManager { manager ->
        val feed = feedScope("paged.single-flight")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            manager.cachePages(feed, Query("all")) {
                calls += 1
                started.complete(Unit)
                release.await()
                page(listOf("a"), next = null)
            }
        }
        started.await()
        val second = async { manager.cachePages(feed, Query("all")) { page(listOf("z"), null) } }
        release.complete(Unit)

        assertEquals(listOf("a"), first.await().items)
        assertEquals(listOf("a"), second.await().items)
        assertEquals(1, calls)
    }

    /** Verifies observation emits accumulated changes without invoking the loader. */
    @Test
    fun `observation emits accumulated changes`() = withManager { manager ->
        val feed = feedScope("paged.observe")
        val observed = async(start = CoroutineStart.UNDISPATCHED) {
            manager.observePages(feed, Query("all")).first { state -> state.hasItems }
        }

        manager.cachePages(feed, Query("all")) { page(listOf("a", "b"), next = null) }

        assertEquals(listOf("a", "b"), observed.await().items)
    }

    /** Verifies clearing the manager also discards accumulated pages. */
    @Test
    fun `clearing discards accumulated pages`() = withManager { manager ->
        val feed = feedScope("paged.clear")
        manager.cachePages(feed, Query("all")) { page(listOf("a"), next = null) }

        manager.clearAll()
        val state = manager.observePages(feed, Query("all")).first()

        assertFalse(state.hasItems)
        assertEquals(0, state.pageCount)
        assertEquals(CacheFreshness.ABSENT, state.freshness)
    }

    /** Verifies a scope without an item identity is rejected where it is declared. */
    @Test
    fun `scopes must declare an item identity`() {
        assertFailsWith<IllegalArgumentException> {
            cachePagedScope<Query, String, String>("paged.no-identity") { maxPages = 2 }
        }
    }

    private fun feedScope(
        name: String,
        expiresAfter: Duration = 1.hours,
        maxPages: Int = 20,
        maxItemsPerQuery: Int = 1_000,
    ): CachePagedScope<Query, String, String> = cachePagedScope(name) {
        this.expiresAfter = expiresAfter
        this.maxPages = maxPages
        this.maxItemsPerQuery = maxItemsPerQuery
        identifyBy { item -> item }
    }

    private fun page(items: List<String>, next: String?): CachePage<String, String> =
        CachePage(items = items, nextCursor = next)

    private fun withManager(test: suspend TestScope.(CacheManager) -> Unit): Unit = runTest {
        val root = createTempDirectory("cache-kmp-paged-")
        val manager = CacheManager(root, this)
        try {
            test(manager)
        } finally {
            manager.close()
            root.toFile().deleteRecursively()
        }
    }

    @Serializable
    private data class Query(val filter: String)

    /** Cursor-addressed fake source whose pages can be replaced between requests. */
    private class FakeFeed(private var pages: List<CachePage<String, String>>) {
        var calls: Int = 0
            private set

        fun replace(replacement: List<CachePage<String, String>>) {
            pages = replacement
        }

        fun load(cursor: String?): CachePage<String, String> {
            calls += 1
            val index = if (cursor == null) 0 else pages.indexOfFirst { it.nextCursor == cursor } + 1
            return pages.getOrElse(index) { CachePage(emptyList(), null) }
        }
    }
}
