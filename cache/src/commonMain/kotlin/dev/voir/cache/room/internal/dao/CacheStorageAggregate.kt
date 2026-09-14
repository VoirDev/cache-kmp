package dev.voir.cache.room.internal.dao

/** Aggregate row and encoded-payload occupancy returned directly by SQLite. */
internal data class CacheStorageAggregate(
    val rowCount: Long,
    val payloadBytes: Long,
)
