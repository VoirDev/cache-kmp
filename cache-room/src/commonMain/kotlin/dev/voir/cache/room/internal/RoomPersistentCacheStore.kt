package dev.voir.cache.room.internal

import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheFamily
import dev.voir.cache.core.persistence.CacheAddress
import dev.voir.cache.core.persistence.ExperimentalCacheStoreApi
import dev.voir.cache.core.persistence.PersistedCacheRecord
import dev.voir.cache.core.persistence.PersistentCacheStore
import dev.voir.cache.core.persistence.PersistentStoreNamespaceStatistics
import dev.voir.cache.core.persistence.PersistentStoreStatistics
import dev.voir.cache.core.persistence.PruneRequest
import dev.voir.cache.core.persistence.PruneResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/** Room-backed implementation of the storage-neutral persistent cache SPI. */
@OptIn(ExperimentalCacheStoreApi::class)
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
     * Marks [address] invalid without removing its payload.
     *
     * @param address Exact encoded address to invalidate.
     * @param invalidatedAt Authoritative invalidation instant.
     */
    override suspend fun invalidate(address: CacheAddress, invalidatedAt: Instant): Unit =
        withOpenStore {
            database.cacheEntryDao().invalidate(
                namespace = address.namespace.value,
                partition = address.partition.value,
                cacheKey = address.key,
                epochSeconds = invalidatedAt.epochSeconds,
                nanoseconds = invalidatedAt.nanosecondsOfSecond,
            )
        }

    /** Marks all rows in one typed family invalid and returns the exact affected count. */
    override suspend fun invalidateFamily(
        namespace: CacheNamespace,
        family: EncodedCacheFamily,
        invalidatedAt: Instant,
    ): Long = withOpenStore {
        database.cacheEntryDao().invalidateFamily(
            namespace = namespace.value,
            partition = family.partition.value,
            family = family.value,
            epochSeconds = invalidatedAt.epochSeconds,
            nanoseconds = invalidatedAt.nanosecondsOfSecond,
        ).toLong()
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

    /** Removes all rows in one typed family and returns the exact affected count. */
    override suspend fun removeFamily(
        namespace: CacheNamespace,
        family: EncodedCacheFamily,
    ): Long = withOpenStore {
        database.cacheEntryDao().deleteFamily(
            namespace = namespace.value,
            partition = family.partition.value,
            family = family.value,
        ).toLong()
    }

    /**
     * Removes every record belonging to [namespace].
     *
     * @param namespace Resource namespace to clear.
     */
    override suspend fun clearNamespace(namespace: CacheNamespace): Unit = withOpenStore {
        database.cacheEntryDao().deleteNamespace(namespace.value)
    }

    /**
     * Removes every record belonging to [partition] across namespaces.
     *
     * @param partition Isolation partition to clear.
     */
    override suspend fun clearPartition(partition: CachePartition): Unit = withOpenStore {
        database.cacheEntryDao().deletePartition(partition.value)
    }

    /** Removes every persistent cache row. */
    override suspend fun clearAll(): Unit = withOpenStore {
        database.cacheEntryDao().deleteAll()
    }

    /**
     * Applies expiration and per-namespace retention limits.
     *
     * Persistent recency is approximate: version 1 records access time on writes and hydration does
     * not add a second disk write.
     *
     * @param request Expiration and row-count limits to apply.
     * @return Exact removed row and payload-byte counts.
     */
    override suspend fun prune(request: PruneRequest): PruneResult = withOpenStore {
        val removed = database.cacheEntryDao().prune(
            expiredBeforeEpochSeconds = request.expiredBefore?.epochSeconds,
            expiredBeforeNanoseconds = request.expiredBefore?.nanosecondsOfSecond,
            maxEntriesPerNamespace = request.maxEntriesPerNamespace,
            maxTotalEntries = request.maxTotalEntries,
            maxTotalPayloadBytes = request.maxTotalPayloadBytes,
        )
        PruneResult(
            rowsRemoved = removed.rowCount,
            bytesRemoved = removed.payloadBytes,
        )
    }

    /** Returns exact row and encoded-payload occupancy calculated inside SQLite. */
    override suspend fun statistics(): PersistentStoreStatistics = withOpenStore {
        val total = database.cacheEntryDao().storageAggregate()
        val namespaces = database.cacheEntryDao().namespaceStorageAggregates().associate { item ->
            CacheNamespace(item.namespace) to PersistentStoreNamespaceStatistics(
                rows = item.rowCount,
                payloadBytes = item.payloadBytes,
            )
        }
        PersistentStoreStatistics(
            totalRows = total.rowCount,
            totalPayloadBytes = total.payloadBytes,
            namespaces = namespaces,
            isExact = true,
        )
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
