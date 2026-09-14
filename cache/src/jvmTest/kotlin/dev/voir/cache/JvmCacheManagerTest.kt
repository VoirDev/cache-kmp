package dev.voir.cache

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmCacheManagerTest {
    @Test
    fun `manager creates owns and reopens both JVM stores`() = runTest {
        val root = createTempDirectory("cache-kmp-manager-")
        try {
            CacheManager(root, this).also { manager ->
                assertTrue(Files.exists(root.resolve("cache-kmp/cache.db")))
                assertTrue(Files.exists(root.resolve("cache-kmp/files/index/cache-file.db")))
                manager.clearAll()
                manager.close()
            }

            CacheManager(root, this).close()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `manager rejects relative and filesystem root directories`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            CacheManager(Path.of("relative"), this)
        }
        assertFailsWith<IllegalArgumentException> {
            CacheManager(Path.of("/"), this)
        }
    }
}
