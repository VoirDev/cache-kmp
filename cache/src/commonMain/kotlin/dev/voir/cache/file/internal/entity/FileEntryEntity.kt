package dev.voir.cache.file.internal.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

/** One authoritative logical address pointing at an immutable filesystem generation. */
@Entity(
    tableName = "file_cache_entry",
    primaryKeys = ["namespace", "partition", "cache_key"],
    indices = [
        Index(value = ["generation"], unique = true),
        Index(value = ["expires_at_epoch_seconds", "expires_at_nanoseconds"]),
        Index(
            value = [
                "last_accessed_at_epoch_seconds",
                "last_accessed_at_nanoseconds",
            ]
        ),
    ],
)
internal data class FileEntryEntity(
    @ColumnInfo(name = "namespace") val namespace: String,
    @ColumnInfo(name = "partition") val partition: String,
    @ColumnInfo(name = "cache_key") val cacheKey: String,
    @ColumnInfo(name = "generation") val generation: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "media_type") val mediaType: String?,
    @ColumnInfo(name = "validated_at_epoch_seconds") val validatedAtEpochSeconds: Long,
    @ColumnInfo(name = "validated_at_nanoseconds") val validatedAtNanoseconds: Int,
    @ColumnInfo(name = "expires_at_epoch_seconds") val expiresAtEpochSeconds: Long?,
    @ColumnInfo(name = "expires_at_nanoseconds") val expiresAtNanoseconds: Int?,
    @ColumnInfo(name = "written_at_epoch_seconds") val writtenAtEpochSeconds: Long,
    @ColumnInfo(name = "written_at_nanoseconds") val writtenAtNanoseconds: Int,
    @ColumnInfo(name = "last_accessed_at_epoch_seconds") val lastAccessedAtEpochSeconds: Long,
    @ColumnInfo(name = "last_accessed_at_nanoseconds") val lastAccessedAtNanoseconds: Int,
)
