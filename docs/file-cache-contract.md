# File-cache contract

The public file cache is intentionally small: `cacheFile`, `cacheFileScope`, `CacheFileOptions`,
`CacheFileOverrides`, `CacheFileSink`, and `CachedFile`.

## Streaming writes

The loader writes chunks to a cache-owned sink:

```kotlin
val file = cache.cacheFile(
    key = documentId,
    options = CacheFileOptions(
        namespace = "documents",
        expiresAfter = 1.days,
        maxEntryBytes = 25L * 1_024 * 1_024,
        maxEntries = 100,
        maxBytes = 250L * 1_024 * 1_024,
        mediaType = "application/pdf",
    ),
) { sink ->
    api.download(documentId) { chunk -> sink.write(chunk) }
}
```

The sink is valid only during that loader call. It exposes the number of bytes written but never a
staging path. A successful loader atomically promotes staged content; a failure removes staging and
does not replace an older committed generation.

## Leased reads

`cacheFile` returns a `CachedFile` lease:

```kotlin
try {
    viewer.open(file.path)
} finally {
    file.close()
}
```

The absolute path remains valid until `close()` completes. Active generations are excluded from
physical eviction. Callers must not retain or reopen the path after closing the lease.

## Freshness and source work

Fresh content is leased without invoking the loader. Stale or absent content invokes the loader;
equal addresses share one in-flight load. `CacheRefresh.ALWAYS` forces a new load. A loader failure
can fall back to an existing stale generation when `useStaleOnError` is enabled.

## Limits and failures

Actual streamed bytes enforce `maxEntryBytes`. Each namespace also enforces `maxEntries` and
`maxBytes`, while `CacheManagerOptions` supplies whole-manager caps. The cache evicts eligible old
content to satisfy a commit. If limits still cannot be satisfied, `CacheFileException.reason` is
`ENTRY_TOO_LARGE` or `CAPACITY_UNAVAILABLE`. Backend failure uses `STORAGE_UNAVAILABLE`; a losing
concurrent result uses `SUPERSEDED`.

## Storage and recovery

Content uses opaque library-generated filenames. Serialized application keys never become paths.
Room stores only the durable index and metadata. Manager creation recovers interrupted staging,
missing generations, and deferred cleanup before returning.

File caching does not download, authenticate, retry, decode, transform, encrypt, or interpret the
content. Those responsibilities stay with the application.
