package dev.voir.cache.file

import dev.voir.cache.core.time.CacheClock
import dev.voir.cache.core.time.SystemCacheClock
import kotlin.coroutines.CoroutineContext

/** Platform-independent execution settings for a filesystem file-cache store. */
internal data class FileCacheStoreConfiguration(
    val queryCoroutineContext: CoroutineContext = defaultStorageCoroutineContext(),
    val fileCoroutineContext: CoroutineContext = defaultStorageCoroutineContext(),
    val clock: CacheClock = SystemCacheClock,
)

/**
 * Returns the dispatcher used for blocking SQLite and filesystem work.
 *
 * Storage calls block their thread, so they must not run on a dispatcher sized for CPU-bound work.
 */
internal expect fun defaultStorageCoroutineContext(): CoroutineContext
