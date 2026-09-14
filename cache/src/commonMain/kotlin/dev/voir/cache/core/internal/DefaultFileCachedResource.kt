package dev.voir.cache.core.internal

import dev.voir.cache.core.config.CacheManagerConfig
import dev.voir.cache.core.file.CachedFile
import dev.voir.cache.core.file.FileCacheDiscarded
import dev.voir.cache.core.file.FileCacheFailure
import dev.voir.cache.core.file.FileCacheLease
import dev.voir.cache.core.file.FileCacheRejected
import dev.voir.cache.core.file.FileCacheRejectionReason
import dev.voir.cache.core.file.FileCacheResult
import dev.voir.cache.core.file.FileCacheSpec
import dev.voir.cache.core.file.FileCacheSuccess
import dev.voir.cache.core.file.FileCacheUnavailable
import dev.voir.cache.core.file.FileFetcher
import dev.voir.cache.core.file.FileWriteSink
import dev.voir.cache.core.file.store.FileEntryTooLargeException
import dev.voir.cache.core.file.store.FileStoreAddress
import dev.voir.cache.core.file.store.FileStoreCommitRequest
import dev.voir.cache.core.file.store.FileStoreCommitted
import dev.voir.cache.core.file.store.FileStoreRejected
import dev.voir.cache.core.file.store.FileStoreWriteSession
import dev.voir.cache.core.file.store.StagedFile
import dev.voir.cache.core.file.store.StoredFileRecord
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.memory.InMemoryResourceStore
import dev.voir.cache.core.memory.expirationAt
import dev.voir.cache.core.model.CacheMetadata
import dev.voir.cache.core.model.CacheSnapshot
import dev.voir.cache.core.model.FetchFailure
import dev.voir.cache.core.model.FetchResult
import dev.voir.cache.core.model.FetchSuccess
import dev.voir.cache.core.policy.FreshnessPolicy
import dev.voir.cache.core.time.CacheClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Coordinates typed file descriptors, staged writes, durable records, and physical leases. */
internal class DefaultFileCachedResource<K : Any, E : Any>(
    private val spec: FileCacheSpec<K>,
    private val fetch: FileFetcher<K, E>,
    private val mapUnexpectedFailure: (Throwable) -> E,
    private val store: InMemoryResourceStore<StoredFileRecord, E>,
    private val scope: CoroutineScope,
    private val lifecycle: CommandLifecycle,
    private val config: CacheManagerConfig,
    private val clock: CacheClock,
    private val files: FileStoreCoordinator,
    private val memoryFirst: MemoryFirstTracker<FileStoreAddress>,
    private val freshnessForKey: (K) -> FreshnessPolicy,
) {
    private val refreshFlights = KeyedSingleFlight<EncodedCacheKey, FileCacheResult<E>>(scope)
    private val hydrationMutex = Mutex()
    private val hydrations = mutableMapOf<EncodedCacheKey, Deferred<Unit>>()

    /** Observes logical state without invoking the source. */
    fun observe(key: K): Flow<CacheSnapshot<CachedFile, E>> {
        lifecycle.ensureOpen()
        val encoded = encode(key)
        return flow {
            lifecycle.ensureOpen()
            val initial = store.current(encoded).toPublic()
            emit(initial)
            startHydration(encoded)
            emitAll(
                store.observe(encoded)
                    .map { snapshot -> snapshot.toPublic() }
                    .dropWhile { snapshot -> snapshot == initial }
            )
        }
    }

    /** Awaits file-index hydration and returns current logical state without source loading. */
    suspend fun current(key: K): CacheSnapshot<CachedFile, E> = lifecycle.runCommand {
        val encoded = encode(key)
        awaitHydration(encoded)
        store.current(encoded).toPublic()
    }

    /** Forces or shares one source refresh even when content is fresh. */
    suspend fun refresh(key: K): FileCacheResult<E> = lifecycle.runCommand {
        val encoded = encode(key)
        awaitHydration(encoded)
        refreshFlights
            .joinOrStart(encoded) { performSourceRefresh(key, encoded) }
            .await()
    }

    /**
     * Atomically acquires a physical file lease for the current logical generation.
     *
     * @param key Domain key whose committed generation should be pinned.
     * @return Open lease, or `null` when no usable generation exists.
     */
    suspend fun acquire(key: K): FileCacheLease? = lifecycle.runCommand {
        val encoded = encode(key)
        awaitHydration(encoded)
        try {
            files.ordered { backend ->
                val record = store.current(encoded).value ?: return@ordered null
                backend.acquire(record) ?: run {
                    val address = address(encoded)
                    memoryFirst.markPending(address)
                    store.remove(encoded)
                    backend.remove(address)
                    null
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
    }

    /** Executes one revision-guarded streamed source refresh. */
    private suspend fun performSourceRefresh(
        key: K,
        encoded: EncodedCacheKey,
    ): FileCacheResult<E> {
        val refresh = store.beginRefresh(encoded)
        var session: FileStoreWriteSession? = null
        try {
            session = files.ordered { backend ->
                backend.startWrite(address(encoded), spec.policy.maxEntryBytes)
            }
            return when (val source = loadFromSource(key, session)) {
                is FetchFailure -> {
                    abortQuietly(session)
                    val committed = store.completeRefreshFailure(
                        encoded,
                        refresh.revision,
                        source.error,
                    )
                    if (committed) {
                        FileCacheFailure(source.error, refresh.staleValue?.descriptor())
                    } else {
                        FileCacheDiscarded(store.current(encoded).toPublic(), source.error)
                    }
                }

                is FetchSuccess -> commitStaged(
                    encoded = encoded,
                    expectedRevision = refresh.revision,
                    staged = session.finish(),
                    content = source.value,
                    freshness = freshnessForKey(key),
                )
            }
        } catch (tooLarge: FileEntryTooLargeException) {
            session?.let { abortQuietly(it) }
            store.cancelRefresh(encoded, refresh.revision)
            return FileCacheRejected(
                FileCacheRejectionReason.ENTRY_TOO_LARGE,
                refresh.staleValue?.descriptor(),
            )
        } catch (cancellation: CancellationException) {
            session?.let { abortQuietly(it) }
            withContext(NonCancellable) { store.cancelRefresh(encoded, refresh.revision) }
            throw cancellation
        } catch (_: Throwable) {
            session?.let { abortQuietly(it) }
            store.cancelRefresh(encoded, refresh.revision)
            return FileCacheUnavailable(refresh.staleValue?.descriptor())
        } finally {
            withContext(NonCancellable) { store.unpinOperation(encoded) }
        }
    }

    /** Promotes one staged generation while its memory revision is still authoritative. */
    private suspend fun commitStaged(
        encoded: EncodedCacheKey,
        expectedRevision: Long,
        staged: StagedFile,
        content: dev.voir.cache.core.file.FileContentMetadata,
        freshness: FreshnessPolicy,
    ): FileCacheResult<E> = try {
        files.ordered { backend ->
            if (!store.isRevisionCurrent(encoded, expectedRevision)) {
                backend.discard(staged)
                return@ordered FileCacheDiscarded(store.current(encoded).toPublic())
            }
            val now = clock.now()
            val metadata = CacheMetadata(
                validatedAt = now,
                expiresAt = expirationAt(freshness, now),
            )
            when (
                val committed = backend.commit(
                    FileStoreCommitRequest(
                        address = address(encoded),
                        staged = staged,
                        content = content,
                        metadata = metadata,
                        policy = spec.policy,
                        writtenAt = now,
                    )
                )
            ) {
                is FileStoreCommitted -> {
                    val memoryCommit = store.completeRefreshSuccess(
                        encoded,
                        expectedRevision,
                        committed.record,
                        metadata,
                    )
                    if (memoryCommit == null) {
                        FileCacheDiscarded(store.current(encoded).toPublic())
                    } else {
                        memoryFirst.markDurable(address(encoded))
                        FileCacheSuccess(
                            committed.record.descriptor(),
                            memoryCommit.snapshot.freshness,
                        )
                    }
                }

                is FileStoreRejected -> {
                    store.cancelRefresh(encoded, expectedRevision)
                    FileCacheRejected(
                        committed.reason,
                        store.current(encoded).value?.descriptor(),
                    )
                }
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        try {
            files.ordered { backend -> backend.discard(staged) }
        } catch (_: Throwable) {
            // The store owns recovery of unreachable staging generations.
        }
        store.cancelRefresh(encoded, expectedRevision)
        FileCacheUnavailable(store.current(encoded).value?.descriptor())
    }

    private suspend fun awaitHydration(encoded: EncodedCacheKey) {
        startHydration(encoded).await()
    }

    private suspend fun startHydration(encoded: EncodedCacheKey): Deferred<Unit> {
        val retained = store.contains(encoded)
        val pending = memoryFirst.isPending(address(encoded))
        return hydrationMutex.withLock {
            releaseCompletedHydrations()
            val existing = hydrations[encoded]
            if (existing != null && (!existing.isCompleted || retained || pending)) {
                return@withLock existing
            }
            scope.async { performHydration(encoded) }
                .also { created -> hydrations[encoded] = created }
        }
    }

    /** Drops finished hydration handles for addresses the memory bound no longer retains. */
    private fun releaseCompletedHydrations() {
        if (hydrations.size <= spec.policy.maxEntries * HYDRATION_HANDLE_SLACK) return
        val retained = store.retainedKeys
        val iterator = hydrations.iterator()
        while (iterator.hasNext()) {
            val (key, hydration) = iterator.next()
            if (hydration.isCompleted && key !in retained) iterator.remove()
        }
    }

    private suspend fun performHydration(encoded: EncodedCacheKey) {
        val hydration = store.beginHydration(encoded)
        try {
            files.ordered { backend ->
                val address = address(encoded)
                if (memoryFirst.isPending(address)) {
                    store.completeHydrationAbsent(encoded, hydration.revision)
                    return@ordered
                }
                val record = backend.read(address)
                when {
                    record == null || memoryFirst.isSuppressed(address, record.writtenAt) ->
                        store.completeHydrationAbsent(encoded, hydration.revision)

                    record.address != address ||
                        record.sizeBytes > spec.policy.maxEntryBytes -> {
                        backend.remove(address)
                        store.completeHydrationAbsent(encoded, hydration.revision)
                    }

                    else -> store.completeHydrationSuccess(
                        encoded,
                        hydration.revision,
                        record,
                        record.metadata,
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) { store.cancelHydration(encoded, hydration.revision) }
            throw cancellation
        } catch (_: Throwable) {
            withContext(NonCancellable) {
                store.completeHydrationAbsent(encoded, hydration.revision)
            }
        } finally {
            withContext(NonCancellable) { store.unpinOperation(encoded) }
        }
    }

    private suspend fun loadFromSource(
        key: K,
        sink: FileWriteSink,
    ): FetchResult<dev.voir.cache.core.file.FileContentMetadata, E> = try {
        fetch.fetch(key, sink)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (tooLarge: FileEntryTooLargeException) {
        throw tooLarge
    } catch (unexpected: Throwable) {
        FetchFailure(mapUnexpectedFailure(unexpected))
    }

    private suspend fun abortQuietly(session: FileStoreWriteSession) {
        withContext(NonCancellable) {
            try {
                session.abort()
            } catch (_: Throwable) {
                // Recovery removes an unreachable staging generation.
            }
        }
    }

    private fun encode(key: K): EncodedCacheKey = spec.keyEncoder.encode(key).also { encoded ->
        require(encoded.partition.value.length <= config.maxPartitionLength) {
            "Cache partition exceeds maxPartitionLength=${config.maxPartitionLength}."
        }
        require(encoded.value.length <= config.maxKeyLength) {
            "Encoded cache key exceeds maxKeyLength=${config.maxKeyLength}."
        }
    }

    private fun address(key: EncodedCacheKey): FileStoreAddress = FileStoreAddress(
        namespace = spec.namespace,
        partition = key.partition,
        key = key.value,
    )

    private companion object {
        /** Sweep only once the handle map is a multiple of the retained memory bound. */
        const val HYDRATION_HANDLE_SLACK: Int = 2
    }
}

private fun StoredFileRecord.descriptor(): CachedFile = CachedFile(sizeBytes, content)

private fun <E : Any> CacheSnapshot<StoredFileRecord, E>.toPublic(): CacheSnapshot<CachedFile, E> =
    CacheSnapshot(
        value = value?.descriptor(),
        freshness = freshness,
        activity = activity,
        metadata = metadata,
        refreshError = refreshError,
    )
