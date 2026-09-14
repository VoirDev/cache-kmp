package dev.voir.cache.codec.kotlinx

import dev.voir.cache.core.codec.CacheCodec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Deterministic internal UTF-8 JSON adapter for serialized cache values.
 *
 * All failures are wrapped without retaining a cause or exposing payload contents in exception
 * messages. Payload versioning exists so a future library release can recognize and discard a
 * format it no longer understands rather than decoding it incorrectly.
 *
 * @param V Non-null value type handled by this codec.
 * @param serializer Explicit serializer for the current payload shape.
 * @param json Caller-configured JSON format used for encoding and decoding.
 */
internal class KotlinxJsonCacheCodec<V : Any>(
    private val serializer: KSerializer<V>,
    private val json: Json,
) : CacheCodec<V> {
    override val currentVersion: Int = PAYLOAD_VERSION

    /**
     * Encodes [value] as UTF-8 JSON using the configured serializer and format.
     *
     * @param value Current typed value to encode.
     * @return Deterministic bytes for the same serializer, JSON configuration, and value.
     * @throws CachePayloadCodecException When serialization fails.
     */
    override fun encode(value: V): ByteArray = try {
        json.encodeToString(serializer, value).encodeToByteArray()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        throw CachePayloadCodecException(
            "Unable to encode JSON cache payload version $currentVersion."
        )
    }

    /**
     * Decodes a payload written with the current format version.
     *
     * @param payload Opaque UTF-8 JSON bytes read from persistence.
     * @param storedVersion Positive version recorded with [payload].
     * @return Decoded current value.
     * @throws CachePayloadCodecException When the version is unsupported or decoding fails.
     */
    override fun decode(payload: ByteArray, storedVersion: Int): V {
        if (storedVersion != currentVersion) {
            throw CachePayloadCodecException(
                "Unsupported JSON cache payload version $storedVersion; " +
                    "currentVersion=$currentVersion."
            )
        }
        return try {
            json.decodeFromString(serializer, payload.decodeToString(throwOnInvalidSequence = true))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            throw CachePayloadCodecException(
                "Unable to decode JSON cache payload version $storedVersion."
            )
        }
    }

    private companion object {
        /** Durable payload format version written by this codec. */
        const val PAYLOAD_VERSION: Int = 1
    }
}
