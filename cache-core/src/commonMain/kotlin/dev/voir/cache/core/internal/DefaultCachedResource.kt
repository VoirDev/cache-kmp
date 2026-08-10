package dev.voir.cache.core.internal

import dev.voir.cache.core.CachedResource
import dev.voir.cache.core.config.CacheManagerConfig
import dev.voir.cache.core.config.CacheSpec
import dev.voir.cache.core.diagnostics.CacheEventType
import dev.voir.cache.core.diagnostics.CacheEvictionCause
import dev.voir.cache.core.diagnostics.CacheOperationKind
import dev.voir.cache.core.diagnostics.CacheOperationOrigin
import dev.voir.cache.core.diagnostics.CacheOperationOutcome
import dev.voir.cache.core.diagnostics.CachePersistenceOperation
import dev.voir.cache.core.diagnostics.CacheSingleFlightStatus
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.key.EncodedCacheFamily
import dev.voir.cache.core.memory.InMemoryResourceStore
import dev.voir.cache.core.memory.MemoryCommit
import dev.voir.cache.core.model.CacheDiscarded
import dev.voir.cache.core.model.CacheFailure
import dev.voir.cache.core.model.CacheFreshness
import dev.voir.cache.core.model.CacheFamilyMutationResult
import dev.voir.cache.core.model.CachePersistenceStatus
import dev.voir.cache.core.model.CacheResult
import dev.voir.cache.core.model.CacheSnapshot
import dev.voir.cache.core.model.CacheSuccess
import dev.voir.cache.core.model.FetchFailure
import dev.voir.cache.core.model.FetchResult
import dev.voir.cache.core.model.FetchSuccess
import dev.voir.cache.core.persistence.CacheAddress
import dev.voir.cache.core.persistence.ExperimentalCacheStoreApi
import dev.voir.cache.core.persistence.PersistedCacheRecord
import dev.voir.cache.core.storage.MemoryOnlyStorage
import dev.voir.cache.core.storage.PersistentStorage
import dev.voir.cache.core.time.CacheClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Typed resource implementation coordinating memory, persistence, and one bound source loader.
 *
 * @param K Domain-owned resource key type.
 * @param V Non-null cached value type.
 * @param E Application-defined source error type.
 * @property spec Immutable resource policies and address encoder.
 * @property fetch Source loader permanently bound to this resource.
 * @property mapUnexpectedFailure Mapper for non-cancellation loader exceptions.
 * @property store Atomic observable memory state owned by this resource.
 * @property scope Manager-owned scope used for hydration and refresh work.
 * @property lifecycle Manager command-admission and shutdown coordinator.
 * @property events Isolated structured diagnostic emitter.
 * @property config Manager-wide encoded-address validation limits.
 * @property clock Time source used for persistence and invalidation timestamps.
 * @property persistent Optional manager-wide persistent operation coordinator.
 * @property persistentClears Session tracker preventing stale restoration after failed mutations.
 */
@OptIn(ExperimentalCacheStoreApi::class)
internal class DefaultCachedResource<K : Any, V : Any, E : Any>(
    private val spec: CacheSpec<K, V>,
    private val fetch: suspend (K) -> FetchResult<V, E>,
    private val mapUnexpectedFailure: (Throwable) -> E,
    private val store: InMemoryResourceStore<V, E>,
    private val scope: CoroutineScope,
    private val lifecycle: CommandLifecycle,
    private val events: CacheEventEmitter,
    private val config: CacheManagerConfig,
    private val clock: CacheClock,
    private val persistent: PersistentStoreCoordinator?,
    private val persistentClears: PersistentClearTracker,
    private val familyForKey: ((K) -> EncodedCacheFamily)? = null,
) : CachedResource<K, V, E> {
    private val refreshMutex = Mutex()
    private val refreshes = mutableMapOf<EncodedCacheKey, Deferred<CacheResult<V, E>>>()
    private val refreshWaiters = mutableMapOf<EncodedCacheKey, Int>()
    private val hydrationMutex = Mutex()
    private val hydrations = mutableMapOf<EncodedCacheKey, Deferred<Unit>>()
    private val persistentStorage: PersistentStorage<V>? = when (val storage = spec.storage) {
        is MemoryOnlyStorage -> null
        is PersistentStorage -> storage
    }

    /**
     * Observes current memory immediately and starts lazy hydration without invoking the source.
     *
     * @param key Domain key to observe.
     * @return Flow of distinct memory snapshots for the encoded address.
     */
    override fun observe(key: K): Flow<CacheSnapshot<V, E>> {
        lifecycle.ensureOpen()
        val encodedKey = encode(key)
        return flow {
            lifecycle.ensureOpen()
            val initial = store.current(encodedKey)
            emit(initial)
            startHydration(encodedKey)
            emitAll(store.observe(encodedKey).dropWhile { snapshot -> snapshot == initial })
        }
    }

    /**
     * Returns process-local memory state without starting or awaiting persistent hydration.
     *
     * @param key Domain key to inspect.
     * @return Immediate freshness-evaluated memory snapshot.
     */
    override suspend fun peek(key: K): CacheSnapshot<V, E> = lifecycle.runCommand {
        val encodedKey = encode(key)
        val operation = events.start(
            CacheOperationKind.PEEK,
            spec.namespace,
            encodedKey,
            CacheOperationOrigin.MEMORY,
        )
        store.current(encodedKey).also { snapshot ->
            emitLookup(snapshot, encodedKey)
            completeWithOccupancy(operation, snapshot.outcome())
        }
    }

    /**
     * Awaits lazy hydration and returns current freshness-evaluated memory state.
     *
     * @param key Domain key to read without source loading.
     * @return Current snapshot after any configured hydration completes.
     */
    override suspend fun current(key: K): CacheSnapshot<V, E> = lifecycle.runCommand {
        val encodedKey = encode(key)
        val operation = events.start(
            CacheOperationKind.CURRENT,
            spec.namespace,
            encodedKey,
            CacheOperationOrigin.HYDRATION,
        )
        awaitHydration(encodedKey)
        store.current(encodedKey).also { snapshot ->
            emitLookup(snapshot, encodedKey)
            completeWithOccupancy(operation, snapshot.outcome())
        }
    }

    /**
     * Returns fresh data or joins one refresh after persistent hydration.
     *
     * @param key Domain key to resolve.
     * @return Successful cached value or typed source failure with optional stale data.
     */
    override suspend fun get(key: K): CacheResult<V, E> = lifecycle.runCommand {
        val encodedKey = encode(key)
        awaitHydration(encodedKey)
        val snapshot = store.current(encodedKey)
        emitLookup(snapshot, encodedKey)
        if (snapshot.freshness == CacheFreshness.FRESH) {
            events.emit(CacheEventType.FRESH_SKIP, spec.namespace, encodedKey)
            val operation = events.start(
                CacheOperationKind.GET,
                spec.namespace,
                encodedKey,
                CacheOperationOrigin.MEMORY,
            )
            CacheSuccess(checkNotNull(snapshot.value), CacheFreshness.FRESH).also {
                completeWithOccupancy(operation, CacheOperationOutcome.FRESH_SKIP)
            }
        } else {
            val origin = if (snapshot.value == null) {
                CacheOperationOrigin.MISS_LOAD
            } else {
                CacheOperationOrigin.STALE_LOAD
            }
            if (snapshot.value == null) {
                events.emit(CacheEventType.MISS, spec.namespace, encodedKey)
            } else {
                events.emit(CacheEventType.STALE_REFRESH, spec.namespace, encodedKey)
            }
            joinOrStartRefresh(key, encodedKey, CacheOperationKind.GET, origin)
        }
    }

    /**
     * Forces or joins one source refresh after persistent hydration.
     *
     * @param key Domain key to refresh regardless of freshness.
     * @return Successful source value or typed source failure with optional stale data.
     */
    override suspend fun refresh(key: K): CacheResult<V, E> = lifecycle.runCommand {
        val encodedKey = encode(key)
        awaitHydration(encodedKey)
        events.emit(CacheEventType.FORCED_REFRESH, spec.namespace, encodedKey)
        joinOrStartRefresh(
            key,
            encodedKey,
            CacheOperationKind.REFRESH,
            CacheOperationOrigin.FORCED_REFRESH,
        )
    }

    /**
     * Publishes [value] to memory before attempting a matching persistent write.
     *
     * @param key Domain key receiving the authoritative value.
     * @param value Non-null value to validate and store.
     */
    override suspend fun put(key: K, value: V) {
        lifecycle.runCommand {
            val encodedKey = encode(key)
            val operation = events.start(
                CacheOperationKind.PUT,
                spec.namespace,
                encodedKey,
                CacheOperationOrigin.EXPLICIT,
            )
            val commit = store.put(encodedKey, value)
            markSessionAuthoritative(encodedKey)
            val persisted = persistCommit(encodedKey, commit)
            if (persisted) markPersisted(encodedKey)
            completeWithOccupancy(operation, persistenceOutcome(persisted))
        }
    }

    /**
     * Applies [transform] atomically and persists the resulting snapshot when present.
     *
     * @param key Domain key whose existing value should change.
     * @param transform Non-suspending transformation executed under the memory lock.
     * @return `true` when an existing value was updated.
     */
    override suspend fun update(key: K, transform: (V) -> V): Boolean = lifecycle.runCommand {
        val encodedKey = encode(key)
        val operation = events.start(
            CacheOperationKind.UPDATE,
            spec.namespace,
            encodedKey,
            CacheOperationOrigin.EXPLICIT,
        )
        awaitHydration(encodedKey)
        val commit = store.updateAndCapture(encodedKey, transform) ?: return@runCommand false.also {
            completeWithOccupancy(operation, CacheOperationOutcome.ABSENT)
        }
        markSessionAuthoritative(encodedKey)
        val persisted = persistCommit(encodedKey, commit)
        if (persisted) markPersisted(encodedKey)
        completeWithOccupancy(operation, persistenceOutcome(persisted))
        true
    }

    /**
     * Marks [key] stale in memory before best-effort persistent invalidation.
     *
     * @param key Domain key to invalidate without deleting its value.
     */
    override suspend fun invalidate(key: K) {
        lifecycle.runCommand {
            val encodedKey = encode(key)
            val operation = events.start(
                CacheOperationKind.INVALIDATE,
                spec.namespace,
                encodedKey,
                CacheOperationOrigin.EXPLICIT,
            )
            awaitHydration(encodedKey)
            val invalidatedAt = clock.now()
            val revision = store.invalidate(encodedKey, invalidatedAt)
            markSessionAuthoritative(encodedKey)
            val retainedValue = store.current(encodedKey).value != null
            val persisted = persistMutation(
                encodedKey,
                revision,
                CachePersistenceOperation.INVALIDATE,
            ) { backend, address ->
                backend.invalidate(address, invalidatedAt)
            } == PersistenceMutationOutcome.COMMITTED
            if (persisted && retainedValue) markPersisted(encodedKey)
            events.emit(CacheEventType.INVALIDATION, spec.namespace, encodedKey)
            completeWithOccupancy(operation, persistenceOutcome(persisted))
        }
    }

    /**
     * Removes [key] from memory before best-effort persistent deletion.
     *
     * @param key Domain key whose exact address should be removed.
     */
    override suspend fun remove(key: K) {
        lifecycle.runCommand {
            val encodedKey = encode(key)
            val operation = events.start(
                CacheOperationKind.REMOVE,
                spec.namespace,
                encodedKey,
                CacheOperationOrigin.EXPLICIT,
            )
            val revision = store.remove(encodedKey)
            markSessionAuthoritative(encodedKey)
            val persisted = persistMutation(
                encodedKey,
                revision,
                CachePersistenceOperation.REMOVE,
            ) { backend, address ->
                backend.remove(address)
            } == PersistenceMutationOutcome.COMMITTED
            completeWithOccupancy(
                operation,
                persistenceOutcome(persisted),
                evictionCause = CacheEvictionCause.EXPLICIT_REMOVAL,
            )
        }
    }

    /** Clears this namespace from memory before best-effort persistent deletion. */
    override suspend fun clear() {
        lifecycle.runCommand {
            val operation = events.start(
                CacheOperationKind.CLEAR_NAMESPACE,
                spec.namespace,
                origin = CacheOperationOrigin.EXPLICIT,
                persistenceOperation = CachePersistenceOperation.CLEAR_NAMESPACE,
            )
            var degraded = false
            val coordinator = persistent
            if (persistentStorage == null || coordinator == null) {
                store.clearAll()
            } else {
                coordinator.ordered { backend ->
                    persistentClears.markNamespace(spec.namespace)
                    store.clearAll()
                    try {
                        backend.clearNamespace(spec.namespace)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        degraded = true
                        emitPersistentFailure()
                    }
                }
            }
            completeWithOccupancy(
                operation,
                if (degraded) {
                    CacheOperationOutcome.PERSISTENCE_DEGRADED_SUCCESS
                } else {
                    CacheOperationOutcome.COMMITTED
                },
            )
        }
    }

    /** Marks every retained and persistent value in one encoded family stale. */
    internal suspend fun invalidateEncodedFamily(
        family: EncodedCacheFamily,
    ): CacheFamilyMutationResult = lifecycle.runCommand {
        validateFamily(family)
        val operation = events.start(
            CacheOperationKind.INVALIDATE_FAMILY,
            spec.namespace,
            origin = CacheOperationOrigin.EXPLICIT,
            persistenceOperation = CachePersistenceOperation.INVALIDATE_FAMILY,
        )
        val invalidatedAt = clock.now()
        var memoryAffected = 0
        var persistentAffected: Long? = 0
        var status = CachePersistenceStatus.NOT_CONFIGURED
        val coordinator = persistent
        if (persistentStorage == null || coordinator == null) {
            memoryAffected = store.invalidateFamily(family, invalidatedAt)
        } else {
            status = CachePersistenceStatus.AVAILABLE
            try {
                coordinator.ordered { backend ->
                    persistentClears.markFamily(spec.namespace, family)
                    memoryAffected = store.invalidateFamily(family, invalidatedAt)
                    persistentAffected = backend.invalidateFamily(
                        spec.namespace,
                        family,
                        invalidatedAt,
                    )
                    persistentClears.markFamilyAvailable(spec.namespace, family)
                }
            } catch (cancellation: CancellationException) {
                completeWithOccupancy(operation, CacheOperationOutcome.CANCELLED)
                throw cancellation
            } catch (_: Throwable) {
                status = CachePersistenceStatus.UNAVAILABLE
                persistentAffected = null
                emitPersistentFailure()
            }
        }
        CacheFamilyMutationResult(memoryAffected, persistentAffected, status).also {
            events.emit(CacheEventType.INVALIDATION, spec.namespace)
            completeWithOccupancy(
                operation,
                if (status == CachePersistenceStatus.UNAVAILABLE) {
                    CacheOperationOutcome.PERSISTENCE_DEGRADED_SUCCESS
                } else {
                    CacheOperationOutcome.COMMITTED
                },
                affectedCount = persistentAffected
                    ?.let { rows -> maxOf(rows, memoryAffected.toLong()) }
                    ?: memoryAffected.toLong(),
            )
        }
    }

    /** Removes every retained and persistent value in one encoded family. */
    internal suspend fun removeEncodedFamily(
        family: EncodedCacheFamily,
    ): CacheFamilyMutationResult = lifecycle.runCommand {
        validateFamily(family)
        val operation = events.start(
            CacheOperationKind.REMOVE_FAMILY,
            spec.namespace,
            origin = CacheOperationOrigin.EXPLICIT,
            persistenceOperation = CachePersistenceOperation.REMOVE_FAMILY,
        )
        var memoryAffected = 0
        var persistentAffected: Long? = 0
        var status = CachePersistenceStatus.NOT_CONFIGURED
        val coordinator = persistent
        if (persistentStorage == null || coordinator == null) {
            memoryAffected = store.removeFamily(family)
        } else {
            status = CachePersistenceStatus.AVAILABLE
            try {
                coordinator.ordered { backend ->
                    persistentClears.markFamily(spec.namespace, family)
                    memoryAffected = store.removeFamily(family)
                    persistentAffected = backend.removeFamily(spec.namespace, family)
                }
            } catch (cancellation: CancellationException) {
                completeWithOccupancy(operation, CacheOperationOutcome.CANCELLED)
                throw cancellation
            } catch (_: Throwable) {
                status = CachePersistenceStatus.UNAVAILABLE
                persistentAffected = null
                emitPersistentFailure()
            }
        }
        CacheFamilyMutationResult(memoryAffected, persistentAffected, status).also {
            completeWithOccupancy(
                operation,
                if (status == CachePersistenceStatus.UNAVAILABLE) {
                    CacheOperationOutcome.PERSISTENCE_DEGRADED_SUCCESS
                } else {
                    CacheOperationOutcome.COMMITTED
                },
                evictionCause = CacheEvictionCause.EXPLICIT_REMOVAL,
                affectedCount = persistentAffected
                    ?.let { rows -> maxOf(rows, memoryAffected.toLong()) }
                    ?: memoryAffected.toLong(),
            )
        }
    }

    /**
     * Starts or joins manager-owned persistent hydration without awaiting its result.
     *
     * @param key Encoded resource identity to hydrate.
     * @return Shared hydration handle, or `null` for a memory-only resource.
     */
    private suspend fun startHydration(key: EncodedCacheKey): Deferred<Unit>? {
        val coordinator = persistent ?: return null
        val storage = persistentStorage ?: return null
        val retained = store.contains(key)
        val suppressed = persistentClears.isCleared(address(key))
        return hydrationMutex.withLock {
            val existing = hydrations[key]
            if (existing != null && (!existing.isCompleted || retained || suppressed)) {
                return@withLock existing
            }
            scope.async {
                performHydration(key, storage, coordinator)
            }.also { created -> hydrations[key] = created }
        }
    }

    /**
     * Awaits one-time persistent hydration when this resource uses persistent storage.
     *
     * @param key Encoded resource identity to hydrate before a command proceeds.
     */
    private suspend fun awaitHydration(key: EncodedCacheKey) {
        startHydration(key)?.await()
    }

    /**
     * Marks [key] session-authoritative so later reads cannot restore older persistent data.
     *
     * @param key Encoded address mutated in memory before persistence completes.
     */
    private suspend fun markSessionAuthoritative(key: EncodedCacheKey) {
        if (persistentStorage == null) return
        persistentClears.markAddress(address(key))
        hydrationMutex.withLock { hydrations[key] = CompletableDeferred(Unit) }
    }

    /**
     * Marks [key] durably available for rehydration after future memory eviction.
     *
     * @param key Encoded address whose persistent operation succeeded.
     */
    private suspend fun markPersisted(key: EncodedCacheKey) {
        if (persistentStorage == null) return
        persistentClears.markAvailable(address(key))
    }

    /**
     * Reads, validates, decodes, and revision-guards one persistent record.
     *
     * @param key Encoded resource identity being hydrated.
     * @param storage Persistent policy and codec for this resource.
     * @param coordinator Manager-wide persistent operation coordinator.
     */
    private suspend fun performHydration(
        key: EncodedCacheKey,
        storage: PersistentStorage<V>,
        coordinator: PersistentStoreCoordinator,
    ) {
        val operation = events.start(
            operation = CacheOperationKind.OBSERVE_HYDRATION,
            namespace = spec.namespace,
            key = key,
            origin = CacheOperationOrigin.HYDRATION,
            persistenceOperation = CachePersistenceOperation.READ,
        )
        val hydration = store.beginHydration(key)
        val address = address(key)
        var outcome = CacheOperationOutcome.ABSENT
        var evictionCause: CacheEvictionCause? = null
        var persistenceDuration: Duration? = null
        var codecDuration: Duration? = null
        try {
            if (persistentClears.isCleared(address)) {
                store.completeHydrationAbsent(key, hydration.revision)
                return
            }
            val persistenceStarted = TimeSource.Monotonic.markNow()
            val record = try {
                coordinator.ordered { backend -> backend.read(address) }.also {
                    persistenceDuration = persistenceStarted.elapsedNow()
                }
            } catch (cancellation: CancellationException) {
                persistenceDuration = persistenceStarted.elapsedNow()
                throw cancellation
            } catch (_: Throwable) {
                persistenceDuration = persistenceStarted.elapsedNow()
                outcome = CacheOperationOutcome.UNAVAILABLE
                emitPersistentFailure(key)
                store.completeHydrationAbsent(key, hydration.revision)
                return
            }
            if (record == null) {
                store.completeHydrationAbsent(key, hydration.revision)
                return
            }
            val codecStarted = TimeSource.Monotonic.markNow()
            val value = decodeRecord(record, address, storage).also {
                codecDuration = codecStarted.elapsedNow()
            } ?: run {
                outcome = CacheOperationOutcome.EVICTED
                evictionCause = CacheEvictionCause.CORRUPTION
                removeCorruptRecord(address, coordinator, key)
                store.completeHydrationAbsent(key, hydration.revision)
                return
            }
            val committed = store.completeHydrationSuccess(
                key = key,
                expectedRevision = hydration.revision,
                value = value,
                metadata = record.metadata,
            )
            if (committed) {
                outcome = CacheOperationOutcome.COMMITTED
                if (record.payloadVersion < storage.codec.currentVersion) {
                    rewriteMigratedRecord(
                        key = key,
                        expectedRevision = hydration.revision + 1,
                        record = record,
                        value = value,
                        storage = storage,
                        coordinator = coordinator,
                    )
                }
                events.emit(CacheEventType.PERSISTENT_HIT, spec.namespace, key)
            } else {
                outcome = CacheOperationOutcome.DISCARDED
                emitDiscardedResult(key)
            }
        } catch (cancellation: CancellationException) {
            outcome = CacheOperationOutcome.CANCELLED
            withContext(NonCancellable) {
                store.cancelHydration(key, hydration.revision)
            }
            throw cancellation
        } finally {
            withContext(NonCancellable) {
                store.unpinOperation(key)
                completeWithOccupancy(
                    operation = operation,
                    outcome = outcome,
                    persistenceDuration = persistenceDuration,
                    codecDuration = codecDuration,
                    evictionCause = evictionCause,
                )
            }
        }
    }

    /**
     * Validates and decodes one opaque persistent record.
     *
     * @param record Record returned by the configured backend.
     * @param expectedAddress Exact address requested by hydration.
     * @param storage Persistent policy supplying codec and payload-size limit.
     * @return Decoded value, or `null` when the record is corrupt or invalid.
     */
    private fun decodeRecord(
        record: PersistedCacheRecord,
        expectedAddress: CacheAddress,
        storage: PersistentStorage<V>,
    ): V? {
        if (
            record.address != expectedAddress ||
            record.payload.size > storage.maxPayloadBytes ||
            record.payloadVersion > storage.codec.currentVersion
        ) {
            return null
        }
        return try {
            storage.codec.decode(record.payload, record.payloadVersion)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Re-encodes one successfully migrated value using the codec's current payload version.
     *
     * The rewrite preserves validation and retention metadata and executes only while the
     * hydration revision remains current, preventing it from overwriting a newer mutation.
     *
     * @param key Encoded resource identity whose historical payload was decoded.
     * @param expectedRevision Memory revision authorizing the best-effort rewrite.
     * @param record Historical persistent record whose metadata is preserved.
     * @param value Migrated current value to encode.
     * @param storage Persistent policy supplying codec and payload-size limit.
     * @param coordinator Manager-wide persistent operation coordinator.
     */
    private suspend fun rewriteMigratedRecord(
        key: EncodedCacheKey,
        expectedRevision: Long,
        record: PersistedCacheRecord,
        value: V,
        storage: PersistentStorage<V>,
        coordinator: PersistentStoreCoordinator,
    ) {
        val payload = try {
            storage.codec.encode(value)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            emitPersistentFailure(key)
            return
        }
        if (payload.size > storage.maxPayloadBytes) {
            emitPersistentFailure(key)
            return
        }
        val rewritten = record.copy(
            payload = payload,
            payloadVersion = storage.codec.currentVersion,
        )
        try {
            coordinator.ordered { backend ->
                if (store.isRevisionCurrent(key, expectedRevision)) {
                    backend.write(rewritten)
                } else {
                    emitDiscardedResult(key)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            emitPersistentFailure(key)
        }
    }

    /**
     * Removes one corrupt row best-effort and emits structured diagnostics.
     *
     * @param address Exact corrupt record address.
     * @param coordinator Manager-wide persistent operation coordinator.
     * @param key Encoded key used only for non-sensitive event fingerprinting.
     */
    private suspend fun removeCorruptRecord(
        address: CacheAddress,
        coordinator: PersistentStoreCoordinator,
        key: EncodedCacheKey,
    ) {
        events.emit(CacheEventType.CORRUPTION_EVICTION, spec.namespace, key)
        try {
            coordinator.ordered { backend -> backend.remove(address) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            emitPersistentFailure(key)
        }
    }

    /**
     * Joins existing source work for [encodedKey] or starts one manager-owned refresh.
     *
     * @param key Domain key supplied to the loader when new work starts.
     * @param encodedKey Stable address used for single-flight identity.
     * @return Result shared by every waiter for the address.
     */
    private suspend fun joinOrStartRefresh(
        key: K,
        encodedKey: EncodedCacheKey,
        operationKind: CacheOperationKind,
        origin: CacheOperationOrigin,
    ): CacheResult<V, E> {
        val (deferred, joined, waiterCount) = refreshMutex.withLock {
            val existing = refreshes[encodedKey]
            if (existing != null) {
                val waiters = refreshWaiters.getOrElse(encodedKey) { 1 } + 1
                refreshWaiters[encodedKey] = waiters
                Triple(existing, true, waiters)
            } else {
                val operation = events.start(
                    operation = operationKind,
                    namespace = spec.namespace,
                    key = encodedKey,
                    origin = origin,
                    singleFlightStatus = CacheSingleFlightStatus.STARTED,
                    waiterCount = 1,
                )
                val created = scope.async {
                    try {
                        performRefresh(key, encodedKey, operation)
                    } finally {
                        withContext(NonCancellable) {
                            refreshMutex.withLock {
                                refreshes.remove(encodedKey)
                                refreshWaiters.remove(encodedKey)
                            }
                        }
                    }
                }
                refreshes[encodedKey] = created
                refreshWaiters[encodedKey] = 1
                Triple(created, false, 1)
            }
        }
        if (joined) {
            events.emit(CacheEventType.SINGLE_FLIGHT_JOIN, spec.namespace, encodedKey)
            val joinedOperation = events.start(
                operation = operationKind,
                namespace = spec.namespace,
                key = encodedKey,
                origin = CacheOperationOrigin.JOINED_WORK,
                singleFlightStatus = CacheSingleFlightStatus.JOINED,
                waiterCount = waiterCount,
            )
            return try {
                deferred.await().also { result ->
                    completeWithOccupancy(
                        joinedOperation,
                        result.outcome(),
                        singleFlightStatus = CacheSingleFlightStatus.JOINED,
                        waiterCount = waiterCount,
                    )
                }
            } catch (cancellation: CancellationException) {
                completeWithOccupancy(
                    joinedOperation,
                    CacheOperationOutcome.CANCELLED,
                    singleFlightStatus = CacheSingleFlightStatus.JOINED,
                    waiterCount = waiterCount,
                )
                throw cancellation
            }
        }
        return deferred.await()
    }

    /**
     * Executes one revision-guarded source refresh outside the memory state lock.
     *
     * @param key Domain key supplied to the bound source loader.
     * @param encodedKey Stable address receiving the guarded result.
     * @return Source outcome represented through cache operation semantics.
     */
    private suspend fun performRefresh(
        key: K,
        encodedKey: EncodedCacheKey,
        operation: CacheOperationToken,
    ): CacheResult<V, E> {
        val refresh = store.beginRefresh(encodedKey)
        val sourceStarted = TimeSource.Monotonic.markNow()
        val result = try {
            val sourceResult = loadFromSource(key)
            val sourceDuration = sourceStarted.elapsedNow()
            val (cacheResult, terminalOutcome) = when (sourceResult) {
                is FetchSuccess -> {
                    val commit = store.completeRefreshSuccess(
                        key = encodedKey,
                        expectedRevision = refresh.revision,
                        value = sourceResult.value,
                    )
                    if (commit == null) {
                        emitDiscardedResult(encodedKey)
                        CacheDiscarded(
                            currentSnapshot = store.current(encodedKey),
                        ) to CacheOperationOutcome.DISCARDED
                    } else {
                        markSessionAuthoritative(encodedKey)
                        val persisted = persistCommit(encodedKey, commit)
                        if (persisted) markPersisted(encodedKey)
                        CacheSuccess(sourceResult.value, commit.snapshot.freshness) to
                            persistenceOutcome(persisted)
                    }
                }

                is FetchFailure -> {
                    val committed = store.completeRefreshFailure(
                        key = encodedKey,
                        expectedRevision = refresh.revision,
                        error = sourceResult.error,
                    )
                    if (committed) {
                        CacheFailure(sourceResult.error, refresh.staleValue) to
                            CacheOperationOutcome.SOURCE_FAILURE
                    } else {
                        emitDiscardedResult(encodedKey)
                        CacheDiscarded(
                            currentSnapshot = store.current(encodedKey),
                            sourceError = sourceResult.error,
                        ) to CacheOperationOutcome.DISCARDED
                    }
                }
            }
            withContext(NonCancellable) {
                store.unpinOperation(encodedKey)
                completeWithOccupancy(
                    operation,
                    terminalOutcome,
                    sourceDuration = sourceDuration,
                    singleFlightStatus = CacheSingleFlightStatus.STARTED,
                    waiterCount = 1,
                )
            }
            cacheResult
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                store.cancelRefresh(encodedKey, refresh.revision)
                store.unpinOperation(encodedKey)
                completeWithOccupancy(
                    operation,
                    CacheOperationOutcome.CANCELLED,
                    sourceDuration = sourceStarted.elapsedNow(),
                    singleFlightStatus = CacheSingleFlightStatus.STARTED,
                    waiterCount = 1,
                )
            }
            throw cancellation
        } catch (unexpected: Throwable) {
            withContext(NonCancellable) {
                store.cancelRefresh(encodedKey, refresh.revision)
                store.unpinOperation(encodedKey)
                completeWithOccupancy(
                    operation,
                    CacheOperationOutcome.UNAVAILABLE,
                    sourceDuration = sourceStarted.elapsedNow(),
                    singleFlightStatus = CacheSingleFlightStatus.STARTED,
                    waiterCount = 1,
                )
            }
            throw unexpected
        }
        return result
    }

    /**
     * Encodes and writes one committed memory snapshot without weakening source success.
     *
     * @param key Encoded address receiving the record.
     * @param commit Atomic memory commit containing revision, value, and metadata.
     * @return `true` when the corresponding persistent write succeeded.
     */
    private suspend fun persistCommit(
        key: EncodedCacheKey,
        commit: MemoryCommit<V, E>,
    ): Boolean {
        val storage = persistentStorage ?: return false
        persistent ?: return false
        val operation = events.start(
            operation = CacheOperationKind.PERSISTENCE,
            namespace = spec.namespace,
            key = key,
            origin = CacheOperationOrigin.EXPLICIT,
            persistenceOperation = CachePersistenceOperation.WRITE,
        )
        val value = checkNotNull(commit.snapshot.value)
        val metadata = checkNotNull(commit.snapshot.metadata)
        val codecStarted = TimeSource.Monotonic.markNow()
        val payload = try {
            storage.codec.encode(value)
        } catch (cancellation: CancellationException) {
            completeWithOccupancy(
                operation,
                CacheOperationOutcome.CANCELLED,
                codecDuration = codecStarted.elapsedNow(),
            )
            throw cancellation
        } catch (_: Throwable) {
            emitPersistentFailure(key)
            completeWithOccupancy(
                operation,
                CacheOperationOutcome.UNAVAILABLE,
                codecDuration = codecStarted.elapsedNow(),
            )
            return false
        }
        val codecDuration = codecStarted.elapsedNow()
        if (payload.size > storage.maxPayloadBytes) {
            emitPersistentFailure(key)
            completeWithOccupancy(
                operation,
                CacheOperationOutcome.UNAVAILABLE,
                codecDuration = codecDuration,
            )
            return false
        }
        val writtenAt = clock.now()
        val record = PersistedCacheRecord(
            address = address(key),
            payload = payload,
            payloadVersion = storage.codec.currentVersion,
            metadata = metadata,
            writtenAt = writtenAt,
            lastAccessedAt = writtenAt,
        )
        val persistenceStarted = TimeSource.Monotonic.markNow()
        val outcome = persistMutation(
            key = key,
            revision = commit.revision,
            operation = CachePersistenceOperation.WRITE,
        ) { backend, _ -> backend.write(record) }
        completeWithOccupancy(
            operation = operation,
            outcome = outcome.cacheOutcome,
            persistenceDuration = persistenceStarted.elapsedNow(),
            codecDuration = codecDuration,
        )
        return outcome == PersistenceMutationOutcome.COMMITTED
    }

    /**
     * Runs one exact-address persistent mutation when its memory revision is still current.
     *
     * @param key Encoded address affected by the mutation.
     * @param revision Memory revision that authorizes the persistent operation.
     * @param mutation Backend operation receiving the configured store and full address.
     * @return `true` when the mutation executed successfully for the current revision.
     */
    private suspend fun persistMutation(
        key: EncodedCacheKey,
        revision: Long,
        operation: CachePersistenceOperation,
        mutation: suspend (
            dev.voir.cache.core.persistence.PersistentCacheStore,
            CacheAddress,
        ) -> Unit,
    ): PersistenceMutationOutcome {
        val coordinator = persistent ?: return PersistenceMutationOutcome.NOT_CONFIGURED
        if (persistentStorage == null) return PersistenceMutationOutcome.NOT_CONFIGURED
        val diagnostic = if (operation == CachePersistenceOperation.WRITE) {
            null
        } else {
            events.start(
                operation = CacheOperationKind.PERSISTENCE,
                namespace = spec.namespace,
                key = key,
                origin = CacheOperationOrigin.EXPLICIT,
                persistenceOperation = operation,
            )
        }
        val started = TimeSource.Monotonic.markNow()
        val outcome = try {
            coordinator.ordered { backend ->
                if (store.isRevisionCurrent(key, revision)) {
                    mutation(backend, address(key))
                    PersistenceMutationOutcome.COMMITTED
                } else {
                    emitDiscardedResult(key)
                    PersistenceMutationOutcome.DISCARDED
                }
            }
        } catch (cancellation: CancellationException) {
            diagnostic?.let { token ->
                completeWithOccupancy(
                    token,
                    CacheOperationOutcome.CANCELLED,
                    persistenceDuration = started.elapsedNow(),
                )
            }
            throw cancellation
        } catch (_: Throwable) {
            emitPersistentFailure(key)
            PersistenceMutationOutcome.UNAVAILABLE
        }
        diagnostic?.let { token ->
            completeWithOccupancy(
                token,
                outcome.cacheOutcome,
                persistenceDuration = started.elapsedNow(),
            )
        }
        return outcome
    }

    /**
     * Invokes the bound loader and maps every non-cancellation exception.
     *
     * @param key Domain key passed to the application-owned loader.
     * @return Typed source result returned directly or produced by failure mapping.
     */
    private suspend fun loadFromSource(key: K): FetchResult<V, E> = try {
        fetch(key)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (unexpected: Throwable) {
        FetchFailure(mapUnexpectedFailure(unexpected))
    }

    /**
     * Encodes and validates [key] against manager-wide address limits.
     *
     * @param key Domain-owned key to encode.
     * @return Validated stable partition and normalized key.
     */
    private fun encode(key: K): EncodedCacheKey {
        val raw = spec.keyEncoder.encode(key)
        val family = familyForKey?.invoke(key)
        if (family != null) {
            validateFamily(family)
            require(raw.partition == family.partition) {
                "Encoded cache key and family must belong to the same partition."
            }
        }
        return raw.copy(family = family?.value).also { encoded ->
        require(encoded.partition.value.length <= config.maxPartitionLength) {
            "Cache partition exceeds maxPartitionLength=${config.maxPartitionLength}."
        }
        require(encoded.value.length <= config.maxKeyLength) {
            "Encoded cache key exceeds maxKeyLength=${config.maxKeyLength}."
        }
        }
    }

    /** Validates family metadata against manager-wide cardinality bounds. */
    private fun validateFamily(family: EncodedCacheFamily) {
        require(family.partition.value.length <= config.maxPartitionLength) {
            "Cache partition exceeds maxPartitionLength=${config.maxPartitionLength}."
        }
        require(family.value.length <= config.maxFamilyLength) {
            "Encoded cache family exceeds maxFamilyLength=${config.maxFamilyLength}."
        }
    }

    /**
     * Creates the complete persistent address for [key].
     *
     * @param key Encoded partition and normalized identity.
     * @return Address combining this resource namespace with [key].
     */
    private fun address(key: EncodedCacheKey): CacheAddress = CacheAddress(
        namespace = spec.namespace,
        partition = key.partition,
        key = key.value,
        family = key.family,
    )

    /**
     * Emits a non-sensitive memory-hit event when [snapshot] contains a value.
     *
     * @param snapshot Memory state inspected by the command.
     * @param key Encoded address used to create a salted fingerprint.
     */
    private fun emitLookup(snapshot: CacheSnapshot<V, E>, key: EncodedCacheKey) {
        if (snapshot.value != null) {
            events.emit(CacheEventType.MEMORY_HIT, spec.namespace, key)
        }
    }

    /**
     * Emits a diagnostic when an older asynchronous result loses its revision race.
     *
     * @param key Encoded address whose completion was discarded.
     */
    private fun emitDiscardedResult(key: EncodedCacheKey) {
        events.emit(CacheEventType.DISCARDED_LATE_RESULT, spec.namespace, key)
    }

    /**
     * Emits a non-sensitive persistent failure diagnostic.
     *
     * @param key Optional encoded address used to create a salted fingerprint.
     */
    private fun emitPersistentFailure(key: EncodedCacheKey? = null) {
        events.emit(CacheEventType.PERSISTENT_STORE_FAILURE, spec.namespace, key)
    }

    /** Completes a structured operation with current namespace memory occupancy. */
    private suspend fun completeWithOccupancy(
        operation: CacheOperationToken,
        outcome: CacheOperationOutcome,
        sourceDuration: Duration? = null,
        persistenceDuration: Duration? = null,
        codecDuration: Duration? = null,
        singleFlightStatus: CacheSingleFlightStatus? = null,
        waiterCount: Int? = null,
        evictionCause: CacheEvictionCause? = null,
        affectedCount: Long? = null,
    ) {
        events.complete(
            token = operation,
            outcome = outcome,
            sourceDuration = sourceDuration,
            persistenceDuration = persistenceDuration,
            codecDuration = codecDuration,
            singleFlightStatus = singleFlightStatus,
            waiterCount = waiterCount,
            evictionCause = evictionCause,
            affectedCount = affectedCount,
            memoryEntryCount = store.entryCount(),
            memoryEntryLimit = spec.memory.maxEntries,
        )
    }

    /** Maps one public operation result to its sanitized terminal classification. */
    private fun CacheResult<V, E>.outcome(): CacheOperationOutcome = when (this) {
        is CacheSuccess -> CacheOperationOutcome.COMMITTED
        is CacheFailure -> CacheOperationOutcome.SOURCE_FAILURE
        is CacheDiscarded -> CacheOperationOutcome.DISCARDED
    }

    /** Maps current observable presence to a structured read outcome. */
    private fun CacheSnapshot<V, E>.outcome(): CacheOperationOutcome =
        if (value == null) CacheOperationOutcome.ABSENT else CacheOperationOutcome.COMMITTED

    /** Distinguishes a successful memory-only mutation from degraded persistent durability. */
    private fun persistenceOutcome(persisted: Boolean): CacheOperationOutcome =
        if (persistentStorage == null || persisted) {
            CacheOperationOutcome.COMMITTED
        } else {
            CacheOperationOutcome.PERSISTENCE_DEGRADED_SUCCESS
        }
}

/** Internal persistence classification retaining discarded versus unavailable detail. */
private enum class PersistenceMutationOutcome {
    COMMITTED,
    DISCARDED,
    UNAVAILABLE,
    NOT_CONFIGURED,
}

/** Maps persistence coordination to the public sanitized operation outcome. */
private val PersistenceMutationOutcome.cacheOutcome: CacheOperationOutcome
    get() = when (this) {
        PersistenceMutationOutcome.COMMITTED -> CacheOperationOutcome.COMMITTED
        PersistenceMutationOutcome.DISCARDED -> CacheOperationOutcome.DISCARDED
        PersistenceMutationOutcome.UNAVAILABLE -> CacheOperationOutcome.UNAVAILABLE
        PersistenceMutationOutcome.NOT_CONFIGURED -> CacheOperationOutcome.NOT_CONFIGURED
    }
