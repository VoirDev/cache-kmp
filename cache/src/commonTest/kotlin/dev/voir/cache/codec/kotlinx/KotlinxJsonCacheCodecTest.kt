package dev.voir.cache.codec.kotlinx

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Verifies deterministic JSON encoding, version rejection, and sanitized failures. */
class KotlinxJsonCacheCodecTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        prettyPrint = false
    }

    /** Verifies current values produce stable bytes and decode without loss. */
    @Test
    fun `current payload encoding is deterministic`() {
        val codec = codec()
        val value = CurrentPayload(label = "alpha", count = 2)

        val first = codec.encode(value)
        val second = codec.encode(value)

        assertContentEquals(first, second)
        assertEquals("{\"label\":\"alpha\",\"count\":2}", first.decodeToString())
        assertEquals(value, codec.decode(first, storedVersion = codec.currentVersion))
    }

    /** Verifies any payload version other than the writer's is rejected without inspection. */
    @Test
    fun `unsupported payload versions are rejected`() {
        val codec = codec()

        val olderVersion = assertFailsWith<CachePayloadCodecException> {
            codec.decode("secret-old-payload".encodeToByteArray(), storedVersion = 0)
        }
        val futureVersion = assertFailsWith<CachePayloadCodecException> {
            codec.decode("secret-future-payload".encodeToByteArray(), storedVersion = 2)
        }

        assertEquals(
            "Unsupported JSON cache payload version 0; currentVersion=1.",
            olderVersion.message,
        )
        assertEquals(
            "Unsupported JSON cache payload version 2; currentVersion=1.",
            futureVersion.message,
        )
    }

    /** Verifies malformed JSON and UTF-8 failures never echo stored payload contents. */
    @Test
    fun `malformed payload failures are sanitized`() {
        val codec = codec()
        val secret = "do-not-log-this-secret"

        val malformedJson = assertFailsWith<CachePayloadCodecException> {
            codec.decode("{\"label\":\"$secret\"".encodeToByteArray(), storedVersion = 1)
        }
        val malformedUtf8 = assertFailsWith<CachePayloadCodecException> {
            codec.decode(byteArrayOf(0xC3.toByte(), 0x28), storedVersion = 1)
        }

        assertEquals("Unable to decode JSON cache payload version 1.", malformedJson.message)
        assertEquals("Unable to decode JSON cache payload version 1.", malformedUtf8.message)
        assertFalse(malformedJson.message.orEmpty().contains(secret))
    }

    private fun codec(): KotlinxJsonCacheCodec<CurrentPayload> = KotlinxJsonCacheCodec(
        serializer = CurrentPayload.serializer(),
        json = json,
    )
}
