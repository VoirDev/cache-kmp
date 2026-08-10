package dev.voir.cache.core.internal

import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheFamily
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.memory.InMemoryClearTarget
import dev.voir.cache.core.memory.expirationAt
import dev.voir.cache.core.memory.freshnessAt
import dev.voir.cache.core.model.CacheFreshness
import dev.voir.cache.core.model.CacheMetadata
import dev.voir.cache.core.paging.FetchedPage
import dev.voir.cache.core.paging.PagedCacheActivity
import dev.voir.cache.core.paging.PagedCacheBoundary
import dev.voir.cache.core.paging.PagedCacheSnapshot
import dev.voir.cache.core.paging.PagedDuplicatePolicy
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

/** Atomic accumulated memory state for one paged namespace. */
internal class PagedMemoryStore<C : Any, I : Any, ID : Any, E : Any>(
    override val namespace: CacheNamespace,
    private val itemId: (I) -> ID,
    private val duplicatePolicy: PagedDuplicatePolicy,
    private val freshnessPolicy: FreshnessPolicy,
    private val memoryPolicy: PagedMemoryPolicy,
    private val clock: CacheClock,
    private val onEviction: (EncodedCacheKey, Int, Int) -> Unit = { _, _, _ -> },
) : InMemoryClearTarget {
    override val entryLimit: Int
        get() = memoryPolicy.maxQueries

    private val mutex = Mutex()
    private val state = MutableStateFlow<Map<EncodedCacheKey, PagedStoredEntry<C, I, E>>>(emptyMap())
    private val revisions = mutableMapOf<EncodedCacheKey, Long>()
    private val observerPins = mutableMapOf<EncodedCacheKey, Int>()
    private var accessSequence = 0L

    fun observe(key: EncodedCacheKey): Flow<PagedCacheSnapshot<I, C, E>> = flow {
        pinObserver(key)
        try {
            emitAll(
                state.map { entries -> entries[key]?.snapshot ?: PagedCacheSnapshot() }
                    .distinctUntilChanged()
            )
        } finally {
            withContext(NonCancellable) { unpinObserver(key) }
        }
    }

    suspend fun current(key: EncodedCacheKey): PagedCacheSnapshot<I, C, E> = mutex.withLock {
        val entry = state.value[key] ?: return@withLock PagedCacheSnapshot()
        val evaluated = entry.evaluate().copy(lastAccessSequence = nextAccessSequence())
        publish(state.value + (key to evaluated))
        evaluated.snapshot
    }

    /** Starts a replacement generation for initial load or forced/stale refresh. */
    suspend fun beginReplacement(
        key: EncodedCacheKey,
        activity: PagedCacheActivity,
    ): PagedWorkStart<I, C, E> = mutex.withLock {
        require(
            activity == PagedCacheActivity.INITIAL_LOADING ||
                activity == PagedCacheActivity.REFRESHING
        ) { "Paged replacement activity must be initial loading or refreshing." }
        val revision = advanceRevision(key)
        val existing = state.value[key]?.evaluate()
        val base = existing?.snapshot ?: PagedCacheSnapshot()
        val loading = base.copy(
            activity = activity,
            initialError = if (activity == PagedCacheActivity.INITIAL_LOADING) null else base.initialError,
            refreshError = if (activity == PagedCacheActivity.REFRESHING) null else base.refreshError,
            appendError = null,
        )
        val entry = PagedStoredEntry(
            snapshot = loading,
            seenCursors = existing?.seenCursors ?: emptySet(),
            lastAccessSequence = nextAccessSequence(),
        )
        publish(enforceBounds(state.value + (key to entry), key))
        PagedWorkStart(revision, loading, cursor = null)
    }

    /** Starts append work for the current committed cursor, or returns `null` when unavailable. */
    suspend fun beginAppend(key: EncodedCacheKey): PagedWorkStart<I, C, E>? = mutex.withLock {
        val entry = state.value[key]?.evaluate() ?: return@withLock null
        val snapshot = entry.snapshot
        val cursor = snapshot.nextCursor ?: return@withLock null
        if (snapshot.boundary != null || snapshot.activity != PagedCacheActivity.IDLE) {
            return@withLock null
        }
        if (snapshot.pageCount >= memoryPolicy.maxPagesPerQuery) {
            val limited = entry.copy(
                snapshot = snapshot.copy(boundary = PagedCacheBoundary.MAX_PAGES),
                lastAccessSequence = nextAccessSequence(),
            )
            publish(state.value + (key to limited))
            return@withLock null
        }
        val appending = entry.copy(
            snapshot = snapshot.copy(
                activity = PagedCacheActivity.APPENDING,
                appendError = null,
            ),
            lastAccessSequence = nextAccessSequence(),
        )
        publish(state.value + (key to appending))
        PagedWorkStart(revisionOf(key), appending.snapshot, cursor)
    }

    /** Commits an initial/refresh page only while its replacement generation is current. */
    suspend fun completeReplacement(
        key: EncodedCacheKey,
        expectedRevision: Long,
        page: FetchedPage<I, C>,
    ): PagedCommit<I, C, E>? = mutex.withLock {
        if (revisionOf(key) != expectedRevision) return@withLock null
        val normalized = normalize(page.items)
        val metadata = metadata()
        var boundary = terminalBoundary(page.items, requestedCursor = null, page.nextCursor, emptySet())
        var items = normalized
        if (items.size > memoryPolicy.maxItemsPerQuery) {
            items = items.take(memoryPolicy.maxItemsPerQuery)
            boundary = PagedCacheBoundary.MAX_ITEMS_PER_QUERY
        }
        if (items.size > memoryPolicy.maxTotalItems) {
            items = items.take(memoryPolicy.maxTotalItems)
            boundary = PagedCacheBoundary.MAX_TOTAL_ITEMS
        }
        val snapshot = PagedCacheSnapshot<I, C, E>(
            items = items,
            nextCursor = page.nextCursor,
            totalCount = page.totalCount,
            pageCount = 1,
            freshness = freshnessAt(metadata, clock.now()),
            activity = PagedCacheActivity.IDLE,
            metadata = metadata,
            boundary = boundary,
        )
        val entry = PagedStoredEntry(
            snapshot = snapshot,
            seenCursors = emptySet(),
            lastAccessSequence = nextAccessSequence(),
        )
        publish(enforceBounds(state.value + (key to entry), key))
        val finalSnapshot = checkNotNull(state.value[key]).snapshot
        PagedCommit(finalSnapshot, finalSnapshot.items.size)
    }

    /** Commits one append only while generation and expected cursor still match. */
    suspend fun completeAppend(
        key: EncodedCacheKey,
        expectedRevision: Long,
        requestedCursor: C,
        page: FetchedPage<I, C>,
    ): PagedCommit<I, C, E>? = mutex.withLock {
        if (revisionOf(key) != expectedRevision) return@withLock null
        val entry = state.value[key] ?: return@withLock null
        if (entry.snapshot.nextCursor != requestedCursor) return@withLock null
        val before = entry.snapshot.items
        var combined = merge(before, page.items)
        val seen = entry.seenCursors + requestedCursor
        var boundary = terminalBoundary(page.items, requestedCursor, page.nextCursor, seen)
        if (entry.snapshot.pageCount + 1 >= memoryPolicy.maxPagesPerQuery && page.nextCursor != null) {
            boundary = PagedCacheBoundary.MAX_PAGES
        }
        if (combined.size > memoryPolicy.maxItemsPerQuery) {
            combined = combined.take(memoryPolicy.maxItemsPerQuery)
            boundary = PagedCacheBoundary.MAX_ITEMS_PER_QUERY
        }
        if (combined.size > memoryPolicy.maxTotalItems) {
            combined = combined.take(memoryPolicy.maxTotalItems)
            boundary = PagedCacheBoundary.MAX_TOTAL_ITEMS
        }
        val snapshot = entry.snapshot.copy(
            items = combined,
            nextCursor = page.nextCursor,
            totalCount = page.totalCount ?: entry.snapshot.totalCount,
            pageCount = entry.snapshot.pageCount + 1,
            activity = PagedCacheActivity.IDLE,
            appendError = null,
            boundary = boundary,
        )
        val committed = entry.copy(
            snapshot = snapshot,
            seenCursors = seen,
            lastAccessSequence = nextAccessSequence(),
        )
        publish(enforceBounds(state.value + (key to committed), key))
        val finalSnapshot = checkNotNull(state.value[key]).snapshot
        val added = (
            finalSnapshot.items.map(itemId).toSet() - before.map(itemId).toSet()
        ).size
        PagedCommit(finalSnapshot, added)
    }

    /** Publishes a typed source failure if the work generation is still authoritative. */
    suspend fun completeFailure(
        key: EncodedCacheKey,
        expectedRevision: Long,
        activity: PagedCacheActivity,
        error: E,
    ): PagedCacheSnapshot<I, C, E>? = mutex.withLock {
        if (revisionOf(key) != expectedRevision) return@withLock null
        val entry = state.value[key] ?: return@withLock null
        val failed = entry.copy(
            snapshot = when (activity) {
                PagedCacheActivity.INITIAL_LOADING -> entry.snapshot.copy(
                    activity = PagedCacheActivity.IDLE,
                    initialError = error,
                )
                PagedCacheActivity.REFRESHING -> entry.snapshot.copy(
                    activity = PagedCacheActivity.IDLE,
                    refreshError = error,
                )
                PagedCacheActivity.APPENDING -> entry.snapshot.copy(
                    activity = PagedCacheActivity.IDLE,
                    appendError = error,
                )
                PagedCacheActivity.HYDRATING -> entry.snapshot.copy(
                    activity = PagedCacheActivity.IDLE,
                    initialError = error,
                )
                PagedCacheActivity.IDLE -> error("Idle paged work cannot fail.")
            },
            lastAccessSequence = nextAccessSequence(),
        )
        publish(state.value + (key to failed))
        failed.snapshot
    }

    suspend fun update(key: EncodedCacheKey, transform: (List<I>) -> List<I>): Boolean =
        mutex.withLock {
            val entry = state.value[key] ?: return@withLock false
            if (entry.snapshot.metadata == null) return@withLock false
            var items = normalize(transform(entry.snapshot.items))
            var boundary = entry.snapshot.boundary
            if (items.size > memoryPolicy.maxItemsPerQuery) {
                items = items.take(memoryPolicy.maxItemsPerQuery)
                boundary = PagedCacheBoundary.MAX_ITEMS_PER_QUERY
            }
            if (items.size > memoryPolicy.maxTotalItems) {
                items = items.take(memoryPolicy.maxTotalItems)
                boundary = PagedCacheBoundary.MAX_TOTAL_ITEMS
            }
            advanceRevision(key)
            val updated = entry.copy(
                snapshot = entry.snapshot.copy(
                    items = items,
                    activity = PagedCacheActivity.IDLE,
                    initialError = null,
                    refreshError = null,
                    appendError = null,
                    boundary = boundary,
                ),
                lastAccessSequence = nextAccessSequence(),
            )
            publish(enforceBounds(state.value + (key to updated), key))
            true
        }

    suspend fun invalidate(key: EncodedCacheKey) {
        mutex.withLock {
            advanceRevision(key)
            val entry = state.value[key] ?: return@withLock
            val metadata = entry.snapshot.metadata ?: return@withLock
            val invalidated = entry.copy(
                snapshot = entry.snapshot.copy(
                    freshness = CacheFreshness.STALE,
                    activity = PagedCacheActivity.IDLE,
                    metadata = metadata.copy(invalidatedAt = clock.now()),
                ),
                lastAccessSequence = nextAccessSequence(),
            )
            publish(state.value + (key to invalidated))
        }
    }

    suspend fun remove(key: EncodedCacheKey) {
        mutex.withLock {
            advanceRevision(key)
            publish(state.value - key)
        }
    }

    suspend fun invalidateFamily(family: EncodedCacheFamily): Int = mutex.withLock {
        val keys = knownKeys().filter { key -> key.belongsTo(family) }
        keys.forEach(::advanceRevision)
        var affected = 0
        val now = clock.now()
        val updated = state.value.mapValues { (key, entry) ->
            if (!key.belongsTo(family) || entry.snapshot.metadata == null) return@mapValues entry
            affected += 1
            entry.copy(
                snapshot = entry.snapshot.copy(
                    freshness = CacheFreshness.STALE,
                    activity = PagedCacheActivity.IDLE,
                    metadata = entry.snapshot.metadata.copy(invalidatedAt = now),
                )
            )
        }
        publish(updated)
        affected
    }

    suspend fun removeFamily(family: EncodedCacheFamily): Int = mutex.withLock {
        val keys = knownKeys().filter { key -> key.belongsTo(family) }
        keys.forEach(::advanceRevision)
        val affected = state.value.keys.count { key -> key.belongsTo(family) }
        publish(state.value.filterKeys { key -> !key.belongsTo(family) })
        affected
    }

    override suspend fun clearPartition(partition: CachePartition) {
        mutex.withLock {
            knownKeys().filter { key -> key.partition == partition }.forEach(::advanceRevision)
            publish(state.value.filterKeys { key -> key.partition != partition })
        }
    }

    override suspend fun clearAll() {
        mutex.withLock {
            knownKeys().forEach(::advanceRevision)
            publish(emptyMap())
        }
    }

    override suspend fun entryCount(): Int = mutex.withLock { state.value.size }

    private fun metadata(): CacheMetadata {
        val now = clock.now()
        return CacheMetadata(now, expirationAt(freshnessPolicy, now))
    }

    private fun normalize(items: List<I>): List<I> {
        val positions = mutableMapOf<ID, Int>()
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

    private fun merge(existing: List<I>, incoming: List<I>): List<I> =
        normalize(existing + incoming)

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

    private fun enforceBounds(
        entries: Map<EncodedCacheKey, PagedStoredEntry<C, I, E>>,
        protectedKey: EncodedCacheKey,
    ): Map<EncodedCacheKey, PagedStoredEntry<C, I, E>> {
        var bounded = entries
        while (bounded.size > memoryPolicy.maxQueries) {
            val candidate = evictionCandidate(bounded, protectedKey) ?: break
            bounded = bounded - candidate
            onEviction(candidate, bounded.size, memoryPolicy.maxQueries)
        }
        while (bounded.values.sumOf { entry -> entry.snapshot.items.size } > memoryPolicy.maxTotalItems) {
            val candidate = evictionCandidate(bounded, protectedKey) ?: break
            bounded = bounded - candidate
            onEviction(candidate, bounded.size, memoryPolicy.maxQueries)
        }
        val totalItems = bounded.values.sumOf { entry -> entry.snapshot.items.size }
        if (totalItems > memoryPolicy.maxTotalItems) {
            val protected = bounded[protectedKey]
            if (protected != null) {
                val otherItems = totalItems - protected.snapshot.items.size
                val available = (memoryPolicy.maxTotalItems - otherItems).coerceAtLeast(0)
                bounded = bounded + (
                    protectedKey to protected.copy(
                        snapshot = protected.snapshot.copy(
                            items = protected.snapshot.items.take(available),
                            boundary = PagedCacheBoundary.MAX_TOTAL_ITEMS,
                        )
                    )
                )
            }
        }
        return bounded
    }

    private fun evictionCandidate(
        entries: Map<EncodedCacheKey, PagedStoredEntry<C, I, E>>,
        protectedKey: EncodedCacheKey,
    ): EncodedCacheKey? = entries
        .filter { (key, entry) ->
            key != protectedKey &&
                observerPins.getOrElse(key) { 0 } == 0 &&
                entry.snapshot.activity == PagedCacheActivity.IDLE
        }
        .minWithOrNull(
            compareBy<Map.Entry<EncodedCacheKey, PagedStoredEntry<C, I, E>>> {
                it.value.lastAccessSequence
            }.thenBy { it.key.partition.value }.thenBy { it.key.value }
        )
        ?.key

    private fun PagedStoredEntry<C, I, E>.evaluate(): PagedStoredEntry<C, I, E> {
        val metadata = snapshot.metadata ?: return this
        val freshness = freshnessAt(metadata, clock.now())
        return if (freshness == snapshot.freshness) this
        else copy(snapshot = snapshot.copy(freshness = freshness))
    }

    private suspend fun pinObserver(key: EncodedCacheKey) {
        mutex.withLock { observerPins[key] = observerPins.getOrElse(key) { 0 } + 1 }
    }

    private suspend fun unpinObserver(key: EncodedCacheKey) {
        mutex.withLock {
            val remaining = observerPins.getOrElse(key) { 0 } - 1
            if (remaining > 0) observerPins[key] = remaining else observerPins.remove(key)
        }
    }

    private fun knownKeys(): Set<EncodedCacheKey> =
        (revisions.keys + state.value.keys + observerPins.keys).toSet()

    private fun revisionOf(key: EncodedCacheKey): Long = revisions.getOrPut(key) { 0L }

    private fun advanceRevision(key: EncodedCacheKey): Long = (revisionOf(key) + 1).also {
        revisions[key] = it
    }

    private fun EncodedCacheKey.belongsTo(family: EncodedCacheFamily): Boolean =
        partition == family.partition && this.family == family.value

    private fun nextAccessSequence(): Long = (++accessSequence)

    private fun publish(entries: Map<EncodedCacheKey, PagedStoredEntry<C, I, E>>) {
        state.value = entries
    }
}

internal data class PagedWorkStart<I : Any, C : Any, E : Any>(
    val revision: Long,
    val snapshot: PagedCacheSnapshot<I, C, E>,
    val cursor: C?,
)

internal data class PagedCommit<I : Any, C : Any, E : Any>(
    val snapshot: PagedCacheSnapshot<I, C, E>,
    val itemsAdded: Int,
)

private data class PagedStoredEntry<C : Any, I : Any, E : Any>(
    val snapshot: PagedCacheSnapshot<I, C, E>,
    val seenCursors: Set<C>,
    val lastAccessSequence: Long,
)
