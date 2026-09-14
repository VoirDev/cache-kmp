package dev.voir.cache.sample.memory

import dev.voir.cache.CacheManager
import dev.voir.cache.cache
import dev.voir.cache.cacheScope
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** Demonstrates reusable, typed, memory-only caching through the unified facade. */
class MemoryOnlyConsumerTest {
    @Test
    fun `domain reuses one memory scope`() = runTest {
        val root = createTempDirectory("cache-kmp-memory-consumer-")
        var sourceCalls = 0
        val manager = CacheManager(rootDirectory = root, scope = this)
        try {
            val articles = cacheScope<ArticleKey, Article>("sample.articles") {
                expiresAfter = 5.minutes
                maxMemoryEntries = 100
                partitionBy { it.accountId }
            }
            val key = ArticleKey(accountId = "account-1", slug = "cache-design")
            val load = suspend {
                sourceCalls += 1
                Article(title = "Article ${key.slug}")
            }

            val first = manager.cache(articles, key, load = load)
            val second = manager.cache(articles, key, load = load)

            assertEquals("Article cache-design", first.title)
            assertEquals(first, second)
            assertEquals(1, sourceCalls)
        } finally {
            manager.close()
            root.toFile().deleteRecursively()
        }
    }
}
