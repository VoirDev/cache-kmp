package dev.voir.cache.codec.kotlinx

import kotlinx.serialization.json.Json

/**
 * Decodes one supported historical JSON payload into the resource's current value type.
 *
 * Migration implementations should remain deterministic and must not retain the decoded payload.
 *
 * @param V Non-null current value type produced by the migration.
 */
public fun interface JsonPayloadMigration<V : Any> {
    /**
     * Decodes historical JSON [payload] with the configured [json] format.
     *
     * @param json Caller-supplied JSON format shared with the current codec.
     * @param payload Historical UTF-8 JSON text.
     * @return Value represented in the current resource type.
     */
    public fun decode(json: Json, payload: String): V
}
