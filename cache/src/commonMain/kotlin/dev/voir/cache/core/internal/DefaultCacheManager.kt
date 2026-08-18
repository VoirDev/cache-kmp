package dev.voir.cache.core.internal

import dev.voir.cache.core.config.CacheManagerConfig
import dev.voir.cache.core.config.CacheSpec
import dev.voir.cache.core.config.PagedCacheSpec
import dev.voir.cache.core.file.FileCacheSpec
import dev.voir.cache.core.file.FileFetcher
import dev.voir.cache.core.file.FileRetentionPolicy
import dev.voir.cache.core.file.store.FileCacheStore
import dev.voir.cache.core.file.store.FileStoreAddress
import dev.voir.cache.core.file.store.FileStorePruneRequest
import dev.voir.cache.core.file.store.StoredFileRecord
import dev.voir.cache.core.memory.InMemoryCacheRegistry
import dev.voir.cache.core.memory.InMemoryResourceStore
import dev.voir.cache.core.model.FetchResult
import dev.voir.cache.core.persistence.CacheAddress
import dev.voir.cache.core.persistence.PersistentCacheStore
import dev.voir.cache.core.persistence.PruneRequest
import dev.voir.cache.core.policy.FreshnessPolicy
import dev.voir.cache.core.policy.MemoryPolicy
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Owns every registered resource, its storage backends, and manager lifecycle.
 *
 * @param coroutineContext Host context inherited by the manager's child supervisor.
 * @param persistentStore Encoded backend owned and closed by this manager.
 * @param fileStore Filesystem backend owned and closed by this manager.
 * @property fileRetention Expiration policy applied by file maintenance.
 * @property clock Time source shared by every registered resource.
 * @property config Manager-wide namespace and encoded-address limits.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class DefaultCacheManager(
    coroutineContext: CoroutineContext,
    persistentStore: PersistentCacheStore,
    fileStore: FileCacheStore,
    private val fileRetention: FileRetentionPolicy,
    val clock: CacheClock,
    private val config: CacheManagerConfig,
) {
    private val managerJob = SupervisorJob(coroutineContext[Job])
    private val managerScope = CoroutineScope(coroutineContext + managerJob)
    private val lifecycle = CommandLifecycle()
    private val closeCompleted = CompletableDeferred<Throwable?>()
    private val memoryRegistry = InMemoryCacheRegistry()
    private val persistent = PersistentStoreCoordinator(persistentStore)
    private val persistentMemoryFirst = MemoryFirstTracker<CacheAddress>()
    private val files = FileStoreCoordinator(fileStore)
    private val fileMemoryFirst = MemoryFirstTracker<FileStoreAddress>()
    private val maintenanceRunning = AtomicBoolean(false)
    private val lastMaintenance = AtomicReference<TimeMark?>(null)

    /**
     * Registers one namespace and creates its typed memory/persistence coordinator.
     *
     * @param K Domain-owned resource key type.
     * @param V Non-null cached value type.
     * @param E Application-defined source error type.
     * @param spec Resource policies and stable key encoder.
     * @param fetch Source loader permanently bound to the namespace.
     * @param mapUnexpectedFailure Mapper for non-cancellation loader exceptions.
     * @param freshnessForKey Per-request policy applied to a successfully committed value.
     * @return Typed resource handle owned by this manager.
     */
    fun <K : Any, V : Any, E : Any> valueResource(
        spec: CacheSpec<K, V>,
        fetch: suspend (K) -> FetchResult<V, E>,
        mapUnexpectedFailure: (Throwable) -> E,
        freshnessForKey: (K) -> FreshnessPolicy,
    ): DefaultCachedResource<K, V, E> {
        lifecycle.ensureOpen()
        validateNamespace(spec.namespace.value)
        val store = InMemoryResourceStore<V, E>(
            namespace = spec.namespace,
            freshnessPolicy = spec.freshness,
            memoryPolicy = spec.memory,
            clock = clock,
        )
        memoryRegistry.register(store)
        return DefaultCachedResource(
            spec = spec,
            fetch = fetch,
            mapUnexpectedFailure = mapUnexpectedFailure,
            store = store,
            scope = managerScope,
            lifecycle = lifecycle,
            config = config,
            clock = clock,
            persistent = if (spec.storage is PersistentStorage<*>) persistent else null,
            memoryFirst = persistentMemoryFirst,
            freshnessForKey = freshnessForKey,
        )
    }

    /**
     * Registers one streamed-file namespace and creates its memory/filesystem coordinator.
     *
     * @param K Domain-owned resource key type.
     * @param E Application-defined source error type.
     * @param spec File policies and stable key encoder.
     * @param fetch Streaming source loader permanently bound to the namespace.
     * @param mapUnexpectedFailure Mapper for non-cancellation loader exceptions.
     * @param freshnessForKey Per-request policy applied to a successfully committed file.
     * @return Typed file resource handle owned by this manager.
     */
    fun <K : Any, E : Any> fileResource(
        spec: FileCacheSpec<K>,
        fetch: FileFetcher<K, E>,
        mapUnexpectedFailure: (Throwable) -> E,
        freshnessForKey: (K) -> FreshnessPolicy,
    ): DefaultFileCachedResource<K, E> {
        lifecycle.ensureOpen()
        validateNamespace(spec.namespace.value)
        val store = InMemoryResourceStore<StoredFileRecord, E>(
            namespace = spec.namespace,
            freshnessPolicy = spec.freshness,
            memoryPolicy = MemoryPolicy(spec.policy.maxEntries),
            clock = clock,
        )
        memoryRegistry.register(store)
        return DefaultFileCachedResource(
            spec = spec,
            fetch = fetch,
            mapUnexpectedFailure = mapUnexpectedFailure,
            store = store,
            scope = managerScope,
            lifecycle = lifecycle,
            config = config,
            clock = clock,
            files = files,
            memoryFirst = fileMemoryFirst,
            freshnessForKey = freshnessForKey,
        )
    }

    /**
     * Registers one accumulated paged namespace.
     *
     * Accumulated pages are process memory only: a cursor is a source-owned window that cannot be
     * assumed valid in a later process, so restoring pages from disk could produce a list the
     * source would never return.
     *
     * @param Q Domain-owned query type.
     * @param I Non-null accumulated item type.
     * @param C Source cursor type.
     * @param E Application-defined source error type.
     * @param spec Accumulation policies and stable query encoder.
     * @param mapUnexpectedFailure Mapper for non-cancellation loader exceptions.
     * @return Typed paged resource handle owned by this manager.
     */
    fun <Q : Any, I : Any, C : Any, E : Any> pagedResource(
        spec: PagedCacheSpec<Q, I>,
        mapUnexpectedFailure: (Throwable) -> E,
    ): DefaultPagedCachedResource<Q, I, C, E> {
        lifecycle.ensureOpen()
        validateNamespace(spec.namespace.value)
        val store = PagedMemoryStore<I, C, E>(
            namespace = spec.namespace,
            itemId = spec.itemId,
            duplicatePolicy = spec.duplicatePolicy,
            freshnessPolicy = spec.freshness,
            memoryPolicy = spec.memory,
            clock = clock,
        )
        memoryRegistry.register(store)
        return DefaultPagedCachedResource(
            spec = spec,
            mapUnexpectedFailure = mapUnexpectedFailure,
            store = store,
            scope = managerScope,
            lifecycle = lifecycle,
            config = config,
        )
    }

    /** Clears all manager-owned memory, persistent records, and files, memory first. */
    suspend fun clearAll() {
        lifecycle.runCommand {
            persistent.ordered { persistentStore ->
                files.ordered { fileStore ->
                    val clearedAt = clock.now()
                    memoryRegistry.clearAll()
                    if (!clearQuietly { persistentStore.clearAll() }) {
                        persistentMemoryFirst.markClearFailed(clearedAt)
                    }
                    if (!clearQuietly { fileStore.clearAll() }) {
                        fileMemoryFirst.markClearFailed(clearedAt)
                    }
                }
            }
        }
    }

    /**
     * Applies persistent and file retention immediately.
     *
     * Startup uses this directly so a manager never returns before its stores are bounded.
     */
    suspend fun runMaintenance() {
        try {
            lifecycle.runCommand {
                pruneValues()
                pruneFiles()
            }
        } finally {
            lastMaintenance.store(TimeSource.Monotonic.markNow())
        }
    }

    /**
     * Requests retention maintenance without delaying the calling cache request.
     *
     * Retention needs whole-store aggregates, so running it inline on every request would put a
     * full scan of both backends in front of ordinary cache hits. Maintenance therefore runs in the
     * background, at most once per [MAINTENANCE_INTERVAL], and never more than one pass at a time.
     */
    fun scheduleMaintenance() {
        if (lifecycle.isClosed || !isMaintenanceDue()) return
        if (!maintenanceRunning.compareAndSet(expectedValue = false, newValue = true)) return
        managerScope.launch {
            try {
                lifecycle.runCommand {
                    pruneValues()
                    pruneFiles()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Retention is best effort; cache reads and writes stay usable without it.
            }
        }.invokeOnCompletion {
            lastMaintenance.store(TimeSource.Monotonic.markNow())
            maintenanceRunning.store(false)
        }
    }

    /**
     * Cancels manager jobs, waits for admitted commands, clears memory, and closes persistence.
     *
     * Shutdown is terminal and idempotent. Concurrent callers observe the same completion and the
     * same close failure.
     */
    suspend fun close() {
        if (!lifecycle.beginClose()) {
            closeCompleted.await()?.let { failure -> throw failure }
            return
        }
        val closeFailure = withContext(NonCancellable) {
            val failure = try {
                managerJob.cancelAndJoin()
                lifecycle.awaitIdle()
                memoryRegistry.clearAll()
                // Both backends are always closed; the first failure is the one reported.
                val fileFailure = closeQuietly { files.ordered { store -> store.close() } }
                val persistentFailure =
                    closeQuietly { persistent.ordered { store -> store.close() } }
                fileFailure ?: persistentFailure
            } catch (failure: Throwable) {
                failure
            }
            closeCompleted.complete(failure)
            failure
        }
        closeFailure?.let { failure -> throw failure }
    }

    /** Reports whether enough time has passed since the last retention pass. */
    private fun isMaintenanceDue(): Boolean {
        val last = lastMaintenance.load() ?: return true
        return last.elapsedNow() >= MAINTENANCE_INTERVAL
    }

    /** Applies the manager-configured persistent retention policy, tolerating backend failure. */
    private suspend fun pruneValues() {
        val policy: PersistentRetentionPolicy = config.persistentRetention
        val now = clock.now()
        try {
            persistent.ordered { store ->
                store.prune(
                    PruneRequest(
                        expiredBefore = if (policy.removeExpired) {
                            now - policy.expiredEntryGrace
                        } else {
                            null
                        },
                        maxTotalEntries = policy.maxTotalEntries,
                        maxTotalPayloadBytes = policy.maxTotalPayloadBytes,
                    )
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Retention is best effort; a failing backend must not fail a cache request.
        }
    }

    /** Applies the manager-configured file retention policy, tolerating backend failure. */
    private suspend fun pruneFiles() {
        val now = clock.now()
        try {
            files.ordered { store ->
                store.prune(
                    FileStorePruneRequest(
                        expiredBefore = if (fileRetention.removeExpired) {
                            now - fileRetention.expiredEntryGrace
                        } else {
                            null
                        },
                    )
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Retention is best effort; a failing backend must not fail a cache request.
        }
    }

    private fun validateNamespace(namespace: String) {
        require(namespace.length <= config.maxNamespaceLength) {
            "Cache namespace exceeds maxNamespaceLength=${config.maxNamespaceLength}."
        }
    }

    private suspend fun clearQuietly(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        false
    }

    private suspend fun closeQuietly(block: suspend () -> Unit): Throwable? = try {
        block()
        null
    } catch (failure: Throwable) {
        failure
    }

    private companion object {
        /** Shortest interval between two background retention passes for one manager. */
        val MAINTENANCE_INTERVAL: Duration = 5.minutes
    }
}
