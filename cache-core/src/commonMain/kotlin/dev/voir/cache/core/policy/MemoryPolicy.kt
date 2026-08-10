package dev.voir.cache.core.policy

/**
 * Bounds the number of entries retained in memory for one resource family.
 *
 * @property maxEntries Maximum number of unpinned and pinned entries retained by the resource.
 */
public data class MemoryPolicy public constructor(
    public val maxEntries: Int,
) {
    init {
        require(maxEntries > 0) { "Cache memory maxEntries must be greater than zero." }
    }
}

