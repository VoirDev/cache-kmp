@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.voir.cache

import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertTrue

class IosCacheManagerTest {
    @Test
    fun `manager creates owns and reopens both iOS stores`() = runTest {
        val root = "${NSTemporaryDirectory()}cache-kmp-manager-${NSUUID.UUID().UUIDString}"
        try {
            createIosCacheManager(root, this, CacheManagerOptions()).also { manager ->
                assertTrue(NSFileManager.defaultManager.fileExistsAtPath("$root/cache.db"))
                assertTrue(
                    NSFileManager.defaultManager.fileExistsAtPath(
                        "$root/files/index/cache-file.db"
                    )
                )
                manager.clearAll()
                manager.close()
            }

            createIosCacheManager(root, this, CacheManagerOptions()).close()
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(root, error = null)
        }
    }
}
