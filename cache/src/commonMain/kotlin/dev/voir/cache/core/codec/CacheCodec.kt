package dev.voir.cache.core.codec

/**
 * Encodes and decodes one persistent cache value type.
 *
 * @param V Non-null value type handled by the codec.
 */
internal interface CacheCodec<V : Any> {
    /** Positive payload format version written by [encode]. */
    val currentVersion: Int

    /**
     * Encodes [value] into an opaque persistent payload.
     *
     * @param value Value to encode.
     * @return Encoded payload bytes.
     */
    fun encode(value: V): ByteArray

    /**
     * Decodes [payload] written with [storedVersion].
     *
     * Implementations must reject a version they do not understand with an exception that does not
     * expose payload contents.
     *
     * @param payload Opaque bytes read from persistent storage.
     * @param storedVersion Positive format version recorded with the payload.
     * @return Decoded value.
     */
    fun decode(payload: ByteArray, storedVersion: Int): V
}
