package dev.voir.cache

import kotlin.time.Instant

/** Describes whether an observed cache entry exists and can be reused without revalidation. */
public enum class CacheFreshness {
    /** No cached value exists. */
    ABSENT,

    /** A cached value exists and can be reused without source validation. */
    FRESH,

    /** A cached value exists but requires source validation before it is current. */
    STALE,
}

/** Describes background work currently affecting an observed cache entry. */
public enum class CacheActivity {
    /** No persistent hydration or source refresh is running. */
    IDLE,

    /** Persistent state is being restored into process memory. */
    HYDRATING,

    /** An application-provided source loader is running. */
    REFRESHING,
}

/** Validation timestamps attached to an observed cached value or file. */
public data class CacheEntryMetadata public constructor(
    public val validatedAt: Instant,
    public val expiresAt: Instant?,
) {
    init {
        require(expiresAt == null || expiresAt >= validatedAt) {
            "Cache expiration cannot be earlier than validation."
        }
    }
}

/**
 * Immutable observable state for one cache entry.
 *
 * Observation never invokes a source loader. Existing data remains in [value] while a refresh is
 * active or after it fails, allowing user interfaces to keep rendering stale data.
 */
public data class CacheState<V : Any> public constructor(
    public val value: V? = null,
    public val freshness: CacheFreshness = CacheFreshness.ABSENT,
    public val activity: CacheActivity = CacheActivity.IDLE,
    public val metadata: CacheEntryMetadata? = null,
    public val refreshError: Throwable? = null,
) {
    init {
        if (value == null) {
            require(freshness == CacheFreshness.ABSENT) {
                "A cache state without a value must have ABSENT freshness."
            }
            require(metadata == null) {
                "A cache state without a value must not have metadata."
            }
        } else {
            require(freshness != CacheFreshness.ABSENT) {
                "A cache state with a value must have FRESH or STALE freshness."
            }
            require(metadata != null) {
                "A cache state with a value must have validation metadata."
            }
        }
    }

    /** Whether this state currently contains cached data. */
    public val hasValue: Boolean
        get() = value != null

    /** Whether persistent hydration or source refresh is currently running. */
    public val isLoading: Boolean
        get() = activity != CacheActivity.IDLE

    /** Whether cached data exists but requires source validation. */
    public val isStale: Boolean
        get() = freshness == CacheFreshness.STALE
}

/** Safe, non-leased metadata emitted while observing a file-cache entry. */
public data class CachedFileInfo public constructor(
    public val sizeBytes: Long,
    public val mediaType: String?,
) {
    init {
        require(sizeBytes >= 0) { "Cached file sizeBytes must not be negative." }
        require(mediaType == null || mediaType.isNotBlank()) {
            "Cached file mediaType must be null or non-blank."
        }
        require(mediaType == null || mediaType.length <= 255) {
            "Cached file mediaType must not exceed 255 characters."
        }
    }
}
