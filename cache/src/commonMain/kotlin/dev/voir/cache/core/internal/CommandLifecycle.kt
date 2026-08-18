package dev.voir.cache.core.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Coordinates command admission with idempotent manager shutdown. */
@OptIn(ExperimentalAtomicApi::class)
internal class CommandLifecycle {
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private var activeCommands = 0
    private var idle = completedSignal()

    /** Whether shutdown has begun. */
    val isClosed: Boolean
        get() = closed.load()

    /** Throws when a non-suspending API is used after shutdown began. */
    fun ensureOpen() {
        check(!closed.load()) { "Cache manager is closed." }
    }

    /**
     * Runs [block] as one admitted command and guarantees release under cancellation.
     *
     * @param T Command result type.
     * @param block Cache command to run.
     * @return Result produced by [block].
     */
    suspend fun <T> runCommand(block: suspend () -> T): T {
        enter()
        return try {
            block()
        } finally {
            withContext(NonCancellable) { exit() }
        }
    }

    /**
     * Atomically marks shutdown as started.
     *
     * @return `true` when this caller owns close processing, or `false` after an earlier close.
     */
    suspend fun beginClose(): Boolean = mutex.withLock {
        closed.compareAndSet(expectedValue = false, newValue = true)
    }

    /** Waits until every command admitted before shutdown has returned. */
    suspend fun awaitIdle() {
        mutex.withLock { idle }.await()
    }

    /** Admits one command while the manager is open. */
    private suspend fun enter() {
        mutex.withLock {
            check(!closed.load()) { "Cache manager is closed." }
            if (activeCommands == 0) idle = CompletableDeferred()
            activeCommands += 1
        }
    }

    /** Releases one admitted command and signals shutdown when the last command exits. */
    private suspend fun exit() {
        mutex.withLock {
            activeCommands -= 1
            check(activeCommands >= 0) { "Cache command lifecycle became unbalanced." }
            if (activeCommands == 0) idle.complete(Unit)
        }
    }

    /**
     * Creates an already completed idle signal for a manager with no active commands.
     *
     * @return Completed signal replaced when the next command enters.
     */
    private fun completedSignal(): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { signal -> signal.complete(Unit) }
}
