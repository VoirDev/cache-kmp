# Paged caching

`cachePages` accumulates source pages for one query into a single deduplicated list. It is designed
for cursor-paginated feeds and search results, where a user scrolls through pages that the source
can reorder between requests.

Paged state is process memory only. A cursor is a source-owned window whose meaning the cache
cannot verify in a later process, so accumulated pages are never restored from disk.

## Declare a scope

A paged scope needs an item identity. Pages overlap whenever the source inserts or removes an item
between requests, so identity is required rather than optional:

```kotlin
import dev.voir.cache.cachePagedScope
import kotlin.time.Duration.Companion.minutes

@Serializable
data class FeedQuery(val accountId: String, val filter: String)

val feed = cachePagedScope<FeedQuery, Article, String>("feed") {
    expiresAfter = 5.minutes
    maxPages = 20
    maxItemsPerQuery = 500
    maxQueries = 10
    maxTotalItems = 2_000
    duplicates = CacheDuplicates.REPLACE_EXISTING
    identifyBy { article -> article.id }
    partitionBy { query -> query.accountId }
}
```

The three type arguments are the query, the accumulated item, and the cursor the source understands.

## Load, append, and refresh

One call site covers all three intents. The loader receives `null` for the first page and the
accumulated cursor for an append:

```kotlin
suspend fun articles(request: CachePages = CachePages.IF_NEEDED): CachePagedState<Article, String> =
    cache.cachePages(feed, FeedQuery(accountId, "latest"), request) { cursor ->
        val response = api.loadArticles(cursor)
        CachePage(
            items = response.articles,
            nextCursor = response.nextCursor,
            totalCount = response.total,
        )
    }
```

- `CachePages.IF_NEEDED` returns the accumulated list while it is fresh and loads the first page
  otherwise.
- `CachePages.NEXT` appends the page after the accumulated cursor.
- `CachePages.REFRESH` reloads the first page.

## Why a refresh drops later pages

This is the behavior to understand before using the API.

Accumulated pages are one logical value, not several independent ones. Page two was addressed by a
cursor the source produced while page one had its previous contents. As soon as the first page
changes — an item is inserted, removed, or reordered — that cursor describes a different window.
Keeping page two after reloading page one would show items twice or skip them entirely.

So `CachePages.REFRESH` reloads the first page and discards every later page, resetting `pageCount`
to `1` and returning a fresh cursor. The same happens when an expired accumulation is reloaded with
`CachePages.IF_NEEDED`. Callers continue from the returned `nextCursor`:

```kotlin
val refreshed = articles(CachePages.REFRESH)
// refreshed.pageCount == 1, refreshed.items == the current first page
// refreshed.canLoadNext == true when the source reported a cursor
```

Appends are protected differently. An appended page is merged by item identity rather than
concatenated, so a page that overlaps what is already accumulated cannot introduce a duplicate.
`duplicates` decides whether the overlapping copy replaces the accumulated item in place
(`REPLACE_EXISTING`, the default) or is ignored (`KEEP_EXISTING`). Item order always follows the
position where an identity was first seen.

## Reading the state

`CachePagedState` keeps the accumulated list available while work runs or after it fails:

```kotlin
val state = articles()

state.items          // deduplicated items across every loaded page
state.pageCount      // pages currently accumulated
state.nextCursor     // cursor for the next page, when one is known
state.totalCount     // latest total reported by the source
state.canLoadNext    // whether CachePages.NEXT would load anything
state.boundary       // why accumulation stopped, when it did
state.refreshError   // latest first-page failure
state.appendError    // latest append failure
```

`boundary` distinguishes a source that ended (`SOURCE_END`, `EMPTY_PAGE`, `REPEATED_CURSOR`) from
accumulation the cache stopped itself (`MAX_PAGES`, `MAX_ITEMS_PER_QUERY`, `MAX_TOTAL_ITEMS`). A
cache-imposed boundary means the list is intentionally partial; a source boundary means the list is
complete.

## Failures

A loader exception is rethrown when there is nothing to fall back to. Once pages are accumulated,
`useStaleOnError` (default `true`) returns the accumulated list with the failure reported through
`refreshError` or `appendError`, so a list already on screen survives a failed "load more". Set
`useStaleOnError = false` to make every failure observable to the caller instead.

Equal queries share one in-flight load. First-page work and append work are separate flights,
because a refresh supersedes the accumulation an append would have extended.

## Observing

`observePages` exposes the same state as a cold flow and never invokes the loader:

```kotlin
val feedState: StateFlow<CachePagedState<Article, String>> = cache
    .observePages(feed, FeedQuery(accountId, "latest"))
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CachePagedState(),
    )
```

Trigger loading from the owner with `cachePages`, exactly as with `cache` and `observe`.

## Bounds and clearing

`maxPages` and `maxItemsPerQuery` bound one query; `maxQueries` and `maxTotalItems` bound the
namespace. Reaching a bound sets `boundary` rather than failing. `clearAll()` discards accumulated
pages along with memory values, persistent values, and cached files.
