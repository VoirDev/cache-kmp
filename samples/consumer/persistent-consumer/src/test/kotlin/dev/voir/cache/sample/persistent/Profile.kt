package dev.voir.cache.sample.persistent

import kotlinx.serialization.Serializable

/**
 * Serializable domain value retained by the persistent sample.
 *
 * @property displayName User-facing name loaded from the source.
 */
@Serializable
data class Profile(
    val displayName: String,
)
