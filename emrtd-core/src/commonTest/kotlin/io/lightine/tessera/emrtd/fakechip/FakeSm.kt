package io.lightine.tessera.emrtd.fakechip

import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.crypto.BlockCipher
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.CipherMode
import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.sm.SmCipher
import io.lightine.tessera.emrtd.internal.tlv.TlvException
import io.lightine.tessera.emrtd.internal.tlv.TlvParser
import io.lightine.tessera.emrtd.internal.tlv.TlvWriter

/**
 * The chip side of secure messaging (Doc 9303 Part 11, section 9.8), written apart from the
 * reader's code so that the two check each other: it shares only the block-cipher and MAC
 * primitives of [ChipCrypto].
 *
 * - Before a session, with [accessControl] on, the chip answers file commands with 6982 and lets
 *   only SELECT by AID and the access protocols' commands through (section 4.3.2).
 * - In a session, a plain command ends it and gets 6982 (sections 4.3.2 and 9.8.3, CHIP-3/CHIP-5).
 * - A protected command with a wrong MAC or a bad data object ends the session and gets a bare
 *   6988 (section 9.8.5: SM errors come back without secure messaging).
 * - Otherwise the command is unwrapped, answered by the next handlers and the core
 *   ([FakeChip.answerInner]), and the answer is wrapped as `[87] 99 8E`.
 *
 * Fault injection: [corruptNextResponseMac], [answerNextBare].
 */
internal class FakeSm(
    private val crypto: ChipCrypto,
    val accessControl: Boolean = true,
) : FakeCommandHandler {
    private class Session(
        val cipher: SmCipher,
        val kEnc: ByteArray,
        val kMac: ByteArray,
        val ssc: ByteArray,
    )

    private var session: Session? = null

    /** Whether a secure-messaging session is open. */
    val isActive: Boolean
        get() = session != null

    /** How many sessions ended by an error or a plain command. */
    var sessionsAborted: Int = 0
        private set

    /** Flip one bit of the next answer's MAC. */
    var corruptNextResponseMac: Boolean = false

    /** Answer the next protected command with this bare status word (still counted in the SSC). */
    var answerNextBare: Int? = null

    /** Opens a session: BAC and PACE call this once they agree on keys and a start counter. */
    fun start(
        cipher: SmCipher,
        kEnc: ByteArray,
        kMac: ByteArray,
        ssc: ByteArray,
    ) {
        session = Session(cipher, kEnc.copyOf(), kMac.copyOf(), ssc.copyOf())
    }

    private fun abort(sw: Int): ResponseApdu {
        session = null
        sessionsAborted++
        return ResponseApdu(ByteArray(0), sw)
    }

    override fun handle(
        chip: FakeChip,
        command: CommandApdu,
    ): ResponseApdu? {
        val protected = command.cla and 0x0C == 0x0C
        val s = session
        if (s == null) {
            if (protected) return ResponseApdu(ByteArray(0), StatusWord.SECURITY_STATUS_NOT_SATISFIED)
            if (!accessControl) return null
            val open = command.ins == ChipCommands.INS_SELECT && command.p1 == ChipCommands.SELECT_BY_AID
            return if (open ||
                command.ins in ACCESS_PROTOCOL_INS
            ) {
                null
            } else {
                ResponseApdu(ByteArray(0), StatusWord.SECURITY_STATUS_NOT_SATISFIED)
            }
        }
        if (!protected) return abort(StatusWord.SECURITY_STATUS_NOT_SATISFIED)
        increment(s.ssc)
        val plain = unwrap(s, command) ?: return abort(StatusWord.SM_DATA_OBJECTS_INCORRECT)
        val answer = chip.answerInner(plain, this)
        increment(s.ssc)
        answerNextBare?.let {
            answerNextBare = null
            return ResponseApdu(ByteArray(0), it)
        }
        return wrap(s, command.ins, answer)
    }

    private fun unwrap(
        s: Session,
        c: CommandApdu,
    ): CommandApdu? {
        val objects =
            try {
                TlvParser().parseAll(c.data)
            } catch (e: TlvException) {
                return null
            }
        if (objects.isEmpty() || objects.last().tag != 0x8E) return null
        val header = byteArrayOf(c.cla.toByte(), c.ins.toByte(), c.p1.toByte(), c.p2.toByte())
        var macInput = CryptoBytes.pad(header, s.cipher.blockSize)
        objects.dropLast(1).forEach { macInput += it.encoded() }
        if (!mac(s, macInput).contentEquals(objects.last().value())) return null

        var data = ByteArray(0)
        var ne: Int? = null
        for (o in objects.dropLast(1)) {
            when (o.tag) {
                0x87 -> data = decrypt(s, o.value().copyOfRange(1, o.length)) ?: return null
                0x85 -> data = decrypt(s, o.value()) ?: return null
                0x97 -> ne = leOf(o.value()) ?: return null
                else -> return null
            }
        }
        return CommandApdu(c.cla and 0x0C.inv(), c.ins, c.p1, c.p2, data, ne)
    }

    private fun wrap(
        s: Session,
        ins: Int,
        answer: ResponseApdu,
    ): ResponseApdu {
        var body = ByteArray(0)
        if (answer.data.isNotEmpty()) {
            val enc = encrypt(s, CryptoBytes.pad(answer.data, s.cipher.blockSize))
            body += if (ins and 1 == 1) TlvWriter.encode(0x85, enc) else TlvWriter.encode(0x87, byteArrayOf(1) + enc)
        }
        body += TlvWriter.encode(0x99, byteArrayOf((answer.sw shr 8).toByte(), answer.sw.toByte()))
        val cc = mac(s, body)
        if (corruptNextResponseMac) {
            corruptNextResponseMac = false
            cc[0] = (cc[0].toInt() xor 1).toByte()
        }
        return ResponseApdu(body + TlvWriter.encode(0x8E, cc), answer.sw)
    }

    private fun iv(s: Session): ByteArray =
        if (s.cipher == SmCipher.DESEDE) {
            ByteArray(8)
        } else {
            crypto.encrypt(BlockCipher.AES, CipherMode.ECB, s.kEnc, null, s.ssc)
        }

    private fun encrypt(
        s: Session,
        padded: ByteArray,
    ) = crypto.encrypt(s.cipher.blockCipher, CipherMode.CBC, s.kEnc, iv(s), padded)

    private fun decrypt(
        s: Session,
        enc: ByteArray,
    ): ByteArray? {
        if (enc.isEmpty() || enc.size % s.cipher.blockSize != 0) return null
        return CryptoBytes.unpad(crypto.decrypt(s.cipher.blockCipher, CipherMode.CBC, s.kEnc, iv(s), enc))
    }

    private fun mac(
        s: Session,
        message: ByteArray,
    ): ByteArray {
        val n = CryptoBytes.pad(s.ssc + message, s.cipher.blockSize)
        return if (s.cipher == SmCipher.DESEDE) crypto.retailMac(s.kMac, n) else crypto.cmac(s.kMac, n).copyOf(8)
    }

    private fun leOf(v: ByteArray): Int? =
        when (v.size) {
            1 -> (v[0].toInt() and 0xFF).let { if (it == 0) 256 else it }
            2 -> (((v[0].toInt() and 0xFF) shl 8) or (v[1].toInt() and 0xFF)).let { if (it == 0) 65_536 else it }
            else -> null
        }

    private fun increment(ssc: ByteArray) {
        var i = ssc.size - 1
        while (i >= 0) {
            ssc[i] = (ssc[i] + 1).toByte()
            if (ssc[i] != 0.toByte()) return
            i--
        }
    }

    private companion object {
        /** GET CHALLENGE, EXTERNAL AUTHENTICATE, MSE, GENERAL AUTHENTICATE: open before access. */
        val ACCESS_PROTOCOL_INS = setOf(0x84, 0x82, 0x22, 0x86)
    }
}
