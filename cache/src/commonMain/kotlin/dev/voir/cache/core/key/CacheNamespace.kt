package dev.voir.cache.core.key

import kotlin.jvm.JvmInline

/**
 * Stable identity of a resource family and its serialized value contract.
 *
 * A namespace must change when stored values are no longer semantically compatible.
 *
 * @property value Non-blank namespace value supplied by the consuming domain.
 */
@JvmInline
internal value class CacheNamespace(val value: String) {
    init {
        require(value.isNotBlank()) { "Cache namespace must not be blank." }
    }
}
