package dev.voir.cache.core.file

/** Small non-sensitive metadata stored beside one file generation. */
internal data class FileContentMetadata(
    val mediaType: String? = null,
) {
    init {
        require(mediaType == null || mediaType.isNotBlank()) {
            "File cache mediaType must be null or non-blank."
        }
        require(mediaType == null || mediaType.length <= MAX_MEDIA_TYPE_LENGTH) {
            "File cache mediaType exceeds the maximum supported length."
        }
    }
}

/** Sanitized descriptor for one logically committed cached file. */
internal data class CachedFile(
    val sizeBytes: Long,
    val content: FileContentMetadata = FileContentMetadata(),
) {
    init {
        require(sizeBytes >= 0) { "Cached file sizeBytes must not be negative." }
    }
}

/** Cache-controlled streaming destination valid only for one source invocation. */
internal interface FileWriteSink {
    /** Number of bytes accepted by this sink so far. */
    val bytesWritten: Long

    /** Writes [byteCount] bytes from [source] starting at [offset]. */
    suspend fun write(source: ByteArray, offset: Int, byteCount: Int)

    /** Writes all bytes in [source]. */
    suspend fun write(source: ByteArray): Unit = write(source, 0, source.size)
}

/**
 * Active pin for one immutable physical file generation.
 *
 * [absolutePath] is valid only until [close] completes. Closing is suspending because releasing the
 * final pin may perform a deferred physical deletion. Implementations must make it idempotent.
 */
internal interface FileCacheLease {
    val absolutePath: String
    val file: CachedFile
    suspend fun close()
}

internal const val MAX_MEDIA_TYPE_LENGTH: Int = 255
