package dev.voir.cache.core.paging

import dev.voir.cache.core.model.CacheFreshness
import dev.voir.cache.core.model.CacheMetadata

/** Complete accumulated state for one query. */
public data class PagedCacheSnapshot<I : Any, C : Any, E : Any> public constructor(
    public val items: List<I> = emptyList(),
    public val nextCursor: C? = null,
    public val totalCount: Int? = null,
    public val pageCount: Int = 0,
    public val freshness: CacheFreshness = CacheFreshness.ABSENT,
    public val activity: PagedCacheActivity = PagedCacheActivity.IDLE,
    public val metadata: CacheMetadata? = null,
    public val initialError: E? = null,
    public val refreshError: E? = null,
    public val appendError: E? = null,
    public val boundary: PagedCacheBoundary? = null,
) {
    init {
        require(pageCount >= 0) { "Paged pageCount must not be negative." }
        require(totalCount == null || totalCount >= 0) {
            "Paged totalCount must be null or non-negative."
        }
    }

    /** Whether another page may be requested under the current cursor and retention state. */
    public val canLoadNext: Boolean
        get() = nextCursor != null && boundary == null && activity == PagedCacheActivity.IDLE

    /** Whether finite retention stopped accumulation before the source reached its end. */
    public val isPartial: Boolean
        get() = boundary == PagedCacheBoundary.MAX_PAGES ||
            boundary == PagedCacheBoundary.MAX_ITEMS_PER_QUERY ||
            boundary == PagedCacheBoundary.MAX_TOTAL_ITEMS
}
