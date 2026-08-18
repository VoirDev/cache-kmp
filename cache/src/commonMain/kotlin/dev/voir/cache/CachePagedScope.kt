package dev.voir.cache

import dev.voir.cache.internal.PagedCacheConfiguration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.time.Duration

/**
 * Reusable accumulated-page cache identity and policy.
 *
 * @param Q Domain-owned query type.
 * @param I Non-null accumulated item type.
 * @param C Cursor type understood by the source.
 */
public class CachePagedScope<Q : Any, I : Any, C : Any> internal constructor(
    public val name: String,
    internal val querySerializer: KSerializer<Q>,
    internal val configuration: PagedCacheConfiguration,
    internal val itemId: (I) -> Any,
    internal val partitionForQuery: (Q) -> String,
)

/** DSL used by [cachePagedScope] to declare reusable accumulated-page policy. */
public class CachePagedScopeBuilder<Q : Any, I : Any> internal constructor(
    private val name: String,
) {
    init {
        require(name.isNotBlank()) { "Paged cache scope name must not be blank." }
    }

    /** How long accumulated pages may be reused before the first page is reloaded. */
    public var expiresAfter: Duration = Duration.INFINITE

    /** Maximum pages accumulated for one query. */
    public var maxPages: Int = 20

    /** Maximum items accumulated for one query. */
    public var maxItemsPerQuery: Int = 1_000

    /** Maximum queries retained for this namespace. */
    public var maxQueries: Int = 20

    /** Maximum items retained across every query in this namespace. */
    public var maxTotalItems: Int = 5_000

    /** Behavior when an incoming item repeats an accumulated identity. */
    public var duplicates: CacheDuplicates = CacheDuplicates.REPLACE_EXISTING

    /**
     * Whether a failed load returns the accumulated pages instead of throwing.
     *
     * A failure with nothing accumulated always throws, because there is no list to fall back to.
     */
    public var useStaleOnError: Boolean = true

    private var identity: ((I) -> Any)? = null
    private var partitionSelector: ((Q) -> String)? = null
    private var fixedPartition: String = DEFAULT_CACHE_PARTITION

    /**
     * Declares the stable identity used to deduplicate accumulated items.
     *
     * Pages overlap whenever the source inserts or removes an item between requests, so an identity
     * is required rather than optional.
     */
    public fun identifyBy(selector: (I) -> Any) {
        identity = selector
    }

    /** Derives the isolation partition for each query. */
    public fun partitionBy(selector: (Q) -> String) {
        partitionSelector = selector
    }

    /** Sets one fixed isolation partition for every query in this scope. */
    public fun partition(value: String) {
        require(value.isNotBlank()) { "Paged cache partition must not be blank." }
        fixedPartition = value
    }

    internal fun <C : Any> build(
        querySerializer: KSerializer<Q>,
    ): CachePagedScope<Q, I, C> {
        val itemId = requireNotNull(identity) {
            "A paged cache scope must declare identifyBy to deduplicate accumulated items."
        }
        val partition = fixedPartition
        return CachePagedScope(
            name = name,
            querySerializer = querySerializer,
            configuration = PagedCacheConfiguration(
                namespace = name,
                expiresAfter = expiresAfter,
                maxPages = maxPages,
                maxItemsPerQuery = maxItemsPerQuery,
                maxQueries = maxQueries,
                maxTotalItems = maxTotalItems,
                duplicates = duplicates,
                useStaleOnError = useStaleOnError,
            ),
            itemId = itemId,
            partitionForQuery = partitionSelector ?: { partition },
        )
    }
}

/**
 * Creates a reusable accumulated-page scope and captures the Kotlinx serializer for its query type.
 *
 * @param Q Domain-owned query type.
 * @param I Non-null accumulated item type.
 * @param C Cursor type understood by the source.
 */
public inline fun <reified Q : Any, I : Any, C : Any> cachePagedScope(
    name: String,
    noinline configure: CachePagedScopeBuilder<Q, I>.() -> Unit,
): CachePagedScope<Q, I, C> = createCachePagedScope(name, configure, serializer<Q>())

@PublishedApi
internal fun <Q : Any, I : Any, C : Any> createCachePagedScope(
    name: String,
    configure: CachePagedScopeBuilder<Q, I>.() -> Unit,
    querySerializer: KSerializer<Q>,
): CachePagedScope<Q, I, C> =
    CachePagedScopeBuilder<Q, I>(name).apply(configure).build(querySerializer)
