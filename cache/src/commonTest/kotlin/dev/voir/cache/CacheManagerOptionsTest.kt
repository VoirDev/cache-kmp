package dev.voir.cache

import kotlin.test.Test
import kotlin.test.assertFailsWith

class CacheManagerOptionsTest {
    @Test
    fun `whole manager limits must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            CacheManagerOptions(persistentMaxEntries = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            CacheManagerOptions(persistentMaxBytes = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            CacheManagerOptions(fileMaxEntries = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            CacheManagerOptions(fileMaxBytes = 0)
        }
    }
}
