package dev.voir.cache

import dev.voir.cache.internal.FileCacheConfiguration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.time.Duration

/** Reusable typed file-cache identity and default policy. */
public class CacheFileScope<K : Any> internal constructor(
    public val name: String,
    internal val keySerializer: KSerializer<K>,
    internal val configuration: FileCacheConfiguration,
    internal val partitionForKey: (K) -> String,
)

/** DSL used by [cacheFileScope] to declare reusable file-cache policy. */
public class CacheFileScopeBuilder<K : Any> internal constructor(
    private val name: String,
) {
    init {
        require(name.isNotBlank()) { "File cache scope name must not be blank." }
    }

    public var expiresAfter: Duration = Duration.INFINITE
    public var maxEntryBytes: Long = 10L * 1_024 * 1_024
    public var maxEntries: Int = 100
    public var maxBytes: Long = 100L * 1_024 * 1_024
    public var partition: String = DEFAULT_CACHE_PARTITION
    public var refresh: CacheRefresh = CacheRefresh.IF_NEEDED
    public var useStaleOnError: Boolean = true
    public var mediaType: String? = null

    private var partitionSelector: ((K) -> String)? = null

    /** Derives the isolation partition for each key. */
    public fun partitionBy(selector: (K) -> String) {
        partitionSelector = selector
    }

    internal fun build(keySerializer: KSerializer<K>): CacheFileScope<K> {
        val options = CacheFileOptions(
            namespace = name,
            expiresAfter = expiresAfter,
            maxEntryBytes = maxEntryBytes,
            maxEntries = maxEntries,
            maxBytes = maxBytes,
            partition = partition,
            refresh = refresh,
            useStaleOnError = useStaleOnError,
            mediaType = mediaType,
        )
        val fixedPartition = options.partition
        return CacheFileScope(
            name = name,
            keySerializer = keySerializer,
            configuration = FileCacheConfiguration.named(options, name),
            partitionForKey = partitionSelector ?: { fixedPartition },
        )
    }
}

/** Creates a reusable file-cache scope and captures its Kotlinx key serializer. */
public inline fun <reified K : Any> cacheFileScope(
    name: String,
    noinline configure: CacheFileScopeBuilder<K>.() -> Unit = {},
): CacheFileScope<K> = createCacheFileScope(name, configure, serializer<K>())

@PublishedApi
internal fun <K : Any> createCacheFileScope(
    name: String,
    configure: CacheFileScopeBuilder<K>.() -> Unit,
    keySerializer: KSerializer<K>,
): CacheFileScope<K> = CacheFileScopeBuilder<K>(name).apply(configure).build(keySerializer)
