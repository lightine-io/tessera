package io.lightine.tessera.emrtd.internal.pace

import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.crypto.HashAlgorithm
import io.lightine.tessera.emrtd.internal.crypto.KeyDerivation
import io.lightine.tessera.emrtd.internal.sm.SmCipher

/** The password PACE runs with, and its reference in MSE:Set AT (Part 11, section 4.4.4.1, tag 0x83). */
internal enum class PacePasswordType(
    val reference: Int,
) {
    MRZ(0x01),
    CAN(0x02),
    ;

    internal companion object {
        fun of(key: ChipAccessKey): PacePasswordType =
            when (key) {
                is ChipAccessKey.Mrz -> MRZ
                is ChipAccessKey.Can -> CAN
            }
    }
}

/** The password key of PACE (Doc 9303 Part 11, section 9.7.3). */
internal object PacePassword {
    /**
     * The password encoding K = f(π) of Table 14:
     * - MRZ: SHA-1 of the MRZ information (document number, date of birth, date of expiry, each
     *   with its check digit), all 20 bytes; the complete document number is the key's job
     *   ([ChipAccessKey.Mrz]).
     * - CAN: the character string in ISO/IEC 8859-1 (digits, so the bytes are the ASCII codes).
     *
     * The caller wipes the result.
     */
    fun encode(
        crypto: ChipCrypto,
        key: ChipAccessKey,
    ): ByteArray {
        val secret = key.secretCopy()
        return when (key) {
            is ChipAccessKey.Mrz -> {
                crypto.digest(HashAlgorithm.SHA1, secret).also { CryptoBytes.wipe(secret) }
            }

            is ChipAccessKey.Can -> {
                secret
            }
        }
    }

    /** Kπ = KDF(f(π), 3) for the suite's cipher (sections 9.7.1 and 9.7.3). The caller wipes it. */
    fun derive(
        crypto: ChipCrypto,
        key: ChipAccessKey,
        cipher: SmCipher,
    ): ByteArray {
        val encoded = encode(crypto, key)
        try {
            return KeyDerivation.derive(crypto, encoded, KeyDerivation.COUNTER_PACE, cipher.keyType)
        } finally {
            CryptoBytes.wipe(encoded)
        }
    }
}
