package dev.voir.cache.core.storage

/**
 * Stores values only in process memory.
 *
 * @param V Non-null cached value type.
 */
internal class MemoryOnlyStorage<V : Any> : CacheStorage<V>
