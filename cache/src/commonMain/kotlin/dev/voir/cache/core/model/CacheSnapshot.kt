package dev.voir.cache.core.model

/**
 * Immutable observable state for one cached value.
 *
 * Existing values remain available while refresh runs or fails. Source errors are transient and
 * never imply removal of a previously cached value.
 *
 * @param V Non-null cached value type.
 * @param E Application-defined source error type.
 * @property value Current cached value, or `null` when absent.
 * @property freshness Current absence or revalidation state.
 * @property activity Mutually exclusive hydration or source-refresh activity.
 * @property metadata Validation metadata, or `null` when absent.
 * @property refreshError Most recent transient source error, cleared when new work starts.
 */
internal data class CacheSnapshot<V : Any, E : Any>(
    val value: V? = null,
    val freshness: CacheFreshness = CacheFreshness.ABSENT,
    val activity: CacheActivity = CacheActivity.IDLE,
    val metadata: CacheMetadata? = null,
    val refreshError: E? = null,
) {
    init {
        if (value == null) {
            require(freshness == CacheFreshness.ABSENT) {
                "A cache snapshot without a value must have ABSENT freshness."
            }
            require(metadata == null) {
                "A cache snapshot without a value must not have metadata."
            }
        } else {
            require(freshness != CacheFreshness.ABSENT) {
                "A cache snapshot with a value must be FRESH or STALE."
            }
            require(metadata != null) {
                "A cache snapshot with a value must have validation metadata."
            }
        }
    }
}

