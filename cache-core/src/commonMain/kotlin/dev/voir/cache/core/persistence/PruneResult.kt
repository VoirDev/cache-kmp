package dev.voir.cache.core.persistence

/**
 * Summary returned by persistent maintenance.
 *
 * @property rowsRemoved Number of records deleted.
 * @property bytesRemoved Number of payload bytes deleted when the backend can report it.
 */
@ExperimentalCacheStoreApi
public data class PruneResult public constructor(
    public val rowsRemoved: Long,
    public val bytesRemoved: Long? = null,
) {
    init {
        require(rowsRemoved >= 0) { "Pruned row count must not be negative." }
        require(bytesRemoved == null || bytesRemoved >= 0) {
            "Pruned byte count must be null or non-negative."
        }
    }
}
