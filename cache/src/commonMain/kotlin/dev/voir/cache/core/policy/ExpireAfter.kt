package dev.voir.cache.core.policy

import kotlin.time.Duration

/**
 * Marks a value stale after a finite [duration] from its validation instant.
 *
 * A zero duration is valid and means the value is always stale.
 *
 * @property duration Finite, non-negative freshness duration.
 */
internal data class ExpireAfter(
    val duration: Duration,
) : FreshnessPolicy {
    init {
        require(duration.isFinite()) { "Cache freshness duration must be finite." }
        require(!duration.isNegative()) { "Cache freshness duration must not be negative." }
    }
}
