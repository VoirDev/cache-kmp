# Getting started

## 1. Add the single dependency

Apply Kotlin serialization and add `cache-kmp` to `commonMain`:

```kotlin
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.voir:cache:1.0.0")
        }
    }
}
```

There are no separate memory, Room, codec, file, or testing artifacts.

## 2. Make cache types serializable

Every custom key and cached value uses Kotlinx serialization:

```kotlin
@Serializable
data class ArticleKey(
    val accountId: String,
    val slug: String,
)

@Serializable
data class Article(
    val title: String,
    val body: String,
)
```

The serialized key is part of durable cache identity. Keep its meaning and encoding stable for a
given namespace.

## 3. Create the manager in platform code

Creation is suspending and performs internal database/file initialization, recovery, and initial
pruning.

```kotlin
// Android
val cache = CacheManager(applicationContext, applicationScope)

// JVM
val cache = CacheManager(cacheRoot.toAbsolutePath(), applicationScope)

// iOS
val cache = CacheManager(applicationScope)
```

Use `CacheManagerOptions` only for whole-manager safety caps:

```kotlin
val options = CacheManagerOptions(
    persistentMaxEntries = 10_000,
    persistentMaxBytes = 256L * 1_024 * 1_024,
    fileMaxEntries = 2_000,
    fileMaxBytes = 512L * 1_024 * 1_024,
)
```

The JVM root must be absolute, application-owned, and not a filesystem root. The manager owns only
its `cache-kmp` child. Android resolves application-private storage from the supplied context. iOS
uses the application caches directory.

## 4. Choose direct calls or scopes

Use a direct call when policy is local:

```kotlin
val article = cache.cache(
    key = ArticleKey(accountId, slug),
    options = CacheOptions(
        namespace = "articles",
        partition = accountId,
        expiresAfter = 5.minutes,
    ),
) {
    api.loadArticle(slug)
}
```

Use a scope when several calls share identity and policy:

```kotlin
val articles = cacheScope<ArticleKey, Article>("articles") {
    expiresAfter = 5.minutes
    maxMemoryEntries = 100
    partitionBy { it.accountId }
}

val article = cache.cache(articles, ArticleKey(accountId, slug)) {
    api.loadArticle(slug)
}
```

Memory is the default storage. Set `storage = CacheStorage.PERSISTENT` to restore serialized values
after manager or process recreation:

```kotlin
val profiles = cacheScope<ProfileKey, Profile>("profiles") {
    storage = CacheStorage.PERSISTENT
    expiresAfter = 30.minutes
    maxMemoryEntries = 100
    maxPayloadBytes = 1_048_576
    partitionBy { it.accountId }
}
```

The first request checks memory, then internal Room storage, then the loader. A successful loader
result is cached and returned directly as `V`; consumers do not handle an engine result hierarchy.

## 5. Override one request

Scope defaults can be changed for one call:

```kotlin
val profile = cache.cache(
    scope = profiles,
    key = ProfileKey(accountId),
    overrides = CacheOverrides(
        expiresAfter = Duration.ZERO,
        refresh = CacheRefresh.ALWAYS,
        useStaleOnError = false,
    ),
) {
    api.loadProfile(accountId)
}
```

The effective expiration is stored with the committed entry. A zero-duration result is immediately
stale, so the next `IF_NEEDED` request loads again.

## 6. Observe changes

Use a scope to observe one value as reactive state:

```kotlin
val states: Flow<CacheState<Article>> = cache.observe(
    scope = articles,
    key = ArticleKey(accountId, slug),
)
```

The flow emits current cache state immediately and later emits persistent hydration, source refresh,
successful replacement, failure, and clear changes for the same address. It does not
invoke a loader. Continue to use `cache` with `CacheRefresh.ALWAYS` when the application wants to
refresh explicitly.

Direct values use `observe(key, options)`. Files use `observeFile(key, options)` or
`observeFile(scope, key)` and emit `CachedFileInfo` rather than an unsafe, unleased path. See
[observing cache state](observation.md) for complete examples.

## 7. Accumulate pages

Cursor-paginated results accumulate into one list per query:

```kotlin
val feed = cachePagedScope<FeedQuery, Article, String>("feed") {
    expiresAfter = 5.minutes
    identifyBy { article -> article.id }
    partitionBy { query -> query.accountId }
}

val state = cache.cachePages(feed, FeedQuery(accountId, "latest"), CachePages.NEXT) { cursor ->
    val response = api.loadArticles(cursor)
    CachePage(response.articles, nextCursor = response.nextCursor)
}
```

Reloading the first page discards later pages so a shifted source cannot produce duplicates. See
[paged caching](paged-caching.md).

## 8. Stream files

The file loader receives a `CacheFileSink`. Write chunks as they arrive:

```kotlin
val document = cache.cacheFile(
    key = DocumentKey(accountId, documentId),
    options = CacheFileOptions(
        namespace = "documents",
        partition = accountId,
        expiresAfter = 1.days,
        maxEntryBytes = 25L * 1_024 * 1_024,
        maxEntries = 100,
        maxBytes = 250L * 1_024 * 1_024,
        mediaType = "application/pdf",
    ),
) { sink ->
    documentApi.download(documentId) { bytes -> sink.write(bytes) }
}

try {
    viewer.open(document.path)
} finally {
    document.close()
}
```

The manager stores file content outside Room and keeps only its durable index and metadata there.
Never retain the path after closing the lease.

## Namespaces, partitions, and keys

An address consists of a namespace, partition, and serialized key.

- Namespace identifies one logical cache and its stable configuration.
- Partition isolates an account, tenant, environment, or session.
- Key identifies one entry inside that partition.

Direct calls derive a namespace when it is omitted, but explicit names are safer for long-lived or
persistent caches. A manager rejects reuse of a namespace with incompatible types, storage, or
limits.

`clearAll()` is the public invalidation boundary; it clears memory values, persistent values,
accumulated pages, and cached files. Use different managers when independent global clearing or
lifecycle ownership is required.

## Lifecycle

Keep the manager in a long-lived owner rather than a screen or short-lived UI component. Call
`close()` at explicit suspending shutdown boundaries such as tests, logout-driven container
replacement, or a controlled JVM host. Close any active `CachedFile` leases first.

See [lifecycle ownership](lifecycle.md) and [guarantees and limitations](guarantees-and-limitations.md)
for the complete behavioral contract.
