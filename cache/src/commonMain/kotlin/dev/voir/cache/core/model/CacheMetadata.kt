package dev.voir.cache.core.model

import kotlin.time.Instant

/**
 * Validation and expiration timestamps for one cached value.
 *
 * @property validatedAt Instant of the most recent successful source validation.
 * @property expiresAt Instant at which the value becomes stale, or `null` when it never expires.
 */
internal data class CacheMetadata(
    val validatedAt: Instant,
    val expiresAt: Instant?,
) {
    init {
        require(expiresAt == null || expiresAt >= validatedAt) {
            "Cache expiration cannot be earlier than validation."
        }
    }
}
