package dev.voir.cache.core

import dev.voir.cache.core.model.CacheFamilyMutationResult

/** Accumulated paged resource with typed query-family invalidation and removal. */
public interface FamilyPagedCachedResource<Q : Any, F : Any, C : Any, I : Any, E : Any> :
    PagedCachedResource<Q, C, I, E> {
    public suspend fun invalidateFamily(family: F): CacheFamilyMutationResult
    public suspend fun removeFamily(family: F): CacheFamilyMutationResult
}
