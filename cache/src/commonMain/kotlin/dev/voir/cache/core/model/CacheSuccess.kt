package dev.voir.cache.core.model

/**
 * Successful cache operation containing a committed, authoritative [value].
 *
 * @param V Non-null cached value type.
 * @property value Value returned to the caller.
 * @property freshness Freshness of the returned value.
 */
internal data class CacheSuccess<V : Any>(
    val value: V,
    val freshness: CacheFreshness,
) : CacheResult<V, Nothing> {
    init {
        require(freshness != CacheFreshness.ABSENT) {
            "A successful cache result must be FRESH or STALE."
        }
    }
}
