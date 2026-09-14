package dev.voir.cache.room.internal.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

/**
 * Generic persisted cache row containing an opaque payload and all authoritative metadata.
 *
 * The composite primary key isolates entries by namespace, partition, and encoded cache key.
 *
 * @property namespace Resource and serialization-contract identity.
 * @property partition Tenant, session, or environment boundary.
 * @property cacheKey Encoded identity within the partition.
 * @property payload Opaque codec-produced bytes.
 * @property payloadVersion Positive codec format version.
 * @property validatedAtEpochSeconds Validation instant epoch-second component.
 * @property validatedAtNanoseconds Validation instant nanosecond component.
 * @property expiresAtEpochSeconds Optional expiration instant epoch-second component.
 * @property expiresAtNanoseconds Optional expiration instant nanosecond component.
 * @property writtenAtEpochSeconds Write instant epoch-second component.
 * @property writtenAtNanoseconds Write instant nanosecond component.
 * @property lastAccessedAtEpochSeconds Approximate retention-access epoch-second component.
 * @property lastAccessedAtNanoseconds Approximate retention-access nanosecond component.
 */
@Entity(
    tableName = "cache_entry",
    primaryKeys = ["namespace", "partition", "cache_key"],
    indices = [
        Index(value = ["expires_at_epoch_seconds", "expires_at_nanoseconds"]),
        Index(
            value = [
                "last_accessed_at_epoch_seconds",
                "last_accessed_at_nanoseconds",
            ]
        ),
    ],
)
internal class CacheEntryEntity(
    @ColumnInfo(name = "namespace")
    val namespace: String,
    @ColumnInfo(name = "partition")
    val partition: String,
    @ColumnInfo(name = "cache_key")
    val cacheKey: String,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB)
    val payload: ByteArray,
    @ColumnInfo(name = "payload_version")
    val payloadVersion: Int,
    @ColumnInfo(name = "validated_at_epoch_seconds")
    val validatedAtEpochSeconds: Long,
    @ColumnInfo(name = "validated_at_nanoseconds")
    val validatedAtNanoseconds: Int,
    @ColumnInfo(name = "expires_at_epoch_seconds")
    val expiresAtEpochSeconds: Long?,
    @ColumnInfo(name = "expires_at_nanoseconds")
    val expiresAtNanoseconds: Int?,
    @ColumnInfo(name = "written_at_epoch_seconds")
    val writtenAtEpochSeconds: Long,
    @ColumnInfo(name = "written_at_nanoseconds")
    val writtenAtNanoseconds: Int,
    @ColumnInfo(name = "last_accessed_at_epoch_seconds")
    val lastAccessedAtEpochSeconds: Long,
    @ColumnInfo(name = "last_accessed_at_nanoseconds")
    val lastAccessedAtNanoseconds: Int,
)
