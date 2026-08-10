# Guarantees and limitations

## State and consistency

- A value is either absent, fresh, or stale. Hydration and refresh activity are mutually exclusive.
- `observe` never invokes the source. Persistent observation may start or join lazy disk hydration;
  concurrent observers share that work. `peek` performs only a process-local memory read, while
  `current` awaits hydration without invoking the source.
- `get` loads only for absent or stale data; `refresh` always requests source work.
- Equal encoded addresses share one source flight. Unrelated addresses can load concurrently.
- A stale value remains observable during refresh and after a source failure.
- Mutations and clear operations advance revision guards. Results started before `put`, `update`,
  exact/family invalidation, exact/family removal, namespace clear, partition clear, or global
  clear cannot resurrect older state.
- Family scope is an exact namespace, partition, and normalized family tuple stored as metadata.
  It never depends on encoded-key prefixes or a consumer-maintained key registry.
- Paged initial/refresh and append requests are single-flight per query. Append commit requires both
  the current query generation and the expected cursor; refresh replaces accumulated pages atomically.
- Paged query, page, per-query item, and whole-resource item limits are finite. Query LRU evicts
  complete entries; item/page truncation is exposed through `boundary` and `isPartial`.
- Source success creates one validation metadata instance for memory and persistence. Persistence is
  hydration storage; memory is the live state for the current process.
- Memory publication occurs before the best-effort persistent write finishes. Process death during
  that interval can lose the newest cache copy; cache persistence is not an authoritative database.
- Persistent retention is host-triggered and uses the injected cache clock. Expiration grace,
  per-namespace rows, whole-store rows, and whole-store encoded-payload bytes are bounded in a
  deterministic most-recent-first order.
- `storageStatistics()` exposes only total and per-namespace occupancy. It never includes raw
  partitions, encoded keys, payloads, or backend exceptions.

## Failure behavior

- Typed source failures return `CacheFailure`; when available, its stale value remains usable.
- A source success or failure superseded by a newer revision returns `CacheDiscarded`. Only its
  `currentSnapshot` is authoritative; a rejected source value is never presented as committed.
- Unexpected source exceptions are converted only by the resource's `mapUnexpectedFailure` callback.
- Persistent read, write, invalidation, removal, clear, and prune failures emit a sanitized
  `PERSISTENT_STORE_FAILURE` diagnostic and leave the memory/source path usable.
- Prune reports and storage snapshots distinguish `AVAILABLE`, `NOT_CONFIGURED`, and `UNAVAILABLE`
  persistence without converting backend failures into application exceptions.
- Family mutation results distinguish memory affected entries, persistent affected rows, and
  `AVAILABLE`, `NOT_CONFIGURED`, or `UNAVAILABLE` persistence.
- Malformed, oversized, or unsupported persistent payloads are evicted and treated as cache misses.
- Diagnostic-listener failures are isolated from cache commands.
- Structured start/completion events have manager-local correlation IDs and monotonic sequence
  numbers. Delivery is serialized and never invokes consumer listener code under cache state locks.
- `CacheStatisticsSnapshot` is reduced independently of listener configuration and uses stable
  definitions for hits, misses, source outcomes, joins, evictions, and occupancy.
- Invalid configuration, invalid encoded addresses, duplicate namespaces, use after close, and a
  persistent spec without a manager store fail fast as programming errors.

## Cancellation and lifecycle

- `CancellationException` is never converted into a domain error.
- Cancelling one waiter does not cancel shared source work needed by other callers.
- Manager-owned work can outlive the last observer but cannot outlive `CacheManager.close()`.
- Close is idempotent and concurrent: it rejects new commands, cancels active hydration/source work,
  drains admitted commands, clears memory, closes persistence, and preserves the application parent
  job. Cancellation of the initiating caller cannot abandon this sequence.
- Persistent close failure is reported to every close caller after cleanup diagnostics are drained;
  it never reopens the terminal manager.
- `RestartableCacheManagerOwner` serializes suspending start/stop transitions and creates a fresh
  manager for every new host lifecycle.

## Security and privacy

- Keys and partitions are application-controlled and must never contain secrets.
- Diagnostics expose namespaces and a manager-salted one-way address fingerprint; raw keys,
  partitions, family values, payloads, and exception causes are excluded.
- Structured details likewise exclude loader values, typed error objects, caller identity, and
  credentials; only sanitized enums, durations, and aggregate counts are exposed.
- JSON and SQLite payloads are not encrypted by this library. Sensitive cached data requires
  platform storage protection or application-level authenticated encryption.
- Partition isolation depends on correct key encoding. Clear the prior identity's partition on
  logout and use distinct namespaces when serialization or semantic contracts change.
- Payload-size and address-length limits reduce accidental resource exhaustion but are not a trust
  boundary for hostile storage or source input.

## Limitations and non-goals

- This is a whole-value cache indexed by namespace, partition, and key. It does not provide field
  queries, joins, partial updates, conflict resolution, or authoritative offline records.
- Accumulated paged resources are currently memory-only and are not restored after manager/process
  recreation.
- Persistence durability is best effort and is not a transaction with the remote source.
- There is no hidden singleton, background scheduler, network policy, retry policy, or encryption.
- Room retention recency is approximate because hydration does not write an access timestamp back
  to disk. Counts and payload-byte statistics are exact; pruning order is deterministic for the
  stored metadata.
- One manager allows only one resource registration per namespace. Namespaces are durable schema
  identities and should not be reused for incompatible value meanings.
