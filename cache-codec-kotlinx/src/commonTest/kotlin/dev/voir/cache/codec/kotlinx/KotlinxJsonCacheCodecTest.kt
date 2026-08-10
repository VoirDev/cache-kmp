package dev.voir.cache.codec.kotlinx

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Verifies deterministic JSON encoding, migrations, version rejection, and sanitized failures. */
class KotlinxJsonCacheCodecTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        prettyPrint = false
    }

    /** Verifies current values produce stable bytes and decode without migration. */
    @Test
    fun `current payload encoding is deterministic`() {
        val codec = codec()
        val value = CurrentPayload(label = "alpha", count = 2)

        val first = codec.encode(value)
        val second = codec.encode(value)

        assertContentEquals(first, second)
        assertEquals("{\"label\":\"alpha\",\"count\":2}", first.decodeToString())
        assertEquals(value, codec.decode(first, storedVersion = 2))
    }

    /** Verifies a registered historical decoder migrates version one to the current value type. */
    @Test
    fun `supported old payload decodes through explicit migration`() {
        val codec = codec()
        val oldPayload = json.encodeToString(OldPayload(name = "legacy")).encodeToByteArray()

        val migrated = codec.decode(oldPayload, storedVersion = 1)

        assertEquals(CurrentPayload(label = "legacy", count = 0), migrated)
    }

    /** Verifies future and unregistered historical versions fail without inspecting payload data. */
    @Test
    fun `unsupported payload versions are rejected`() {
        val codec = KotlinxJsonCacheCodec(
            serializer = CurrentPayload.serializer(),
            currentVersion = 3,
            json = json,
            migrations = mapOf(
                1 to JsonPayloadMigration { format, payload ->
                    val old = format.decodeFromString(OldPayload.serializer(), payload)
                    CurrentPayload(label = old.name, count = 0)
                }
            ),
        )

        val missingMigration = assertFailsWith<CachePayloadCodecException> {
            codec.decode("secret-old-payload".encodeToByteArray(), storedVersion = 2)
        }
        val futureVersion = assertFailsWith<CachePayloadCodecException> {
            codec.decode("secret-future-payload".encodeToByteArray(), storedVersion = 4)
        }

        assertEquals(
            "Unsupported JSON cache payload version 2; currentVersion=3.",
            missingMigration.message,
        )
        assertEquals(
            "Unsupported JSON cache payload version 4; currentVersion=3.",
            futureVersion.message,
        )
    }

    /** Verifies malformed JSON and UTF-8 failures never echo stored payload contents. */
    @Test
    fun `malformed payload failures are sanitized`() {
        val codec = codec()
        val secret = "do-not-log-this-secret"

        val malformedJson = assertFailsWith<CachePayloadCodecException> {
            codec.decode("{\"label\":\"$secret\"".encodeToByteArray(), storedVersion = 2)
        }
        val malformedUtf8 = assertFailsWith<CachePayloadCodecException> {
            codec.decode(byteArrayOf(0xC3.toByte(), 0x28), storedVersion = 2)
        }

        assertEquals("Unable to decode JSON cache payload version 2.", malformedJson.message)
        assertEquals("Unable to decode JSON cache payload version 2.", malformedUtf8.message)
        assertFalse(malformedJson.message.orEmpty().contains(secret))
    }

    /** Verifies migration registration accepts only positive versions older than the writer. */
    @Test
    fun `migration versions must precede current version`() {
        assertFailsWith<IllegalArgumentException> {
            KotlinxJsonCacheCodec(
                serializer = CurrentPayload.serializer(),
                currentVersion = 2,
                json = json,
                migrations = mapOf(
                    2 to JsonPayloadMigration { _, _ -> CurrentPayload("invalid", 0) }
                ),
            )
        }
    }

    /**
     * Creates the version-two codec with one explicit version-one migration.
     *
     * @return Configured current codec used by contract tests.
     */
    private fun codec(): KotlinxJsonCacheCodec<CurrentPayload> = KotlinxJsonCacheCodec(
        serializer = CurrentPayload.serializer(),
        currentVersion = 2,
        json = json,
        migrations = mapOf(
            1 to JsonPayloadMigration { format, payload ->
                val old = format.decodeFromString(OldPayload.serializer(), payload)
                CurrentPayload(label = old.name, count = 0)
            }
        ),
    )
}
