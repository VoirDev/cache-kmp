package dev.voir.cache.core.key

/**
 * Converts a domain-owned family key into stable partitioned storage metadata.
 *
 * @param F Domain-owned family key type.
 */
public fun interface CacheFamilyEncoder<F : Any> {
    /** Encodes [family] without exposing its domain type to cache internals. */
    public fun encode(family: F): EncodedCacheFamily
}
