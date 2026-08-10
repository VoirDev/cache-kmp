package dev.voir.cache.core.paging

/** Source request for an initial page or the page following [cursor]. */
public data class PageLoadRequest<Q : Any, C : Any> public constructor(
    public val query: Q,
    public val cursor: C?,
)
