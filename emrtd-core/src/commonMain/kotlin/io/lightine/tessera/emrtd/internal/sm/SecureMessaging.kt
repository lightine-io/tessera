package io.lightine.tessera.emrtd.internal.sm

import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.crypto.BlockCipher
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.CipherMode
import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.tlv.Tlv
import io.lightine.tessera.emrtd.internal.tlv.TlvException
import io.lightine.tessera.emrtd.internal.tlv.TlvParser
import io.lightine.tessera.emrtd.internal.tlv.TlvWriter

/**
 * The reader side of secure messaging, Doc 9303 Part 11, section 9.8: turns a plain command into a
 * protected one ([protect]) and a protected answer back into a plain one ([unprotect]), keeping the
 * send sequence counter (SSC).
 *
 * - Encrypt-then-MAC (9.8.1). Data is padded with ISO/IEC 9797-1 method 2 and encrypted with CBC:
 *   zero IV for 3DES (9.8.6.1), IV = E(KSEnc, SSC) for AES (9.8.7.1).
 * - The MAC covers SSC || padded header || data objects, padded again (9.8.4, Figure 5). 3DES uses
 *   the retail MAC (9.8.6.2), AES the CMAC cut to 8 bytes (9.8.7.2).
 * - The SSC is incremented before every command and before every answer (9.8.2), so the first
 *   command uses x+1 and its answer x+2.
 * - Data objects: command `[85|87] [97] 8E`, answer `[85|87] [99] 8E`; DO'87' for an even INS and
 *   DO'85' for an odd one (9.8.4); CLA gets the secure-messaging bits `0C`.
 *
 * One instance is one session. It is not thread-safe: commands of a session are sequential anyway.
 * [SecureChannel] wraps it into an [io.lightine.tessera.emrtd.internal.apdu.ApduSender] and ends the
 * session on the first failure.
 */
internal class SecureMessaging(
    private val crypto: ChipCrypto,
    keys: SessionKeys,
    ssc: ByteArray,
) {
    private var keys: SessionKeys = keys
    private var ssc: ByteArray = startSsc(keys, ssc)

    /** The cipher of the current keys. */
    val cipher: SmCipher
        get() = keys.cipher

    /** A copy of the current SSC, for tests and for protocols that check it. */
    fun sscCopy(): ByteArray = ssc.copyOf()

    /**
     * Restarts the session with new keys and a new start SSC (section 9.8.2): chip authentication
     * calls this after the answer to its last key-agreement command has been unprotected with the
     * old keys. The old keys and counter are wiped.
     */
    fun restart(
        newKeys: SessionKeys,
        newSsc: ByteArray,
    ) {
        val checked = startSsc(newKeys, newSsc)
        keys.wipe()
        CryptoBytes.wipe(ssc)
        keys = newKeys
        ssc = checked
    }

    /** Wipes the keys and the counter. The instance cannot be used afterwards. */
    fun wipe() {
        keys.wipe()
        CryptoBytes.wipe(ssc)
    }

    /** The protected form of the plain [command]. Increments the SSC. */
    fun protect(command: CommandApdu): CommandApdu {
        require(command.cla and CLA_SM_BITS == 0) { "the command is already marked as protected" }
        increment(ssc)
        val bs = cipher.blockSize
        val cla = command.cla or CLA_SM_BITS
        val header = byteArrayOf(cla.toByte(), command.ins.toByte(), command.p1.toByte(), command.p2.toByte())

        var dataObject = ByteArray(0)
        if (command.data.isNotEmpty()) {
            val padded = CryptoBytes.pad(command.data, bs)
            val encrypted = encrypt(padded)
            CryptoBytes.wipe(padded)
            dataObject =
                if (command.ins and 1 == 1) {
                    TlvWriter.encode(TAG_ODD_DATA, encrypted)
                } else {
                    TlvWriter.encode(TAG_EVEN_DATA, byteArrayOf(PADDING_INDICATOR) + encrypted)
                }
        }
        val ne = command.ne
        val leObject = if (ne == null) ByteArray(0) else TlvWriter.encode(TAG_LE, leBytes(ne))

        val macInput = CryptoBytes.pad(header, bs) + dataObject + leObject
        val macObject = TlvWriter.encode(TAG_MAC, mac(macInput))
        val body = dataObject + leObject + macObject

        // The protected Le is '00' (short) or '00 00' (extended): any length (Figure 5).
        val extended = !command.fitsShort || body.size > CommandApdu.MAX_SHORT_LC
        return CommandApdu(
            cla,
            command.ins,
            command.p1,
            command.p2,
            body,
            if (extended) CommandApdu.MAX_EXTENDED_NE else CommandApdu.MAX_SHORT_NE,
        )
    }

    /**
     * The plain form of the chip's [response]. Increments the SSC first, whatever the answer.
     *
     * An answer with no data is a bare status word, which a chip sends without secure messaging:
     * - 9000 bare is unauthenticated: [SmFailure.MISSING_MAC];
     * - a secure-messaging error (6987, 6988) or 6982 means the chip ended the session (section
     *   9.8.5): [SmFailure.CHIP_ENDED_SESSION];
     * - any other status word is returned as it is and the session goes on. Section 9.8.5 asks for
     *   protected answers here, but chips are known to answer some errors bare; whether such a chip
     *   still counts that answer in its SSC needs a real-device check.
     *
     * @throws SecureMessagingException when the answer cannot be authenticated or read.
     */
    fun unprotect(response: ResponseApdu): ResponseApdu {
        increment(ssc)
        // A plain 61xx/6Cxx at the APDU level means the chip answered outside its protection: those
        // hints are plain protocol, and a protected answer carries its status in DO'99' instead.
        if (response.sw1 == StatusWord.SW1_BYTES_REMAINING || response.sw1 == StatusWord.SW1_WRONG_LE) {
            throw SecureMessagingException(SmFailure.PLAIN_ANSWER_LENGTH_HINT, response.sw)
        }
        if (response.data.isEmpty()) {
            when (response.sw) {
                StatusWord.SUCCESS -> {
                    throw SecureMessagingException(SmFailure.MISSING_MAC)
                }

                StatusWord.SM_DATA_OBJECTS_MISSING,
                StatusWord.SM_DATA_OBJECTS_INCORRECT,
                StatusWord.SECURITY_STATUS_NOT_SATISFIED,
                -> {
                    throw SecureMessagingException(SmFailure.CHIP_ENDED_SESSION, response.sw)
                }

                else -> {
                    return response
                }
            }
        }
        val objects =
            try {
                TlvParser().parseAll(response.data)
            } catch (e: TlvException) {
                throw SecureMessagingException(SmFailure.MALFORMED_RESPONSE, cause = e)
            }
        val macObject = objects.lastOrNull()?.takeIf { it.tag == TAG_MAC } ?: throw SecureMessagingException(SmFailure.MISSING_MAC)
        val covered = objects.dropLast(1)
        var dataObject: Tlv? = null
        var statusObject: Tlv? = null
        for ((i, o) in covered.withIndex()) {
            when {
                i == 0 && (o.tag == TAG_EVEN_DATA || o.tag == TAG_ODD_DATA) -> dataObject = o
                o.tag == TAG_STATUS && statusObject == null && i == covered.lastIndex -> statusObject = o
                else -> throw SecureMessagingException(SmFailure.MALFORMED_RESPONSE)
            }
        }

        var macInput = ByteArray(0)
        covered.forEach { macInput += it.encoded() }
        val expected = mac(macInput)
        val received = macObject.value()
        if (!CryptoBytes.constantTimeEquals(expected, received)) throw SecureMessagingException(SmFailure.WRONG_MAC)

        val sw =
            statusObject?.let {
                val v = it.value()
                if (v.size != 2) throw SecureMessagingException(SmFailure.MALFORMED_RESPONSE)
                ((v[0].toInt() and 0xFF) shl 8) or (v[1].toInt() and 0xFF)
            } ?: response.sw

        val data = dataObject?.let { decryptDataObject(it) } ?: ByteArray(0)
        val plain = ResponseApdu(data, sw)
        CryptoBytes.wipe(data)
        return plain
    }

    private fun decryptDataObject(o: Tlv): ByteArray {
        val value = o.value()
        val encrypted =
            if (o.tag == TAG_EVEN_DATA) {
                if (value.isEmpty() || value[0] != PADDING_INDICATOR) throw SecureMessagingException(SmFailure.MALFORMED_RESPONSE)
                value.copyOfRange(1, value.size)
            } else {
                value
            }
        if (encrypted.isEmpty() || encrypted.size % cipher.blockSize != 0) {
            throw SecureMessagingException(SmFailure.MALFORMED_RESPONSE)
        }
        val padded = decrypt(encrypted)
        val data = CryptoBytes.unpad(padded)
        CryptoBytes.wipe(padded)
        return data ?: throw SecureMessagingException(SmFailure.WRONG_PADDING)
    }

    private fun iv(): ByteArray =
        when (cipher.blockCipher) {
            BlockCipher.DESEDE -> ByteArray(cipher.blockSize)
            BlockCipher.AES -> crypto.encrypt(BlockCipher.AES, CipherMode.ECB, keys.encKey, null, ssc)
        }

    private fun encrypt(padded: ByteArray): ByteArray = crypto.encrypt(cipher.blockCipher, CipherMode.CBC, keys.encKey, iv(), padded)

    private fun decrypt(encrypted: ByteArray): ByteArray = crypto.decrypt(cipher.blockCipher, CipherMode.CBC, keys.encKey, iv(), encrypted)

    /** The 8-byte MAC of SSC || [message], padded to the block size. */
    private fun mac(message: ByteArray): ByteArray {
        val padded = CryptoBytes.pad(ssc + message, cipher.blockSize)
        return when (cipher.blockCipher) {
            BlockCipher.DESEDE -> crypto.retailMac(keys.macKey, padded)
            BlockCipher.AES -> crypto.cmac(keys.macKey, padded).copyOf(MAC_LENGTH)
        }
    }

    internal companion object {
        /** The CLA bits that mark a command as protected with an authenticated header (`0C`). */
        const val CLA_SM_BITS = 0x0C
        const val TAG_ODD_DATA = 0x85
        const val TAG_EVEN_DATA = 0x87
        const val TAG_LE = 0x97
        const val TAG_STATUS = 0x99
        const val TAG_MAC = 0x8E
        const val MAC_LENGTH = 8

        /** The first value byte of DO'87': padding method 2 was applied. */
        const val PADDING_INDICATOR: Byte = 0x01

        /** The value of DO'97': one byte for a short Le (256 as `00`), two for an extended one. */
        fun leBytes(ne: Int): ByteArray =
            if (ne <= CommandApdu.MAX_SHORT_NE) {
                byteArrayOf((if (ne == CommandApdu.MAX_SHORT_NE) 0 else ne).toByte())
            } else {
                val v = if (ne == CommandApdu.MAX_EXTENDED_NE) 0 else ne
                byteArrayOf((v shr 8).toByte(), v.toByte())
            }

        /** Adds one to the unsigned big-endian counter [ssc], wrapping to zero after all ones. */
        fun increment(ssc: ByteArray) {
            for (i in ssc.indices.reversed()) {
                val b = (ssc[i].toInt() and 0xFF) + 1
                ssc[i] = b.toByte()
                if (b <= 0xFF) return
            }
        }

        private fun startSsc(
            keys: SessionKeys,
            ssc: ByteArray,
        ): ByteArray {
            require(ssc.size == keys.cipher.blockSize) { "the send sequence counter must be one block long" }
            return ssc.copyOf()
        }
    }
}
