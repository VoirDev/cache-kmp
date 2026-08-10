# Getting started

## Dependencies

Choose only the artifacts your application needs. In a Kotlin Multiplatform source set:

```kotlin
commonMain.dependencies {
    implementation("dev.voir:cache-core:1.0.0")

    // Add these only for encoded Room persistence.
    implementation("dev.voir:cache-room:1.0.0")
    implementation("dev.voir:cache-codec-kotlinx:1.0.0")
}
```

`cache-core` has no Room or SQLite dependency. `cache-testing` is intended for test source sets.

## Memory-only resource

The domain owns its key, value, errors, source loader, and stable address encoding. It can register
a resource directly without an application-specific cache facade:

```kotlin
data class ArticleKey(val accountId: String, val slug: String)
data class Article(val title: String)

val manager = createCacheManager(applicationScope.coroutineContext)
val articles = manager.resource(
    spec = cacheSpec<ArticleKey, Article>(
        namespace = "articles.v1",
        key = CacheKeyEncoder { key ->
            EncodedCacheKey(CachePartition(key.accountId), key.slug)
        },
        freshness = ExpireAfter(5.minutes),
        storage = memoryOnly(),
        memory = MemoryPolicy(maxEntries = 100),
    ),
    fetch = { key -> api.loadArticle(key).toFetchResult() },
    mapUnexpectedFailure = ::mapDomainError,
)
```

Use `get` for fresh-or-load semantics and `refresh` to force the source. `observe` never invokes the
source, but collection of a persistent resource starts or joins lazy disk hydration. Use `current`
to await hydration without source loading, or `peek` for a process-local memory snapshot that does
not start or await disk work.

## Persistent resource

Create one platform store and pass ownership to the manager:

```kotlin
// JVM
val store = createJvmRoomPersistentCacheStore(databasePath)

// Android
val store = createAndroidRoomPersistentCacheStore(applicationContext, "cache.db")

// iOS
val store = createIosRoomPersistentCacheStore(absoluteDatabasePath)
```

Register a serializable value with an explicit codec and payload version:

```kotlin
val manager = createCacheManager(
    coroutineContext = applicationScope.coroutineContext,
    persistentStore = store,
    config = CacheManagerConfig(
        persistentRetention = PersistentRetentionPolicy(
            expiredEntryGrace = 1.days,
            maxEntriesPerNamespace = 1_000,
            maxTotalEntries = 10_000,
            maxTotalPayloadBytes = 50L * 1024 * 1024,
        ),
    ),
)
val profiles = manager.resource(
    spec = cacheSpec<ProfileKey, Profile>(
        namespace = "profiles.v1",
        key = profileKeyEncoder,
        freshness = ExpireAfter(30.minutes),
        storage = persistent(
            codec = KotlinxJsonCacheCodec(Profile.serializer(), currentVersion = 1),
        ),
        memory = MemoryPolicy(maxEntries = 100),
    ),
    fetch = { key -> api.loadProfile(key).toFetchResult() },
    mapUnexpectedFailure = ::mapDomainError,
)
```

Hydration is lazy. The first `observe`, `current`, `get`, or `refresh` checks Room. Observation and
`current` never invoke the source; `get` calls it only when the hydrated value is absent or stale.
`peek` remains memory-only and can therefore report absence before hydration.

## Invalidation and clearing

```kotlin
profiles.invalidate(key)                    // Keep the value but mark it stale.
profiles.remove(key)                        // Remove exactly one address.
profiles.clear()                            // Clear the profiles namespace.
manager.clear(CachePartition(accountId))    // Clear one account across namespaces.
manager.clearAll()                          // Clear everything owned by the manager.
val report = manager.prune()                // Apply configured retention.
val occupancy = manager.storageStatistics() // Sanitized rows/bytes by namespace.
val metrics = manager.statistics()           // Hits, outcomes, joins, evictions, and memory.
```

Use a partition for every tenant, account, session, or environment boundary. Clear the old
partition during logout or account switching before allowing the new session to read cached data.

Persistent retention limits are enforced only when the host calls `prune()`. The library does not
invent a startup, foreground, or background schedule. `prune(explicitPolicy)` can run a one-shot
policy without changing manager defaults. Reports use `CachePersistenceStatus` to distinguish a
successful backend, a memory-only manager, and a degraded persistent operation.

## Typed family operations

Use `familyCacheSpec` when several exact keys must be invalidated or removed together. The family
type stays at the domain boundary; only normalized partition and family metadata enter the cache:

```kotlin
data class DetailKey(val accountId: String, val commandId: String)
data class AccountFamily(val accountId: String)

val details = manager.resource(
    spec = familyCacheSpec<DetailKey, AccountFamily, Detail>(
        namespace = "command-details.v1",
        key = { key -> EncodedCacheKey(CachePartition(key.accountId), key.commandId) },
        familyOf = { key -> AccountFamily(key.accountId) },
        family = { value ->
            EncodedCacheFamily(CachePartition(value.accountId), "command-details")
        },
        freshness = ExpireAfter(5.minutes),
        storage = persistent(detailCodec),
        memory = MemoryPolicy(maxEntries = 200),
    ),
    fetch = { key -> api.loadDetail(key).toFetchResult() },
    mapUnexpectedFailure = ::mapDomainError,
)

val invalidated = details.invalidateFamily(AccountFamily(accountId))
val removed = details.removeFamily(AccountFamily(accountId))
```

Scope is exactly one resource namespace, one partition, and one encoded family. The result reports
memory entries, persistent rows when known, and persistence availability. Older hydration/source
work for matching addresses cannot commit; unrelated families and partitions continue normally.

## Accumulated paged resource

Use `pagedCacheSpec` for cursor or numeric-page protocols that need coherent accumulated query
state. The source receives both the typed query and the current cursor:

```kotlin
val feed = manager.pagedResource(
    spec = pagedCacheSpec<FeedQuery, String, FeedItem, String>(
        namespace = "feed.v1",
        query = { query -> EncodedCacheKey(CachePartition(query.accountId), query.filter) },
        itemId = FeedItem::id,
        duplicatePolicy = PagedDuplicatePolicy.REPLACE_EXISTING,
        freshness = ExpireAfter(5.minutes),
        memory = PagedMemoryPolicy(
            maxQueries = 8,
            maxPagesPerQuery = 10,
            maxItemsPerQuery = 250,
            maxTotalItems = 1_000,
        ),
    ),
    fetch = { request -> api.page(request.query, request.cursor).toFetchResult() },
    mapUnexpectedFailure = ::mapDomainError,
)

feed.observe(query)
feed.ensureLoaded(query) // Fresh reuse or one initial/stale source flight.
feed.loadNext(query)     // One shared request for the committed next cursor.
feed.refresh(query)      // Retains old items, then atomically replaces them on success.
```

`PagedCacheSnapshot` separates initial, refresh, and append errors. `boundary` explains source end,
empty/repeated cursor, or retention truncation; `isPartial` is true when a finite limit stopped
accumulation before source end. Query entries are evicted whole using LRU. Current paged storage is
memory-only; manager recreation intentionally starts a new paged session.

An optional `CacheEventListener` receives ordered start/completion pairs outside cache state locks.
See [diagnostics and operation statistics](diagnostics.md) for correlation, timing, metrics, and
privacy contracts.

## Lifecycle

The manager owns its supervisor job, registered resources, memory, and optional persistent store.
Create it at the application/session scope and always call `close()` when that scope ends. Closing
is idempotent, cancels manager work, clears memory, closes the store, and does not cancel the host
parent job. Restartable hosts should use `RestartableCacheManagerOwner` and await `stop()` before
cancelling their host scope. See [lifecycle ownership](lifecycle.md) for shutdown ordering, failure
semantics, and Koin integration boundaries.

Runnable consumers are under [`samples/consumer`](../samples/consumer). They resolve published
artifacts from a local Maven repository rather than using Gradle project dependencies.
