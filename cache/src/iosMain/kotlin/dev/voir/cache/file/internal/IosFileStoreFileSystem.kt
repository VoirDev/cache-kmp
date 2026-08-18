@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.voir.cache.file.internal

import dev.voir.cache.core.file.store.FileGeneration
import dev.voir.cache.file.IosStoreLock
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID
import platform.posix.EINTR
import platform.posix.ENOENT
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_WRONLY
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.close
import platform.posix.errno
import platform.posix.fsync
import platform.posix.lstat
import platform.posix.open
import platform.posix.rename
import platform.posix.stat
import platform.posix.unlink
import kotlin.coroutines.CoroutineContext

/** Darwin POSIX implementation constrained to one validated store-owned data directory. */
internal class IosFileStoreFileSystem(
    private val dataDirectory: String,
    private val fileCoroutineContext: CoroutineContext,
    private val storeLock: IosStoreLock,
) : FileStoreFileSystem {
    init {
        if (!NSFileManager.defaultManager.fileExistsAtPath(dataDirectory)) {
            check(
                NSFileManager.defaultManager.createDirectoryAtPath(
                    path = dataDirectory,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null,
                )
            ) { "Could not create file cache data directory." }
        }
        val status = lstatOrNull(dataDirectory)
        require(status?.isDirectory == true && !status.isSymbolicLink) {
            "File cache data path must be a non-symbolic-link directory."
        }
    }

    override fun newGeneration(): FileGeneration = FileGeneration(
        NSUUID().UUIDString.lowercase().replace("-", "")
    )

    override suspend fun createStaging(generation: FileGeneration): GenerationWriteHandle =
        withContext(fileCoroutineContext) {
            val path = requireGenerationPath(generation, STAGING_SUFFIX)
            val descriptor = open(
                path,
                O_CREAT or O_EXCL or O_WRONLY,
                OWNER_READ_WRITE_MODE,
            )
            check(descriptor >= 0) { "Could not create staged generation." }
            IosGenerationWriteHandle(path, descriptor, fileCoroutineContext)
        }

    override suspend fun promote(generation: FileGeneration): Unit =
        withContext(fileCoroutineContext) {
            val staging = requireGenerationPath(generation, STAGING_SUFFIX)
            val content = requireGenerationPath(generation, CONTENT_SUFFIX)
            check(regularFileSize(staging) != null) {
                "Staged generation is missing or is not a regular file."
            }
            check(lstatOrNull(content) == null) { "Committed generation path already exists." }
            check(rename(staging, content) == 0) { "Could not promote staged generation." }
        }

    override suspend fun discardStaging(generation: FileGeneration): Unit =
        withContext(fileCoroutineContext) {
            generationPathOrNull(generation, STAGING_SUFFIX)?.let(::deleteNoFollow)
        }

    override suspend fun stagingSize(generation: FileGeneration): Long? =
        withContext(fileCoroutineContext) {
            generationPathOrNull(generation, STAGING_SUFFIX)?.let(::regularFileSize)
        }

    override suspend fun deleteContent(generation: FileGeneration): Unit =
        withContext(fileCoroutineContext) {
            generationPathOrNull(generation, CONTENT_SUFFIX)?.let(::deleteNoFollow)
        }

    override suspend fun contentSize(generation: FileGeneration): Long? =
        withContext(fileCoroutineContext) {
            generationPathOrNull(generation, CONTENT_SUFFIX)?.let(::regularFileSize)
        }

    override suspend fun contentAbsolutePath(generation: FileGeneration): String? =
        withContext(fileCoroutineContext) {
            val path = generationPathOrNull(generation, CONTENT_SUFFIX)
                ?: return@withContext null
            if (regularFileSize(path) == null) return@withContext null
            path
        }

    override suspend fun listOwnedFiles(): List<OwnedGenerationFile> =
        withContext(fileCoroutineContext) {
            checkNotNull(
                NSFileManager.defaultManager.contentsOfDirectoryAtPath(
                    dataDirectory,
                    error = null,
                )
            ) { "Could not list file cache data directory." }
                .mapNotNull { filename ->
                    (filename as? String)?.toOwnedGenerationFileOrNull()
                }
        }

    override suspend fun close(): Unit = withContext(fileCoroutineContext) {
        storeLock.close()
    }

    private fun String.toOwnedGenerationFileOrNull(): OwnedGenerationFile? {
        val (kind, suffix) = when {
            endsWith(CONTENT_SUFFIX) -> OwnedFileKind.CONTENT to CONTENT_SUFFIX
            endsWith(STAGING_SUFFIX) -> OwnedFileKind.STAGING to STAGING_SUFFIX
            else -> return null
        }
        val generationValue = removeSuffix(suffix)
        if (!GENERATION_PATTERN.matches(generationValue)) return null
        return OwnedGenerationFile(
            generation = FileGeneration(generationValue),
            kind = kind,
            sizeBytes = regularFileSize("$dataDirectory/$this") ?: 0L,
        )
    }

    private fun requireGenerationPath(generation: FileGeneration, suffix: String): String =
        checkNotNull(generationPathOrNull(generation, suffix)) {
            "File generation has an invalid platform representation."
        }

    private fun generationPathOrNull(generation: FileGeneration, suffix: String): String? {
        if (!GENERATION_PATTERN.matches(generation.value)) return null
        return "$dataDirectory/${generation.value}$suffix"
    }
}

private class IosGenerationWriteHandle(
    private val path: String,
    private val descriptor: Int,
    private val fileCoroutineContext: CoroutineContext,
) : GenerationWriteHandle {
    private var terminal = false
    private var acceptedBytes = 0L

    override val bytesWritten: Long
        get() = acceptedBytes

    override suspend fun write(source: ByteArray, offset: Int, byteCount: Int) {
        withContext(fileCoroutineContext) {
            check(!terminal) { "Generation write handle is terminal." }
            if (byteCount == 0) return@withContext
            var writtenBytes = 0
            source.usePinned { pinned ->
                while (writtenBytes < byteCount) {
                    val result = platform.posix.write(
                        descriptor,
                        pinned.addressOf(offset + writtenBytes),
                        (byteCount - writtenBytes).convert(),
                    )
                    if (result < 0 && errno == EINTR) continue
                    check(result > 0) { "Could not write staged generation." }
                    writtenBytes += result.toInt()
                }
            }
            acceptedBytes += byteCount
        }
    }

    override suspend fun finish(): Long = withContext(fileCoroutineContext) {
        if (!terminal) {
            check(fsync(descriptor) == 0) { "Could not synchronize staged generation." }
            check(close(descriptor) == 0) { "Could not close staged generation." }
            terminal = true
        }
        checkNotNull(regularFileSize(path)) { "Staged generation is not a regular file." }
    }

    override suspend fun abort() {
        withContext(fileCoroutineContext) {
            if (!terminal) {
                close(descriptor)
                terminal = true
            }
            deleteNoFollow(path)
        }
    }
}

private fun lstatOrNull(path: String): FileStatus? = memScoped {
    val status = alloc<stat>()
    if (lstat(path, status.ptr) != 0) {
        if (errno == ENOENT) return@memScoped null
        error("Could not inspect file cache generation.")
    }
    FileStatus(status.st_mode.toInt(), status.st_size)
}

private fun regularFileSize(path: String): Long? {
    val status = lstatOrNull(path) ?: return null
    return if (status.isRegularFile && !status.isSymbolicLink) status.sizeBytes else null
}

private fun deleteNoFollow(path: String) {
    if (unlink(path) != 0 && errno != ENOENT) error("Could not delete file cache generation.")
}

private data class FileStatus(val mode: Int, val sizeBytes: Long) {
    val isDirectory: Boolean get() = mode and S_IFMT == platform.posix.S_IFDIR
    val isRegularFile: Boolean get() = mode and S_IFMT == S_IFREG
    val isSymbolicLink: Boolean get() = mode and S_IFMT == S_IFLNK
}

private const val OWNER_READ_WRITE_MODE: Int = 384 // 0600
private val GENERATION_PATTERN: Regex = Regex("[0-9a-f]{32}")
private const val CONTENT_SUFFIX: String = ".entry"
private const val STAGING_SUFFIX: String = ".part"
