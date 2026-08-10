# CACHE-00 behavior baseline

Status: frozen on 2026-08-10 from the Sonars `:cache` prototype and its `:data` integration tests.

This document preserves behavior, not the prototype's layering or names. Every retained item is
application-independent and must be represented by executable tests as the corresponding new
engine capability is implemented. Sonars-specific record types remain reference fixtures only.

## Core policy and state

Source: `cache/src/commonTest/kotlin/dev/voir/cache/model`.

- [x] A finite TTL calculates expiration from the validation instant.
- [x] A non-expiring freshness policy has no expiration instant.
- [x] Negative and infinite TTL values are rejected.
- [x] An entry becomes stale exactly at its expiration boundary.
- [x] Explicit invalidation makes non-expiring data stale.
- [x] Blank identities and expiration before validation are rejected.
- [x] Freshness can be tested deterministically with an injected clock.
- [x] An absent snapshot contains no value, validation metadata, activity, or error.
- [x] Refreshing without a cached value represents initial loading.
- [x] Existing data remains renderable while refresh runs.
- [x] A refresh error with data is non-blocking; an error without data is an initial-load error.
- [x] An absent value cannot be marked fresh.

The prototype represented absence as `STALE`. The new contract deliberately introduces `ABSENT`;
the observable meaning is retained while the enum value changes in CACHE-01.

## In-memory state transitions

Source: `cache/src/commonTest/kotlin/dev/voir/cache/store/InMemoryResourceStoreTest.kt`.

- [x] Observing an unknown key immediately emits an absent snapshot without external work.
- [x] Refresh start and success publish complete atomic snapshots.
- [x] TTL expiration is evaluated lazily on access and published to observers.
- [x] Hydration preserves original validation metadata and evaluates current freshness.
- [x] A delayed persistent read cannot replace newer in-memory data.
- [x] A forced refresh may run while data is fresh and clears an obsolete transient error.
- [x] Refresh failure retains the last value and validation instant.
- [x] First-load failure does not invent a value.
- [x] Invalidation retains data and marks it stale.
- [x] Authoritative update changes only an existing value and preserves validation metadata.
- [x] Remove and clear publish absent state to existing observers.
- [x] Concurrent writes to different keys do not lose entries.
- [ ] Test stores can record transition ordering deterministically.

## Loading and lifecycle

Sources:

- `cache/src/commonTest/kotlin/dev/voir/cache/concurrent/SingleFlightTest.kt`
- `cache/src/commonTest/kotlin/dev/voir/cache/controller/CachedResourceControllerTest.kt`

- [x] Equivalent addresses join one shared execution and receive the same result.
- [x] Different addresses execute independently.
- [x] Cancelling one waiter does not cancel manager-owned shared work.
- [x] Completed work is removed so the address can load again.
- [x] Closing the owner cancels shared work and clears flight bookkeeping.
- [x] A miss starts one request and stores success.
- [x] A fresh hit skips the source.
- [x] Stale data remains visible while revalidation is suspended.
- [x] Forced refresh ignores freshness.
- [x] Failed revalidation preserves data, validation metadata, and stale freshness.
- [x] Explicitly invalidated data is revalidated before TTL expiration.
- [x] Concurrent `get` calls for one address join one request.
- [x] Unexpected loader exceptions use the consumer-provided error mapping.
- [x] Cancellation is never converted to an application failure.

The new API binds one loader to a resource. The prototype's per-call loader behavior is not retained.

## Persistence behavior to generalize

Source: `data/src/jvmTest/kotlin/dev/voir/sonars/data/cache/room/PersistentCacheDatabaseTest.kt`.

- [ ] Values and metadata round-trip together.
- [ ] Authoritative empty values survive persistence.
- [ ] Partition replacement and clearing cannot cross user boundaries.
- [ ] Exact-key update and removal do not affect sibling entries.
- [ ] Invalidation metadata round-trips with the value.

The original provider, account, and statistics entities are not retained. CACHE-05 must run these
behaviors against the generic opaque-record Room store.

## Characterization gaps

- [x] `CancellationCleanupCharacterizationTest.cancelledRefreshClearsActivityState` — CACHE-03.
- [x] `LateResultSuppressionCharacterizationTest.lateSourceSuccessCannotRestoreRemovedEntry` — CACHE-03.
- [x] `BoundedMemoryCharacterizationTest.leastRecentlyUsedUnpinnedEntryIsEvictedAtLimit` — CACHE-02.

All three characterization tests are enabled and passing.
