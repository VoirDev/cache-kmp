package dev.voir.cache.codec.kotlinx

import kotlinx.serialization.Serializable

/**
 * Current JSON payload fixture.
 *
 * @property label Stable fixture label.
 * @property count Current fixture count introduced in version two.
 */
@Serializable
internal data class CurrentPayload(
    val label: String,
    val count: Int,
)
