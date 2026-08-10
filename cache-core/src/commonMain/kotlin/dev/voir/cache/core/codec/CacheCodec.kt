package dev.voir.cache.core.codec

/**
 * Encodes and decodes one persistent cache value type.
 *
 * @param V Non-null value type handled by the codec.
 */
public interface CacheCodec<V : Any> {
    /** Positive payload format version written by [encode]. */
    public val currentVersion: Int

    /**
     * Encodes [value] into an opaque persistent payload.
     *
     * @param value Value to encode.
     * @return Encoded payload bytes.
     */
    public fun encode(value: V): ByteArray

    /**
     * Decodes [payload] written with [storedVersion].
     *
     * Implementations may migrate supported older versions in memory and must reject unsupported
     * versions with an exception that does not expose payload contents.
     *
     * @param payload Opaque bytes read from persistent storage.
     * @param storedVersion Positive format version recorded with the payload.
     * @return Decoded value.
     */
    public fun decode(payload: ByteArray, storedVersion: Int): V
}

