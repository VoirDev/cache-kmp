package dev.voir.cache.core

import dev.voir.cache.core.model.CacheFamilyMutationResult

/**
 * Typed cache resource supporting exact operations and family invalidation/removal.
 *
 * @param K Domain-owned exact key type.
 * @param F Domain-owned family key type.
 * @param V Non-null cached value type.
 * @param E Application-defined source error type.
 */
public interface FamilyCachedResource<K : Any, F : Any, V : Any, E : Any> :
    CachedResource<K, V, E> {
    /** Marks every cached value in [family] stale while retaining its payload. */
    public suspend fun invalidateFamily(family: F): CacheFamilyMutationResult

    /** Removes every cached value in [family]. */
    public suspend fun removeFamily(family: F): CacheFamilyMutationResult
}
