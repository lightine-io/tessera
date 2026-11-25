package io.lightine.tessera.emrtd.internal.bac

import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.internal.apdu.ApduChannel
import io.lightine.tessera.emrtd.internal.apdu.ApduSender
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.crypto.BlockCipher
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.CipherMode
import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.crypto.DerivedKeyType
import io.lightine.tessera.emrtd.internal.crypto.HashAlgorithm
import io.lightine.tessera.emrtd.internal.crypto.KeyDerivation
import io.lightine.tessera.emrtd.internal.sm.SecureChannel
import io.lightine.tessera.emrtd.internal.sm.SessionKeys
import io.lightine.tessera.emrtd.internal.sm.SmCipher

/** Why Basic Access Control did not open the chip. Carries no key, nonce or data bytes. */
internal enum class BacFailure {
    /** GET CHALLENGE was refused (status word other than 9000). */
    CHALLENGE_REFUSED,

    /** GET CHALLENGE returned a challenge that is not 8 bytes long. */
    WRONG_CHALLENGE_LENGTH,

    /**
     * EXTERNAL AUTHENTICATE was refused: usually the MRZ values do not match the ones the chip
     * was set up with (6300 or 6982), or the chip has no BAC.
     */
    AUTHENTICATION_REFUSED,

    /** EXTERNAL AUTHENTICATE answered 9000 with data that is not 40 bytes long. */
    WRONG_RESPONSE_LENGTH,

    /** The chip's checksum M.IC over E.IC does not match. */
    WRONG_CHIP_MAC,

    /** The decrypted answer does not hold the reader's nonce RND.IFD and the chip's RND.IC. */
    NONCE_MISMATCH,
}

/** Basic Access Control failed. [sw] is the refusing status word, when there is one. */
internal class BacException(
    val failure: BacFailure,
    val sw: Int? = null,
) : IllegalStateException("BAC failed: $failure" + (sw?.let { " (status ${StatusWord.hex(it)})" } ?: ""))

/** The document basic access keys KEnc and KMAC (Part 11, section 9.7.2). The caller wipes both. */
internal class BacKeys(
    val encKey: ByteArray,
    val macKey: ByteArray,
) {
    fun wipe() = CryptoBytes.wipe(encKey, macKey)

    override fun toString(): String = "BacKeys"
}

/**
 * Basic Access Control, Doc 9303 Part 11, section 4.3: the reader proves it has read the MRZ, and
 * reader and chip agree on 3DES session keys and a send sequence counter for secure messaging.
 *
 * The caller selects the eMRTD application first (section 4.2, step 4) and sends everything after
 * a successful [open] through the returned [SecureChannel] (section 4.3.2, step 3).
 */
internal object Bac {
    const val INS_GET_CHALLENGE = 0x84
    const val INS_EXTERNAL_AUTHENTICATE = 0x82
    private const val NONCE_LENGTH = 8
    private const val KEY_MATERIAL_LENGTH = 16
    private const val KEY_SEED_LENGTH = 16
    private const val CRYPTOGRAM_LENGTH = 32
    private const val MAC_LENGTH = 8

    /**
     * Kseed: the most significant 16 bytes of the SHA-1 hash of the MRZ information (section 4.3.2,
     * step 1). [mrzInformation] is the document number, date of birth and date of expiry, each with
     * its check digit, as ASCII.
     */
    fun keySeed(
        crypto: ChipCrypto,
        mrzInformation: ByteArray,
    ): ByteArray {
        val hash = crypto.digest(HashAlgorithm.SHA1, mrzInformation)
        val seed = hash.copyOf(KEY_SEED_LENGTH)
        CryptoBytes.wipe(hash)
        return seed
    }

    /** KEnc = KDF(Kseed, 1) and KMAC = KDF(Kseed, 2) for [key] (sections 4.3.2 and 9.7.2). */
    fun documentKeys(
        crypto: ChipCrypto,
        key: ChipAccessKey.Mrz,
    ): BacKeys {
        val info = key.secretCopy()
        val seed = keySeed(crypto, info)
        val keys =
            BacKeys(
                KeyDerivation.derive(crypto, seed, KeyDerivation.COUNTER_ENC, DerivedKeyType.DESEDE),
                KeyDerivation.derive(crypto, seed, KeyDerivation.COUNTER_MAC, DerivedKeyType.DESEDE),
            )
        CryptoBytes.wipe(info, seed)
        return keys
    }

    /**
     * Runs the BAC protocol of section 4.3.1 over the raw [exchange] and returns the secure channel
     * it opens. Steps:
     * 1. GET CHALLENGE (`00 84 00 00 08`): the chip's nonce RND.IC.
     * 2. S = RND.IFD || RND.IC || K.IFD, with fresh random RND.IFD (8 bytes) and K.IFD (16 bytes);
     *    E.IFD = 3DES-CBC(KEnc, zero IV, S) with no padding; M.IFD = retail MAC(KMAC, E.IFD).
     * 3. EXTERNAL AUTHENTICATE (`00 82 00 00 28`, E.IFD || M.IFD, Le 28): the chip's E.IC || M.IC.
     * 4. Check M.IC, decrypt R = RND.IC || RND.IFD || K.IC and check both nonces.
     * 5. Session keys from K.IFD xor K.IC (sections 9.7.1, 9.7.4); SSC = the last 4 bytes of
     *    RND.IC || the last 4 bytes of RND.IFD (section 9.8.6.3).
     *
     * BAC's own commands are plain, so it wraps [exchange] in an [ApduChannel] to follow the plain
     * 61xx/6Cxx answer-length hints, except that EXTERNAL AUTHENTICATE goes out exactly once
     * ([ApduChannel.sendOnce]: no re-send on 6Cxx); the returned [SecureChannel] decorates the raw [exchange]
     * directly. [random] supplies the reader's nonce and key material (the platform's strong random
     * source; tests pass the values of Appendix D.3). Transport failures pass through unchanged.
     * [beforeAuthentication] runs once, after the chip answered GET CHALLENGE and right before
     * EXTERNAL AUTHENTICATE: GET CHALLENGE only asks the chip for a random number, and the
     * authentication attempt is EXTERNAL AUTHENTICATE (section 4.3.1).
     *
     * @throws BacException when the chip refuses or answers wrongly.
     */
    suspend fun open(
        exchange: ApduSender,
        crypto: ChipCrypto,
        key: ChipAccessKey.Mrz,
        random: (Int) -> ByteArray = crypto::randomBytes,
        beforeAuthentication: suspend () -> Unit = {},
    ): SecureChannel {
        val keys = documentKeys(crypto, key)
        try {
            return run(exchange, crypto, keys, random, beforeAuthentication)
        } finally {
            keys.wipe()
        }
    }

    private suspend fun run(
        exchange: ApduSender,
        crypto: ChipCrypto,
        keys: BacKeys,
        random: (Int) -> ByteArray,
        beforeAuthentication: suspend () -> Unit,
    ): SecureChannel {
        val plain = ApduChannel(exchange)
        val challenge = plain.send(CommandApdu(0x00, INS_GET_CHALLENGE, 0x00, 0x00, ne = NONCE_LENGTH))
        if (challenge.sw != StatusWord.SUCCESS) throw BacException(BacFailure.CHALLENGE_REFUSED, challenge.sw)
        val rndIc = challenge.data
        if (rndIc.size != NONCE_LENGTH) throw BacException(BacFailure.WRONG_CHALLENGE_LENGTH)

        val rndIfd = random(NONCE_LENGTH)
        val kIfd = random(KEY_MATERIAL_LENGTH)
        // Every secret below is wiped on every path, an exception included (a lost connection, a
        // crypto failure): the finally block owns them.
        var s: ByteArray? = null
        var r: ByteArray? = null
        var kIc: ByteArray? = null
        var seed: ByteArray? = null
        var ssc: ByteArray? = null
        try {
            require(rndIfd.size == NONCE_LENGTH && kIfd.size == KEY_MATERIAL_LENGTH) { "random source returned the wrong length" }
            s = rndIfd + rndIc + kIfd
            val eIfd = crypto.encrypt(BlockCipher.DESEDE, CipherMode.CBC, keys.encKey, ByteArray(NONCE_LENGTH), s)
            val mIfd = crypto.retailMac(keys.macKey, CryptoBytes.pad(eIfd, NONCE_LENGTH))
            CryptoBytes.wipe(s)

            beforeAuthentication()
            // Sent exactly once: on 6Cxx a second EXTERNAL AUTHENTICATE would be a second attempt.
            val answer =
                plain.sendOnce(
                    CommandApdu(0x00, INS_EXTERNAL_AUTHENTICATE, 0x00, 0x00, eIfd + mIfd, CRYPTOGRAM_LENGTH + MAC_LENGTH),
                )
            if (answer.sw != StatusWord.SUCCESS) throw BacException(BacFailure.AUTHENTICATION_REFUSED, answer.sw)
            val data = answer.data
            if (data.size != CRYPTOGRAM_LENGTH + MAC_LENGTH) throw BacException(BacFailure.WRONG_RESPONSE_LENGTH)
            val eIc = data.copyOf(CRYPTOGRAM_LENGTH)
            val mIc = data.copyOfRange(CRYPTOGRAM_LENGTH, data.size)
            if (!CryptoBytes.constantTimeEquals(crypto.retailMac(keys.macKey, CryptoBytes.pad(eIc, NONCE_LENGTH)), mIc)) {
                throw BacException(BacFailure.WRONG_CHIP_MAC)
            }
            r = crypto.decrypt(BlockCipher.DESEDE, CipherMode.CBC, keys.encKey, ByteArray(NONCE_LENGTH), eIc)
            val nonceOk =
                CryptoBytes.constantTimeEquals(r.copyOfRange(0, NONCE_LENGTH), rndIc) and
                    CryptoBytes.constantTimeEquals(r.copyOfRange(NONCE_LENGTH, 2 * NONCE_LENGTH), rndIfd)
            if (!nonceOk) throw BacException(BacFailure.NONCE_MISMATCH)
            val chipKey = r.copyOfRange(2 * NONCE_LENGTH, CRYPTOGRAM_LENGTH)
            kIc = chipKey
            val keySeed = ByteArray(KEY_MATERIAL_LENGTH) { (kIfd[it].toInt() xor chipKey[it].toInt()).toByte() }
            seed = keySeed
            val sessionKeys = SessionKeys.derive(crypto, keySeed, SmCipher.DESEDE)
            val counter = rndIc.copyOfRange(4, NONCE_LENGTH) + rndIfd.copyOfRange(4, NONCE_LENGTH)
            ssc = counter
            // The channel owns the session keys from here and wipes them when the session ends.
            return try {
                SecureChannel(exchange, crypto, sessionKeys, counter)
            } catch (e: Throwable) {
                sessionKeys.wipe()
                throw e
            }
        } finally {
            CryptoBytes.wipe(s, r, kIc, seed, kIfd, rndIfd, ssc)
        }
    }
}
