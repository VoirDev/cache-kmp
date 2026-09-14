package dev.voir.cache.core.paging

import dev.voir.cache.core.model.CacheFreshness
import dev.voir.cache.core.model.CacheMetadata

/** Deterministic handling for an item identity already present in accumulated state. */
internal enum class PagedDuplicatePolicy {
    KEEP_EXISTING,
    REPLACE_EXISTING,
}

/** Mutually exclusive work currently affecting one accumulated query. */
internal enum class PagedCacheActivity {
    IDLE,
    LOADING,
    REFRESHING,
    APPENDING,
}

/** Why an accumulated query cannot currently load another page. */
internal enum class PagedCacheBoundary {
    /** The source reported no cursor after the last page. */
    SOURCE_END,

    /** The source returned a page with no items. */
    EMPTY_PAGE,

    /** The source repeated a cursor it had already returned. */
    REPEATED_CURSOR,

    /** The configured page bound for one query was reached. */
    MAX_PAGES,

    /** The configured item bound for one query was reached. */
    MAX_ITEMS_PER_QUERY,

    /** The configured item bound for the whole resource was reached. */
    MAX_TOTAL_ITEMS,
}

/** One source page and the cursor required to continue loading. */
internal data class FetchedPage<I : Any, C : Any>(
    val items: List<I>,
    val nextCursor: C?,
    val totalCount: Int? = null,
) {
    init {
        require(totalCount == null || totalCount >= 0) {
            "Paged source totalCount must be null or non-negative."
        }
    }
}

/** Complete accumulated state for one query. */
internal data class PagedCacheSnapshot<I : Any, C : Any, E : Any>(
    val items: List<I> = emptyList(),
    val nextCursor: C? = null,
    val totalCount: Int? = null,
    val pageCount: Int = 0,
    val freshness: CacheFreshness = CacheFreshness.ABSENT,
    val activity: PagedCacheActivity = PagedCacheActivity.IDLE,
    val metadata: CacheMetadata? = null,
    val refreshError: E? = null,
    val appendError: E? = null,
    val boundary: PagedCacheBoundary? = null,
) {
    init {
        require(pageCount >= 0) { "Paged pageCount must not be negative." }
        require(totalCount == null || totalCount >= 0) {
            "Paged totalCount must be null or non-negative."
        }
        if (pageCount == 0) {
            require(freshness == CacheFreshness.ABSENT) {
                "A paged snapshot without pages must have ABSENT freshness."
            }
            require(metadata == null) {
                "A paged snapshot without pages must not have validation metadata."
            }
        } else {
            require(freshness != CacheFreshness.ABSENT) {
                "A paged snapshot with pages must be FRESH or STALE."
            }
            require(metadata != null) {
                "A paged snapshot with pages must have validation metadata."
            }
        }
    }

    /** Whether another page may be requested under the current cursor and retention state. */
    val canLoadNext: Boolean
        get() = nextCursor != null && boundary == null && activity == PagedCacheActivity.IDLE
}

/** Revision-guarded start of one paged command. */
internal data class PagedWorkStart<I : Any, C : Any, E : Any>(
    val revision: Long,
    val snapshot: PagedCacheSnapshot<I, C, E>,
    val cursor: C?,
)
