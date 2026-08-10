package dev.voir.cache.core.diagnostics

/** Sanitized reason for removing a retained cache entry. */
public enum class CacheEvictionCause {
    MEMORY_LRU,
    PERSISTENT_EXPIRY,
    PERSISTENT_NAMESPACE_BOUND,
    PERSISTENT_ROW_BOUND,
    PERSISTENT_BYTE_BOUND,
    CORRUPTION,
    EXPLICIT_REMOVAL,
}
