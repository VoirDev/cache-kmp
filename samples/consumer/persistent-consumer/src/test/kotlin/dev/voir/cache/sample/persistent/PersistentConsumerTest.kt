package dev.voir.cache.sample.persistent

import dev.voir.cache.CacheManager
import dev.voir.cache.CacheOptions
import dev.voir.cache.CacheStorage
import dev.voir.cache.cache
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/** Demonstrates restoring a typed value from Room before any source refresh is needed. */
class PersistentConsumerTest {
    @Test
    fun `restart reads persistent value before refresh`() = runTest {
        val directory = createTempDirectory("cache-kmp-consumer-")
        val options = CacheOptions(
            namespace = "sample.profiles",
            storage = CacheStorage.PERSISTENT,
        )
        val key = ProfileKey("account-1")
        try {
            var firstSourceCalls = 0
            val firstManager = CacheManager(directory, this)
            val first = firstManager.cache(key, options) {
                firstSourceCalls += 1
                Profile(displayName = "Ada")
            }
            assertEquals("Ada", first.displayName)
            assertEquals(1, firstSourceCalls)
            firstManager.close()

            var restartedSourceCalls = 0
            val restartedManager = CacheManager(directory, this)
            val restored = restartedManager.cache(key, options) {
                restartedSourceCalls += 1
                Profile(displayName = "Should not load")
            }
            assertEquals("Ada", restored.displayName)
            assertEquals(0, restartedSourceCalls)
            restartedManager.close()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
