package dev.voir.cache.file.internal

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.os.Build
import dev.voir.cache.core.file.store.FileGeneration
import dev.voir.cache.file.AndroidStoreLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/** Android filesystem implementation using POSIX rename and no-follow metadata checks. */
internal class AndroidFileStoreFileSystem(
    private val dataDirectory: File,
    private val fileCoroutineContext: CoroutineContext,
    private val storeLock: AndroidStoreLock,
) : FileStoreFileSystem {
    init {
        if (!dataDirectory.exists()) check(dataDirectory.mkdirs()) {
            "Could not create file cache data directory."
        }
        val status = Os.lstat(dataDirectory.absolutePath)
        require(OsConstants.S_ISDIR(status.st_mode) && !OsConstants.S_ISLNK(status.st_mode)) {
            "File cache data path must be a non-symbolic-link directory."
        }
    }

    override fun newGeneration(): FileGeneration = FileGeneration(
        UUID.randomUUID().toString().replace("-", "")
    )

    override suspend fun createStaging(generation: FileGeneration): GenerationWriteHandle =
        withContext(fileCoroutineContext) {
            val file = requireGenerationFile(generation, STAGING_SUFFIX)
            val descriptor = Os.open(
                file.absolutePath,
                OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_WRONLY,
                OWNER_READ_WRITE_MODE,
            )
            val output = try {
                FileOutputStream(descriptor)
            } catch (failure: Throwable) {
                Os.close(descriptor)
                deleteNoFollow(file)
                throw failure
            }
            AndroidGenerationWriteHandle(file, output, fileCoroutineContext)
        }

    override suspend fun promote(generation: FileGeneration): Unit =
        withContext(fileCoroutineContext) {
            val staging = requireGenerationFile(generation, STAGING_SUFFIX)
            val content = requireGenerationFile(generation, CONTENT_SUFFIX)
            check(regularFileSize(staging) != null) {
                "Staged generation is missing or is not a regular file."
            }
            check(lstatOrNull(content) == null) { "Committed generation path already exists." }
            if (Build.FINGERPRINT == "robolectric") {
                check(staging.renameTo(content)) { "Could not promote staged generation." }
            } else {
                Os.rename(staging.absolutePath, content.absolutePath)
            }
        }

    override suspend fun discardStaging(generation: FileGeneration): Unit =
        withContext(fileCoroutineContext) {
            generationFileOrNull(generation, STAGING_SUFFIX)?.let(::deleteNoFollow)
        }

    override suspend fun stagingSize(generation: FileGeneration): Long? =
        withContext(fileCoroutineContext) {
            generationFileOrNull(generation, STAGING_SUFFIX)?.let(::regularFileSize)
        }

    override suspend fun deleteContent(generation: FileGeneration): Unit =
        withContext(fileCoroutineContext) {
            generationFileOrNull(generation, CONTENT_SUFFIX)?.let(::deleteNoFollow)
        }

    override suspend fun contentSize(generation: FileGeneration): Long? =
        withContext(fileCoroutineContext) {
            generationFileOrNull(generation, CONTENT_SUFFIX)?.let(::regularFileSize)
        }

    override suspend fun contentAbsolutePath(generation: FileGeneration): String? =
        withContext(fileCoroutineContext) {
            val file = generationFileOrNull(generation, CONTENT_SUFFIX)
                ?: return@withContext null
            if (regularFileSize(file) == null) return@withContext null
            file.absolutePath
        }

    override suspend fun listOwnedFiles(): List<OwnedGenerationFile> =
        withContext(fileCoroutineContext) {
            checkNotNull(dataDirectory.listFiles()) {
                "Could not list file cache data directory."
            }.mapNotNull { file ->
                file.toOwnedGenerationFileOrNull()
            }
        }

    override suspend fun close(): Unit = withContext(fileCoroutineContext) {
        storeLock.close()
    }

    private fun File.toOwnedGenerationFileOrNull(): OwnedGenerationFile? {
        val (kind, suffix) = when {
            name.endsWith(CONTENT_SUFFIX) -> OwnedFileKind.CONTENT to CONTENT_SUFFIX
            name.endsWith(STAGING_SUFFIX) -> OwnedFileKind.STAGING to STAGING_SUFFIX
            else -> return null
        }
        val generationValue = name.removeSuffix(suffix)
        if (!GENERATION_PATTERN.matches(generationValue)) return null
        return OwnedGenerationFile(
            generation = FileGeneration(generationValue),
            kind = kind,
            sizeBytes = regularFileSize(this) ?: 0L,
        )
    }

    private fun requireGenerationFile(generation: FileGeneration, suffix: String): File =
        checkNotNull(generationFileOrNull(generation, suffix)) {
            "File generation has an invalid platform representation."
        }

    private fun generationFileOrNull(generation: FileGeneration, suffix: String): File? {
        if (!GENERATION_PATTERN.matches(generation.value)) return null
        return File(dataDirectory, generation.value + suffix)
    }
}

private class AndroidGenerationWriteHandle(
    private val file: File,
    private val output: FileOutputStream,
    private val fileCoroutineContext: CoroutineContext,
) : GenerationWriteHandle {
    private var terminal = false
    private var acceptedBytes = 0L

    override val bytesWritten: Long
        get() = acceptedBytes

    override suspend fun write(source: ByteArray, offset: Int, byteCount: Int) {
        withContext(fileCoroutineContext) {
            check(!terminal) { "Generation write handle is terminal." }
            output.write(source, offset, byteCount)
            acceptedBytes += byteCount
        }
    }

    override suspend fun finish(): Long = withContext(fileCoroutineContext) {
        if (!terminal) {
            output.fd.sync()
            output.close()
            terminal = true
        }
        checkNotNull(regularFileSize(file)) { "Staged generation is not a regular file." }
    }

    override suspend fun abort() {
        withContext(fileCoroutineContext) {
            if (!terminal) {
                output.close()
                terminal = true
            }
            deleteNoFollow(file)
        }
    }
}

private fun lstatOrNull(file: File): android.system.StructStat? {
    val directoryEntryExists = file.parentFile?.list()?.any { name -> name == file.name } == true
    if (!file.exists() && !directoryEntryExists) return null
    return try {
        Os.lstat(file.absolutePath)
    } catch (failure: ErrnoException) {
        if (failure.errno == OsConstants.ENOENT) null else throw failure
    }
}

private fun regularFileSize(file: File): Long? {
    if (Build.FINGERPRINT == "robolectric") return if (file.isFile) file.length() else null
    val status = lstatOrNull(file) ?: return null
    if (OsConstants.S_ISLNK(status.st_mode)) return null
    if (!OsConstants.S_ISREG(status.st_mode)) return null
    return status.st_size
}

private fun deleteNoFollow(file: File) {
    if (Build.FINGERPRINT == "robolectric") {
        if (file.exists()) check(file.delete()) { "Could not delete file cache generation." }
        return
    }
    try {
        Os.remove(file.absolutePath)
    } catch (failure: ErrnoException) {
        if (failure.errno != OsConstants.ENOENT) throw failure
    }
}

private val GENERATION_PATTERN: Regex = Regex("[0-9a-f]{32}")
private const val OWNER_READ_WRITE_MODE: Int = 384 // 0600
private const val CONTENT_SUFFIX: String = ".entry"
private const val STAGING_SUFFIX: String = ".part"
