package dev.voir.cache.core.internal

import dev.voir.cache.core.config.CacheManagerConfig
import dev.voir.cache.core.config.PagedCacheSpec
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.model.CacheFreshness
import dev.voir.cache.core.model.FetchFailure
import dev.voir.cache.core.model.FetchResult
import dev.voir.cache.core.model.FetchSuccess
import dev.voir.cache.core.paging.FetchedPage
import dev.voir.cache.core.paging.PagedCacheActivity
import dev.voir.cache.core.paging.PagedCacheSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Coordinates accumulated page loading for one memory-only paged namespace.
 *
 * Equal queries share one in-flight page load. Loading the first page and appending a page are
 * separate flights because a refresh supersedes the accumulation an append would extend.
 *
 * @param Q Domain-owned query type.
 * @param I Non-null accumulated item type.
 * @param C Source cursor type.
 * @param E Application-defined source error type.
 */
internal class DefaultPagedCachedResource<Q : Any, I : Any, C : Any, E : Any>(
    private val spec: PagedCacheSpec<Q, I>,
    private val mapUnexpectedFailure: (Throwable) -> E,
    private val store: PagedMemoryStore<I, C, E>,
    scope: CoroutineScope,
    private val lifecycle: CommandLifecycle,
    private val config: CacheManagerConfig,
) {
    private val flights = KeyedSingleFlight<PagedFlightKey, PagedOutcome<I, C, E>>(scope)

    /** Observes accumulated state without invoking the source. */
    fun observe(query: Q): Flow<PagedCacheSnapshot<I, C, E>> {
        lifecycle.ensureOpen()
        val key = encode(query)
        return flow {
            lifecycle.ensureOpen()
            val initial = store.current(key)
            emit(initial)
            emitAll(store.observe(key).dropWhile { snapshot -> snapshot == initial })
        }
    }

    /**
     * Returns accumulated state, loading the first page only when it is absent or stale.
     *
     * @param query Domain query to resolve.
     * @param load Source loader receiving `null` for the first page.
     * @return Accumulated state and the failure produced by this call, when one occurred.
     */
    suspend fun ensureLoaded(
        query: Q,
        load: suspend (C?) -> FetchResult<FetchedPage<I, C>, E>,
    ): PagedOutcome<I, C, E> = lifecycle.runCommand {
        val key = encode(query)
        val snapshot = store.current(key)
        if (snapshot.pageCount > 0 && snapshot.freshness == CacheFreshness.FRESH) {
            return@runCommand PagedOutcome(snapshot, error = null)
        }
        replace(key, PagedCacheActivity.LOADING, load)
    }

    /**
     * Reloads the first page and replaces the whole accumulation.
     *
     * @param query Domain query to reload.
     * @param load Source loader receiving `null` for the first page.
     * @return Accumulated state and the failure produced by this call, when one occurred.
     */
    suspend fun refresh(
        query: Q,
        load: suspend (C?) -> FetchResult<FetchedPage<I, C>, E>,
    ): PagedOutcome<I, C, E> = lifecycle.runCommand {
        replace(encode(query), PagedCacheActivity.REFRESHING, load)
    }

    /**
     * Appends the page following the committed cursor.
     *
     * @param query Domain query to extend.
     * @param load Source loader receiving the committed cursor.
     * @return Accumulated state, unchanged when no further page may be loaded.
     */
    suspend fun loadNext(
        query: Q,
        load: suspend (C?) -> FetchResult<FetchedPage<I, C>, E>,
    ): PagedOutcome<I, C, E> = lifecycle.runCommand {
        val key = encode(query)
        flights.joinOrStart(PagedFlightKey(key, append = true)) {
            val start = store.beginAppend(key)
                ?: return@joinOrStart PagedOutcome(store.current(key), error = null)
            val cursor = checkNotNull(start.cursor) { "An append flight requires a cursor." }
            runGuarded(key, start.revision, PagedCacheActivity.APPENDING) {
                when (val result = loadFromSource(load, cursor)) {
                    is FetchSuccess -> PagedCommit(
                        snapshot = store.completeAppend(
                            key = key,
                            expectedRevision = start.revision,
                            requestedCursor = cursor,
                            page = result.value,
                        ),
                        error = null,
                    )

                    is FetchFailure -> PagedCommit(
                        snapshot = store.completeFailure(
                            key = key,
                            expectedRevision = start.revision,
                            activity = PagedCacheActivity.APPENDING,
                            error = result.error,
                        ),
                        error = result.error,
                    )
                }
            }
        }.await()
    }

    /** Starts or joins one first-page load that replaces the accumulation. */
    private suspend fun replace(
        key: EncodedCacheKey,
        activity: PagedCacheActivity,
        load: suspend (C?) -> FetchResult<FetchedPage<I, C>, E>,
    ): PagedOutcome<I, C, E> = flights
        .joinOrStart(PagedFlightKey(key, append = false)) {
            val start = store.beginReplacement(key, activity)
            runGuarded(key, start.revision, activity) {
                when (val result = loadFromSource(load, cursor = null)) {
                    is FetchSuccess -> PagedCommit(
                        snapshot = store.completeReplacement(
                            key = key,
                            expectedRevision = start.revision,
                            page = result.value,
                        ),
                        error = null,
                    )

                    is FetchFailure -> PagedCommit(
                        snapshot = store.completeFailure(
                            key = key,
                            expectedRevision = start.revision,
                            activity = activity,
                            error = result.error,
                        ),
                        error = result.error,
                    )
                }
            }
        }
        .await()

    /**
     * Runs one page command, clearing transient activity when it is cancelled or fails unexpectedly.
     *
     * @param key Encoded query identity being changed.
     * @param revision Revision captured when the command started.
     * @param activity Work reported while the command runs.
     * @param commit Command producing the published snapshot, or `null` when superseded.
     * @return Authoritative state after the command, with the failure it produced.
     */
    private suspend inline fun runGuarded(
        key: EncodedCacheKey,
        revision: Long,
        activity: PagedCacheActivity,
        commit: () -> PagedCommit<I, C, E>,
    ): PagedOutcome<I, C, E> = try {
        val committed = commit()
        PagedOutcome(committed.snapshot ?: store.current(key), committed.error)
    } catch (cancellation: CancellationException) {
        withContext(NonCancellable) { store.cancelWork(key, revision) }
        throw cancellation
    } catch (unexpected: Throwable) {
        withContext(NonCancellable) {
            store.completeFailure(key, revision, activity, mapUnexpectedFailure(unexpected))
        }
        throw unexpected
    }

    /** Invokes the bound loader and maps every non-cancellation exception. */
    private suspend fun loadFromSource(
        load: suspend (C?) -> FetchResult<FetchedPage<I, C>, E>,
        cursor: C?,
    ): FetchResult<FetchedPage<I, C>, E> = try {
        load(cursor)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (unexpected: Throwable) {
        FetchFailure(mapUnexpectedFailure(unexpected))
    }

    /** Encodes and validates [query] against manager-wide address limits. */
    private fun encode(query: Q): EncodedCacheKey =
        spec.queryEncoder.encode(query).also { encoded ->
            require(encoded.partition.value.length <= config.maxPartitionLength) {
                "Cache partition exceeds maxPartitionLength=${config.maxPartitionLength}."
            }
            require(encoded.value.length <= config.maxKeyLength) {
                "Encoded cache key exceeds maxKeyLength=${config.maxKeyLength}."
            }
        }
}

/**
 * Authoritative accumulated state after one command, plus the failure that command produced.
 *
 * The failure is reported separately from the snapshot so a caller cannot mistake an error left by
 * an earlier command for the outcome of this one.
 */
internal class PagedOutcome<I : Any, C : Any, E : Any>(
    val snapshot: PagedCacheSnapshot<I, C, E>,
    val error: E?,
)

/** Result of one guarded store commit, whose snapshot is absent when a newer command won. */
internal class PagedCommit<I : Any, C : Any, E : Any>(
    val snapshot: PagedCacheSnapshot<I, C, E>?,
    val error: E?,
)

/** Separates first-page work from append work for the same query. */
private data class PagedFlightKey(
    val key: EncodedCacheKey,
    val append: Boolean,
)
