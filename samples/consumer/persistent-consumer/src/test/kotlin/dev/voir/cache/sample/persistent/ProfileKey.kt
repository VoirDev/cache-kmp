package dev.voir.cache.sample.persistent

/**
 * Domain-owned profile key.
 *
 * @property accountId Account partition and stable profile identity.
 */
data class ProfileKey(
    val accountId: String,
)
