package io.lightine.tessera.emrtd.internal.aa

import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.ChipCryptoException
import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.crypto.HashAlgorithm

/**
 * The RSA answer of active authentication: ISO/IEC 9796-2 digital signature scheme 1 with partial
 * message recovery, as Doc 9303 Part 11, section 6.1.2.2 and Appendix F specify it.
 *
 * The message is `M = M1 || M2`: M1 the chip's own nonce, recoverable from the signature, and M2
 * the reader's nonce RND.IFD, known to both and not returned (Appendix F, the note under step 11).
 * Applying the public operation to the answer gives the message representative
 * `F = 6A || M1 || H(M) || T`: the header `6A` marks partial recovery, and the trailer T names the
 * hash - option 1 is the one byte `BC` (SHA-1), option 2 is two bytes, the hash's identifier then
 * `CC`. The reader takes M1 and the digest out of F, rebuilds `M* = M1 || M2` and compares `H(M*)`
 * with the digest the signature carried (Appendix F, steps 12 to 18).
 *
 * The signature generation scheme B.6 of ISO/IEC 9796-2 - which the chip side SHOULD implement and
 * Appendix F prints - fills F exactly: M1 is c - 4 bits, with c = k - Lh - 8t - 4 for a k-bit
 * modulus. Reading takes the parts by structure, not by length, so any representative laid out the
 * same way reads too.
 */
internal object RsaMessageRecovery {
    /** `6A`, the header of partial message recovery (Appendix F, step 9.2 and its note). */
    private const val PARTIAL_RECOVERY_HEADER = 0x6A

    /** `BC`, the option-1 trailer: SHA-1 (section 6.1.2.2). */
    private const val TRAILER_SHA1 = 0xBC

    /** `CC`, the second byte of every option-2 trailer (section 6.1.2.2). */
    private const val TRAILER_OPTION_2 = 0xCC

    /** The hash identifiers of the option-2 trailers (section 6.1.2.2). */
    private val OPTION_2_HASHES: Map<Int, HashAlgorithm> =
        mapOf(
            0x38 to HashAlgorithm.SHA224,
            0x34 to HashAlgorithm.SHA256,
            0x36 to HashAlgorithm.SHA384,
            0x35 to HashAlgorithm.SHA512,
        )

    /**
     * The parts of one recovered message representative: the recoverable message, the digest the
     * signature carried, and the hash its trailer names.
     */
    class Recovered(
        val recoverablePart: ByteArray,
        val digest: ByteArray,
        val hash: HashAlgorithm,
    )

    /**
     * Splits [recovered] = header || M1 || H || T. `null` when the header or the trailer is not one
     * this module reads, or when no byte is left for M1.
     */
    fun parse(recovered: ByteArray): Recovered? {
        if (recovered.isEmpty() || (recovered[0].toInt() and 0xFF) != PARTIAL_RECOVERY_HEADER) return null
        val hash = trailerHash(recovered) ?: return null
        val trailerLength = if (hash == HashAlgorithm.SHA1) 1 else 2
        val digestStart = recovered.size - trailerLength - hash.outputLength
        if (digestStart < 2) return null
        return Recovered(
            recovered.copyOfRange(1, digestStart),
            recovered.copyOfRange(digestStart, recovered.size - trailerLength),
            hash,
        )
    }

    /**
     * Whether [recovered] is consistent with the reader's nonce [m2]: `H(M1 || m2)` equals the
     * digest the signature carried (Appendix F, steps 16 to 18).
     */
    fun matches(
        crypto: ChipCrypto,
        recovered: Recovered,
        m2: ByteArray,
    ): Boolean =
        CryptoBytes.constantTimeEquals(
            crypto.digest(recovered.hash, recovered.recoverablePart + m2),
            recovered.digest,
        )

    /**
     * The reader's whole RSA step: the public operation on [signature], the recovery, and the
     * digest comparison against [m2], the nonce the reader sent (6.1.2.2).
     */
    fun verify(
        crypto: ChipCrypto,
        key: ActiveAuthenticationPublicKey.Rsa,
        m2: ByteArray,
        signature: ByteArray,
    ): ActiveAuthenticationResult {
        val recovered =
            try {
                parse(crypto.rsaPublic(key.modulus, key.publicExponent, signature))
            } catch (e: ChipCryptoException) {
                null
            } ?: return ActiveAuthenticationResult.Failed(ActiveAuthenticationFailure.MALFORMED_SIGNATURE)
        return if (matches(crypto, recovered, m2)) {
            ActiveAuthenticationResult.Passed
        } else {
            ActiveAuthenticationResult.Failed(ActiveAuthenticationFailure.WRONG_SIGNATURE)
        }
    }

    /** The hash the trailer at the end of [recovered] names, or `null` for any other trailer. */
    private fun trailerHash(recovered: ByteArray): HashAlgorithm? {
        val last = recovered[recovered.size - 1].toInt() and 0xFF
        if (last == TRAILER_SHA1) return HashAlgorithm.SHA1
        if (last == TRAILER_OPTION_2 && recovered.size >= 2) {
            return OPTION_2_HASHES[recovered[recovered.size - 2].toInt() and 0xFF]
        }
        return null
    }
}
