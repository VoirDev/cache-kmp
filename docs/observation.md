# Observing cache state

`observe` and `observeFile` connect cache changes to Kotlin `Flow` without moving loading policy
into the cache. Both APIs are cold: an address is observed only while its flow is collected.

## Value caches

Reusable scopes give observation the strongest type inference and keep identity in one place:

```kotlin
val profiles = cacheScope<ProfileKey, Profile>("profiles") {
    storage = CacheStorage.PERSISTENT
    expiresAfter = 30.minutes
    partitionBy { key -> key.accountId }
}

val states: Flow<CacheState<Profile>> = cache.observe(
    scope = profiles,
    key = ProfileKey(accountId),
)
```

Direct caches are also observable. Use the same stable options for `observe` and `cache`:

```kotlin
val options = CacheOptions(
    namespace = "profiles",
    partition = accountId,
    storage = CacheStorage.PERSISTENT,
    expiresAfter = 30.minutes,
)

val states: Flow<CacheState<Profile>> = cache.observe(
    key = ProfileKey(accountId),
    options = options,
)

val profile = cache.cache(ProfileKey(accountId), options) {
    api.loadProfile(accountId)
}
```

An observer receives source activity and the successful value from that `cache` call. Concurrent
loaders for the same address remain single-flight as usual.

## UI state

`CacheState` keeps an existing value available while it is stale, refreshing, or carrying a
transient refresh error. A long-lived UI owner can convert the cold flow to `StateFlow`:

```kotlin
val profileState: StateFlow<CacheState<Profile>> = cache
    .observe(profiles, ProfileKey(accountId))
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CacheState(),
    )
```

Typical rendering can use `state.value` as the data, `state.isLoading` for progress,
`state.isStale` for revalidation affordances, and `state.refreshError` for a non-destructive error.
Use `state.freshness` or `state.activity` when all states need distinct UI treatment.

Observation never calls the application loader. Trigger initial loading or refresh from the owner:

```kotlin
suspend fun loadProfile(forceRefresh: Boolean) {
    cache.cache(
        scope = profiles,
        key = ProfileKey(accountId),
        overrides = CacheOverrides(
            refresh = if (forceRefresh) CacheRefresh.ALWAYS else CacheRefresh.IF_NEEDED,
        ),
    ) {
        api.loadProfile(accountId)
    }
}
```

This separation prevents every new collector or UI recreation from unexpectedly starting network
work.

## Persistent hydration

Collection starts or joins lazy hydration for a persistent value. The flow emits current memory
state first, may emit `CacheActivity.HYDRATING`, and then emits restored data. Hydration reads only
cache-owned storage and never invokes the source loader.

Memory-only values skip hydration. File observation hydrates the durable file index in the same
way.

## File caches

`observeFile` has direct and scoped overloads and returns `Flow<CacheState<CachedFileInfo>>`:

```kotlin
val fileStates = cache.observeFile(
    scope = documents,
    key = DocumentKey(accountId, documentId),
)

fileStates.collect { state ->
    showDownloadState(
        available = state.hasValue,
        sizeBytes = state.value?.sizeBytes,
        mediaType = state.value?.mediaType,
        loading = state.isLoading,
    )
}
```

`CachedFileInfo` is non-leased metadata and intentionally has no path. When the content is needed,
call `cacheFile` to reuse or load it, keep the returned lease open during access, and close it:

```kotlin
val file = cache.cacheFile(documents, key) { sink ->
    api.downloadDocument(key.documentId, sink)
}
try {
    viewer.open(file.path)
} finally {
    file.close()
}
```

Returning a raw path from observation would be unsafe because a replacement, clear, or eviction
could remove that physical generation while another component still uses it.

## What produces emissions

Active collectors receive distinct state changes caused by:

- persistent value or file-index hydration;
- source refresh start, success, failure, or cancellation cleanup;
- successful `cache` or `cacheFile` calls for the same address;
- manager clearing of the observed address.

Observation is process-local and manager-local. It does not watch arbitrary database changes,
another `CacheManager`, or another process. An active collector pins its address against normal
in-memory LRU eviction until collection ends; avoid collecting an unbounded number of keys at once.
