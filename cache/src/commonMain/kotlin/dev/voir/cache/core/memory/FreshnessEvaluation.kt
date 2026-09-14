package dev.voir.cache.core.memory

import dev.voir.cache.core.model.CacheFreshness
import dev.voir.cache.core.model.CacheMetadata
import dev.voir.cache.core.policy.ExpireAfter
import dev.voir.cache.core.policy.FreshnessPolicy
import dev.voir.cache.core.policy.NeverExpire
import kotlin.time.Instant

/**
 * Calculates the expiration instant created by [policy] at [validatedAt].
 *
 * @param policy Resource freshness policy.
 * @param validatedAt Instant of successful validation.
 * @return Expiration instant, or `null` for non-expiring freshness.
 */
internal fun expirationAt(
    policy: FreshnessPolicy,
    validatedAt: Instant,
): Instant? = when (policy) {
    is ExpireAfter -> validatedAt + policy.duration
    NeverExpire -> null
}

/**
 * Evaluates [metadata] at [now] using the inclusive expiration boundary.
 *
 * @param metadata Validation metadata to evaluate.
 * @param now Current instant supplied by the cache clock.
 * @return `STALE` when expired, otherwise `FRESH`.
 */
internal fun freshnessAt(metadata: CacheMetadata, now: Instant): CacheFreshness =
    if (metadata.expiresAt != null && now >= metadata.expiresAt) {
        CacheFreshness.STALE
    } else {
        CacheFreshness.FRESH
    }
