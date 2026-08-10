package dev.voir.cache.core.time

import kotlin.time.Instant

/** Injectable wall clock used for deterministic freshness and metadata evaluation. */
public fun interface CacheClock {
    /**
     * Returns the current wall-clock instant.
     *
     * @return Current instant used by cache operations.
     */
    public fun now(): Instant
}

