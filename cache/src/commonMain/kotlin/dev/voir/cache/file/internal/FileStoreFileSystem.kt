package dev.voir.cache.file.internal

import dev.voir.cache.core.file.store.FileGeneration

/** Minimal no-follow filesystem boundary shared by platform-neutral store coordination. */
internal interface FileStoreFileSystem {
    fun newGeneration(): FileGeneration
    suspend fun createStaging(generation: FileGeneration): GenerationWriteHandle
    suspend fun promote(generation: FileGeneration)
    suspend fun discardStaging(generation: FileGeneration)
    suspend fun stagingSize(generation: FileGeneration): Long?
    suspend fun deleteContent(generation: FileGeneration)
    suspend fun contentSize(generation: FileGeneration): Long?
    suspend fun contentAbsolutePath(generation: FileGeneration): String?
    suspend fun listOwnedFiles(): List<OwnedGenerationFile>
    suspend fun close()
}

internal interface GenerationWriteHandle {
    val bytesWritten: Long
    suspend fun write(source: ByteArray, offset: Int, byteCount: Int)
    suspend fun finish(): Long
    suspend fun abort()
}

internal data class OwnedGenerationFile(
    val generation: FileGeneration,
    val kind: OwnedFileKind,
    val sizeBytes: Long,
)

internal enum class OwnedFileKind {
    CONTENT,
    STAGING,
}
