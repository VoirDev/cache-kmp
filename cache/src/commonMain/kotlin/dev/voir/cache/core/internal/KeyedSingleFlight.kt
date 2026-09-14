package dev.voir.cache.core.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coordinates one manager-owned, non-memoized operation for each equal [K].
 *
 * The first caller starts work in [scope]. Later callers receive the same [Deferred] without
 * becoming parents of that work, so one caller's cancellation never cancels another's load.
 * Completion, failure, and cancellation all remove the flight before its deferred completes, so a
 * later caller starts a new operation.
 *
 * @param K Address type identifying shared work.
 * @param V Result type produced by shared work.
 * @property scope Manager-owned scope that outlives individual callers.
 */
internal class KeyedSingleFlight<K : Any, V>(
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val flights = mutableMapOf<K, Deferred<V>>()

    /**
     * Joins current work for [key] or starts a new manager-owned operation.
     *
     * @param key Address identifying shared work.
     * @param work Operation executed once for a newly admitted flight.
     * @return Shared handle every caller for [key] awaits.
     */
    suspend fun joinOrStart(key: K, work: suspend () -> V): Deferred<V> = mutex.withLock {
        flights[key]?.let { existing -> return@withLock existing }

        lateinit var created: Deferred<V>
        created = scope.async(start = CoroutineStart.LAZY) {
            try {
                work()
            } finally {
                withContext(NonCancellable) { removeCompleted(key, created) }
            }
        }
        flights[key] = created
        if (!created.start()) flights.remove(key)
        created
    }

    /** Removes [completed] only when it is still the authoritative flight for [key]. */
    private suspend fun removeCompleted(key: K, completed: Deferred<V>) {
        mutex.withLock {
            if (flights[key] !== completed) return@withLock
            flights.remove(key)
        }
    }
}
