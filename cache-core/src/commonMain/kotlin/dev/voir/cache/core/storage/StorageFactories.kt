package dev.voir.cache.core.storage

import dev.voir.cache.core.codec.CacheCodec

/** Default maximum encoded size for one persistent cache value. */
public const val DEFAULT_MAX_PAYLOAD_BYTES: Int = 1_048_576

/**
 * Creates memory-only storage for values of type [V].
 *
 * @param V Non-null cached value type.
 * @return Memory-only storage selection.
 */
public fun <V : Any> memoryOnly(): CacheStorage<V> = MemoryOnlyStorage()

/**
 * Creates persistent storage for values of type [V].
 *
 * @param V Non-null cached value type.
 * @param codec Explicit payload codec.
 * @param maxPayloadBytes Maximum encoded payload size accepted for one value.
 * @return Memory-plus-persistence storage selection.
 */
public fun <V : Any> persistent(
    codec: CacheCodec<V>,
    maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
): CacheStorage<V> = PersistentStorage(codec, maxPayloadBytes)

