package dev.voir.cache.core.file

import dev.voir.cache.core.key.CacheKeyEncoder
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.policy.FreshnessPolicy

/** Immutable configuration for one typed file resource namespace. */
internal data class FileCacheSpec<K : Any>(
    val namespace: CacheNamespace,
    val keyEncoder: CacheKeyEncoder<K>,
    val freshness: FreshnessPolicy,
    val policy: FileCachePolicy,
)

/** Creates one typed file resource specification from a stable string namespace. */
internal fun <K : Any> fileCacheSpec(
    namespace: String,
    key: CacheKeyEncoder<K>,
    freshness: FreshnessPolicy,
    policy: FileCachePolicy,
): FileCacheSpec<K> = FileCacheSpec(
    namespace = CacheNamespace(namespace),
    keyEncoder = key,
    freshness = freshness,
    policy = policy,
)
