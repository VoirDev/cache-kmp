package dev.voir.cache.core

import dev.voir.cache.core.config.CacheManagerConfig
import dev.voir.cache.core.diagnostics.CacheEventListener
import dev.voir.cache.core.internal.DefaultCacheManager
import dev.voir.cache.core.persistence.ExperimentalCacheStoreApi
import dev.voir.cache.core.persistence.PersistentCacheStore
import dev.voir.cache.core.time.CacheClock
import dev.voir.cache.core.time.SystemCacheClock
import kotlin.coroutines.CoroutineContext

/**
 * Creates an isolated manager whose jobs are children of [coroutineContext].
 *
 * Closing the returned manager cancels only its own supervisor job, never the host's parent job.
 *
 * @param coroutineContext Host context used by manager-owned source and diagnostic work.
 * @param clock Time source used for freshness and validation metadata.
 * @param eventListener Optional non-sensitive diagnostic listener.
 * @param config Address validation limits.
 * @return New cache manager instance.
 */
@OptIn(ExperimentalCacheStoreApi::class)
public fun createCacheManager(
    coroutineContext: CoroutineContext,
    clock: CacheClock = SystemCacheClock,
    eventListener: CacheEventListener? = null,
    config: CacheManagerConfig = CacheManagerConfig(),
): CacheManager = DefaultCacheManager(
    coroutineContext = coroutineContext,
    persistentStore = null,
    clock = clock,
    eventListener = eventListener,
    config = config,
)

/**
 * Creates a manager that owns [persistentStore] in addition to its memory tier.
 *
 * @param coroutineContext Host context used by manager-owned source and diagnostic work.
 * @param persistentStore Encoded persistent backend owned and closed by the manager.
 * @param clock Time source used for freshness and validation metadata.
 * @param eventListener Optional non-sensitive diagnostic listener.
 * @param config Address validation limits.
 * @return New cache manager instance.
 */
@ExperimentalCacheStoreApi
public fun createCacheManager(
    coroutineContext: CoroutineContext,
    persistentStore: PersistentCacheStore,
    clock: CacheClock = SystemCacheClock,
    eventListener: CacheEventListener? = null,
    config: CacheManagerConfig = CacheManagerConfig(),
): CacheManager = DefaultCacheManager(
    coroutineContext = coroutineContext,
    persistentStore = persistentStore,
    clock = clock,
    eventListener = eventListener,
    config = config,
)
