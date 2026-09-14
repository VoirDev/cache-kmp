package dev.voir.cache.core.model

/**
 * Result returned by a cache operation that may invoke the source loader.
 *
 * [CacheSuccess] means the returned value is committed cache state. [CacheFailure] means the
 * source failure was committed as the current refresh outcome. [CacheDiscarded] means a newer
 * mutation or clear won the revision race; its snapshot is authoritative and any rejected source
 * value is not exposed as cache state. Coroutine cancellation is thrown and is never represented
 * by this hierarchy.
 *
 * @param V Non-null cached value type.
 * @param E Application-defined source error type.
 */
internal sealed interface CacheResult<out V : Any, out E : Any>
