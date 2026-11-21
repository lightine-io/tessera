package io.lightine.tessera.emrtd.internal.sm

import io.lightine.tessera.emrtd.internal.crypto.BlockCipher
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.crypto.DerivedKeyType
import io.lightine.tessera.emrtd.internal.crypto.KeyDerivation

/**
 * The cipher of a secure-messaging session (Doc 9303 Part 11, sections 9.8.6 and 9.8.7): 3DES after
 * BAC (and after PACE or chip authentication with a 3DES suite), AES with a 128, 192 or 256-bit key
 * otherwise.
 */
internal enum class SmCipher(
    val blockCipher: BlockCipher,
    val keyType: DerivedKeyType,
) {
    DESEDE(BlockCipher.DESEDE, DerivedKeyType.DESEDE),
    AES128(BlockCipher.AES, DerivedKeyType.AES128),
    AES192(BlockCipher.AES, DerivedKeyType.AES192),
    AES256(BlockCipher.AES, DerivedKeyType.AES256),
    ;

    /** The block size, which is also the length of the send sequence counter (section 9.8.2). */
    val blockSize: Int
        get() = blockCipher.blockSize

    /**
     * The send sequence counter a session starts with when no protocol sets another one: zero
     * (section 9.8.6.3 for 3DES after PACE or chip authentication, 9.8.7.3 for AES). BAC sets its
     * own start value ([io.lightine.tessera.emrtd.internal.bac.Bac]).
     */
    fun zeroSsc(): ByteArray = ByteArray(blockSize)
}

/**
 * The two session keys of secure messaging, KSEnc and KSMAC, for [cipher]. The object owns its
 * arrays: [wipe] overwrites them when the session ends or restarts with new keys.
 */
internal class SessionKeys(
    val cipher: SmCipher,
    encKey: ByteArray,
    macKey: ByteArray,
) {
    val encKey: ByteArray = encKey.copyOf()
    val macKey: ByteArray = macKey.copyOf()

    init {
        require(this.encKey.size == cipher.keyType.length && this.macKey.size == cipher.keyType.length) {
            "session keys do not have the length of the cipher"
        }
    }

    fun wipe() = CryptoBytes.wipe(encKey, macKey)

    /** Never shows the keys. */
    override fun toString(): String = "SessionKeys($cipher)"

    internal companion object {
        /**
         * KSEnc = KDF(K, 1) and KSMAC = KDF(K, 2) from the shared secret [secret] (section 9.7.4).
         * The caller wipes [secret].
         */
        fun derive(
            crypto: ChipCrypto,
            secret: ByteArray,
            cipher: SmCipher,
        ): SessionKeys {
            val enc = KeyDerivation.derive(crypto, secret, KeyDerivation.COUNTER_ENC, cipher.keyType)
            val mac = KeyDerivation.derive(crypto, secret, KeyDerivation.COUNTER_MAC, cipher.keyType)
            val keys = SessionKeys(cipher, enc, mac)
            CryptoBytes.wipe(enc, mac)
            return keys
        }
    }
}
