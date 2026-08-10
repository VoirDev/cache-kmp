package dev.voir.cache.core.characterization

import dev.voir.cache.core.createCacheManager
import dev.voir.cache.core.config.cacheSpec
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.model.CacheActivity
import dev.voir.cache.core.policy.ExpireAfter
import dev.voir.cache.core.policy.MemoryPolicy
import dev.voir.cache.core.storage.memoryOnly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.minutes

/**
 * Freezes the required terminal state when an active source load is cancelled.
 *
 * This guards cleanup when the application loader propagates cancellation while the manager stays
 * usable.
 */
class CancellationCleanupCharacterizationTest {
    /**
     * Requires cancellation to retain stale data and return the entry to idle activity.
     */
    @Test
    fun `cancelled refresh clears activity state`() = runTest {
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = cacheSpec(
                namespace = "characterization.cancellation.v1",
                key = { key: String ->
                    EncodedCacheKey(CachePartition("test"), key)
                },
                freshness = ExpireAfter(5.minutes),
                storage = memoryOnly(),
                memory = MemoryPolicy(maxEntries = 4),
            ),
            fetch = { throw CancellationException("Source cancelled") },
            mapUnexpectedFailure = { throwable -> throwable.message ?: "unexpected" },
        )
        resource.put("entry", "stale")
        resource.invalidate("entry")

        assertFailsWith<CancellationException> { resource.refresh("entry") }

        val snapshot = resource.current("entry")
        assertEquals("stale", snapshot.value)
        assertEquals(CacheActivity.IDLE, snapshot.activity)
        manager.close()
    }
}
