package dev.voir.cache.file.internal.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import dev.voir.cache.file.internal.entity.FileEntryEntity

/**
 * Serialized metadata operations used by the filesystem store.
 *
 * Occupancy, eviction candidates, and expiration are answered by SQL so a commit never loads the
 * whole index into application memory.
 */
@Dao
internal abstract class FileEntryDao {
    @Query(
        """
        SELECT * FROM file_cache_entry
        WHERE namespace = :namespace AND partition = :partition AND cache_key = :cacheKey
        """
    )
    abstract suspend fun select(
        namespace: String,
        partition: String,
        cacheKey: String,
    ): FileEntryEntity?

    @Query("SELECT * FROM file_cache_entry ORDER BY namespace ASC, partition ASC, cache_key ASC")
    abstract suspend fun selectAll(): List<FileEntryEntity>

    @Query("SELECT generation FROM file_cache_entry")
    abstract suspend fun selectAllGenerations(): List<String>

    @Query(
        """
        SELECT COUNT(*) AS entryCount, COALESCE(SUM(size_bytes), 0) AS contentBytes
        FROM file_cache_entry
        """
    )
    abstract suspend fun totalOccupancy(): FileOccupancy

    @Query(
        """
        SELECT COUNT(*) AS entryCount, COALESCE(SUM(size_bytes), 0) AS contentBytes
        FROM file_cache_entry
        WHERE namespace = :namespace
        """
    )
    abstract suspend fun namespaceOccupancy(namespace: String): FileOccupancy

    /**
     * Returns eviction candidates in removal order: expired first, then least recently used.
     *
     * @param epochSeconds Expiration cutoff epoch-second component.
     * @param nanoseconds Expiration cutoff nanosecond component.
     * @param limit Maximum candidates to return.
     * @param offset Candidates already considered by the caller.
     */
    @Query(
        """
        SELECT namespace, partition, cache_key AS cacheKey, generation, size_bytes AS sizeBytes
        FROM file_cache_entry
        ORDER BY
            CASE
                WHEN expires_at_epoch_seconds IS NOT NULL
                     AND (
                         expires_at_epoch_seconds < :epochSeconds
                         OR (
                             expires_at_epoch_seconds = :epochSeconds
                             AND expires_at_nanoseconds <= :nanoseconds
                         )
                     )
                THEN 0 ELSE 1
            END ASC,
            last_accessed_at_epoch_seconds ASC,
            last_accessed_at_nanoseconds ASC,
            written_at_epoch_seconds ASC,
            written_at_nanoseconds ASC,
            namespace ASC,
            partition ASC,
            cache_key ASC
        LIMIT :limit OFFSET :offset
        """
    )
    abstract suspend fun selectEvictionCandidates(
        epochSeconds: Long,
        nanoseconds: Int,
        limit: Int,
        offset: Int,
    ): List<FileEvictionCandidate>

    /** Returns eviction candidates restricted to one namespace, in the same removal order. */
    @Query(
        """
        SELECT namespace, partition, cache_key AS cacheKey, generation, size_bytes AS sizeBytes
        FROM file_cache_entry
        WHERE namespace = :namespace
        ORDER BY
            CASE
                WHEN expires_at_epoch_seconds IS NOT NULL
                     AND (
                         expires_at_epoch_seconds < :epochSeconds
                         OR (
                             expires_at_epoch_seconds = :epochSeconds
                             AND expires_at_nanoseconds <= :nanoseconds
                         )
                     )
                THEN 0 ELSE 1
            END ASC,
            last_accessed_at_epoch_seconds ASC,
            last_accessed_at_nanoseconds ASC,
            written_at_epoch_seconds ASC,
            written_at_nanoseconds ASC,
            partition ASC,
            cache_key ASC
        LIMIT :limit OFFSET :offset
        """
    )
    abstract suspend fun selectNamespaceEvictionCandidates(
        namespace: String,
        epochSeconds: Long,
        nanoseconds: Int,
        limit: Int,
        offset: Int,
    ): List<FileEvictionCandidate>

    /** Returns every entry whose expiration is strictly older than the supplied cutoff. */
    @Query(
        """
        SELECT namespace, partition, cache_key AS cacheKey, generation, size_bytes AS sizeBytes
        FROM file_cache_entry
        WHERE expires_at_epoch_seconds IS NOT NULL
          AND expires_at_nanoseconds IS NOT NULL
          AND (
              expires_at_epoch_seconds < :epochSeconds
              OR (
                  expires_at_epoch_seconds = :epochSeconds
                  AND expires_at_nanoseconds < :nanoseconds
              )
          )
        """
    )
    abstract suspend fun selectExpired(
        epochSeconds: Long,
        nanoseconds: Int,
    ): List<FileEvictionCandidate>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsert(entry: FileEntryEntity)

    @Query("DELETE FROM file_cache_entry WHERE generation IN (:generations)")
    abstract suspend fun deleteGenerations(generations: List<String>): Int

    @Transaction
    open suspend fun commit(entry: FileEntryEntity, victimGenerations: List<String>) {
        if (victimGenerations.isNotEmpty()) deleteGenerations(victimGenerations)
        upsert(entry)
    }

    @Query(
        """
        UPDATE file_cache_entry
        SET last_accessed_at_epoch_seconds = :epochSeconds,
            last_accessed_at_nanoseconds = :nanoseconds
        WHERE generation = :generation
        """
    )
    abstract suspend fun touch(generation: String, epochSeconds: Long, nanoseconds: Int): Int

    @Query(
        """
        DELETE FROM file_cache_entry
        WHERE namespace = :namespace AND partition = :partition AND cache_key = :cacheKey
        """
    )
    abstract suspend fun delete(namespace: String, partition: String, cacheKey: String): Int

    @Query("DELETE FROM file_cache_entry")
    abstract suspend fun deleteAll(): Int
}

/** Aggregate logical occupancy returned directly by SQLite. */
internal data class FileOccupancy(
    val entryCount: Long,
    val contentBytes: Long,
)

/** Minimal projection used to choose and delete eviction victims. */
internal data class FileEvictionCandidate(
    val namespace: String,
    val partition: String,
    val cacheKey: String,
    val generation: String,
    val sizeBytes: Long,
)
