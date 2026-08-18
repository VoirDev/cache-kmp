package dev.voir.cache

/** Source-loading behavior for one accumulated page request. */
public enum class CachePages {
    /** Reuses accumulated pages while they are fresh, otherwise loads the first page. */
    IF_NEEDED,

    /**
     * Always reloads the first page and discards every later page.
     *
     * Later pages are dropped deliberately. Their cursors describe the previous generation of the
     * source, so keeping them after the first page changes would duplicate or omit items.
     */
    REFRESH,

    /** Appends the page following the accumulated cursor. */
    NEXT,
}

/** Behavior when an incoming item repeats an identity already accumulated. */
public enum class CacheDuplicates {
    /** Keeps the accumulated item and its position. */
    KEEP_EXISTING,

    /** Replaces the accumulated item in place, keeping its position. */
    REPLACE_EXISTING,
}

/** Why an accumulated query cannot currently load another page. */
public enum class CachePageBoundary {
    /** The source returned no cursor after the last page. */
    SOURCE_END,

    /** The source returned a page with no items. */
    EMPTY_PAGE,

    /** The source repeated a cursor it had already returned. */
    REPEATED_CURSOR,

    /** The configured page bound for one query was reached. */
    MAX_PAGES,

    /** The configured item bound for one query was reached. */
    MAX_ITEMS_PER_QUERY,

    /** The configured item bound for the whole namespace was reached. */
    MAX_TOTAL_ITEMS,
}

/** Background work currently affecting an accumulated query. */
public enum class CachePageActivity {
    IDLE,
    LOADING,
    REFRESHING,
    APPENDING,
}

/**
 * One page returned by a paged loader.
 *
 * @param I Non-null item type.
 * @param C Cursor type understood by the source.
 * @property items Items in source order.
 * @property nextCursor Cursor for the following page, or `null` at the end of the source.
 * @property totalCount Optional total reported by the source.
 */
public data class CachePage<I : Any, C : Any> public constructor(
    public val items: List<I>,
    public val nextCursor: C? = null,
    public val totalCount: Int? = null,
) {
    init {
        require(totalCount == null || totalCount >= 0) {
            "Cache page totalCount must be null or non-negative."
        }
    }
}

/**
 * Immutable accumulated state for one paged query.
 *
 * Accumulated items survive a failed refresh or append so a user interface can keep rendering the
 * list it already has.
 *
 * @param I Non-null item type.
 * @param C Cursor type understood by the source.
 * @property items Deduplicated items accumulated across loaded pages.
 * @property nextCursor Cursor for the next page, or `null` when none is known.
 * @property totalCount Latest total reported by the source, when it reports one.
 * @property pageCount Pages currently accumulated.
 * @property freshness Whether accumulated pages may be reused without revalidation.
 * @property activity Background work currently affecting this query.
 * @property metadata Validation timestamps of the accumulated generation.
 * @property refreshError Latest first-page load failure, cleared when new work starts.
 * @property appendError Latest append failure, cleared when new work starts.
 * @property boundary Why no further page can be loaded, when accumulation stopped.
 */
public data class CachePagedState<I : Any, C : Any> public constructor(
    public val items: List<I> = emptyList(),
    public val nextCursor: C? = null,
    public val totalCount: Int? = null,
    public val pageCount: Int = 0,
    public val freshness: CacheFreshness = CacheFreshness.ABSENT,
    public val activity: CachePageActivity = CachePageActivity.IDLE,
    public val metadata: CacheEntryMetadata? = null,
    public val refreshError: Throwable? = null,
    public val appendError: Throwable? = null,
    public val boundary: CachePageBoundary? = null,
) {
    init {
        require(pageCount >= 0) { "Cache pageCount must not be negative." }
        require(totalCount == null || totalCount >= 0) {
            "Cache totalCount must be null or non-negative."
        }
    }

    /** Whether any page has been accumulated. */
    public val hasItems: Boolean
        get() = items.isNotEmpty()

    /** Whether a first-page load or an append is running. */
    public val isLoading: Boolean
        get() = activity != CachePageActivity.IDLE

    /** Whether accumulated pages require revalidation. */
    public val isStale: Boolean
        get() = freshness == CacheFreshness.STALE

    /** Whether another page can be requested right now. */
    public val canLoadNext: Boolean
        get() = nextCursor != null && boundary == null && activity == CachePageActivity.IDLE
}
