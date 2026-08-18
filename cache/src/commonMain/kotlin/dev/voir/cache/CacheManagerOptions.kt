package dev.voir.cache

/**
 * Whole-manager safety limits for internally owned persistent storage.
 *
 * Cache scopes will provide their own finer-grained memory, payload, and file limits. These values
 * are final caps across all scopes in one manager.
 */
public data class CacheManagerOptions public constructor(
    public val persistentMaxEntries: Int = DEFAULT_PERSISTENT_MAX_ENTRIES,
    public val persistentMaxBytes: Long = DEFAULT_PERSISTENT_MAX_BYTES,
    public val fileMaxEntries: Int = DEFAULT_FILE_MAX_ENTRIES,
    public val fileMaxBytes: Long = DEFAULT_FILE_MAX_BYTES,
) {
    init {
        require(persistentMaxEntries > 0) {
            "Cache persistentMaxEntries must be greater than zero."
        }
        require(persistentMaxBytes > 0) {
            "Cache persistentMaxBytes must be greater than zero."
        }
        require(fileMaxEntries > 0) {
            "Cache fileMaxEntries must be greater than zero."
        }
        require(fileMaxBytes > 0) {
            "Cache fileMaxBytes must be greater than zero."
        }
    }
}

private const val DEFAULT_PERSISTENT_MAX_ENTRIES: Int = 10_000
private const val DEFAULT_PERSISTENT_MAX_BYTES: Long = 256L * 1_024 * 1_024
private const val DEFAULT_FILE_MAX_ENTRIES: Int = 2_000
private const val DEFAULT_FILE_MAX_BYTES: Long = 512L * 1_024 * 1_024
