package dev.voir.cache.testing

import dev.voir.cache.core.time.CacheClock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Deterministic mutable clock for cache tests that must control freshness boundaries.
 *
 * @param initialInstant Instant returned before the clock is changed.
 */
public class MutableCacheClock public constructor(
    initialInstant: Instant,
) : CacheClock {
    /** Mutable instant returned by [now]. */
    private var currentInstant: Instant = initialInstant

    /** Returns the current deterministic instant. */
    override fun now(): Instant = currentInstant

    /**
     * Replaces the current instant with [instant].
     *
     * @param instant New deterministic instant.
     */
    public fun set(instant: Instant) {
        currentInstant = instant
    }

    /**
     * Advances the clock by [duration].
     *
     * @param duration Non-negative amount of time to advance.
     */
    public fun advanceBy(duration: Duration) {
        require(!duration.isNegative()) { "Clock duration must not be negative." }
        currentInstant += duration
    }
}
