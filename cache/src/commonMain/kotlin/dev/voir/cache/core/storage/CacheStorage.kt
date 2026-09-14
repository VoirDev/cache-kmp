package dev.voir.cache.core.storage

/**
 * Selects the storage tiers used by a cached value.
 *
 * @param V Non-null cached value type.
 */
internal sealed interface CacheStorage<V : Any>

