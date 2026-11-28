package io.lightine.tessera.emrtd

import io.lightine.tessera.emrtd.fakechip.FakeCommandHandler
import io.lightine.tessera.emrtd.fakechip.FakePaceCam
import io.lightine.tessera.emrtd.fakechip.FakePaceMath
import io.lightine.tessera.emrtd.fakechip.FakePaceSuite
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip.ECDH_CAM_AES128
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip.ECDH_GM_AES128
import io.lightine.tessera.emrtd.fakechip.FakeTagLoss
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.LdsIds
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.StandardDomainParameterValues
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.emrtd.internal.pa.withKeys
import io.lightine.tessera.telemetry.TelemetryEvent
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.vocabulary.CheckOutcome
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The read flow from both states a chip can be in when the app gets the tag: the master file
 * current (Android, after activation) or the eMRTD application current (iOS, where the system has
 * already selected it). EF.CardAccess and EF.CardSecurity live in the master file and SELECT by
 * file identifier finds only an EF under the current DF (Doc 9303 Part 10, 3.6.2 and 3.11), so
 * every case runs in both states; chips that refuse SELECT MF in one form or both are covered too.
 * Fake chips from the project specimen; they run on every platform.
 */
class ChipReaderStartStateTest {
    private val checkTime = Instant.parse("2026-06-01T12:00:00Z")

    private class Recorder : TelemetrySink {
        val events = mutableListOf<ChipReadEvent>()

        override fun record(event: TelemetryEvent) {
            events += event as ChipReadEvent
        }
    }

    private suspend fun read(
        flow: FlowChip,
        telemetry: TelemetrySink = Recorder(),
    ): ChipReadResult =
        ChipReader(telemetry).read(flow.chip, ChipReadOptions(FakeReadFlowChip.mrzKey(), setOf(1, 2), listOf(flow.anchor), checkTime))

    private fun complete(
        result: ChipReadResult,
        label: String,
    ): ChipRead = assertIs<ChipReadResult.Complete>(result, "$label: $result").read

    private inline fun withChips(block: (ChipCrypto, TestKeys) -> Unit) {
        withCrypto { c -> withKeys { keys -> block(c, keys) } }
    }

    private fun assertSignedFilesPassed(
        read: ChipRead,
        label: String,
    ) {
        assertTrue(read.fileErrors.isEmpty(), "$label: ${read.fileErrors}")
        assertNotNull(read.dg1, label)
        val sod = ChipSignedFile.SECURITY_OBJECT
        assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.SignatureVerifies(sod)), label)
        assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.DataGroupHashMatches(1)), label)
        assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.DataGroupHashMatches(2)), label)
    }

    private fun sent(flow: FlowChip): List<String> =
        flow.chip.received.map { b ->
            b.joinToString("") {
                (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
            }
        }

    private fun assertNoGetChallenge(
        flow: FlowChip,
        label: String,
    ) {
        assertTrue(sent(flow).none { it.startsWith("0084") }, "$label: BAC started")
    }

    private fun cam(c: ChipCrypto): FakePaceCam {
        val params = StandardDomainParameterValues.forId(13) as EcDomainParameters
        return FakePaceCam(FakePaceMath.randomPrivateKey(c, params, c::randomBytes))
    }

    @Test
    fun aPaceChipReadsCardAccessRunsPaceAndReadsTheRealCardSecurityFromEitherState() =
        runTest {
            withChips { c, keys ->
                for (inApplication in listOf(false, true)) {
                    val label = "inApplication=$inApplication"
                    val flow =
                        FakeReadFlowChip.build(
                            c,
                            keys,
                            FlowAccess.PACE_AND_BAC,
                            paceSuites = listOf(FakePaceSuite(ECDH_CAM_AES128, 13)),
                            cam = cam(c),
                            startInApplication = inApplication,
                        )
                    val read = complete(read(flow), label)
                    assertEquals(ChipAccessProtocol.PACE, read.access.protocol, label)
                    assertTrue(read.access.cardAccessRead, label)
                    assertNoGetChallenge(flow, label)
                    // 011D read in the master file is EF.CardSecurity, not the application's EF.SOD.
                    val cardSecurity = read.files.single { it.fileId == 0x011D && it.inMasterFile }
                    assertTrue(
                        cardSecurity.bytes().contentEquals(
                            flow.chip.files.masterFiles
                                .getValue(0x011D),
                        ),
                        label,
                    )
                    assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.PaceChipAuthenticationMapping), label)
                    assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.SignatureVerifies(ChipSignedFile.CARD_SECURITY)), label)
                    assertEquals(
                        CheckOutcome.Passed,
                        read.outcomeOf(ChipCheckKind.CardAccessMatchesSignedCopy(ChipSignedCopy.CARD_SECURITY)),
                        label,
                    )
                    assertEquals(
                        CheckOutcome.Passed,
                        read.outcomeOf(ChipCheckKind.PaceOfferMatchesSignedCopy(ChipSignedCopy.CARD_SECURITY)),
                        label,
                    )
                    assertSignedFilesPassed(read, label)
                }
            }
        }

    @Test
    fun chipAuthenticationInTheMasterFileRunsFromEitherState() =
        runTest {
            withChips { c, keys ->
                for (inApplication in listOf(false, true)) {
                    val label = "inApplication=$inApplication"
                    val flow =
                        FakeReadFlowChip.build(
                            c,
                            keys,
                            FlowAccess.PACE_ONLY,
                            caInMasterFile = true,
                            startInApplication = inApplication,
                        )
                    val read = complete(read(flow), label)
                    assertEquals(1, flow.ca!!.restarts, label)
                    assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.ChipAuthentication(ChipFileContext.MASTER_FILE)), label)
                    assertSignedFilesPassed(read, label)
                }
            }
        }

    @Test
    fun aBacOnlyChipHasNoCardAccessAndNoFalseDowngradeFromEitherState() =
        runTest {
            withChips { c, keys ->
                for (inApplication in listOf(false, true)) {
                    val label = "inApplication=$inApplication"
                    // DG14 is there (chip authentication keys) and lists no PACE: a signed copy without PACE.
                    val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, caInApplication = true, startInApplication = inApplication)
                    val read = complete(read(flow), label)
                    assertEquals(ChipAccessProtocol.BAC, read.access.protocol, label)
                    assertFalse(read.access.cardAccessRead, label)
                    for (check in read.checks.filter {
                        it.kind is ChipCheckKind.CardAccessMatchesSignedCopy || it.kind is ChipCheckKind.PaceOfferMatchesSignedCopy
                    }) {
                        assertFalse(check.outcome is CheckOutcome.Failed, "$label: $check")
                    }
                    assertEquals(
                        CheckOutcome.NotApplicable(ChipCheckReason.NOT_OFFERED),
                        read.outcomeOf(ChipCheckKind.PaceOfferMatchesSignedCopy(ChipSignedCopy.DG14)),
                        label,
                    )
                    assertSignedFilesPassed(read, label)
                }
            }
        }

    @Test
    fun cardAccessMatchesItsSignedCopyFromEitherStateAndAStrippedOneIsStillCaught() =
        runTest {
            withChips { c, keys ->
                val suite = listOf(FakePaceSuite(ECDH_GM_AES128, 13))
                for (inApplication in listOf(false, true)) {
                    val label = "inApplication=$inApplication"
                    val flow =
                        FakeReadFlowChip.build(
                            c,
                            keys,
                            FlowAccess.PACE_ONLY,
                            paceSuites = suite,
                            signedPaceSuites = suite,
                            startInApplication = inApplication,
                        )
                    val read = complete(read(flow), label)
                    assertEquals(ChipAccessProtocol.PACE, read.access.protocol, label)
                    assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.PaceOfferMatchesSignedCopy(ChipSignedCopy.DG14)), label)
                    assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.CardAccessMatchesSignedCopy(ChipSignedCopy.DG14)), label)

                    // A chip with no EF.CardAccess whose DG14 lists PACE is still reported.
                    val stripped =
                        FakeReadFlowChip.build(c, keys, FlowAccess.BAC, signedPaceSuites = suite, startInApplication = inApplication)
                    val strippedRead = complete(read(stripped), label)
                    assertEquals(
                        CheckOutcome.Failed(ChipCheckReason.PACE_MISSING_FROM_CARD_ACCESS),
                        strippedRead.outcomeOf(ChipCheckKind.PaceOfferMatchesSignedCopy(ChipSignedCopy.DG14)),
                        label,
                    )
                }
            }
        }

    @Test
    fun theCommandsSentForEfCardAccessInEachState() =
        runTest {
            withChips { c, keys ->
                val selectCardAccess = "00A4020C02011C"
                val selectMf = "00A4000C"

                // Master file current: EF.CardAccess is selected at once; no SELECT MF is sent.
                val android = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY)
                complete(read(android), "android")
                assertEquals(selectCardAccess, sent(android)[0])
                assertTrue(sent(android).none { it.startsWith("00A400") }, "android: ${sent(android)}")

                // Application current: the first SELECT is refused (6982 before a session on this
                // chip), then SELECT MF in the Table 1 form, then EF.CardAccess again.
                val ios = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY, startInApplication = true)
                complete(read(ios), "ios")
                assertEquals(listOf(selectCardAccess, selectMf, selectCardAccess), sent(ios).take(3))
                assertEquals(1, sent(ios).count { it.startsWith("00A400") })

                // A BAC-only chip with the master file current: 6A82, one SELECT MF, 6A82 again, then
                // the application and BAC.
                val bac = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                complete(read(bac), "bac")
                assertEquals(listOf(selectCardAccess, selectMf, selectCardAccess, "00A4040C07A0000002471001"), sent(bac).take(4))
            }
        }

    @Test
    fun chipsThatRefuseSelectMfStillReadWhereTheyCan() =
        runTest {
            withChips { c, keys ->
                // Master file current and SELECT MF refused in both forms, as some chips do: PACE and
                // BAC chips both read as before.
                for (access in listOf(FlowAccess.PACE_ONLY, FlowAccess.BAC)) {
                    val label = "refuses both, $access"
                    val flow = FakeReadFlowChip.build(c, keys, access, acceptsSelectMf = false, acceptsSelectMfById = false)
                    val read = complete(read(flow), label)
                    val expected = if (access == FlowAccess.BAC) ChipAccessProtocol.BAC else ChipAccessProtocol.PACE
                    assertEquals(expected, read.access.protocol, label)
                    assertSignedFilesPassed(read, label)
                }
                val bac = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, acceptsSelectMf = false, acceptsSelectMfById = false)
                complete(read(bac), "bac")
                assertEquals(listOf("00A4020C02011C", "00A4000C", "00A4000C023F00", "00A4040C07A0000002471001"), sent(bac).take(4))

                // Application current, only the identifier form taken: the fallback reaches EF.CardAccess.
                val byId = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY, startInApplication = true, acceptsSelectMf = false)
                val byIdRead = complete(read(byId), "by id")
                assertEquals(ChipAccessProtocol.PACE, byIdRead.access.protocol)
                assertEquals(listOf("00A4020C02011C", "00A4000C", "00A4000C023F00", "00A4020C02011C"), sent(byId).take(4))

                // Application current and SELECT MF refused in both forms: EF.CardAccess cannot be
                // reached, so a chip that also takes BAC is read with BAC.
                val stuck =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.PACE_AND_BAC,
                        startInApplication = true,
                        acceptsSelectMf = false,
                        acceptsSelectMfById = false,
                    )
                val stuckRead = complete(read(stuck), "stuck")
                assertEquals(ChipAccessProtocol.BAC, stuckRead.access.protocol)
                assertFalse(stuckRead.access.cardAccessRead)
            }
        }

    @Test
    fun aFailedFileReadReportsItsStatusWordAndALostTagItsBytesSoFar() =
        runTest {
            withChips { c, keys ->
                for (inApplication in listOf(false, true)) {
                    val label = "inApplication=$inApplication"
                    val lost =
                        FakeReadFlowChip.build(
                            c,
                            keys,
                            FlowAccess.PACE_ONLY,
                            tagLoss = FakeTagLoss(0x0102),
                            startInApplication = inApplication,
                        )
                    val recorder = Recorder()
                    val partial = assertIs<ChipReadResult.Partial>(read(lost, recorder), label)
                    assertEquals(NfcTagLost, partial.stoppedBy, label)
                    val dg2 = recorder.events.single { it.step == ChipReadStep.READ_FILE && it.fileId == 0x0102 }
                    assertEquals(ChipReadStepOutcome.FAILED, dg2.outcome, label)
                    val soFar = assertNotNull(dg2.byteCount, label)
                    assertTrue(soFar in 1 until lost.dataGroups.getValue(2).size, "$label: $soFar")
                }

                // EF.CardAccess answered with 6985 (in the master file too): the event keeps the status word.
                val refusing =
                    FakeCommandHandler { _, command ->
                        val cardAccess = command.data.contentEquals(byteArrayOf(0x01, 0x1C))
                        if (command.ins == ChipCommands.INS_SELECT && cardAccess) ResponseApdu(ByteArray(0), 0x6985) else null
                    }
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, leadingHandlers = listOf(refusing))
                val recorder = Recorder()
                complete(read(flow, recorder), "6985")
                val event = recorder.events.single { it.step == ChipReadStep.READ_FILE && it.fileId == LdsIds.EF_CARD_ACCESS }
                assertEquals(ChipReadStepOutcome.FAILED, event.outcome)
                assertEquals(0x6985, event.statusWord)
            }
        }

    /** Answers 6999 to the SELECT of each master-file EF in [fids] (a chip seen failing EF.ATR/INFO 2F01 and EF.CardAccess 011C). */
    private fun refusingWith6999(vararg fids: Int) =
        FakeCommandHandler { _, command ->
            val hit = fids.any { command.data.contentEquals(byteArrayOf((it shr 8).toByte(), it.toByte())) }
            if (command.ins == ChipCommands.INS_SELECT && hit) ResponseApdu(ByteArray(0), 0x6999) else null
        }

    @Test
    fun aChipThatAnswers6999ToTheCardAccessProbeIsStillReadOverBacWithoutARestart() =
        runTest {
            withChips { c, keys ->
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.PACE_AND_BAC,
                        leadingHandlers = listOf(refusingWith6999(LdsIds.EF_CARD_ACCESS)),
                    )
                val recorder = Recorder()
                val heard = mutableListOf<ChipReadUpdate>()
                val options = ChipReadOptions(FakeReadFlowChip.mrzKey(), setOf(1, 2), listOf(flow.anchor), checkTime)
                val result = ChipReader(recorder).read(flow.chip, options) { heard += it }
                val read = complete(result, "6999")
                assertEquals(ChipAccessProtocol.BAC, read.access.protocol)
                assertFalse(read.access.cardAccessRead)
                val probe = recorder.events.single { it.step == ChipReadStep.READ_FILE && it.fileId == LdsIds.EF_CARD_ACCESS }
                assertEquals(ChipReadStepOutcome.FAILED, probe.outcome)
                assertEquals(0x6999, probe.statusWord)
                assertTrue(heard.isNotEmpty() && heard.all { it.attempt == 1 && it.maxAttempts == 1 }, "a restart was used")
                assertNotNull(read.dg1)
            }
        }

    @Test
    fun aChipThatAnswers6999ToBothProbeReadsIsStillReadOverBacWithoutARestart() =
        runTest {
            withChips { c, keys ->
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.PACE_AND_BAC,
                        supportsExtendedLength = true,
                        leadingHandlers = listOf(refusingWith6999(0x2F01, LdsIds.EF_CARD_ACCESS)),
                    )
                val recorder = Recorder()
                val heard = mutableListOf<ChipReadUpdate>()
                val options = ChipReadOptions(FakeReadFlowChip.mrzKey(), setOf(1, 2), listOf(flow.anchor), checkTime)
                val read = complete(ChipReader(recorder).read(flow.chip, options) { heard += it }, "6999 twice")
                assertEquals(ChipAccessProtocol.BAC, read.access.protocol)
                for (fid in listOf(0x2F01, LdsIds.EF_CARD_ACCESS)) {
                    val probe = recorder.events.single { it.step == ChipReadStep.READ_FILE && it.fileId == fid }
                    assertEquals(ChipReadStepOutcome.FAILED, probe.outcome, "file $fid")
                    assertEquals(0x6999, probe.statusWord, "file $fid")
                }
                assertTrue(heard.all { it.attempt == 1 && it.maxAttempts == 1 }, "a restart was used")
                assertNotNull(read.dg1)
            }
        }
}
