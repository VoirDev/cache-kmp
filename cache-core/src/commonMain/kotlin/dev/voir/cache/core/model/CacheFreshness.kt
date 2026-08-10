package dev.voir.cache.core.model

/** Describes whether a cache entry exists and may be reused without source validation. */
public enum class CacheFreshness {
    /** No cached value or validation metadata exists. */
    ABSENT,

    /** A cached value exists and does not currently require validation. */
    FRESH,

    /** A cached value exists but requires validation before it is considered current. */
    STALE,
}

