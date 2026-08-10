package dev.voir.cache.core.internal

import dev.voir.cache.core.FamilyPagedCachedResource
import dev.voir.cache.core.PagedCachedResource
import dev.voir.cache.core.key.CacheFamilyEncoder
import dev.voir.cache.core.model.CacheFamilyMutationResult
import dev.voir.cache.core.model.CachePersistenceStatus

/** Typed family boundary over the shared paged coordination engine. */
internal class DefaultFamilyPagedCachedResource<Q : Any, F : Any, C : Any, I : Any, ID : Any, E : Any>(
    private val delegate: DefaultPagedCachedResource<Q, C, I, ID, E>,
    private val familyEncoder: CacheFamilyEncoder<F>,
) : FamilyPagedCachedResource<Q, F, C, I, E>,
    PagedCachedResource<Q, C, I, E> by delegate {
    override suspend fun invalidateFamily(family: F): CacheFamilyMutationResult =
        CacheFamilyMutationResult(
            memoryEntriesAffected = delegate.invalidateEncodedFamily(familyEncoder.encode(family)),
            persistentRowsAffected = 0,
            persistenceStatus = CachePersistenceStatus.NOT_CONFIGURED,
        )

    override suspend fun removeFamily(family: F): CacheFamilyMutationResult =
        CacheFamilyMutationResult(
            memoryEntriesAffected = delegate.removeEncodedFamily(familyEncoder.encode(family)),
            persistentRowsAffected = 0,
            persistenceStatus = CachePersistenceStatus.NOT_CONFIGURED,
        )
}
