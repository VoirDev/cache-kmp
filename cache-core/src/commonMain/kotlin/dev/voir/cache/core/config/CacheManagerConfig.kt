package dev.voir.cache.core.config

import dev.voir.cache.core.policy.PersistentRetentionPolicy

/**
 * Validation limits shared by every resource registered with one cache manager.
 *
 * @property maxNamespaceLength Maximum accepted namespace length in characters.
 * @property maxPartitionLength Maximum accepted partition length in characters.
 * @property maxKeyLength Maximum accepted encoded-key length in characters.
 * @property maxFamilyLength Maximum accepted encoded-family length in characters.
 * @property persistentRetention Default manager-level persistent maintenance policy.
 */
public data class CacheManagerConfig public constructor(
    public val maxNamespaceLength: Int = 128,
    public val maxPartitionLength: Int = 256,
    public val maxKeyLength: Int = 1_024,
    public val maxFamilyLength: Int = 512,
    public val persistentRetention: PersistentRetentionPolicy = PersistentRetentionPolicy(),
) {
    init {
        require(maxNamespaceLength > 0) {
            "Cache maxNamespaceLength must be greater than zero."
        }
        require(maxPartitionLength > 0) {
            "Cache maxPartitionLength must be greater than zero."
        }
        require(maxKeyLength > 0) { "Cache maxKeyLength must be greater than zero." }
        require(maxFamilyLength > 0) { "Cache maxFamilyLength must be greater than zero." }
    }
}
