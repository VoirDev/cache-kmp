package dev.voir.cache.core.internal

import dev.voir.cache.core.CacheManager
import dev.voir.cache.core.CachedResource
import dev.voir.cache.core.FamilyCachedResource
import dev.voir.cache.core.FamilyPagedCachedResource
import dev.voir.cache.core.PagedCachedResource
import dev.voir.cache.core.config.CacheManagerConfig
import dev.voir.cache.core.config.CacheSpec
import dev.voir.cache.core.config.FamilyCacheSpec
import dev.voir.cache.core.config.FamilyPagedCacheSpec
import dev.voir.cache.core.config.PagedCacheSpec
import dev.voir.cache.core.diagnostics.CacheEventListener
import dev.voir.cache.core.diagnostics.CacheEventType
import dev.voir.cache.core.diagnostics.CacheOperationKind
import dev.voir.cache.core.diagnostics.CacheOperationOrigin
import dev.voir.cache.core.diagnostics.CacheOperationOutcome
import dev.voir.cache.core.diagnostics.CachePersistenceOperation
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.memory.InMemoryCacheRegistry
import dev.voir.cache.core.memory.InMemoryResourceStore
import dev.voir.cache.core.model.CachePersistenceStatus
import dev.voir.cache.core.model.CachePruneReport
import dev.voir.cache.core.model.CacheStatisticsSnapshot
import dev.voir.cache.core.model.FetchResult
import dev.voir.cache.core.model.PersistentCacheStatistics
import dev.voir.cache.core.model.PersistentNamespaceStatistics
import dev.voir.cache.core.paging.FetchedPage
import dev.voir.cache.core.paging.PageLoadRequest
import dev.voir.cache.core.persistence.ExperimentalCacheStoreApi
import dev.voir.cache.core.persistence.PersistentCacheStore
import dev.voir.cache.core.persistence.PruneRequest
import dev.voir.cache.core.policy.PersistentRetentionPolicy
import dev.voir.cache.core.storage.PersistentStorage
import dev.voir.cache.core.time.CacheClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Default manager implementation owning resource stores and asynchronous source work.
 *
 * @param coroutineContext Host context inherited by the manager's child supervisor.
 * @param persistentStore Optional encoded backend owned and closed by this manager.
 * @property clock Time source shared by every registered resource.
 * @param eventListener Optional diagnostic consumer isolated from cache failures.
 * @property config Manager-wide namespace and encoded-address limits.
 */
@OptIn(ExperimentalCacheStoreApi::class)
internal class DefaultCacheManager(
    coroutineContext: CoroutineContext,
    persistentStore: PersistentCacheStore?,
    private val clock: CacheClock,
    eventListener: CacheEventListener?,
    private val config: CacheManagerConfig,
) : CacheManager {
    private val managerJob = SupervisorJob(coroutineContext[Job])
    private val managerScope = CoroutineScope(coroutineContext + managerJob)
    private val lifecycle = CommandLifecycle()
    private val closeCompleted = CompletableDeferred<Throwable?>()
    private val memoryRegistry = InMemoryCacheRegistry()
    private val persistent = persistentStore?.let(::PersistentStoreCoordinator)
    private val persistentClears = PersistentClearTracker()
    private val events = CacheEventEmitter(
        coroutineContext = coroutineContext,
        listener = eventListener,
        fingerprinter = CacheKeyFingerprinter(),
    )

    /**
     * Registers one namespace and creates its typed memory/persistence coordinator.
     *
     * @param K Domain-owned resource key type.
     * @param V Non-null cached value type.
     * @param E Application-defined source error type.
     * @param spec Resource policies and stable key encoder.
     * @param fetch Source loader permanently bound to the namespace.
     * @param mapUnexpectedFailure Mapper for non-cancellation loader exceptions.
     * @return Typed resource handle owned by this manager.
     */
    override fun <K : Any, V : Any, E : Any> resource(
        spec: CacheSpec<K, V>,
        fetch: suspend (K) -> FetchResult<V, E>,
        mapUnexpectedFailure: (Throwable) -> E,
    ): CachedResource<K, V, E> {
        lifecycle.ensureOpen()
        require(spec.namespace.value.length <= config.maxNamespaceLength) {
            "Cache namespace exceeds maxNamespaceLength=${config.maxNamespaceLength}."
        }
        require(spec.storage !is PersistentStorage<*> || persistent != null) {
            "Persistent cache storage requires a configured persistent store."
        }
        val store = InMemoryResourceStore<V, E>(
            namespace = spec.namespace,
            freshnessPolicy = spec.freshness,
            memoryPolicy = spec.memory,
            clock = clock,
            onEviction = { key, remainingEntries, configuredLimit ->
                events.memoryEviction(
                    namespace = spec.namespace,
                    key = key,
                    remainingEntries = remainingEntries,
                    configuredLimit = configuredLimit,
                )
            },
        )
        memoryRegistry.register(store)
        return DefaultCachedResource(
            spec = spec,
            fetch = fetch,
            mapUnexpectedFailure = mapUnexpectedFailure,
            store = store,
            scope = managerScope,
            lifecycle = lifecycle,
            events = events,
            config = config,
            clock = clock,
            persistent = persistent,
            persistentClears = persistentClears,
        )
    }

    /** Registers one family-aware resource with typed exact and bulk-operation boundaries. */
    override fun <K : Any, F : Any, V : Any, E : Any> resource(
        spec: FamilyCacheSpec<K, F, V>,
        fetch: suspend (K) -> FetchResult<V, E>,
        mapUnexpectedFailure: (Throwable) -> E,
    ): FamilyCachedResource<K, F, V, E> {
        val cache = CacheSpec(
            namespace = spec.namespace,
            keyEncoder = spec.keyEncoder,
            freshness = spec.freshness,
            storage = spec.storage,
            memory = spec.memory,
        )
        lifecycle.ensureOpen()
        require(cache.namespace.value.length <= config.maxNamespaceLength) {
            "Cache namespace exceeds maxNamespaceLength=${config.maxNamespaceLength}."
        }
        require(cache.storage !is PersistentStorage<*> || persistent != null) {
            "Persistent cache storage requires a configured persistent store."
        }
        val store = InMemoryResourceStore<V, E>(
            namespace = cache.namespace,
            freshnessPolicy = cache.freshness,
            memoryPolicy = cache.memory,
            clock = clock,
            onEviction = { key, remainingEntries, configuredLimit ->
                events.memoryEviction(
                    namespace = cache.namespace,
                    key = key,
                    remainingEntries = remainingEntries,
                    configuredLimit = configuredLimit,
                )
            },
        )
        memoryRegistry.register(store)
        val delegate = DefaultCachedResource(
            spec = cache,
            fetch = fetch,
            mapUnexpectedFailure = mapUnexpectedFailure,
            store = store,
            scope = managerScope,
            lifecycle = lifecycle,
            events = events,
            config = config,
            clock = clock,
            persistent = persistent,
            persistentClears = persistentClears,
            familyForKey = { key -> spec.familyEncoder.encode(spec.familyOf(key)) },
        )
        return DefaultFamilyCachedResource(delegate, spec.familyEncoder)
    }

    /** Registers one accumulated memory-only paged namespace. */
    override fun <Q : Any, C : Any, I : Any, ID : Any, E : Any> pagedResource(
        spec: PagedCacheSpec<Q, C, I, ID>,
        fetch: suspend (PageLoadRequest<Q, C>) -> FetchResult<FetchedPage<I, C>, E>,
        mapUnexpectedFailure: (Throwable) -> E,
    ): PagedCachedResource<Q, C, I, E> {
        validatePagedNamespace(spec.namespace)
        val store = PagedMemoryStore<C, I, ID, E>(
            namespace = spec.namespace,
            itemId = spec.itemId,
            duplicatePolicy = spec.duplicatePolicy,
            freshnessPolicy = spec.freshness,
            memoryPolicy = spec.memory,
            clock = clock,
            onEviction = { key, remainingEntries, configuredLimit ->
                events.memoryEviction(
                    spec.namespace,
                    key,
                    remainingEntries,
                    configuredLimit,
                )
            },
        )
        memoryRegistry.register(store)
        return DefaultPagedCachedResource(
            spec,
            fetch,
            mapUnexpectedFailure,
            store,
            managerScope,
            lifecycle,
            events,
            config,
        )
    }

    /** Registers one family-aware accumulated memory-only paged namespace. */
    override fun <Q : Any, F : Any, C : Any, I : Any, ID : Any, E : Any> pagedResource(
        spec: FamilyPagedCacheSpec<Q, F, C, I, ID>,
        fetch: suspend (PageLoadRequest<Q, C>) -> FetchResult<FetchedPage<I, C>, E>,
        mapUnexpectedFailure: (Throwable) -> E,
    ): FamilyPagedCachedResource<Q, F, C, I, E> {
        validatePagedNamespace(spec.namespace)
        val base = PagedCacheSpec<Q, C, I, ID>(
            namespace = spec.namespace,
            queryEncoder = spec.queryEncoder,
            itemId = spec.itemId,
            duplicatePolicy = spec.duplicatePolicy,
            freshness = spec.freshness,
            memory = spec.memory,
        )
        val store = PagedMemoryStore<C, I, ID, E>(
            namespace = spec.namespace,
            itemId = spec.itemId,
            duplicatePolicy = spec.duplicatePolicy,
            freshnessPolicy = spec.freshness,
            memoryPolicy = spec.memory,
            clock = clock,
            onEviction = { key, remainingEntries, configuredLimit ->
                events.memoryEviction(
                    spec.namespace,
                    key,
                    remainingEntries,
                    configuredLimit,
                )
            },
        )
        memoryRegistry.register(store)
        val delegate = DefaultPagedCachedResource(
            base,
            fetch,
            mapUnexpectedFailure,
            store,
            managerScope,
            lifecycle,
            events,
            config,
            familyForQuery = { query -> spec.familyEncoder.encode(spec.familyOf(query)) },
        )
        return DefaultFamilyPagedCachedResource(delegate, spec.familyEncoder)
    }

    private fun validatePagedNamespace(namespace: dev.voir.cache.core.key.CacheNamespace) {
        lifecycle.ensureOpen()
        require(namespace.value.length <= config.maxNamespaceLength) {
            "Cache namespace exceeds maxNamespaceLength=${config.maxNamespaceLength}."
        }
    }

    /**
     * Clears [partition] from memory first and then from the configured persistent backend.
     *
     * @param partition Isolation boundary to clear across all registered namespaces.
     */
    override suspend fun clear(partition: CachePartition) {
        lifecycle.runCommand {
            var degraded = false
            val coordinator = persistent
            if (coordinator == null) {
                memoryRegistry.clear(partition)
            } else {
                coordinator.ordered { store ->
                    persistentClears.markPartition(partition)
                    memoryRegistry.clear(partition)
                    try {
                        store.clearPartition(partition)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        degraded = true
                        events.emit(CacheEventType.PERSISTENT_STORE_FAILURE)
                    }
                }
            }
            emitBulkOccupancy(
                operationKind = CacheOperationKind.CLEAR_PARTITION,
                outcome = if (degraded) {
                    CacheOperationOutcome.PERSISTENCE_DEGRADED_SUCCESS
                } else {
                    CacheOperationOutcome.COMMITTED
                },
                persistenceOperation = CachePersistenceOperation.CLEAR_PARTITION,
            )
        }
    }

    /** Clears all manager-owned memory and persistent records with memory-first degradation. */
    override suspend fun clearAll() {
        lifecycle.runCommand {
            var degraded = false
            val coordinator = persistent
            if (coordinator == null) {
                memoryRegistry.clearAll()
            } else {
                coordinator.ordered { store ->
                    persistentClears.markAll()
                    memoryRegistry.clearAll()
                    try {
                        store.clearAll()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        degraded = true
                        events.emit(CacheEventType.PERSISTENT_STORE_FAILURE)
                    }
                }
            }
            emitBulkOccupancy(
                operationKind = CacheOperationKind.CLEAR_ALL,
                outcome = if (degraded) {
                    CacheOperationOutcome.PERSISTENCE_DEGRADED_SUCCESS
                } else {
                    CacheOperationOutcome.COMMITTED
                },
                persistenceOperation = CachePersistenceOperation.CLEAR_ALL,
            )
        }
    }

    /** Applies the manager-configured persistent retention policy. */
    override suspend fun prune(): CachePruneReport = prune(config.persistentRetention)

    /** Applies one explicit persistent retention policy and returns a sanitized report. */
    override suspend fun prune(policy: PersistentRetentionPolicy): CachePruneReport =
        lifecycle.runCommand {
            val operation = events.start(
                operation = CacheOperationKind.PRUNE,
                origin = CacheOperationOrigin.MAINTENANCE,
                persistenceOperation = CachePersistenceOperation.PRUNE,
            )
            // Memory limits are enforced after every write and pin release.
            val coordinator = persistent ?: return@runCommand CachePruneReport(
                rowsRemoved = 0,
                payloadBytesRemoved = 0,
                persistenceStatus = CachePersistenceStatus.NOT_CONFIGURED,
            ).also {
                events.complete(operation, CacheOperationOutcome.NOT_CONFIGURED, affectedCount = 0)
            }
            try {
                coordinator.ordered { store ->
                    val now = clock.now()
                    val result = store.prune(
                        PruneRequest(
                            expiredBefore = if (policy.removeExpired) {
                                now - policy.expiredEntryGrace
                            } else {
                                null
                            },
                            maxEntriesPerNamespace = policy.maxEntriesPerNamespace,
                            maxTotalEntries = policy.maxTotalEntries,
                            maxTotalPayloadBytes = policy.maxTotalPayloadBytes,
                        )
                    )
                    CachePruneReport(
                        rowsRemoved = result.rowsRemoved,
                        payloadBytesRemoved = result.bytesRemoved,
                        persistenceStatus = CachePersistenceStatus.AVAILABLE,
                    ).also {
                        events.complete(
                            operation,
                            CacheOperationOutcome.COMMITTED,
                            persistenceDuration = operation.started.elapsedNow(),
                            affectedCount = result.rowsRemoved,
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                events.complete(operation, CacheOperationOutcome.CANCELLED)
                throw cancellation
            } catch (_: Throwable) {
                events.emit(CacheEventType.PERSISTENT_STORE_FAILURE)
                CachePruneReport(
                    rowsRemoved = 0,
                    payloadBytesRemoved = null,
                    persistenceStatus = CachePersistenceStatus.UNAVAILABLE,
                ).also {
                    events.complete(
                        operation,
                        CacheOperationOutcome.UNAVAILABLE,
                        persistenceDuration = operation.started.elapsedNow(),
                    )
                }
            }
        }

    /** Returns one serialized manager-level persistent occupancy snapshot. */
    override suspend fun storageStatistics(): PersistentCacheStatistics = lifecycle.runCommand {
        val operation = events.start(
            operation = CacheOperationKind.STORAGE_STATISTICS,
            origin = CacheOperationOrigin.MAINTENANCE,
            persistenceOperation = CachePersistenceOperation.STATISTICS,
        )
        val coordinator = persistent ?: return@runCommand PersistentCacheStatistics(
            totalRows = 0,
            totalPayloadBytes = 0,
            namespaces = emptyMap(),
            capturedAt = clock.now(),
            isExact = true,
            persistenceStatus = CachePersistenceStatus.NOT_CONFIGURED,
        ).also {
            events.complete(
                operation,
                CacheOperationOutcome.NOT_CONFIGURED,
                persistentRowCount = 0,
                persistentPayloadBytes = 0,
                persistentValuesExact = true,
            )
        }
        try {
            coordinator.ordered { store ->
                val statistics = store.statistics()
                PersistentCacheStatistics(
                    totalRows = statistics.totalRows,
                    totalPayloadBytes = statistics.totalPayloadBytes,
                    namespaces = statistics.namespaces.mapValues { (_, namespace) ->
                        PersistentNamespaceStatistics(
                            rows = namespace.rows,
                            payloadBytes = namespace.payloadBytes,
                        )
                    }.toMap(),
                    capturedAt = clock.now(),
                    isExact = statistics.isExact,
                    persistenceStatus = CachePersistenceStatus.AVAILABLE,
                ).also {
                    events.complete(
                        operation,
                        CacheOperationOutcome.COMMITTED,
                        persistenceDuration = operation.started.elapsedNow(),
                        persistentRowCount = statistics.totalRows,
                        persistentPayloadBytes = statistics.totalPayloadBytes,
                        persistentValuesExact = statistics.isExact,
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            events.complete(operation, CacheOperationOutcome.CANCELLED)
            throw cancellation
        } catch (_: Throwable) {
            events.emit(CacheEventType.PERSISTENT_STORE_FAILURE)
            PersistentCacheStatistics(
                totalRows = null,
                totalPayloadBytes = null,
                namespaces = emptyMap(),
                capturedAt = clock.now(),
                isExact = false,
                persistenceStatus = CachePersistenceStatus.UNAVAILABLE,
            ).also {
                events.complete(
                    operation,
                    CacheOperationOutcome.UNAVAILABLE,
                    persistenceDuration = operation.started.elapsedNow(),
                )
            }
        }
    }

    /** Returns the current lock-free aggregate of sanitized diagnostic metrics. */
    override fun statistics(): CacheStatisticsSnapshot = events.statistics()

    /** Cancels manager jobs, waits for admitted commands, clears memory, and closes persistence. */
    override suspend fun close() {
        if (!lifecycle.beginClose()) {
            closeCompleted.await()?.let { failure -> throw failure }
            return
        }
        val operation = events.start(
            CacheOperationKind.CLOSE,
            origin = CacheOperationOrigin.LIFECYCLE,
            persistenceOperation = CachePersistenceOperation.CLOSE,
        )
        var closeFailure: Throwable? = null
        withContext(NonCancellable) {
            try {
                managerJob.cancelAndJoin()
                lifecycle.awaitIdle()
                memoryRegistry.clearAll()
                persistent?.let { coordinator ->
                    try {
                        coordinator.ordered { store -> store.close() }
                    } catch (failure: Throwable) {
                        closeFailure = failure
                        events.emit(CacheEventType.PERSISTENT_STORE_FAILURE)
                    }
                }
                events.complete(
                    operation,
                    if (closeFailure == null) CacheOperationOutcome.COMMITTED
                    else CacheOperationOutcome.UNAVAILABLE,
                )
            } catch (failure: Throwable) {
                closeFailure = closeFailure ?: failure
            } finally {
                try {
                    events.close()
                } catch (failure: Throwable) {
                    closeFailure = closeFailure ?: failure
                }
                closeCompleted.complete(closeFailure)
            }
        }
        closeFailure?.let { failure -> throw failure }
    }

    /** Emits one correlated manager command per affected namespace with final occupancy. */
    private suspend fun emitBulkOccupancy(
        operationKind: CacheOperationKind,
        outcome: CacheOperationOutcome,
        persistenceOperation: CachePersistenceOperation,
    ) {
        memoryRegistry.occupancies().forEach { occupancy ->
            val operation = events.start(
                operation = operationKind,
                namespace = occupancy.namespace,
                origin = CacheOperationOrigin.EXPLICIT,
                persistenceOperation = persistenceOperation,
            )
            events.complete(
                token = operation,
                outcome = outcome,
                memoryEntryCount = occupancy.entries,
                memoryEntryLimit = occupancy.limit,
            )
        }
    }
}
