package dev.voir.cache.core.paging

/** One source page and the cursor required to continue loading. */
public data class FetchedPage<I : Any, C : Any> public constructor(
    public val items: List<I>,
    public val nextCursor: C?,
    public val totalCount: Int? = null,
) {
    init {
        require(totalCount == null || totalCount >= 0) {
            "Paged source totalCount must be null or non-negative."
        }
    }
}
