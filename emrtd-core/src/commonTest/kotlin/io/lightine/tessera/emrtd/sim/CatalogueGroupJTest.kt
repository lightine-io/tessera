package io.lightine.tessera.emrtd.sim

import io.lightine.tessera.emrtd.ChipCheckKind
import io.lightine.tessera.emrtd.ChipCheckReason
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipStartSelection
import io.lightine.tessera.emrtd.LinkLossPolicy
import io.lightine.tessera.emrtd.fakechip.FakeChip
import io.lightine.tessera.emrtd.fakechip.FakeCommandHandler
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.ScriptedChip
import io.lightine.tessera.emrtd.fakechip.ScriptedTransport
import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.LdsIds
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import io.lightine.tessera.emrtd.sim.SimDrive.drive
import io.lightine.tessera.emrtd.sim.SimDrive.driveFlow
import io.lightine.tessera.emrtd.sim.SimDrive.options
import io.lightine.tessera.emrtd.sim.SimDrive.withChips
import io.lightine.tessera.types.errors.ChipBlank
import io.lightine.tessera.types.vocabulary.CheckOutcome
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Catalogue group J (start state: application already selected, master file, SELECT quirks),
 * TES-346, at the core: whole reads through the coordinator on the scripted chip.
 */
class CatalogueGroupJTest {
    private val travelRecordsAid = listOf(0xA0, 0x00, 0x00, 0x02, 0x47, 0x20, 0x01).map { it.toByte() }

    /** EF.DIR (Part 10, 3.11.1): one application template per application. */
    private fun efDir(vararg aids: List<Byte>): ByteArray =
        DerWriter.concat(*aids.map { DerWriter.tlv(0x61, DerWriter.tlv(0x4F, it.toByteArray())) }.toTypedArray())

    /** EFDIRInfo (Part 11, 9.2.11): EF.DIR's copy for EF.CardSecurity. */
    private fun efDirInfo(efDir: ByteArray): ByteArray = DerWriter.sequence(DerWriter.oid("2.23.136.1.1.13"), DerWriter.octetString(efDir))

    /** Answers both forms of SELECT MF with [sw]. */
    private class SelectMfAnswer(
        private val sw: Int,
    ) : FakeCommandHandler {
        override fun handle(
            chip: FakeChip,
            command: CommandApdu,
        ): ResponseApdu? {
            if (command.ins != ChipCommands.INS_SELECT || command.p1 != 0x00) return null
            val selectsMf =
                command.data.isEmpty() || (command.data.size == 2 && command.data[0] == 0x3F.toByte() && command.data[1] == 0x00.toByte())
            return if (selectsMf) ResponseApdu(ByteArray(0), sw) else null
        }
    }

    /** Loses the eMRTD application after SELECT MF: SELECT of its AID answers 6A82 once the MF was selected (J-08). */
    private class ApplicationGoneAfterSelectMf : FakeCommandHandler {
        private var mfSelected = false

        override fun powerOff() {
            mfSelected = false
        }

        override fun handle(
            chip: FakeChip,
            command: CommandApdu,
        ): ResponseApdu? {
            if (command.ins != ChipCommands.INS_SELECT) return null
            if (command.p1 == 0x00 &&
                (command.data.isEmpty() || (command.data.size == 2 && command.data[0] == 0x3F.toByte()))
            ) {
                mfSelected = true
            }
            if (command.p1 == ChipCommands.SELECT_BY_AID && mfSelected) return ResponseApdu(ByteArray(0), 0x6A82)
            return null
        }
    }

    /** Records the AIDs selected, as the chip sees them (after secure messaging is removed). */
    private class AidLog : FakeCommandHandler {
        val aids = mutableListOf<List<Byte>>()

        override fun handle(
            chip: FakeChip,
            command: CommandApdu,
        ): ResponseApdu? {
            if (command.ins == ChipCommands.INS_SELECT && command.p1 == ChipCommands.SELECT_BY_AID) aids += command.data.toList()
            return null
        }
    }

    // --- J-07: a chip with several applications ----------------------------------------------------

    @Test
    fun j07_aChipWithSeveralApplications_selectsOnlyTheIcaoAidAndComparesEfDirWithItsSignedCopy() =
        runTest {
            withChips { c, keys ->
                val dir = efDir(LdsIds.LDS1_AID.toList(), travelRecordsAid)
                val log = AidLog()
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.PACE_ONLY,
                        extraMasterFiles = mapOf(EF_DIR to dir),
                        cardSecurityExtraInfos = listOf(efDirInfo(dir)),
                        otherApplications = mapOf(travelRecordsAid to mapOf(0x011E to byteArrayOf(0x60, 0x00))),
                        extraHandlers = listOf(log),
                    )
                val read = driveFlow(flow).complete
                assertTrue(
                    log.aids.isNotEmpty() && log.aids.all { it == LdsIds.LDS1_AID.toList() },
                    "only the ICAO application: ${log.aids}",
                )
                // Part 11, 9.2.11 (EFDIRInfo) and the check's own definition (ChipCheckKind.EfDirMatchesSignedCopy,
                // Part 11, 4.2): with a signed copy of EF.DIR in EF.CardSecurity, EF.DIR is read and compared.
                assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.EfDirMatchesSignedCopy))
            }
        }

    @Test
    fun j07_anEfDirThatDiffersFromItsSignedCopy_isAFailedFactNotAVerdict() =
        runTest {
            withChips { c, keys ->
                val signed = efDir(LdsIds.LDS1_AID.toList(), travelRecordsAid)
                val stripped = efDir(LdsIds.LDS1_AID.toList())
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.PACE_ONLY,
                        extraMasterFiles = mapOf(EF_DIR to stripped),
                        cardSecurityExtraInfos = listOf(efDirInfo(signed)),
                    )
                val read = driveFlow(flow).complete
                // [DIFF] catalogue J-07: the SDK reports the fact and gives no verdict.
                assertEquals(
                    CheckOutcome.Failed(ChipCheckReason.DIFFERS_FROM_SIGNED_COPY),
                    read.outcomeOf(ChipCheckKind.EfDirMatchesSignedCopy),
                )
            }
        }

    @Test
    fun j07_aChipWithoutASignedEfDirCopy_sendsNoEfDirCommand() =
        runTest {
            withChips { c, keys ->
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.PACE_ONLY,
                        extraMasterFiles = mapOf(EF_DIR to efDir(LdsIds.LDS1_AID.toList())),
                    )
                val read = driveFlow(flow).complete
                assertEquals(CheckOutcome.NotApplicable(ChipCheckReason.NO_EF_DIR), read.outcomeOf(ChipCheckKind.EfDirMatchesSignedCopy))
                val selects =
                    flow.chip.received
                        .mapNotNull { CommandApdu.decode(it) }
                        .filter { it.ins == ChipCommands.INS_SELECT }
                assertFalse(selects.any { it.data.contentEquals(byteArrayOf(0x2F, 0x00)) })
            }
        }

    @Test
    fun j07_selectMfAnswered6985_theReadGoesOnWhereItCanWithBac() =
        runTest {
            withChips { c, keys ->
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.PACE_AND_BAC,
                        startInApplication = true,
                        leadingHandlers = listOf(SelectMfAnswer(0x6985)),
                    )
                flow.chip.startSelection = ChipStartSelection.EMRTD_APPLICATION
                val read = driveFlow(flow).complete
                assertEquals(ChipAccessProtocol.BAC, read.access.protocol)
                assertFalse(read.access.cardAccessRead, "the master file could not be reached")
            }
        }

    @Test
    fun j07_theIcaoApplicationAbsentButAnotherPresent_failsAsBlank() =
        runTest {
            withChips { c, keys ->
                val log = AidLog()
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.BAC,
                        blank = true,
                        otherApplications = mapOf(travelRecordsAid to mapOf(0x011E to byteArrayOf(0x60, 0x00))),
                        extraHandlers = listOf(log),
                    )
                val driven = driveFlow(flow)
                assertEquals(ChipBlank, assertIs<ChipReadEnding.Failed>(driven.ending).error)
                assertEquals(1, driven.ending.attempts)
                assertEquals(listOf(LdsIds.LDS1_AID.toList()), log.aids, "only the ICAO AID is tried, once")
            }
        }

    // --- J-08: the application lost through SELECT MF -------------------------------------------------

    @Test
    fun j08_selectApplicationAnswers6A82AfterSelectMf_isReportedBlankAndNotReadAgain() =
        runTest {
            withChips { c, keys ->
                // [JDG] catalogue J-08: a chip that loses its application through SELECT MF is a
                // corner case; the reader reports 6A82 as a blank chip (Failed, never Partial).
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.BAC,
                        startInApplication = true,
                        leadingHandlers = listOf(ApplicationGoneAfterSelectMf()),
                    )
                flow.chip.startSelection = ChipStartSelection.EMRTD_APPLICATION
                val driven = driveFlow(flow, LinkLossPolicy.Resume(2))
                assertEquals(ChipBlank, assertIs<ChipReadEnding.Failed>(driven.ending).error)
                assertEquals(1, driven.links, "an answered 6A82 is never followed by a new attempt")
            }
        }

    // --- J-09: EF.ATR/INFO and extended length ---------------------------------------------------------

    private val atrInfoExtended = byteArrayOf(0x47, 0x03, 0x04, 0x01, 0xE0.toByte())

    @Test
    fun j09_extendedLengthAllowedAndSupported_readsEfAtrInfoFirstAndUsesWhatItSays() =
        runTest {
            withChips { c, keys ->
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.BAC,
                        supportsExtendedLength = true,
                        extraMasterFiles =
                            mapOf(0x2F01 to atrInfoExtended),
                    )
                val read = driveFlow(flow).complete
                val first = CommandApdu.decode(flow.chip.received.first())!!
                assertEquals(ChipCommands.INS_SELECT, first.ins)
                assertTrue(first.data.contentEquals(byteArrayOf(0x2F, 0x01)), "EF.ATR/INFO is selected before EF.CardAccess")
                assertTrue(read.access.extendedLengthAllowed)
            }
        }

    @Test
    fun j09_noEfAtrInfo_orTheAppForbidsIt_readsShort() =
        runTest {
            withChips { c, keys ->
                val absent = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, supportsExtendedLength = true)
                assertFalse(driveFlow(absent).complete.access.extendedLengthAllowed)

                val forbidden =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.BAC,
                        supportsExtendedLength = true,
                        extraMasterFiles =
                            mapOf(0x2F01 to atrInfoExtended),
                    )
                val options =
                    ChipReadOptions(
                        FakeReadFlowChip.mrzKey(),
                        setOf(1, 2),
                        listOf(forbidden.anchor),
                        SimDrive.CHECK_TIME,
                        allowExtendedLength = false,
                    )
                val read = driveFlow(forbidden, options = options).complete
                assertFalse(read.access.extendedLengthAllowed)
                val selects =
                    forbidden.chip.received
                        .mapNotNull { CommandApdu.decode(it) }
                        .filter { it.ins == ChipCommands.INS_SELECT }
                assertFalse(selects.any { it.data.contentEquals(byteArrayOf(0x2F, 0x01)) }, "EF.ATR/INFO is not even read")
            }
        }

    // --- J-10 (core part): the probe runs whatever the transport says ----------------------------------

    @Test
    fun j10_corePart_thePaceProbeRunsOnAnyTransportAndPaceIsUsed() =
        runTest {
            withChips { c, keys ->
                // The core's transport says nothing about PACE (ApduTransport has no such property);
                // the platform's own claim (iOS supportsPACE) is tested in the iOS suite. The probe of
                // EF.CardAccess runs on a transport that names no start selection.
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY)
                val script = ScriptedChip(flow.chip)
                val driven = drive(options(flow), LinkLossPolicy.Resume(2)) { ScriptedTransport(script) }
                assertEquals(ChipAccessProtocol.PACE, driven.complete.access.protocol)
                val firstSelect =
                    script.exchanges
                        .first()
                        .command.apdu!!
                assertTrue(firstSelect.data.contentEquals(byteArrayOf(0x01, 0x1C)), "the first command selects EF.CardAccess")
            }
        }

    private companion object {
        const val EF_DIR = 0x2F00
    }
}
