package dev.voir.cache

import dev.voir.cache.core.model.CacheFreshness as CoreCacheFreshness
import dev.voir.cache.core.paging.PagedCacheActivity
import dev.voir.cache.core.paging.PagedCacheBoundary
import dev.voir.cache.core.paging.PagedCacheSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Accumulates source pages for one query into a single deduplicated list.
 *
 * [request] selects what the call does:
 *
 * - [CachePages.IF_NEEDED] returns the accumulated list while it is fresh and loads the first page
 *   otherwise.
 * - [CachePages.REFRESH] reloads the first page and drops every later page, because those pages
 *   were addressed by cursors the source has since invalidated.
 * - [CachePages.NEXT] appends the page after the accumulated cursor, merging by item identity so an
 *   overlapping page cannot duplicate an item already shown.
 *
 * Equal queries share one in-flight load. Loader exceptions are rethrown; a failure leaves the
 * accumulated list intact and reports the error through the returned state.
 *
 * @param Q Domain-owned query type.
 * @param I Non-null accumulated item type.
 * @param C Cursor type understood by the source.
 * @param scope Reusable accumulated-page identity and policy.
 * @param query Query whose pages accumulate together.
 * @param request What this call should do.
 * @param load Source loader receiving `null` for the first page and the stored cursor for appends.
 * @return Accumulated state after the request completes.
 */
public suspend fun <Q : Any, I : Any, C : Any> CacheManager.cachePages(
    scope: CachePagedScope<Q, I, C>,
    query: Q,
    request: CachePages = CachePages.IF_NEEDED,
    load: suspend (cursor: C?) -> CachePage<I, C>,
): CachePagedState<I, C> {
    val partition = scope.partitionForQuery(query)
    require(partition.isNotBlank()) { "Paged cache partition must not be blank." }
    val runtime = runtimes.paged(scope)
    return runtime.cache(query, partition, request, load).toPagedState()
}

/**
 * Observes accumulated pages for one query without invoking the source loader.
 *
 * The cold flow emits current state immediately and then emits every later change for the same
 * manager, namespace, partition, and serialized query.
 *
 * @param Q Domain-owned query type.
 * @param I Non-null accumulated item type.
 * @param C Cursor type understood by the source.
 * @param scope Reusable accumulated-page identity and policy.
 * @param query Query to observe.
 * @return Flow of accumulated state for the query.
 */
public fun <Q : Any, I : Any, C : Any> CacheManager.observePages(
    scope: CachePagedScope<Q, I, C>,
    query: Q,
): Flow<CachePagedState<I, C>> {
    val partition = scope.partitionForQuery(query)
    require(partition.isNotBlank()) { "Paged cache partition must not be blank." }
    return flow {
        val runtime = runtimes.paged(scope)
        emitAll(runtime.observe(query, partition).map { snapshot -> snapshot.toPagedState() })
    }
}

private fun <I : Any, C : Any> PagedCacheSnapshot<I, C, Throwable>.toPagedState():
    CachePagedState<I, C> = CachePagedState(
    items = items,
    nextCursor = nextCursor,
    totalCount = totalCount,
    pageCount = pageCount,
    freshness = when (freshness) {
        CoreCacheFreshness.ABSENT -> CacheFreshness.ABSENT
        CoreCacheFreshness.FRESH -> CacheFreshness.FRESH
        CoreCacheFreshness.STALE -> CacheFreshness.STALE
    },
    activity = when (activity) {
        PagedCacheActivity.IDLE -> CachePageActivity.IDLE
        PagedCacheActivity.LOADING -> CachePageActivity.LOADING
        PagedCacheActivity.REFRESHING -> CachePageActivity.REFRESHING
        PagedCacheActivity.APPENDING -> CachePageActivity.APPENDING
    },
    metadata = metadata?.let { current ->
        CacheEntryMetadata(validatedAt = current.validatedAt, expiresAt = current.expiresAt)
    },
    refreshError = refreshError,
    appendError = appendError,
    boundary = when (boundary) {
        null -> null
        PagedCacheBoundary.SOURCE_END -> CachePageBoundary.SOURCE_END
        PagedCacheBoundary.EMPTY_PAGE -> CachePageBoundary.EMPTY_PAGE
        PagedCacheBoundary.REPEATED_CURSOR -> CachePageBoundary.REPEATED_CURSOR
        PagedCacheBoundary.MAX_PAGES -> CachePageBoundary.MAX_PAGES
        PagedCacheBoundary.MAX_ITEMS_PER_QUERY -> CachePageBoundary.MAX_ITEMS_PER_QUERY
        PagedCacheBoundary.MAX_TOTAL_ITEMS -> CachePageBoundary.MAX_TOTAL_ITEMS
    },
)
