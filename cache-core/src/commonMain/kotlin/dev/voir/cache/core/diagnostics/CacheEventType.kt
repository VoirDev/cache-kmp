package dev.voir.cache.core.diagnostics

/** Non-sensitive categories emitted by cache diagnostics. */
public enum class CacheEventType {
    /** A correlated structured cache operation started. */
    OPERATION_STARTED,

    /** A correlated structured cache operation completed. */
    OPERATION_COMPLETED,

    /** A value was served from process memory. */
    MEMORY_HIT,

    /** A value was hydrated from persistent storage. */
    PERSISTENT_HIT,

    /** No usable value existed in either configured tier. */
    MISS,

    /** Source loading was skipped because the value was fresh. */
    FRESH_SKIP,

    /** A stale value triggered source refresh. */
    STALE_REFRESH,

    /** A caller explicitly forced source refresh. */
    FORCED_REFRESH,

    /** A caller joined source work already running for the address. */
    SINGLE_FLIGHT_JOIN,

    /** An entry was removed to enforce a retention policy. */
    EVICTION,

    /** An entry was explicitly marked stale. */
    INVALIDATION,

    /** A malformed or unsupported persistent value was removed. */
    CORRUPTION_EVICTION,

    /** A persistent-store operation failed while memory remained usable. */
    PERSISTENT_STORE_FAILURE,

    /** An older asynchronous result was rejected by a revision guard. */
    DISCARDED_LATE_RESULT,

    /** The cache lifecycle owner was closed. */
    LIFECYCLE_CLOSE,
}
