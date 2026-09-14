# cache-kmp

`cache-kmp` is one Kotlin Multiplatform cache for Android, iOS, and JVM. One `CacheManager`
internally owns bounded memory caching, Room-backed persistent values, accumulated page caching,
streamed file storage, startup recovery, and pruning. Consumers use Kotlinx serialization and never
initialize a codec, database, file index, or storage adapter.

## Install

Version `1.0.0` is published as one artifact:

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

Custom keys and values must be serializable:

```kotlin
import kotlinx.serialization.Serializable

@Serializable
data class ProfileKey(val accountId: String)

@Serializable
data class Profile(val displayName: String)
```

Primitive and standard Kotlin types with built-in serializers work without annotations.

## Create one manager

Manager creation is suspending because persistent stores are opened and recovered before the
manager is returned. Use a long-lived application or session `CoroutineScope`.

```kotlin
// Android: storage is selected below the application context.
val cache = CacheManager(
    context = applicationContext,
    scope = applicationScope,
)

// JVM: rootDirectory must be an absolute, application-owned directory.
val cache = CacheManager(
    rootDirectory = cacheRoot.toAbsolutePath(),
    scope = applicationScope,
)

// iOS: storage is selected below the system caches directory.
val cache = CacheManager(scope = applicationScope)
```

All storage initialization and maintenance are internal. `CacheManagerOptions` is only needed to
change whole-manager persistent or file limits.

## Direct caching

For a one-off or locally configured cache, call `cache` directly. Memory is the default:

```kotlin
val article = cache.cache(key = articleId) {
    api.loadArticle(articleId)
}
```

Add `CacheOptions` when identity or policy must be explicit:

```kotlin
import dev.voir.cache.CacheOptions
import dev.voir.cache.CacheStorage
import dev.voir.cache.cache
import kotlin.time.Duration.Companion.minutes

val profile = cache.cache(
    key = ProfileKey(accountId),
    options = CacheOptions(
        namespace = "profiles",
        partition = accountId,
        storage = CacheStorage.PERSISTENT,
        expiresAfter = 30.minutes,
        maxMemoryEntries = 100,
        maxPayloadBytes = 1_048_576,
    ),
) {
    api.loadProfile(accountId)
}
```

Keys and values are encoded internally with Kotlinx serialization. A persistent value is restored
after manager recreation without invoking the loader while the stored entry remains fresh.

When `namespace` is omitted, `cache` derives one from the serialized value type. Specify a stable,
unique namespace whenever the same value type represents more than one logical cache.

## Reusable scopes

A scope keeps identity, limits, expiration, storage, and partitioning in one reusable declaration:

```kotlin
import dev.voir.cache.CacheOverrides
import dev.voir.cache.CacheStorage
import dev.voir.cache.cache
import dev.voir.cache.cacheScope
import kotlin.time.Duration.Companion.minutes

val profiles = cacheScope<ProfileKey, Profile>("profiles") {
    storage = CacheStorage.PERSISTENT
    expiresAfter = 30.minutes
    maxMemoryEntries = 100
    maxPayloadBytes = 1_048_576
    partitionBy { it.accountId }
}

val profile = cache.cache(profiles, ProfileKey(accountId)) {
    api.loadProfile(accountId)
}

val shortLivedProfile = cache.cache(
    scope = profiles,
    key = ProfileKey(accountId),
    overrides = CacheOverrides(expiresAfter = 5.minutes),
) {
    api.loadProfile(accountId)
}
```

Per-call overrides can change expiration, refresh behavior, and stale-on-error fallback without
redeclaring the scope. Storage and capacity limits remain stable for the namespace.

## Paged caching

`cachePages` accumulates cursor-paginated results for one query into a single deduplicated list:

```kotlin
import dev.voir.cache.CachePage
import dev.voir.cache.CachePages
import dev.voir.cache.cachePagedScope
import dev.voir.cache.cachePages
import kotlin.time.Duration.Companion.minutes

val feed = cachePagedScope<FeedQuery, Article, String>("feed") {
    expiresAfter = 5.minutes
    maxPages = 20
    maxItemsPerQuery = 500
    identifyBy { article -> article.id }
    partitionBy { query -> query.accountId }
}

suspend fun articles(request: CachePages = CachePages.IF_NEEDED) =
    cache.cachePages(feed, FeedQuery(accountId, "latest"), request) { cursor ->
        val response = api.loadArticles(cursor)
        CachePage(response.articles, nextCursor = response.nextCursor)
    }

val first = articles()                       // loads page one when absent or stale
val more = articles(CachePages.NEXT)         // appends the next page
val reloaded = articles(CachePages.REFRESH)  // reloads page one, drops later pages
```

A refresh discards later pages on purpose. Their cursors were produced while the first page held
different contents, so keeping them would repeat or skip items. Appends merge by the identity given
to `identifyBy`, so an overlapping page cannot duplicate an item already shown. `observePages`
exposes the same accumulated state as a cold `Flow`.

Accumulated pages are process memory only. See [Paged caching](docs/paged-caching.md) for the
complete contract.

## File caching

`cacheFile` streams bytes into cache-owned staging without loading the complete file into memory:

```kotlin
import dev.voir.cache.CacheFileOptions
import dev.voir.cache.cacheFile
import kotlin.time.Duration.Companion.days

val avatar = cache.cacheFile(
    key = ProfileKey(accountId),
    options = CacheFileOptions(
        namespace = "avatars",
        partition = accountId,
        expiresAfter = 1.days,
        maxEntryBytes = 10L * 1_024 * 1_024,
        maxEntries = 500,
        maxBytes = 200L * 1_024 * 1_024,
        mediaType = "image/jpeg",
    ),
) { sink ->
    api.downloadAvatar(accountId, sink)
}

try {
    imageLoader.open(avatar.path)
} finally {
    avatar.close()
}
```

The returned `CachedFile` is a lease. Its absolute path remains valid until suspending `close()`
completes. File scopes are available for repeated policy:

```kotlin
val avatars = cacheFileScope<ProfileKey>("avatars") {
    expiresAfter = 1.days
    maxEntryBytes = 10L * 1_024 * 1_024
    maxEntries = 500
    maxBytes = 200L * 1_024 * 1_024
    mediaType = "image/jpeg"
    partitionBy { it.accountId }
}

val avatar = cache.cacheFile(avatars, ProfileKey(accountId)) { sink ->
    api.downloadAvatar(accountId, sink)
}
```

## Observe cache changes

`observe` exposes a cold `Flow<CacheState<V>>` for memory and persistent values. It emits the
current state immediately and then emits whenever the same manager changes that address:

```kotlin
import dev.voir.cache.CacheState
import dev.voir.cache.observe
import kotlinx.coroutines.flow.Flow

val profileStates: Flow<CacheState<Profile>> = cache.observe(
    scope = profiles,
    key = ProfileKey(accountId),
)

profileStates.collect { state ->
    renderProfile(
        profile = state.value,
        loading = state.isLoading,
        stale = state.isStale,
        error = state.refreshError,
    )
}
```

Observation does not invoke a loader. Keep loading and refreshing explicit with `cache`; every
active observer sees its progress and committed result:

```kotlin
suspend fun refreshProfile() {
    cache.cache(
        scope = profiles,
        key = ProfileKey(accountId),
        overrides = CacheOverrides(refresh = CacheRefresh.ALWAYS),
    ) {
        api.loadProfile(accountId)
    }
}
```

Direct observation uses the same options and inferred serializers as direct caching:

```kotlin
val states: Flow<CacheState<Profile>> = cache.observe(
    key = ProfileKey(accountId),
    options = CacheOptions(
        namespace = "profiles",
        partition = accountId,
        storage = CacheStorage.PERSISTENT,
    ),
)
```

Persistent observation first emits process-local state, then reports hydration and emits restored
disk data without calling the source. `CacheState` distinguishes absent, fresh, and stale data;
reports idle, hydration, and refresh activity; and includes validation timestamps and the latest
loader error.

Use `observeFile` for file-cache changes. It emits safe `CachedFileInfo` metadata rather than an
unleased path. Acquire and close a `CachedFile` whenever content must be read:

```kotlin
cache.observeFile(avatars, ProfileKey(accountId)).collect { state ->
    renderAvatarStatus(
        available = state.hasValue,
        loading = state.isLoading,
        sizeBytes = state.value?.sizeBytes,
    )
}

suspend fun openAvatar() {
    val file = cache.cacheFile(avatars, ProfileKey(accountId)) { sink ->
        api.downloadAvatar(accountId, sink)
    }
    try {
        imageLoader.open(file.path)
    } finally {
        file.close()
    }
}
```

See [Observing cache state](docs/observation.md) for UI `StateFlow` usage and the complete
observation contract.

## Request behavior

- `CacheRefresh.IF_NEEDED` reuses a fresh entry and loads only when absent or stale.
- `CacheRefresh.ALWAYS` always invokes the loader and commits the successful result.
- Concurrent requests for the same serialized namespace, partition, and key share one loader.
- Loader exceptions are rethrown. When stale data exists and `useStaleOnError` is `true`, that data
  is returned instead.
- A namespace cannot be reused in one manager with different key/value types, storage, or stable
  limits; incompatible configuration fails immediately.
- Accumulated pages deduplicate by item identity, and reloading the first page discards later
  pages addressed by superseded cursors.
- `clearAll()` clears every memory value, persistent value, accumulated page, and file owned by the
  manager.
- `close()` is idempotent and closes every internally owned backend.

Cached data is disposable acceleration, not an authoritative offline database. The library does
not provide networking, retries, encryption, image decoding, or background scheduling.

## Documentation

- [Getting started](docs/getting-started.md)
- [Observing cache state](docs/observation.md)
- [Paged caching](docs/paged-caching.md)
- [Guarantees and limitations](docs/guarantees-and-limitations.md)
- [File-cache contract](docs/file-cache-contract.md)
- [Lifecycle ownership](docs/lifecycle.md)
- [Serialized-data changes](docs/migrations.md)
- [Testing](docs/testing.md)
- [Compatibility](docs/compatibility.md)
- [Versioning and publishing](docs/versioning-and-publishing.md)

## Build from source

```shell
./gradlew build
```

The [standalone consumers](samples/consumer) resolve the locally published `dev.voir:cache` artifact
rather than depending on project internals.

## License

Apache License 2.0. See [LICENSE](LICENSE).
