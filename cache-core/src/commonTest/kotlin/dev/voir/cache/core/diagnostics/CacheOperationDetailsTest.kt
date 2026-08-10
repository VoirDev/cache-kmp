package dev.voir.cache.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration

/** Verifies construction invariants for structured non-sensitive operation events. */
class CacheOperationDetailsTest {
    /** Verifies start and completion phases require consistent outcome and duration fields. */
    @Test
    fun `operation phases enforce terminal invariants`() {
        CacheOperationDetails(
            correlationId = 1,
            phase = CacheOperationPhase.STARTED,
            operation = CacheOperationKind.GET,
        )
        CacheOperationDetails(
            correlationId = 1,
            phase = CacheOperationPhase.COMPLETED,
            operation = CacheOperationKind.GET,
            outcome = CacheOperationOutcome.COMMITTED,
            totalDuration = Duration.ZERO,
        )

        assertFailsWith<IllegalArgumentException> {
            CacheOperationDetails(
                correlationId = 1,
                phase = CacheOperationPhase.STARTED,
                operation = CacheOperationKind.GET,
                outcome = CacheOperationOutcome.COMMITTED,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CacheOperationDetails(
                correlationId = 1,
                phase = CacheOperationPhase.COMPLETED,
                operation = CacheOperationKind.GET,
            )
        }
    }

    /** Verifies low-cardinality count and occupancy fields reject invalid values. */
    @Test
    fun `operation counts reject invalid values`() {
        assertFailsWith<IllegalArgumentException> {
            CacheOperationDetails(
                correlationId = 0,
                phase = CacheOperationPhase.STARTED,
                operation = CacheOperationKind.GET,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CacheOperationDetails(
                correlationId = 1,
                phase = CacheOperationPhase.STARTED,
                operation = CacheOperationKind.GET,
                waiterCount = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CacheOperationDetails(
                correlationId = 1,
                phase = CacheOperationPhase.STARTED,
                operation = CacheOperationKind.GET,
                memoryEntryLimit = 0,
            )
        }
    }
}
