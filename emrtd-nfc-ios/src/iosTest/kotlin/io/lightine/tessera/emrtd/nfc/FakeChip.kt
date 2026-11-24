package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.types.errors.NfcError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A scripted chip for whole reads in the iOS tests, with no cryptography: a plain chip (ICAO Doc
 * 9303 Part 11, section 4.1, Note 1) that lets the reader read EF.COM and DG1 in the clear after a
 * card access number key, as the shared reader does when the chip offers no PACE and the key cannot
 * run BAC. With [bacRefusesKey] it answers GET CHALLENGE and refuses EXTERNAL AUTHENTICATE with
 * 6300, so an MRZ key ends as "key not accepted".
 *
 * The emrtd-core fake chip lives in that module's test sources, which another module cannot use
 * without publishing test code; this one needs only plain SELECT and READ BINARY. Every byte is
 * synthetic: fillers and tags, no document values.
 *
 * Scripting, by command number from 1 (counted over the chip's whole life, every link):
 * - [loseAt]: those commands fail with [lossError], as a lost frame does.
 * - [silentFrom]: from that command on, the chip never answers (the callback is kept in [held]).
 * - [answerDelayMillis]: each answer comes that long after the command, on [scope] (test time).
 */
internal class FakeChip(
    private val scope: CoroutineScope? = null,
    private val answerDelayMillis: Long = 0,
    private val bacRefusesKey: Boolean = false,
    dg1Size: Int = 400,
    override val initialSelectedAid: String? = null,
) : Iso7816Channel {
    override val supportsPace: Boolean? = null

    val sent = mutableListOf<ByteArray>()
    val loseAt = mutableSetOf<Int>()
    var lossError: NfcError = io.lightine.tessera.types.errors.NfcTagLost
    var silentFrom: Int? = null
    val held = mutableListOf<(ByteArray?, NfcError?) -> Unit>()

    /** How many times a link to the chip was set up (each tag connect powers it up again). */
    var powerUps = 0
        private set

    private val efCom =
        tlv(
            0x60,
            tlv(0x5F01, "0107".encodeToByteArray()) + tlv(0x5F36, "040000".encodeToByteArray()) + tlv(0x5C, byteArrayOf(0x61)),
        )
    private val dg1 = tlv(0x61, tlv(0x5F1F, ByteArray(dg1Size) { '<'.code.toByte() }))
    private val application = mapOf(0x011E to efCom, 0x0101 to dg1)

    private var inApplication = false
    private var current: ByteArray? = null

    /** A new link: the chip is powered again and nothing is selected. */
    fun powerUp() {
        powerUps++
        inApplication = false
        current = null
    }

    override fun send(
        command: ByteArray,
        onDone: (answer: ByteArray?, error: NfcError?) -> Unit,
    ) {
        sent += command
        val number = sent.size
        val silent = silentFrom
        if (silent != null && number >= silent) {
            held += onDone
            return
        }
        val answer: () -> Unit =
            if (number in loseAt) {
                { onDone(null, lossError) }
            } else {
                val bytes = answer(command)
                ({ onDone(bytes, null) })
            }
        val runner = scope
        if (runner == null || answerDelayMillis == 0L) {
            answer()
        } else {
            runner.launch {
                delay(answerDelayMillis)
                answer()
            }
        }
    }

    private fun answer(c: ByteArray): ByteArray {
        val ins = c[1].toInt() and 0xFF
        val p1 = c[2].toInt() and 0xFF
        val p2 = c[3].toInt() and 0xFF
        return when (ins) {
            INS_SELECT -> select(c, p1)
            INS_READ_BINARY -> readBinary(c, p1, p2)
            INS_GET_CHALLENGE -> if (bacRefusesKey) ByteArray(8) { (it + 1).toByte() } + OK else SW_NOT_SUPPORTED
            INS_EXTERNAL_AUTHENTICATE -> SW_KEY_NOT_MATCHED
            else -> SW_NOT_SUPPORTED
        }
    }

    private fun select(
        c: ByteArray,
        p1: Int,
    ): ByteArray {
        val lc = if (c.size > 4) c[4].toInt() and 0xFF else 0
        val data = if (lc > 0 && c.size >= 5 + lc) c.copyOfRange(5, 5 + lc) else ByteArray(0)
        return when {
            p1 == SELECT_BY_AID && data.contentEquals(AID) -> {
                inApplication = true
                current = null
                OK
            }

            p1 == SELECT_BY_AID -> {
                SW_NOT_FOUND
            }

            data.isEmpty() || data.contentEquals(byteArrayOf(0x3F, 0x00)) -> {
                inApplication = false
                current = null
                OK
            }

            data.size == 2 -> {
                val fid = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                val file = if (inApplication) application[fid] else null
                current = file
                if (file == null) SW_NOT_FOUND else OK
            }

            else -> {
                SW_NOT_FOUND
            }
        }
    }

    private fun readBinary(
        c: ByteArray,
        p1: Int,
        p2: Int,
    ): ByteArray {
        val file = current ?: return SW_NO_CURRENT_EF
        if (p1 and 0x80 != 0) return SW_NOT_FOUND
        val offset = (p1 shl 8) or p2
        val le = if (c.size > 4) (c.last().toInt() and 0xFF).let { if (it == 0) 256 else it } else 256
        if (offset > file.size) return SW_WRONG_OFFSET
        val end = minOf(file.size, offset + le)
        return file.copyOfRange(offset, end) + OK
    }

    companion object {
        private const val INS_SELECT = 0xA4
        private const val INS_READ_BINARY = 0xB0
        private const val INS_GET_CHALLENGE = 0x84
        private const val INS_EXTERNAL_AUTHENTICATE = 0x82
        private const val SELECT_BY_AID = 0x04
        val AID = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x02, 0x47, 0x10, 0x01)
        val OK = byteArrayOf(0x90.toByte(), 0x00)
        val SW_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        val SW_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
        val SW_KEY_NOT_MATCHED = byteArrayOf(0x63, 0x00)
        val SW_NO_CURRENT_EF = byteArrayOf(0x69, 0x86.toByte())
        val SW_WRONG_OFFSET = byteArrayOf(0x6B, 0x00)

        fun tlv(
            tag: Int,
            value: ByteArray,
        ): ByteArray {
            val tagBytes = if (tag > 0xFF) byteArrayOf((tag shr 8).toByte(), tag.toByte()) else byteArrayOf(tag.toByte())
            val n = value.size
            val length =
                when {
                    n < 0x80 -> byteArrayOf(n.toByte())
                    n <= 0xFF -> byteArrayOf(0x81.toByte(), n.toByte())
                    else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
                }
            return tagBytes + length + value
        }
    }
}

/** A tag for [FakeChip]: each connect is a new link, so the chip starts with nothing selected. */
internal class FakeChipTag(
    private val chip: FakeChip,
    private val connectFailure: ConnectFailure? = null,
) : DetectedTag {
    override val iso7816: Iso7816Channel = chip
    var connectCalls = 0

    override fun connect(onDone: (failure: ConnectFailure?) -> Unit) {
        connectCalls++
        if (connectFailure == null) chip.powerUp()
        onDone(connectFailure)
    }
}
