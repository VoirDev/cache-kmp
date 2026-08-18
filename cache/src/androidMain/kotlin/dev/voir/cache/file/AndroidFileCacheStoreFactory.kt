package dev.voir.cache.file

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import androidx.room3.Room
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.voir.cache.core.file.FileStoreLimits
import dev.voir.cache.core.file.store.FileCacheStore
import dev.voir.cache.file.internal.AndroidFileStoreFileSystem
import dev.voir.cache.file.internal.FileIndexDatabase
import dev.voir.cache.file.internal.buildRoomFileCacheStore
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/** Creates an Android file cache under the application cache directory. */
internal suspend fun createAndroidFileCacheStore(
    context: Context,
    directoryName: String,
    limits: FileStoreLimits,
    configuration: FileCacheStoreConfiguration = FileCacheStoreConfiguration(),
): FileCacheStore = createAndroidFileCacheStore(
    context = context,
    directoryName = directoryName,
    limits = limits,
    configuration = configuration,
    driver = BundledSQLiteDriver(),
)

internal suspend fun createAndroidFileCacheStore(
    context: Context,
    directoryName: String,
    limits: FileStoreLimits,
    configuration: FileCacheStoreConfiguration,
    driver: SQLiteDriver,
): FileCacheStore {
    requireValidDirectoryName(directoryName)
    val applicationContext = context.applicationContext ?: context
    val root = File(applicationContext.cacheDir, directoryName)
    prepareDirectory(root)
    val indexDirectory = File(root, INDEX_DIRECTORY)
    prepareDirectory(indexDirectory)
    val databaseFile = File(indexDirectory, INDEX_FILE)
    requireNotSymbolicLink(databaseFile, "File cache index path")
    val storeLock = acquireStoreLock(root)
    val fileSystem = try {
        AndroidFileStoreFileSystem(
            dataDirectory = File(root, DATA_DIRECTORY),
            fileCoroutineContext = configuration.fileCoroutineContext,
            storeLock = storeLock,
        )
    } catch (failure: Throwable) {
        storeLock.close()
        throw failure
    }
    val store = try {
        buildRoomFileCacheStore(
            builder = Room.databaseBuilder<FileIndexDatabase>(
                context = applicationContext,
                name = databaseFile.absolutePath,
            ),
            fileSystem = fileSystem,
            limits = limits,
            configuration = configuration,
            driver = driver,
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

private fun requireValidDirectoryName(directoryName: String) {
    require(
        directoryName.isNotBlank() &&
            directoryName != "." &&
            directoryName != ".." &&
            '/' !in directoryName &&
            '\\' !in directoryName
    ) { "Android file cache directoryName must be one non-blank path component." }
}

private fun prepareDirectory(directory: File) {
    if (!directory.exists()) check(directory.mkdirs()) { "Could not create file cache directory." }
    val status = Os.lstat(directory.absolutePath)
    require(OsConstants.S_ISDIR(status.st_mode)) { "File cache path must be a directory." }
    require(!OsConstants.S_ISLNK(status.st_mode)) {
        "File cache directories must not be symbolic links."
    }
}

private fun requireNotSymbolicLink(file: File, label: String) {
    val directoryEntryExists = file.parentFile?.list()?.any { name -> name == file.name } == true
    if (!file.exists() && !directoryEntryExists) return
    val status = try {
        Os.lstat(file.absolutePath)
    } catch (failure: ErrnoException) {
        if (failure.errno == OsConstants.ENOENT) return else throw failure
    }
    require(!OsConstants.S_ISLNK(status.st_mode)) { "$label must not be a symbolic link." }
}

private fun acquireStoreLock(root: File): AndroidStoreLock {
    val lockFile = File(root, LOCK_FILE)
    requireNotSymbolicLink(lockFile, "File cache lock path")
    val randomAccessFile = RandomAccessFile(lockFile, "rw")
    val channel = randomAccessFile.channel
    val lock = try {
        channel.tryLock()
    } catch (_: OverlappingFileLockException) {
        null
    }
    if (lock == null) {
        channel.close()
        randomAccessFile.close()
        error("File cache root directory is already open by another store.")
    }
    return AndroidStoreLock(randomAccessFile, channel, lock)
}

internal class AndroidStoreLock(
    private val randomAccessFile: RandomAccessFile,
    private val channel: FileChannel,
    private val lock: FileLock,
) {
    private var closed = false

    fun close() {
        if (closed) return
        closed = true
        lock.release()
        channel.close()
        randomAccessFile.close()
    }
}

private const val DATA_DIRECTORY: String = "data"
private const val INDEX_DIRECTORY: String = "index"
private const val INDEX_FILE: String = "cache-file.db"
private const val LOCK_FILE: String = ".cache-file.lock"
