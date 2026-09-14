package dev.voir.cache.core.memory

/**
 * Captures the address revision against which one persistent read may restore memory.
 *
 * @property revision Revision captured before persistent I/O starts.
 */
internal data class HydrationStart(
    val revision: Long,
)
