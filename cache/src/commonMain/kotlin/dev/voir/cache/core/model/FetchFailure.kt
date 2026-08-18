package dev.voir.cache.core.model

/**
 * Expected source failure represented by an application-defined [error].
 *
 * @param E Application-defined source error type.
 * @property error Failure to expose without deleting usable cached data.
 */
internal data class FetchFailure<E : Any>(
    val error: E,
) : FetchResult<Nothing, E>

