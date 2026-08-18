@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.voir.cache.file

import androidx.room3.Room
import dev.voir.cache.core.file.FileStoreLimits
import dev.voir.cache.core.file.store.FileCacheStore
import dev.voir.cache.file.internal.FileIndexDatabase
import dev.voir.cache.file.internal.IosFileStoreFileSystem
import dev.voir.cache.file.internal.buildRoomFileCacheStore
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import platform.Foundation.NSFileManager
import platform.posix.LOCK_EX
import platform.posix.LOCK_NB
import platform.posix.LOCK_UN
import platform.posix.ENOENT
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.S_IFDIR
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.close
import platform.posix.errno
import platform.posix.flock
import platform.posix.lstat
import platform.posix.open
import platform.posix.stat

/**
 * Creates an iOS file cache in a dedicated absolute [rootDirectory].
 *
 * The store owns `data` and `index` children inside the root. Startup recovery completes before
 * this function returns. The caller should choose an app caches or application-support location.
 */
internal suspend fun createIosFileCacheStore(
    rootDirectory: String,
    limits: FileStoreLimits,
    configuration: FileCacheStoreConfiguration = FileCacheStoreConfiguration(),
): FileCacheStore {
    val root = validateAndPrepareRoot(rootDirectory)
    val indexDirectory = "$root/$INDEX_DIRECTORY"
    prepareDirectory(indexDirectory)
    val databasePath = "$indexDirectory/$INDEX_FILE"
    requireNotSymbolicLink(databasePath, "File cache index path")
    val storeLock = acquireStoreLock(root)
    val fileSystem = try {
        IosFileStoreFileSystem(
            dataDirectory = "$root/$DATA_DIRECTORY",
            fileCoroutineContext = configuration.fileCoroutineContext,
            storeLock = storeLock,
        )
    } catch (failure: Throwable) {
        storeLock.close()
        throw failure
    }
    val store = try {
        buildRoomFileCacheStore(
            builder = Room.databaseBuilder<FileIndexDatabase>(name = databasePath),
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

private fun validateAndPrepareRoot(rootDirectory: String): String {
    require(rootDirectory.startsWith('/')) { "File cache root directory must be absolute." }
    val components = rootDirectory.split('/').filter(String::isNotEmpty)
    require(components.isNotEmpty()) { "File cache root directory must not be a filesystem root." }
    require(components.none { it == "." || it == ".." }) {
        "File cache root directory must not contain dot path components."
    }
    val root = "/${components.joinToString("/")}"
    prepareDirectory(root)
    return root
}

private fun prepareDirectory(path: String) {
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) {
        check(
            NSFileManager.defaultManager.createDirectoryAtPath(
                path = path,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
        ) { "Could not create file cache directory." }
    }
    val status = lstatOrNull(path) ?: error("File cache directory is missing.")
    require(status.mode and S_IFMT == S_IFDIR) { "File cache path must be a directory." }
    require(status.mode and S_IFMT != S_IFLNK) {
        "File cache directories must not be symbolic links."
    }
}

private fun requireNotSymbolicLink(path: String, label: String) {
    val status = lstatOrNull(path) ?: return
    require(status.mode and S_IFMT != S_IFLNK) { "$label must not be a symbolic link." }
}

private fun acquireStoreLock(root: String): IosStoreLock {
    val lockPath = "$root/$LOCK_FILE"
    requireNotSymbolicLink(lockPath, "File cache lock path")
    val descriptor = open(lockPath, O_CREAT or O_RDWR, OWNER_READ_WRITE_MODE)
    check(descriptor >= 0) { "Could not open file cache lock." }
    if (flock(descriptor, LOCK_EX or LOCK_NB) != 0) {
        close(descriptor)
        error("File cache root directory is already open by another store.")
    }
    return IosStoreLock(descriptor)
}

internal class IosStoreLock(private val descriptor: Int) {
    private var closed = false

    fun close() {
        if (closed) return
        closed = true
        flock(descriptor, LOCK_UN)
        close(descriptor)
    }
}

private fun lstatOrNull(path: String): FileStatus? = kotlinx.cinterop.memScoped {
    val status = alloc<stat>()
    if (lstat(path, status.ptr) != 0) {
        if (errno == ENOENT) return@memScoped null
        error("Could not inspect file cache path.")
    }
    FileStatus(status.st_mode.toInt())
}

private data class FileStatus(val mode: Int)

private const val OWNER_READ_WRITE_MODE: Int = 384 // 0600
private const val DATA_DIRECTORY: String = "data"
private const val INDEX_DIRECTORY: String = "index"
private const val INDEX_FILE: String = "cache-file.db"
private const val LOCK_FILE: String = ".cache-file.lock"
