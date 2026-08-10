package dev.voir.cache.core.paging

/** Why an accumulated query cannot currently load another page. */
public enum class PagedCacheBoundary {
    SOURCE_END,
    EMPTY_PAGE,
    REPEATED_CURSOR,
    MAX_PAGES,
    MAX_ITEMS_PER_QUERY,
    MAX_TOTAL_ITEMS,
}
