package dev.voir.cache.core.fixture

/**
 * Application-independent fake domain key used to demonstrate typed cache specifications.
 *
 * @property partition Stable isolation boundary used by the sample.
 * @property id Stable identity within the partition.
 */
internal data class FakeDomainKey(
    val partition: String,
    val id: String,
)

