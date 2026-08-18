package dev.voir.cache.internal

import dev.voir.cache.CacheDuplicates
import dev.voir.cache.CacheFileOptions
import dev.voir.cache.CacheFileOverrides
import dev.voir.cache.CacheOptions
import dev.voir.cache.CacheOverrides
import dev.voir.cache.CacheRefresh
import dev.voir.cache.CacheStorage
import kotlinx.serialization.KSerializer
import kotlin.time.Duration

internal data class ValueCacheConfiguration(
    val namespace: String,
    val storage: CacheStorage,
    val expiresAfter: Duration,
    val maxMemoryEntries: Int,
    val maxPayloadBytes: Int,
    val refresh: CacheRefresh,
    val useStaleOnError: Boolean,
) {
    fun request(partition: String, overrides: CacheOverrides): ValueRequestConfiguration =
        ValueRequestConfiguration(
            partition = partition,
            expiresAfter = overrides.expiresAfter ?: expiresAfter,
            refresh = overrides.refresh ?: refresh,
            useStaleOnError = overrides.useStaleOnError ?: useStaleOnError,
        )

    companion object {
        /**
         * Derives the effective namespace from [options] or the serialized value type.
         *
         * @param options Caller-supplied direct-cache configuration.
         * @param valueSerializer Serializer whose name identifies an unnamed value cache.
         */
        fun from(
            options: CacheOptions,
            valueSerializer: KSerializer<*>,
        ): ValueCacheConfiguration = ValueCacheConfiguration(
            namespace = options.namespace ?: "value.${valueSerializer.descriptor.serialName}",
            storage = options.storage,
            expiresAfter = options.expiresAfter,
            maxMemoryEntries = options.maxMemoryEntries,
            maxPayloadBytes = options.maxPayloadBytes,
            refresh = options.refresh,
            useStaleOnError = options.useStaleOnError,
        )

        /** Creates a configuration for a scope that already owns an explicit namespace. */
        fun named(options: CacheOptions, namespace: String): ValueCacheConfiguration =
            ValueCacheConfiguration(
                namespace = namespace,
                storage = options.storage,
                expiresAfter = options.expiresAfter,
                maxMemoryEntries = options.maxMemoryEntries,
                maxPayloadBytes = options.maxPayloadBytes,
                refresh = options.refresh,
                useStaleOnError = options.useStaleOnError,
            )
    }
}

internal data class ValueRequestConfiguration(
    val partition: String,
    val expiresAfter: Duration,
    val refresh: CacheRefresh,
    val useStaleOnError: Boolean,
)

internal data class FileCacheConfiguration(
    val namespace: String,
    val expiresAfter: Duration,
    val maxEntryBytes: Long,
    val maxEntries: Int,
    val maxBytes: Long,
    val refresh: CacheRefresh,
    val useStaleOnError: Boolean,
    val mediaType: String?,
) {
    fun request(partition: String, overrides: CacheFileOverrides): FileRequestConfiguration =
        FileRequestConfiguration(
            partition = partition,
            expiresAfter = overrides.expiresAfter ?: expiresAfter,
            refresh = overrides.refresh ?: refresh,
            useStaleOnError = overrides.useStaleOnError ?: useStaleOnError,
            mediaType = overrides.mediaType ?: mediaType,
        )

    companion object {
        /**
         * Derives the effective namespace from [options] or the serialized key type.
         *
         * @param options Caller-supplied direct file-cache configuration.
         * @param keySerializer Serializer whose name identifies an unnamed file cache.
         */
        fun from(
            options: CacheFileOptions,
            keySerializer: KSerializer<*>,
        ): FileCacheConfiguration =
            named(options, options.namespace ?: "file.${keySerializer.descriptor.serialName}")

        /** Creates a configuration for a scope that already owns an explicit namespace. */
        fun named(options: CacheFileOptions, namespace: String): FileCacheConfiguration =
            FileCacheConfiguration(
                namespace = namespace,
                expiresAfter = options.expiresAfter,
                maxEntryBytes = options.maxEntryBytes,
                maxEntries = options.maxEntries,
                maxBytes = options.maxBytes,
                refresh = options.refresh,
                useStaleOnError = options.useStaleOnError,
                mediaType = options.mediaType,
            )
    }
}

internal data class FileRequestConfiguration(
    val partition: String,
    val expiresAfter: Duration,
    val refresh: CacheRefresh,
    val useStaleOnError: Boolean,
    val mediaType: String?,
)

internal data class PagedCacheConfiguration(
    val namespace: String,
    val expiresAfter: Duration,
    val maxPages: Int,
    val maxItemsPerQuery: Int,
    val maxQueries: Int,
    val maxTotalItems: Int,
    val duplicates: CacheDuplicates,
    val useStaleOnError: Boolean,
) {
    init {
        require(!expiresAfter.isNegative()) {
            "Paged cache expiresAfter must not be negative."
        }
        require(maxPages > 0) { "Paged cache maxPages must be greater than zero." }
        require(maxItemsPerQuery > 0) {
            "Paged cache maxItemsPerQuery must be greater than zero."
        }
        require(maxQueries > 0) { "Paged cache maxQueries must be greater than zero." }
        require(maxTotalItems > 0) { "Paged cache maxTotalItems must be greater than zero." }
        require(maxItemsPerQuery <= maxTotalItems) {
            "Paged cache maxItemsPerQuery must not exceed maxTotalItems."
        }
    }
}
