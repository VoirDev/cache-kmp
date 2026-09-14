package dev.voir.cache

import dev.voir.cache.internal.FileCacheConfiguration
import dev.voir.cache.internal.FileRequest
import dev.voir.cache.internal.ValueCacheConfiguration
import dev.voir.cache.internal.ValueRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/** Direct read-through caching without an explicit scope declaration. */
public suspend inline fun <reified K : Any, reified V : Any> CacheManager.cache(
    key: K,
    options: CacheOptions = CacheOptions(),
    noinline load: suspend () -> V,
): V = cacheDirect(
    key = key,
    keySerializer = serializer<K>(),
    valueSerializer = serializer<V>(),
    options = options,
    load = load,
)

/** Read-through caching using a reusable typed scope and optional per-call overrides. */
public suspend fun <K : Any, V : Any> CacheManager.cache(
    scope: CacheScope<K, V>,
    key: K,
    overrides: CacheOverrides = CacheOverrides(),
    load: suspend () -> V,
): V {
    val request = scope.configuration.request(scope.partitionForKey(key), overrides)
    require(request.partition.isNotBlank()) { "Cache partition must not be blank." }
    val runtime = runtimes.value(
        configuration = scope.configuration,
        keySerializer = scope.keySerializer,
        valueSerializer = scope.valueSerializer,
    )
    return runtime.cache(
        ValueRequest(
            key = key,
            partition = request.partition,
            expiresAfter = request.expiresAfter,
            refresh = request.refresh,
            useStaleOnError = request.useStaleOnError,
            load = load,
        )
    ).also { engine.scheduleMaintenance() }
}

/** Direct streamed-file caching without an explicit scope declaration. */
public suspend inline fun <reified K : Any> CacheManager.cacheFile(
    key: K,
    options: CacheFileOptions = CacheFileOptions(),
    noinline load: suspend (CacheFileSink) -> Unit,
): CachedFile = cacheFileDirect(
    key = key,
    keySerializer = serializer<K>(),
    options = options,
    load = load,
)

/** Streamed-file caching using a reusable typed scope and optional per-call overrides. */
public suspend fun <K : Any> CacheManager.cacheFile(
    scope: CacheFileScope<K>,
    key: K,
    overrides: CacheFileOverrides = CacheFileOverrides(),
    load: suspend (CacheFileSink) -> Unit,
): CachedFile {
    val request = scope.configuration.request(scope.partitionForKey(key), overrides)
    require(request.partition.isNotBlank()) { "File cache partition must not be blank." }
    val runtime = runtimes.file(scope.configuration, scope.keySerializer)
    return runtime.cache(
        FileRequest(
            key = key,
            partition = request.partition,
            expiresAfter = request.expiresAfter,
            refresh = request.refresh,
            useStaleOnError = request.useStaleOnError,
            mediaType = request.mediaType,
            load = load,
        )
    ).also { engine.scheduleMaintenance() }
}

@PublishedApi
internal suspend fun <K : Any, V : Any> CacheManager.cacheDirect(
    key: K,
    keySerializer: KSerializer<K>,
    valueSerializer: KSerializer<V>,
    options: CacheOptions,
    load: suspend () -> V,
): V {
    val configuration = ValueCacheConfiguration.from(options, valueSerializer)
    val runtime = runtimes.value(configuration, keySerializer, valueSerializer)
    return runtime.cache(
        ValueRequest(
            key = key,
            partition = options.partition,
            expiresAfter = options.expiresAfter,
            refresh = options.refresh,
            useStaleOnError = options.useStaleOnError,
            load = load,
        )
    ).also { engine.scheduleMaintenance() }
}

@PublishedApi
internal suspend fun <K : Any> CacheManager.cacheFileDirect(
    key: K,
    keySerializer: KSerializer<K>,
    options: CacheFileOptions,
    load: suspend (CacheFileSink) -> Unit,
): CachedFile {
    val configuration = FileCacheConfiguration.from(options, keySerializer)
    val runtime = runtimes.file(configuration, keySerializer)
    return runtime.cache(
        FileRequest(
            key = key,
            partition = options.partition,
            expiresAfter = options.expiresAfter,
            refresh = options.refresh,
            useStaleOnError = options.useStaleOnError,
            mediaType = options.mediaType,
            load = load,
        )
    ).also { engine.scheduleMaintenance() }
}
