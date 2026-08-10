package dev.voir.cache.codec.kotlinx

import dev.voir.cache.core.codec.CacheCodec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Deterministic UTF-8 JSON adapter for the cache-core codec contract.
 *
 * The current version uses [serializer]. Historical versions are supported only when explicitly
 * listed in [migrations]. All failures are wrapped without retaining a cause or exposing payload
 * contents in exception messages.
 *
 * @param V Non-null value type handled by this codec.
 * @param serializer Explicit serializer for the current payload shape.
 * @param currentVersion Positive payload version written by [encode].
 * @param json Caller-configured JSON format used for current and historical payloads.
 * @param migrations Explicit decoders keyed by supported historical payload version.
 */
public class KotlinxJsonCacheCodec<V : Any> public constructor(
    private val serializer: KSerializer<V>,
    override public val currentVersion: Int = 1,
    private val json: Json = Json,
    migrations: Map<Int, JsonPayloadMigration<V>> = emptyMap(),
) : CacheCodec<V> {
    private val migrations: Map<Int, JsonPayloadMigration<V>> = migrations.toMap()

    init {
        require(currentVersion > 0) { "JSON cache codec currentVersion must be greater than zero." }
        require(this.migrations.keys.all { version -> version in 1 until currentVersion }) {
            "JSON cache migration versions must be positive and older than currentVersion."
        }
    }

    /**
     * Encodes [value] as UTF-8 JSON using the configured serializer and format.
     *
     * @param value Current typed value to encode.
     * @return Deterministic bytes for the same serializer, JSON configuration, and value.
     * @throws CachePayloadCodecException When serialization fails.
     */
    override public fun encode(value: V): ByteArray = try {
        json.encodeToString(serializer, value).encodeToByteArray()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        throw CachePayloadCodecException(
            "Unable to encode JSON cache payload version $currentVersion."
        )
    }

    /**
     * Decodes current JSON or invokes the explicit migration for [storedVersion].
     *
     * @param payload Opaque UTF-8 JSON bytes read from persistence.
     * @param storedVersion Positive version recorded with [payload].
     * @return Decoded current value.
     * @throws CachePayloadCodecException When the version is unsupported or decoding fails.
     */
    override public fun decode(payload: ByteArray, storedVersion: Int): V {
        if (storedVersion <= 0 || storedVersion > currentVersion) {
            throw unsupportedVersion(storedVersion)
        }
        val migration = if (storedVersion == currentVersion) null else migrations[storedVersion]
            ?: throw unsupportedVersion(storedVersion)
        return try {
            val text = payload.decodeToString(throwOnInvalidSequence = true)
            if (migration == null) {
                json.decodeFromString(serializer, text)
            } else {
                migration.decode(json, text)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            throw CachePayloadCodecException(
                "Unable to decode JSON cache payload version $storedVersion."
            )
        }
    }

    /**
     * Creates a sanitized unsupported-version failure.
     *
     * @param storedVersion Version found in the persistent row.
     * @return Exception that contains no payload data.
     */
    private fun unsupportedVersion(storedVersion: Int): CachePayloadCodecException =
        CachePayloadCodecException(
            "Unsupported JSON cache payload version $storedVersion; currentVersion=$currentVersion."
        )
}
