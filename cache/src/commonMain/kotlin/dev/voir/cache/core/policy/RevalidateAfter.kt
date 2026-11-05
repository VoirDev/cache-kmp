package dev.voir.cache.core.policy

import kotlin.time.Duration

/**
 * Marks a value stale after a finite [duration] from its validation instant and never makes it
 * removable: it is kept until evicted, replaced, or cleared.
 *
 * @property duration Finite, non-negative time the value stays fresh.
 */
internal data class RevalidateAfter(
    val duration: Duration,
) : FreshnessPolicy {
    init {
        require(duration.isFinite()) { "Cache freshness duration must be finite." }
        require(!duration.isNegative()) { "Cache freshness duration must not be negative." }
    }
}
