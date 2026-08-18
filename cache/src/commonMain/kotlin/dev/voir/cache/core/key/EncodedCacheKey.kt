package dev.voir.cache.core.key

/**
 * Storage-neutral identity of one value within a cache partition.
 *
 * @property partition Isolation boundary that owns the entry.
 * @property value Non-blank normalized identity containing every value-affecting input.
 */
internal data class EncodedCacheKey(
    val partition: CachePartition,
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Encoded cache key must not be blank." }
    }
}
