package dev.voir.cache.core.model

/**
 * Result returned by an application-provided source loader.
 *
 * @param V Non-null loaded value type.
 * @param E Application-defined source error type.
 */
public sealed interface FetchResult<out V : Any, out E : Any>

