package dev.voir.cache.core.storage

/**
 * Stores values only in process memory.
 *
 * @param V Non-null cached value type.
 */
public class MemoryOnlyStorage<V : Any> public constructor() : CacheStorage<V>

