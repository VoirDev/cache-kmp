package dev.voir.cache

import kotlin.time.Duration

/** Complete configuration for a direct [CacheManager.cacheFile] call. */
public data class CacheFileOptions public constructor(
    public val namespace: String? = null,
    public val expiresAfter: Duration = Duration.INFINITE,
    public val maxEntryBytes: Long = 10L * 1_024 * 1_024,
    public val maxEntries: Int = 100,
    public val maxBytes: Long = 100L * 1_024 * 1_024,
    public val partition: String = DEFAULT_CACHE_PARTITION,
    public val refresh: CacheRefresh = CacheRefresh.IF_NEEDED,
    public val useStaleOnError: Boolean = true,
    public val mediaType: String? = null,
) {
    init {
        require(namespace == null || namespace.isNotBlank()) {
            "File cache namespace must be null or non-blank."
        }
        require(!expiresAfter.isNegative()) {
            "File cache expiresAfter must not be negative."
        }
        require(maxEntryBytes > 0) {
            "File cache maxEntryBytes must be greater than zero."
        }
        require(maxEntries > 0) {
            "File cache maxEntries must be greater than zero."
        }
        require(maxBytes > 0) {
            "File cache maxBytes must be greater than zero."
        }
        require(maxEntryBytes <= maxBytes) {
            "File cache maxEntryBytes must not exceed maxBytes."
        }
        require(partition.isNotBlank()) {
            "File cache partition must not be blank."
        }
        require(mediaType == null || mediaType.isNotBlank()) {
            "File cache mediaType must be null or non-blank."
        }
        require(mediaType == null || mediaType.length <= 255) {
            "File cache mediaType must not exceed 255 characters."
        }
    }
}

/** Per-call overrides applied on top of a reusable [CacheFileScope]. */
public data class CacheFileOverrides public constructor(
    public val expiresAfter: Duration? = null,
    public val refresh: CacheRefresh? = null,
    public val useStaleOnError: Boolean? = null,
    public val mediaType: String? = null,
) {
    init {
        require(expiresAfter == null || !expiresAfter.isNegative()) {
            "File cache expiresAfter override must be null or non-negative."
        }
        require(mediaType == null || mediaType.isNotBlank()) {
            "File cache mediaType override must be null or non-blank."
        }
        require(mediaType == null || mediaType.length <= 255) {
            "File cache mediaType override must not exceed 255 characters."
        }
    }
}
