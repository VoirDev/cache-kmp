package dev.voir.cache.core.paging

/** Terminal result of an initial, refresh, or append source-backed command. */
public sealed interface PagedLoadResult<I : Any, C : Any, E : Any> {
    public val snapshot: PagedCacheSnapshot<I, C, E>
}

/** Committed page state or a cache/terminal reuse that required no source work. */
public data class PagedLoadSuccess<I : Any, C : Any, E : Any> public constructor(
    override val snapshot: PagedCacheSnapshot<I, C, E>,
    public val sourceLoaded: Boolean,
    public val itemsAdded: Int,
) : PagedLoadResult<I, C, E> {
    init {
        require(itemsAdded >= 0) { "Paged itemsAdded must not be negative." }
    }
}

/** Typed source failure retaining the authoritative accumulated snapshot. */
public data class PagedLoadFailure<I : Any, C : Any, E : Any> public constructor(
    public val error: E,
    override val snapshot: PagedCacheSnapshot<I, C, E>,
) : PagedLoadResult<I, C, E>

/** Source result discarded because a newer query generation or mutation won. */
public data class PagedLoadDiscarded<I : Any, C : Any, E : Any> public constructor(
    override val snapshot: PagedCacheSnapshot<I, C, E>,
    public val sourceError: E? = null,
) : PagedLoadResult<I, C, E>
