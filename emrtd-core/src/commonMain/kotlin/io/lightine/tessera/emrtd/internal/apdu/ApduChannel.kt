package io.lightine.tessera.emrtd.internal.apdu

import io.lightine.tessera.emrtd.ApduTransport

/**
 * Sends command APDUs over an [ApduTransport] and returns parsed answers.
 *
 * It encodes short when a command fits, and extended only when both the transport and
 * [extendedLengthAllowed] allow it. Doc 9303 Part 11, section 9.3.1.2: the reader checks the chip's
 * own statement (ATR/ATS or EF.ATR/INFO) before it uses extended length, so the caller that has read
 * that statement turns [extendedLengthAllowed] on.
 *
 * It follows the two ISO/IEC 7816-4 answer-length hints: `61xx` (more bytes waiting: fetch them with
 * GET RESPONSE) and `6Cxx` (wrong Le: send again with Le = xx), each within fixed limits, so a
 * hostile chip cannot keep it looping or make it collect without bound.
 *
 * [ApduTransport] exceptions pass through unchanged.
 */
internal class ApduChannel(
    private val transport: ApduTransport,
    var extendedLengthAllowed: Boolean = false,
) {
    private val extended: Boolean
        get() = extendedLengthAllowed && transport.supportsExtendedLength

    /** Sends one command, following `61xx` and `6Cxx`. */
    suspend fun send(command: CommandApdu): ResponseApdu {
        var response = exchange(command)
        if (response.sw1 == StatusWord.SW1_WRONG_LE) {
            val le = response.sw2.let { if (it == 0) CommandApdu.MAX_SHORT_NE else it }
            response = exchange(CommandApdu(command.cla, command.ins, command.p1, command.p2, command.data, le))
        }
        if (response.sw1 != StatusWord.SW1_BYTES_REMAINING) return response

        var collected = response.data
        var rounds = 0
        while (response.sw1 == StatusWord.SW1_BYTES_REMAINING) {
            if (++rounds > MAX_GET_RESPONSE_ROUNDS) throw ResponseTooLong(collected.size)
            val le = response.sw2.let { if (it == 0) CommandApdu.MAX_SHORT_NE else it }
            response = exchange(CommandApdu(command.cla and CLA_CHANNEL_BITS, INS_GET_RESPONSE, 0, 0, ne = le))
            if (collected.size + response.data.size > MAX_COLLECTED_RESPONSE) {
                throw ResponseTooLong(collected.size + response.data.size)
            }
            collected += response.data
        }
        return ResponseApdu(collected, response.sw)
    }

    /**
     * Sends a chain of commands. Every non-final answer must be 9000; the first other answer ends
     * the chain and is returned. Returns the final command's answer otherwise.
     */
    suspend fun sendChain(commands: List<CommandApdu>): ResponseApdu {
        require(commands.isNotEmpty()) { "empty chain" }
        for (i in 0 until commands.lastIndex) {
            val r = send(commands[i])
            if (r.sw != StatusWord.SUCCESS) return r
        }
        return send(commands.last())
    }

    private suspend fun exchange(command: CommandApdu): ResponseApdu {
        val bytes = command.encode(extended)
        if (bytes.size > transport.maxCommandLength) throw CommandTooLong(bytes.size, transport.maxCommandLength)
        return ResponseApdu.parse(transport.transceive(bytes))
    }

    internal companion object {
        const val INS_GET_RESPONSE = 0xC0

        // The logical-channel and secure-messaging bits of CLA carry over to GET RESPONSE; the
        // chaining bit does not.
        const val CLA_CHANNEL_BITS = 0x0F

        /** At most this many GET RESPONSE rounds for one command. */
        const val MAX_GET_RESPONSE_ROUNDS = 300

        /** At most this many answer bytes collected for one command: one extended answer. */
        const val MAX_COLLECTED_RESPONSE = CommandApdu.MAX_EXTENDED_NE
    }
}

/** The encoded command is longer than the transport can send. */
internal class CommandTooLong(
    val length: Int,
    val max: Int,
) : IllegalStateException("The command has $length bytes; the transport sends at most $max")

/** The chip kept announcing more answer bytes past the limit. */
internal class ResponseTooLong(
    val collected: Int,
) : IllegalStateException("The chip's answer grew past the limit at $collected bytes")
