# cache-core

`cache-core` contains storage-neutral contracts, the cache manager, source coordination, the
in-memory tier, and an experimental encoded persistence SPI. It depends only on Kotlin and
kotlinx.coroutines.

## Package layout

```text
dev.voir.cache.core
├── root          # Public manager/resource API and factory
├── codec         # Versioned opaque-value codec contract
├── config        # Manager limits and typed resource specifications
├── diagnostics   # Non-sensitive cache events
├── key           # Namespace, partition, and domain-key encoding
├── internal      # Manager lifecycle, source single-flight, and guarded coordination
├── memory        # Internal atomic state, scoped clearing, and bounded LRU
├── model         # Observable state and operation/source results
├── persistence   # Experimental opaque-record store and pruning SPI
├── policy        # Freshness and bounded-memory policies
├── storage       # Memory-only and persistent storage selection
└── time          # Injectable clock contract
```

Consuming domains own resource meaning, keys, policies, source loaders, and codecs. Cache contracts
must not reference Room, serialization formats, dependency injection, networking, logging, or an
application package.

Persistent payloads are opaque but are not encrypted by `cache-core`. Do not store credentials,
tokens, or other secrets unless the host supplies an appropriately encrypted codec or store
decorator.
