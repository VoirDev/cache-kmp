package dev.voir.cache.core.internal

import dev.voir.cache.core.config.CacheManagerConfig
import dev.voir.cache.core.config.CacheSpec
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.memory.InMemoryResourceStore
import dev.voir.cache.core.memory.MemoryCommit
import dev.voir.cache.core.model.CacheDiscarded
import dev.voir.cache.core.model.CacheFailure
import dev.voir.cache.core.model.CacheResult
import dev.voir.cache.core.model.CacheSnapshot
import dev.voir.cache.core.model.CacheSuccess
import dev.voir.cache.core.model.FetchFailure
import dev.voir.cache.core.model.FetchResult
import dev.voir.cache.core.model.FetchSuccess
import dev.voir.cache.core.persistence.CacheAddress
import dev.voir.cache.core.persistence.PersistedCacheRecord
import dev.voir.cache.core.policy.FreshnessPolicy
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
 * @property config Manager-wide encoded-address validation limits.
 * @property clock Time source used for persistence timestamps.
 * @property persistent Optional manager-wide persistent operation coordinator.
 * @property memoryFirst Session tracker preventing stale restoration after failed durable writes.
 * @property freshnessForKey Per-request policy applied to a successfully committed value.
 */
internal class DefaultCachedResource<K : Any, V : Any, E : Any>(
    private val spec: CacheSpec<K, V>,
    private val fetch: suspend (K) -> FetchResult<V, E>,
    private val mapUnexpectedFailure: (Throwable) -> E,
    private val store: InMemoryResourceStore<V, E>,
    private val scope: CoroutineScope,
    private val lifecycle: CommandLifecycle,
    private val config: CacheManagerConfig,
    private val clock: CacheClock,
    private val persistent: PersistentStoreCoordinator?,
    private val memoryFirst: MemoryFirstTracker<CacheAddress>,
    private val freshnessForKey: (K) -> FreshnessPolicy,
) {
    private val refreshFlights = KeyedSingleFlight<EncodedCacheKey, CacheResult<V, E>>(scope)
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
    fun observe(key: K): Flow<CacheSnapshot<V, E>> {
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
     * Awaits lazy hydration and returns current freshness-evaluated memory state.
     *
     * @param key Domain key to read without source loading.
     * @return Current snapshot after any configured hydration completes.
     */
    suspend fun current(key: K): CacheSnapshot<V, E> = lifecycle.runCommand {
        val encodedKey = encode(key)
        awaitHydration(encodedKey)
        store.current(encodedKey)
    }

    /**
     * Forces or joins one source refresh after persistent hydration.
     *
     * @param key Domain key to refresh regardless of freshness.
     * @return Successful source value or typed source failure with optional stale data.
     */
    suspend fun refresh(key: K): CacheResult<V, E> = lifecycle.runCommand {
        val encodedKey = encode(key)
        awaitHydration(encodedKey)
        joinOrStartRefresh(key, encodedKey)
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
        val pending = memoryFirst.isPending(address(key))
        return hydrationMutex.withLock {
            releaseCompletedHydrations()
            val existing = hydrations[key]
            if (existing != null && (!existing.isCompleted || retained || pending)) {
                return@withLock existing
            }
            scope.async {
                performHydration(key, storage, coordinator)
            }.also { created -> hydrations[key] = created }
        }
    }

    /**
     * Drops finished hydration handles for addresses the memory bound no longer retains.
     *
     * Without this sweep the handle map would outlive its bounded memory tier and grow with the
     * number of distinct addresses a long-lived manager observes.
     */
    private fun releaseCompletedHydrations() {
        if (hydrations.size <= hydrationHandleBound) return
        val retained = store.retainedKeys
        val iterator = hydrations.iterator()
        while (iterator.hasNext()) {
            val (key, hydration) = iterator.next()
            if (hydration.isCompleted && key !in retained) iterator.remove()
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
    private suspend fun markMemoryAuthoritative(key: EncodedCacheKey) {
        if (persistentStorage == null) return
        memoryFirst.markPending(address(key))
        hydrationMutex.withLock { hydrations[key] = CompletableDeferred(Unit) }
    }

    /**
     * Marks [key] durably available for rehydration after future memory eviction.
     *
     * @param key Encoded address whose persistent operation succeeded.
     */
    private suspend fun markDurable(key: EncodedCacheKey) {
        if (persistentStorage == null) return
        memoryFirst.markDurable(address(key))
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
        val hydration = store.beginHydration(key)
        val address = address(key)
        try {
            if (memoryFirst.isPending(address)) {
                store.completeHydrationAbsent(key, hydration.revision)
                return
            }
            val record = try {
                coordinator.ordered { backend -> backend.read(address) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                store.completeHydrationAbsent(key, hydration.revision)
                return
            }
            if (record == null || memoryFirst.isSuppressed(address, record.writtenAt)) {
                store.completeHydrationAbsent(key, hydration.revision)
                return
            }
            val value = decodeRecord(record, address, storage) ?: run {
                removeCorruptRecord(address, coordinator)
                store.completeHydrationAbsent(key, hydration.revision)
                return
            }
            store.completeHydrationSuccess(
                key = key,
                expectedRevision = hydration.revision,
                value = value,
                metadata = record.metadata,
            )
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                store.cancelHydration(key, hydration.revision)
            }
            throw cancellation
        } catch (failure: Throwable) {
            // A non-cancellation failure must never leave the address stuck reporting hydration.
            withContext(NonCancellable) {
                store.completeHydrationAbsent(key, hydration.revision)
            }
            throw failure
        } finally {
            withContext(NonCancellable) { store.unpinOperation(key) }
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
            record.payloadVersion != storage.codec.currentVersion
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
     * Removes one corrupt row best-effort.
     *
     * @param address Exact corrupt record address.
     * @param coordinator Manager-wide persistent operation coordinator.
     */
    private suspend fun removeCorruptRecord(
        address: CacheAddress,
        coordinator: PersistentStoreCoordinator,
    ) {
        try {
            coordinator.ordered { backend -> backend.remove(address) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Retention removes an undeletable corrupt row later.
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
    ): CacheResult<V, E> = refreshFlights
        .joinOrStart(key = encodedKey) { performRefresh(key, encodedKey) }
        .await()

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
    ): CacheResult<V, E> {
        val refresh = store.beginRefresh(encodedKey)
        try {
            return when (val sourceResult = loadFromSource(key)) {
                is FetchSuccess -> {
                    val commit = store.completeRefreshSuccess(
                        key = encodedKey,
                        expectedRevision = refresh.revision,
                        value = sourceResult.value,
                        committedFreshnessPolicy = freshnessForKey(key),
                    )
                    if (commit == null) {
                        CacheDiscarded(currentSnapshot = store.current(encodedKey))
                    } else {
                        markMemoryAuthoritative(encodedKey)
                        if (persistCommit(encodedKey, commit)) markDurable(encodedKey)
                        CacheSuccess(sourceResult.value, commit.snapshot.freshness)
                    }
                }

                is FetchFailure -> {
                    val committed = store.completeRefreshFailure(
                        key = encodedKey,
                        expectedRevision = refresh.revision,
                        error = sourceResult.error,
                    )
                    if (committed) {
                        CacheFailure(sourceResult.error, refresh.staleValue)
                    } else {
                        CacheDiscarded(
                            currentSnapshot = store.current(encodedKey),
                            sourceError = sourceResult.error,
                        )
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) { store.cancelRefresh(encodedKey, refresh.revision) }
            throw cancellation
        } catch (unexpected: Throwable) {
            withContext(NonCancellable) { store.cancelRefresh(encodedKey, refresh.revision) }
            throw unexpected
        } finally {
            withContext(NonCancellable) { store.unpinOperation(encodedKey) }
        }
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
        val coordinator = persistent ?: return false
        val value = checkNotNull(commit.snapshot.value)
        val metadata = checkNotNull(commit.snapshot.metadata)
        val payload = try {
            storage.codec.encode(value)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return false
        }
        if (payload.size > storage.maxPayloadBytes) return false
        val writtenAt = clock.now()
        val record = PersistedCacheRecord(
            address = address(key),
            payload = payload,
            payloadVersion = storage.codec.currentVersion,
            metadata = metadata,
            writtenAt = writtenAt,
            lastAccessedAt = writtenAt,
        )
        return try {
            coordinator.ordered { backend ->
                if (store.isRevisionCurrent(key, commit.revision)) {
                    backend.write(record)
                    true
                } else {
                    false
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        }
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
    private fun encode(key: K): EncodedCacheKey = spec.keyEncoder.encode(key).also { encoded ->
        require(encoded.partition.value.length <= config.maxPartitionLength) {
            "Cache partition exceeds maxPartitionLength=${config.maxPartitionLength}."
        }
        require(encoded.value.length <= config.maxKeyLength) {
            "Encoded cache key exceeds maxKeyLength=${config.maxKeyLength}."
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
    )

    /** Upper bound on retained hydration handles before finished entries are swept. */
    private val hydrationHandleBound: Int
        get() = spec.memory.maxEntries * HYDRATION_HANDLE_SLACK

    private companion object {
        /** Sweep only once the handle map is a multiple of the retained memory bound. */
        const val HYDRATION_HANDLE_SLACK: Int = 2
    }
}
