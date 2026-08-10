package dev.voir.cache.core.fixture

import dev.voir.cache.core.time.CacheClock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Deterministic mutable clock used by cache state tests.
 *
 * @param initialInstant Wall-clock instant returned before the first advancement.
 */
internal class FakeCacheClock(
    initialInstant: Instant,
) : CacheClock {
    private var currentInstant = initialInstant

    /** Returns the current deterministic instant. */
    override fun now(): Instant = currentInstant

    /**
     * Advances the clock by [duration].
     *
     * @param duration Non-negative amount of time to advance.
     */
    fun advanceBy(duration: Duration) {
        require(!duration.isNegative()) { "Fake clock duration must not be negative." }
        currentInstant += duration
    }
}
