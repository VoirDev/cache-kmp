package dev.voir.cache.core.file.store

import dev.voir.cache.core.file.FileCachePolicy
import dev.voir.cache.core.file.FileCacheRejectionReason
import dev.voir.cache.core.file.FileContentMetadata
import dev.voir.cache.core.file.FileWriteSink
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.model.CacheMetadata
import kotlin.time.Instant

/** Exact storage address for one cached file. */
internal data class FileStoreAddress(
    val namespace: CacheNamespace,
    val partition: CachePartition,
    val key: String,
) {
    init {
        require(key.isNotBlank()) { "File-store address key must not be blank." }
    }
}

/** Opaque immutable generation identifier assigned by a file store. */
internal data class FileGeneration(val value: String) {
    init {
        require(value.isNotBlank()) { "File generation must not be blank." }
    }
}

/** Durable logical metadata for one committed physical generation. */
internal data class StoredFileRecord(
    val address: FileStoreAddress,
    val generation: FileGeneration,
    val sizeBytes: Long,
    val content: FileContentMetadata,
    val metadata: CacheMetadata,
    val writtenAt: Instant,
    val lastAccessedAt: Instant,
) {
    init {
        require(sizeBytes >= 0) { "Stored file sizeBytes must not be negative." }
    }
}

/** Opaque completed staging generation not yet visible as committed state. */
internal data class StagedFile(
    val generation: FileGeneration,
    val sizeBytes: Long,
) {
    init {
        require(sizeBytes >= 0) { "Staged file sizeBytes must not be negative." }
    }
}

/**
 * Store-owned write session enforcing one hard entry-byte bound.
 *
 * `finish` and `abort` are mutually terminal and idempotent for repeated calls of the same method.
 * Writing after either terminal operation must fail.
 */
internal interface FileStoreWriteSession : FileWriteSink {
    suspend fun finish(): StagedFile
    suspend fun abort()
}

/** Store signal thrown by [FileWriteSink.write] before accepting bytes beyond the entry limit. */
internal class FileEntryTooLargeException(
    val maxEntryBytes: Long,
) : IllegalStateException("File content exceeds maxEntryBytes=$maxEntryBytes.") {
    init {
        require(maxEntryBytes > 0) { "File cache maxEntryBytes must be greater than zero." }
    }
}

/** Atomic promotion request for one successfully staged generation. */
internal data class FileStoreCommitRequest(
    val address: FileStoreAddress,
    val staged: StagedFile,
    val content: FileContentMetadata,
    val metadata: CacheMetadata,
    val policy: FileCachePolicy,
    val writtenAt: Instant,
) {
    init {
        require(staged.sizeBytes <= policy.maxEntryBytes) {
            "Staged file sizeBytes must not exceed policy maxEntryBytes."
        }
    }
}

/** Outcome of consuming one [FileStoreCommitRequest]. */
internal sealed interface FileStoreCommitResult

/** Successfully committed record. */
internal data class FileStoreCommitted(
    val record: StoredFileRecord,
) : FileStoreCommitResult

/** Commit rejected while leaving every previously committed generation unchanged. */
internal data class FileStoreRejected(
    val reason: FileCacheRejectionReason,
) : FileStoreCommitResult

/** Host-derived expiration cutoff for one maintenance pass. */
internal data class FileStorePruneRequest(
    val expiredBefore: Instant? = null,
)

/** Exact logical content removed by one maintenance pass. */
internal data class FileStorePruneResult(
    val entriesRemoved: Long,
    val contentBytesRemoved: Long,
) {
    init {
        require(entriesRemoved >= 0) { "Pruned file entry count must not be negative." }
        require(contentBytesRemoved >= 0) { "Pruned file byte count must not be negative." }
    }
}

/** Exact cleanup aggregates returned by startup reconciliation. */
internal data class FileStoreRecoveryResult(
    val recordsRemoved: Long,
    val orphanFilesRemoved: Long,
    val stagingFilesRemoved: Long,
    val physicalBytesRemoved: Long,
) {
    init {
        require(recordsRemoved >= 0) { "Recovered record count must not be negative." }
        require(orphanFilesRemoved >= 0) { "Recovered orphan count must not be negative." }
        require(stagingFilesRemoved >= 0) { "Recovered staging count must not be negative." }
        require(physicalBytesRemoved >= 0) { "Recovered byte count must not be negative." }
    }
}
