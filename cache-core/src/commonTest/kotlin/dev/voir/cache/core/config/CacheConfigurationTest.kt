package dev.voir.cache.core.config

import dev.voir.cache.core.fixture.TestStringCodec
import dev.voir.cache.core.key.CacheNamespace
import dev.voir.cache.core.key.CachePartition
import dev.voir.cache.core.key.EncodedCacheKey
import dev.voir.cache.core.key.EncodedCacheFamily
import dev.voir.cache.core.policy.ExpireAfter
import dev.voir.cache.core.policy.MemoryPolicy
import dev.voir.cache.core.policy.PersistentRetentionPolicy
import dev.voir.cache.core.policy.PagedMemoryPolicy
import dev.voir.cache.core.paging.FetchedPage
import dev.voir.cache.core.storage.persistent
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Verifies that invalid cache configuration fails at its construction boundary. */
class CacheConfigurationTest {
    /** Verifies stable address components reject blank values immediately. */
    @Test
    fun `blank address components are rejected`() {
        assertFailsWith<IllegalArgumentException> { CacheNamespace(" ") }
        assertFailsWith<IllegalArgumentException> { CachePartition("\t") }
        assertFailsWith<IllegalArgumentException> {
            EncodedCacheKey(CachePartition("partition"), "")
        }
        assertFailsWith<IllegalArgumentException> {
            EncodedCacheFamily(CachePartition("partition"), " ")
        }
    }

    /** Verifies freshness durations reject negative and infinite values while accepting zero. */
    @Test
    fun `invalid freshness durations are rejected`() {
        ExpireAfter(Duration.ZERO)

        assertFailsWith<IllegalArgumentException> { ExpireAfter((-1).seconds) }
        assertFailsWith<IllegalArgumentException> { ExpireAfter(Duration.INFINITE) }
    }

    /** Verifies every resource has an explicit positive in-memory entry bound. */
    @Test
    fun `non-positive memory bounds are rejected`() {
        assertFailsWith<IllegalArgumentException> { MemoryPolicy(maxEntries = 0) }
        assertFailsWith<IllegalArgumentException> { MemoryPolicy(maxEntries = -1) }
        assertFailsWith<IllegalArgumentException> {
            PagedMemoryPolicy(0, 1, 1, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            PagedMemoryPolicy(1, 0, 1, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            PagedMemoryPolicy(1, 1, 0, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            PagedMemoryPolicy(1, 1, 1, 0)
        }
    }

    /** Verifies source page aggregate metadata cannot report a negative total. */
    @Test
    fun `negative paged total count is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            FetchedPage<String, Int>(emptyList(), nextCursor = null, totalCount = -1)
        }
    }

    /** Verifies persistent storage rejects unusable codec versions and payload bounds. */
    @Test
    fun `invalid persistent storage configuration is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            persistent(TestStringCodec(currentVersion = 0))
        }
        assertFailsWith<IllegalArgumentException> {
            persistent(TestStringCodec(), maxPayloadBytes = 0)
        }
    }

    /** Verifies manager address limits must all be positive. */
    @Test
    fun `non-positive manager limits are rejected`() {
        assertFailsWith<IllegalArgumentException> { CacheManagerConfig(maxNamespaceLength = 0) }
        assertFailsWith<IllegalArgumentException> { CacheManagerConfig(maxPartitionLength = 0) }
        assertFailsWith<IllegalArgumentException> { CacheManagerConfig(maxKeyLength = 0) }
        assertFailsWith<IllegalArgumentException> { CacheManagerConfig(maxFamilyLength = 0) }
    }

    /** Verifies persistent retention rejects unusable grace, row, and byte limits. */
    @Test
    fun `invalid persistent retention limits are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PersistentRetentionPolicy(expiredEntryGrace = (-1).seconds)
        }
        assertFailsWith<IllegalArgumentException> {
            PersistentRetentionPolicy(expiredEntryGrace = Duration.INFINITE)
        }
        assertFailsWith<IllegalArgumentException> {
            PersistentRetentionPolicy(maxEntriesPerNamespace = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            PersistentRetentionPolicy(maxTotalEntries = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            PersistentRetentionPolicy(maxTotalPayloadBytes = 0)
        }
    }
}
