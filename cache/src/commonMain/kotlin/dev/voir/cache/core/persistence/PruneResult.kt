package dev.voir.cache.core.persistence

/**
 * Summary returned by persistent maintenance.
 *
 * @property rowsRemoved Number of records deleted.
 * @property bytesRemoved Number of payload bytes deleted.
 */
internal data class PruneResult(
    val rowsRemoved: Long,
    val bytesRemoved: Long,
) {
    init {
        require(rowsRemoved >= 0) { "Pruned row count must not be negative." }
        require(bytesRemoved >= 0) { "Pruned byte count must not be negative." }
    }
}
