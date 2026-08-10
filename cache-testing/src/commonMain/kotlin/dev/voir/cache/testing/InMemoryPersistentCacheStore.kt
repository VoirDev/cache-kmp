package dev.voir.cache.testing

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

/**
 * In-memory implementation of the encoded persistent-store SPI for integration tests.
 *
 * Failure flags let a test verify the cache's documented memory-first degradation behavior. The
 * store retains records after [close] so the same instance can model a persistent backend across
 * cache-manager recreation.
 *
 * @param initialRecords Records copied into the store before the test starts.
 */
@OptIn(ExperimentalCacheStoreApi::class)
public class InMemoryPersistentCacheStore public constructor(
    initialRecords: Collection<PersistedCacheRecord> = emptyList(),
) : PersistentCacheStore {
    /** Serializes store mutations so the fake is safe for concurrent cache operations. */
    private val mutex: Mutex = Mutex()

    /** Encoded records currently retained by the simulated backend. */
    private val records: MutableMap<CacheAddress, PersistedCacheRecord> =
        initialRecords.associateByTo(mutableMapOf()) { record -> record.address }

    /** Whether reads should throw a deterministic injected failure. */
    public var failReads: Boolean = false

    /** Whether writes should throw a deterministic injected failure. */
    public var failWrites: Boolean = false

    /** Whether invalidations should throw a deterministic injected failure. */
    public var failInvalidations: Boolean = false

    /** Whether removals should throw a deterministic injected failure. */
    public var failRemovals: Boolean = false

    /** Whether namespace, partition, and global clears should throw an injected failure. */
    public var failClears: Boolean = false

    /** Whether retention maintenance should throw an injected failure. */
    public var failPrunes: Boolean = false

    /** Whether storage statistics should throw an injected failure. */
    public var failStatistics: Boolean = false

    /** Number of reads attempted against this store. */
    public var readCount: Int = 0
        private set

    /** Number of writes attempted against this store. */
    public var writeCount: Int = 0
        private set

    /** Number of times [close] has been called. */
    public var closeCount: Int = 0
        private set

    /**
     * Reads the record at [address], or returns `null` when it is absent.
     *
     * @param address Exact encoded address to read.
     * @return Stored record, or `null` when absent.
     */
    override suspend fun read(address: CacheAddress): PersistedCacheRecord? = mutex.withLock {
        readCount += 1
        if (failReads) injectedFailure("read")
        records[address]
    }

    /**
     * Inserts or replaces [record].
     *
     * @param record Complete encoded record to store.
     */
    override suspend fun write(record: PersistedCacheRecord): Unit = mutex.withLock {
        writeCount += 1
        if (failWrites) injectedFailure("write")
        records[record.address] = record
    }

    /**
     * Updates invalidation metadata for [address] when a record exists.
     *
     * @param address Exact encoded address to invalidate.
     * @param invalidatedAt Authoritative invalidation instant.
     */
    override suspend fun invalidate(address: CacheAddress, invalidatedAt: Instant): Unit =
        mutex.withLock {
            if (failInvalidations) injectedFailure("invalidate")
            records[address] = records[address]?.let { record ->
                record.copy(metadata = record.metadata.copy(invalidatedAt = invalidatedAt))
            } ?: return@withLock
        }

    /** Invalidates every matching family record and returns the exact affected count. */
    override suspend fun invalidateFamily(
        namespace: CacheNamespace,
        family: EncodedCacheFamily,
        invalidatedAt: Instant,
    ): Long = mutex.withLock {
        if (failInvalidations) injectedFailure("invalidate family")
        val matching = records.keys.filter { address ->
            address.namespace == namespace &&
                address.partition == family.partition &&
                address.family == family.value
        }
        matching.forEach { address ->
            records[address] = checkNotNull(records[address]).let { record ->
                record.copy(metadata = record.metadata.copy(invalidatedAt = invalidatedAt))
            }
        }
        matching.size.toLong()
    }

    /**
     * Removes the record at [address].
     *
     * @param address Exact encoded address to remove.
     */
    override suspend fun remove(address: CacheAddress): Unit = mutex.withLock {
        if (failRemovals) injectedFailure("remove")
        records.remove(address)
    }

    /** Removes every matching family record and returns the exact affected count. */
    override suspend fun removeFamily(
        namespace: CacheNamespace,
        family: EncodedCacheFamily,
    ): Long = mutex.withLock {
        if (failRemovals) injectedFailure("remove family")
        val matching = records.keys.filter { address ->
            address.namespace == namespace &&
                address.partition == family.partition &&
                address.family == family.value
        }
        matching.forEach(records::remove)
        matching.size.toLong()
    }

    /**
     * Removes every record in [namespace].
     *
     * @param namespace Resource namespace to clear.
     */
    override suspend fun clearNamespace(namespace: CacheNamespace): Unit = mutex.withLock {
        if (failClears) injectedFailure("clear")
        records.keys.removeAll { address -> address.namespace == namespace }
    }

    /**
     * Removes every record in [partition] across namespaces.
     *
     * @param partition Isolation boundary to clear.
     */
    override suspend fun clearPartition(partition: CachePartition): Unit = mutex.withLock {
        if (failClears) injectedFailure("clear")
        records.keys.removeAll { address -> address.partition == partition }
    }

    /** Removes every record from this store. */
    override suspend fun clearAll(): Unit = mutex.withLock {
        if (failClears) injectedFailure("clear")
        records.clear()
    }

    /**
     * Applies expiration, namespace, whole-store row, and payload-byte bounds in [request].
     *
     * @param request Expiration cutoff and optional namespace row bound.
     * @return Counts for records and payload bytes removed.
     */
    override suspend fun prune(request: PruneRequest): PruneResult = mutex.withLock {
        if (failPrunes) injectedFailure("prune")
        val removed = mutableListOf<PersistedCacheRecord>()
        request.expiredBefore?.let { cutoff ->
            val expiredAddresses = records.values
                .filter { record -> record.metadata.expiresAt?.let { it < cutoff } == true }
                .map { record -> record.address }
            expiredAddresses.forEach { address -> records.remove(address)?.let(removed::add) }
        }

        request.maxEntriesPerNamespace?.let { limit ->
            records.values
                .groupBy { record -> record.address.namespace }
                .values
                .forEach { namespaceRecords ->
                    namespaceRecords
                        .sortedWith(retentionOrder)
                        .drop(limit)
                        .forEach { record -> records.remove(record.address)?.let(removed::add) }
                }
        }

        request.maxTotalEntries?.let { limit ->
            records.values
                .sortedWith(retentionOrder)
                .drop(limit)
                .forEach { record -> records.remove(record.address)?.let(removed::add) }
        }

        request.maxTotalPayloadBytes?.let { limit ->
            var cumulativeBytes = 0L
            records.values
                .sortedWith(retentionOrder)
                .forEach { record ->
                    cumulativeBytes += record.payload.size.toLong()
                    if (cumulativeBytes > limit) {
                        records.remove(record.address)?.let(removed::add)
                    }
                }
        }

        PruneResult(
            rowsRemoved = removed.size.toLong(),
            bytesRemoved = removed.sumOf { record -> record.payload.size }.toLong(),
        )
    }

    /** Returns exact row and encoded-payload occupancy without exposing addresses or values. */
    override suspend fun statistics(): PersistentStoreStatistics = mutex.withLock {
        if (failStatistics) injectedFailure("statistics")
        val namespaceStatistics = records.values
            .groupBy { record -> record.address.namespace }
            .entries
            .sortedBy { entry -> entry.key.value }
            .associate { (namespace, namespaceRecords) ->
                namespace to PersistentStoreNamespaceStatistics(
                    rows = namespaceRecords.size.toLong(),
                    payloadBytes = namespaceRecords.sumOf { record ->
                        record.payload.size.toLong()
                    },
                )
            }
        PersistentStoreStatistics(
            totalRows = records.size.toLong(),
            totalPayloadBytes = records.values.sumOf { record -> record.payload.size.toLong() },
            namespaces = namespaceStatistics,
            isExact = true,
        )
    }

    /** Records one lifecycle close call without discarding the simulated persistent records. */
    override suspend fun close(): Unit = mutex.withLock {
        closeCount += 1
    }

    /**
     * Returns the record at [address] without incrementing [readCount].
     *
     * @param address Exact encoded address to inspect.
     * @return Stored record, or `null` when absent.
     */
    public suspend fun peek(address: CacheAddress): PersistedCacheRecord? =
        mutex.withLock { records[address] }

    /**
     * Returns a stable snapshot of all records for assertions.
     *
     * @return Copy of the current records sorted by encoded address components.
     */
    public suspend fun records(): List<PersistedCacheRecord> = mutex.withLock {
        records.values.sortedWith(
            compareBy(
                { record -> record.address.namespace.value },
                { record -> record.address.partition.value },
                { record -> record.address.key },
            ),
        )
    }

    /**
     * Creates the deterministic failure used by injectable failure paths.
     *
     * @param operation Safe operation label included in the exception.
     * @return Nothing because the helper always throws.
     */
    private fun injectedFailure(operation: String): Nothing =
        error("Injected persistent store $operation failure.")

    /** Deterministic most-recent-first ordering shared by every row and byte bound. */
    private val retentionOrder: Comparator<PersistedCacheRecord> =
        compareByDescending<PersistedCacheRecord> { record -> record.lastAccessedAt }
            .thenByDescending { record -> record.writtenAt }
            .thenBy { record -> record.address.namespace.value }
            .thenBy { record -> record.address.partition.value }
            .thenBy { record -> record.address.key }
}
