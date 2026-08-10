package dev.voir.cache.core.fixture

import dev.voir.cache.core.codec.CacheCodec

/** Deterministic test codec that migrates raw version-one strings to prefixed version two. */
internal class MigratingTestStringCodec : CacheCodec<String> {
    /** Current prefixed payload format version. */
    override val currentVersion: Int = 2

    /**
     * Encodes [value] with the current version-two marker.
     *
     * @param value String value to encode.
     * @return UTF-8 version-two payload.
     */
    override fun encode(value: String): ByteArray = "v2:$value".encodeToByteArray()

    /**
     * Decodes raw version-one or marked version-two payloads.
     *
     * @param payload UTF-8 payload bytes.
     * @param storedVersion Version recorded by persistence.
     * @return Current string value.
     */
    override fun decode(payload: ByteArray, storedVersion: Int): String {
        val value = payload.decodeToString()
        return when (storedVersion) {
            1 -> value
            2 -> {
                require(value.startsWith("v2:")) { "Malformed version-two test payload." }
                value.removePrefix("v2:")
            }

            else -> error("Unsupported test payload version.")
        }
    }
}
