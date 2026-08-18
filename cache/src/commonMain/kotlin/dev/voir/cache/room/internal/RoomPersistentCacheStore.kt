package dev.voir.cache.room.internal

import dev.voir.cache.core.persistence.CacheAddress
import dev.voir.cache.core.persistence.PersistedCacheRecord
import dev.voir.cache.core.persistence.PersistentCacheStore
import dev.voir.cache.core.persistence.PruneRequest
import dev.voir.cache.core.persistence.PruneResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Room-backed implementation of the storage-neutral persistent cache SPI. */
internal class RoomPersistentCacheStore(
    private val database: CacheDatabase,
) : PersistentCacheStore {
    private val operationMutex = Mutex()
    private var closed = false

    /**
     * Reads the record stored at [address].
     *
     * @param address Exact encoded address to read.
     * @return Stored record, or `null` when absent.
     */
    override suspend fun read(address: CacheAddress): PersistedCacheRecord? = withOpenStore {
        database.cacheEntryDao().select(
            namespace = address.namespace.value,
            partition = address.partition.value,
            cacheKey = address.key,
        )?.toRecord()
    }

    /**
     * Atomically inserts or replaces [record].
     *
     * @param record Complete opaque payload and metadata to store.
     */
    override suspend fun write(record: PersistedCacheRecord): Unit = withOpenStore {
        database.cacheEntryDao().upsert(record.toEntity())
    }

    /**
     * Removes exactly [address].
     *
     * @param address Exact encoded address to remove.
     */
    override suspend fun remove(address: CacheAddress): Unit = withOpenStore {
        database.cacheEntryDao().delete(
            namespace = address.namespace.value,
            partition = address.partition.value,
            cacheKey = address.key,
        )
    }

    /** Removes every persistent cache row. */
    override suspend fun clearAll(): Unit = withOpenStore {
        database.cacheEntryDao().deleteAll()
    }

    /**
     * Applies expiration and whole-store retention limits.
     *
     * Persistent recency is approximate: access time is recorded on writes, and hydration does not
     * add a second disk write.
     *
     * @param request Expiration and row-count limits to apply.
     * @return Exact removed row and payload-byte counts.
     */
    override suspend fun prune(request: PruneRequest): PruneResult = withOpenStore {
        val removed = database.cacheEntryDao().prune(
            expiredBeforeEpochSeconds = request.expiredBefore?.epochSeconds,
            expiredBeforeNanoseconds = request.expiredBefore?.nanosecondsOfSecond,
            maxTotalEntries = request.maxTotalEntries,
            maxTotalPayloadBytes = request.maxTotalPayloadBytes,
        )
        PruneResult(rowsRemoved = removed.rowCount, bytesRemoved = removed.payloadBytes)
    }

    /** Releases the Room database once; repeated calls are no-ops. */
    override suspend fun close() {
        operationMutex.withLock {
            if (closed) return
            closed = true
            database.close()
        }
    }

    /**
     * Serializes an operation with close and rejects use after lifecycle termination.
     *
     * @param operation Database operation to execute while the store is open.
     * @return Result produced by [operation].
     */
    private suspend fun <T> withOpenStore(operation: suspend () -> T): T =
        operationMutex.withLock {
            check(!closed) { "Persistent cache store is closed." }
            operation()
        }
}
