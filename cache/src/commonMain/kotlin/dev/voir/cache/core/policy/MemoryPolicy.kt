package dev.voir.cache.core.policy

/**
 * Bounds the number of entries retained in memory for one resource.
 *
 * @property maxEntries Maximum number of unpinned and pinned entries retained by the resource.
 */
internal data class MemoryPolicy(
    val maxEntries: Int,
) {
    init {
        require(maxEntries > 0) { "Cache memory maxEntries must be greater than zero." }
    }
}
