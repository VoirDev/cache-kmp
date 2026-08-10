package dev.voir.cache.testing

import dev.voir.cache.core.model.FetchResult
import dev.voir.cache.core.paging.FetchedPage
import dev.voir.cache.core.paging.PageLoadRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Deterministic paged source fixture with FIFO scripted results and an optional next-call gate.
 *
 * Consumers pass [fetch] directly to `CacheManager.pagedResource` and use [awaitNextRequest] plus
 * [releaseNextRequest] to coordinate revision-race tests without delays.
 */
public class ControllablePageSource<Q : Any, C : Any, I : Any, E : Any> public constructor() {
    private val mutex: Mutex = Mutex()
    private val scripted: MutableList<FetchResult<FetchedPage<I, C>, E>> = mutableListOf()
    private val requests: MutableList<PageLoadRequest<Q, C>> = mutableListOf()
    private var nextStarted: CompletableDeferred<PageLoadRequest<Q, C>> = CompletableDeferred()
    private var nextRelease: CompletableDeferred<Unit>? = null

    /** Stable bound source function suitable for a paged-resource declaration. */
    public val fetch: suspend (PageLoadRequest<Q, C>) -> FetchResult<FetchedPage<I, C>, E> =
        { request -> execute(request) }

    /** Adds one result returned by the next source invocation. */
    public suspend fun enqueue(result: FetchResult<FetchedPage<I, C>, E>) {
        mutex.withLock { scripted += result }
    }

    /** Makes the next source call pause after recording its request. */
    public suspend fun pauseNextRequest() {
        mutex.withLock {
            check(nextRelease == null) { "A controllable page request is already paused." }
            nextStarted = CompletableDeferred()
            nextRelease = CompletableDeferred()
        }
    }

    /** Awaits the next recorded request without polling or sleeping. */
    public suspend fun awaitNextRequest(): PageLoadRequest<Q, C> = nextStarted.await()

    /** Releases the currently paused request. */
    public suspend fun releaseNextRequest() {
        val gate = mutex.withLock {
            checkNotNull(nextRelease) { "No controllable page request is paused." }
                .also { nextRelease = null }
        }
        gate.complete(Unit)
    }

    /** Returns every recorded request in source invocation order. */
    public suspend fun requests(): List<PageLoadRequest<Q, C>> = mutex.withLock {
        requests.toList()
    }

    private suspend fun execute(
        request: PageLoadRequest<Q, C>,
    ): FetchResult<FetchedPage<I, C>, E> {
        val execution = mutex.withLock {
            requests += request
            nextStarted.complete(request)
            val result = checkNotNull(scripted.firstOrNull()) {
                "No scripted page result is available for request $request."
            }
            scripted.removeAt(0)
            result to nextRelease
        }
        execution.second?.await()
        return execution.first
    }
}
