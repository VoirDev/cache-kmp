package dev.voir.cache.core

import dev.voir.cache.core.paging.PagedCacheSnapshot
import dev.voir.cache.core.paging.PagedLoadResult
import kotlinx.coroutines.flow.Flow

/** First-class accumulated query cache with coordinated initial, refresh, and append work. */
public interface PagedCachedResource<Q : Any, C : Any, I : Any, E : Any> {
    public fun observe(query: Q): Flow<PagedCacheSnapshot<I, C, E>>
    public suspend fun current(query: Q): PagedCacheSnapshot<I, C, E>
    public suspend fun ensureLoaded(query: Q): PagedLoadResult<I, C, E>
    public suspend fun refresh(query: Q): PagedLoadResult<I, C, E>
    public suspend fun loadNext(query: Q): PagedLoadResult<I, C, E>
    public suspend fun update(query: Q, transform: (List<I>) -> List<I>): Boolean
    public suspend fun invalidate(query: Q)
    public suspend fun remove(query: Q)
    public suspend fun clear()
}
