package dev.voir.cache.core.paging

/** Current manager-owned work for one accumulated query entry. */
public enum class PagedCacheActivity {
    IDLE,
    HYDRATING,
    INITIAL_LOADING,
    REFRESHING,
    APPENDING,
}
