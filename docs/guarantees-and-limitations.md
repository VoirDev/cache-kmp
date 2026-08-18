# Guarantees and limitations

## Values and freshness

- A request addresses exactly one serialized namespace, partition, and key.
- `CacheRefresh.IF_NEEDED` returns a fresh value without invoking the loader.
- `CacheRefresh.ALWAYS` invokes the loader even when a fresh value exists.
- The effective expiration of each request is committed with its successful value or file.
- Equal addresses share one in-flight loader. Different addresses may load concurrently.
- A namespace has one key type, value type or file kind, storage mode, and set of stable limits
  within a manager. Incompatible reuse fails immediately.

## Accumulated pages

- Pages accumulated for one query are one logical value with one freshness.
- Reloading the first page replaces the accumulation, because later cursors describe a window the
  source has since changed.
- Appended pages merge by the declared item identity, so overlapping pages cannot duplicate items.
- Accumulation stops at `maxPages`, `maxItemsPerQuery`, `maxQueries`, or `maxTotalItems` and reports
  which bound applied.
- Accumulated pages are process memory only and are never restored from disk.

## Failures and cancellation

- Loader exceptions are rethrown without being wrapped.
- When a stale entry exists and `useStaleOnError` is enabled, a loader failure returns that entry.
- Disabling stale fallback makes the loader failure observable to the caller.
- Coroutine cancellation remains cancellation and is not converted into a cached result.
- File capacity and storage failures use `CacheFileException` with a stable
  `CacheFileFailureReason`.
- A failed or superseded loader never commits its candidate as the winning cache entry.

## Storage

- Accumulated pages are bounded per namespace by `maxQueries` and `maxTotalItems`.
- Memory storage is bounded per namespace by `maxMemoryEntries`, including the per-address
  bookkeeping the manager keeps for evicted entries.
- An address may exceed the bound only while an observer or an in-flight load holds it.
- Persistent values are Kotlinx-serialized JSON with a per-entry encoded payload limit.
- Persistent values and file metadata are stored internally with Room.
- Files are streamed to filesystem staging and are not stored as database BLOBs.
- File commits enforce per-entry, per-namespace entry, and per-namespace byte limits.
- Whole-manager persistent and file caps are configured with `CacheManagerOptions`.
- Startup recovery and pruning are internal and complete before manager creation returns.
- Retention maintenance afterwards is rate limited and runs in the background, so it never delays
  a cache request or repeats a whole-store scan on every call.
- Stored values and files are cache data, not authoritative application records. A process or
  storage failure may discard them.

## Observation

- `observe` supports direct and scoped memory or persistent values.
- `observeFile` supports direct and scoped file caches and emits non-leased file metadata.
- Observation emits current process-local state and never invokes an application loader.
- Persistent collection starts or joins lazy disk hydration.
- Cache operations, refresh activity, refresh failures, and clearing publish state changes to active
  collectors for the same manager and address.
- Observation does not monitor another manager, another process, or arbitrary database writes.
- An active collector pins its address against normal in-memory LRU eviction until collection ends.

## File leases

- `CachedFile.path` is absolute and valid while its lease is open.
- Active file generations are protected from eviction until `CachedFile.close()` completes.
- Closing a lease is idempotent.
- Capacity can reject a new file when active leases prevent sufficient eviction.
- Media type is informational and must not be treated as trusted content validation.

## Security and privacy

- Serialized keys, values, SQLite data, and file content are not encrypted by this library.
- Use platform storage protection or application-level encryption for sensitive cached content.
- Do not put credentials or secrets in keys, namespaces, or partitions.
- Partition isolation depends on the application assigning the correct partition.
- `clearAll()` is the only public invalidation boundary and emits absent state to active
  observers, including paged observers.

## Non-goals

The library does not provide networking, retries, HTTP cache-header interpretation, background
scheduling, authoritative offline records, database queries, image decoding, file transformation,
resumable downloads, content deduplication, offset-based or bidirectional paging, durable paged
state, or encryption.
