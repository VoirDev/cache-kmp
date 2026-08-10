package dev.voir.cache.sample.memory

/**
 * Domain value cached by the memory-only consumer.
 *
 * @property title Display title returned by the source.
 */
data class Article(
    val title: String,
)
