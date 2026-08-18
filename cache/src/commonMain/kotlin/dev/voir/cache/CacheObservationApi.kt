package dev.voir.cache

import dev.voir.cache.core.model.CacheActivity as CoreCacheActivity
import dev.voir.cache.core.model.CacheFreshness as CoreCacheFreshness
import dev.voir.cache.core.model.CacheSnapshot as CoreCacheSnapshot
import dev.voir.cache.internal.FileCacheConfiguration
import dev.voir.cache.internal.FileRequest
import dev.voir.cache.internal.ValueCacheConfiguration
import dev.voir.cache.internal.ValueRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.time.Duration

/**
 * Observes one directly configured value-cache entry without invoking its source loader.
 *
 * The cold flow emits current process-local state immediately. Persistent caches then hydrate from
 * disk and emit the restored state. Later cache operations for the same manager, namespace,
 * partition, and serialized key are emitted to every active collector.
 */
public inline fun <reified K : Any, reified V : Any> CacheManager.observe(
    key: K,
    options: CacheOptions = CacheOptions(),
): Flow<CacheState<V>> = observeDirect(
    key = key,
    keySerializer = serializer<K>(),
    valueSerializer = serializer<V>(),
    options = options,
)

/** Observes one entry in a reusable value-cache [scope] without invoking its source loader. */
public fun <K : Any, V : Any> CacheManager.observe(
    scope: CacheScope<K, V>,
    key: K,
): Flow<CacheState<V>> {
    val request = scope.configuration.request(scope.partitionForKey(key), CacheOverrides())
    require(request.partition.isNotBlank()) { "Cache partition must not be blank." }
    return observeValue(
        configuration = scope.configuration,
        key = key,
        partition = request.partition,
        expiresAfter = request.expiresAfter,
        keySerializer = scope.keySerializer,
        valueSerializer = scope.valueSerializer,
    )
}

/**
 * Observes one directly configured file-cache entry without invoking its source loader.
 *
 * The emitted [CachedFileInfo] is deliberately non-leased and contains no path. Call [cacheFile]
 * when a state contains a value to acquire a [CachedFile] lease for safe file access.
 */
public inline fun <reified K : Any> CacheManager.observeFile(
    key: K,
    options: CacheFileOptions = CacheFileOptions(),
): Flow<CacheState<CachedFileInfo>> = observeFileDirect(
    key = key,
    keySerializer = serializer<K>(),
    options = options,
)

/** Observes one entry in a reusable file-cache [scope] without invoking its source loader. */
public fun <K : Any> CacheManager.observeFile(
    scope: CacheFileScope<K>,
    key: K,
): Flow<CacheState<CachedFileInfo>> {
    val request = scope.configuration.request(scope.partitionForKey(key), CacheFileOverrides())
    require(request.partition.isNotBlank()) { "File cache partition must not be blank." }
    return observeFileMetadata(
        configuration = scope.configuration,
        key = key,
        partition = request.partition,
        expiresAfter = request.expiresAfter,
        mediaType = request.mediaType,
        keySerializer = scope.keySerializer,
    )
}

@PublishedApi
internal fun <K : Any, V : Any> CacheManager.observeDirect(
    key: K,
    keySerializer: KSerializer<K>,
    valueSerializer: KSerializer<V>,
    options: CacheOptions,
): Flow<CacheState<V>> = observeValue(
    configuration = ValueCacheConfiguration.from(options, valueSerializer),
    key = key,
    partition = options.partition,
    expiresAfter = options.expiresAfter,
    keySerializer = keySerializer,
    valueSerializer = valueSerializer,
)

@PublishedApi
internal fun <K : Any> CacheManager.observeFileDirect(
    key: K,
    keySerializer: KSerializer<K>,
    options: CacheFileOptions,
): Flow<CacheState<CachedFileInfo>> = observeFileMetadata(
    configuration = FileCacheConfiguration.from(options, keySerializer),
    key = key,
    partition = options.partition,
    expiresAfter = options.expiresAfter,
    mediaType = options.mediaType,
    keySerializer = keySerializer,
)

private fun <K : Any, V : Any> CacheManager.observeValue(
    configuration: ValueCacheConfiguration,
    key: K,
    partition: String,
    expiresAfter: Duration,
    keySerializer: KSerializer<K>,
    valueSerializer: KSerializer<V>,
): Flow<CacheState<V>> = flow {
    val runtime = runtimes.value(configuration, keySerializer, valueSerializer)
    val request = ValueRequest<K, V>(
        key = key,
        partition = partition,
        expiresAfter = expiresAfter,
        refresh = CacheRefresh.IF_NEEDED,
        useStaleOnError = true,
        load = { error("Cache observation must not invoke a source loader.") },
    )
    emitAll(runtime.observe(request).map { snapshot -> snapshot.toCacheState() })
}

private fun <K : Any> CacheManager.observeFileMetadata(
    configuration: FileCacheConfiguration,
    key: K,
    partition: String,
    expiresAfter: Duration,
    mediaType: String?,
    keySerializer: KSerializer<K>,
): Flow<CacheState<CachedFileInfo>> = flow {
    val runtime = runtimes.file(configuration, keySerializer)
    val request = FileRequest(
        key = key,
        partition = partition,
        expiresAfter = expiresAfter,
        refresh = CacheRefresh.IF_NEEDED,
        useStaleOnError = true,
        mediaType = mediaType,
        load = { error("File-cache observation must not invoke a source loader.") },
    )
    emitAll(
        runtime.observe(request).map { snapshot ->
            snapshot.toCacheState { file ->
                CachedFileInfo(sizeBytes = file.sizeBytes, mediaType = file.content.mediaType)
            }
        }
    )
}

private fun <V : Any> CoreCacheSnapshot<V, Throwable>.toCacheState(): CacheState<V> =
    toCacheState { value -> value }

private fun <V : Any, R : Any> CoreCacheSnapshot<V, Throwable>.toCacheState(
    transform: (V) -> R,
): CacheState<R> = CacheState(
    value = value?.let(transform),
    freshness = when (freshness) {
        CoreCacheFreshness.ABSENT -> CacheFreshness.ABSENT
        CoreCacheFreshness.FRESH -> CacheFreshness.FRESH
        CoreCacheFreshness.STALE -> CacheFreshness.STALE
    },
    activity = when (activity) {
        CoreCacheActivity.IDLE -> CacheActivity.IDLE
        CoreCacheActivity.HYDRATING -> CacheActivity.HYDRATING
        CoreCacheActivity.REFRESHING -> CacheActivity.REFRESHING
    },
    metadata = metadata?.let { current ->
        CacheEntryMetadata(validatedAt = current.validatedAt, expiresAt = current.expiresAt)
    },
    refreshError = refreshError,
)
