package dev.voir.cache.core.internal

import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheFamily
import dev.voir.cache.core.persistence.CacheAddress
import dev.voir.cache.core.persistence.ExperimentalCacheStoreApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Prevents failed bulk deletes from rehydrating cleared persistent data in the same session. */
@OptIn(ExperimentalCacheStoreApi::class)
internal class PersistentClearTracker {
    private val mutex = Mutex()
    private val namespaces = mutableSetOf<CacheNamespace>()
    private val partitions = mutableSetOf<CachePartition>()
    private val addresses = mutableSetOf<CacheAddress>()
    private val families = mutableSetOf<TrackedFamily>()
    private val availableAddresses = mutableSetOf<CacheAddress>()
    private var allCleared = false

    /**
     * Suppresses pre-clear records belonging to [namespace] for this manager session.
     *
     * @param namespace Resource namespace cleared from memory and persistence.
     */
    suspend fun markNamespace(namespace: CacheNamespace) {
        mutex.withLock {
            namespaces += namespace
            availableAddresses.removeAll { address -> address.namespace == namespace }
        }
    }

    /**
     * Suppresses pre-clear records belonging to [partition] for this manager session.
     *
     * @param partition Isolation boundary cleared across resource namespaces.
     */
    suspend fun markPartition(partition: CachePartition) {
        mutex.withLock {
            partitions += partition
            availableAddresses.removeAll { address -> address.partition == partition }
        }
    }

    /** Records that no pre-clear persistent address may hydrate in this manager session. */
    suspend fun markAll() {
        mutex.withLock {
            allCleared = true
            availableAddresses.clear()
        }
    }

    /** Suppresses pre-mutation rows from one exact namespace/partition/family selection. */
    suspend fun markFamily(namespace: CacheNamespace, family: EncodedCacheFamily) {
        mutex.withLock {
            families += TrackedFamily(namespace, family)
            availableAddresses.removeAll { address ->
                address.namespace == namespace &&
                    address.partition == family.partition &&
                    address.family == family.value
            }
        }
    }

    /** Removes temporary suppression after a family invalidation durably updates every row. */
    suspend fun markFamilyAvailable(namespace: CacheNamespace, family: EncodedCacheFamily) {
        mutex.withLock { families -= TrackedFamily(namespace, family) }
    }

    /**
     * Suppresses [address] after an exact memory-first mutation until a durable write succeeds.
     *
     * @param address Exact persistent address made authoritative in memory.
     */
    suspend fun markAddress(address: CacheAddress) {
        mutex.withLock {
            addresses += address
            availableAddresses -= address
        }
    }

    /**
     * Allows [address] to rehydrate because a newer durable write succeeded.
     *
     * @param address Exact persistent address now safe to restore after memory eviction.
     */
    suspend fun markAvailable(address: CacheAddress) {
        mutex.withLock {
            addresses -= address
            availableAddresses += address
        }
    }

    /**
     * Determines whether [address] is covered by an exact or bulk memory-first clear.
     *
     * @param address Persistent address considered for hydration.
     * @return `true` when the address must remain absent in this manager session.
     */
    suspend fun isCleared(address: CacheAddress): Boolean = mutex.withLock {
        address !in availableAddresses && (
            allCleared ||
                address in addresses ||
                families.any { family -> family.contains(address) } ||
                address.namespace in namespaces ||
                address.partition in partitions
        )
    }
}

/** Internal normalized family selection retained without exposing it to diagnostics. */
@OptIn(ExperimentalCacheStoreApi::class)
private data class TrackedFamily(
    val namespace: CacheNamespace,
    val family: EncodedCacheFamily,
) {
    fun contains(address: CacheAddress): Boolean =
        address.namespace == namespace &&
            address.partition == family.partition &&
            address.family == family.value
}
