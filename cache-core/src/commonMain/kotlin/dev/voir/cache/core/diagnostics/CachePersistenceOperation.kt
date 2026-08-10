package dev.voir.cache.core.diagnostics

/** Sanitized persistent-backend operation kind. */
public enum class CachePersistenceOperation {
    READ,
    WRITE,
    INVALIDATE,
    INVALIDATE_FAMILY,
    REMOVE,
    REMOVE_FAMILY,
    CLEAR_NAMESPACE,
    CLEAR_PARTITION,
    CLEAR_ALL,
    PRUNE,
    STATISTICS,
    MIGRATE,
    CLOSE,
}
