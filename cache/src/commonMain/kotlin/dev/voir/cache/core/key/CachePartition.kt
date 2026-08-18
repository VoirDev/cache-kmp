package dev.voir.cache.core.key

import kotlin.jvm.JvmInline

/**
 * Tenant, session, or environment boundary used for isolation.
 *
 * @property value Non-blank partition value supplied by the consuming domain.
 */
@JvmInline
internal value class CachePartition(val value: String) {
    init {
        require(value.isNotBlank()) { "Cache partition must not be blank." }
    }
}
