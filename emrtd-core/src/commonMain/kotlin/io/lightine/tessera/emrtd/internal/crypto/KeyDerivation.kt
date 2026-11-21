package io.lightine.tessera.emrtd.internal.crypto

/** The key a KDF call makes, with the hash and length Doc 9303 Part 11, sections 9.7.1.1-9.7.1.2 fix. */
internal enum class DerivedKeyType(
    val hash: HashAlgorithm,
    val length: Int,
) {
    /** Two-key 3DES: SHA-1, octets 1-16 (key A then key B), DES parity bits adjusted. */
    DESEDE(HashAlgorithm.SHA1, 16),

    /** AES-128: SHA-1, octets 1-16. */
    AES128(HashAlgorithm.SHA1, 16),

    /** AES-192: SHA-256, octets 1-24. */
    AES192(HashAlgorithm.SHA256, 24),

    /** AES-256: SHA-256, all 32 octets. */
    AES256(HashAlgorithm.SHA256, 32),
}

/**
 * The key derivation function of Doc 9303 Part 11, section 9.7.1: `keydata = H(K || c)` with `c` a
 * 32-bit big-endian counter, cut to the key length of [DerivedKeyType]. For ECDH secrets, K is the
 * x-coordinate of the shared point ([EllipticCurves.sharedSecret] already returns it).
 */
internal object KeyDerivation {
    /** Counter for the encryption key (KEnc, KSEnc; section 9.7.2 and 9.7.4). */
    const val COUNTER_ENC: Int = 1

    /** Counter for the MAC key (KMAC, KSMAC). */
    const val COUNTER_MAC: Int = 2

    /** Counter for the PACE password key Kπ (section 9.7.3). */
    const val COUNTER_PACE: Int = 3

    /** `KDF(secret, counter)` for a key of [type]. The caller wipes [secret] when done. */
    fun derive(
        crypto: ChipCrypto,
        secret: ByteArray,
        counter: Int,
        type: DerivedKeyType,
    ): ByteArray {
        val input = ByteArray(secret.size + 4)
        secret.copyInto(input)
        input[secret.size] = (counter ushr 24).toByte()
        input[secret.size + 1] = (counter ushr 16).toByte()
        input[secret.size + 2] = (counter ushr 8).toByte()
        input[secret.size + 3] = counter.toByte()
        val keydata = crypto.digest(type.hash, input)
        CryptoBytes.wipe(input)
        val key = keydata.copyOf(type.length)
        CryptoBytes.wipe(keydata)
        // Parity adjustment is OPTIONAL in section 9.7.1.1; doing it matches the worked example in
        // Appendix D.1, and DES ignores the parity bits either way.
        if (type == DerivedKeyType.DESEDE) adjustDesParity(key)
        return key
    }

    /** Sets the low bit of each byte so every byte has odd parity (DES key parity, FIPS 46-3). */
    fun adjustDesParity(key: ByteArray) {
        for (i in key.indices) {
            val b = key[i].toInt() and 0xFE
            val ones = (b ushr 1).countOneBits()
            key[i] = (if (ones % 2 == 0) b or 1 else b).toByte()
        }
    }
}
