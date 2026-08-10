package dev.voir.cache.core.storage

import dev.voir.cache.core.codec.CacheCodec

/**
 * Stores values in memory and through the manager's configured persistent store.
 *
 * @param V Non-null cached value type.
 * @property codec Explicit codec used for persistent payloads.
 * @property maxPayloadBytes Maximum encoded payload size accepted for one value.
 */
public data class PersistentStorage<V : Any> public constructor(
    public val codec: CacheCodec<V>,
    public val maxPayloadBytes: Int,
) : CacheStorage<V> {
    init {
        require(codec.currentVersion > 0) { "Cache codec currentVersion must be greater than zero." }
        require(maxPayloadBytes > 0) { "Cache maxPayloadBytes must be greater than zero." }
    }
}

