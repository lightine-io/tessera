package io.lightine.tessera.emrtd.fakechip

import io.lightine.tessera.emrtd.internal.apdu.LdsIds
import io.lightine.tessera.emrtd.internal.tlv.TlvWriter
import io.lightine.tessera.mrz.generation.GenerationResult
import io.lightine.tessera.mrz.generation.MrzGenerator
import io.lightine.tessera.types.vocabulary.Sex
import kotlinx.datetime.LocalDate

/**
 * Personalizes fake chips at test time from the SDK's own generator: the project specimen
 * (SPECIMEN, ALEX MORGAN, Utopia). Nothing here comes from a real document.
 */
internal object FakeChipData {
    /** The specimen TD3 MRZ lines, generated. */
    fun specimenTd3Lines(): List<String> {
        val result =
            MrzGenerator.generateTD3(
                documentType = "P",
                issuingState = "UTO",
                documentNumber = "T22000129",
                primaryIdentifier = "SPECIMEN",
                secondaryIdentifier = "ALEX MORGAN",
                nationality = "UTO",
                dateOfBirth = LocalDate(1990, 3, 15),
                sex = Sex.FEMALE,
                dateOfExpiry = LocalDate(2034, 3, 14),
                personalNumber = "S98765432",
            )
        return (result as GenerationResult.Success).mrz
    }

    /** EF.DG1: tag 61 holding the MRZ data object 5F1F (Part 10, section 4.7.1). */
    fun dg1(mrzLines: List<String>): ByteArray {
        val mrz = mrzLines.joinToString("").encodeToByteArray()
        return TlvWriter.constructed(0x61, TlvWriter.encode(0x5F1F, mrz))
    }

    /**
     * EF.COM: tag 60 with the LDS version (5F01), the Unicode version (5F36) and the tag list (5C)
     * of the data groups present (Part 10, section 4.6.1).
     */
    fun efCom(dataGroupTags: List<Int>): ByteArray =
        TlvWriter.constructed(
            0x60,
            TlvWriter.encode(0x5F01, "0108".encodeToByteArray()),
            TlvWriter.encode(0x5F36, "040000".encodeToByteArray()),
            TlvWriter.encode(0x5C, ByteArray(dataGroupTags.size) { dataGroupTags[it].toByte() }),
        )

    /** A plain chip (no access control) with EF.COM and DG1 in the LDS1 application. */
    fun plainChipFiles(mrzLines: List<String> = specimenTd3Lines()): FakeFileSystem =
        FakeFileSystem(
            masterFiles = emptyMap(),
            applications =
                mapOf(
                    LdsIds.LDS1_AID.toList() to
                        mapOf(
                            LdsIds.EF_COM to efCom(listOf(0x61)),
                            LdsIds.EF_DG1 to dg1(mrzLines),
                        ),
                ),
        )
}
