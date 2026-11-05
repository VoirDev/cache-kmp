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
 *
 * [expiresAfter] is how long a value is kept: once it has passed, retention may remove the value,
 * including from persistent storage when a manager opens. [revalidateAfter] is how long it stays
 * fresh: once it has passed, [CacheRefresh.IF_NEEDED] loads again, while the value is still served,
 * observed, and used as a stale-on-error fallback. Keeping a value much longer than it stays fresh
 * lets an application show what it last read at once, even offline, and revalidate it quietly.
 *
 * @property revalidateAfter How long a value stays fresh, or `null` to stay fresh until it expires.
 * Never longer than [expiresAfter].
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
    public val revalidateAfter: Duration? = null,
) {
    init {
        require(namespace == null || namespace.isNotBlank()) {
            "Cache namespace must be null or non-blank."
        }
        require(!expiresAfter.isNegative()) {
            "Cache expiresAfter must not be negative."
        }
        require(revalidateAfter == null || !revalidateAfter.isNegative()) {
            "Cache revalidateAfter must be null or non-negative."
        }
        require(revalidateAfter == null || revalidateAfter <= expiresAfter) {
            "Cache revalidateAfter must not exceed expiresAfter."
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

/**
 * Per-call overrides applied on top of a reusable [CacheScope].
 *
 * A freshness longer than the call's resolved expiration is cut to it, so overriding only
 * [expiresAfter] downwards also shortens freshness.
 *
 * @property revalidateAfter How long the value this call commits stays fresh, or `null` for the
 * scope's own.
 */
public data class CacheOverrides public constructor(
    public val expiresAfter: Duration? = null,
    public val refresh: CacheRefresh? = null,
    public val useStaleOnError: Boolean? = null,
    public val revalidateAfter: Duration? = null,
) {
    init {
        require(expiresAfter == null || !expiresAfter.isNegative()) {
            "Cache expiresAfter override must be null or non-negative."
        }
        require(revalidateAfter == null || !revalidateAfter.isNegative()) {
            "Cache revalidateAfter override must be null or non-negative."
        }
    }
}

internal const val DEFAULT_CACHE_PARTITION: String = "default"
