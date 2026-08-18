# Changelog

All notable changes are documented here. The project follows Semantic Versioning and keeps the
format compatible with Keep a Changelog.

## [Unreleased]

Nothing yet.

## [1.0.0]

First release. `dev.voir:cache` is a single Kotlin Multiplatform artifact for Android, iOS, and the
JVM.

### Added

- A self-initializing `CacheManager` that owns memory, Room persistence, accumulated page state,
  file storage, recovery, and retention internally. Platform factories pick the storage location
  from an Android context, a JVM root directory, or the iOS caches directory.
- `cache` for read-through value caching, with direct `CacheOptions` calls and reusable typed
  `cacheScope` declarations.
- Per-call `CacheOverrides` for expiration, refresh mode, and stale-on-error fallback, so one
  request can deviate from its scope without redeclaring it.
- `cacheFile` for streamed file caching into cache-owned staging, returning a `CachedFile` lease
  whose path stays valid until it is closed. Per-namespace `maxEntryBytes`, `maxEntries`, and
  `maxBytes` limits, plus whole-manager caps through `CacheManagerOptions`.
- `cachePages` for accumulated cursor paging. Pages for one query accumulate into a single list
  deduplicated by a declared item identity; reloading the first page discards later pages whose
  cursors the source has superseded, which is what prevents duplicated or skipped items. Bounds are
  reported through `CachePagedState.boundary` rather than failing.
- `observe`, `observeFile`, and `observePages` cold flows exposing freshness, activity, validation
  metadata, and transient load failures without ever invoking a loader.
- Single-flight loading for equal addresses, stale-on-error fallback, and bounded memory retention
  for every cache kind.
- `clearAll()` across memory values, persistent values, accumulated pages, and cached files.
- Kotlinx serialization for every key, query, and cached value, with no codec, database, or storage
  adapter for consumers to configure.
- ABI validation, source and Dokka JAR publication, external consumer samples, and release checks.

### Notes

- Retention maintenance runs once at startup and is rate limited afterwards, in the background, so
  a cache request never waits for a whole-store scan.
- Room queries and filesystem work run on an IO dispatcher on Android and the JVM.
- Persisted values carry an internal payload format version, and both internal databases rebuild
  themselves rather than failing when a future release changes their schema.
- Accumulated pages are process memory only; cursors are not assumed valid in a later process.

[Unreleased]: https://github.com/VoirDev/cache-kmp/commits/main
[1.0.0]: https://github.com/VoirDev/cache-kmp/releases/tag/v1.0.0
