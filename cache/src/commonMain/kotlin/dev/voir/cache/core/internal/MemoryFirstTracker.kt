package dev.voir.cache.core.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/**
 * Keeps memory-first mutations authoritative when their durable counterpart did not complete.
 *
 * Two situations must never restore superseded durable data inside one manager session:
 *
 * - an exact address whose memory mutation committed while its durable write failed;
 * - a whole-store clear whose durable delete failed, leaving older rows readable.
 *
 * The first is tracked per address, but only while the durable write is outstanding, so the set
 * stays proportional to in-flight and degraded work rather than to the number of cached addresses.
 * A hard bound keeps a permanently failing backend from growing it without limit. The second needs
 * no per-address state: every durable record carries the instant it was written, so a single
 * cutoff instant suppresses exactly the records that the failed clear should have removed.
 *
 * @param A Storage address type owned by one backend.
 */
internal class MemoryFirstTracker<A : Any> {
    private val mutex = Mutex()
    private val pending = LinkedHashSet<A>()
    private var suppressedBefore: Instant? = null

    /**
     * Records that memory is authoritative for [address] until a durable write succeeds.
     *
     * @param address Exact address mutated in memory before persistence completed.
     */
    suspend fun markPending(address: A) {
        mutex.withLock {
            pending.remove(address)
            pending.add(address)
            while (pending.size > MAX_PENDING) {
                pending.remove(pending.first())
            }
        }
    }

    /**
     * Releases [address] because a newer durable write succeeded.
     *
     * @param address Exact address now safe to restore after memory eviction.
     */
    suspend fun markDurable(address: A) {
        mutex.withLock { pending.remove(address) }
    }

    /**
     * Suppresses every durable record written before [at] after a failed whole-store clear.
     *
     * @param at Instant at which the memory clear became authoritative.
     */
    suspend fun markClearFailed(at: Instant) {
        mutex.withLock {
            val current = suppressedBefore
            if (current == null || at > current) suppressedBefore = at
        }
    }

    /**
     * Returns whether memory is authoritative for [address] regardless of durable content.
     *
     * @param address Exact address considered for hydration.
     * @return `true` when a durable write for the address is still outstanding.
     */
    suspend fun isPending(address: A): Boolean = mutex.withLock { address in pending }

    /**
     * Returns whether a durable record for [address] must be ignored.
     *
     * @param address Exact address considered for hydration.
     * @param writtenAt Instant recorded with the durable record.
     * @return `true` when the record predates a failed clear or a newer memory mutation.
     */
    suspend fun isSuppressed(address: A, writtenAt: Instant): Boolean = mutex.withLock {
        address in pending || suppressedBefore?.let { cutoff -> writtenAt < cutoff } == true
    }

    private companion object {
        /**
         * Upper bound on degraded addresses retained for this manager session.
         *
         * Reaching the bound means the backend has failed for thousands of distinct addresses; the
         * oldest suppression is then dropped in favor of a finite memory cost.
         */
        const val MAX_PENDING: Int = 4_096
    }
}
