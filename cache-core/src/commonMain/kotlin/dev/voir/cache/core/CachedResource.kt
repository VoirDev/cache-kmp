package dev.voir.cache.core

import dev.voir.cache.core.model.CacheResult
import dev.voir.cache.core.model.CacheSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * Typed cache operations for one resource family and its bound source loader.
 *
 * @param K Domain-owned resource key type.
 * @param V Non-null cached value type.
 * @param E Application-defined source error type.
 */
public interface CachedResource<K : Any, V : Any, E : Any> {
    /**
     * Observes process-local state without invoking the source loader.
     *
     * A persistent resource starts or joins lazy disk hydration when collection begins. The flow
     * emits the current memory snapshot first, reports hydration activity when applicable, and
     * then publishes the hydrated value. Concurrent collectors share one hydration operation.
     *
     * @param key Domain key to observe.
     * @return Flow that immediately emits the current snapshot.
     */
    public fun observe(key: K): Flow<CacheSnapshot<V, E>>

    /**
     * Returns the current process-local memory state without hydration or source loading.
     *
     * This operation may return an absent snapshot before persistent hydration has run.
     *
     * @param key Domain key to inspect.
     * @return Immediate freshness-evaluated process-local snapshot.
     */
    public suspend fun peek(key: K): CacheSnapshot<V, E>

    /**
     * Awaits configured persistent hydration and returns current state without source loading.
     *
     * @param key Domain key to read.
     * @return Current freshness-evaluated snapshot after hydration completes.
     */
    public suspend fun current(key: K): CacheSnapshot<V, E>

    /**
     * Returns fresh data or joins one source refresh when data is absent or stale.
     *
     * @param key Domain key to load.
     * @return Committed value, typed source failure, or a discarded result with authoritative state.
     */
    public suspend fun get(key: K): CacheResult<V, E>

    /**
     * Forces or joins one source refresh even when the cached value is fresh.
     *
     * @param key Domain key to refresh.
     * @return Committed value, typed source failure, or a discarded result with authoritative state.
     */
    public suspend fun refresh(key: K): CacheResult<V, E>

    /**
     * Stores an authoritative validated value.
     *
     * @param key Domain key receiving the value.
     * @param value Non-null authoritative value.
     */
    public suspend fun put(key: K, value: V)

    /**
     * Atomically transforms an existing value while preserving validation metadata.
     *
     * @param key Domain key whose value should change.
     * @param transform Non-suspending transformation applied under the state lock.
     * @return `true` when an existing value was updated.
     */
    public suspend fun update(key: K, transform: (V) -> V): Boolean

    /**
     * Retains an existing value while marking it stale.
     *
     * @param key Domain key to invalidate.
     */
    public suspend fun invalidate(key: K)

    /**
     * Removes one exact address from the cache.
     *
     * @param key Domain key to remove.
     */
    public suspend fun remove(key: K)

    /** Removes every value registered under this resource namespace. */
    public suspend fun clear()
}
