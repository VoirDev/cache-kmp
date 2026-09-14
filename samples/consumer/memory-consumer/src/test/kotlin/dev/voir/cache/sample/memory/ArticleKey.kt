package dev.voir.cache.sample.memory

import kotlinx.serialization.Serializable

/**
 * Domain-owned cache key containing its isolation boundary and stable identity.
 *
 * @property accountId Account partition that owns the article.
 * @property slug Stable article identifier within the account.
 */
@Serializable
data class ArticleKey(
    val accountId: String,
    val slug: String,
)
