package dev.voir.cache.sample.persistent

import kotlinx.serialization.Serializable

/**
 * Domain-owned profile key.
 *
 * @property accountId Account partition and stable profile identity.
 */
@Serializable
data class ProfileKey(
    val accountId: String,
)
