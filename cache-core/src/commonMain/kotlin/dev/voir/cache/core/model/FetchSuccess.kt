package dev.voir.cache.core.model

/**
 * Successful source load containing a cacheable [value].
 *
 * @param V Non-null loaded value type.
 * @property value Value to validate and store.
 */
public data class FetchSuccess<V : Any> public constructor(
    public val value: V,
) : FetchResult<V, Nothing>

