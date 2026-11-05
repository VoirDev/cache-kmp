package dev.voir.cache.core.memory

import dev.voir.cache.core.model.CacheFreshness
import dev.voir.cache.core.model.CacheMetadata
import dev.voir.cache.core.policy.ExpireAfter
import dev.voir.cache.core.policy.FreshnessPolicy
import dev.voir.cache.core.policy.NeverExpire
import dev.voir.cache.core.policy.RevalidateAfter
import kotlin.time.Instant

/**
 * Creates the metadata [policy] gives a value validated at [validatedAt].
 *
 * @param policy Resource freshness policy.
 * @param validatedAt Instant of successful validation.
 * @return Metadata carrying the staleness and expiration instants the policy derives.
 */
internal fun metadataAt(
    policy: FreshnessPolicy,
    validatedAt: Instant,
): CacheMetadata = when (policy) {
    is ExpireAfter -> CacheMetadata(
        validatedAt = validatedAt,
        expiresAt = validatedAt + policy.duration,
        staleAt = validatedAt + policy.revalidateAfter,
    )

    is RevalidateAfter -> CacheMetadata(
        validatedAt = validatedAt,
        expiresAt = null,
        staleAt = validatedAt + policy.duration,
    )

    NeverExpire -> CacheMetadata(validatedAt = validatedAt, expiresAt = null)
}

/**
 * Evaluates [metadata] at [now] using the inclusive staleness boundary.
 *
 * @param metadata Validation metadata to evaluate.
 * @param now Current instant supplied by the cache clock.
 * @return `STALE` once the value requires revalidation, otherwise `FRESH`.
 */
internal fun freshnessAt(metadata: CacheMetadata, now: Instant): CacheFreshness =
    if (metadata.staleAt != null && now >= metadata.staleAt) {
        CacheFreshness.STALE
    } else {
        CacheFreshness.FRESH
    }
