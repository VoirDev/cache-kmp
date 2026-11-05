package dev.voir.cache.core.model

import kotlin.time.Instant

/**
 * Validation, staleness, and expiration timestamps for one cached value.
 *
 * Staleness and expiration are separate so a value can be kept on the device long after it should
 * be revalidated: a stale value is still served and observed, while an expired one may be removed by
 * retention maintenance. A value never outlives its expiration fresh, so [staleAt] is never later
 * than [expiresAt].
 *
 * @property validatedAt Instant of the most recent successful source validation.
 * @property expiresAt Instant after which retention may remove the value, or `null` when it is kept
 * until evicted or cleared.
 * @property staleAt Instant at which the value requires revalidation, or `null` when it never does.
 * Defaults to [expiresAt], which is how every value behaved before the two were separated.
 */
internal data class CacheMetadata(
    val validatedAt: Instant,
    val expiresAt: Instant?,
    val staleAt: Instant? = expiresAt,
) {
    init {
        require(expiresAt == null || expiresAt >= validatedAt) {
            "Cache expiration cannot be earlier than validation."
        }
        require(staleAt == null || staleAt >= validatedAt) {
            "Cache staleness cannot be earlier than validation."
        }
        require(expiresAt == null || (staleAt != null && staleAt <= expiresAt)) {
            "Cache staleness cannot be later than expiration."
        }
    }
}
