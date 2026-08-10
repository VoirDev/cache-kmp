package dev.voir.cache.core.fixture

import dev.voir.cache.core.codec.CacheCodec

/**
 * Deterministic string codec used by cache-core contract tests.
 *
 * @param currentVersion Positive payload version accepted by this codec instance.
 */
internal class TestStringCodec(
    override val currentVersion: Int = 1,
) : CacheCodec<String> {
    /**
     * Encodes [value] as UTF-8 bytes.
     *
     * @param value String value to encode.
     * @return Deterministic UTF-8 payload.
     */
    override fun encode(value: String): ByteArray = value.encodeToByteArray()

    /**
     * Decodes UTF-8 [payload] when [storedVersion] matches [currentVersion].
     *
     * @param payload UTF-8 bytes to decode.
     * @param storedVersion Payload version recorded by persistence.
     * @return Decoded string value.
     */
    override fun decode(payload: ByteArray, storedVersion: Int): String {
        require(storedVersion == currentVersion) { "Unsupported test payload version." }
        return payload.decodeToString()
    }
}
