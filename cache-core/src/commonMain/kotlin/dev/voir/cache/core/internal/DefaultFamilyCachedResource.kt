package dev.voir.cache.core.internal

import dev.voir.cache.core.CachedResource
import dev.voir.cache.core.FamilyCachedResource
import dev.voir.cache.core.key.CacheFamilyEncoder
import dev.voir.cache.core.model.CacheFamilyMutationResult

/** Typed family boundary delegating exact-address behavior to the shared cache coordinator. */
internal class DefaultFamilyCachedResource<K : Any, F : Any, V : Any, E : Any>(
    private val delegate: DefaultCachedResource<K, V, E>,
    private val familyEncoder: CacheFamilyEncoder<F>,
) : FamilyCachedResource<K, F, V, E>, CachedResource<K, V, E> by delegate {
    override suspend fun invalidateFamily(family: F): CacheFamilyMutationResult =
        delegate.invalidateEncodedFamily(familyEncoder.encode(family))

    override suspend fun removeFamily(family: F): CacheFamilyMutationResult =
        delegate.removeEncodedFamily(familyEncoder.encode(family))
}
