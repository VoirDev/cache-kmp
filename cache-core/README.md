# cache-core

`cache-core` is the storage-neutral foundation of cache-kmp. It provides typed resources, bounded
in-memory caching, freshness policies, source single-flight, lifecycle management, diagnostics,
pagination, and the experimental persistence SPI. It depends only on Kotlin and kotlinx.coroutines.

```kotlin
commonMain.dependencies {
    implementation("dev.voir:cache-core:1.0.0")
}
```

Use this artifact by itself for process-memory caches. Add `cache-room` and a codec such as
`cache-codec-kotlinx` only when values must survive manager or process recreation.

## Public packages

```text
dev.voir.cache.core
├── root          # Cache manager and resource APIs
├── codec         # Versioned opaque-value codec contract
├── config        # Manager configuration and resource specifications
├── diagnostics   # Sanitized events and operation details
├── key           # Namespace, partition, key, and family encoding
├── model         # Cache snapshots and operation/source results
├── paging        # Accumulated-page models
├── persistence   # Experimental persistent-store SPI
├── policy        # Freshness, memory, and retention policies
├── storage       # Memory-only and persistent storage selection
└── time          # Injectable clock contract
```

Consuming domains own resource meaning, keys, policies, source loaders, and codecs. Cache contracts
do not depend on Room, serialization formats, dependency injection, networking, or logging.

Start with the repository [README](../README.md) and [getting-started guide](../docs/getting-started.md).
For precise failure, concurrency, persistence, and privacy behavior, see
[guarantees and limitations](../docs/guarantees-and-limitations.md).
