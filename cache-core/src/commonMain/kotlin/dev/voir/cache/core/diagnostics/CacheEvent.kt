package dev.voir.cache.core.diagnostics

import dev.voir.cache.core.key.CacheNamespace

/**
 * Non-sensitive diagnostic emitted by cache operations.
 *
 * Payloads, raw partitions, and raw keys are intentionally excluded.
 *
 * @property type Diagnostic category.
 * @property namespace Resource namespace when the event belongs to one resource family.
 * @property keyFingerprint One-way manager-salted address fingerprint, or `null` for bulk events.
 * @property sequence Manager-local monotonic event sequence.
 * @property operation Optional correlated structured operation details.
 */
public data class CacheEvent public constructor(
    public val type: CacheEventType,
    public val namespace: CacheNamespace? = null,
    public val keyFingerprint: String? = null,
    public val sequence: Long = 0,
    public val operation: CacheOperationDetails? = null,
) {
    init {
        require(keyFingerprint == null || keyFingerprint.isNotBlank()) {
            "Cache event keyFingerprint must be null or non-blank."
        }
        require(sequence >= 0) { "Cache event sequence must not be negative." }
    }
}
