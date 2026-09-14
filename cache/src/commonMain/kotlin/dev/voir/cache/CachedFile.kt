package dev.voir.cache

import dev.voir.cache.core.file.FileCacheLease
import dev.voir.cache.core.file.FileWriteSink

/** Leased cached file whose [path] remains valid until [close] completes. */
public class CachedFile internal constructor(
    private val lease: FileCacheLease,
) {
    public val path: String
        get() = lease.absolutePath

    public val sizeBytes: Long
        get() = lease.file.sizeBytes

    public val mediaType: String?
        get() = lease.file.content.mediaType

    /** Releases the physical file generation. This operation is idempotent. */
    public suspend fun close(): Unit = lease.close()
}

/** Cache-controlled streaming destination supplied to a file loader. */
public interface CacheFileSink {
    public val bytesWritten: Long

    public suspend fun write(source: ByteArray, offset: Int, byteCount: Int)

    public suspend fun write(source: ByteArray): Unit = write(source, 0, source.size)
}

/** Stable reasons a file request can fail after source loading. */
public enum class CacheFileFailureReason {
    ENTRY_TOO_LARGE,
    CAPACITY_UNAVAILABLE,
    STORAGE_UNAVAILABLE,
    SUPERSEDED,
}

/** File-cache infrastructure or policy failure. Loader exceptions are rethrown directly. */
public class CacheFileException public constructor(
    public val reason: CacheFileFailureReason,
) : IllegalStateException("File cache request failed: $reason")

internal class CacheFileSinkAdapter(
    private val sink: FileWriteSink,
) : CacheFileSink {
    override val bytesWritten: Long
        get() = sink.bytesWritten

    override suspend fun write(source: ByteArray, offset: Int, byteCount: Int) {
        sink.write(source, offset, byteCount)
    }
}
