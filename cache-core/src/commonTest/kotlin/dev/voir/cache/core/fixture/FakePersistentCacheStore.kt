package dev.voir.cache.core.fixture

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/**
 * Deterministic encoded store with injectable delays and failures for coordination tests.
 *
 * @param initialRecords Records copied into the fake backend before the test starts.
 */
@OptIn(ExperimentalCacheStoreApi::class)
internal class FakePersistentCacheStore(
    initialRecords: Collection<PersistedCacheRecord> = emptyList(),
) : PersistentCacheStore {
    private val mutex = Mutex()
    private val records = initialRecords.associateByTo(mutableMapOf()) { record -> record.address }

    /** Optional gate completed when a read may continue. */
    var readRelease: CompletableDeferred<Unit>? = null

    /** Signal completed when the first read starts. */
    val readStarted: CompletableDeferred<Unit> = CompletableDeferred()

    /** Number of persistent reads performed. */
    var readCount: Int = 0
        private set

    /** Number of persistent writes performed. */
    var writeCount: Int = 0
        private set

    /** Optional gate completed before a write may continue. */
    var writeRelease: CompletableDeferred<Unit>? = null

    /** Signal completed when the first write starts. */
    val writeStarted: CompletableDeferred<Unit> = CompletableDeferred()

    /** Whether reads throw an injected backend failure. */
    var failReads: Boolean = false

    /** Whether writes throw an injected backend failure. */
    var failWrites: Boolean = false

    /** Whether invalidations throw an injected backend failure. */
    var failInvalidations: Boolean = false

    /** Whether removals throw an injected backend failure. */
    var failRemovals: Boolean = false

    /** Whether bulk clear operations throw an injected backend failure. */
    var failClears: Boolean = false

    /** Whether pruning throws an injected backend failure. */
    var failPrunes: Boolean = false

    /** Whether statistics throws an injected backend failure. */
    var failStatistics: Boolean = false

    /** Whether close throws an injected backend failure. */
    var failClose: Boolean = false

    /** Optional gate completed before close may continue. */
    var closeRelease: CompletableDeferred<Unit>? = null

    /** Signal completed when the first close starts. */
    val closeStarted: CompletableDeferred<Unit> = CompletableDeferred()

    /** Number of close calls received by this store. */
    var closeCount: Int = 0
        private set

    /** Most recent retention request received from the manager. */
    var lastPruneRequest: PruneRequest? = null
        private set

    /** Whether close was invoked. */
    var isClosed: Boolean = false
        private set

    /**
     * Reads [address] after the optional gate and applies the configured read failure.
     *
     * @param address Exact encoded address to read.
     * @return Stored record, or `null` when absent.
     */
    override suspend fun read(address: CacheAddress): PersistedCacheRecord? {
        readCount += 1
        readStarted.complete(Unit)
        readRelease?.await()
        if (failReads) error("Injected persistent read failure.")
        return mutex.withLock { records[address] }
    }

    /**
     * Inserts or replaces [record] unless write failure is enabled.
     *
     * @param record Complete encoded record to store.
     */
    override suspend fun write(record: PersistedCacheRecord) {
        writeCount += 1
        writeStarted.complete(Unit)
        writeRelease?.await()
        if (failWrites) error("Injected persistent write failure.")
        mutex.withLock { records[record.address] = record }
    }

    /**
     * Rewrites invalidation metadata for [address] unless invalidation failure is enabled.
     *
     * @param address Exact encoded address to invalidate.
     * @param invalidatedAt Authoritative invalidation instant.
     */
    override suspend fun invalidate(address: CacheAddress, invalidatedAt: Instant) {
        if (failInvalidations) error("Injected persistent invalidation failure.")
        mutex.withLock {
            records[address] = records[address]?.let { record ->
                record.copy(metadata = record.metadata.copy(invalidatedAt = invalidatedAt))
            } ?: return@withLock
        }
    }

    /** Invalidates all fake records matching one namespace, partition, and family. */
    override suspend fun invalidateFamily(
        namespace: CacheNamespace,
        family: EncodedCacheFamily,
        invalidatedAt: Instant,
    ): Long {
        if (failInvalidations) error("Injected persistent invalidation failure.")
        return mutex.withLock {
            val matching = records.keys.filter { address ->
                address.namespace == namespace &&
                    address.partition == family.partition &&
                    address.family == family.value
            }
            matching.forEach { address ->
                records[address] = checkNotNull(records[address]).copy(
                    metadata = checkNotNull(records[address]).metadata.copy(
                        invalidatedAt = invalidatedAt
                    )
                )
            }
            matching.size.toLong()
        }
    }

    /**
     * Removes [address] unless removal failure is enabled.
     *
     * @param address Exact encoded address to remove.
     */
    override suspend fun remove(address: CacheAddress) {
        if (failRemovals) error("Injected persistent removal failure.")
        mutex.withLock { records.remove(address) }
    }

    /** Removes all fake records matching one namespace, partition, and family. */
    override suspend fun removeFamily(
        namespace: CacheNamespace,
        family: EncodedCacheFamily,
    ): Long {
        if (failRemovals) error("Injected persistent removal failure.")
        return mutex.withLock {
            val matching = records.keys.filter { address ->
                address.namespace == namespace &&
                    address.partition == family.partition &&
                    address.family == family.value
            }
            matching.forEach(records::remove)
            matching.size.toLong()
        }
    }

    /**
     * Removes records in [namespace] unless bulk-clear failure is enabled.
     *
     * @param namespace Resource namespace to clear.
     */
    override suspend fun clearNamespace(namespace: CacheNamespace) {
        if (failClears) error("Injected persistent namespace-clear failure.")
        mutex.withLock { records.keys.removeAll { address -> address.namespace == namespace } }
    }

    /**
     * Removes records in [partition] unless bulk-clear failure is enabled.
     *
     * @param partition Isolation boundary to clear across namespaces.
     */
    override suspend fun clearPartition(partition: CachePartition) {
        if (failClears) error("Injected persistent partition-clear failure.")
        mutex.withLock { records.keys.removeAll { address -> address.partition == partition } }
    }

    /** Clears all fake records unless bulk-clear failure is enabled. */
    override suspend fun clearAll() {
        if (failClears) error("Injected persistent global-clear failure.")
        mutex.withLock { records.clear() }
    }

    /** Applies all storage-neutral maintenance bounds with deterministic retention ordering. */
    override suspend fun prune(request: PruneRequest): PruneResult = mutex.withLock {
        lastPruneRequest = request
        if (failPrunes) error("Injected persistent prune failure.")
        val removed = mutableListOf<PersistedCacheRecord>()
        request.expiredBefore?.let { cutoff ->
            records.values
                .filter { record -> record.metadata.expiresAt?.let { it < cutoff } == true }
                .map { record -> record.address }
                .forEach { address -> records.remove(address)?.let(removed::add) }
        }
        request.maxEntriesPerNamespace?.let { limit ->
            records.values.groupBy { record -> record.address.namespace }.values.forEach { rows ->
                rows.sortedWith(retentionOrder).drop(limit).forEach { record ->
                    records.remove(record.address)?.let(removed::add)
                }
            }
        }
        request.maxTotalEntries?.let { limit ->
            records.values.sortedWith(retentionOrder).drop(limit).forEach { record ->
                records.remove(record.address)?.let(removed::add)
            }
        }
        request.maxTotalPayloadBytes?.let { limit ->
            var cumulativeBytes = 0L
            records.values.sortedWith(retentionOrder).forEach { record ->
                cumulativeBytes += record.payload.size.toLong()
                if (cumulativeBytes > limit) {
                    records.remove(record.address)?.let(removed::add)
                }
            }
        }
        PruneResult(
            rowsRemoved = removed.size.toLong(),
            bytesRemoved = removed.sumOf { record -> record.payload.size.toLong() },
        )
    }

    /** Returns exact sanitized occupancy for manager-level contract tests. */
    override suspend fun statistics(): PersistentStoreStatistics = mutex.withLock {
        if (failStatistics) error("Injected persistent statistics failure.")
        PersistentStoreStatistics(
            totalRows = records.size.toLong(),
            totalPayloadBytes = records.values.sumOf { record -> record.payload.size.toLong() },
            namespaces = records.values
                .groupBy { record -> record.address.namespace }
                .mapValues { (_, rows) ->
                    PersistentStoreNamespaceStatistics(
                        rows = rows.size.toLong(),
                        payloadBytes = rows.sumOf { record -> record.payload.size.toLong() },
                    )
                },
            isExact = true,
        )
    }

    /** Marks this fake backend closed for lifecycle assertions. */
    override suspend fun close() {
        closeCount += 1
        closeStarted.complete(Unit)
        closeRelease?.await()
        if (failClose) error("Injected persistent close failure.")
        isClosed = true
    }

    /**
     * Returns the current record at [address] for assertions and manager recreation.
     *
     * @param address Exact encoded address to inspect.
     * @return Stored record, or `null` when absent.
     */
    suspend fun record(address: CacheAddress): PersistedCacheRecord? =
        mutex.withLock { records[address] }

    /** Deterministic most-recent-first ordering shared by every retention bound. */
    private val retentionOrder: Comparator<PersistedCacheRecord> =
        compareByDescending<PersistedCacheRecord> { record -> record.lastAccessedAt }
            .thenByDescending { record -> record.writtenAt }
            .thenBy { record -> record.address.namespace.value }
            .thenBy { record -> record.address.partition.value }
            .thenBy { record -> record.address.key }
}
