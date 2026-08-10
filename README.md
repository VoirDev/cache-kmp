# cache-kmp

`cache-kmp` is a typed Kotlin Multiplatform cache library targeting Android, iOS, and JVM. It keeps
domain models independent from storage, supports bounded memory caching by default, and adds Room
persistence and kotlinx.serialization only when their focused artifacts are selected.

## Coordinates

- Group: `dev.voir`
- Project: `cache-kmp`
- Current version: `1.0.0`

Published functionality is split into focused artifacts:

| Artifact | Responsibility |
| --- | --- |
| `dev.voir:cache-core` | Cache contracts, engine, memory tier, and persistence SPI |
| `dev.voir:cache-room` | Generic Room-backed persistent store |
| `dev.voir:cache-codec-kotlinx` | Optional kotlinx.serialization JSON codec |
| `dev.voir:cache-testing` | Reusable deterministic test fixtures and fakes |

## In-memory cache

Add only `cache-core` when values should live for the lifetime of the cache manager:

```kotlin
commonMain.dependencies {
    implementation("dev.voir:cache-core:1.0.0")
}
```

Define a domain key, a value, and a resource. The key encoder controls both isolation and identity:

```kotlin
import dev.voir.cache.core.createCacheManager
import dev.voir.cache.core.config.cacheSpec
import dev.voir.cache.core.key.CacheKeyEncoder
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.model.CacheFailure
import dev.voir.cache.core.model.CacheSuccess
import dev.voir.cache.core.policy.ExpireAfter
import dev.voir.cache.core.policy.MemoryPolicy
import dev.voir.cache.core.storage.memoryOnly
import kotlin.time.Duration.Companion.minutes

data class ArticleKey(
    val accountId: String,
    val slug: String,
)

data class Article(
    val title: String,
)

data class ArticleError(
    val message: String,
)

val manager = createCacheManager(applicationScope.coroutineContext)

val articles = manager.resource(
    spec = cacheSpec<ArticleKey, Article>(
        namespace = "articles.v1",
        key = CacheKeyEncoder { key ->
            EncodedCacheKey(
                partition = CachePartition(key.accountId),
                value = key.slug,
            )
        },
        freshness = ExpireAfter(5.minutes),
        storage = memoryOnly(),
        memory = MemoryPolicy(maxEntries = 100),
    ),
    fetch = { key: ArticleKey -> loadArticle(key) },
    mapUnexpectedFailure = { throwable ->
        ArticleError(throwable.message ?: "Unexpected source failure")
    },
)
```

Here, `applicationScope` is the long-lived scope that owns the cache and `loadArticle` is the
application's source function. It returns `FetchResult<Article, ArticleError>`, so expected source
failures remain typed. Read and refresh the resource from a coroutine:

```kotlin
val key = ArticleKey("account-1", "cache-design")

when (val result = articles.get(key)) {
    is CacheSuccess -> showArticle(result.value)
    is CacheFailure -> {
        result.staleValue?.let(::showArticle)
        showError(result.error)
    }
    is CacheDiscarded -> render(result.currentSnapshot)
}

articles.refresh(key)           // Always asks the source for a newer value.
val memory = articles.peek(key)  // Reads memory without disk hydration or source loading.
val current = articles.current(key) // Awaits disk hydration but never invokes the source.
val states = articles.observe(key) // May hydrate from disk; never invokes the source.
articles.invalidate(key)        // Retains the value but marks it stale.
articles.remove(key)            // Removes exactly one cached address.
manager.clear(CachePartition("account-1"))
manager.close()                 // Call when the owning application/session scope ends.
```

For a restartable JVM/DI host, use `RestartableCacheManagerOwner` and await its `stop()` before
cancelling the owning scope. See [lifecycle ownership](docs/lifecycle.md) for the complete concurrent
shutdown contract and the safe Koin boundary.

Concurrent `get` calls for the same address share one source request. Fresh values are returned
without calling the source, and the least recently used unobserved entries are evicted when
`maxEntries` is reached.

A source completion rejected by a newer mutation or clear returns `CacheDiscarded`. Its
`currentSnapshot` is authoritative; a rejected source value is never returned as committed state.

Persistent managers accept a `PersistentRetentionPolicy` through `CacheManagerConfig`. Hosts call
`prune()` when appropriate and can inspect sanitized occupancy through `storageStatistics()`.
Retention supports expiration grace, namespace and whole-store row bounds, and an encoded-payload
byte bound; the library does not schedule maintenance itself.

Optional diagnostics provide ordered correlated operation events with sanitized outcomes, timing,
single-flight status, eviction causes, and occupancy. `manager.statistics()` exposes the same
manager-level aggregate even when no event listener is configured.

Resources with composite keys can declare a typed family boundary. Family operations use exact
namespace, partition, and encoded-family metadata—never string-prefix matching:

```kotlin
data class CommandKey(val accountId: String, val commandId: String)
data class AccountFamily(val accountId: String)

val commands = manager.resource(
    spec = familyCacheSpec<CommandKey, AccountFamily, Command>(
        namespace = "commands.v1",
        key = { key -> EncodedCacheKey(CachePartition(key.accountId), key.commandId) },
        familyOf = { key -> AccountFamily(key.accountId) },
        family = { value ->
            EncodedCacheFamily(CachePartition(value.accountId), "commands")
        },
        freshness = ExpireAfter(5.minutes),
        storage = memoryOnly(),
        memory = MemoryPolicy(maxEntries = 100),
    ),
    fetch = ::loadCommand,
    mapUnexpectedFailure = ::mapCommandError,
)

commands.invalidateFamily(AccountFamily("account-1"))
commands.removeFamily(AccountFamily("account-1"))
```

Accumulated pagination uses a separate bounded memory-only resource. The cache owns initial,
refresh, and append source coordination instead of requiring a consumer-side `update()` race:

```kotlin
val search = manager.pagedResource(
    spec = pagedCacheSpec<SearchQuery, String, SearchItem, String>(
        namespace = "search.v1",
        query = { query -> EncodedCacheKey(CachePartition(query.accountId), query.text) },
        itemId = SearchItem::id,
        freshness = ExpireAfter(5.minutes),
        memory = PagedMemoryPolicy(
            maxQueries = 10,
            maxPagesPerQuery = 20,
            maxItemsPerQuery = 500,
            maxTotalItems = 2_000,
        ),
    ),
    fetch = { request -> searchApi.load(request.query, request.cursor) },
    mapUnexpectedFailure = ::mapSearchError,
)

search.ensureLoaded(query)
search.loadNext(query)
search.refresh(query)
```

Paged persistence is not part of the current contract; paged entries are process-memory session
state with explicit finite bounds.

## Room-backed cache

Add Room persistence and a codec alongside the core artifact. Apply the Kotlin serialization plugin
when using `@Serializable` values:

```kotlin
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.voir:cache-core:1.0.0")
            implementation("dev.voir:cache-room:1.0.0")
            implementation("dev.voir:cache-codec-kotlinx:1.0.0")
        }
    }
}
```

Create one Room store in platform code and give it to the manager. The manager owns the store and
closes it when `manager.close()` is called:

```kotlin
// Android
@OptIn(ExperimentalCacheStoreApi::class)
val store = createAndroidRoomPersistentCacheStore(
    context = applicationContext,
    databaseName = "cache.db",
)

// JVM
@OptIn(ExperimentalCacheStoreApi::class)
val store = createJvmRoomPersistentCacheStore(databasePath)

// iOS; choose an absolute path in Application Support or Documents.
@OptIn(ExperimentalCacheStoreApi::class)
val store = createIosRoomPersistentCacheStore(absoluteDatabasePath)
```

Register a serializable resource in shared code with an explicit payload version:

```kotlin
import dev.voir.cache.codec.kotlinx.KotlinxJsonCacheCodec
import dev.voir.cache.core.createCacheManager
import dev.voir.cache.core.config.cacheSpec
import dev.voir.cache.core.key.CacheKeyEncoder
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.persistence.ExperimentalCacheStoreApi
import dev.voir.cache.core.policy.ExpireAfter
import dev.voir.cache.core.policy.MemoryPolicy
import dev.voir.cache.core.storage.persistent
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes

data class ProfileKey(
    val accountId: String,
)

@Serializable
data class Profile(
    val displayName: String,
)

data class ProfileError(
    val message: String,
)

@OptIn(ExperimentalCacheStoreApi::class)
val manager = createCacheManager(
    coroutineContext = applicationScope.coroutineContext,
    persistentStore = store,
)

val profiles = manager.resource(
    spec = cacheSpec<ProfileKey, Profile>(
        namespace = "profiles.v1",
        key = CacheKeyEncoder { key ->
            EncodedCacheKey(
                partition = CachePartition(key.accountId),
                value = key.accountId,
            )
        },
        freshness = ExpireAfter(30.minutes),
        storage = persistent(
            codec = KotlinxJsonCacheCodec(
                serializer = Profile.serializer(),
                currentVersion = 1,
            ),
        ),
        memory = MemoryPolicy(maxEntries = 100),
    ),
    fetch = { key: ProfileKey -> loadProfile(key) },
    mapUnexpectedFailure = { throwable ->
        ProfileError(throwable.message ?: "Unexpected source failure")
    },
)
```

The first `profiles.get(key)` checks memory, then Room, and finally the source if no fresh cached
value exists. After an application restart, a new manager using the same database can restore the
Room value before calling the source. Writes, updates, invalidation, removal, partition clearing,
and pruning are applied to both tiers.

Use a stable namespace and key encoding after release. When the serialized payload shape changes,
increment `currentVersion` and provide an explicit migration; see
[payload and Room migrations](docs/migrations.md). For full lifecycle and behavior details,
continue with the [five-minute setup guide](docs/getting-started.md) and
[guarantees and limitations](docs/guarantees-and-limitations.md).

## Documentation

- [Getting started and lifecycle](docs/getting-started.md)
- [Lifecycle ownership and restartable hosts](docs/lifecycle.md)
- [Failure, cancellation, security, and consistency guarantees](docs/guarantees-and-limitations.md)
- [Testing utilities](docs/testing.md)
- [Diagnostics and operation statistics](docs/diagnostics.md)
- [Payload and Room migrations](docs/migrations.md)
- [Compatibility matrix](docs/compatibility.md)
- [Versioning and publishing](docs/versioning-and-publishing.md)
- [Implementation plan](generic-kmp-cache-module-implementation-plan.md)

## Build

```shell
./gradlew build
```

Release verification also compares committed ABI dumps, publishes all platform artifacts to a local
Maven repository, and builds the external consumer samples. See the publishing guide for commands.

## License

Apache License 2.0. See [LICENSE](LICENSE).
