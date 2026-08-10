package dev.voir.cache.core.memory

import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.key.EncodedCacheFamily
import dev.voir.cache.core.model.CacheActivity
import dev.voir.cache.core.model.CacheFreshness
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
 * Atomic observable memory state for one typed resource family.
 *
 * All mutable state is serialized by one resource-local mutex. Independent resources therefore
 * remain concurrent, while updates to one resource preserve snapshot and LRU invariants on every
 * supported platform.
 *
 * @param V Non-null cached value type.
 * @param E Application-defined source error type.
 * @property namespace Stable identity owned by this resource store.
 * @param freshnessPolicy Policy used to create and evaluate validation metadata.
 * @param memoryPolicy Required per-resource memory bound.
 * @param clock Time source used for validation, expiration, and invalidation.
 * @param onEviction Non-blocking callback receiving deterministic memory-LRU removals.
 */
internal class InMemoryResourceStore<V : Any, E : Any>(
    override val namespace: CacheNamespace,
    private val freshnessPolicy: FreshnessPolicy,
    private val memoryPolicy: MemoryPolicy,
    private val clock: CacheClock,
    private val onEviction: (EncodedCacheKey, Int, Int) -> Unit = { _, _, _ -> },
) : InMemoryClearTarget {
    override val entryLimit: Int
        get() = memoryPolicy.maxEntries
    private val mutex = Mutex()
    private val state = MutableStateFlow<Map<EncodedCacheKey, StoredCacheEntry<V, E>>>(emptyMap())
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
                    .map { entries -> entries[key]?.snapshot ?: CacheSnapshot() }
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
        val entry = state.value[key] ?: return@withLock CacheSnapshot()
        val accessed = entry
            .evaluateAt(clock.now())
            .copy(lastAccessSequence = nextAccessSequence())
        publish(state.value + (key to accessed))
        accessed.snapshot
    }

    /**
     * Stores [value] as an authoritative validated write and enforces the memory bound.
     *
     * @param key Encoded resource identity receiving the value.
     * @param value Non-null authoritative value.
     * @return Committed revision and complete snapshot for persistence coordination.
     */
    suspend fun put(key: EncodedCacheKey, value: V): MemoryCommit<V, E> = mutex.withLock {
        val revision = advanceRevision(key)
        val validatedAt = clock.now()
        val metadata = CacheMetadata(
            validatedAt = validatedAt,
            expiresAt = expirationAt(freshnessPolicy, validatedAt),
        )
        val entry = StoredCacheEntry<V, E>(
            snapshot = CacheSnapshot<V, E>(
                value = value,
                freshness = freshnessAt(metadata, validatedAt),
                activity = CacheActivity.IDLE,
                metadata = metadata,
                refreshError = null,
            ),
            lastAccessSequence = nextAccessSequence(),
        )
        publish(enforceMemoryLimit(state.value + (key to entry), protectedKey = key))
        MemoryCommit(revision = revision, snapshot = entry.snapshot)
    }

    /**
     * Atomically transforms an existing value without changing validation metadata.
     *
     * If [transform] throws, no state—including lazy freshness evaluation—is published.
     *
     * @param key Encoded resource identity to update.
     * @param transform Non-suspending authoritative transformation.
     * @return `true` when the key existed and was updated, otherwise `false`.
     */
    suspend fun update(key: EncodedCacheKey, transform: (V) -> V): Boolean =
        updateAndCapture(key, transform) != null

    /**
     * Atomically transforms an existing value and returns its persistence coordination data.
     *
     * @param key Encoded resource identity to update.
     * @param transform Non-suspending authoritative transformation.
     * @return Committed revision and snapshot, or `null` when no value existed.
     */
    suspend fun updateAndCapture(
        key: EncodedCacheKey,
        transform: (V) -> V,
    ): MemoryCommit<V, E>? = mutex.withLock {
        val entry = state.value[key] ?: return@withLock null
        val evaluated = entry.evaluateAt(clock.now())
        val currentValue = evaluated.snapshot.value ?: return@withLock null
        val transformedValue = transform(currentValue)
        val revision = advanceRevision(key)
        val updated = evaluated.copy(
            snapshot = evaluated.snapshot.copy(
                value = transformedValue,
                activity = CacheActivity.IDLE,
                refreshError = null,
            ),
            lastAccessSequence = nextAccessSequence(),
        )
        publish(enforceMemoryLimit(state.value + (key to updated), protectedKey = key))
        MemoryCommit(revision = revision, snapshot = updated.snapshot)
    }

    /**
     * Marks an existing [key] stale without removing its value or validation instant.
     *
     * @param key Encoded resource identity to invalidate.
     * @param invalidatedAt Authoritative invalidation instant shared with persistence.
     * @return Revision produced by the invalidation command.
     */
    suspend fun invalidate(
        key: EncodedCacheKey,
        invalidatedAt: Instant = clock.now(),
    ): Long = mutex.withLock {
        val revision = advanceRevision(key)
        val entry = state.value[key] ?: return@withLock revision
        val currentMetadata = entry.snapshot.metadata
        if (entry.snapshot.value == null || currentMetadata == null) {
            publish(state.value - key)
            return@withLock revision
        }
        val metadata = currentMetadata.copy(
            invalidatedAt = invalidatedAt,
        )
        val updated = entry.copy(
            snapshot = entry.snapshot.copy(
                freshness = CacheFreshness.STALE,
                activity = CacheActivity.IDLE,
                metadata = metadata,
                refreshError = null,
            ),
            lastAccessSequence = nextAccessSequence(),
        )
        publish(enforceMemoryLimit(state.value + (key to updated), protectedKey = key))
        revision
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
        revision
    }

    /** Atomically invalidates all known addresses in one exact partitioned family. */
    suspend fun invalidateFamily(
        family: EncodedCacheFamily,
        invalidatedAt: Instant = clock.now(),
    ): Int = mutex.withLock {
        familyKeys(family).forEach(::advanceRevision)
        var affected = 0
        val updated = state.value.mapNotNull { (key, entry) ->
            if (!key.belongsTo(family)) return@mapNotNull key to entry
            val metadata = entry.snapshot.metadata
            if (entry.snapshot.value == null || metadata == null) return@mapNotNull null
            affected += 1
            key to entry.copy(
                snapshot = entry.snapshot.copy(
                    freshness = CacheFreshness.STALE,
                    activity = CacheActivity.IDLE,
                    metadata = metadata.copy(invalidatedAt = invalidatedAt),
                    refreshError = null,
                ),
                lastAccessSequence = nextAccessSequence(),
            )
        }.toMap()
        publish(updated)
        affected
    }

    /** Atomically removes all retained entries and supersedes active work in one family. */
    suspend fun removeFamily(family: EncodedCacheFamily): Int = mutex.withLock {
        familyKeys(family).forEach(::advanceRevision)
        val affected = state.value.keys.count { key -> key.belongsTo(family) }
        publish(state.value.filterKeys { key -> !key.belongsTo(family) })
        affected
    }

    /**
     * Removes every entry belonging to [partition].
     *
     * @param partition Isolation boundary to clear.
     */
    override suspend fun clearPartition(partition: CachePartition) {
        mutex.withLock {
            revisions.keys
                .filter { key -> key.partition == partition }
                .forEach(::advanceRevision)
            publish(state.value.filterKeys { key -> key.partition != partition })
        }
    }

    /** Removes every entry in this namespace and publishes absent state to observers. */
    override suspend fun clearAll() {
        mutex.withLock {
            revisions.keys.toList().forEach(::advanceRevision)
            publish(emptyMap())
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
        val hydrating = existing?.copy(
            snapshot = existing.snapshot.copy(
                activity = CacheActivity.HYDRATING,
                refreshError = null,
            ),
            lastAccessSequence = nextAccessSequence(),
        ) ?: StoredCacheEntry(
            snapshot = CacheSnapshot<V, E>(activity = CacheActivity.HYDRATING),
            lastAccessSequence = nextAccessSequence(),
        )
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
        val entry = StoredCacheEntry<V, E>(
            snapshot = CacheSnapshot<V, E>(
                value = value,
                freshness = freshnessAt(metadata, clock.now()),
                activity = CacheActivity.IDLE,
                metadata = metadata,
            ),
            lastAccessSequence = nextAccessSequence(),
        )
        publish(enforceMemoryLimit(state.value + (key to entry), protectedKey = key))
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
        val entry = StoredCacheEntry<V, E>(
            snapshot = CacheSnapshot(),
            lastAccessSequence = nextAccessSequence(),
        )
        publish(enforceMemoryLimit(state.value + (key to entry), protectedKey = key))
        true
    }

    /**
     * Clears hydration activity after cancellation when this generation is still current.
     *
     * @param key Encoded resource identity whose persistent read was cancelled.
     * @param expectedRevision Revision captured by [beginHydration].
     */
    suspend fun cancelHydration(key: EncodedCacheKey, expectedRevision: Long) {
        mutex.withLock {
            if (revisionOf(key) != expectedRevision) return@withLock
            revisions[key] = expectedRevision + 1
            val existing = state.value[key] ?: return@withLock
            if (existing.snapshot.value == null) {
                publish(state.value - key)
            } else {
                publish(
                    state.value + (
                        key to existing.copy(
                            snapshot = existing.snapshot.copy(activity = CacheActivity.IDLE)
                        )
                    )
                )
            }
        }
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
        val refreshing = existing?.copy(
            snapshot = existing.snapshot.copy(
                activity = CacheActivity.REFRESHING,
                refreshError = null,
            ),
            lastAccessSequence = nextAccessSequence(),
        ) ?: StoredCacheEntry(
            snapshot = CacheSnapshot<V, E>(activity = CacheActivity.REFRESHING),
            lastAccessSequence = nextAccessSequence(),
        )
        publish(enforceMemoryLimit(state.value + (key to refreshing), protectedKey = key))
        RefreshStart(
            revision = revision,
            staleValue = refreshing.snapshot.value,
        )
    }

    /**
     * Commits a successful source [value] only when [expectedRevision] is still authoritative.
     *
     * @param key Encoded resource identity receiving the loaded value.
     * @param expectedRevision Revision captured by [beginRefresh].
     * @param value Successfully loaded value.
     * @return Committed revision and snapshot, or `null` when a newer mutation won.
     */
    suspend fun completeRefreshSuccess(
        key: EncodedCacheKey,
        expectedRevision: Long,
        value: V,
    ): MemoryCommit<V, E>? = mutex.withLock {
        if (revisionOf(key) != expectedRevision) return@withLock null
        val revision = expectedRevision + 1
        revisions[key] = revision
        val validatedAt = clock.now()
        val metadata = CacheMetadata(
            validatedAt = validatedAt,
            expiresAt = expirationAt(freshnessPolicy, validatedAt),
        )
        val entry = StoredCacheEntry<V, E>(
            snapshot = CacheSnapshot<V, E>(
                value = value,
                freshness = freshnessAt(metadata, validatedAt),
                activity = CacheActivity.IDLE,
                metadata = metadata,
            ),
            lastAccessSequence = nextAccessSequence(),
        )
        publish(enforceMemoryLimit(state.value + (key to entry), protectedKey = key))
        MemoryCommit(revision = revision, snapshot = entry.snapshot)
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
        val failed = existing?.copy(
            snapshot = existing.snapshot.copy(
                activity = CacheActivity.IDLE,
                refreshError = error,
            ),
            lastAccessSequence = nextAccessSequence(),
        ) ?: StoredCacheEntry(
            snapshot = CacheSnapshot<V, E>(refreshError = error),
            lastAccessSequence = nextAccessSequence(),
        )
        publish(enforceMemoryLimit(state.value + (key to failed), protectedKey = key))
        true
    }

    /**
     * Clears refresh activity after manager cancellation when this generation is still current.
     *
     * @param key Encoded resource identity whose work was cancelled.
     * @param expectedRevision Revision captured by [beginRefresh].
     */
    suspend fun cancelRefresh(key: EncodedCacheKey, expectedRevision: Long) {
        mutex.withLock {
            if (revisionOf(key) != expectedRevision) return@withLock
            revisions[key] = expectedRevision + 1
            val existing = state.value[key] ?: return@withLock
            if (existing.snapshot.value == null) {
                publish(state.value - key)
            } else {
                publish(
                    state.value + (
                        key to existing.copy(
                            snapshot = existing.snapshot.copy(
                                activity = CacheActivity.IDLE,
                                refreshError = null,
                            )
                        )
                    )
                )
            }
        }
    }

    /**
     * Pins [key] while manager-owned hydration or refresh work is active.
     *
     * @param key Encoded resource identity protected from eviction.
     */
    suspend fun pinOperation(key: EncodedCacheKey) {
        mutex.withLock {
            operationPins[key] = operationPins.getOrElse(key) { 0 } + 1
            touchAndEnforce(key)
        }
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
            if (operationPins[key] == null && state.value[key] == null) {
                revisions.remove(key)
            }
        }
    }

    /**
     * Counts all retained entries, including bounded hydrated-absence markers.
     *
     * @return Current number of entries participating in the memory bound.
     */
    override suspend fun entryCount(): Int = mutex.withLock { state.value.size }

    /**
     * Checks whether [key] has retained state, including a hydrated-absence marker.
     *
     * @param key Encoded address to inspect.
     * @return `true` when the address currently participates in memory retention.
     */
    suspend fun contains(key: EncodedCacheKey): Boolean = mutex.withLock { key in state.value }

    /**
     * Checks whether [expectedRevision] still authorizes a persistent operation for [key].
     *
     * @param key Encoded address whose revision is inspected.
     * @param expectedRevision Revision captured by the pending operation.
     * @return `true` when no newer mutation has superseded the operation.
     */
    suspend fun isRevisionCurrent(key: EncodedCacheKey, expectedRevision: Long): Boolean =
        mutex.withLock { revisionOf(key) == expectedRevision }

    /**
     * Increments the observer pin count, evaluates freshness, and records an access.
     *
     * @param key Encoded resource identity whose observer attached.
     */
    private suspend fun pinObserver(key: EncodedCacheKey) {
        mutex.withLock {
            observerPins[key] = observerPins.getOrElse(key) { 0 } + 1
            touchAndEnforce(key)
        }
    }

    /**
     * Decrements the observer pin count and enforces any eviction deferred while observed.
     *
     * @param key Encoded resource identity whose observer detached.
     */
    private suspend fun unpinObserver(key: EncodedCacheKey) {
        mutex.withLock {
            decrementPin(observerPins, key)
            publish(enforceMemoryLimit(state.value))
        }
    }

    /**
     * Evaluates and touches [key] when present, then enforces the memory bound.
     *
     * @param key Encoded resource identity to access.
     */
    private fun touchAndEnforce(key: EncodedCacheKey) {
        val entry = state.value[key]
        val updated = if (entry == null) {
            state.value
        } else {
            val accessed = entry
                .evaluateAt(clock.now())
                .copy(lastAccessSequence = nextAccessSequence())
            state.value + (key to accessed)
        }
        publish(enforceMemoryLimit(updated))
    }

    /**
     * Returns this entry with freshness evaluated at [now].
     *
     * @receiver Stored entry to evaluate.
     * @param now Current instant supplied by the cache clock.
     * @return Unchanged entry or a copy containing the new freshness.
     */
    private fun StoredCacheEntry<V, E>.evaluateAt(now: Instant): StoredCacheEntry<V, E> {
        val metadata = snapshot.metadata ?: return this
        val evaluatedFreshness = freshnessAt(metadata, now)
        return if (snapshot.freshness == evaluatedFreshness) {
            this
        } else {
            copy(snapshot = snapshot.copy(freshness = evaluatedFreshness))
        }
    }

    /**
     * Removes least-recently-used unpinned entries until [entries] satisfies the bound.
     *
     * @param entries Candidate state to bound.
     * @param protectedKey Key temporarily protected by the committing operation.
     * @return Bounded state, or the smallest possible state when every candidate is pinned.
     */
    private fun enforceMemoryLimit(
        entries: Map<EncodedCacheKey, StoredCacheEntry<V, E>>,
        protectedKey: EncodedCacheKey? = null,
    ): Map<EncodedCacheKey, StoredCacheEntry<V, E>> {
        var bounded = entries
        while (bounded.size > memoryPolicy.maxEntries) {
            val candidate = bounded
                .filter { (key, entry) -> key != protectedKey && !isPinned(key, entry) }
                .minWithOrNull(
                    compareBy<Map.Entry<EncodedCacheKey, StoredCacheEntry<V, E>>> {
                        it.value.lastAccessSequence
                    }.thenBy { it.key.partition.value }
                        .thenBy { it.key.value }
                )
                ?.key
                ?: break
            bounded = bounded - candidate
            onEviction(candidate, bounded.size, memoryPolicy.maxEntries)
        }
        return bounded
    }

    /**
     * Returns whether [key] is protected by observation, active work, or snapshot activity.
     *
     * @param key Encoded identity whose pin counts are inspected.
     * @param entry Stored entry whose activity is inspected.
     * @return `true` when eviction must be deferred.
     */
    private fun isPinned(
        key: EncodedCacheKey,
        entry: StoredCacheEntry<V, E>,
    ): Boolean =
        observerPins.getOrElse(key) { 0 } > 0 ||
            operationPins.getOrElse(key) { 0 } > 0 ||
            entry.snapshot.activity != CacheActivity.IDLE

    /**
     * Decrements [pins] for [key] without retaining zero-count bookkeeping.
     *
     * @param pins Pin-count map to update.
     * @param key Encoded identity whose pin is released.
     */
    private fun decrementPin(pins: MutableMap<EncodedCacheKey, Int>, key: EncodedCacheKey) {
        val remaining = pins.getOrElse(key) { 0 } - 1
        if (remaining > 0) pins[key] = remaining else pins.remove(key)
    }

    /**
     * Returns the current revision for [key], creating its initial generation when necessary.
     *
     * @param key Encoded address whose generation is requested.
     * @return Current monotonically increasing address revision.
     */
    private fun revisionOf(key: EncodedCacheKey): Long = revisions.getOrPut(key) { 0L }

    /**
     * Advances the authoritative revision for [key].
     *
     * @param key Encoded address superseded by a new mutation.
     * @return Newly committed address revision.
     */
    private fun advanceRevision(key: EncodedCacheKey): Long {
        val next = revisionOf(key) + 1
        revisions[key] = next
        return next
    }

    /** Returns every retained or active-work address covered by [family]. */
    private fun familyKeys(family: EncodedCacheFamily): Set<EncodedCacheKey> =
        (revisions.keys + state.value.keys + observerPins.keys + operationPins.keys)
            .filterTo(mutableSetOf()) { key -> key.belongsTo(family) }

    /** Checks exact partition and normalized family metadata without prefix matching. */
    private fun EncodedCacheKey.belongsTo(family: EncodedCacheFamily): Boolean =
        partition == family.partition && this.family == family.value

    /**
     * Advances the resource-local LRU access sequence.
     *
     * @return Next monotonically increasing access sequence.
     */
    private fun nextAccessSequence(): Long {
        accessSequence += 1
        return accessSequence
    }

    /**
     * Publishes [entries] as one complete atomic state transition.
     *
     * @param entries Complete replacement state.
     */
    private fun publish(entries: Map<EncodedCacheKey, StoredCacheEntry<V, E>>) {
        state.value = entries
    }
}
