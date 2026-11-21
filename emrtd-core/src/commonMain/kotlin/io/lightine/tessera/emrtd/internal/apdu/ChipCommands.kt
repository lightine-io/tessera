package io.lightine.tessera.emrtd.internal.apdu

/**
 * The plain (unprotected) file commands of Doc 9303 Part 10, section 3.6, and the LDS1
 * identifiers they use. Secure messaging wraps these same commands.
 */
internal object ChipCommands {
    const val INS_SELECT = 0xA4
    const val INS_READ_BINARY = 0xB0
    const val INS_READ_BINARY_ODD = 0xB1

    /** SELECT P1: select by application identifier (DF name). */
    const val SELECT_BY_AID = 0x04

    /** SELECT P1: select an EF under the current DF by file identifier. */
    const val SELECT_EF_UNDER_CURRENT_DF = 0x02

    /** SELECT P2: return no file control information. */
    const val SELECT_NO_FCI = 0x0C

    /**
     * SELECT an application by AID: `00 A4 04 0C`, data = AID, no Le (Part 10, section 3.6.1.2).
     * The reader never sends SELECT MF (`00 A4 00 0C`), which Part 10, section 3.6.1.1 advises against.
     */
    fun selectApplication(aid: ByteArray): CommandApdu = CommandApdu(0x00, INS_SELECT, SELECT_BY_AID, SELECT_NO_FCI, aid)

    /** SELECT an elementary file by its two-byte file identifier: `00 A4 02 0C`, data = FID, no Le. */
    fun selectFile(fid: Int): CommandApdu {
        require(fid in 0..0xFFFF) { "fid out of range" }
        return CommandApdu(0x00, INS_SELECT, SELECT_EF_UNDER_CURRENT_DF, SELECT_NO_FCI, byteArrayOf((fid shr 8).toByte(), fid.toByte()))
    }

    /** SELECT the master file: `00 A4 00 0C 02 3F 00`. */
    fun selectMasterFile(): CommandApdu = CommandApdu(0x00, INS_SELECT, 0x00, SELECT_NO_FCI, byteArrayOf(0x3F, 0x00))

    /** READ BINARY of the current EF at a 15-bit [offset]: `00 B0 P1 P2 Le`. */
    fun readBinary(
        offset: Int,
        ne: Int,
    ): CommandApdu {
        require(offset in 0..0x7FFF) { "offset out of range" }
        return CommandApdu(0x00, INS_READ_BINARY, offset shr 8, offset and 0xFF, ne = ne)
    }

    /**
     * READ BINARY with a short file identifier: selects the EF and reads it from an 8-bit [offset]
     * in one command (`P1 = 100xxxxx`).
     */
    fun readBinaryBySfi(
        sfi: Int,
        offset: Int,
        ne: Int,
    ): CommandApdu {
        require(sfi in 1..30) { "sfi out of range" }
        require(offset in 0..0xFF) { "offset out of range" }
        return CommandApdu(0x00, INS_READ_BINARY, 0x80 or sfi, offset, ne = ne)
    }

    /**
     * READ BINARY with an extended offset (ISO/IEC 7816-4): `00 B1 00 00`, data object `54`
     * containing the unsigned offset, and [ne].
     */
    fun readBinaryExtendedOffset(
        offset: Int,
        ne: Int,
    ): CommandApdu {
        require(offset > 0x7FFF) { "extended offset must be above 32767" }
        val value =
            when {
                offset <= 0xFF -> byteArrayOf(offset.toByte())
                offset <= 0xFFFF -> byteArrayOf((offset shr 8).toByte(), offset.toByte())
                offset <= 0xFFFFFF -> byteArrayOf((offset shr 16).toByte(), (offset shr 8).toByte(), offset.toByte())
                else -> byteArrayOf((offset shr 24).toByte(), (offset shr 16).toByte(), (offset shr 8).toByte(), offset.toByte())
            }
        val data = byteArrayOf(0x54, value.size.toByte()) + value
        return CommandApdu(0x00, INS_READ_BINARY_ODD, 0x00, 0x00, data, ne)
    }
}

/** LDS1 identifiers from Doc 9303 Part 10 (sections 3.10 and 4). */
internal object LdsIds {
    /** The LDS1 eMRTD application: `A0 00 00 02 47 10 01`. */
    val LDS1_AID: ByteArray
        get() = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x02, 0x47, 0x10, 0x01)

    /** EF.CardAccess, in the master file. */
    const val EF_CARD_ACCESS = 0x011C

    /** EF.COM, in the eMRTD application. */
    const val EF_COM = 0x011E

    /** EF.SOD, in the eMRTD application. */
    const val EF_SOD = 0x011D

    /** EF.DG1 (the MRZ), in the eMRTD application. DGn is `0x0100 + n`. */
    const val EF_DG1 = 0x0101

    /** The short file identifier of an LDS1 file: the low five bits of its file identifier. */
    fun sfi(fid: Int): Int = fid and 0x1F
}
