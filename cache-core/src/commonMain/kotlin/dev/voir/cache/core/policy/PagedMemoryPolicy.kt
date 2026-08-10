package dev.voir.cache.core.policy

/** Finite process-memory bounds for one paged resource. */
public data class PagedMemoryPolicy public constructor(
    public val maxQueries: Int,
    public val maxPagesPerQuery: Int,
    public val maxItemsPerQuery: Int,
    public val maxTotalItems: Int,
) {
    init {
        require(maxQueries > 0) { "Paged maxQueries must be positive." }
        require(maxPagesPerQuery > 0) { "Paged maxPagesPerQuery must be positive." }
        require(maxItemsPerQuery > 0) { "Paged maxItemsPerQuery must be positive." }
        require(maxTotalItems > 0) { "Paged maxTotalItems must be positive." }
    }
}
