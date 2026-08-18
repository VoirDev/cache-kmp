package dev.voir.cache.core.policy

/** Finite process-memory bounds for one paged resource. */
internal data class PagedMemoryPolicy(
    val maxQueries: Int,
    val maxPages: Int,
    val maxItemsPerQuery: Int,
    val maxTotalItems: Int,
) {
    init {
        require(maxQueries > 0) { "Paged maxQueries must be positive." }
        require(maxPages > 0) { "Paged maxPages must be positive." }
        require(maxItemsPerQuery > 0) { "Paged maxItemsPerQuery must be positive." }
        require(maxTotalItems > 0) { "Paged maxTotalItems must be positive." }
        require(maxItemsPerQuery <= maxTotalItems) {
            "Paged maxItemsPerQuery must not exceed maxTotalItems."
        }
    }
}
