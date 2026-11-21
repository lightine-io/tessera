package io.lightine.tessera.emrtd.internal.apdu

/**
 * A command APDU (ISO/IEC 7816-4, section 5.1): header, optional command data, optional expected
 * answer length.
 *
 * [ne] is the expected number of answer bytes: `null` means no Le field (no data expected),
 * 1..256 fits a short Le, up to 65,536 needs extended length. An Le of "all available" is 256 in
 * short form and 65,536 in extended form, encoded as `00` and `00 00`.
 */
internal class CommandApdu(
    val cla: Int,
    val ins: Int,
    val p1: Int,
    val p2: Int,
    data: ByteArray = EMPTY,
    val ne: Int? = null,
) {
    // Defensive copy: the caller may wipe or reuse its array.
    val data: ByteArray = data.copyOf()

    init {
        require(cla in 0..0xFF && ins in 0..0xFF && p1 in 0..0xFF && p2 in 0..0xFF) { "header byte out of range" }
        require(this.data.size <= MAX_EXTENDED_LC) { "command data longer than $MAX_EXTENDED_LC bytes" }
        require(ne == null || ne in 1..MAX_EXTENDED_NE) { "ne out of range" }
    }

    /** True when the command fits a short APDU: at most 255 data bytes and at most 256 expected. */
    val fitsShort: Boolean
        get() = data.size <= MAX_SHORT_LC && (ne == null || ne <= MAX_SHORT_NE)

    /**
     * Encodes the command, short when it fits, else extended. Throws [ExtendedLengthRequired]
     * when the command needs extended length and [extendedAllowed] is false.
     */
    fun encode(extendedAllowed: Boolean): ByteArray =
        if (fitsShort) {
            encodeShort()
        } else if (extendedAllowed) {
            encodeExtended()
        } else {
            throw ExtendedLengthRequired(data.size, ne)
        }

    /** The encoded length this command will have, short when it fits, else extended. */
    fun encodedLength(): Int {
        val nc = data.size
        val ne = ne
        return if (fitsShort) {
            4 + (if (nc > 0) 1 + nc else 0) + (if (ne != null) 1 else 0)
        } else {
            4 + (if (nc > 0) 3 + nc else 0) + (if (ne != null) (if (nc > 0) 2 else 3) else 0)
        }
    }

    private fun encodeShort(): ByteArray {
        val out = ArrayList<Byte>(5 + data.size)
        header(out)
        if (data.isNotEmpty()) {
            out.add(data.size.toByte())
            data.forEach { out.add(it) }
        }
        ne?.let { out.add((if (it == MAX_SHORT_NE) 0 else it).toByte()) }
        return out.toByteArray()
    }

    // ISO/IEC 7816-4, section 5.1: extended Lc is 00 followed by two bytes; extended Le is two bytes
    // after an extended Lc, or 00 followed by two bytes when there is no command data.
    private fun encodeExtended(): ByteArray {
        val out = ArrayList<Byte>(9 + data.size)
        header(out)
        if (data.isNotEmpty()) {
            out.add(0)
            out.add((data.size shr 8).toByte())
            out.add(data.size.toByte())
            data.forEach { out.add(it) }
        }
        ne?.let {
            val v = if (it == MAX_EXTENDED_NE) 0 else it
            if (data.isEmpty()) out.add(0)
            out.add((v shr 8).toByte())
            out.add(v.toByte())
        }
        return out.toByteArray()
    }

    private fun header(out: MutableList<Byte>) {
        out.add(cla.toByte())
        out.add(ins.toByte())
        out.add(p1.toByte())
        out.add(p2.toByte())
    }

    /** The same command with another class byte, for command chaining or secure messaging. */
    fun withCla(newCla: Int): CommandApdu = CommandApdu(newCla, ins, p1, p2, data, ne)

    override fun toString(): String = "CommandApdu(ins=${ins.toString(16)}, nc=${data.size}, ne=$ne)"

    internal companion object {
        val EMPTY = ByteArray(0)
        const val MAX_SHORT_LC = 255
        const val MAX_SHORT_NE = 256
        const val MAX_EXTENDED_LC = 65_535
        const val MAX_EXTENDED_NE = 65_536

        /** The class-byte bit that marks a command as one of a chain, not the last (ISO/IEC 7816-4, 5.4.2). */
        const val CLA_CHAINING_BIT = 0x10

        /**
         * Decodes a command APDU, short or extended. Returns `null` when the bytes are no
         * well-formed command (used by the fake chip, which answers such a command with 6700).
         */
        fun decode(bytes: ByteArray): CommandApdu? {
            if (bytes.size < 4) return null
            val cla = bytes[0].u()
            val ins = bytes[1].u()
            val p1 = bytes[2].u()
            val p2 = bytes[3].u()
            val body = bytes.size - 4
            if (body == 0) return CommandApdu(cla, ins, p1, p2)
            if (body == 1) return CommandApdu(cla, ins, p1, p2, ne = shortNe(bytes[4].u()))
            val b0 = bytes[4].u()
            if (b0 != 0) {
                // Short Lc.
                val nc = b0
                return when (body) {
                    1 + nc -> CommandApdu(cla, ins, p1, p2, bytes.copyOfRange(5, 5 + nc))
                    2 + nc -> CommandApdu(cla, ins, p1, p2, bytes.copyOfRange(5, 5 + nc), shortNe(bytes[5 + nc].u()))
                    else -> null
                }
            }
            // b0 == 0 and body >= 2: extended.
            if (body == 3) return CommandApdu(cla, ins, p1, p2, ne = extendedNe(bytes[5].u(), bytes[6].u()))
            if (body < 3) return null
            val nc = (bytes[5].u() shl 8) or bytes[6].u()
            if (nc == 0) return null
            return when (body) {
                3 + nc -> {
                    CommandApdu(cla, ins, p1, p2, bytes.copyOfRange(7, 7 + nc))
                }

                5 + nc -> {
                    CommandApdu(
                        cla,
                        ins,
                        p1,
                        p2,
                        bytes.copyOfRange(7, 7 + nc),
                        extendedNe(bytes[7 + nc].u(), bytes[8 + nc].u()),
                    )
                }

                else -> {
                    null
                }
            }
        }

        private fun shortNe(b: Int): Int = if (b == 0) MAX_SHORT_NE else b

        private fun extendedNe(
            hi: Int,
            lo: Int,
        ): Int {
            val v = (hi shl 8) or lo
            return if (v == 0) MAX_EXTENDED_NE else v
        }
    }
}

/** The command needs extended length, which the transport or the chip does not allow. */
internal class ExtendedLengthRequired(
    val dataLength: Int,
    val ne: Int?,
) : IllegalStateException("The command needs an extended-length APDU, which is not allowed here")

internal fun Byte.u(): Int = toInt() and 0xFF
