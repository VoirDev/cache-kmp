package dev.voir.cache.core.key

/**
 * Converts a domain-owned key into its stable partition and normalized identity.
 *
 * @param K Domain key type containing every input that affects a resource value.
 */
internal fun interface CacheKeyEncoder<K : Any> {
    /**
     * Encodes [key] without exposing the domain type to cache internals.
     *
     * @param key Domain key to encode.
     * @return Stable partition and key representation.
     */
    fun encode(key: K): EncodedCacheKey
}
