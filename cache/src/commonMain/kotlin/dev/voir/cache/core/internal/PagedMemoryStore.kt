package dev.voir.cache.core.internal

import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.memory.InMemoryClearTarget
import dev.voir.cache.core.memory.expirationAt
import dev.voir.cache.core.memory.freshnessAt
import dev.voir.cache.core.model.CacheMetadata
import dev.voir.cache.core.paging.FetchedPage
import dev.voir.cache.core.paging.PagedCacheActivity
import dev.voir.cache.core.paging.PagedCacheBoundary
import dev.voir.cache.core.paging.PagedCacheSnapshot
import dev.voir.cache.core.paging.PagedDuplicatePolicy
import dev.voir.cache.core.paging.PagedWorkStart
import dev.voir.cache.core.policy.FreshnessPolicy
import dev.voir.cache.core.policy.PagedMemoryPolicy
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

/**
 * Atomic observable accumulation state for one paged resource.
 *
 * Accumulated pages are one logical value. Reloading the first page therefore replaces the whole
 * accumulation rather than merging into it: once the source reorders, inserts, or removes an item,
 * every cursor obtained from the previous generation describes a different window, so retained
 * later pages would duplicate or omit items. Appends merge by item identity so an overlapping page
 * cannot introduce duplicates either.
 *
 * @param I Non-null accumulated item type.
 * @param C Source cursor type.
 * @param E Application-defined source error type.
 */
internal class PagedMemoryStore<I : Any, C : Any, E : Any>(
    override val namespace: CacheNamespace,
    private val itemId: (I) -> Any,
    private val duplicatePolicy: PagedDuplicatePolicy,
    private val freshnessPolicy: FreshnessPolicy,
    private val memoryPolicy: PagedMemoryPolicy,
    private val clock: CacheClock,
) : InMemoryClearTarget {
    private val mutex = Mutex()
    private val state = MutableStateFlow<Map<EncodedCacheKey, PagedEntry<I, C, E>>>(emptyMap())
    private val observerPins = mutableMapOf<EncodedCacheKey, Int>()
    private val revisions = mutableMapOf<EncodedCacheKey, Long>()
    private val accessSequences = mutableMapOf<EncodedCacheKey, Long>()
    private var accessSequence = 0L

    /** Observes [key] and pins it against eviction for the collector's lifetime. */
    fun observe(key: EncodedCacheKey): Flow<PagedCacheSnapshot<I, C, E>> = flow {
        pinObserver(key)
        try {
            emitAll(
                state
                    .map { entries -> entries[key]?.snapshot ?: PagedCacheSnapshot() }
                    .distinctUntilChanged()
            )
        } finally {
            withContext(NonCancellable) { unpinObserver(key) }
        }
    }

    /** Reads [key], evaluates freshness, and records an access. */
    suspend fun current(key: EncodedCacheKey): PagedCacheSnapshot<I, C, E> = mutex.withLock {
        val entry = state.value[key] ?: return@withLock PagedCacheSnapshot()
        touch(key)
        val evaluated = entry.evaluated()
        if (evaluated !== entry) publish(state.value + (key to evaluated))
        evaluated.snapshot
    }

    /**
     * Starts a replacement generation for an initial load or a refresh.
     *
     * @param key Encoded query identity being loaded.
     * @param activity Reported work, which must not be an append.
     * @return Captured revision and the state visible while work runs.
     */
    suspend fun beginReplacement(
        key: EncodedCacheKey,
        activity: PagedCacheActivity,
    ): PagedWorkStart<I, C, E> = mutex.withLock {
        require(
            activity == PagedCacheActivity.LOADING || activity == PagedCacheActivity.REFRESHING
        ) { "A paged replacement must report loading or refreshing." }
        val revision = advanceRevision(key)
        val existing = state.value[key]?.evaluated()
        val loading = (existing?.snapshot ?: PagedCacheSnapshot()).copy(
            activity = activity,
            refreshError = null,
            appendError = null,
        )
        touch(key)
        publish(enforceBounds(state.value + (key to PagedEntry(loading)), key))
        PagedWorkStart(revision, loading, cursor = null)
    }

    /**
     * Starts append work for the current committed cursor.
     *
     * @param key Encoded query identity being extended.
     * @return Captured revision and cursor, or `null` when no further page may be loaded.
     */
    suspend fun beginAppend(key: EncodedCacheKey): PagedWorkStart<I, C, E>? = mutex.withLock {
        val entry = state.value[key]?.evaluated() ?: return@withLock null
        val snapshot = entry.snapshot
        val cursor = snapshot.nextCursor ?: return@withLock null
        if (snapshot.boundary != null || snapshot.activity != PagedCacheActivity.IDLE) {
            return@withLock null
        }
        if (snapshot.pageCount >= memoryPolicy.maxPages) {
            publish(
                state.value + (
                    key to entry.copy(
                        snapshot = snapshot.copy(boundary = PagedCacheBoundary.MAX_PAGES)
                    )
                )
            )
            return@withLock null
        }
        val appending = entry.copy(
            snapshot = snapshot.copy(
                activity = PagedCacheActivity.APPENDING,
                appendError = null,
            ),
        )
        touch(key)
        publish(state.value + (key to appending))
        PagedWorkStart(revisionOf(key), appending.snapshot, cursor)
    }

    /**
     * Replaces the whole accumulation with one freshly loaded first page.
     *
     * @param key Encoded query identity receiving the page.
     * @param expectedRevision Revision captured by [beginReplacement].
     * @param page Source page starting the new generation.
     * @return Committed snapshot, or `null` when a newer command won.
     */
    suspend fun completeReplacement(
        key: EncodedCacheKey,
        expectedRevision: Long,
        page: FetchedPage<I, C>,
    ): PagedCacheSnapshot<I, C, E>? = mutex.withLock {
        if (revisionOf(key) != expectedRevision) return@withLock null
        val now = clock.now()
        val metadata = CacheMetadata(now, expirationAt(freshnessPolicy, now))
        var boundary = terminalBoundary(page.items, requestedCursor = null, page.nextCursor, emptySet())
        var items = normalize(page.items)
        if (items.size > memoryPolicy.maxItemsPerQuery) {
            items = items.take(memoryPolicy.maxItemsPerQuery)
            boundary = PagedCacheBoundary.MAX_ITEMS_PER_QUERY
        }
        val snapshot = PagedCacheSnapshot<I, C, E>(
            items = items,
            nextCursor = page.nextCursor,
            totalCount = page.totalCount,
            pageCount = 1,
            freshness = freshnessAt(metadata, now),
            activity = PagedCacheActivity.IDLE,
            metadata = metadata,
            boundary = boundary,
        )
        touch(key)
        publish(enforceBounds(state.value + (key to PagedEntry(snapshot)), key))
        state.value[key]?.snapshot
    }

    /**
     * Merges one appended page into the accumulation.
     *
     * @param key Encoded query identity being extended.
     * @param expectedRevision Revision captured by [beginAppend].
     * @param requestedCursor Cursor the source was asked to continue from.
     * @param page Source page following [requestedCursor].
     * @return Committed snapshot, or `null` when a newer command won.
     */
    suspend fun completeAppend(
        key: EncodedCacheKey,
        expectedRevision: Long,
        requestedCursor: C,
        page: FetchedPage<I, C>,
    ): PagedCacheSnapshot<I, C, E>? = mutex.withLock {
        if (revisionOf(key) != expectedRevision) return@withLock null
        val entry = state.value[key] ?: return@withLock null
        if (entry.snapshot.nextCursor != requestedCursor) return@withLock null
        val seen = entry.seenCursors + requestedCursor
        var boundary = terminalBoundary(page.items, requestedCursor, page.nextCursor, seen)
        var items = normalize(entry.snapshot.items + page.items)
        val pageCount = entry.snapshot.pageCount + 1
        if (pageCount >= memoryPolicy.maxPages && page.nextCursor != null) {
            boundary = PagedCacheBoundary.MAX_PAGES
        }
        if (items.size > memoryPolicy.maxItemsPerQuery) {
            items = items.take(memoryPolicy.maxItemsPerQuery)
            boundary = PagedCacheBoundary.MAX_ITEMS_PER_QUERY
        }
        val committed = entry.copy(
            snapshot = entry.snapshot.copy(
                items = items,
                nextCursor = page.nextCursor,
                totalCount = page.totalCount ?: entry.snapshot.totalCount,
                pageCount = pageCount,
                activity = PagedCacheActivity.IDLE,
                appendError = null,
                boundary = boundary,
            ),
            seenCursors = seen,
        )
        touch(key)
        publish(enforceBounds(state.value + (key to committed), key))
        state.value[key]?.snapshot
    }

    /**
     * Publishes a typed source failure while the work generation is still authoritative.
     *
     * @param key Encoded query identity whose command failed.
     * @param expectedRevision Revision captured when the command started.
     * @param activity Work that failed, deciding which error field is published.
     * @param error Application-defined source error.
     * @return Published snapshot, or `null` when a newer command won.
     */
    suspend fun completeFailure(
        key: EncodedCacheKey,
        expectedRevision: Long,
        activity: PagedCacheActivity,
        error: E,
    ): PagedCacheSnapshot<I, C, E>? = mutex.withLock {
        if (revisionOf(key) != expectedRevision) return@withLock null
        val snapshot = state.value[key]?.snapshot ?: PagedCacheSnapshot()
        val failed = snapshot.copy(
            activity = PagedCacheActivity.IDLE,
            refreshError = if (activity == PagedCacheActivity.APPENDING) {
                snapshot.refreshError
            } else {
                error
            },
            appendError = if (activity == PagedCacheActivity.APPENDING) error else null,
        )
        touch(key)
        publish(enforceBounds(state.value + (key to PagedEntry(failed, seenCursorsOf(key))), key))
        failed
    }

    /**
     * Clears transient activity after cancellation when the generation is still current.
     *
     * @param key Encoded query identity whose command was cancelled.
     * @param expectedRevision Revision captured when the command started.
     */
    suspend fun cancelWork(key: EncodedCacheKey, expectedRevision: Long) {
        mutex.withLock {
            if (revisionOf(key) != expectedRevision) return@withLock
            val entry = state.value[key] ?: return@withLock
            if (entry.snapshot.pageCount == 0) {
                publish(state.value - key)
                forget(key)
            } else {
                publish(
                    state.value + (
                        key to entry.copy(
                            snapshot = entry.snapshot.copy(activity = PagedCacheActivity.IDLE)
                        )
                    )
                )
            }
        }
    }

    /** Removes every accumulated query and publishes absent state to observers. */
    override suspend fun clearAll() {
        mutex.withLock {
            revisions.keys.toList().forEach(::advanceRevision)
            publish(emptyMap())
            revisions.keys.toList().forEach(::forget)
        }
    }

    /** Addresses that currently hold accumulated state. */
    val retainedKeys: Set<EncodedCacheKey>
        get() = state.value.keys

    /** Number of addresses with retained per-address bookkeeping. */
    val trackedAddressCount: Int
        get() = revisions.size

    private fun seenCursorsOf(key: EncodedCacheKey): Set<C> =
        state.value[key]?.seenCursors ?: emptySet()

    /**
     * Removes repeated identities, keeping first-seen order.
     *
     * @param items Accumulated items followed by newly loaded items.
     * @return Items with one entry per identity.
     */
    private fun normalize(items: List<I>): List<I> {
        val positions = mutableMapOf<Any, Int>()
        val normalized = mutableListOf<I>()
        items.forEach { item ->
            val id = itemId(item)
            val position = positions[id]
            if (position == null) {
                positions[id] = normalized.size
                normalized += item
            } else if (duplicatePolicy == PagedDuplicatePolicy.REPLACE_EXISTING) {
                normalized[position] = item
            }
        }
        return normalized
    }

    /** Detects a source that cannot or should not be asked for another page. */
    private fun terminalBoundary(
        sourceItems: List<I>,
        requestedCursor: C?,
        nextCursor: C?,
        seenCursors: Set<C>,
    ): PagedCacheBoundary? = when {
        sourceItems.isEmpty() -> PagedCacheBoundary.EMPTY_PAGE
        nextCursor == null -> PagedCacheBoundary.SOURCE_END
        nextCursor == requestedCursor || nextCursor in seenCursors ->
            PagedCacheBoundary.REPEATED_CURSOR

        else -> null
    }

    /** Evicts whole queries, then truncates the committing query, until every bound holds. */
    private fun enforceBounds(
        entries: Map<EncodedCacheKey, PagedEntry<I, C, E>>,
        protectedKey: EncodedCacheKey,
    ): Map<EncodedCacheKey, PagedEntry<I, C, E>> {
        var bounded = entries
        while (bounded.size > memoryPolicy.maxQueries) {
            val candidate = evictionCandidate(bounded, protectedKey) ?: break
            bounded = bounded - candidate
            forget(candidate)
        }
        while (bounded.totalItems() > memoryPolicy.maxTotalItems) {
            val candidate = evictionCandidate(bounded, protectedKey) ?: break
            bounded = bounded - candidate
            forget(candidate)
        }
        val overflow = bounded.totalItems() - memoryPolicy.maxTotalItems
        if (overflow <= 0) return bounded
        val protected = bounded[protectedKey] ?: return bounded
        val retained = (protected.snapshot.items.size - overflow).coerceAtLeast(0)
        return bounded + (
            protectedKey to protected.copy(
                snapshot = protected.snapshot.copy(
                    items = protected.snapshot.items.take(retained),
                    boundary = PagedCacheBoundary.MAX_TOTAL_ITEMS,
                )
            )
        )
    }

    private fun Map<EncodedCacheKey, PagedEntry<I, C, E>>.totalItems(): Int =
        values.sumOf { entry -> entry.snapshot.items.size }

    private fun evictionCandidate(
        entries: Map<EncodedCacheKey, PagedEntry<I, C, E>>,
        protectedKey: EncodedCacheKey,
    ): EncodedCacheKey? = entries
        .filter { (key, entry) ->
            key != protectedKey &&
                observerPins.getOrElse(key) { 0 } == 0 &&
                entry.snapshot.activity == PagedCacheActivity.IDLE
        }
        .minWithOrNull(
            compareBy<Map.Entry<EncodedCacheKey, PagedEntry<I, C, E>>> {
                accessSequences.getOrElse(it.key) { 0L }
            }.thenBy { it.key.partition.value }.thenBy { it.key.value }
        )
        ?.key

    private suspend fun pinObserver(key: EncodedCacheKey) {
        mutex.withLock {
            observerPins[key] = observerPins.getOrElse(key) { 0 } + 1
            if (key in state.value) touch(key)
        }
    }

    private suspend fun unpinObserver(key: EncodedCacheKey) {
        mutex.withLock {
            val remaining = observerPins.getOrElse(key) { 0 } - 1
            if (remaining > 0) observerPins[key] = remaining else observerPins.remove(key)
            publish(enforceBounds(state.value, protectedKey = key))
            if (key !in state.value) forget(key)
        }
    }

    private fun PagedEntry<I, C, E>.evaluated(): PagedEntry<I, C, E> {
        val metadata = snapshot.metadata ?: return this
        val evaluated = freshnessAt(metadata, clock.now())
        return if (snapshot.freshness == evaluated) this else copy(
            snapshot = snapshot.copy(freshness = evaluated)
        )
    }

    private fun touch(key: EncodedCacheKey) {
        accessSequence += 1
        accessSequences[key] = accessSequence
    }

    private fun forget(key: EncodedCacheKey) {
        if (key in state.value || observerPins.containsKey(key)) return
        accessSequences.remove(key)
        revisions.remove(key)
    }

    private fun revisionOf(key: EncodedCacheKey): Long = revisions.getOrPut(key) { 0L }

    private fun advanceRevision(key: EncodedCacheKey): Long =
        (revisionOf(key) + 1).also { next -> revisions[key] = next }

    private fun publish(entries: Map<EncodedCacheKey, PagedEntry<I, C, E>>) {
        state.value = entries
    }
}

/** Accumulated state plus the cursors already consumed by this generation. */
private data class PagedEntry<I : Any, C : Any, E : Any>(
    val snapshot: PagedCacheSnapshot<I, C, E>,
    val seenCursors: Set<C> = emptySet(),
)
