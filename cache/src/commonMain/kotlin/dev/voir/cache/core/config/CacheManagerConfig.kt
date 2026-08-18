package dev.voir.cache.core.config

import dev.voir.cache.core.policy.PersistentRetentionPolicy

/**
 * Validation limits shared by every resource registered with one cache manager.
 *
 * @property maxNamespaceLength Maximum accepted namespace length in characters.
 * @property maxPartitionLength Maximum accepted partition length in characters.
 * @property maxKeyLength Maximum accepted encoded-key length in characters.
 * @property persistentRetention Manager-level persistent maintenance policy.
 */
internal data class CacheManagerConfig(
    val maxNamespaceLength: Int = 128,
    val maxPartitionLength: Int = 256,
    val maxKeyLength: Int = 1_024,
    val persistentRetention: PersistentRetentionPolicy = PersistentRetentionPolicy(),
) {
    init {
        require(maxNamespaceLength > 0) { "Cache maxNamespaceLength must be greater than zero." }
        require(maxPartitionLength > 0) { "Cache maxPartitionLength must be greater than zero." }
        require(maxKeyLength > 0) { "Cache maxKeyLength must be greater than zero." }
    }
}
