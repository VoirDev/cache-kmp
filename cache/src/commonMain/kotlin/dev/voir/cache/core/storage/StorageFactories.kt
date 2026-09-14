package dev.voir.cache.core.storage

import dev.voir.cache.core.codec.CacheCodec

/**
 * Creates memory-only storage for values of type [V].
 *
 * @param V Non-null cached value type.
 * @return Memory-only storage selection.
 */
internal fun <V : Any> memoryOnly(): CacheStorage<V> = MemoryOnlyStorage()

/**
 * Creates persistent storage for values of type [V].
 *
 * @param V Non-null cached value type.
 * @param codec Explicit payload codec.
 * @param maxPayloadBytes Maximum encoded payload size accepted for one value.
 * @return Memory-plus-persistence storage selection.
 */
internal fun <V : Any> persistent(
    codec: CacheCodec<V>,
    maxPayloadBytes: Int,
): CacheStorage<V> = PersistentStorage(codec, maxPayloadBytes)
