package dev.voir.cache.core.memory

import dev.voir.cache.core.model.CacheSnapshot

/**
 * Internal pairing of observable state and its access-ordered LRU sequence.
 *
 * @param V Non-null cached value type.
 * @param E Application-defined source error type.
 * @property snapshot Complete state exposed to observers.
 * @property lastAccessSequence Monotonic sequence of the most recent access.
 */
internal data class StoredCacheEntry<V : Any, E : Any>(
    val snapshot: CacheSnapshot<V, E>,
    val lastAccessSequence: Long,
)

