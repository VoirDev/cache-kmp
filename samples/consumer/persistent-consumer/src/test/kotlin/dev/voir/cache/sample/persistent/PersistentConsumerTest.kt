package dev.voir.cache.sample.persistent

import dev.voir.cache.codec.kotlinx.KotlinxJsonCacheCodec
import dev.voir.cache.core.CacheManager
import dev.voir.cache.core.CachedResource
import dev.voir.cache.core.createCacheManager
import dev.voir.cache.core.config.cacheSpec
import dev.voir.cache.core.key.CacheKeyEncoder
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.model.CacheSuccess
import dev.voir.cache.core.model.FetchSuccess
import dev.voir.cache.core.persistence.ExperimentalCacheStoreApi
import dev.voir.cache.core.policy.MemoryPolicy
import dev.voir.cache.core.policy.NeverExpire
import dev.voir.cache.core.storage.persistent
import dev.voir.cache.room.createJvmRoomPersistentCacheStore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Demonstrates restoring a typed value from Room before any source refresh is needed. */
@OptIn(ExperimentalCacheStoreApi::class)
class PersistentConsumerTest {
    /** Verifies a restarted manager reads its fresh Room value without calling the source. */
    @Test
    fun `restart reads persistent value before refresh`() = runTest {
        val directory = createTempDirectory("cache-kmp-consumer-")
        val databasePath = directory.resolve("profiles.db")
        var firstSourceCalls = 0
        val firstManager = manager(databasePath.toString())
        val firstProfiles = profiles(firstManager) {
            firstSourceCalls += 1
            Profile(displayName = "Ada")
        }

        assertEquals(
            "Ada",
            assertIs<CacheSuccess<Profile>>(firstProfiles.get(ProfileKey("account-1"))).value.displayName,
        )
        assertEquals(1, firstSourceCalls)
        firstManager.close()

        var restartedSourceCalls = 0
        val restartedManager = manager(databasePath.toString())
        val restartedProfiles = profiles(restartedManager) {
            restartedSourceCalls += 1
            Profile(displayName = "Should not load")
        }

        val restored = assertIs<CacheSuccess<Profile>>(
            restartedProfiles.get(ProfileKey("account-1")),
        )
        assertEquals("Ada", restored.value.displayName)
        assertEquals(0, restartedSourceCalls)
        restartedManager.close()
        directory.toFile().deleteRecursively()
    }

    /**
     * Creates a manager that owns one JVM Room store.
     *
     * @param databasePath Absolute database path encoded as text for compact sample setup.
     * @return Manager owned by the current test scope.
     */
    private fun TestScope.manager(databasePath: String): CacheManager = createCacheManager(
        coroutineContext = coroutineContext,
        persistentStore = createJvmRoomPersistentCacheStore(
            java.nio.file.Path.of(databasePath),
        ),
    )

    /**
     * Registers the typed persistent profile resource directly on [manager].
     *
     * @param manager Cache manager that owns the resource.
     * @param source Source invoked only when no fresh cached value exists.
     * @return Registered profile resource.
     */
    private fun profiles(
        manager: CacheManager,
        source: suspend () -> Profile,
    ): CachedResource<ProfileKey, Profile, String> = manager.resource(
        spec = cacheSpec(
            namespace = "sample.profiles",
            key = CacheKeyEncoder { key ->
                EncodedCacheKey(
                    partition = CachePartition(key.accountId),
                    value = key.accountId,
                )
            },
            freshness = NeverExpire,
            storage = persistent(
                KotlinxJsonCacheCodec(Profile.serializer()),
            ),
            memory = MemoryPolicy(maxEntries = 10),
        ),
        fetch = { FetchSuccess(source()) },
        mapUnexpectedFailure = { throwable -> throwable.message ?: "Unexpected source failure" },
    )
}
