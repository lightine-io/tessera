package io.lightine.tessera.emrtd.sim

import io.lightine.tessera.emrtd.ChipCheckKind
import io.lightine.tessera.emrtd.ChipRead
import io.lightine.tessera.emrtd.ChipReadResult
import io.lightine.tessera.emrtd.ChipReader
import io.lightine.tessera.emrtd.fakechip.DeviceChipProfile
import io.lightine.tessera.emrtd.fakechip.FakeChip
import io.lightine.tessera.emrtd.fakechip.FakeCommandHandler
import io.lightine.tessera.emrtd.fakechip.FakeFileSystem
import io.lightine.tessera.emrtd.fakechip.FakeFiles
import io.lightine.tessera.emrtd.fakechip.FakeProtectedFile
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FakeReadSpy
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.types.errors.ChipAccessDenied
import io.lightine.tessera.types.errors.ChipBlank
import io.lightine.tessera.types.errors.ChipError
import io.lightine.tessera.types.errors.ChipFileNotFound
import io.lightine.tessera.types.errors.ChipMalformedFile
import io.lightine.tessera.types.errors.ChipUnexpectedAnswer
import io.lightine.tessera.types.vocabulary.CheckOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Catalogue group C (file reading), card TES-346: status words, short and long files, the odd-INS
 * read, declared versus real length, and the command counts of the device-shaped chip, through
 * [ChipReader] on BAC fakes. Sources: ICAO TR RF and Protocol Testing Part 4 v3.0 (P4) unit
 * ISO7816_F; Doc 9303 Part 10 (P10), 3.6.3.1 and 4.6.
 */
class CatalogueGroupCTest {
    private val dg1 = 0x0101
    private val dg2 = 0x0102

    private fun offset(c: CommandApdu): Int = (c.p1 shl 8) or c.p2

    private fun isRead(c: CommandApdu) = c.cla == 0x00 && c.ins == 0xB0 && c.p1 and 0x80 == 0

    /** A plain (inner) command handler; goes in `extraHandlers`, after secure messaging. */
    private fun inner(block: (FakeChip, CommandApdu, FakeCommandHandler) -> ResponseApdu?): FakeCommandHandler =
        object : FakeCommandHandler {
            override fun handle(
                chip: FakeChip,
                command: CommandApdu,
            ): ResponseApdu? = block(chip, command, this)
        }

    private fun selects(
        c: CommandApdu,
        fid: Int,
    ) = c.ins == 0xA4 && c.data.size == 2 && ((c.data[0].toInt() and 0xFF) shl 8 or (c.data[1].toInt() and 0xFF)) == fid

    private fun bac(
        c: ChipCrypto,
        keys: TestKeys,
        vararg handlers: FakeCommandHandler,
        dg2Bytes: Int = 900,
    ): FlowChip = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, extraHandlers = handlers.toList(), dg2Bytes = dg2Bytes)

    private fun complete(r: ChipReadResult): ChipRead = assertIs<ChipReadResult.Complete>(r, "$r").read

    private fun errorOf(
        read: ChipRead,
        fid: Int,
    ): ChipError = assertNotNull(read.fileErrors.firstOrNull { it.fileId == fid }, "${read.fileErrors}").error

    private fun hash(
        read: ChipRead,
        n: Int,
    ) = read.outcomeOf(ChipCheckKind.DataGroupHashMatches(n))

    // --- Status words at READ BINARY and SELECT ------------------------------------------------------

    @Test
    fun c04_aReadPastTheEndAnswered6B00_isAFileErrorWithTheWordAndTheReadGoesOn() =
        runTest {
            Sim.withChips { c, keys ->
                val flow =
                    bac(
                        c,
                        keys,
                        inner { chip, cmd, _ ->
                            if (isRead(cmd) && chip.currentEf == dg2 &&
                                offset(cmd) > 0
                            ) {
                                ResponseApdu(ByteArray(0), 0x6B00)
                            } else {
                                null
                            }
                        },
                    )
                val read = complete(Sim.read(flow))
                assertEquals(ChipUnexpectedAnswer(ChipUnexpectedAnswer.Reason.UNEXPECTED_STATUS_WORD, 0x6B00), errorOf(read, dg2))
                assertEquals(CheckOutcome.Passed, hash(read, 1), "DG1 still read and checked")
            }
        }

    /**
     * C-05 and C-22, OPEN DECISION (P4 ISO7816_F_05 says the inspection fails; catalogue "Open
     * decisions"). A chip answers 6282 (end of file before Ne) well before the length its outer TLV
     * declares. Today the file reader keeps the bytes received and returns the file SHORT without a
     * reader error of its own: the facts that show it are the hash (HASH_DIFFERS), the file's size,
     * and, for a group with a typed view (DG2 here), the view's parse failure (NOT_VALID_TLV at the
     * cut). A group kept raw, with no view, would show only the hash. Pinned here at the current
     * behaviour; the options are in the TES-346 report.
     */
    @Test
    fun c05_c22_a6282BeforeTheDeclaredEnd_OPEN_DECISION_returnsTheFileShortWithOnlyTheHashFact() =
        runTest {
            Sim.withChips { c, keys ->
                val cut = 500
                val flow =
                    bac(
                        c,
                        keys,
                        inner { chip, cmd, self ->
                            if (isRead(cmd) && chip.currentEf == dg2 && offset(cmd) + (cmd.ne ?: 0) > cut) {
                                val real = chip.answerInner(cmd, self)
                                ResponseApdu(real.data.copyOf(maxOf(0, cut - offset(cmd))), 0x6282)
                            } else {
                                null
                            }
                        },
                    )
                val read = complete(Sim.read(flow))
                val file = read.files.single { it.fileId == dg2 }
                assertEquals(cut, file.bytes().size, "the file is returned short")
                val error = assertIs<ChipMalformedFile>(errorOf(read, dg2), "only the view's parse failure, no reader error")
                assertEquals(ChipMalformedFile.Reason.NOT_VALID_TLV, error.reason)
                val outcome = assertIs<CheckOutcome.Failed>(hash(read, 2))
                assertTrue("HASH_DIFFERS" in outcome.toString(), "$outcome")
            }
        }

    @Test
    fun c25_selectOfAnApplicationFileAnsweredWithAnOddWord_isAFileErrorAndTheReadGoesOn() =
        runTest {
            Sim.withChips { c, keys ->
                for (sw in listOf(0x6700, 0x6A86, 0x6D00, 0x6E00, 0x6F00, 0x6981, 0x6985, 0x6986)) {
                    val flow = bac(c, keys, inner { _, cmd, _ -> if (selects(cmd, dg2)) ResponseApdu(ByteArray(0), sw) else null })
                    val read = complete(Sim.read(flow))
                    assertEquals(
                        ChipUnexpectedAnswer(ChipUnexpectedAnswer.Reason.UNEXPECTED_STATUS_WORD, sw),
                        errorOf(read, dg2),
                        sw.toString(16),
                    )
                    assertEquals(CheckOutcome.Passed, hash(read, 1))
                }
            }
        }

    @Test
    fun c25_selectOfTheApplicationAnsweredWithAnOddWord_failsBeforeAccess() =
        runTest {
            Sim.withChips { c, keys ->
                for (sw in listOf(0x6700, 0x6D00, 0x6E00, 0x6F00, 0x6985)) {
                    val flow = bac(c, keys, Sim.answer(0x00, 0xA4, sw, p1 = 0x04))
                    val error = Sim.failed(Sim.read(flow))
                    assertTrue(error !is ChipBlank || sw == 0x6A82, "${sw.toString(16)}: $error")
                    assertEquals(0, flow.chip.received.count { it[1] == 0x82.toByte() }, "no key tried: ${sw.toString(16)}")
                }
            }
        }

    @Test
    fun c27_select6982ForEfSodAfterAccess_isFileProtectedAndTheReadGoesOn() =
        runTest {
            Sim.withChips { c, keys ->
                val flow = bac(c, keys, FakeProtectedFile(0x011D))
                val read = complete(Sim.read(flow))
                val error = assertIs<ChipAccessDenied>(errorOf(read, 0x011D))
                assertEquals(ChipAccessDenied.Reason.FILE_PROTECTED, error.reason)
                assertNotNull(read.dg1, "DG1 still read")
            }
        }

    @Test
    fun c28_read6982InTheMiddleOfAFile_isFileProtectedAndTheNextFileIsRead() =
        runTest {
            Sim.withChips { c, keys ->
                val flow =
                    bac(
                        c,
                        keys,
                        inner { chip, cmd, _ ->
                            if (isRead(cmd) && chip.currentEf == dg1 &&
                                offset(cmd) > 0
                            ) {
                                ResponseApdu(ByteArray(0), 0x6982)
                            } else {
                                null
                            }
                        },
                        dg2Bytes = 900,
                    )
                // DG1 is one chunk; put the 6982 in DG2 instead, which needs several.
                val flow2 =
                    bac(
                        c,
                        keys,
                        inner { chip, cmd, _ ->
                            if (isRead(cmd) && chip.currentEf == dg2 &&
                                offset(cmd) > 0
                            ) {
                                ResponseApdu(ByteArray(0), 0x6982)
                            } else {
                                null
                            }
                        },
                    )
                val read = complete(Sim.read(flow2, dataGroups = setOf(2, 1)))
                assertEquals(ChipAccessDenied.Reason.FILE_PROTECTED, assertIs<ChipAccessDenied>(errorOf(read, dg2)).reason)
                assertTrue(read.files.any { it.fileId == dg1 }, "the other file is still read")
                assertEquals(CheckOutcome.Passed, hash(complete(Sim.read(flow)), 2))
            }
        }

    @Test
    fun c18_aFirstReadAnswering9000WithNoBytes_isAMalformedFileAndTheReadGoesOn() =
        runTest {
            Sim.withChips { c, keys ->
                val flow =
                    bac(
                        c,
                        keys,
                        inner { chip, cmd, _ ->
                            if (isRead(cmd) && chip.currentEf == dg2 &&
                                offset(cmd) == 0
                            ) {
                                ResponseApdu(ByteArray(0), 0x9000)
                            } else {
                                null
                            }
                        },
                    )
                val read = complete(Sim.read(flow))
                assertIs<ChipMalformedFile>(errorOf(read, dg2))
                assertEquals(CheckOutcome.Passed, hash(read, 1))
            }
        }

    // --- Short and capped answers ---------------------------------------------------------------------

    @Test
    fun c11_aChipThatCapsItsAnswerAt160Bytes_isReadOnFromTheRealCount() =
        runTest {
            Sim.withChips { c, keys ->
                val cap = 160
                val flow =
                    bac(
                        c,
                        keys,
                        inner { chip, cmd, self ->
                            val ne = cmd.ne
                            if (isRead(cmd) && ne != null && ne > cap) {
                                val real = chip.answerInner(CommandApdu(cmd.cla, cmd.ins, cmd.p1, cmd.p2, cmd.data, cap), self)
                                ResponseApdu(real.data, if (real.sw == 0x6282) 0x9000 else real.sw)
                            } else {
                                null
                            }
                        },
                        dg2Bytes = 4000,
                    )
                val read = complete(Sim.read(flow))
                assertEquals(CheckOutcome.Passed, hash(read, 2), "DG2 joined from 160-byte answers")
                assertTrue(read.fileErrors.isEmpty(), "${read.fileErrors}")
            }
        }

    /**
     * C-11, gap pinned at today's behaviour ([JDG] in the catalogue): a chip that answers 6700 to a
     * read of 0xDF bytes gets no retry with a smaller Le. The file is a typed file error; the read
     * goes on.
     */
    @Test
    fun c11_aChipThatAnswers6700ToTheReadSize_isAFileErrorWithNoSmallerRetry() =
        runTest {
            Sim.withChips { c, keys ->
                val flow =
                    bac(
                        c,
                        keys,
                        inner { chip, cmd, _ ->
                            if (isRead(cmd) && chip.currentEf == dg2 &&
                                (cmd.ne ?: 0) > 160
                            ) {
                                ResponseApdu(ByteArray(0), 0x6700)
                            } else {
                                null
                            }
                        },
                    )
                val read = complete(Sim.read(flow))
                assertEquals(ChipUnexpectedAnswer(ChipUnexpectedAnswer.Reason.UNEXPECTED_STATUS_WORD, 0x6700), errorOf(read, dg2))
            }
        }

    /**
     * C-12 ([JDG]): the app and the transport allow extended length and EF.ATR/INFO says the chip
     * takes it, but the chip answers 6700 to an extended read. Today: a file error for that file, no
     * fall back to short reads; the read goes on.
     */
    @Test
    fun c12_aChipThatRefusesTheExtendedLengthItAnnounced_isAFileErrorWithNoFallback() =
        runTest {
            Sim.withChips { c, keys ->
                val refuseExtended =
                    inner { _, cmd, _ ->
                        if (cmd.ins == 0xB0 &&
                            (cmd.ne ?: 0) > 256
                        ) {
                            ResponseApdu(ByteArray(0), 0x6700)
                        } else {
                            null
                        }
                    }
                val base = bac(c, keys, refuseExtended, dg2Bytes = 4000)
                // EF.ATR/INFO (P10 3.10): card capabilities with extended Lc and Le (third byte E0).
                val atrInfo = byteArrayOf(0x47, 0x03, 0x04, 0x01, 0xE0.toByte())
                val chip =
                    FakeChip(
                        FakeFileSystem(base.chip.files.masterFiles + (0x2F01 to atrInfo), base.chip.files.applications),
                        maxCommandLength = FakeChip.EXTENDED_MAX_COMMAND,
                        supportsExtendedLength = true,
                        handlers = base.chip.handlers,
                    )
                val result = ChipReader(NoOpTelemetrySink).read(chip, Sim.options(base))
                val read = complete(result)
                assertEquals(ChipUnexpectedAnswer(ChipUnexpectedAnswer.Reason.UNEXPECTED_STATUS_WORD, 0x6700), errorOf(read, dg2))
                assertTrue(chip.received.any { (it[1].toInt() and 0xFF) == 0xB0 && it.size > 7 }, "an extended read was asked for")
            }
        }

    // --- Large files and the 32 767-byte boundary ------------------------------------------------------

    private val dg2Overhead: Int = FakeReadFlowChip.dg2(1000).size - 1000

    @Test
    fun c09_filesAroundThe32767ByteBoundary_areReadWholeWithB0ThenB1() =
        runTest {
            Sim.withChips { c, keys ->
                for (size in listOf(32_766, 32_767, 32_768, 32_769, 40_000)) {
                    val flow = bac(c, keys, FakeFiles, dg2Bytes = size - dg2Overhead)
                    assertEquals(size, flow.dataGroups.getValue(2).size)
                    val read = complete(Sim.read(flow))
                    assertEquals(CheckOutcome.Passed, hash(read, 2), "size $size")
                    val b1 = flow.chip.received.count { (it[1].toInt() and 0xFF) == 0xB1 }
                    assertEquals(size > 32_768, b1 > 0, "B1 only past offset 32 767 (size $size)")
                }
            }
        }

    @Test
    fun c10_aChipWithoutOddInsAndAFileOver32767Bytes_isAFileErrorWithTheWordAndTheReadGoesOn() =
        runTest {
            Sim.withChips { c, keys ->
                val noOddIns = inner { _, cmd, _ -> if (cmd.ins == 0xB1) ResponseApdu(ByteArray(0), 0x6D00) else null }
                val flow = bac(c, keys, noOddIns, dg2Bytes = 40_000)
                val read = complete(Sim.read(flow))
                assertEquals(ChipUnexpectedAnswer(ChipUnexpectedAnswer.Reason.UNEXPECTED_STATUS_WORD, 0x6D00), errorOf(read, dg2))
                assertEquals(CheckOutcome.Passed, hash(read, 1))
            }
        }

    @Test
    fun c08_dg2HoldingAnImageOfSizeZero_isReadWithoutException() =
        runTest {
            Sim.withChips { c, keys ->
                val flow = bac(c, keys, dg2Bytes = 0)
                val read = complete(Sim.read(flow))
                assertEquals(CheckOutcome.Passed, hash(read, 2))
            }
        }

    // --- Missing and protected files ----------------------------------------------------------------

    @Test
    fun c14_efComMissingAndEfSodAndDataGroupsProtected_endsCompleteWithEveryErrorAndNoVerdict() =
        runTest {
            Sim.withChips { c, keys ->
                val efComMissing = inner { _, cmd, _ -> if (selects(cmd, 0x011E)) ResponseApdu(ByteArray(0), 0x6A82) else null }
                val flow = bac(c, keys, efComMissing, FakeProtectedFile(0x011D), FakeProtectedFile(dg1), FakeProtectedFile(dg2))
                val read = complete(Sim.read(flow))
                assertIs<ChipFileNotFound>(errorOf(read, 0x011E))
                for (fid in listOf(0x011D, dg1, dg2)) {
                    assertEquals(
                        ChipAccessDenied.Reason.FILE_PROTECTED,
                        assertIs<ChipAccessDenied>(errorOf(read, fid)).reason,
                        fid.toString(16),
                    )
                }
                assertTrue(
                    read.checks.none { it.outcome is CheckOutcome.Passed || it.outcome is CheckOutcome.Failed },
                    "no verdict: ${read.checks}",
                )
            }
        }

    @Test
    fun c15_efComMissingButEfSodPresent_theGroupsEfSodListsAreRead() =
        runTest {
            Sim.withChips { c, keys ->
                val efComMissing = inner { _, cmd, _ -> if (selects(cmd, 0x011E)) ResponseApdu(ByteArray(0), 0x6A82) else null }
                val flow = bac(c, keys, efComMissing)
                val read = complete(Sim.read(flow))
                assertIs<ChipFileNotFound>(errorOf(read, 0x011E))
                assertEquals(CheckOutcome.Passed, hash(read, 1))
                assertEquals(CheckOutcome.Passed, hash(read, 2))
            }
        }

    @Test
    fun c17_aGroupNeitherListNamesButTheAppAsksFor_isNotFoundAndNotListed() =
        runTest {
            Sim.withChips { c, keys ->
                val flow = bac(c, keys)
                val read = complete(Sim.read(flow, dataGroups = setOf(1, 2, 3)))
                val error = assertIs<ChipFileNotFound>(errorOf(read, 0x0103))
                assertEquals(false, error.listedByChip)
            }
        }

    // --- Command counts of the device-shaped chip (C-30) ------------------------------------------------

    @Test
    fun c30_theDeviceShapedChip_needsTheRecordedNumberOfReadsPerFileAnd121CommandsFromTheMasterFile() =
        runTest {
            Sim.withChips { c, keys ->
                val spy = FakeReadSpy()
                val flow = DeviceChipProfile.build(c, keys, extraHandlers = listOf(spy))
                val read = complete(Sim.read(flow, DeviceChipProfile.key(), dataGroups = setOf(1, 2, 14, 15)))
                val expected = mapOf(0x011E to 1, 0x011D to 11, 0x0101 to 1, 0x010E to 2, 0x010F to 2, 0x0102 to 91)
                for ((fid, reads) in expected) assertEquals(reads, spy.offsets(1, fid).size, "reads of ${fid.toString(16)}")
                assertEquals(121, flow.chip.received.size, "whole read from the master file")
                assertTrue(read.fileErrors.isEmpty(), "${read.fileErrors}")
            }
        }
}
