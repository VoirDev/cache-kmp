package dev.voir.cache.internal

import dev.voir.cache.CacheDuplicates
import dev.voir.cache.CacheFileException
import dev.voir.cache.CacheFileFailureReason
import dev.voir.cache.CacheFileSink
import dev.voir.cache.CacheFileSinkAdapter
import dev.voir.cache.CachePage
import dev.voir.cache.CachePagedScope
import dev.voir.cache.CachePages
import dev.voir.cache.CacheRefresh
import dev.voir.cache.CacheStorage
import dev.voir.cache.CachedFile
import dev.voir.cache.codec.kotlinx.KotlinxJsonCacheCodec
import dev.voir.cache.core.file.CachedFile as CoreCachedFile
import dev.voir.cache.core.file.FileCacheDiscarded
import dev.voir.cache.core.file.FileCacheFailure
import dev.voir.cache.core.file.FileCachePolicy
import dev.voir.cache.core.file.FileCacheRejected
import dev.voir.cache.core.file.FileCacheRejectionReason
import dev.voir.cache.core.file.FileCacheResult
import dev.voir.cache.core.file.FileCacheSuccess
import dev.voir.cache.core.file.FileCacheUnavailable
import dev.voir.cache.core.file.FileContentMetadata
import dev.voir.cache.core.file.FileFetcher
import dev.voir.cache.core.file.fileCacheSpec
import dev.voir.cache.core.internal.DefaultCacheManager
import dev.voir.cache.core.internal.DefaultCachedResource
import dev.voir.cache.core.internal.DefaultFileCachedResource
import dev.voir.cache.core.internal.DefaultPagedCachedResource
import dev.voir.cache.core.config.cacheSpec
import dev.voir.cache.core.config.pagedCacheSpec
import dev.voir.cache.core.key.CacheKeyEncoder
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.model.CacheDiscarded
import dev.voir.cache.core.model.CacheFailure
import dev.voir.cache.core.model.CacheFreshness
import dev.voir.cache.core.model.CacheSnapshot
import dev.voir.cache.core.model.CacheSuccess
import dev.voir.cache.core.model.FetchResult
import dev.voir.cache.core.model.FetchSuccess
import dev.voir.cache.core.paging.FetchedPage
import dev.voir.cache.core.paging.PagedCacheSnapshot
import dev.voir.cache.core.paging.PagedDuplicatePolicy
import dev.voir.cache.core.policy.ExpireAfter
import dev.voir.cache.core.policy.FreshnessPolicy
import dev.voir.cache.core.policy.MemoryPolicy
import dev.voir.cache.core.policy.NeverExpire
import dev.voir.cache.core.policy.PagedMemoryPolicy
import dev.voir.cache.core.storage.memoryOnly
import dev.voir.cache.core.storage.persistent
import dev.voir.cache.core.time.CacheClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Duration

/**
 * Creates and reuses one engine resource per public namespace.
 *
 * A namespace may be declared by many call sites, so its identity and stable limits are
 * fingerprinted. Conflicting reuse fails immediately instead of silently sharing storage with an
 * incompatible type or bound.
 */
internal class CacheRuntimeRegistry(
    private val engine: DefaultCacheManager,
) {
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, RuntimeEntry>()
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    suspend fun <K : Any, V : Any> value(
        configuration: ValueCacheConfiguration,
        keySerializer: KSerializer<K>,
        valueSerializer: KSerializer<V>,
    ): ValueCacheRuntime<K, V> = mutex.withLock {
        val fingerprint = RuntimeFingerprint.value(
            configuration = configuration,
            keyType = keySerializer.descriptor.serialName,
            valueType = valueSerializer.descriptor.serialName,
        )
        existing(configuration.namespace, fingerprint)?.let { entry ->
            @Suppress("UNCHECKED_CAST")
            return@withLock entry.runtime as ValueCacheRuntime<K, V>
        }
        val runtime = createValueRuntime(configuration, keySerializer, valueSerializer)
        entries[configuration.namespace] = RuntimeEntry(fingerprint, runtime)
        runtime
    }

    suspend fun <K : Any> file(
        configuration: FileCacheConfiguration,
        keySerializer: KSerializer<K>,
    ): FileCacheRuntime<K> = mutex.withLock {
        val fingerprint = RuntimeFingerprint.file(
            configuration = configuration,
            keyType = keySerializer.descriptor.serialName,
        )
        existing(configuration.namespace, fingerprint)?.let { entry ->
            @Suppress("UNCHECKED_CAST")
            return@withLock entry.runtime as FileCacheRuntime<K>
        }
        val runtime = createFileRuntime(configuration, keySerializer)
        entries[configuration.namespace] = RuntimeEntry(fingerprint, runtime)
        runtime
    }

    suspend fun <Q : Any, I : Any, C : Any> paged(
        scope: CachePagedScope<Q, I, C>,
    ): PagedCacheRuntime<Q, I, C> = mutex.withLock {
        val configuration = scope.configuration
        val fingerprint = RuntimeFingerprint.paged(
            configuration = configuration,
            queryType = scope.querySerializer.descriptor.serialName,
        )
        existing(configuration.namespace, fingerprint)?.let { entry ->
            @Suppress("UNCHECKED_CAST")
            return@withLock entry.runtime as PagedCacheRuntime<Q, I, C>
        }
        val runtime = createPagedRuntime(scope)
        entries[configuration.namespace] = RuntimeEntry(fingerprint, runtime)
        runtime
    }

    private fun existing(namespace: String, fingerprint: RuntimeFingerprint): RuntimeEntry? {
        val existing = entries[namespace] ?: return null
        require(existing.fingerprint == fingerprint) {
            "Cache namespace '$namespace' is already used with different types or stable limits."
        }
        return existing
    }

    private fun <K : Any, V : Any> createValueRuntime(
        configuration: ValueCacheConfiguration,
        keySerializer: KSerializer<K>,
        valueSerializer: KSerializer<V>,
    ): ValueCacheRuntime<K, V> {
        val storage = when (configuration.storage) {
            CacheStorage.MEMORY -> memoryOnly()
            CacheStorage.PERSISTENT -> persistent(
                codec = KotlinxJsonCacheCodec(serializer = valueSerializer, json = json),
                maxPayloadBytes = configuration.maxPayloadBytes,
            )
        }
        val resource = engine.valueResource(
            spec = cacheSpec<ValueRequest<K, V>, V>(
                namespace = configuration.namespace,
                key = CacheKeyEncoder { request ->
                    EncodedCacheKey(
                        partition = CachePartition(request.partition),
                        value = json.encodeToString(keySerializer, request.key),
                    )
                },
                freshness = NeverExpire,
                storage = storage,
                memory = MemoryPolicy(configuration.maxMemoryEntries),
            ),
            fetch = { request -> FetchSuccess(request.load()) },
            mapUnexpectedFailure = { failure -> failure },
            freshnessForKey = { request -> request.expiresAfter.toFreshnessPolicy() },
        )
        return ValueCacheRuntime(engine.clock, resource)
    }

    private fun <K : Any> createFileRuntime(
        configuration: FileCacheConfiguration,
        keySerializer: KSerializer<K>,
    ): FileCacheRuntime<K> {
        val resource = engine.fileResource(
            spec = fileCacheSpec<FileRequest<K>>(
                namespace = configuration.namespace,
                key = CacheKeyEncoder { request ->
                    EncodedCacheKey(
                        partition = CachePartition(request.partition),
                        value = json.encodeToString(keySerializer, request.key),
                    )
                },
                freshness = NeverExpire,
                policy = FileCachePolicy(
                    maxEntryBytes = configuration.maxEntryBytes,
                    maxEntries = configuration.maxEntries,
                    maxBytes = configuration.maxBytes,
                ),
            ),
            fetch = FileFetcher { request, sink ->
                request.load(CacheFileSinkAdapter(sink))
                FetchSuccess(FileContentMetadata(request.mediaType))
            },
            mapUnexpectedFailure = { failure -> failure },
            freshnessForKey = { request -> request.expiresAfter.toFreshnessPolicy() },
        )
        return FileCacheRuntime(engine.clock, resource)
    }

    private fun <Q : Any, I : Any, C : Any> createPagedRuntime(
        scope: CachePagedScope<Q, I, C>,
    ): PagedCacheRuntime<Q, I, C> {
        val configuration = scope.configuration
        val resource = engine.pagedResource<PagedQuery<Q>, I, C, Throwable>(
            spec = pagedCacheSpec(
                namespace = configuration.namespace,
                query = CacheKeyEncoder { addressed ->
                    EncodedCacheKey(
                        partition = CachePartition(addressed.partition),
                        value = json.encodeToString(scope.querySerializer, addressed.query),
                    )
                },
                itemId = scope.itemId,
                duplicatePolicy = when (configuration.duplicates) {
                    CacheDuplicates.KEEP_EXISTING -> PagedDuplicatePolicy.KEEP_EXISTING
                    CacheDuplicates.REPLACE_EXISTING -> PagedDuplicatePolicy.REPLACE_EXISTING
                },
                freshness = configuration.expiresAfter.toFreshnessPolicy(),
                memory = PagedMemoryPolicy(
                    maxQueries = configuration.maxQueries,
                    maxPages = configuration.maxPages,
                    maxItemsPerQuery = configuration.maxItemsPerQuery,
                    maxTotalItems = configuration.maxTotalItems,
                ),
            ),
            mapUnexpectedFailure = { failure -> failure },
        )
        return PagedCacheRuntime(configuration.useStaleOnError, resource)
    }
}

internal class ValueCacheRuntime<K : Any, V : Any>(
    private val clock: CacheClock,
    private val resource: DefaultCachedResource<ValueRequest<K, V>, V, Throwable>,
) {
    fun observe(request: ValueRequest<K, V>): Flow<CacheSnapshot<V, Throwable>> =
        resource.observe(request)

    suspend fun cache(request: ValueRequest<K, V>): V {
        if (request.refresh != CacheRefresh.ALWAYS) {
            val snapshot = resource.current(request)
            if (snapshot.isFreshFor(clock, request.expiresAfter)) return checkNotNull(snapshot.value)
        }
        return when (val result = resource.refresh(request)) {
            is CacheSuccess -> result.value
            is CacheFailure -> result.staleValue?.takeIf { request.useStaleOnError }
                ?: throw result.error

            is CacheDiscarded -> result.currentSnapshot.value
                ?: result.sourceError?.let { error -> throw error }
                ?: error("Cache request was superseded before a value was committed.")
        }
    }
}

internal class FileCacheRuntime<K : Any>(
    private val clock: CacheClock,
    private val resource: DefaultFileCachedResource<FileRequest<K>, Throwable>,
) {
    fun observe(request: FileRequest<K>): Flow<CacheSnapshot<CoreCachedFile, Throwable>> =
        resource.observe(request)

    suspend fun cache(request: FileRequest<K>): CachedFile {
        if (request.refresh != CacheRefresh.ALWAYS) {
            val snapshot = resource.current(request)
            if (snapshot.isFreshFor(clock, request.expiresAfter)) {
                resource.acquire(request)?.let { lease -> return CachedFile(lease) }
            }
        }
        return resolve(request, resource.refresh(request))
    }

    private suspend fun resolve(
        request: FileRequest<K>,
        result: FileCacheResult<Throwable>,
    ): CachedFile = when (result) {
        is FileCacheSuccess -> acquireOrThrow(request, CacheFileFailureReason.STORAGE_UNAVAILABLE)
        is FileCacheFailure -> staleOr(request, result.staleFile != null) { throw result.error }
        is FileCacheRejected -> staleOr(request, result.staleFile != null) {
            throw rejection(result.reason)
        }

        is FileCacheUnavailable -> staleOr(request, result.staleFile != null) {
            throw CacheFileException(CacheFileFailureReason.STORAGE_UNAVAILABLE)
        }

        is FileCacheDiscarded -> if (result.currentSnapshot.value != null) {
            acquireOrThrow(request, CacheFileFailureReason.SUPERSEDED)
        } else {
            result.sourceError?.let { error -> throw error }
            throw CacheFileException(CacheFileFailureReason.SUPERSEDED)
        }
    }

    private suspend inline fun staleOr(
        request: FileRequest<K>,
        hasStale: Boolean,
        failure: () -> Nothing,
    ): CachedFile {
        if (hasStale && request.useStaleOnError) {
            resource.acquire(request)?.let { lease -> return CachedFile(lease) }
        }
        failure()
    }

    private suspend fun acquireOrThrow(
        request: FileRequest<K>,
        reason: CacheFileFailureReason,
    ): CachedFile = resource.acquire(request)?.let(::CachedFile)
        ?: throw CacheFileException(reason)

    private fun rejection(reason: FileCacheRejectionReason): CacheFileException =
        CacheFileException(
            when (reason) {
                FileCacheRejectionReason.ENTRY_TOO_LARGE ->
                    CacheFileFailureReason.ENTRY_TOO_LARGE

                FileCacheRejectionReason.CAPACITY_UNAVAILABLE ->
                    CacheFileFailureReason.CAPACITY_UNAVAILABLE
            }
        )
}

internal class ValueRequest<K : Any, V : Any>(
    val key: K,
    val partition: String,
    val expiresAfter: Duration,
    val refresh: CacheRefresh,
    val useStaleOnError: Boolean,
    val load: suspend () -> V,
)

internal class FileRequest<K : Any>(
    val key: K,
    val partition: String,
    val expiresAfter: Duration,
    val refresh: CacheRefresh,
    val useStaleOnError: Boolean,
    val mediaType: String?,
    val load: suspend (CacheFileSink) -> Unit,
)

private class RuntimeEntry(
    val fingerprint: RuntimeFingerprint,
    val runtime: Any,
)

/**
 * Stable identity and capacity of one namespace.
 *
 * Per-request policy such as expiration, refresh mode, and stale fallback is deliberately excluded
 * because every call may override it.
 */
private data class RuntimeFingerprint(
    val kind: String,
    val keyType: String,
    val valueType: String?,
    val storage: CacheStorage?,
    val maxEntries: Int,
    val maxEntryBytes: Long,
    val maxBytes: Long?,
) {
    companion object {
        fun value(
            configuration: ValueCacheConfiguration,
            keyType: String,
            valueType: String,
        ): RuntimeFingerprint = RuntimeFingerprint(
            kind = "value",
            keyType = keyType,
            valueType = valueType,
            storage = configuration.storage,
            maxEntries = configuration.maxMemoryEntries,
            maxEntryBytes = configuration.maxPayloadBytes.toLong(),
            maxBytes = null,
        )

        fun paged(
            configuration: PagedCacheConfiguration,
            queryType: String,
        ): RuntimeFingerprint = RuntimeFingerprint(
            kind = "paged",
            keyType = queryType,
            valueType = null,
            storage = null,
            maxEntries = configuration.maxQueries,
            maxEntryBytes = configuration.maxItemsPerQuery.toLong(),
            maxBytes = configuration.maxTotalItems.toLong(),
        )

        fun file(
            configuration: FileCacheConfiguration,
            keyType: String,
        ): RuntimeFingerprint = RuntimeFingerprint(
            kind = "file",
            keyType = keyType,
            valueType = null,
            storage = null,
            maxEntries = configuration.maxEntries,
            maxEntryBytes = configuration.maxEntryBytes,
            maxBytes = configuration.maxBytes,
        )
    }
}

private fun Duration.toFreshnessPolicy(): FreshnessPolicy =
    if (this == Duration.INFINITE) NeverExpire else ExpireAfter(this)

/**
 * Re-evaluates a committed entry against the expiration this request asks for.
 *
 * The entry stores the expiration that was effective when it was committed. A later request may
 * ask for a shorter one, and must then reload rather than reuse an entry the engine still
 * considers fresh.
 */
private fun CacheSnapshot<*, *>.isFreshFor(clock: CacheClock, expiresAfter: Duration): Boolean {
    if (value == null || freshness != CacheFreshness.FRESH) return false
    val metadata = metadata ?: return false
    if (expiresAfter == Duration.INFINITE) return true
    return clock.now() < metadata.validatedAt + expiresAfter
}

/**
 * Accumulated-page runtime bound to one namespace.
 *
 * @param Q Domain-owned query type.
 * @param I Non-null accumulated item type.
 * @param C Cursor type understood by the source.
 */
internal class PagedCacheRuntime<Q : Any, I : Any, C : Any>(
    private val useStaleOnError: Boolean,
    private val resource: DefaultPagedCachedResource<PagedQuery<Q>, I, C, Throwable>,
) {
    fun observe(query: Q, partition: String): Flow<PagedCacheSnapshot<I, C, Throwable>> =
        resource.observe(PagedQuery(query, partition))

    suspend fun cache(
        query: Q,
        partition: String,
        request: CachePages,
        load: suspend (C?) -> CachePage<I, C>,
    ): PagedCacheSnapshot<I, C, Throwable> {
        val addressed = PagedQuery(query, partition)
        val fetch: suspend (C?) -> FetchResult<FetchedPage<I, C>, Throwable> = { cursor ->
            val page = load(cursor)
            FetchSuccess(FetchedPage(page.items, page.nextCursor, page.totalCount))
        }
        val outcome = when (request) {
            CachePages.IF_NEEDED -> resource.ensureLoaded(addressed, fetch)
            CachePages.REFRESH -> resource.refresh(addressed, fetch)
            CachePages.NEXT -> resource.loadNext(addressed, fetch)
        }
        val failure = outcome.error
        if (failure != null && (!useStaleOnError || outcome.snapshot.items.isEmpty())) {
            throw failure
        }
        return outcome.snapshot
    }
}

/** A domain query addressed inside one isolation partition. */
internal data class PagedQuery<Q : Any>(
    val query: Q,
    val partition: String,
)
