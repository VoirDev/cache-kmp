# ADR-0001: Domain-owned cache usage with pluggable persistence

- Status: Accepted
- Date: 2026-08-10
- Work item: CACHE-01

## Context

The Sonars prototype placed generic in-memory mechanics in one module while its data module exposed
a second, structurally identical cache API. Repositories then coordinated memory restoration, Room
reads and writes, source loading, and failure recovery themselves. That duplicated contracts and
allowed the two storage tiers to diverge.

The reusable library must support domain-defined resource meaning without depending on domain
models, Room, serialization formats, networking, dependency injection, or logging frameworks.
Memory-only consumers must not receive persistence dependencies transitively.

## Decision

Application domain code may depend directly on `cache-core` and owns:

- typed resource keys and their stable encoding;
- namespaces and partition boundaries;
- freshness, memory, and storage policies;
- source loaders and application error mapping;
- codec selection for persistent values.

`cache-core` owns cache mechanics and defines only storage-neutral contracts. Persistence is exposed
through a narrow encoded-record SPI implemented by separate artifacts. `cache-room` will implement
that SPI without exposing Room entities, DAOs, or database types through the ordinary cache API.
`cache-codec-kotlinx` will provide an optional explicit JSON codec without making serialization a
core dependency.

The composition root constructs and closes the cache manager and supplies an optional persistent
store. Domain code receives the manager and creates resources directly; no application-specific
cache facade duplicates core contracts.

## Consequences

- `cache-core` depends only on Kotlin and coroutines.
- Memory-only consumers do not depend on Room, SQLite, KSP, or a serialization format.
- Application keys remain domain types and are converted by `CacheKeyEncoder`.
- Persistent payloads are opaque to the store and evolve through explicit codec versions.
- The engine becomes the single coordinator of hydration, source loading, metadata, and mutations.
- Applications needing relational queries or durable transactional state must use a repository and
  application database rather than this cache.
- Existing Sonars data-layer cache facades are migration inputs, not compatibility commitments.

