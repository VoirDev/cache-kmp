package dev.voir.cache.file.internal

import dev.voir.cache.core.file.store.FileGeneration
import dev.voir.cache.file.JvmStoreLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/** Java NIO implementation constrained to one validated store-owned data directory. */
internal class JvmFileStoreFileSystem(
    private val dataDirectory: Path,
    private val fileCoroutineContext: CoroutineContext,
    private val storeLock: JvmStoreLock,
) : FileStoreFileSystem {
    init {
        Files.createDirectories(dataDirectory)
        require(!Files.isSymbolicLink(dataDirectory)) {
            "File cache data directory must not be a symbolic link."
        }
        require(Files.isDirectory(dataDirectory, LinkOption.NOFOLLOW_LINKS)) {
            "File cache data path must be a directory."
        }
    }

    override fun newGeneration(): FileGeneration = FileGeneration(
        UUID.randomUUID().toString().replace("-", "")
    )

    override suspend fun createStaging(generation: FileGeneration): GenerationWriteHandle =
        withContext(fileCoroutineContext) {
            val path = requireGenerationPath(generation, STAGING_SUFFIX)
            JvmGenerationWriteHandle(
                path = path,
                channel = FileChannel.open(
                    path,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                ),
                fileCoroutineContext = fileCoroutineContext,
            )
        }

    override suspend fun promote(generation: FileGeneration): Unit =
        withContext(fileCoroutineContext) {
            val staging = requireGenerationPath(generation, STAGING_SUFFIX)
            val content = requireGenerationPath(generation, CONTENT_SUFFIX)
            check(Files.isRegularFile(staging, LinkOption.NOFOLLOW_LINKS)) {
                "Staged generation is missing or is not a regular file."
            }
            check(!Files.exists(content, LinkOption.NOFOLLOW_LINKS)) {
                "Committed generation path already exists."
            }
            Files.move(staging, content, StandardCopyOption.ATOMIC_MOVE)
        }

    override suspend fun discardStaging(generation: FileGeneration): Unit =
        withContext(fileCoroutineContext) {
            generationPathOrNull(generation, STAGING_SUFFIX)?.let(Files::deleteIfExists)
        }

    override suspend fun stagingSize(generation: FileGeneration): Long? =
        withContext(fileCoroutineContext) {
            regularFileSize(generationPathOrNull(generation, STAGING_SUFFIX))
        }

    override suspend fun deleteContent(generation: FileGeneration): Unit =
        withContext(fileCoroutineContext) {
            generationPathOrNull(generation, CONTENT_SUFFIX)?.let(Files::deleteIfExists)
        }

    override suspend fun contentSize(generation: FileGeneration): Long? =
        withContext(fileCoroutineContext) {
            regularFileSize(generationPathOrNull(generation, CONTENT_SUFFIX))
        }

    override suspend fun contentAbsolutePath(generation: FileGeneration): String? =
        withContext(fileCoroutineContext) {
            val path = generationPathOrNull(generation, CONTENT_SUFFIX)
                ?: return@withContext null
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@withContext null
            path.toAbsolutePath().normalize().toString()
        }

    override suspend fun listOwnedFiles(): List<OwnedGenerationFile> =
        withContext(fileCoroutineContext) {
            Files.newDirectoryStream(dataDirectory).use { entries ->
                entries.mapNotNull { path -> path.toOwnedGenerationFileOrNull() }
            }
        }

    override suspend fun close(): Unit = withContext(fileCoroutineContext) {
        storeLock.close()
    }

    private fun Path.toOwnedGenerationFileOrNull(): OwnedGenerationFile? {
        val filename = fileName.toString()
        val (kind, suffix) = when {
            filename.endsWith(CONTENT_SUFFIX) -> OwnedFileKind.CONTENT to CONTENT_SUFFIX
            filename.endsWith(STAGING_SUFFIX) -> OwnedFileKind.STAGING to STAGING_SUFFIX
            else -> return null
        }
        val generationValue = filename.removeSuffix(suffix)
        if (!GENERATION_PATTERN.matches(generationValue)) return null
        val size = if (Files.isRegularFile(this, LinkOption.NOFOLLOW_LINKS)) Files.size(this) else 0L
        return OwnedGenerationFile(FileGeneration(generationValue), kind, size)
    }

    private fun requireGenerationPath(generation: FileGeneration, suffix: String): Path =
        checkNotNull(generationPathOrNull(generation, suffix)) {
            "File generation has an invalid platform representation."
        }

    private fun generationPathOrNull(generation: FileGeneration, suffix: String): Path? {
        if (!GENERATION_PATTERN.matches(generation.value)) return null
        return dataDirectory.resolve(generation.value + suffix)
    }

    private fun regularFileSize(path: Path?): Long? {
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        return Files.size(path)
    }
}

private class JvmGenerationWriteHandle(
    private val path: Path,
    private val channel: FileChannel,
    private val fileCoroutineContext: CoroutineContext,
) : GenerationWriteHandle {
    private var terminal = false
    private var acceptedBytes = 0L

    override val bytesWritten: Long
        get() = acceptedBytes

    override suspend fun write(source: ByteArray, offset: Int, byteCount: Int) {
        withContext(fileCoroutineContext) {
            check(!terminal) { "Generation write handle is terminal." }
            val buffer = ByteBuffer.wrap(source, offset, byteCount)
            while (buffer.hasRemaining()) channel.write(buffer)
            acceptedBytes += byteCount
        }
    }

    override suspend fun finish(): Long = withContext(fileCoroutineContext) {
        if (!terminal) {
            channel.force(true)
            channel.close()
            terminal = true
        }
        Files.size(path)
    }

    override suspend fun abort() {
        withContext(fileCoroutineContext) {
            if (!terminal) {
                channel.close()
                terminal = true
            }
            Files.deleteIfExists(path)
        }
    }
}

private val GENERATION_PATTERN: Regex = Regex("[0-9a-f]{32}")
private const val CONTENT_SUFFIX: String = ".entry"
private const val STAGING_SUFFIX: String = ".part"
