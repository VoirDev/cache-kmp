package dev.voir.cache.core.internal

import dev.voir.cache.core.PagedCachedResource
import dev.voir.cache.core.config.CacheManagerConfig
import dev.voir.cache.core.config.PagedCacheSpec
import dev.voir.cache.core.diagnostics.CacheEventType
import dev.voir.cache.core.diagnostics.CacheOperationKind
import dev.voir.cache.core.diagnostics.CacheOperationOrigin
import dev.voir.cache.core.diagnostics.CacheOperationOutcome
import dev.voir.cache.core.diagnostics.CacheSingleFlightStatus
import dev.voir.cache.core.key.EncodedCacheFamily
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.model.CacheFreshness
import dev.voir.cache.core.model.FetchFailure
import dev.voir.cache.core.model.FetchResult
import dev.voir.cache.core.model.FetchSuccess
import dev.voir.cache.core.paging.FetchedPage
import dev.voir.cache.core.paging.PageLoadRequest
import dev.voir.cache.core.paging.PagedCacheActivity
import dev.voir.cache.core.paging.PagedCacheSnapshot
import dev.voir.cache.core.paging.PagedLoadDiscarded
import dev.voir.cache.core.paging.PagedLoadFailure
import dev.voir.cache.core.paging.PagedLoadResult
import dev.voir.cache.core.paging.PagedLoadSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Manager-owned coordination for one memory-only accumulated paged namespace. */
internal class DefaultPagedCachedResource<Q : Any, C : Any, I : Any, ID : Any, E : Any>(
    private val spec: PagedCacheSpec<Q, C, I, ID>,
    private val fetch: suspend (PageLoadRequest<Q, C>) -> FetchResult<FetchedPage<I, C>, E>,
    private val mapUnexpectedFailure: (Throwable) -> E,
    private val store: PagedMemoryStore<C, I, ID, E>,
    private val scope: CoroutineScope,
    private val lifecycle: CommandLifecycle,
    private val events: CacheEventEmitter,
    private val config: CacheManagerConfig,
    private val familyForQuery: ((Q) -> EncodedCacheFamily)? = null,
) : PagedCachedResource<Q, C, I, E> {
    private val flightMutex = Mutex()
    private val replacements = mutableMapOf<EncodedCacheKey, Deferred<PagedFlight<I, C, E>>>()
    private val appends = mutableMapOf<EncodedCacheKey, Deferred<PagedFlight<I, C, E>>>()
    private val replacementWaiters = mutableMapOf<EncodedCacheKey, Int>()
    private val appendWaiters = mutableMapOf<EncodedCacheKey, Int>()

    override fun observe(query: Q): Flow<PagedCacheSnapshot<I, C, E>> {
        lifecycle.ensureOpen()
        return store.observe(encode(query))
    }

    override suspend fun current(query: Q): PagedCacheSnapshot<I, C, E> = lifecycle.runCommand {
        val key = encode(query)
        val operation = events.start(
            CacheOperationKind.PAGED_CURRENT,
            spec.namespace,
            key,
            CacheOperationOrigin.MEMORY,
        )
        store.current(key).also { snapshot ->
            events.complete(
                operation,
                if (snapshot.metadata == null) CacheOperationOutcome.ABSENT
                else CacheOperationOutcome.COMMITTED,
                memoryEntryCount = store.entryCount(),
                memoryEntryLimit = spec.memory.maxQueries,
            )
        }
    }

    override suspend fun ensureLoaded(query: Q): PagedLoadResult<I, C, E> =
        lifecycle.runCommand {
            val key = encode(query)
            val snapshot = store.current(key)
            if (snapshot.metadata != null && snapshot.freshness == CacheFreshness.FRESH) {
                events.emit(CacheEventType.FRESH_SKIP, spec.namespace, key)
                val operation = events.start(
                    CacheOperationKind.PAGED_ENSURE_LOADED,
                    spec.namespace,
                    key,
                    CacheOperationOrigin.MEMORY,
                )
                return@runCommand PagedLoadSuccess(snapshot, false, 0).also {
                    complete(operation, CacheOperationOutcome.FRESH_SKIP)
                }
            }
            if (snapshot.metadata == null) events.emit(CacheEventType.MISS, spec.namespace, key)
            else events.emit(CacheEventType.STALE_REFRESH, spec.namespace, key)
            joinReplacement(
                query,
                key,
                if (snapshot.metadata == null) PagedCacheActivity.INITIAL_LOADING
                else PagedCacheActivity.REFRESHING,
                CacheOperationKind.PAGED_ENSURE_LOADED,
                if (snapshot.metadata == null) CacheOperationOrigin.MISS_LOAD
                else CacheOperationOrigin.STALE_LOAD,
            )
        }

    override suspend fun refresh(query: Q): PagedLoadResult<I, C, E> = lifecycle.runCommand {
        val key = encode(query)
        events.emit(CacheEventType.FORCED_REFRESH, spec.namespace, key)
        joinReplacement(
            query,
            key,
            PagedCacheActivity.REFRESHING,
            CacheOperationKind.PAGED_REFRESH,
            CacheOperationOrigin.FORCED_REFRESH,
        )
    }

    override suspend fun loadNext(query: Q): PagedLoadResult<I, C, E> = lifecycle.runCommand {
        val key = encode(query)
        joinAppend(query, key)
    }

    override suspend fun update(query: Q, transform: (List<I>) -> List<I>): Boolean =
        lifecycle.runCommand {
            val key = encode(query)
            val operation = events.start(
                CacheOperationKind.PAGED_UPDATE,
                spec.namespace,
                key,
                CacheOperationOrigin.EXPLICIT,
            )
            store.update(key, transform).also { updated ->
                complete(
                    operation,
                    if (updated) CacheOperationOutcome.COMMITTED else CacheOperationOutcome.ABSENT,
                )
            }
        }

    override suspend fun invalidate(query: Q) {
        lifecycle.runCommand {
            val key = encode(query)
            val operation = events.start(
                CacheOperationKind.PAGED_INVALIDATE,
                spec.namespace,
                key,
                CacheOperationOrigin.EXPLICIT,
            )
            store.invalidate(key)
            events.emit(CacheEventType.INVALIDATION, spec.namespace, key)
            complete(operation, CacheOperationOutcome.COMMITTED)
        }
    }

    override suspend fun remove(query: Q) {
        lifecycle.runCommand {
            val key = encode(query)
            val operation = events.start(
                CacheOperationKind.PAGED_REMOVE,
                spec.namespace,
                key,
                CacheOperationOrigin.EXPLICIT,
            )
            store.remove(key)
            complete(operation, CacheOperationOutcome.COMMITTED)
        }
    }

    override suspend fun clear() {
        lifecycle.runCommand {
            val operation = events.start(
                CacheOperationKind.PAGED_CLEAR,
                spec.namespace,
                origin = CacheOperationOrigin.EXPLICIT,
            )
            store.clearAll()
            complete(operation, CacheOperationOutcome.COMMITTED)
        }
    }

    internal suspend fun invalidateEncodedFamily(family: EncodedCacheFamily): Int =
        lifecycle.runCommand {
            validateFamily(family)
            val operation = events.start(
                CacheOperationKind.INVALIDATE_FAMILY,
                spec.namespace,
                origin = CacheOperationOrigin.EXPLICIT,
            )
            store.invalidateFamily(family).also { affected ->
                complete(operation, CacheOperationOutcome.COMMITTED, affected.toLong())
            }
        }

    internal suspend fun removeEncodedFamily(family: EncodedCacheFamily): Int =
        lifecycle.runCommand {
            validateFamily(family)
            val operation = events.start(
                CacheOperationKind.REMOVE_FAMILY,
                spec.namespace,
                origin = CacheOperationOrigin.EXPLICIT,
            )
            store.removeFamily(family).also { affected ->
                complete(operation, CacheOperationOutcome.COMMITTED, affected.toLong())
            }
        }

    private suspend fun joinReplacement(
        query: Q,
        key: EncodedCacheKey,
        activity: PagedCacheActivity,
        operationKind: CacheOperationKind,
        origin: CacheOperationOrigin,
    ): PagedLoadResult<I, C, E> {
        var joined = false
        val deferred = flightMutex.withLock {
            replacements[key]?.also {
                joined = true
                replacementWaiters[key] = replacementWaiters.getOrElse(key) { 1 } + 1
            } ?: run {
                replacementWaiters[key] = 1
                val start = store.beginReplacement(key, activity)
                val created = scope.async {
                    performReplacement(query, key, start, activity)
                }
                replacements[key] = created
                created.invokeOnCompletion {
                    scope.launch {
                        flightMutex.withLock {
                            if (replacements[key] === created) replacements.remove(key)
                        }
                    }
                }
                created
            }
        }
        if (joined) events.emit(CacheEventType.SINGLE_FLIGHT_JOIN, spec.namespace, key)
        val waiterCount = flightMutex.withLock { replacementWaiters.getOrElse(key) { 1 } }
        val operation = events.start(
            operationKind,
            spec.namespace,
            key,
            if (joined) CacheOperationOrigin.JOINED_WORK else origin,
            singleFlightStatus = if (joined) CacheSingleFlightStatus.JOINED
            else CacheSingleFlightStatus.STARTED,
            waiterCount = waiterCount,
        )
        return try {
            val flight = deferred.await()
            complete(
                operation,
                flight.result.outcome(),
                singleFlightStatus = if (joined) CacheSingleFlightStatus.JOINED
                else CacheSingleFlightStatus.STARTED,
                waiterCount = waiterCount,
                sourceDuration = flight.sourceDuration,
            )
            flight.result
        } catch (cancellation: CancellationException) {
            complete(operation, CacheOperationOutcome.CANCELLED)
            throw cancellation
        } finally {
            flightMutex.withLock {
                val remaining = replacementWaiters.getOrElse(key) { 1 } - 1
                if (remaining > 0) replacementWaiters[key] = remaining
                else replacementWaiters.remove(key)
                if (replacements[key] === deferred && deferred.isCompleted) replacements.remove(key)
            }
        }
    }

    private suspend fun joinAppend(
        query: Q,
        key: EncodedCacheKey,
    ): PagedLoadResult<I, C, E> {
        var joined = false
        val deferred = flightMutex.withLock {
            appends[key]?.also {
                joined = true
                appendWaiters[key] = appendWaiters.getOrElse(key) { 1 } + 1
            } ?: run {
                val start = store.beginAppend(key)
                    ?: return@withLock null
                appendWaiters[key] = 1
                val created = scope.async {
                    performAppend(query, key, start)
                }
                appends[key] = created
                created.invokeOnCompletion {
                    scope.launch {
                        flightMutex.withLock {
                            if (appends[key] === created) appends.remove(key)
                        }
                    }
                }
                created
            }
        }
        if (deferred == null) {
            val snapshot = store.current(key)
            val operation = events.start(
                CacheOperationKind.PAGED_LOAD_NEXT,
                spec.namespace,
                key,
                CacheOperationOrigin.MEMORY,
            )
            return PagedLoadSuccess(snapshot, false, 0).also {
                complete(operation, CacheOperationOutcome.FRESH_SKIP)
            }
        }
        if (joined) events.emit(CacheEventType.SINGLE_FLIGHT_JOIN, spec.namespace, key)
        val waiterCount = flightMutex.withLock { appendWaiters.getOrElse(key) { 1 } }
        val operation = events.start(
            CacheOperationKind.PAGED_LOAD_NEXT,
            spec.namespace,
            key,
            if (joined) CacheOperationOrigin.JOINED_WORK else CacheOperationOrigin.MISS_LOAD,
            singleFlightStatus = if (joined) CacheSingleFlightStatus.JOINED
            else CacheSingleFlightStatus.STARTED,
            waiterCount = waiterCount,
        )
        return try {
            val flight = deferred.await()
            complete(
                operation,
                flight.result.outcome(),
                singleFlightStatus = if (joined) CacheSingleFlightStatus.JOINED
                else CacheSingleFlightStatus.STARTED,
                waiterCount = waiterCount,
                sourceDuration = flight.sourceDuration,
            )
            flight.result
        } catch (cancellation: CancellationException) {
            complete(operation, CacheOperationOutcome.CANCELLED)
            throw cancellation
        } finally {
            flightMutex.withLock {
                val remaining = appendWaiters.getOrElse(key) { 1 } - 1
                if (remaining > 0) appendWaiters[key] = remaining else appendWaiters.remove(key)
                if (appends[key] === deferred && deferred.isCompleted) appends.remove(key)
            }
        }
    }

    private suspend fun performReplacement(
        query: Q,
        key: EncodedCacheKey,
        start: PagedWorkStart<I, C, E>,
        activity: PagedCacheActivity,
    ): PagedFlight<I, C, E> {
        val sourceStarted = TimeSource.Monotonic.markNow()
        val source = load(PageLoadRequest(query, cursor = null))
        val duration = sourceStarted.elapsedNow()
        val result = when (source) {
            is FetchSuccess -> store.completeReplacement(key, start.revision, source.value)
                ?.let { commit -> PagedLoadSuccess(commit.snapshot, true, commit.itemsAdded) }
                ?: PagedLoadDiscarded(store.current(key))
            is FetchFailure -> store.completeFailure(key, start.revision, activity, source.error)
                ?.let { snapshot -> PagedLoadFailure(source.error, snapshot) }
                ?: PagedLoadDiscarded(store.current(key), source.error)
        }
        if (result is PagedLoadDiscarded) {
            events.emit(CacheEventType.DISCARDED_LATE_RESULT, spec.namespace, key)
        }
        return PagedFlight(result, duration)
    }

    private suspend fun performAppend(
        query: Q,
        key: EncodedCacheKey,
        start: PagedWorkStart<I, C, E>,
    ): PagedFlight<I, C, E> {
        val cursor = checkNotNull(start.cursor)
        val sourceStarted = TimeSource.Monotonic.markNow()
        val source = load(PageLoadRequest(query, cursor))
        val duration = sourceStarted.elapsedNow()
        val result = when (source) {
            is FetchSuccess -> store.completeAppend(key, start.revision, cursor, source.value)
                ?.let { commit -> PagedLoadSuccess(commit.snapshot, true, commit.itemsAdded) }
                ?: PagedLoadDiscarded(store.current(key))
            is FetchFailure -> store.completeFailure(
                key,
                start.revision,
                PagedCacheActivity.APPENDING,
                source.error,
            )?.let { snapshot -> PagedLoadFailure(source.error, snapshot) }
                ?: PagedLoadDiscarded(store.current(key), source.error)
        }
        if (result is PagedLoadDiscarded) {
            events.emit(CacheEventType.DISCARDED_LATE_RESULT, spec.namespace, key)
        }
        return PagedFlight(result, duration)
    }

    private suspend fun load(request: PageLoadRequest<Q, C>): FetchResult<FetchedPage<I, C>, E> =
        try {
            fetch(request)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Throwable) {
            FetchFailure(mapUnexpectedFailure(unexpected))
        }

    private fun encode(query: Q): EncodedCacheKey {
        val raw = spec.queryEncoder.encode(query)
        val family = familyForQuery?.invoke(query)
        if (family != null) {
            validateFamily(family)
            require(raw.partition == family.partition) {
                "Encoded paged query and family must belong to the same partition."
            }
        }
        return raw.copy(family = family?.value).also { key ->
            require(key.partition.value.length <= config.maxPartitionLength)
            require(key.value.length <= config.maxKeyLength)
        }
    }

    private fun validateFamily(family: EncodedCacheFamily) {
        require(family.partition.value.length <= config.maxPartitionLength)
        require(family.value.length <= config.maxFamilyLength)
    }

    private suspend fun complete(
        operation: CacheOperationToken,
        outcome: CacheOperationOutcome,
        affectedCount: Long? = null,
        singleFlightStatus: CacheSingleFlightStatus? = null,
        waiterCount: Int? = null,
        sourceDuration: Duration? = null,
    ) {
        events.complete(
            operation,
            outcome,
            sourceDuration = sourceDuration,
            singleFlightStatus = singleFlightStatus,
            waiterCount = waiterCount,
            affectedCount = affectedCount,
            memoryEntryCount = store.entryCount(),
            memoryEntryLimit = spec.memory.maxQueries,
        )
    }

    private fun PagedLoadResult<I, C, E>.outcome(): CacheOperationOutcome = when (this) {
        is PagedLoadSuccess -> CacheOperationOutcome.COMMITTED
        is PagedLoadFailure -> CacheOperationOutcome.SOURCE_FAILURE
        is PagedLoadDiscarded -> CacheOperationOutcome.DISCARDED
    }
}

private data class PagedFlight<I : Any, C : Any, E : Any>(
    val result: PagedLoadResult<I, C, E>,
    val sourceDuration: Duration,
)
