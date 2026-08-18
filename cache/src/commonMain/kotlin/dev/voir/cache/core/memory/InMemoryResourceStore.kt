package dev.voir.cache.core.memory

import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.model.CacheActivity
import dev.voir.cache.core.model.CacheMetadata
import dev.voir.cache.core.model.CacheSnapshot
import dev.voir.cache.core.policy.FreshnessPolicy
import dev.voir.cache.core.policy.MemoryPolicy
import dev.voir.cache.core.time.CacheClock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Instant

/**
 * Atomic observable memory state for one typed resource.
 *
 * All mutable state is serialized by one resource-local mutex. Independent resources therefore
 * remain concurrent, while updates to one resource preserve snapshot and LRU invariants on every
 * supported platform.
 *
 * Access ordering, pin counts, and revisions are kept outside the published snapshot map so a plain
 * read never rewrites observable state.
 *
 * @param V Non-null cached value type.
 * @param E Application-defined source error type.
 * @property namespace Stable identity owned by this resource store.
 * @param freshnessPolicy Policy used to create and evaluate validation metadata.
 * @param memoryPolicy Required per-resource memory bound.
 * @param clock Time source used for validation, expiration, and invalidation.
 */
internal class InMemoryResourceStore<V : Any, E : Any>(
    override val namespace: CacheNamespace,
    private val freshnessPolicy: FreshnessPolicy,
    private val memoryPolicy: MemoryPolicy,
    private val clock: CacheClock,
) : InMemoryClearTarget {
    private val mutex = Mutex()
    private val state = MutableStateFlow<Map<EncodedCacheKey, CacheSnapshot<V, E>>>(emptyMap())
    private val accessSequences = mutableMapOf<EncodedCacheKey, Long>()
    private val observerPins = mutableMapOf<EncodedCacheKey, Int>()
    private val operationPins = mutableMapOf<EncodedCacheKey, Int>()
    private val revisions = mutableMapOf<EncodedCacheKey, Long>()
    private var accessSequence = 0L

    /**
     * Observes [key] and pins it against eviction for the collector's lifetime.
     *
     * Observation evaluates freshness but never starts external work.
     *
     * @param key Encoded resource identity to observe.
     * @return Flow that immediately emits current state and all later distinct snapshots.
     */
    fun observe(key: EncodedCacheKey): Flow<CacheSnapshot<V, E>> = flow {
        pinObserver(key)
        try {
            emitAll(
                state
                    .map { entries -> entries[key] ?: CacheSnapshot() }
                    .distinctUntilChanged()
            )
        } finally {
            withContext(NonCancellable) { unpinObserver(key) }
        }
    }

    /**
     * Reads [key], evaluates freshness, and records an LRU access.
     *
     * @param key Encoded resource identity to read.
     * @return Current complete snapshot, or absent state for an unknown key.
     */
    suspend fun current(key: EncodedCacheKey): CacheSnapshot<V, E> = mutex.withLock {
        val snapshot = state.value[key] ?: return@withLock CacheSnapshot()
        touch(key)
        val evaluated = snapshot.evaluateAt(clock.now())
        if (evaluated !== snapshot) publish(state.value + (key to evaluated))
        evaluated
    }

    /**
     * Removes [key] and publishes absent state to existing observers.
     *
     * @param key Encoded resource identity to remove.
     * @return Revision produced by the removal command.
     */
    suspend fun remove(key: EncodedCacheKey): Long = mutex.withLock {
        val revision = advanceRevision(key)
        publish(state.value - key)
        forget(key)
        revision
    }

    /** Removes every entry in this namespace and publishes absent state to observers. */
    override suspend fun clearAll() {
        mutex.withLock {
            revisions.keys.toList().forEach(::advanceRevision)
            publish(emptyMap())
            accessSequences.keys.toList().forEach { key -> forget(key) }
        }
    }

    /**
     * Starts one persistent hydration and captures the revision used by its restore guard.
     *
     * @param key Encoded resource identity being hydrated.
     * @return Captured address revision.
     */
    suspend fun beginHydration(key: EncodedCacheKey): HydrationStart = mutex.withLock {
        operationPins[key] = operationPins.getOrElse(key) { 0 } + 1
        val revision = revisions.getOrPut(key) { 0L }
        val existing = state.value[key]?.evaluateAt(clock.now())
        val hydrating = existing?.copy(activity = CacheActivity.HYDRATING, refreshError = null)
            ?: CacheSnapshot(activity = CacheActivity.HYDRATING)
        touch(key)
        publish(enforceMemoryLimit(state.value + (key to hydrating), protectedKey = key))
        HydrationStart(revision)
    }

    /**
     * Restores a decoded persistent [value] when [expectedRevision] remains authoritative.
     *
     * @param key Encoded resource identity receiving the restored value.
     * @param expectedRevision Revision captured by [beginHydration].
     * @param value Decoded non-null persistent value.
     * @param metadata Original persistent validation metadata.
     * @return `true` when the value restored, or `false` when a newer mutation won.
     */
    suspend fun completeHydrationSuccess(
        key: EncodedCacheKey,
        expectedRevision: Long,
        value: V,
        metadata: CacheMetadata,
    ): Boolean = mutex.withLock {
        if (revisionOf(key) != expectedRevision) return@withLock false
        revisions[key] = expectedRevision + 1
        val snapshot = CacheSnapshot<V, E>(
            value = value,
            freshness = freshnessAt(metadata, clock.now()),
            activity = CacheActivity.IDLE,
            metadata = metadata,
        )
        touch(key)
        publish(enforceMemoryLimit(state.value + (key to snapshot), protectedKey = key))
        true
    }

    /**
     * Completes hydration without a value while retaining an LRU-bounded absence marker.
     *
     * @param key Encoded resource identity whose persistent row was absent or unavailable.
     * @param expectedRevision Revision captured by [beginHydration].
     * @return `true` when absence committed, or `false` when a newer mutation won.
     */
    suspend fun completeHydrationAbsent(
        key: EncodedCacheKey,
        expectedRevision: Long,
    ): Boolean = mutex.withLock {
        if (revisionOf(key) != expectedRevision) return@withLock false
        revisions[key] = expectedRevision + 1
        touch(key)
        publish(enforceMemoryLimit(state.value + (key to CacheSnapshot()), protectedKey = key))
        true
    }

    /**
     * Clears hydration activity after cancellation when this generation is still current.
     *
     * @param key Encoded resource identity whose persistent read was cancelled.
     * @param expectedRevision Revision captured by [beginHydration].
     */
    suspend fun cancelHydration(key: EncodedCacheKey, expectedRevision: Long) {
        mutex.withLock { clearActivity(key, expectedRevision) }
    }

    /**
     * Starts one source refresh and captures the address revision used by its commit guard.
     *
     * The current value remains visible, activity becomes [CacheActivity.REFRESHING], and any
     * previous transient source error is cleared.
     *
     * @param key Encoded resource identity being refreshed.
     * @return Captured revision and the value retained while work runs.
     */
    suspend fun beginRefresh(key: EncodedCacheKey): RefreshStart<V> = mutex.withLock {
        operationPins[key] = operationPins.getOrElse(key) { 0 } + 1
        val revision = revisions.getOrPut(key) { 0L }
        val existing = state.value[key]?.evaluateAt(clock.now())
        val refreshing = existing?.copy(activity = CacheActivity.REFRESHING, refreshError = null)
            ?: CacheSnapshot(activity = CacheActivity.REFRESHING)
        touch(key)
        publish(enforceMemoryLimit(state.value + (key to refreshing), protectedKey = key))
        RefreshStart(revision = revision, staleValue = refreshing.value)
    }

    /**
     * Commits a successful source [value] only when [expectedRevision] is still authoritative.
     *
     * @param key Encoded resource identity receiving the loaded value.
     * @param expectedRevision Revision captured by [beginRefresh].
     * @param value Successfully loaded value.
     * @param committedMetadata Metadata already produced by a durable commit, when available.
     * @param committedFreshnessPolicy Policy applied when this commit derives its own metadata.
     * @return Committed revision and snapshot, or `null` when a newer mutation won.
     */
    suspend fun completeRefreshSuccess(
        key: EncodedCacheKey,
        expectedRevision: Long,
        value: V,
        committedMetadata: CacheMetadata? = null,
        committedFreshnessPolicy: FreshnessPolicy = freshnessPolicy,
    ): MemoryCommit<V, E>? = mutex.withLock {
        if (revisionOf(key) != expectedRevision) return@withLock null
        val revision = expectedRevision + 1
        revisions[key] = revision
        val validatedAt = committedMetadata?.validatedAt ?: clock.now()
        val metadata = committedMetadata ?: CacheMetadata(
            validatedAt = validatedAt,
            expiresAt = expirationAt(committedFreshnessPolicy, validatedAt),
        )
        val snapshot = CacheSnapshot<V, E>(
            value = value,
            freshness = freshnessAt(metadata, validatedAt),
            activity = CacheActivity.IDLE,
            metadata = metadata,
        )
        touch(key)
        publish(enforceMemoryLimit(state.value + (key to snapshot), protectedKey = key))
        MemoryCommit(revision = revision, snapshot = snapshot)
    }

    /**
     * Publishes a source [error] only when [expectedRevision] still identifies this refresh.
     *
     * @param key Encoded resource identity whose refresh failed.
     * @param expectedRevision Revision captured by [beginRefresh].
     * @param error Application-defined source error.
     * @return `true` when the failure was published, or `false` when it was superseded.
     */
    suspend fun completeRefreshFailure(
        key: EncodedCacheKey,
        expectedRevision: Long,
        error: E,
    ): Boolean = mutex.withLock {
        if (revisionOf(key) != expectedRevision) return@withLock false
        revisions[key] = expectedRevision + 1
        val existing = state.value[key]
        val failed = existing?.copy(activity = CacheActivity.IDLE, refreshError = error)
            ?: CacheSnapshot(refreshError = error)
        touch(key)
        publish(enforceMemoryLimit(state.value + (key to failed), protectedKey = key))
        true
    }

    /**
     * Clears refresh activity after cancellation when this generation is still current.
     *
     * @param key Encoded resource identity whose work was cancelled.
     * @param expectedRevision Revision captured by [beginRefresh].
     */
    suspend fun cancelRefresh(key: EncodedCacheKey, expectedRevision: Long) {
        mutex.withLock { clearActivity(key, expectedRevision) }
    }

    /**
     * Releases one manager-owned operation pin and enforces deferred eviction.
     *
     * @param key Encoded resource identity whose operation completed.
     */
    suspend fun unpinOperation(key: EncodedCacheKey) {
        mutex.withLock {
            decrementPin(operationPins, key)
            publish(enforceMemoryLimit(state.value))
            if (key !in state.value) forget(key)
        }
    }

    /**
     * Checks whether [key] has retained state, including a hydrated-absence marker.
     *
     * @param key Encoded address to inspect.
     * @return `true` when the address currently participates in memory retention.
     */
    suspend fun contains(key: EncodedCacheKey): Boolean = mutex.withLock { key in state.value }

    /**
     * Returns the addresses that currently hold retained state.
     *
     * Callers use this to release per-address bookkeeping they keep outside this store once the
     * memory bound has evicted an address.
     */
    val retainedKeys: Set<EncodedCacheKey>
        get() = state.value.keys

    /**
     * Number of addresses with retained per-address bookkeeping.
     *
     * Exposed so the bounded-memory contract can be asserted directly rather than inferred.
     */
    val trackedAddressCount: Int
        get() = revisions.size

    /**
     * Checks whether [expectedRevision] still authorizes a persistent operation for [key].
     *
     * @param key Encoded address whose revision is inspected.
     * @param expectedRevision Revision captured by the pending operation.
     * @return `true` when no newer mutation has superseded the operation.
     */
    suspend fun isRevisionCurrent(key: EncodedCacheKey, expectedRevision: Long): Boolean =
        mutex.withLock { revisionOf(key) == expectedRevision }

    /** Increments the observer pin count, evaluates freshness, and records an access. */
    private suspend fun pinObserver(key: EncodedCacheKey) {
        mutex.withLock {
            observerPins[key] = observerPins.getOrElse(key) { 0 } + 1
            touchAndEnforce(key)
        }
    }

    /** Decrements the observer pin count and enforces any eviction deferred while observed. */
    private suspend fun unpinObserver(key: EncodedCacheKey) {
        mutex.withLock {
            decrementPin(observerPins, key)
            publish(enforceMemoryLimit(state.value))
            if (key !in state.value) forget(key)
        }
    }

    /** Evaluates and touches [key] when present, then enforces the memory bound. */
    private fun touchAndEnforce(key: EncodedCacheKey) {
        val snapshot = state.value[key]
        if (snapshot == null) {
            publish(enforceMemoryLimit(state.value))
            return
        }
        touch(key)
        val evaluated = snapshot.evaluateAt(clock.now())
        val entries = if (evaluated === snapshot) state.value else state.value + (key to evaluated)
        publish(enforceMemoryLimit(entries))
    }

    /** Clears transient activity for a cancelled operation that is still authoritative. */
    private fun clearActivity(key: EncodedCacheKey, expectedRevision: Long) {
        if (revisionOf(key) != expectedRevision) return
        revisions[key] = expectedRevision + 1
        val existing = state.value[key] ?: return
        if (existing.value == null) {
            publish(state.value - key)
        } else {
            publish(
                state.value + (
                    key to existing.copy(
                        activity = CacheActivity.IDLE,
                        refreshError = null,
                    )
                )
            )
        }
    }

    /** Returns this snapshot with freshness evaluated at [now]. */
    private fun CacheSnapshot<V, E>.evaluateAt(now: Instant): CacheSnapshot<V, E> {
        val current = metadata ?: return this
        val evaluated = freshnessAt(current, now)
        return if (freshness == evaluated) this else copy(freshness = evaluated)
    }

    /**
     * Removes least-recently-used unpinned entries until [entries] satisfies the bound.
     *
     * @param entries Candidate state to bound.
     * @param protectedKey Key temporarily protected by the committing operation.
     * @return Bounded state, or the smallest possible state when every candidate is pinned.
     */
    private fun enforceMemoryLimit(
        entries: Map<EncodedCacheKey, CacheSnapshot<V, E>>,
        protectedKey: EncodedCacheKey? = null,
    ): Map<EncodedCacheKey, CacheSnapshot<V, E>> {
        if (entries.size <= memoryPolicy.maxEntries) return entries
        var bounded = entries
        while (bounded.size > memoryPolicy.maxEntries) {
            val evictable = bounded.entries
                .filter { (key, snapshot) -> key != protectedKey && !isPinned(key, snapshot) }
            if (evictable.isEmpty()) break
            // Observers and active work hold their addresses. Once those alone fill the bound,
            // retaining a just-committed entry is better than evicting it the moment it lands.
            if (bounded.size - evictable.size >= memoryPolicy.maxEntries) break
            val candidate = evictable.minWith(
                compareBy<Map.Entry<EncodedCacheKey, CacheSnapshot<V, E>>> {
                    accessSequences.getOrElse(it.key) { 0L }
                }.thenBy { it.key.partition.value }
                    .thenBy { it.key.value }
            ).key
            bounded = bounded - candidate
            forget(candidate)
        }
        return bounded
    }

    /** Returns whether [key] is protected by observation, active work, or snapshot activity. */
    private fun isPinned(key: EncodedCacheKey, snapshot: CacheSnapshot<V, E>): Boolean =
        observerPins.getOrElse(key) { 0 } > 0 ||
            operationPins.getOrElse(key) { 0 } > 0 ||
            snapshot.activity != CacheActivity.IDLE

    /** Decrements [pins] for [key] without retaining zero-count bookkeeping. */
    private fun decrementPin(pins: MutableMap<EncodedCacheKey, Int>, key: EncodedCacheKey) {
        val remaining = pins.getOrElse(key) { 0 } - 1
        if (remaining > 0) pins[key] = remaining else pins.remove(key)
    }

    /** Returns the current revision for [key], creating its initial generation when necessary. */
    private fun revisionOf(key: EncodedCacheKey): Long = revisions.getOrPut(key) { 0L }

    /** Advances the authoritative revision for [key]. */
    private fun advanceRevision(key: EncodedCacheKey): Long {
        val next = revisionOf(key) + 1
        revisions[key] = next
        return next
    }

    /** Records an LRU access for [key]. */
    private fun touch(key: EncodedCacheKey) {
        accessSequence += 1
        accessSequences[key] = accessSequence
    }

    /**
     * Releases per-address bookkeeping for an address with no retained state and no pins.
     *
     * Without this, revision and access bookkeeping would grow without bound for managers that
     * observe far more distinct addresses than the configured memory limit retains.
     */
    private fun forget(key: EncodedCacheKey) {
        if (observerPins.containsKey(key) || operationPins.containsKey(key)) return
        accessSequences.remove(key)
        revisions.remove(key)
    }

    /** Publishes [entries] as one complete atomic state transition. */
    private fun publish(entries: Map<EncodedCacheKey, CacheSnapshot<V, E>>) {
        state.value = entries
    }
}
