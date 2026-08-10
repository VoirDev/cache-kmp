package dev.voir.cache.room.internal

import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.model.CacheMetadata
import dev.voir.cache.core.persistence.CacheAddress
import dev.voir.cache.core.persistence.ExperimentalCacheStoreApi
import dev.voir.cache.core.persistence.PersistedCacheRecord
import dev.voir.cache.room.internal.entity.CacheEntryEntity
import kotlin.time.Instant

/**
 * Converts this storage-neutral record into its Room row.
 *
 * @return Room entity preserving payload and instant precision.
 */
@OptIn(ExperimentalCacheStoreApi::class)
internal fun PersistedCacheRecord.toEntity(): CacheEntryEntity = CacheEntryEntity(
    namespace = address.namespace.value,
    partition = address.partition.value,
    cacheKey = address.key,
    family = address.family,
    payload = payload,
    payloadVersion = payloadVersion,
    validatedAtEpochSeconds = metadata.validatedAt.epochSeconds,
    validatedAtNanoseconds = metadata.validatedAt.nanosecondsOfSecond,
    expiresAtEpochSeconds = metadata.expiresAt?.epochSeconds,
    expiresAtNanoseconds = metadata.expiresAt?.nanosecondsOfSecond,
    invalidatedAtEpochSeconds = metadata.invalidatedAt?.epochSeconds,
    invalidatedAtNanoseconds = metadata.invalidatedAt?.nanosecondsOfSecond,
    writtenAtEpochSeconds = writtenAt.epochSeconds,
    writtenAtNanoseconds = writtenAt.nanosecondsOfSecond,
    lastAccessedAtEpochSeconds = lastAccessedAt.epochSeconds,
    lastAccessedAtNanoseconds = lastAccessedAt.nanosecondsOfSecond,
)

/**
 * Converts this Room row into its storage-neutral record.
 *
 * @return Persistent record preserving payload and instant precision.
 */
@OptIn(ExperimentalCacheStoreApi::class)
internal fun CacheEntryEntity.toRecord(): PersistedCacheRecord = PersistedCacheRecord(
    address = CacheAddress(
        namespace = CacheNamespace(namespace),
        partition = CachePartition(partition),
        key = cacheKey,
        family = family,
    ),
    payload = payload,
    payloadVersion = payloadVersion,
    metadata = CacheMetadata(
        validatedAt = Instant.fromEpochSeconds(validatedAtEpochSeconds, validatedAtNanoseconds),
        expiresAt = expiresAtEpochSeconds.toInstant(expiresAtNanoseconds),
        invalidatedAt = invalidatedAtEpochSeconds.toInstant(invalidatedAtNanoseconds),
    ),
    writtenAt = Instant.fromEpochSeconds(writtenAtEpochSeconds, writtenAtNanoseconds),
    lastAccessedAt = Instant.fromEpochSeconds(lastAccessedAtEpochSeconds, lastAccessedAtNanoseconds),
)

/**
 * Reconstructs an optional instant from paired nullable database columns.
 *
 * @param nanoseconds Optional nanosecond component paired with this epoch-second value.
 * @return Reconstructed instant, or `null` when both components are absent.
 */
private fun Long?.toInstant(nanoseconds: Int?): Instant? {
    check((this == null) == (nanoseconds == null)) {
        "Persistent cache instant components must either both be null or both be present."
    }
    return this?.let { epochSeconds ->
        Instant.fromEpochSeconds(epochSeconds, requireNotNull(nanoseconds))
    }
}
