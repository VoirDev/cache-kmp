# Lifecycle ownership

Create one `CacheManager` for a long-lived application or session scope. The manager owns all cache
memory, Room databases, file storage, source coordination, recovery, and pruning behind the public
facade.

## Platform ownership

- Android accepts a `Context` and resolves storage from its application context.
- iOS uses the application's system caches directory.
- JVM accepts an absolute application-owned root and creates only a `cache-kmp` child below it.

Do not create a manager for each screen, request, or short-lived UI component. Manager recreation is
supported, but repeatedly reopening storage adds unnecessary work and discards memory caching.

## Shutdown

Call suspending `close()` when the owner has an explicit suspending shutdown boundary:

```kotlin
try {
    runApplication(cache)
} finally {
    cache.close()
}
```

Close is idempotent. It stops manager-owned work, clears process memory, and closes internal stores
without cancelling the `CoroutineScope` supplied by the application. New cache operations are not
valid after shutdown begins.

Close active `CachedFile` leases when their readers finish. Prefer closing leases before closing the
manager so deferred file cleanup can complete immediately.

Mobile process termination does not provide a reliable suspending cleanup callback. A
process-lifetime manager may simply be reclaimed by the operating system; durable cache recovery is
performed the next time a manager is created. Tests, logout-driven container replacement, and
controlled JVM hosts should call `close()` explicitly.

## Clearing

`clearAll()` clears memory values, persistent values, accumulated pages, and cached files owned by
the manager. It does not
close the manager, so later requests may cache again. Use it for logout or any boundary where all
cached identities must be discarded.

This version does not expose per-key, namespace, or partition invalidation. Use separate managers
when separate lifecycle and global-clear boundaries are required, `CacheRefresh.ALWAYS` to force one
address to reload, or a per-call `expiresAfter` override to make one address stale immediately.
