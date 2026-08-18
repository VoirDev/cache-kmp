package dev.voir.cache.file.internal

import dev.voir.cache.core.file.CachedFile
import dev.voir.cache.core.file.FileCacheLease
import dev.voir.cache.core.file.FileCacheRejectionReason
import dev.voir.cache.core.file.FileStoreLimits
import dev.voir.cache.core.file.store.FileCacheStore
import dev.voir.cache.core.file.store.FileGeneration
import dev.voir.cache.core.file.store.FileStoreAddress
import dev.voir.cache.core.file.store.FileStoreCommitRequest
import dev.voir.cache.core.file.store.FileStoreCommitResult
import dev.voir.cache.core.file.store.FileStoreCommitted
import dev.voir.cache.core.file.store.FileStorePruneRequest
import dev.voir.cache.core.file.store.FileStorePruneResult
import dev.voir.cache.core.file.store.FileStoreRecoveryResult
import dev.voir.cache.core.file.store.FileStoreRejected
import dev.voir.cache.core.file.store.FileStoreWriteSession
import dev.voir.cache.core.file.store.StagedFile
import dev.voir.cache.core.file.store.StoredFileRecord
import dev.voir.cache.core.time.CacheClock
import dev.voir.cache.file.internal.dao.FileEvictionCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Room-indexed immutable-generation implementation of the file-cache storage SPI. */
internal class RoomFileCacheStore(
    private val database: FileIndexDatabase,
    private val fileSystem: FileStoreFileSystem,
    private val limits: FileStoreLimits,
    private val clock: CacheClock,
) : FileCacheStore {
    private val operationMutex = Mutex()
    private val leaseCounts = mutableMapOf<FileGeneration, Int>()
    private val pendingDeletes = mutableSetOf<FileGeneration>()
    private var closed = false
    private var fileSystemClosed = false

    override suspend fun read(address: FileStoreAddress): StoredFileRecord? = withOpenStore {
        val entity = database.fileEntryDao().select(
            namespace = address.namespace.value,
            partition = address.partition.value,
            cacheKey = address.key,
        ) ?: return@withOpenStore null
        val record = try {
            entity.toRecord()
        } catch (_: IllegalArgumentException) {
            deleteEntry(address)
            entity.generation.asGenerationOrNull()?.let { generation -> deleteQuietly(generation) }
            return@withOpenStore null
        }
        if (record.address != address ||
            fileSystem.contentSize(record.generation) != record.sizeBytes
        ) {
            deleteEntry(address)
            deleteQuietly(record.generation)
            return@withOpenStore null
        }
        record
    }

    override suspend fun startWrite(
        address: FileStoreAddress,
        maxEntryBytes: Long,
    ): FileStoreWriteSession = withOpenStore {
        require(maxEntryBytes > 0) { "File cache maxEntryBytes must be greater than zero." }
        val generation = fileSystem.newGeneration()
        StoreWriteSession(generation, maxEntryBytes, fileSystem.createStaging(generation))
    }

    override suspend fun commit(request: FileStoreCommitRequest): FileStoreCommitResult =
        withOpenStore {
            val stagedSize = fileSystem.stagingSize(request.staged.generation)
            if (stagedSize == null || stagedSize != request.staged.sizeBytes) {
                fileSystem.discardStaging(request.staged.generation)
                error("Staged file is missing or its size changed before commit.")
            }
            if (stagedSize > request.policy.maxEntryBytes || stagedSize > limits.maxTotalBytes) {
                fileSystem.discardStaging(request.staged.generation)
                return@withOpenStore FileStoreRejected(FileCacheRejectionReason.ENTRY_TOO_LARGE)
            }

            val dao = database.fileEntryDao()
            val replaced = dao.select(
                namespace = request.address.namespace.value,
                partition = request.address.partition.value,
                cacheKey = request.address.key,
            )
            val namespaceOccupancy = dao.namespaceOccupancy(request.address.namespace.value)
            val totalOccupancy = dao.totalOccupancy()
            val addedEntries = if (replaced == null) 1L else 0L
            val replacedBytes = replaced?.sizeBytes ?: 0L
            val plan = EvictionPlan(
                namespaceEntries = namespaceOccupancy.entryCount + addedEntries,
                namespaceBytes = namespaceOccupancy.contentBytes - replacedBytes + stagedSize,
                totalEntries = totalOccupancy.entryCount + addedEntries,
                totalBytes = totalOccupancy.contentBytes - replacedBytes + stagedSize,
            )
            val victims = selectVictims(request, plan)
                ?: run {
                    fileSystem.discardStaging(request.staged.generation)
                    return@withOpenStore FileStoreRejected(
                        FileCacheRejectionReason.CAPACITY_UNAVAILABLE
                    )
                }

            val newRecord = StoredFileRecord(
                address = request.address,
                generation = request.staged.generation,
                sizeBytes = request.staged.sizeBytes,
                content = request.content,
                metadata = request.metadata,
                writtenAt = request.writtenAt,
                lastAccessedAt = request.writtenAt,
            )
            fileSystem.promote(request.staged.generation)
            try {
                dao.commit(
                    entry = newRecord.toEntity(),
                    victimGenerations = victims.map { victim -> victim.generation },
                )
            } catch (failure: Throwable) {
                deleteQuietly(request.staged.generation)
                throw failure
            }

            (victims.map { victim -> victim.generation } + listOfNotNull(replaced?.generation))
                .asSequence()
                .mapNotNull { value -> value.asGenerationOrNull() }
                .filter { generation -> generation != newRecord.generation }
                .distinct()
                .forEach { generation -> scheduleDelete(generation) }
            FileStoreCommitted(newRecord)
        }

    override suspend fun discard(staged: StagedFile): Unit = withOpenStore {
        fileSystem.discardStaging(staged.generation)
    }

    override suspend fun acquire(record: StoredFileRecord): FileCacheLease? = withOpenStore {
        val current = database.fileEntryDao().select(
            namespace = record.address.namespace.value,
            partition = record.address.partition.value,
            cacheKey = record.address.key,
        )?.toRecord() ?: return@withOpenStore null
        if (
            current.generation != record.generation ||
            current.sizeBytes != record.sizeBytes ||
            fileSystem.contentSize(current.generation) != current.sizeBytes
        ) {
            return@withOpenStore null
        }
        val absolutePath = fileSystem.contentAbsolutePath(current.generation)
            ?: return@withOpenStore null
        val accessedAt = clock.now()
        database.fileEntryDao().touch(
            generation = current.generation.value,
            epochSeconds = accessedAt.epochSeconds,
            nanoseconds = accessedAt.nanosecondsOfSecond,
        )
        leaseCounts[current.generation] = leaseCounts.getOrElse(current.generation) { 0 } + 1
        GenerationLease(
            absolutePath = absolutePath,
            file = CachedFile(current.sizeBytes, current.content),
            generation = current.generation,
        )
    }

    override suspend fun remove(address: FileStoreAddress): Unit = withOpenStore {
        val entity = database.fileEntryDao().select(
            namespace = address.namespace.value,
            partition = address.partition.value,
            cacheKey = address.key,
        ) ?: return@withOpenStore
        deleteEntry(address)
        entity.generation.asGenerationOrNull()?.let { generation -> scheduleDelete(generation) }
    }

    override suspend fun clearAll(): Unit = withOpenStore {
        val generations = database.fileEntryDao().selectAllGenerations()
        database.fileEntryDao().deleteAll()
        generations.mapNotNull { value -> value.asGenerationOrNull() }
            .forEach { generation -> scheduleDelete(generation) }
    }

    override suspend fun prune(request: FileStorePruneRequest): FileStorePruneResult =
        withOpenStore {
            val cutoff = request.expiredBefore ?: return@withOpenStore FileStorePruneResult(0, 0)
            val expired = database.fileEntryDao().selectExpired(
                epochSeconds = cutoff.epochSeconds,
                nanoseconds = cutoff.nanosecondsOfSecond,
            )
            val removable = expired.filter { candidate ->
                candidate.generation.asGenerationOrNull()
                    ?.let { generation -> leaseCounts.getOrElse(generation) { 0 } == 0 } == true
            }
            if (removable.isEmpty()) return@withOpenStore FileStorePruneResult(0, 0)
            database.fileEntryDao().deleteGenerations(
                removable.map { candidate -> candidate.generation }
            )
            removable.forEach { candidate ->
                candidate.generation.asGenerationOrNull()
                    ?.let { generation -> scheduleDelete(generation) }
            }
            FileStorePruneResult(
                entriesRemoved = removable.size.toLong(),
                contentBytesRemoved = removable.sumOf { candidate -> candidate.sizeBytes },
            )
        }

    override suspend fun recover(): FileStoreRecoveryResult = withOpenStore {
        val entities = database.fileEntryDao().selectAll()
        val invalidGenerations = mutableListOf<String>()
        val referenced = mutableSetOf<FileGeneration>()
        var recordsRemoved = 0L
        entities.forEach { entity ->
            val record = try {
                entity.toRecord()
            } catch (_: IllegalArgumentException) {
                null
            }
            if (record == null || fileSystem.contentSize(record.generation) != record.sizeBytes) {
                invalidGenerations += entity.generation
                recordsRemoved += 1
            } else {
                referenced += record.generation
            }
        }
        if (invalidGenerations.isNotEmpty()) {
            database.fileEntryDao().deleteGenerations(invalidGenerations)
        }

        var orphanFilesRemoved = 0L
        var stagingFilesRemoved = 0L
        var physicalBytesRemoved = 0L
        fileSystem.listOwnedFiles().forEach { owned ->
            when {
                owned.kind == OwnedFileKind.STAGING -> {
                    fileSystem.discardStaging(owned.generation)
                    stagingFilesRemoved += 1
                    physicalBytesRemoved += owned.sizeBytes
                }

                owned.generation !in referenced -> {
                    fileSystem.deleteContent(owned.generation)
                    orphanFilesRemoved += 1
                    physicalBytesRemoved += owned.sizeBytes
                }
            }
        }
        FileStoreRecoveryResult(
            recordsRemoved = recordsRemoved,
            orphanFilesRemoved = orphanFilesRemoved,
            stagingFilesRemoved = stagingFilesRemoved,
            physicalBytesRemoved = physicalBytesRemoved,
        )
    }

    override suspend fun close() {
        operationMutex.withLock {
            if (closed) return
            closed = true
            var failure: Throwable? = null
            try {
                database.close()
            } catch (closeFailure: Throwable) {
                failure = closeFailure
            }
            if (leaseCounts.isEmpty()) {
                try {
                    closeFileSystem()
                } catch (closeFailure: Throwable) {
                    failure = failure ?: closeFailure
                }
            }
            failure?.let { closeFailure -> throw closeFailure }
        }
    }

    /**
     * Chooses the entries that must be removed before [request] fits within every configured bound.
     *
     * Candidates are read in removal order a page at a time so a commit never materializes the
     * whole index. Leased generations and the committing address itself are never evicted.
     *
     * @return Victims to delete, or `null` when the commit cannot fit.
     */
    private suspend fun selectVictims(
        request: FileStoreCommitRequest,
        plan: EvictionPlan,
    ): List<FileEvictionCandidate>? {
        if (!plan.exceeds(request, limits)) return emptyList()
        val dao = database.fileEntryDao()
        val now = clock.now()
        val victims = mutableListOf<FileEvictionCandidate>()
        val considered = mutableSetOf<String>()
        var namespaceOffset = 0
        var totalOffset = 0
        while (plan.exceeds(request, limits)) {
            val namespaceOnly = plan.namespaceExceeds(request)
            val page = if (namespaceOnly) {
                dao.selectNamespaceEvictionCandidates(
                    namespace = request.address.namespace.value,
                    epochSeconds = now.epochSeconds,
                    nanoseconds = now.nanosecondsOfSecond,
                    limit = CANDIDATE_PAGE_SIZE,
                    offset = namespaceOffset,
                ).also { namespaceOffset += it.size }
            } else {
                dao.selectEvictionCandidates(
                    epochSeconds = now.epochSeconds,
                    nanoseconds = now.nanosecondsOfSecond,
                    limit = CANDIDATE_PAGE_SIZE,
                    offset = totalOffset,
                ).also { totalOffset += it.size }
            }
            if (page.isEmpty()) return null
            var evictedFromPage = false
            for (candidate in page) {
                if (!plan.exceeds(request, limits)) break
                if (!considered.add(candidate.identity())) continue
                if (candidate.matches(request.address)) continue
                val generation = candidate.generation.asGenerationOrNull() ?: continue
                if (leaseCounts.getOrElse(generation) { 0 } > 0) continue
                victims += candidate
                plan.remove(candidate, request.address.namespace.value)
                evictedFromPage = true
            }
            if (!evictedFromPage && page.size < CANDIDATE_PAGE_SIZE) return null
        }
        return victims
    }

    private suspend fun deleteEntry(address: FileStoreAddress) {
        database.fileEntryDao().delete(
            namespace = address.namespace.value,
            partition = address.partition.value,
            cacheKey = address.key,
        )
    }

    private suspend fun scheduleDelete(generation: FileGeneration) {
        if (leaseCounts.getOrElse(generation) { 0 } > 0) {
            pendingDeletes += generation
        } else {
            deleteQuietly(generation)
        }
    }

    private suspend fun deleteQuietly(generation: FileGeneration) {
        try {
            fileSystem.deleteContent(generation)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // A now-unreferenced generation is removed by recovery.
        }
    }

    private suspend fun releaseLease(generation: FileGeneration) {
        operationMutex.withLock {
            val remaining = leaseCounts.getOrElse(generation) { 0 } - 1
            if (remaining <= 0) {
                leaseCounts.remove(generation)
            } else {
                leaseCounts[generation] = remaining
            }
            if (remaining <= 0 && generation in pendingDeletes) {
                pendingDeletes -= generation
                deleteQuietly(generation)
            }
            if (closed && leaseCounts.isEmpty()) closeFileSystem()
        }
    }

    private suspend fun closeFileSystem() {
        if (fileSystemClosed) return
        fileSystem.close()
        fileSystemClosed = true
    }

    private suspend fun <T> withOpenStore(operation: suspend () -> T): T =
        operationMutex.withLock {
            check(!closed) { "File cache store is closed." }
            operation()
        }

    private inner class StoreWriteSession(
        private val generation: FileGeneration,
        private val maxEntryBytes: Long,
        private val handle: GenerationWriteHandle,
    ) : FileStoreWriteSession {
        private val mutex = Mutex()
        private var finished: StagedFile? = null
        private var aborted = false

        override val bytesWritten: Long
            get() = handle.bytesWritten

        override suspend fun write(source: ByteArray, offset: Int, byteCount: Int) {
            mutex.withLock {
                check(finished == null && !aborted) { "File write session is terminal." }
                require(offset >= 0 && byteCount >= 0 && offset <= source.size - byteCount) {
                    "Invalid source byte range."
                }
                if (byteCount.toLong() > maxEntryBytes - handle.bytesWritten) {
                    throw dev.voir.cache.core.file.store.FileEntryTooLargeException(maxEntryBytes)
                }
                handle.write(source, offset, byteCount)
            }
        }

        override suspend fun finish(): StagedFile = mutex.withLock {
            check(!aborted) { "File write session was aborted." }
            finished?.let { staged -> return@withLock staged }
            val actualSize = handle.finish()
            check(actualSize == handle.bytesWritten) { "Staged file size changed while finishing." }
            check(actualSize <= maxEntryBytes) { "Staged file exceeds its configured entry limit." }
            StagedFile(generation, actualSize).also { staged -> finished = staged }
        }

        override suspend fun abort() {
            mutex.withLock {
                check(finished == null) { "File write session was finished." }
                if (aborted) return
                aborted = true
                handle.abort()
            }
        }
    }

    private inner class GenerationLease(
        override val absolutePath: String,
        override val file: CachedFile,
        private val generation: FileGeneration,
    ) : FileCacheLease {
        private val mutex = Mutex()
        private var released = false

        override suspend fun close() {
            mutex.withLock {
                if (released) return
                released = true
                releaseLease(generation)
            }
        }
    }

    private companion object {
        /** Eviction candidates read per query while a commit makes room. */
        const val CANDIDATE_PAGE_SIZE: Int = 64
    }
}

/** Running occupancy for one commit, adjusted as victims are chosen. */
private class EvictionPlan(
    var namespaceEntries: Long,
    var namespaceBytes: Long,
    var totalEntries: Long,
    var totalBytes: Long,
) {
    fun namespaceExceeds(request: FileStoreCommitRequest): Boolean =
        namespaceEntries > request.policy.maxEntries || namespaceBytes > request.policy.maxBytes

    fun exceeds(request: FileStoreCommitRequest, limits: FileStoreLimits): Boolean =
        namespaceExceeds(request) ||
            totalEntries > limits.maxTotalEntries ||
            totalBytes > limits.maxTotalBytes

    fun remove(candidate: FileEvictionCandidate, namespace: String) {
        if (candidate.namespace == namespace) {
            namespaceEntries -= 1
            namespaceBytes -= candidate.sizeBytes
        }
        totalEntries -= 1
        totalBytes -= candidate.sizeBytes
    }
}

private fun FileEvictionCandidate.identity(): String =
    "$namespace $partition $cacheKey"

private fun FileEvictionCandidate.matches(address: FileStoreAddress): Boolean =
    namespace == address.namespace.value &&
        partition == address.partition.value &&
        cacheKey == address.key

private fun String.asGenerationOrNull(): FileGeneration? = try {
    FileGeneration(this)
} catch (_: IllegalArgumentException) {
    null
}
