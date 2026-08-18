# Testing

Applications use the same public `cache-kmp` API in tests. There is no separate testing artifact or
public storage fake.

## Memory behavior

Create a manager below a temporary JVM directory and count loader calls:

```kotlin
@Test
fun `fresh value loads once`() = runTest {
    val root = createTempDirectory("cache-test-")
    val cache = CacheManager(root, this)
    try {
        var loads = 0
        val options = CacheOptions(namespace = "articles")

        val first = cache.cache("one", options) { ++loads }
        val second = cache.cache("one", options) { ++loads }

        assertEquals(1, first)
        assertEquals(first, second)
        assertEquals(1, loads)
    } finally {
        cache.close()
        root.toFile().deleteRecursively()
    }
}
```

Use `Duration.ZERO` for deterministic stale behavior without sleeps. `CacheRefresh.ALWAYS` is useful
when a test must force the loader independently of wall-clock timing.

## Persistent recreation

To verify persistence, close the first manager, create a second manager with the same root, and make
the second loader fail the test. A fresh stored value must be returned without invoking it. Use
`@Serializable` custom keys and values exactly as production code does.

## Files

Stream known chunks into `cacheFile`, read `CachedFile.path`, and always close the lease. Recreate the
manager with the same root to test durable file reuse. Configure small limits to cover oversized
entries and capacity behavior; assert `CacheFileException.reason` rather than internal store types.

## Failure and concurrency

Public contract tests should cover:

- stale fallback enabled and disabled;
- direct and scoped expiration overrides;
- forced refresh;
- concurrent equal-key calls invoking one loader;
- namespace configuration conflicts;
- accumulated paging: appended pages, deduplicated overlaps, and a refresh discarding later pages;
- `clearAll()` across memory, persistent values, accumulated pages, and files;
- manager and file-lease idempotent close behavior.

The repository's `samples/consumer` build is an external integration gate. It resolves the locally
published artifact and contains memory-scope, persistent-recreation, and file-recreation examples.
