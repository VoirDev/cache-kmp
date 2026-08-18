package dev.voir.cache.core.file.store

import dev.voir.cache.core.file.FileCacheLease

/**
 * Storage SPI for immutable filesystem generations and their durable logical index.
 *
 * Implementations isolate runtime failures from raw paths and content in diagnostics. Every method
 * must reject use after [close], and close must be idempotent.
 */
internal interface FileCacheStore {
    /** Returns the current logical record at [address], or `null` when absent. */
    suspend fun read(address: FileStoreAddress): StoredFileRecord?

    /** Creates a unique staging session that rejects bytes beyond [maxEntryBytes]. */
    suspend fun startWrite(address: FileStoreAddress, maxEntryBytes: Long): FileStoreWriteSession

    /**
     * Atomically consumes [request], enforces namespace and whole-store limits, and promotes it.
     *
     * Rejection deletes the staged generation and leaves prior committed state unchanged.
     */
    suspend fun commit(request: FileStoreCommitRequest): FileStoreCommitResult

    /** Deletes an uncommitted staged generation. Repeated discards are no-ops. */
    suspend fun discard(staged: StagedFile)

    /** Acquires an active physical-generation lease when [record] is still authoritative. */
    suspend fun acquire(record: StoredFileRecord): FileCacheLease?

    /** Removes one exact logical record and schedules its physical generation for deletion. */
    suspend fun remove(address: FileStoreAddress)

    /** Removes every logical record owned by this store. */
    suspend fun clearAll()

    /** Applies expiration maintenance while preserving active physical leases. */
    suspend fun prune(request: FileStorePruneRequest): FileStorePruneResult

    /** Reconciles missing records, orphan generations, and interrupted staging files. */
    suspend fun recover(): FileStoreRecoveryResult

    /**
     * Releases store resources without invalidating existing leases. This operation is idempotent.
     * A lease closed afterward may defer its final deletion until the next recovery pass.
     */
    suspend fun close()
}
