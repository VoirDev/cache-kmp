package dev.voir.cache.core.model

/** Describes mutually exclusive background work currently affecting one cache entry. */
public enum class CacheActivity {
    /** No hydration or source refresh is active. */
    IDLE,

    /** Persistent state is being read into process memory. */
    HYDRATING,

    /** The application-provided source loader is running. */
    REFRESHING,
}

