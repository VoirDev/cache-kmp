package dev.voir.cache.core.model

/**
 * Source failure committed as the current refresh outcome, with an optional usable stale value.
 *
 * @param V Non-null cached value type.
 * @param E Application-defined source error type.
 * @property error Source or mapped unexpected failure.
 * @property staleValue Last usable value, or `null` when the initial load failed.
 */
internal data class CacheFailure<V : Any, E : Any>(
    val error: E,
    val staleValue: V? = null,
) : CacheResult<V, E>
