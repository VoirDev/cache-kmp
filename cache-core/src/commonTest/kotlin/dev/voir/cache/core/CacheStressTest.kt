package dev.voir.cache.core

import dev.voir.cache.core.config.cacheSpec
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.model.CacheActivity
import dev.voir.cache.core.model.CacheSuccess
import dev.voir.cache.core.model.FetchSuccess
import dev.voir.cache.core.policy.MemoryPolicy
import dev.voir.cache.core.policy.NeverExpire
import dev.voir.cache.core.storage.memoryOnly
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Exercises deterministic concurrent command pressure across independent and shared addresses. */
class CacheStressTest {
    /** Verifies mixed concurrent commands settle without stuck activity or missing values. */
    @Test
    fun `mixed concurrent commands settle consistently`() = runTest {
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = cacheSpec(
                namespace = "stress.mixed.v1",
                key = { key: String -> EncodedCacheKey(CachePartition("stress"), key) },
                freshness = NeverExpire,
                storage = memoryOnly(),
                memory = MemoryPolicy(maxEntries = 64),
            ),
            fetch = { key -> FetchSuccess("source:$key") },
            mapUnexpectedFailure = { throwable -> throwable.message ?: "unexpected" },
        )
        repeat(40) { index -> resource.put("key-$index", "seed:$index") }

        (0 until 1_000).map { index ->
            async {
                val key = "key-${index % 40}"
                when (index % 4) {
                    0 -> resource.put(key, "put:$index")
                    1 -> assertIs<CacheSuccess<String>>(resource.get(key))
                    2 -> resource.update(key) { value -> "$value:updated" }
                    else -> resource.invalidate(key)
                }
            }
        }.awaitAll()

        repeat(40) { index ->
            val snapshot = resource.current("key-$index")
            assertNotNull(snapshot.value)
            assertEquals(CacheActivity.IDLE, snapshot.activity)
        }
        manager.close()
    }
}
