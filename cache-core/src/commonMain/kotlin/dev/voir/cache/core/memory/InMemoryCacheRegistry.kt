package dev.voir.cache.core.memory

import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Coordinates namespace, partition, and global clearing across typed resource stores. */
@OptIn(ExperimentalAtomicApi::class)
internal class InMemoryCacheRegistry {
    private val targets = AtomicReference<Map<CacheNamespace, InMemoryClearTarget>>(emptyMap())

    /**
     * Registers [target] as the only memory store for its namespace.
     *
     * @param target Typed resource store exposed through its clearing contract.
     */
    fun register(target: InMemoryClearTarget) {
        while (true) {
            val current = targets.load()
            require(current[target.namespace] == null) {
                "Cache namespace '${target.namespace.value}' is already registered."
            }
            if (targets.compareAndSet(current, current + (target.namespace to target))) return
        }
    }

    /**
     * Removes the registered resource store from global clearing coordination.
     *
     * @param namespace Namespace whose store should be unregistered.
     */
    fun unregister(namespace: CacheNamespace) {
        while (true) {
            val current = targets.load()
            if (current[namespace] == null) return
            if (targets.compareAndSet(current, current - namespace)) return
        }
    }

    /**
     * Clears only [namespace] when it is registered.
     *
     * @param namespace Resource family to clear.
     */
    suspend fun clear(namespace: CacheNamespace) {
        val target = targets.load()[namespace]
        target?.clearAll()
    }

    /**
     * Clears [partition] from every registered resource family.
     *
     * @param partition Isolation boundary to clear globally.
     */
    suspend fun clear(partition: CachePartition) {
        snapshotTargets().forEach { target -> target.clearPartition(partition) }
    }

    /** Clears every registered resource family. */
    suspend fun clearAll() {
        snapshotTargets().forEach { target -> target.clearAll() }
    }

    /** Returns current namespace occupancy for diagnostics after manager-level commands. */
    suspend fun occupancies(): List<MemoryOccupancy> = snapshotTargets().map { target ->
        MemoryOccupancy(
            namespace = target.namespace,
            entries = target.entryCount(),
            limit = target.entryLimit,
        )
    }

    /**
     * Copies registered targets before invoking suspending clear operations.
     *
     * @return Stable snapshot that is safe to iterate independently of later registration.
     */
    private suspend fun snapshotTargets(): List<InMemoryClearTarget> =
        targets.load().values.toList()
}

/** Type-erased namespace memory occupancy used only by manager diagnostics. */
internal data class MemoryOccupancy(
    val namespace: CacheNamespace,
    val entries: Int,
    val limit: Int,
)
