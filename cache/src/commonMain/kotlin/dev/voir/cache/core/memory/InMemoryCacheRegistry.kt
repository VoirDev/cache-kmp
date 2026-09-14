package dev.voir.cache.core.memory

import dev.voir.cache.core.key.CacheNamespace
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Coordinates global clearing across typed resource stores owned by one manager. */
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

    /** Clears every registered resource family. */
    suspend fun clearAll() {
        targets.load().values.toList().forEach { target -> target.clearAll() }
    }
}
