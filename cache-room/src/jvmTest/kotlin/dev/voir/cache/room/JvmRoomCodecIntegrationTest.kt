package dev.voir.cache.room

import dev.voir.cache.codec.kotlinx.KotlinxJsonCacheCodec
import dev.voir.cache.core.CacheManager
import dev.voir.cache.core.CachedResource
import dev.voir.cache.core.FamilyCachedResource
import dev.voir.cache.core.RestartableCacheManagerOwner
import dev.voir.cache.core.config.CacheSpec
import dev.voir.cache.core.config.familyCacheSpec
import dev.voir.cache.core.config.cacheSpec
import dev.voir.cache.core.createCacheManager
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheFamily
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.model.FetchSuccess
import dev.voir.cache.core.persistence.ExperimentalCacheStoreApi
import dev.voir.cache.core.policy.MemoryPolicy
import dev.voir.cache.core.policy.NeverExpire
import dev.voir.cache.core.storage.persistent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Verifies typed JSON values and validation metadata survive a real Room database restart. */
@OptIn(ExperimentalCacheStoreApi::class)
class JvmRoomCodecIntegrationTest {
    /** Verifies the JSON codec, cache manager, and Room store preserve one typed value exactly. */
    @Test
    fun `typed json value survives manager and database recreation`() = runTest {
        val directory = Files.createTempDirectory("cache-kmp-codec-room-test")
        val databasePath = directory.resolve("cache.db")
        val owner = RestartableCacheManagerOwner {
            createCacheManager(
                coroutineContext = backgroundScope.coroutineContext,
                persistentStore = createJvmRoomPersistentCacheStore(databasePath),
            )
        }
        try {
            val firstManager = owner.start()
            val firstResource = resource(firstManager)
            val expectedValue = listOf("first", "second", "third")
            firstResource.put("entry", expectedValue)
            val expectedMetadata = assertNotNull(firstResource.current("entry").metadata)
            owner.stop()

            val secondManager = owner.start()
            val restored = resource(secondManager).current("entry")

            assertEquals(expectedValue, restored.value)
            assertEquals(expectedMetadata, restored.metadata)
        } finally {
            owner.stop()
            directory.toFile().deleteRecursively()
        }
    }

    /** Verifies the public family API durably removes only its selected Room rows. */
    @Test
    fun `family removal remains absent after manager and database recreation`() = runTest {
        val directory = Files.createTempDirectory("cache-kmp-family-room-test")
        val databasePath = directory.resolve("cache.db")
        var manager: CacheManager? = null
        try {
            val firstManager = createCacheManager(
                backgroundScope.coroutineContext,
                createJvmRoomPersistentCacheStore(databasePath),
            )
            manager = firstManager
            val first = familyResource(firstManager)
            val selectedOne = RoomFamilyKey("tenant", "account-a", "one")
            val selectedTwo = RoomFamilyKey("tenant", "account-a", "two")
            val unrelated = RoomFamilyKey("tenant", "account-b", "three")
            first.put(selectedOne, "one")
            first.put(selectedTwo, "two")
            first.put(unrelated, "three")
            assertEquals(2, first.removeFamily(RoomFamily("tenant", "account-a")).persistentRowsAffected)
            firstManager.close()
            manager = null

            val secondManager = createCacheManager(
                backgroundScope.coroutineContext,
                createJvmRoomPersistentCacheStore(databasePath),
            )
            manager = secondManager
            val reopened = familyResource(secondManager)
            assertNull(reopened.current(selectedOne).value)
            assertNull(reopened.current(selectedTwo).value)
            assertEquals("three", reopened.current(unrelated).value)
        } finally {
            manager?.close()
            directory.toFile().deleteRecursively()
        }
    }

    /**
     * Registers the typed list resource on [manager].
     *
     * @param manager Cache manager that owns the returned resource.
     * @return Persistent list resource using the deterministic JSON codec.
     */
    private fun resource(
        manager: CacheManager,
    ): CachedResource<String, List<String>, String> = manager.resource(
        spec = spec(),
        fetch = { FetchSuccess(emptyList()) },
        mapUnexpectedFailure = { throwable -> throwable.message ?: "unexpected" },
    )

    /**
     * Creates the integration resource specification.
     *
     * @return Never-expiring persistent specification for string lists.
     */
    private fun spec(): CacheSpec<String, List<String>> = cacheSpec(
        namespace = "codec-room-integration.v1",
        key = { key -> EncodedCacheKey(CachePartition("integration"), key) },
        freshness = NeverExpire,
        storage = persistent(
            codec = KotlinxJsonCacheCodec(
                serializer = ListSerializer(String.serializer()),
                json = Json {
                    encodeDefaults = true
                    explicitNulls = true
                    prettyPrint = false
                },
            ),
        ),
        memory = MemoryPolicy(maxEntries = 4),
    )

    /** Registers a persistent family resource using only public manager and Room APIs. */
    private fun familyResource(
        manager: CacheManager,
    ): FamilyCachedResource<RoomFamilyKey, RoomFamily, String, String> = manager.resource(
        spec = familyCacheSpec(
            namespace = "codec-room-family.v1",
            key = { key: RoomFamilyKey ->
                EncodedCacheKey(CachePartition(key.partition), key.item)
            },
            familyOf = { key -> RoomFamily(key.partition, key.family) },
            family = { value ->
                EncodedCacheFamily(CachePartition(value.partition), value.value)
            },
            freshness = NeverExpire,
            storage = persistent(
                KotlinxJsonCacheCodec(serializer = String.serializer())
            ),
            memory = MemoryPolicy(maxEntries = 8),
        ),
        fetch = { FetchSuccess("source") },
        mapUnexpectedFailure = { throwable -> throwable.message ?: "unexpected" },
    )
}

private data class RoomFamilyKey(
    val partition: String,
    val family: String,
    val item: String,
)

private data class RoomFamily(val partition: String, val value: String)
