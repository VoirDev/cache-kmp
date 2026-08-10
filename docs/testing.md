# Testing

Add the test utilities beside your normal test dependencies:

```kotlin
commonTest.dependencies {
    implementation("dev.voir:cache-testing:1.0.0")
}
```

`MutableCacheClock` makes TTL boundaries deterministic:

```kotlin
val clock = MutableCacheClock(Instant.parse("2026-01-01T00:00:00Z"))
val manager = createCacheManager(backgroundScope.coroutineContext, clock = clock)

clock.advanceBy(5.minutes)
assertEquals(CacheFreshness.STALE, resource.current(key).freshness)
```

`InMemoryPersistentCacheStore` exercises the same encoded SPI as Room without platform I/O. It
supports seeded records, inspection, operation counters, all retention bounds, exact statistics,
typed family invalidation/removal, and deterministic failure flags:

```kotlin
@OptIn(ExperimentalCacheStoreApi::class)
@Test
fun `disk failure falls back to source`() = runTest {
    val store = InMemoryPersistentCacheStore().apply { failReads = true }
    val manager = createCacheManager(backgroundScope.coroutineContext, store)

    val result = resourceRegisteredOn(manager).get(key)

    assertIs<CacheSuccess<Value>>(result)
    manager.close()
}
```

Use Room integration tests for reopen behavior, schema migration, driver configuration, and exact
metadata round trips. Use the fake store for race and failure tests. Test cancellation explicitly;
never swallow `CancellationException` in a source or error mapper.

Revision-race tests should assert `CacheDiscarded` directly and verify its `currentSnapshot` instead
of checking observable state alone. This prevents a rejected source value from regressing to an
ordinary `CacheSuccess` while the revision guard still happens to protect memory.

Family contract tests should cover empty, one-entry, and multi-entry families; namespace and
partition isolation; persistent reopen; and a late matching source result alongside an unrelated
result that still commits.

`ControllablePageSource` scripts typed page results and can pause exactly the next request. Use it
to test append/refresh generation races and waiter cancellation without sleeps. Paged contracts
should cover fresh reuse, ordered append, duplicate IDs, retryable append failure, empty/repeated
cursor termination, finite bounds, whole-query LRU, and family/partition suppression.

Diagnostics tests should match operation pairs by `correlationId`, verify increasing `sequence`,
and assert that event string representations do not contain fixture keys, partitions, payloads, or
errors. `manager.statistics()` can be asserted without installing a listener.
