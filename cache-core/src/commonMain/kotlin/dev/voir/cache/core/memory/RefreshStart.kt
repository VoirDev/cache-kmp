package dev.voir.cache.core.memory

/**
 * Captures the state against which one source refresh is allowed to commit.
 *
 * @param V Non-null cached value type.
 * @property revision Address revision captured before the loader starts.
 * @property staleValue Previously cached value retained during the refresh, when present.
 */
internal data class RefreshStart<V : Any>(
    val revision: Long,
    val staleValue: V?,
)
