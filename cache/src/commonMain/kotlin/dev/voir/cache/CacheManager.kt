package dev.voir.cache

import dev.voir.cache.core.internal.DefaultCacheManager
import dev.voir.cache.internal.CacheRuntimeRegistry

/**
 * Application-owned entry point for memory, persistent-value, and file caching.
 *
 * Platform factories create and own every storage backend internally. Call [close] when the
 * application scope has an explicit suspending shutdown boundary.
 */
public class CacheManager internal constructor(
    internal val engine: DefaultCacheManager,
) {
    internal val runtimes: CacheRuntimeRegistry = CacheRuntimeRegistry(engine)

    /** Clears all memory, persistent values, and cached files owned by this manager. */
    public suspend fun clearAll(): Unit = engine.clearAll()

    /** Closes the manager and every internally owned storage backend. */
    public suspend fun close(): Unit = engine.close()
}
