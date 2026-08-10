package dev.voir.cache.codec.kotlinx

/**
 * Reports JSON cache encoding or decoding failure without including value or payload contents.
 *
 * @param message Non-sensitive description of the failed codec operation.
 */
public class CachePayloadCodecException internal constructor(
    message: String,
) : IllegalArgumentException(message)
