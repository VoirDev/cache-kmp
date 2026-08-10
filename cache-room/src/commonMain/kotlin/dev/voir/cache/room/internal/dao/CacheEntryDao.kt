package dev.voir.cache.room.internal.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import dev.voir.cache.room.internal.entity.CacheEntryEntity

/** Room access point for generic persistent cache rows. */
@Dao
internal abstract class CacheEntryDao {
    /**
     * Reads the row at an exact address.
     *
     * @param namespace Resource namespace.
     * @param partition Isolation partition.
     * @param cacheKey Encoded key within the partition.
     * @return Matching row, or `null` when absent.
     */
    @Query(
        """
        SELECT * FROM cache_entry
        WHERE namespace = :namespace AND partition = :partition AND cache_key = :cacheKey
        """
    )
    abstract suspend fun select(
        namespace: String,
        partition: String,
        cacheKey: String,
    ): CacheEntryEntity?

    /**
     * Inserts or atomically replaces [entry].
     *
     * @param entry Complete row to persist.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entry: CacheEntryEntity)

    /**
     * Updates the invalidation instant for an exact address.
     *
     * @param namespace Resource namespace.
     * @param partition Isolation partition.
     * @param cacheKey Encoded key within the partition.
     * @param epochSeconds Invalidation epoch-second component.
     * @param nanoseconds Invalidation nanosecond component.
     */
    @Query(
        """
        UPDATE cache_entry
        SET invalidated_at_epoch_seconds = :epochSeconds,
            invalidated_at_nanoseconds = :nanoseconds
        WHERE namespace = :namespace AND partition = :partition AND cache_key = :cacheKey
        """
    )
    abstract suspend fun invalidate(
        namespace: String,
        partition: String,
        cacheKey: String,
        epochSeconds: Long,
        nanoseconds: Int,
    )

    /** Invalidates and counts one exact namespace/partition/family selection. */
    @Query(
        """
        UPDATE cache_entry
        SET invalidated_at_epoch_seconds = :epochSeconds,
            invalidated_at_nanoseconds = :nanoseconds
        WHERE namespace = :namespace AND partition = :partition AND family = :family
        """
    )
    abstract suspend fun invalidateFamily(
        namespace: String,
        partition: String,
        family: String,
        epochSeconds: Long,
        nanoseconds: Int,
    ): Int

    /**
     * Removes the row at an exact address.
     *
     * @param namespace Resource namespace.
     * @param partition Isolation partition.
     * @param cacheKey Encoded key within the partition.
     */
    @Query(
        """
        DELETE FROM cache_entry
        WHERE namespace = :namespace AND partition = :partition AND cache_key = :cacheKey
        """
    )
    abstract suspend fun delete(
        namespace: String,
        partition: String,
        cacheKey: String,
    )

    /** Deletes and counts one exact namespace/partition/family selection. */
    @Query(
        """
        DELETE FROM cache_entry
        WHERE namespace = :namespace AND partition = :partition AND family = :family
        """
    )
    abstract suspend fun deleteFamily(
        namespace: String,
        partition: String,
        family: String,
    ): Int

    /**
     * Removes every row in [namespace].
     *
     * @param namespace Resource namespace to clear.
     */
    @Query("DELETE FROM cache_entry WHERE namespace = :namespace")
    abstract suspend fun deleteNamespace(namespace: String)

    /**
     * Removes every row in [partition] across namespaces.
     *
     * @param partition Isolation partition to clear.
     */
    @Query("DELETE FROM cache_entry WHERE partition = :partition")
    abstract suspend fun deletePartition(partition: String)

    /** Removes every row owned by this database. */
    @Query("DELETE FROM cache_entry")
    abstract suspend fun deleteAll()

    /** Returns exact total row and encoded-payload occupancy. */
    @Query(
        """
        SELECT COUNT(*) AS rowCount,
               COALESCE(SUM(LENGTH(payload)), 0) AS payloadBytes
        FROM cache_entry
        """
    )
    abstract suspend fun storageAggregate(): CacheStorageAggregate

    /** Returns exact row and encoded-payload occupancy grouped by namespace. */
    @Query(
        """
        SELECT namespace AS namespace,
               COUNT(*) AS rowCount,
               COALESCE(SUM(LENGTH(payload)), 0) AS payloadBytes
        FROM cache_entry
        GROUP BY namespace
        ORDER BY namespace ASC
        """
    )
    abstract suspend fun namespaceStorageAggregates(): List<CacheNamespaceStorageAggregate>

    /** Counts expired rows and bytes without reading their payloads into application memory. */
    @Query(
        """
        SELECT COUNT(*) AS rowCount,
               COALESCE(SUM(LENGTH(payload)), 0) AS payloadBytes
        FROM cache_entry
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
    protected abstract suspend fun expiredAggregate(
        epochSeconds: Long,
        nanoseconds: Int,
    ): CacheStorageAggregate

    /** Deletes expired rows entirely inside SQLite. */
    @Query(
        """
        DELETE FROM cache_entry
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
    protected abstract suspend fun deleteExpired(epochSeconds: Long, nanoseconds: Int): Int

    /** Counts rows outside the most-recent prefix of each namespace. */
    @Query(
        """
        SELECT COUNT(*) AS rowCount,
               COALESCE(SUM(payloadBytes), 0) AS payloadBytes
        FROM (
            SELECT LENGTH(payload) AS payloadBytes,
                   ROW_NUMBER() OVER (
                       PARTITION BY namespace
                       ORDER BY last_accessed_at_epoch_seconds DESC,
                                last_accessed_at_nanoseconds DESC,
                                written_at_epoch_seconds DESC,
                                written_at_nanoseconds DESC,
                                partition ASC,
                                cache_key ASC
                   ) AS retentionRank
            FROM cache_entry
        )
        WHERE retentionRank > :maxEntriesPerNamespace
        """
    )
    protected abstract suspend fun namespaceOverflowAggregate(
        maxEntriesPerNamespace: Int,
    ): CacheStorageAggregate

    /** Deletes rows outside the most-recent prefix of each namespace. */
    @Query(
        """
        DELETE FROM cache_entry
        WHERE rowid IN (
            SELECT retainedRowId
            FROM (
                SELECT rowid AS retainedRowId,
                       ROW_NUMBER() OVER (
                           PARTITION BY namespace
                           ORDER BY last_accessed_at_epoch_seconds DESC,
                                    last_accessed_at_nanoseconds DESC,
                                    written_at_epoch_seconds DESC,
                                    written_at_nanoseconds DESC,
                                    partition ASC,
                                    cache_key ASC
                       ) AS retentionRank
                FROM cache_entry
            )
            WHERE retentionRank > :maxEntriesPerNamespace
        )
        """
    )
    protected abstract suspend fun deleteNamespaceOverflow(maxEntriesPerNamespace: Int): Int

    /** Counts rows outside the whole-store most-recent prefix. */
    @Query(
        """
        SELECT COUNT(*) AS rowCount,
               COALESCE(SUM(payloadBytes), 0) AS payloadBytes
        FROM (
            SELECT LENGTH(payload) AS payloadBytes,
                   ROW_NUMBER() OVER (
                       ORDER BY last_accessed_at_epoch_seconds DESC,
                                last_accessed_at_nanoseconds DESC,
                                written_at_epoch_seconds DESC,
                                written_at_nanoseconds DESC,
                                namespace ASC,
                                partition ASC,
                                cache_key ASC
                   ) AS retentionRank
            FROM cache_entry
        )
        WHERE retentionRank > :maxTotalEntries
        """
    )
    protected abstract suspend fun totalEntryOverflowAggregate(
        maxTotalEntries: Int,
    ): CacheStorageAggregate

    /** Deletes rows outside the whole-store most-recent prefix. */
    @Query(
        """
        DELETE FROM cache_entry
        WHERE rowid IN (
            SELECT retainedRowId
            FROM (
                SELECT rowid AS retainedRowId,
                       ROW_NUMBER() OVER (
                           ORDER BY last_accessed_at_epoch_seconds DESC,
                                    last_accessed_at_nanoseconds DESC,
                                    written_at_epoch_seconds DESC,
                                    written_at_nanoseconds DESC,
                                    namespace ASC,
                                    partition ASC,
                                    cache_key ASC
                       ) AS retentionRank
                FROM cache_entry
            )
            WHERE retentionRank > :maxTotalEntries
        )
        """
    )
    protected abstract suspend fun deleteTotalEntryOverflow(maxTotalEntries: Int): Int

    /** Counts the least-recent suffix beyond the cumulative payload-byte bound. */
    @Query(
        """
        SELECT COUNT(*) AS rowCount,
               COALESCE(SUM(payloadBytes), 0) AS payloadBytes
        FROM (
            SELECT LENGTH(payload) AS payloadBytes,
                   SUM(LENGTH(payload)) OVER (
                       ORDER BY last_accessed_at_epoch_seconds DESC,
                                last_accessed_at_nanoseconds DESC,
                                written_at_epoch_seconds DESC,
                                written_at_nanoseconds DESC,
                                namespace ASC,
                                partition ASC,
                                cache_key ASC
                       ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                   ) AS cumulativePayloadBytes
            FROM cache_entry
        )
        WHERE cumulativePayloadBytes > :maxTotalPayloadBytes
        """
    )
    protected abstract suspend fun payloadOverflowAggregate(
        maxTotalPayloadBytes: Long,
    ): CacheStorageAggregate

    /** Deletes the least-recent suffix beyond the cumulative payload-byte bound. */
    @Query(
        """
        DELETE FROM cache_entry
        WHERE rowid IN (
            SELECT retainedRowId
            FROM (
                SELECT rowid AS retainedRowId,
                       SUM(LENGTH(payload)) OVER (
                           ORDER BY last_accessed_at_epoch_seconds DESC,
                                    last_accessed_at_nanoseconds DESC,
                                    written_at_epoch_seconds DESC,
                                    written_at_nanoseconds DESC,
                                    namespace ASC,
                                    partition ASC,
                                    cache_key ASC
                           ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                       ) AS cumulativePayloadBytes
                FROM cache_entry
            )
            WHERE cumulativePayloadBytes > :maxTotalPayloadBytes
        )
        """
    )
    protected abstract suspend fun deletePayloadOverflow(maxTotalPayloadBytes: Long): Int

    /**
     * Applies all retention limits in one writer transaction without materializing payload rows.
     *
     * @param expiredBeforeEpochSeconds Optional expiration cutoff epoch-second component.
     * @param expiredBeforeNanoseconds Optional expiration cutoff nanosecond component.
     * @param maxEntriesPerNamespace Optional maximum retained rows per namespace.
     * @param maxTotalEntries Optional maximum retained rows for the complete store.
     * @param maxTotalPayloadBytes Optional encoded-payload byte bound for the complete store.
     * @return Exact aggregate removed by this maintenance pass.
     */
    @Transaction
    open suspend fun prune(
        expiredBeforeEpochSeconds: Long?,
        expiredBeforeNanoseconds: Int?,
        maxEntriesPerNamespace: Int?,
        maxTotalEntries: Int?,
        maxTotalPayloadBytes: Long?,
    ): CacheStorageAggregate {
        var removedRows = 0L
        var removedBytes = 0L

        suspend fun remove(aggregate: CacheStorageAggregate, operation: suspend () -> Int) {
            if (aggregate.rowCount == 0L) return
            operation()
            removedRows += aggregate.rowCount
            removedBytes += aggregate.payloadBytes
        }

        if (expiredBeforeEpochSeconds != null && expiredBeforeNanoseconds != null) {
            remove(expiredAggregate(expiredBeforeEpochSeconds, expiredBeforeNanoseconds)) {
                deleteExpired(expiredBeforeEpochSeconds, expiredBeforeNanoseconds)
            }
        }
        if (maxEntriesPerNamespace != null) {
            remove(namespaceOverflowAggregate(maxEntriesPerNamespace)) {
                deleteNamespaceOverflow(maxEntriesPerNamespace)
            }
        }
        if (maxTotalEntries != null) {
            remove(totalEntryOverflowAggregate(maxTotalEntries)) {
                deleteTotalEntryOverflow(maxTotalEntries)
            }
        }
        if (maxTotalPayloadBytes != null) {
            remove(payloadOverflowAggregate(maxTotalPayloadBytes)) {
                deletePayloadOverflow(maxTotalPayloadBytes)
            }
        }
        return CacheStorageAggregate(removedRows, removedBytes)
    }
}
