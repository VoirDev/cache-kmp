package dev.voir.cache.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Verifies deterministic clock mutation used by cache tests. */
class MutableCacheClockTest {
    /** Verifies setting and advancing the deterministic clock. */
    @Test
    fun `clock can be set and advanced deterministically`() {
        val clock = MutableCacheClock(Instant.fromEpochSeconds(10))

        clock.advanceBy(5.seconds)
        assertEquals(Instant.fromEpochSeconds(15), clock.now())

        clock.set(Instant.fromEpochSeconds(20))
        assertEquals(Instant.fromEpochSeconds(20), clock.now())
    }

    /** Verifies that time cannot accidentally move backwards through advancement. */
    @Test
    fun `negative advancement is rejected`() {
        val clock = MutableCacheClock(Instant.fromEpochSeconds(10))

        assertFailsWith<IllegalArgumentException> {
            clock.advanceBy((-1).seconds)
        }
    }
}
