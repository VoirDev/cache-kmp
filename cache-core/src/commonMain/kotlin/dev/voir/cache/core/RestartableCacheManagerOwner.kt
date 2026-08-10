package dev.voir.cache.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coroutine-native owner for hosts that start and stop cache managers more than once.
 *
 * The owner serializes lifecycle transitions without blocking a platform thread. [factory] must
 * return a new manager for every invocation because a stopped manager is terminal and cannot be
 * reopened.
 *
 * @param factory Creates one fresh manager for the next active host lifecycle.
 */
public class RestartableCacheManagerOwner(
    private val factory: () -> CacheManager,
) {
    private val mutex: Mutex = Mutex()
    private var activeManager: CacheManager? = null

    /**
     * Returns the active manager, creating it once for the current host lifecycle when necessary.
     *
     * Concurrent callers share the same manager instance.
     */
    public suspend fun start(): CacheManager = mutex.withLock {
        activeManager ?: factory().also { manager -> activeManager = manager }
    }

    /** Returns the active manager without starting a new lifecycle. */
    public suspend fun current(): CacheManager? = mutex.withLock { activeManager }

    /**
     * Detaches and fully closes the active manager, if any.
     *
     * Concurrent lifecycle transitions wait for shutdown to finish. The manager remains detached
     * even when persistent shutdown fails, so a later [start] creates a fresh instance.
     */
    public suspend fun stop() {
        mutex.withLock {
            val manager = activeManager ?: return@withLock
            activeManager = null
            withContext(NonCancellable) { manager.close() }
        }
    }

    /** Alias for [stop] for owners whose surrounding lifecycle also uses `close` terminology. */
    public suspend fun close(): Unit = stop()
}
