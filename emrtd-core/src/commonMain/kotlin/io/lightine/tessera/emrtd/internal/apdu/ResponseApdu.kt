package io.lightine.tessera.emrtd.internal.apdu

/**
 * A response APDU: the response data and the two-byte status word (ISO/IEC 7816-4, section 5.1).
 */
internal class ResponseApdu(
    data: ByteArray,
    val sw: Int,
) {
    val data: ByteArray = data.copyOf()

    init {
        require(sw in 0..0xFFFF) { "status word out of range" }
    }

    val sw1: Int get() = sw shr 8
    val sw2: Int get() = sw and 0xFF

    /** Encodes data then status word, as a chip sends it. */
    fun encode(): ByteArray = data + byteArrayOf((sw shr 8).toByte(), sw.toByte())

    override fun toString(): String = "ResponseApdu(nr=${data.size}, sw=${StatusWord.hex(sw)})"

    internal companion object {
        /** Splits raw answer bytes; throws [MalformedResponse] when there is no status word. */
        fun parse(bytes: ByteArray): ResponseApdu {
            if (bytes.size < 2) throw MalformedResponse(bytes.size)
            val n = bytes.size
            return ResponseApdu(bytes.copyOfRange(0, n - 2), (bytes[n - 2].u() shl 8) or bytes[n - 1].u())
        }
    }
}

/** The chip's answer was shorter than the two status-word bytes. */
internal class MalformedResponse(
    val length: Int,
) : IllegalStateException("The chip's answer has $length bytes, fewer than a status word")

/**
 * Status words the chip protocol code acts on (ISO/IEC 7816-4, section 5.6, and Doc 9303 Part 11).
 *
 * Telemetry and errors may carry a status word: it is protocol state, not document data.
 */
internal object StatusWord {
    const val SUCCESS = 0x9000

    /** Warning: end of file reached before Ne bytes were read. The data that exists is returned. */
    const val END_OF_FILE_BEFORE_NE = 0x6282
    const val WRONG_LENGTH = 0x6700
    const val SM_NOT_SUPPORTED = 0x6882
    const val LAST_COMMAND_OF_CHAIN_EXPECTED = 0x6883
    const val CHAINING_NOT_SUPPORTED = 0x6884
    const val SECURITY_STATUS_NOT_SATISFIED = 0x6982
    const val AUTHENTICATION_METHOD_BLOCKED = 0x6983
    const val CONDITIONS_OF_USE_NOT_SATISFIED = 0x6985
    const val NO_CURRENT_EF = 0x6986
    const val SM_DATA_OBJECTS_MISSING = 0x6987
    const val SM_DATA_OBJECTS_INCORRECT = 0x6988
    const val WRONG_DATA = 0x6A80
    const val FILE_NOT_FOUND = 0x6A82
    const val INCORRECT_P1_P2 = 0x6A86
    const val WRONG_P1_P2 = 0x6B00
    const val INS_NOT_SUPPORTED = 0x6D00
    const val CLA_NOT_SUPPORTED = 0x6E00

    /** SW1 61: SW2 more bytes are waiting; fetch them with GET RESPONSE. */
    const val SW1_BYTES_REMAINING = 0x61

    /** SW1 6C: wrong Le; SW2 is the exact length to ask for. */
    const val SW1_WRONG_LE = 0x6C

    /** True for 9000 and for 6282, the end-of-file warning that still carries the data read. */
    fun isSuccess(sw: Int): Boolean = sw == SUCCESS || sw == END_OF_FILE_BEFORE_NE

    fun hex(sw: Int): String = sw.toString(16).uppercase().padStart(4, '0')
}
