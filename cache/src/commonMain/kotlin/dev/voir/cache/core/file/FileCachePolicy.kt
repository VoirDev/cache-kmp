package dev.voir.cache.core.file

import kotlin.time.Duration

/**
 * Hard count and byte limits for one file resource namespace.
 *
 * All limits are required so neither content bytes nor zero-byte entry metadata can grow without a
 * finite bound. Limits are enforced for every successful commit.
 */
internal data class FileCachePolicy(
    val maxEntryBytes: Long,
    val maxEntries: Int,
    val maxBytes: Long,
) {
    init {
        require(maxEntryBytes > 0) { "File cache maxEntryBytes must be greater than zero." }
        require(maxEntries > 0) { "File cache maxEntries must be greater than zero." }
        require(maxBytes > 0) { "File cache maxBytes must be greater than zero." }
        require(maxEntryBytes <= maxBytes) {
            "File cache maxEntryBytes must not exceed maxBytes."
        }
    }
}

/** Hard whole-store bounds configured by one filesystem adapter. */
internal data class FileStoreLimits(
    val maxTotalEntries: Int,
    val maxTotalBytes: Long,
) {
    init {
        require(maxTotalEntries > 0) { "File cache maxTotalEntries must be greater than zero." }
        require(maxTotalBytes > 0) { "File cache maxTotalBytes must be greater than zero." }
    }
}

/** Expiration maintenance behavior for file content. */
internal data class FileRetentionPolicy(
    val removeExpired: Boolean = true,
    val expiredEntryGrace: Duration = Duration.ZERO,
) {
    init {
        require(expiredEntryGrace.isFinite() && !expiredEntryGrace.isNegative()) {
            "File cache expiredEntryGrace must be finite and non-negative."
        }
    }
}
