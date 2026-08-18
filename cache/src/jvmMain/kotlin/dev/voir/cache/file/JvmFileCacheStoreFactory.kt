package dev.voir.cache.file

import androidx.room3.Room
import dev.voir.cache.core.file.FileStoreLimits
import dev.voir.cache.core.file.store.FileCacheStore
import dev.voir.cache.file.internal.FileIndexDatabase
import dev.voir.cache.file.internal.JvmFileStoreFileSystem
import dev.voir.cache.file.internal.buildRoomFileCacheStore
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * Creates a JVM file cache in a dedicated absolute [rootDirectory].
 *
 * The store owns `data` and `index` children inside the root. Startup recovery completes before
 * this function returns, so missing rows, orphan generations, and interrupted staging files cannot
 * hydrate through the returned store.
 */
internal suspend fun createJvmFileCacheStore(
    rootDirectory: Path,
    limits: FileStoreLimits,
    configuration: FileCacheStoreConfiguration = FileCacheStoreConfiguration(),
): FileCacheStore {
    val root = validateAndPrepareRoot(rootDirectory)
    val indexDirectory = root.resolve(INDEX_DIRECTORY)
    Files.createDirectories(indexDirectory)
    requireOwnedDirectory(indexDirectory)
    val databasePath = indexDirectory.resolve(INDEX_FILE)
    require(!Files.isSymbolicLink(databasePath)) {
        "File cache index path must not be a symbolic link."
    }
    val storeLock = acquireStoreLock(root)
    val fileSystem = try {
        JvmFileStoreFileSystem(
            dataDirectory = root.resolve(DATA_DIRECTORY),
            fileCoroutineContext = configuration.fileCoroutineContext,
            storeLock = storeLock,
        )
    } catch (failure: Throwable) {
        storeLock.close()
        throw failure
    }
    val store = try {
        buildRoomFileCacheStore(
            builder = Room.databaseBuilder<FileIndexDatabase>(name = databasePath.toString()),
            fileSystem = fileSystem,
            limits = limits,
            configuration = configuration,
        )
    } catch (failure: Throwable) {
        fileSystem.close()
        throw failure
    }
    return try {
        store.recover()
        store
    } catch (failure: Throwable) {
        try {
            store.close()
        } catch (_: Throwable) {
            // Preserve the startup recovery failure.
        }
        throw failure
    }
}

private fun validateAndPrepareRoot(rootDirectory: Path): Path {
    require(rootDirectory.isAbsolute) { "File cache root directory must be absolute." }
    val root = rootDirectory.normalize()
    require(root.parent != null && root.fileName != null) {
        "File cache root directory must not be a filesystem root."
    }
    if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) requireOwnedDirectory(root)
    Files.createDirectories(root)
    requireOwnedDirectory(root)
    return root
}

private fun requireOwnedDirectory(directory: Path) {
    require(!Files.isSymbolicLink(directory)) { "File cache directories must not be symbolic links." }
    require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        "File cache path must be a directory."
    }
}

private fun acquireStoreLock(root: Path): JvmStoreLock {
    val lockPath = root.resolve(LOCK_FILE)
    require(!Files.isSymbolicLink(lockPath)) { "File cache lock path must not be a symbolic link." }
    val channel = FileChannel.open(
        lockPath,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
    )
    val lock = try {
        channel.tryLock()
    } catch (_: OverlappingFileLockException) {
        null
    }
    if (lock == null) {
        channel.close()
        error("File cache root directory is already open by another store.")
    }
    return JvmStoreLock(channel, lock)
}

internal class JvmStoreLock(
    private val channel: FileChannel,
    private val lock: FileLock,
) {
    private var closed = false

    fun close() {
        if (closed) return
        closed = true
        lock.release()
        channel.close()
    }
}

private const val DATA_DIRECTORY: String = "data"
private const val INDEX_DIRECTORY: String = "index"
private const val INDEX_FILE: String = "cache-file.db"
private const val LOCK_FILE: String = ".cache-file.lock"
