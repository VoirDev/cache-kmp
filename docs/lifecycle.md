# Lifecycle ownership

`CacheManager` owns its supervisor job, registered resources, memory state, diagnostic delivery, and
optional persistent store. Its lifecycle is terminal: create a new manager instead of trying to
reopen one that has begun closing.

## Process-lifetime mobile owner

For the usual mobile application model, create one manager from an application-owned coroutine
context and keep it for the process lifetime. Do not tie it to an Activity, ViewController,
navigation graph, or another short-lived UI scope.

An operating-system process kill does not provide a reliable suspending cleanup callback. When the
application does have an explicit suspending shutdown boundary (tests, logout-driven container
replacement, or a controlled host), call `manager.close()` before cancelling the coroutine context
that owns it.

## Restartable host

`RestartableCacheManagerOwner` serializes host lifecycle transitions without blocking a platform
thread:

```kotlin
val cacheOwner = RestartableCacheManagerOwner {
    createCacheManager(
        coroutineContext = hostScope.coroutineContext,
        persistentStore = createPersistentStore(),
    )
}

suspend fun startHost(): CacheManager = cacheOwner.start()

suspend fun stopHost() {
    cacheOwner.stop()
    // Cancel hostScope only after stop() returns.
}
```

Concurrent `start()` calls share one active manager. `stop()` detaches and closes it completely;
the next `start()` invokes the factory for a fresh manager and persistent-store instance. Concurrent
start/stop transitions are serialized. `close()` on the owner is an alias for `stop()`.

The factory must never return an earlier manager or persistent-store instance after it was closed.

## Shutdown contract

Once the first `CacheManager.close()` begins:

- new resource registration and commands fail with `IllegalStateException`;
- manager-owned hydration and source work is cancelled;
- commands admitted before shutdown are allowed to release their lifecycle admission, including an
  in-progress persistent write;
- memory is cleared, then the persistent store is closed;
- queued diagnostics are delivered before terminal `LIFECYCLE_CLOSE` delivery completes;
- concurrent and later `close()` calls observe the same completion result;
- cancellation of the initiating caller cannot abandon cleanup;
- a persistent close failure is emitted as `PERSISTENT_STORE_FAILURE`, is rethrown to every close
  caller, and does not make the terminal manager reusable.

Close never cancels the host parent job supplied to `createCacheManager`.

## Koin and synchronous disposal hooks

A synchronous Koin `onClose` callback cannot prove completion of a suspending cache shutdown. Do
not bridge it with `runBlocking` on a UI thread, `GlobalScope`, or an unowned fire-and-forget launch.

For a restartable Koin container, keep `RestartableCacheManagerOwner` at the host boundary and make
container shutdown itself suspending: call `cacheOwner.stop()`, await it, and only then stop Koin or
cancel the owning scope. If the application has no suspending shutdown boundary, use a
process-lifetime manager rather than pretending synchronous disposal completed cleanup.
