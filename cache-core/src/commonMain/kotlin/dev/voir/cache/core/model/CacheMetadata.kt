package dev.voir.cache.core.model

import kotlin.time.Instant

/**
 * Validation, expiration, and invalidation timestamps for one cached value.
 *
 * @property validatedAt Instant of the most recent successful source validation or authoritative
 * write.
 * @property expiresAt Instant at which the value becomes stale, or `null` when it never expires.
 * @property invalidatedAt Instant of explicit invalidation, or `null` when not invalidated.
 */
public data class CacheMetadata public constructor(
    public val validatedAt: Instant,
    public val expiresAt: Instant?,
    public val invalidatedAt: Instant? = null,
) {
    init {
        require(expiresAt == null || expiresAt >= validatedAt) {
            "Cache expiration cannot be earlier than validation."
        }
    }
}
