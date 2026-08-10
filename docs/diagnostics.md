# Diagnostics and operation statistics

`CacheEventListener` receives non-blocking, ordered diagnostics. Every event has a manager-local
monotonic `sequence`. Structured operations produce `OPERATION_STARTED` and `OPERATION_COMPLETED`
events sharing one opaque `correlationId`.

Completion details can include:

- operation kind, origin, and sanitized outcome;
- total, source, persistence, and codec duration;
- persistence operation kind;
- single-flight started/joined status and waiter count;
- eviction cause and affected count;
- typed family invalidation/removal affected count without the encoded family value;
- paged initial/refresh/append operations, joins, source duration, and discarded generations;
- current memory occupancy and configured namespace limit;
- persistent rows and encoded payload bytes after `storageStatistics()` samples them.

Delivery uses one manager-owned queue. Cache operations only offer immutable events to that queue;
listener latency and exceptions do not execute under cache state locks and cannot change command
results. `close()` drains accepted events in sequence before delivering `LIFECYCLE_CLOSE`. A
persistent-store close exception completes the structured `CLOSE` operation as `UNAVAILABLE`, emits
`PERSISTENT_STORE_FAILURE`, and is then rethrown to all close callers.

```kotlin
val manager = createCacheManager(
    coroutineContext = applicationScope.coroutineContext,
    eventListener = CacheEventListener { event ->
        diagnosticsSink.record(event)
    },
)

val statistics = manager.statistics()
println(statistics.hitRate)
println(statistics.sourceCommitted)
println(statistics.memory[CacheNamespace("profiles.v1")])
```

`CacheStatisticsSnapshot` is available even when no listener is configured. It uses one reducer for
memory/persistent hits, misses, fresh skips, stale and forced loads, source success/failure,
discarded results, single-flight joins, evictions, outcomes, and current/peak memory occupancy.
Persistent occupancy is copied into the operation snapshot when `storageStatistics()` completes.

## Privacy

Events may contain a public namespace and a manager-salted one-way address fingerprint. They never
contain raw partitions, raw encoded keys, raw family values, payloads, loader values, error objects, exceptions,
credentials, or caller identity. Durations and counts are low-cardinality operational data.
