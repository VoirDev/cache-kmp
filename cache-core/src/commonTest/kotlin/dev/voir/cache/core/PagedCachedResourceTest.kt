package dev.voir.cache.core

import dev.voir.cache.core.config.familyPagedCacheSpec
import dev.voir.cache.core.config.pagedCacheSpec
import dev.voir.cache.core.fixture.FakeCacheClock
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheFamily
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.model.FetchFailure
import dev.voir.cache.core.model.FetchResult
import dev.voir.cache.core.model.FetchSuccess
import dev.voir.cache.core.paging.FetchedPage
import dev.voir.cache.core.paging.PageLoadRequest
import dev.voir.cache.core.paging.PagedCacheActivity
import dev.voir.cache.core.paging.PagedCacheBoundary
import dev.voir.cache.core.paging.PagedDuplicatePolicy
import dev.voir.cache.core.paging.PagedLoadDiscarded
import dev.voir.cache.core.paging.PagedLoadFailure
import dev.voir.cache.core.paging.PagedLoadSuccess
import dev.voir.cache.core.policy.ExpireAfter
import dev.voir.cache.core.policy.NeverExpire
import dev.voir.cache.core.policy.PagedMemoryPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Verifies paged accumulation, single-flight, bounds, and generation safety. */
@OptIn(ExperimentalCoroutinesApi::class)
class PagedCachedResourceTest {
    /** Initial loading is cached and ordered append reaches an explicit empty-page boundary. */
    @Test
    fun `initial load fresh reuse and ordered append accumulate one query`() = runTest {
        var fetchCount = 0
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = resource(manager) { request ->
            fetchCount += 1
            when (request.cursor) {
                null -> page(item("one"), next = 1, total = 2)
                1 -> page(item("two"), next = 2, total = 2)
                else -> FetchSuccess(FetchedPage(emptyList(), nextCursor = null, totalCount = 2))
            }
        }
        val query = query("a")

        val initial = assertIs<PagedLoadSuccess<Item, Int, String>>(resource.ensureLoaded(query))
        val reused = assertIs<PagedLoadSuccess<Item, Int, String>>(resource.ensureLoaded(query))
        val appended = assertIs<PagedLoadSuccess<Item, Int, String>>(resource.loadNext(query))
        val terminal = assertIs<PagedLoadSuccess<Item, Int, String>>(resource.loadNext(query))

        assertEquals(listOf("one"), initial.snapshot.items.map(Item::id))
        assertFalse(reused.sourceLoaded)
        assertEquals(listOf("one", "two"), appended.snapshot.items.map(Item::id))
        assertEquals(1, appended.itemsAdded)
        assertEquals(PagedCacheBoundary.EMPTY_PAGE, terminal.snapshot.boundary)
        assertFalse(terminal.snapshot.canLoadNext)
        assertEquals(3, fetchCount)
        manager.close()
    }

    /** Duplicate identities replace in place without duplicating accumulated rows. */
    @Test
    fun `duplicate append uses configured replacement policy`() = runTest {
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = resource(manager) { request ->
            if (request.cursor == null) {
                FetchSuccess(FetchedPage(listOf(Item("one", "old")), 1))
            } else {
                FetchSuccess(
                    FetchedPage(
                        listOf(Item("one", "new"), Item("two", "second")),
                        nextCursor = null,
                    )
                )
            }
        }

        resource.ensureLoaded(query("a"))
        val result = assertIs<PagedLoadSuccess<Item, Int, String>>(
            resource.loadNext(query("a"))
        )

        assertEquals(listOf(Item("one", "new"), Item("two", "second")), result.snapshot.items)
        assertEquals(1, result.itemsAdded)
        manager.close()
    }

    /** Append failure preserves pages and cursor so a later call retries the same page. */
    @Test
    fun `append failure is retryable without losing accumulated pages`() = runTest {
        var failAppend = true
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = resource(manager) { request ->
            if (request.cursor == null) page(item("one"), next = 1)
            else if (failAppend) FetchFailure("offline")
            else page(item("two"), next = null)
        }
        val query = query("a")
        resource.ensureLoaded(query)

        val failed = assertIs<PagedLoadFailure<Item, Int, String>>(resource.loadNext(query))
        failAppend = false
        val retried = assertIs<PagedLoadSuccess<Item, Int, String>>(resource.loadNext(query))

        assertEquals(listOf("one"), failed.snapshot.items.map(Item::id))
        assertEquals("offline", failed.snapshot.appendError)
        assertEquals(listOf("one", "two"), retried.snapshot.items.map(Item::id))
        manager.close()
    }

    /** Initial failure stays typed and leaves an observable retryable absent query entry. */
    @Test
    fun `initial failure is typed and ensure loaded retries it`() = runTest {
        var fail = true
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = resource(manager) {
            if (fail) FetchFailure("initial-offline") else page(item("loaded"), next = null)
        }
        val query = query("a")

        val failed = assertIs<PagedLoadFailure<Item, Int, String>>(resource.ensureLoaded(query))
        fail = false
        val retried = assertIs<PagedLoadSuccess<Item, Int, String>>(
            resource.ensureLoaded(query)
        )

        assertEquals("initial-offline", failed.snapshot.initialError)
        assertEquals(listOf("loaded"), retried.snapshot.items.map(Item::id))
        manager.close()
    }

    /** A repeated cursor terminates pagination instead of creating a request loop. */
    @Test
    fun `repeated next cursor is an explicit terminal boundary`() = runTest {
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = resource(manager) { request ->
            if (request.cursor == null) page(item("one"), next = 1)
            else page(item("two"), next = 1)
        }
        val query = query("a")
        resource.ensureLoaded(query)

        val repeated = assertIs<PagedLoadSuccess<Item, Int, String>>(resource.loadNext(query))
        val noOp = assertIs<PagedLoadSuccess<Item, Int, String>>(resource.loadNext(query))

        assertEquals(PagedCacheBoundary.REPEATED_CURSOR, repeated.snapshot.boundary)
        assertFalse(noOp.sourceLoaded)
        manager.close()
    }

    /** Failed refresh preserves old pages while successful refresh atomically replaces them. */
    @Test
    fun `refresh failure retains pages and success replaces them atomically`() = runTest {
        var call = 0
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = resource(manager) {
            call += 1
            when (call) {
                1 -> page(item("old"), next = 1)
                2 -> FetchFailure("refresh-offline")
                else -> page(item("new"), next = null)
            }
        }
        val query = query("a")
        resource.ensureLoaded(query)

        val failed = assertIs<PagedLoadFailure<Item, Int, String>>(resource.refresh(query))
        val replaced = assertIs<PagedLoadSuccess<Item, Int, String>>(resource.refresh(query))

        assertEquals(listOf("old"), failed.snapshot.items.map(Item::id))
        assertEquals("refresh-offline", failed.snapshot.refreshError)
        assertEquals(listOf("new"), replaced.snapshot.items.map(Item::id))
        assertEquals(1, replaced.snapshot.pageCount)
        manager.close()
    }

    /** Expired accumulated state is retained during one stale ensure-loaded replacement. */
    @Test
    fun `ensure loaded revalidates stale pages and atomically replaces them`() = runTest {
        val clock = FakeCacheClock(Instant.parse("2026-08-12T10:00:00Z"))
        var call = 0
        val manager = createCacheManager(backgroundScope.coroutineContext, clock = clock)
        val resource = manager.pagedResource(
            spec = pagedCacheSpec<Query, Int, Item, String>(
                namespace = "paged.stale.v1",
                query = { query -> EncodedCacheKey(CachePartition(query.partition), query.value) },
                itemId = Item::id,
                freshness = ExpireAfter(5.minutes),
                memory = defaultPolicy,
            ),
            fetch = {
                call += 1
                if (call == 1) page(item("old"), next = null)
                else page(item("new"), next = null)
            },
            mapUnexpectedFailure = ::failure,
        )
        val query = query("a")
        resource.ensureLoaded(query)
        clock.advanceBy(5.minutes)

        val refreshed = assertIs<PagedLoadSuccess<Item, Int, String>>(
            resource.ensureLoaded(query)
        )

        assertEquals(listOf("new"), refreshed.snapshot.items.map(Item::id))
        assertEquals(2, call)
        manager.close()
    }

    /** Concurrent append callers share exactly one request for the current cursor. */
    @Test
    fun `concurrent load next calls join one cursor flight`() = runTest {
        val appendStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var appendCount = 0
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = resource(manager) { request ->
            if (request.cursor == null) page(item("one"), next = 1)
            else {
                appendCount += 1
                appendStarted.complete(Unit)
                release.await()
                page(item("two"), next = null)
            }
        }
        val query = query("a")
        resource.ensureLoaded(query)

        val first = async { resource.loadNext(query) }
        appendStarted.await()
        val second = async { resource.loadNext(query) }
        runCurrent()
        assertEquals(1, appendCount)
        release.complete(Unit)

        assertEquals(
            listOf("one", "two"),
            assertIs<PagedLoadSuccess<Item, Int, String>>(first.await()).snapshot.items.map(Item::id),
        )
        assertIs<PagedLoadSuccess<Item, Int, String>>(second.await())
        assertEquals(1, manager.statistics().singleFlightJoins)
        manager.close()
    }

    /** Cancelling one waiter does not cancel shared page work required by another waiter. */
    @Test
    fun `cancelled append waiter does not cancel shared source work`() = runTest {
        val appendStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = resource(manager) { request ->
            if (request.cursor == null) page(item("one"), next = 1)
            else {
                appendStarted.complete(Unit)
                release.await()
                page(item("two"), next = null)
            }
        }
        val query = query("a")
        resource.ensureLoaded(query)
        val cancelled = async { resource.loadNext(query) }
        appendStarted.await()
        val retained = async { resource.loadNext(query) }
        runCurrent()

        cancelled.cancelAndJoin()
        release.complete(Unit)

        assertEquals(
            listOf("one", "two"),
            assertIs<PagedLoadSuccess<Item, Int, String>>(retained.await()).snapshot.items.map(Item::id),
        )
        manager.close()
    }

    /** A refresh generation replaces state and discards an older in-flight append. */
    @Test
    fun `late append cannot commit after refresh replacement`() = runTest {
        val appendStarted = CompletableDeferred<Unit>()
        val releaseAppend = CompletableDeferred<Unit>()
        var initialCalls = 0
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = resource(manager) { request ->
            if (request.cursor == 1) {
                appendStarted.complete(Unit)
                releaseAppend.await()
                page(item("late"), next = null)
            } else {
                initialCalls += 1
                if (initialCalls == 1) page(item("old"), next = 1)
                else page(item("replacement"), next = null)
            }
        }
        val query = query("a")
        resource.ensureLoaded(query)
        val append = async { resource.loadNext(query) }
        appendStarted.await()

        resource.refresh(query)
        releaseAppend.complete(Unit)

        assertIs<PagedLoadDiscarded<Item, Int, String>>(append.await())
        assertEquals(listOf("replacement"), resource.current(query).items.map(Item::id))
        manager.close()
    }

    /** Query and page/item limits evict whole queries or mark retained coverage partial. */
    @Test
    fun `query page and item bounds are deterministic and observable`() = runTest {
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val policy = PagedMemoryPolicy(
            maxQueries = 2,
            maxPagesPerQuery = 2,
            maxItemsPerQuery = 2,
            maxTotalItems = 3,
        )
        val resource = resource(manager, policy) { request ->
            when {
                request.query.value == "oversized" -> FetchSuccess(
                    FetchedPage(
                        listOf(item("one"), item("two"), item("three")),
                        nextCursor = 1,
                    )
                )
                request.cursor == null -> page(item("${request.query.value}-1"), next = 1)
                else -> page(item("${request.query.value}-2"), next = 2)
            }
        }
        val first = query("first")
        val second = query("second")
        val third = query("third")
        resource.ensureLoaded(first)
        resource.ensureLoaded(second)
        resource.current(first)
        resource.ensureLoaded(third)
        assertNull(resource.current(second).metadata)
        assertEquals(1, manager.statistics().evictions)

        val secondPage = assertIs<PagedLoadSuccess<Item, Int, String>>(resource.loadNext(first))
        assertEquals(PagedCacheBoundary.MAX_PAGES, secondPage.snapshot.boundary)
        assertTrue(secondPage.snapshot.isPartial)
        val oversized = assertIs<PagedLoadSuccess<Item, Int, String>>(
            resource.ensureLoaded(query("oversized"))
        )
        assertEquals(2, oversized.snapshot.items.size)
        assertEquals(PagedCacheBoundary.MAX_ITEMS_PER_QUERY, oversized.snapshot.boundary)
        manager.close()
    }

    /** Observation exposes manager-owned initial activity before the source completes. */
    @Test
    fun `observe exposes initial loading activity`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = resource(manager) {
            started.complete(Unit)
            release.await()
            page(item("one"), next = null)
        }
        val query = query("a")
        val load = async { resource.ensureLoaded(query) }
        started.await()

        val loading = resource.observe(query).first { snapshot ->
            snapshot.activity == PagedCacheActivity.INITIAL_LOADING
        }
        assertEquals(PagedCacheActivity.INITIAL_LOADING, loading.activity)
        release.complete(Unit)
        load.await()
        manager.close()
    }

    /** Typed family removal discards matching work without affecting another family. */
    @Test
    fun `paged family removal supersedes only matching query work`() = runTest {
        val selectedStarted = CompletableDeferred<Unit>()
        val unrelatedStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.pagedResource(
            spec = familyPagedCacheSpec<Query, Family, Int, Item, String>(
                namespace = "paged.family.v1",
                query = { query -> EncodedCacheKey(CachePartition(query.partition), query.value) },
                familyOf = { query -> Family(query.partition, query.family) },
                family = { family ->
                    EncodedCacheFamily(CachePartition(family.partition), family.value)
                },
                itemId = Item::id,
                freshness = NeverExpire,
                memory = defaultPolicy,
            ),
            fetch = { request ->
                if (request.query.family == "a") selectedStarted.complete(Unit)
                else unrelatedStarted.complete(Unit)
                release.await()
                page(item(request.query.value), next = null)
            },
            mapUnexpectedFailure = ::failure,
        )
        val selected = query("selected", family = "a")
        val unrelated = query("unrelated", family = "b")
        val selectedLoad = async { resource.ensureLoaded(selected) }
        val unrelatedLoad = async { resource.ensureLoaded(unrelated) }
        selectedStarted.await()
        unrelatedStarted.await()

        val removed = resource.removeFamily(Family("tenant", "a"))
        release.complete(Unit)

        assertEquals(1, removed.memoryEntriesAffected)
        assertIs<PagedLoadDiscarded<Item, Int, String>>(selectedLoad.await())
        assertIs<PagedLoadSuccess<Item, Int, String>>(unrelatedLoad.await())
        assertNull(resource.current(selected).metadata)
        assertEquals(listOf("unrelated"), resource.current(unrelated).items.map(Item::id))
        manager.close()
    }

    /** Manager partition clear discards matching query work and preserves another partition. */
    @Test
    fun `partition clear supersedes only matching paged query work`() = runTest {
        val selectedStarted = CompletableDeferred<Unit>()
        val unrelatedStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = resource(manager) { request ->
            if (request.query.partition == "selected") selectedStarted.complete(Unit)
            else unrelatedStarted.complete(Unit)
            release.await()
            page(item(request.query.value), next = null)
        }
        val selected = Query("selected", "family", "selected-query")
        val unrelated = Query("unrelated", "family", "unrelated-query")
        val selectedLoad = async { resource.ensureLoaded(selected) }
        val unrelatedLoad = async { resource.ensureLoaded(unrelated) }
        selectedStarted.await()
        unrelatedStarted.await()

        manager.clear(CachePartition("selected"))
        release.complete(Unit)

        assertIs<PagedLoadDiscarded<Item, Int, String>>(selectedLoad.await())
        assertIs<PagedLoadSuccess<Item, Int, String>>(unrelatedLoad.await())
        assertNull(resource.current(selected).metadata)
        assertEquals(
            listOf("unrelated-query"),
            resource.current(unrelated).items.map(Item::id),
        )
        manager.close()
    }

    private fun resource(
        manager: CacheManager,
        policy: PagedMemoryPolicy = defaultPolicy,
        fetch: suspend (PageLoadRequest<Query, Int>) -> FetchResult<FetchedPage<Item, Int>, String>,
    ): PagedCachedResource<Query, Int, Item, String> = manager.pagedResource(
        spec = pagedCacheSpec<Query, Int, Item, String>(
            namespace = "paged.values.v1",
            query = { query -> EncodedCacheKey(CachePartition(query.partition), query.value) },
            itemId = Item::id,
            duplicatePolicy = PagedDuplicatePolicy.REPLACE_EXISTING,
            freshness = NeverExpire,
            memory = policy,
        ),
        fetch = fetch,
        mapUnexpectedFailure = ::failure,
    )

    private fun page(item: Item, next: Int?, total: Int? = null): FetchSuccess<FetchedPage<Item, Int>> =
        FetchSuccess(FetchedPage(listOf(item), next, total))

    private fun item(id: String): Item = Item(id, id)

    private fun query(value: String, family: String = "default"): Query =
        Query("tenant", family, value)

    private fun failure(throwable: Throwable): String = throwable.message ?: "unexpected"

    private companion object {
        val defaultPolicy = PagedMemoryPolicy(
            maxQueries = 4,
            maxPagesPerQuery = 4,
            maxItemsPerQuery = 10,
            maxTotalItems = 20,
        )
    }
}

private data class Query(val partition: String, val family: String, val value: String)

private data class Family(val partition: String, val value: String)

private data class Item(val id: String, val value: String)
