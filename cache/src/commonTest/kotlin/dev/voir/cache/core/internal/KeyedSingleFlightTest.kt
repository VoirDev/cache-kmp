package dev.voir.cache.core.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Verifies reusable manager-owned keyed work coordination and cleanup. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyedSingleFlightTest {
    /** Equal keys share one operation and its result. */
    @Test
    fun `equal keys join one operation`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var workCount = 0
        val coordinator = KeyedSingleFlight<String, String>(backgroundScope)

        val first = coordinator.joinOrStart("entry") {
            workCount += 1
            started.complete(Unit)
            release.await()
            "result"
        }
        started.await()
        val second = coordinator.joinOrStart("entry") { "unexpected" }

        assertSame(first, second)
        assertEquals(1, workCount)

        release.complete(Unit)
        assertEquals("result", first.await())
        assertEquals("result", second.await())
    }

    /** Completed work is not memoized, so a later caller starts a new operation. */
    @Test
    fun `completion removes flight before later acquisition`() = runTest {
        var workCount = 0
        val coordinator = KeyedSingleFlight<String, Int>(backgroundScope)

        assertEquals(1, coordinator.joinOrStart("entry") { ++workCount }.await())

        assertEquals(2, coordinator.joinOrStart("entry") { ++workCount }.await())
    }

    /** Failed work is removed just like successful work and cannot poison a later acquisition. */
    @Test
    fun `failure removes flight before retry`() = runTest {
        val supervisor = SupervisorJob(backgroundScope.coroutineContext[Job])
        val coordinator = KeyedSingleFlight<String, String>(
            CoroutineScope(backgroundScope.coroutineContext + supervisor),
        )

        val failed = coordinator.joinOrStart("entry") { error("injected") }
        assertEquals(
            "injected",
            assertFailsWith<IllegalStateException> { failed.await() }.message,
        )

        assertEquals("recovered", coordinator.joinOrStart("entry") { "recovered" }.await())
        supervisor.cancel()
    }

    /** Cancelling one awaiting coroutine does not cancel manager-owned work or another waiter. */
    @Test
    fun `cancelled waiter leaves shared work active`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = KeyedSingleFlight<String, String>(backgroundScope)
        val flight = coordinator.joinOrStart("entry") {
            started.complete(Unit)
            release.await()
            "result"
        }
        started.await()
        val cancelled = async { flight.await() }
        val remaining = async { flight.await() }
        runCurrent()

        cancelled.cancelAndJoin()
        release.complete(Unit)

        assertEquals("result", remaining.await())
        assertTrue(flight.isCompleted)
    }

    /** A scope cancelled before admission cannot leave an unreachable cancelled flight retained. */
    @Test
    fun `cancelled owner scope does not retain lazy flight`() = runTest {
        val owner = SupervisorJob().also { job -> job.cancel() }
        val coordinator = KeyedSingleFlight<String, String>(
            CoroutineScope(backgroundScope.coroutineContext + owner),
        )

        val first = coordinator.joinOrStart("entry") { "unexpected" }
        val second = coordinator.joinOrStart("entry") { "unexpected" }

        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
    }
}
