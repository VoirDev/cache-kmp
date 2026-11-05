package dev.voir.cache.core.policy

import kotlin.time.Duration

/**
 * Marks a value stale after a finite [revalidateAfter] and removable after [duration], both from
 * its validation instant.
 *
 * A zero [revalidateAfter] is valid and means the value is always stale.
 *
 * @property duration Finite, non-negative time the value is kept before retention may remove it.
 * @property revalidateAfter Finite, non-negative time the value stays fresh; never longer than
 * [duration]. Defaults to [duration], so the value is stale exactly when it may be removed.
 */
internal data class ExpireAfter(
    val duration: Duration,
    val revalidateAfter: Duration = duration,
) : FreshnessPolicy {
    init {
        require(duration.isFinite()) { "Cache expiration duration must be finite." }
        require(!duration.isNegative()) { "Cache expiration duration must not be negative." }
        require(!revalidateAfter.isNegative()) { "Cache freshness duration must not be negative." }
        require(revalidateAfter <= duration) {
            "Cache freshness duration must not exceed the expiration duration."
        }
    }
}
