package dev.voir.cache.file.internal

import dev.voir.cache.core.file.FileContentMetadata
import dev.voir.cache.core.file.store.FileGeneration
import dev.voir.cache.core.file.store.FileStoreAddress
import dev.voir.cache.core.file.store.StoredFileRecord
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.model.CacheMetadata
import dev.voir.cache.file.internal.entity.FileEntryEntity
import kotlin.time.Instant

internal fun StoredFileRecord.toEntity(): FileEntryEntity = FileEntryEntity(
    namespace = address.namespace.value,
    partition = address.partition.value,
    cacheKey = address.key,
    generation = generation.value,
    sizeBytes = sizeBytes,
    mediaType = content.mediaType,
    validatedAtEpochSeconds = metadata.validatedAt.epochSeconds,
    validatedAtNanoseconds = metadata.validatedAt.nanosecondsOfSecond,
    expiresAtEpochSeconds = metadata.expiresAt?.epochSeconds,
    expiresAtNanoseconds = metadata.expiresAt?.nanosecondsOfSecond,
    writtenAtEpochSeconds = writtenAt.epochSeconds,
    writtenAtNanoseconds = writtenAt.nanosecondsOfSecond,
    lastAccessedAtEpochSeconds = lastAccessedAt.epochSeconds,
    lastAccessedAtNanoseconds = lastAccessedAt.nanosecondsOfSecond,
)

internal fun FileEntryEntity.toRecord(): StoredFileRecord = StoredFileRecord(
    address = FileStoreAddress(
        namespace = CacheNamespace(namespace),
        partition = CachePartition(partition),
        key = cacheKey,
    ),
    generation = FileGeneration(generation),
    sizeBytes = sizeBytes,
    content = FileContentMetadata(mediaType),
    metadata = CacheMetadata(
        validatedAt = Instant.fromEpochSeconds(validatedAtEpochSeconds, validatedAtNanoseconds),
        expiresAt = expiresAtEpochSeconds.toInstant(expiresAtNanoseconds),
    ),
    writtenAt = Instant.fromEpochSeconds(writtenAtEpochSeconds, writtenAtNanoseconds),
    lastAccessedAt = Instant.fromEpochSeconds(
        lastAccessedAtEpochSeconds,
        lastAccessedAtNanoseconds,
    ),
)

private fun Long?.toInstant(nanoseconds: Int?): Instant? {
    require((this == null) == (nanoseconds == null)) {
        "File-cache instant components must either both be absent or both be present."
    }
    return this?.let { seconds -> Instant.fromEpochSeconds(seconds, requireNotNull(nanoseconds)) }
}
