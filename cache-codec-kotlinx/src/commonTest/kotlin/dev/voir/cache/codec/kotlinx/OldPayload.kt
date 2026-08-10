package dev.voir.cache.codec.kotlinx

import kotlinx.serialization.Serializable

/**
 * Historical version-one JSON payload fixture.
 *
 * @property name Historical label renamed by the version-two model.
 */
@Serializable
internal data class OldPayload(
    val name: String,
)
