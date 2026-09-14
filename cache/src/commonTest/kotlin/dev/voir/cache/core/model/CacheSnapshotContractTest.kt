package dev.voir.cache.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Verifies observable snapshot and operation-result invariants. */
class CacheSnapshotContractTest {
    private val validatedAt = Instant.parse("2026-08-10T10:00:00Z")
    private val metadata = CacheMetadata(
        validatedAt = validatedAt,
        expiresAt = validatedAt + 5.minutes,
    )

    /** Verifies the default snapshot represents a fully absent and idle entry. */
    @Test
    fun `default snapshot is absent and idle`() {
        val snapshot = CacheSnapshot<String, String>()

        assertNull(snapshot.value)
        assertEquals(CacheFreshness.ABSENT, snapshot.freshness)
        assertEquals(CacheActivity.IDLE, snapshot.activity)
        assertNull(snapshot.metadata)
    }

    /** Verifies a stale value remains available during refresh with its original metadata. */
    @Test
    fun `stale value remains visible during refresh`() {
        val snapshot = CacheSnapshot<String, String>(
            value = "cached",
            freshness = CacheFreshness.STALE,
            activity = CacheActivity.REFRESHING,
            metadata = metadata,
        )

        assertEquals("cached", snapshot.value)
        assertEquals(metadata, snapshot.metadata)
    }

    /** Verifies absent entries cannot carry value metadata or fresh/stale freshness. */
    @Test
    fun `absent snapshot invariants are enforced`() {
        assertFailsWith<IllegalArgumentException> {
            CacheSnapshot<String, String>(freshness = CacheFreshness.FRESH)
        }
        assertFailsWith<IllegalArgumentException> {
            CacheSnapshot<String, String>(metadata = metadata)
        }
    }

    /** Verifies present values require both non-absent freshness and validation metadata. */
    @Test
    fun `present snapshot invariants are enforced`() {
        assertFailsWith<IllegalArgumentException> {
            CacheSnapshot<String, String>(value = "cached")
        }
        assertFailsWith<IllegalArgumentException> {
            CacheSnapshot<String, String>(
                value = "cached",
                freshness = CacheFreshness.FRESH,
            )
        }
    }

    /** Verifies successful operation results cannot claim absent freshness. */
    @Test
    fun `successful result requires a value freshness`() {
        assertFailsWith<IllegalArgumentException> {
            CacheSuccess(value = "cached", freshness = CacheFreshness.ABSENT)
        }
    }

    /** Verifies a discarded result exposes only the authoritative committed snapshot. */
    @Test
    fun `discarded result carries authoritative snapshot and optional source error`() {
        val snapshot = CacheSnapshot<String, String>(
            value = "winner",
            freshness = CacheFreshness.FRESH,
            metadata = metadata,
        )

        val discarded = CacheDiscarded(
            currentSnapshot = snapshot,
            sourceError = "losing-error",
        )

        assertEquals(snapshot, discarded.currentSnapshot)
        assertEquals("losing-error", discarded.sourceError)
    }

    /** Verifies metadata cannot expire before its validation instant. */
    @Test
    fun `expiration before validation is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CacheMetadata(
                validatedAt = validatedAt,
                expiresAt = validatedAt - 1.minutes,
            )
        }
    }
}
