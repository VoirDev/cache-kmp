package dev.voir.cache.core.key

import kotlin.jvm.JvmInline

/**
 * Tenant, session, or environment boundary used for isolation and bulk clearing.
 *
 * @property value Non-blank partition value supplied by the consuming domain.
 */
@JvmInline
public value class CachePartition(public val value: String) {
    init {
        require(value.isNotBlank()) { "Cache partition must not be blank." }
    }
}
