package dev.voir.cache.core.diagnostics

/** Known origin or reason for one cache operation. */
public enum class CacheOperationOrigin {
    MEMORY,
    HYDRATION,
    MISS_LOAD,
    STALE_LOAD,
    FORCED_REFRESH,
    JOINED_WORK,
    EXPLICIT,
    MAINTENANCE,
    LIFECYCLE,
}
