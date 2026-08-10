package dev.voir.cache.core.characterization

import dev.voir.cache.core.createCacheManager
import dev.voir.cache.core.config.cacheSpec
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.model.CacheFreshness
import dev.voir.cache.core.model.CacheDiscarded
import dev.voir.cache.core.model.FetchSuccess
import dev.voir.cache.core.policy.NeverExpire
import dev.voir.cache.core.policy.MemoryPolicy
import dev.voir.cache.core.storage.memoryOnly
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Freezes the revision-guard requirement for source work that completes after a mutation.
 *
 * The source is deliberately released only after removal has advanced the address revision.
 */
class LateResultSuppressionCharacterizationTest {
    /**
     * Requires a late source success to remain discarded after its address is removed.
     */
    @Test
    fun `late source success cannot restore removed entry`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = createCacheManager(backgroundScope.coroutineContext)
        val resource = manager.resource(
            spec = cacheSpec(
                namespace = "characterization.late-result.v1",
                key = { key: String ->
                    EncodedCacheKey(CachePartition("test"), key)
                },
                freshness = NeverExpire,
                storage = memoryOnly(),
                memory = MemoryPolicy(maxEntries = 4),
            ),
            fetch = {
                started.complete(Unit)
                release.await()
                FetchSuccess("late")
            },
            mapUnexpectedFailure = { throwable -> throwable.message ?: "unexpected" },
        )
        val refresh = async { resource.refresh("entry") }
        started.await()

        resource.remove("entry")
        release.complete(Unit)
        val discarded = assertIs<CacheDiscarded<String, String>>(refresh.await())

        val snapshot = resource.current("entry")
        assertNull(snapshot.value)
        assertEquals(CacheFreshness.ABSENT, snapshot.freshness)
        assertEquals(snapshot, discarded.currentSnapshot)
        manager.close()
    }
}
