package dev.voir.cache.core.memory

import dev.voir.cache.core.model.CacheSnapshot

/**
 * Atomic memory mutation result used to coordinate a matching persistent operation.
 *
 * @param V Non-null cached value type.
 * @param E Application-defined source error type.
 * @property revision Revision produced by the mutation.
 * @property snapshot Complete state committed to memory.
 */
internal data class MemoryCommit<V : Any, E : Any>(
    val revision: Long,
    val snapshot: CacheSnapshot<V, E>,
)
