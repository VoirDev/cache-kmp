package dev.voir.cache

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class CacheOptionsTest {
    @Test
    fun `value options reject invalid stable limits and partitions`() {
        assertFailsWith<IllegalArgumentException> { CacheOptions(namespace = "") }
        assertFailsWith<IllegalArgumentException> { CacheOptions(expiresAfter = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { CacheOptions(maxMemoryEntries = 0) }
        assertFailsWith<IllegalArgumentException> { CacheOptions(maxPayloadBytes = 0) }
        assertFailsWith<IllegalArgumentException> { CacheOptions(partition = "") }
    }

    @Test
    fun `file options reject invalid stable limits and partitions`() {
        assertFailsWith<IllegalArgumentException> { CacheFileOptions(namespace = "") }
        assertFailsWith<IllegalArgumentException> {
            CacheFileOptions(expiresAfter = (-1).seconds)
        }
        assertFailsWith<IllegalArgumentException> { CacheFileOptions(maxEntryBytes = 0) }
        assertFailsWith<IllegalArgumentException> { CacheFileOptions(maxEntries = 0) }
        assertFailsWith<IllegalArgumentException> { CacheFileOptions(maxBytes = 0) }
        assertFailsWith<IllegalArgumentException> {
            CacheFileOptions(maxEntryBytes = 2, maxBytes = 1)
        }
        assertFailsWith<IllegalArgumentException> { CacheFileOptions(partition = "") }
    }
}
