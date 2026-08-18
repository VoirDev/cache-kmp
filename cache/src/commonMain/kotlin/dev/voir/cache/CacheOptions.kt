package dev.voir.cache

import kotlin.time.Duration

/** Storage tiers used by a typed value cache. */
public enum class CacheStorage {
    MEMORY,
    PERSISTENT,
}

/** Source-loading behavior for one cache request. */
public enum class CacheRefresh {
    IF_NEEDED,
    ALWAYS,
}

/**
 * Complete configuration for direct [CacheManager.cache] calls.
 *
 * When [namespace] is omitted it is derived from the serialized value type. Specify it when the
 * same value type represents more than one logical cache.
 */
public data class CacheOptions public constructor(
    public val namespace: String? = null,
    public val storage: CacheStorage = CacheStorage.MEMORY,
    public val expiresAfter: Duration = Duration.INFINITE,
    public val maxMemoryEntries: Int = 100,
    public val maxPayloadBytes: Int = 1_048_576,
    public val partition: String = DEFAULT_CACHE_PARTITION,
    public val refresh: CacheRefresh = CacheRefresh.IF_NEEDED,
    public val useStaleOnError: Boolean = true,
) {
    init {
        require(namespace == null || namespace.isNotBlank()) {
            "Cache namespace must be null or non-blank."
        }
        require(!expiresAfter.isNegative()) {
            "Cache expiresAfter must not be negative."
        }
        require(maxMemoryEntries > 0) {
            "Cache maxMemoryEntries must be greater than zero."
        }
        require(maxPayloadBytes > 0) {
            "Cache maxPayloadBytes must be greater than zero."
        }
        require(partition.isNotBlank()) {
            "Cache partition must not be blank."
        }
    }
}

/** Per-call overrides applied on top of a reusable [CacheScope]. */
public data class CacheOverrides public constructor(
    public val expiresAfter: Duration? = null,
    public val refresh: CacheRefresh? = null,
    public val useStaleOnError: Boolean? = null,
) {
    init {
        require(expiresAfter == null || !expiresAfter.isNegative()) {
            "Cache expiresAfter override must be null or non-negative."
        }
    }
}

internal const val DEFAULT_CACHE_PARTITION: String = "default"
