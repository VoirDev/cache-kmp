package dev.voir.cache.sample.memory

import kotlinx.serialization.Serializable

/**
 * Domain value cached by the memory-only consumer.
 *
 * @property title Display title returned by the source.
 */
@Serializable
data class Article(
    val title: String,
)
