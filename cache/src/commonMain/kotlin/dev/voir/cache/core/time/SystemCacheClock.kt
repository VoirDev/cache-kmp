package dev.voir.cache.core.time

import kotlin.time.Clock
import kotlin.time.Instant

/** Production cache clock backed by the platform system clock. */
internal object SystemCacheClock : CacheClock {
    /**
     * Reads the current instant from the platform system clock.
     *
     * @return Current platform wall-clock instant.
     */
    override fun now(): Instant = Clock.System.now()
}

