package dev.voir.cache.core.internal

import dev.voir.cache.core.key.EncodedCacheKey
import kotlin.random.Random

/**
 * Creates manager-salted, non-reversible diagnostic fingerprints for encoded addresses.
 *
 * @param salt Per-manager random salt preventing stable cross-manager address correlation.
 */
internal class CacheKeyFingerprinter(
    private val salt: Long = Random.nextLong(),
) {
    /**
     * Fingerprints [key] without exposing its raw partition or identity.
     *
     * @param key Encoded address to fingerprint.
     * @return Fixed-width hexadecimal diagnostic identifier.
     */
    fun fingerprint(key: EncodedCacheKey): String {
        var hash = FNV_OFFSET xor salt.toULong()
        val address = "${key.partition.value}\u0000${key.value}"
        address.forEach { character ->
            hash = (hash xor character.code.toULong()) * FNV_PRIME
        }
        return hash.toString(16).padStart(FINGERPRINT_LENGTH, '0')
    }

    private companion object {
        /** FNV-1a 64-bit offset basis. */
        val FNV_OFFSET: ULong = 14_695_981_039_346_656_037uL

        /** FNV-1a 64-bit prime. */
        val FNV_PRIME: ULong = 1_099_511_628_211uL

        /** Hexadecimal length of an unsigned 64-bit value. */
        const val FINGERPRINT_LENGTH: Int = 16
    }
}
