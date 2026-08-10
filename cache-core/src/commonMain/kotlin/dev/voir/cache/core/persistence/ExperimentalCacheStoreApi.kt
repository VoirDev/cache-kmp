package dev.voir.cache.core.persistence

/**
 * Marks the advanced persistent-store SPI, which may evolve while storage adapters stabilize.
 *
 * Ordinary consumers should use a supported adapter such as `cache-room` instead of implementing
 * this SPI directly.
 */
@RequiresOptIn(
    message = "The persistent cache-store SPI is experimental and may evolve.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
public annotation class ExperimentalCacheStoreApi
