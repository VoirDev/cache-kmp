package dev.voir.cache.room

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.voir.cache.core.persistence.ExperimentalCacheStoreApi
import dev.voir.cache.core.persistence.PersistentCacheStore
import dev.voir.cache.room.internal.CacheDatabase
import dev.voir.cache.room.internal.buildRoomPersistentCacheStore

/**
 * Creates an Android Room persistent cache store in the application's database directory.
 *
 * @param context Context whose application context owns the database.
 * @param databaseName Non-blank application-selected database file name.
 * @param configuration Platform-independent Room execution settings.
 * @return Persistent cache store backed by the selected database.
 */
@ExperimentalCacheStoreApi
public fun createAndroidRoomPersistentCacheStore(
    context: Context,
    databaseName: String,
    configuration: RoomCacheStoreConfiguration = RoomCacheStoreConfiguration(),
): PersistentCacheStore = createAndroidRoomPersistentCacheStore(
    context = context,
    databaseName = databaseName,
    configuration = configuration,
    driver = BundledSQLiteDriver(),
)

/**
 * Creates an Android store with an explicit driver for host verification.
 *
 * Production callers use the public factory and its bundled driver. Android host tests use the
 * framework driver because host JVMs cannot load native libraries packaged for Android ABIs.
 *
 * @param context Context whose application context owns the database.
 * @param databaseName Non-blank application-selected database file name.
 * @param configuration Platform-independent Room execution settings.
 * @param driver SQLite driver used by the database.
 * @return Persistent cache store backed by the selected database.
 */
@OptIn(ExperimentalCacheStoreApi::class)
internal fun createAndroidRoomPersistentCacheStore(
    context: Context,
    databaseName: String,
    configuration: RoomCacheStoreConfiguration,
    driver: SQLiteDriver,
): PersistentCacheStore {
    require(databaseName.isNotBlank()) { "Room cache database name must not be blank." }
    val applicationContext = context.applicationContext ?: context
    val databasePath = applicationContext.getDatabasePath(databaseName).absolutePath
    return buildRoomPersistentCacheStore(
        builder = Room.databaseBuilder<CacheDatabase>(
            context = applicationContext,
            name = databasePath,
        ),
        configuration = configuration,
        driver = driver,
    )
}
