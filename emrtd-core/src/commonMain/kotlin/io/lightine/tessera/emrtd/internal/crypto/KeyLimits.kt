package io.lightine.tessera.emrtd.internal.crypto

/** What [KeyLimits] found about a key or a set of domain parameters read from chip or certificate data. */
internal enum class KeyCheck {
    /** Within the limits and well formed as far as sizes and parity go. */
    OK,

    /** Larger than this reader computes with (a limit below). */
    TOO_LARGE,

    /** Not a usable key or group: an even or tiny modulus, an exponent of 1, an order below 2, a value not below the modulus. */
    INVALID,
}

/**
 * The one size policy for keys and domain parameters that come from untrusted data (certificates in
 * EF.SOD, EF.CardSecurity and master lists; chip-authentication keys in EF.DG14 or EF.CardSecurity).
 * Every backend computes with what passes here, so a hostile chip or certificate cannot make the
 * reader do arithmetic on numbers of any size it likes.
 *
 * The limits are well above anything Doc 9303 Part 12 (section 4.1.6 and Appendix A) or the
 * standardized domain parameters of Part 11 (Table 12) use: RSA keys of 2048 to 4096 bits, DSA and
 * DH groups of 2048 bits, prime curves up to 521 bits.
 */
internal object KeyLimits {
    const val MAX_RSA_MODULUS_BITS = 8192
    const val MAX_RSA_EXPONENT_BITS = 64
    const val MAX_DSA_P_BITS = 4096
    const val MAX_EC_FIELD_BITS = 640
    const val MAX_DH_P_BITS = 4096

    /** The bit length of the unsigned big-endian number [value] (0 for zero). */
    fun bitLength(value: ByteArray): Int {
        val v = CryptoBytes.stripLeadingZeros(value)
        if (v.isEmpty()) return 0
        return (v.size - 1) * 8 + (32 - (v[0].toInt() and 0xFF).countLeadingZeroBits())
    }

    private fun isOdd(value: ByteArray): Boolean = value.isNotEmpty() && value[value.size - 1].toInt() and 1 == 1

    private fun below(
        value: ByteArray,
        bound: ByteArray,
    ): Boolean = CryptoBytes.compareUnsigned(value, bound) < 0

    private fun atLeast(
        value: ByteArray,
        min: Int,
    ): Boolean = CryptoBytes.compareUnsigned(value, CryptoBytes.ofInt(min)) >= 0

    /** An RSA public key: modulus odd, above 3 and at most [MAX_RSA_MODULUS_BITS]; exponent odd, above 1 and at most [MAX_RSA_EXPONENT_BITS]. */
    fun rsa(
        modulus: ByteArray,
        exponent: ByteArray,
    ): KeyCheck =
        when {
            bitLength(modulus) > MAX_RSA_MODULUS_BITS || bitLength(exponent) > MAX_RSA_EXPONENT_BITS -> KeyCheck.TOO_LARGE
            !isOdd(modulus) || !atLeast(modulus, 5) -> KeyCheck.INVALID
            !isOdd(exponent) || !atLeast(exponent, 3) || !below(exponent, modulus) -> KeyCheck.INVALID
            else -> KeyCheck.OK
        }

    /** A DSA key: p odd and at most [MAX_DSA_P_BITS]; q at least 2 and no longer than p; g and y in `[2, p-1]`. */
    fun dsa(
        p: ByteArray,
        q: ByteArray,
        g: ByteArray,
        y: ByteArray,
    ): KeyCheck =
        when {
            bitLength(p) > MAX_DSA_P_BITS -> KeyCheck.TOO_LARGE
            !isOdd(p) || !atLeast(p, 5) -> KeyCheck.INVALID
            !atLeast(q, 2) || bitLength(q) > bitLength(p) -> KeyCheck.INVALID
            !atLeast(g, 2) || !below(g, p) || !atLeast(y, 2) || !below(y, p) -> KeyCheck.INVALID
            else -> KeyCheck.OK
        }

    /**
     * Prime-curve parameters: p odd, above 3 and at most [MAX_EC_FIELD_BITS]; a and b below p; the
     * order n at least 2 and at most one bit longer than p (Hasse's bound); a cofactor, when given,
     * at least 1 and no longer than p.
     */
    fun ecCurve(
        p: ByteArray,
        a: ByteArray,
        b: ByteArray,
        n: ByteArray,
        cofactor: ByteArray?,
    ): KeyCheck {
        val pBits = bitLength(p)
        return when {
            pBits > MAX_EC_FIELD_BITS -> KeyCheck.TOO_LARGE
            !isOdd(p) || !atLeast(p, 5) -> KeyCheck.INVALID
            !below(a, p) || !below(b, p) -> KeyCheck.INVALID
            !atLeast(n, 2) || bitLength(n) > pBits + 1 -> KeyCheck.INVALID
            cofactor != null && (!atLeast(cofactor, 1) || bitLength(cofactor) > pBits) -> KeyCheck.INVALID
            else -> KeyCheck.OK
        }
    }

    /** DH parameters: p odd, above 3 and at most [MAX_DH_P_BITS]; g in `[2, p-2]`; q, when given, at least 2 and no longer than p. */
    fun dh(
        p: ByteArray,
        g: ByteArray,
        q: ByteArray?,
    ): KeyCheck =
        when {
            bitLength(p) > MAX_DH_P_BITS -> KeyCheck.TOO_LARGE

            !isOdd(p) || !atLeast(p, 5) -> KeyCheck.INVALID

            // g <= p-2, that is g + 1 < p: for odd p the same as g < p-1.
            !atLeast(g, 2) || CryptoBytes.compareUnsigned(g, p) >= 0 || isPMinusOne(g, p) -> KeyCheck.INVALID

            q != null && (!atLeast(q, 2) || bitLength(q) > bitLength(p)) -> KeyCheck.INVALID

            else -> KeyCheck.OK
        }

    /** Whether [g] is p-1 for an odd [p]: the same bytes as p with the last bit cleared. */
    private fun isPMinusOne(
        g: ByteArray,
        p: ByteArray,
    ): Boolean {
        val pm = CryptoBytes.stripLeadingZeros(p)
        pm[pm.size - 1] = (pm[pm.size - 1].toInt() and 0xFE).toByte()
        return CryptoBytes.unsignedEquals(g, pm)
    }

    /**
     * The largest RSASSA-PSS salt a key with [modulus] can hold with [hash] (RFC 8017, 9.1.1 step 3):
     * `emLen - hLen - 2`, where emLen is the byte length of `modBits - 1` bits. Negative when not even
     * an empty salt fits.
     */
    fun maxPssSaltLength(
        modulus: ByteArray,
        hash: HashAlgorithm,
    ): Int {
        val emBits = bitLength(modulus) - 1
        val emLen = (emBits + 7) / 8
        return emLen - hash.outputLength - 2
    }
}
