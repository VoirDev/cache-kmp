package dev.voir.cache.core.diagnostics

/** Sanitized terminal classification of a structured cache operation. */
public enum class CacheOperationOutcome {
    FRESH_SKIP,
    COMMITTED,
    SOURCE_FAILURE,
    PERSISTENCE_DEGRADED_SUCCESS,
    DISCARDED,
    EVICTED,
    CANCELLED,
    REJECTED_AFTER_CLOSE,
    ABSENT,
    NOT_CONFIGURED,
    UNAVAILABLE,
}
