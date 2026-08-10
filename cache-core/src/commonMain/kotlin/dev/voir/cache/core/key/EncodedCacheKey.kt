package dev.voir.cache.core.key

/**
 * Storage-neutral identity of one value within a cache partition.
 *
 * @property partition Isolation boundary that owns the entry.
 * @property value Non-blank normalized identity containing every value-affecting input.
 * @property family Optional normalized family identity used by typed bulk operations.
 */
public data class EncodedCacheKey public constructor(
    public val partition: CachePartition,
    public val value: String,
    public val family: String? = null,
) {
    init {
        require(value.isNotBlank()) { "Encoded cache key must not be blank." }
        require(family == null || family.isNotBlank()) {
            "Encoded cache family must be null or non-blank."
        }
    }
}
