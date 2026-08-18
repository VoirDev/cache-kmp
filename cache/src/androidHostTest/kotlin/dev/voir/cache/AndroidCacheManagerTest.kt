package dev.voir.cache

import android.content.Context
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidCacheManagerTest {
    @Test
    fun `manager creates owns and reopens both Android stores`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("cache-kmp.db")
        File(context.cacheDir, "cache-kmp").deleteRecursively()
        try {
            createTestManager(context).also { manager ->
                assertTrue(context.getDatabasePath("cache-kmp.db").exists())
                assertTrue(File(context.cacheDir, "cache-kmp/index/cache-file.db").exists())
                manager.clearAll()
                manager.close()
            }

            createTestManager(context).close()
        } finally {
            context.deleteDatabase("cache-kmp.db")
            File(context.cacheDir, "cache-kmp").deleteRecursively()
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.createTestManager(
        context: Context,
    ): CacheManager = createAndroidCacheManager(
        context = context,
        scope = this,
        options = CacheManagerOptions(),
        persistentDriver = AndroidSQLiteDriver(),
        fileDriver = AndroidSQLiteDriver(),
    )
}
