package dev.voir.cache.sample.memory

import dev.voir.cache.core.createCacheManager
import dev.voir.cache.core.config.cacheSpec
import dev.voir.cache.core.key.CacheKeyEncoder
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.model.CacheSuccess
import dev.voir.cache.core.model.FetchSuccess
import dev.voir.cache.core.policy.ExpireAfter
import dev.voir.cache.core.policy.MemoryPolicy
import dev.voir.cache.core.storage.memoryOnly
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes

/** Demonstrates a domain using `cache-core` directly without an application cache facade. */
class MemoryOnlyConsumerTest {
    /** Verifies a domain key and value can use the memory-only artifact directly. */
    @Test
    fun `domain uses cache core directly without a facade`() = runTest {
        var sourceCalls = 0
        val manager = createCacheManager(coroutineContext)
        val articles = manager.resource(
            spec = cacheSpec<ArticleKey, Article>(
                namespace = "sample.articles",
                key = CacheKeyEncoder { key ->
                    EncodedCacheKey(
                        partition = CachePartition(key.accountId),
                        value = key.slug,
                    )
                },
                freshness = ExpireAfter(5.minutes),
                storage = memoryOnly(),
                memory = MemoryPolicy(maxEntries = 100),
            ),
            fetch = { key ->
                sourceCalls += 1
                FetchSuccess(Article(title = "Article ${key.slug}"))
            },
            mapUnexpectedFailure = { throwable -> throwable.message ?: "Unexpected source failure" },
        )

        val key = ArticleKey(accountId = "account-1", slug = "cache-design")
        val first = assertIs<CacheSuccess<Article>>(articles.get(key))
        val second = assertIs<CacheSuccess<Article>>(articles.get(key))

        assertEquals("Article cache-design", first.value.title)
        assertEquals(first.value, second.value)
        assertEquals(1, sourceCalls)
        manager.close()
    }
}
