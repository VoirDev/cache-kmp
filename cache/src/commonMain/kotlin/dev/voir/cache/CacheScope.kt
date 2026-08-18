package dev.voir.cache

import dev.voir.cache.internal.ValueCacheConfiguration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.time.Duration

/** Reusable typed value-cache identity and default policy. */
public class CacheScope<K : Any, V : Any> internal constructor(
    public val name: String,
    internal val keySerializer: KSerializer<K>,
    internal val valueSerializer: KSerializer<V>,
    internal val configuration: ValueCacheConfiguration,
    internal val partitionForKey: (K) -> String,
)

/** DSL used by [cacheScope] to declare reusable value-cache policy. */
public class CacheScopeBuilder<K : Any> internal constructor(
    private val name: String,
) {
    init {
        require(name.isNotBlank()) { "Cache scope name must not be blank." }
    }

    public var storage: CacheStorage = CacheStorage.MEMORY
    public var expiresAfter: Duration = Duration.INFINITE
    public var maxMemoryEntries: Int = 100
    public var maxPayloadBytes: Int = 1_048_576
    public var partition: String = DEFAULT_CACHE_PARTITION
    public var refresh: CacheRefresh = CacheRefresh.IF_NEEDED
    public var useStaleOnError: Boolean = true

    private var partitionSelector: ((K) -> String)? = null

    /** Derives the isolation partition for each key. */
    public fun partitionBy(selector: (K) -> String) {
        partitionSelector = selector
    }

    internal fun <V : Any> build(
        keySerializer: KSerializer<K>,
        valueSerializer: KSerializer<V>,
    ): CacheScope<K, V> {
        val options = CacheOptions(
            namespace = name,
            storage = storage,
            expiresAfter = expiresAfter,
            maxMemoryEntries = maxMemoryEntries,
            maxPayloadBytes = maxPayloadBytes,
            partition = partition,
            refresh = refresh,
            useStaleOnError = useStaleOnError,
        )
        val fixedPartition = options.partition
        return CacheScope(
            name = name,
            keySerializer = keySerializer,
            valueSerializer = valueSerializer,
            configuration = ValueCacheConfiguration.named(options, name),
            partitionForKey = partitionSelector ?: { fixedPartition },
        )
    }
}

/** Creates a reusable cache scope and captures Kotlinx serializers for its key and value types. */
public inline fun <reified K : Any, reified V : Any> cacheScope(
    name: String,
    noinline configure: CacheScopeBuilder<K>.() -> Unit = {},
): CacheScope<K, V> = createCacheScope(
    name = name,
    configure = configure,
    keySerializer = serializer<K>(),
    valueSerializer = serializer<V>(),
)

@PublishedApi
internal fun <K : Any, V : Any> createCacheScope(
    name: String,
    configure: CacheScopeBuilder<K>.() -> Unit,
    keySerializer: KSerializer<K>,
    valueSerializer: KSerializer<V>,
): CacheScope<K, V> = CacheScopeBuilder<K>(name).apply(configure).build(
    keySerializer = keySerializer,
    valueSerializer = valueSerializer,
)
