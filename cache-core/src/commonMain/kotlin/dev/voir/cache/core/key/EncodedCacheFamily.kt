package dev.voir.cache.core.key

/**
 * Storage-neutral identity of one typed family within a cache partition.
 *
 * @property partition Isolation boundary owning this family.
 * @property value Non-blank normalized family identity.
 */
public data class EncodedCacheFamily public constructor(
    public val partition: CachePartition,
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "Encoded cache family must not be blank." }
    }
}
