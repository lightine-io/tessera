package io.lightine.tessera.emrtd.sim

import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.ChipReadResult
import io.lightine.tessera.emrtd.fakechip.DeviceChipProfile
import io.lightine.tessera.emrtd.fakechip.FakeCommandHandler
import io.lightine.tessera.emrtd.fakechip.FakePaceSuite
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip.ECDH_GM_AES128
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.StandardDomainParameterValues
import io.lightine.tessera.emrtd.internal.tlv.TlvParser
import io.lightine.tessera.emrtd.internal.tlv.TlvWriter
import io.lightine.tessera.emrtd.sim.Sim.count
import io.lightine.tessera.emrtd.sim.Sim.isCommand
import io.lightine.tessera.emrtd.sim.Sim.last
import io.lightine.tessera.types.errors.ChipAccessDenied
import io.lightine.tessera.types.errors.ChipBlank
import io.lightine.tessera.types.errors.ChipUnsupportedAlgorithm
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Catalogue group A (access: BAC, PACE, refusals), card TES-346: the rows that had no test or only
 * a neighbour's, each through [io.lightine.tessera.emrtd.ChipReader] on a fake chip. Test names
 * start with the catalogue id. Sources: ICAO TR RF and Protocol Testing Part 4 v3.0 (P4), Doc 9303
 * Part 11 (P11).
 */
class CatalogueGroupATest {
    private val pace = FlowAccess.PACE_AND_BAC

    private fun bacOrPaceCommands(flow: FlowChip): Int = flow.chip.count(0x00, 0x84) + flow.chip.count(0x00, 0x82)

    /** The content of the chip's 0x7C answer with the object [from] given the tag [to]. */
    private fun retag(
        inner: ByteArray,
        from: Int,
        to: Int,
    ): ByteArray =
        TlvParser().parseAll(inner).fold(ByteArray(0)) { acc, t ->
            acc + if (t.tag == from) TlvWriter.encode(to, t.value()) else t.encoded()
        }

    /** Every component of the static Brainpool P256r1 key as PACE's public-key data objects (P4 7.3.16, 7.3.21: OID, p, a, b, G, n, point, h). */
    private fun allKeyComponents(point: ByteArray): ByteArray {
        val ec = StandardDomainParameterValues.forId(13) as EcDomainParameters
        val g = byteArrayOf(0x04) + ec.generator.x + ec.generator.y
        return DerWriter.oid("0.4.0.127.0.7.2.2.4.2.2") + TlvWriter.encode(0x81, ec.p) + TlvWriter.encode(0x82, ec.a) +
            TlvWriter.encode(0x83, ec.b) + TlvWriter.encode(0x84, g) + TlvWriter.encode(0x85, ec.n) +
            TlvWriter.encode(0x86, point) + TlvWriter.encode(0x87, ec.cofactor)
    }

    /** Runs PACE on a PACE-and-BAC chip whose answer at [step] is changed by [change]; returns the flow and the result. */
    private suspend fun withPaceFault(
        step: Int,
        change: (ByteArray) -> ByteArray,
        check: (FlowChip, ChipReadResult) -> Unit,
    ) {
        Sim.withChips { c, keys ->
            val flow = FakeReadFlowChip.build(c, keys, pace)
            flow.pace!!.rewriteAnswer = { s, inner -> if (s == step) change(inner) else inner }
            check(flow, Sim.read(flow))
        }
    }

    private fun assertPaceStoppedAfterStep(
        flow: FlowChip,
        result: ChipReadResult,
        step: Int,
    ) {
        val error = assertIs<ChipAccessDenied>(Sim.failed(result))
        assertEquals(ChipAccessProtocol.PACE, error.protocol)
        assertEquals(ChipAccessDenied.Reason.UNEXPECTED_CHIP_ANSWER, error.reason, "$error")
        val chained = minOf(step, 3)
        assertEquals(chained, flow.chip.count(0x10, 0x86), "GENERAL AUTHENTICATE steps sent")
        assertEquals(if (step == 4) 1 else 0, flow.chip.count(0x00, 0x86), "the last step sent only when step 4 was reached")
        assertTrue(flow.chip.last()[1] == 0x86.toByte(), "nothing after the faulty answer")
        assertEquals(0, bacOrPaceCommands(flow), "no BAC after PACE (P11 4.1)")
        assertEquals(1, flow.chip.count(0x00, 0x22), "one PACE run")
    }

    // --- TD1 keys (N8) ----------------------------------------------------------------------------

    @Test
    fun a06_bacChipPersonalisedFromATd1Mrz_opensWithTheKeyFromTheThreeLineFields() =
        runTest {
            Sim.withChips { c, keys ->
                val td1 = DeviceChipProfile.td1Lines()
                val flow =
                    FakeReadFlowChip.build(c, keys, FlowAccess.BAC, keyMrzInformation = Sim.td1MrzInformation(td1), dg1Lines = td1)
                val result = assertIs<ChipReadResult.Complete>(Sim.read(flow, Sim.td1Key(td1)))
                assertEquals(ChipAccessProtocol.BAC, result.read.access.protocol)
            }
        }

    @Test
    fun a09_paceWithATd1MrzKey_sendsReferenceOneAndOpensTheChip() =
        runTest {
            Sim.withChips { c, keys ->
                val td1 = DeviceChipProfile.td1Lines()
                val flow =
                    FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY, keyMrzInformation = Sim.td1MrzInformation(td1), dg1Lines = td1)
                val result = assertIs<ChipReadResult.Complete>(Sim.read(flow, Sim.td1Key(td1)))
                assertEquals(ChipAccessProtocol.PACE, result.read.access.protocol)
                val setAt = TlvParser().parseAll(flow.pace!!.lastSetAtData!!).associate { it.tag to it.value() }
                assertTrue(setAt.getValue(0x83).contentEquals(byteArrayOf(1)), "DO83 = 01, the MRZ password (P4 ISO7816_C_01c)")
            }
        }

    // --- PACE: MSE:Set AT content (A-07) -------------------------------------------------------------

    @Test
    fun a07_paceWithTheMrz_setAtCarriesTheOidFromCardAccessAndReferenceOne() =
        runTest {
            Sim.withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY)
                assertIs<ChipReadResult.Complete>(Sim.read(flow))
                val setAt = TlvParser().parseAll(flow.pace!!.lastSetAtData!!).associate { it.tag to it.value() }
                val oid = DerWriter.oid(ECDH_GM_AES128).let { it.copyOfRange(2, it.size) }
                assertTrue(setAt.getValue(0x80).contentEquals(oid), "DO80 = the OID listed in EF.CardAccess")
                assertTrue(setAt.getValue(0x83).contentEquals(byteArrayOf(1)), "DO83 = 01")
            }
        }

    // --- PACE: malformed step answers (N4) ----------------------------------------------------------

    @Test
    fun a19_gaStep1AnswerWithTag90_failsTypedAndSendsNothingMore() =
        runTest { withPaceFault(1, { retag(it, 0x80, 0x90) }) { flow, r -> assertPaceStoppedAfterStep(flow, r, 1) } }

    @Test
    fun a21_gaStep2AnswerWithTag92_failsTypedAndSendsNothingMore() =
        runTest { withPaceFault(2, { retag(it, 0x82, 0x92) }) { flow, r -> assertPaceStoppedAfterStep(flow, r, 2) } }

    @Test
    fun a26_gaStep3AnswerWithTag94_failsTypedAndSendsNothingMore() =
        runTest { withPaceFault(3, { retag(it, 0x84, 0x94) }) { flow, r -> assertPaceStoppedAfterStep(flow, r, 3) } }

    @Test
    fun a31_gaStep4AnswerWithTag96_failsTypedAndSendsNothingMore() =
        runTest { withPaceFault(4, { retag(it, 0x86, 0x96) }) { flow, r -> assertPaceStoppedAfterStep(flow, r, 4) } }

    @Test
    fun a24_gaStep2AnswerHoldingEveryKeyComponent_failsAfterStep2() =
        runTest {
            withPaceFault(2, { allKeyComponents(TlvParser().parseAll(it).single().value()) }) { flow, r ->
                assertPaceStoppedAfterStep(flow, r, 2)
            }
        }

    @Test
    fun a28_gaStep3AnswerHoldingEveryKeyComponent_failsAfterStep3() =
        runTest {
            withPaceFault(3, { allKeyComponents(TlvParser().parseAll(it).single().value()) }) { flow, r ->
                assertPaceStoppedAfterStep(flow, r, 3)
            }
        }

    @Test
    fun a37_gmStep4AnswerWithAnExtraTag8A_failsAfterTheLastStep() =
        runTest {
            withPaceFault(4, { it + TlvWriter.encode(0x8A, ByteArray(16) { 0x5A }) }) { flow, r ->
                assertPaceStoppedAfterStep(flow, r, 4)
            }
        }

    @Test
    fun a22_gmMappingDataPlusOne_isRejectedOrFailsAtMutualAuthentication_neverAnotherRun() =
        runTest {
            withPaceFault(2, { inner ->
                val v = TlvParser().parseAll(inner).single().value()
                v[v.size - 1] = (v.last() + 1).toByte()
                TlvWriter.encode(0x82, v)
            }) { flow, r ->
                // P4 7.3.14: "accepted that the test object aborts"; otherwise step 4 fails. Either
                // way one PACE run and no BAC.
                val error = assertIs<ChipAccessDenied>(Sim.failed(r))
                assertEquals(ChipAccessProtocol.PACE, error.protocol)
                assertEquals(1, flow.chip.count(0x00, 0x22))
                assertEquals(0, bacOrPaceCommands(flow))
            }
        }

    @Test
    fun a27_chipEphemeralKeyPlusOne_isRejectedBeforeAnyToken() =
        runTest {
            withPaceFault(3, { inner ->
                val v = TlvParser().parseAll(inner).single().value()
                v[v.size - 1] = (v.last() + 1).toByte()
                TlvWriter.encode(0x84, v)
            }) { flow, r ->
                val error = assertIs<ChipAccessDenied>(Sim.failed(r))
                assertEquals(ChipAccessProtocol.PACE, error.protocol)
                assertEquals(0, flow.chip.count(0x00, 0x86), "no token sent for a key off the curve")
                assertEquals(0, bacOrPaceCommands(flow))
            }
        }

    @Test
    fun a17_wrongNonceThen6300AtMutualAuthentication_isKeyNotMatchedWithOneRunAndNoBac() =
        runTest {
            Sim.withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, pace)
                flow.pace!!.rewriteAnswer = { s, inner ->
                    if (s == 1) {
                        val z = TlvParser().parseAll(inner).single().value()
                        z[z.size - 1] = (z.last() + 1).toByte()
                        TlvWriter.encode(0x80, z)
                    } else {
                        inner
                    }
                }
                val error = Sim.failed(Sim.read(flow))
                assertEquals(ChipAccessDenied(ChipAccessProtocol.PACE, ChipAccessDenied.Reason.KEY_NOT_MATCHED, 0x6300), error)
                assertEquals(1, flow.chip.count(0x00, 0x86))
                assertTrue(flow.chip.last().isCommand(0x00, 0x86))
                assertEquals(0, bacOrPaceCommands(flow))
            }
        }

    // --- EF.CardAccess variants (N5) -----------------------------------------------------------------

    @Test
    fun a13_anExtraPaceEntryWithNoParameterId_isIgnoredAndThePaceRunsWithTheGoodOne() =
        runTest {
            Sim.withChips { c, keys ->
                val good = FakeReadFlowChip.paceInfo(ECDH_GM_AES128, 13)
                val extra = DerWriter.sequence(DerWriter.oid(ECDH_GM_AES128), DerWriter.integer(2))
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY, cardAccessOverride = DerWriter.set(good, extra))
                val result = assertIs<ChipReadResult.Complete>(Sim.read(flow))
                assertEquals(ChipAccessProtocol.PACE, result.read.access.protocol)
                assertEquals(1, flow.chip.count(0x00, 0x22))
            }
        }

    @Test
    fun a15_aProprietaryIdAndExplicitDomainParameters_areNeverUsedAndTheStandardIdRuns() =
        runTest {
            Sim.withChips { c, keys ->
                val standard = FakeReadFlowChip.paceInfo(ECDH_GM_AES128, 13)
                val proprietary = FakeReadFlowChip.paceInfo(ECDH_GM_AES128, 32)
                // PACEDomainParameterInfo (P11 9.2.1): id-PACE-ECDH-GM, explicit parameters, parameterId 32.
                val explicit =
                    DerWriter.sequence(
                        DerWriter.oid("0.4.0.127.0.7.2.2.4.2"),
                        DerWriter.sequence(DerWriter.oid("1.2.840.10045.2.1"), DerWriter.sequence(DerWriter.integer(1))),
                        DerWriter.integer(32),
                    )
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.PACE_ONLY,
                        cardAccessOverride = DerWriter.set(proprietary, standard, explicit),
                    )
                val result = assertIs<ChipReadResult.Complete>(Sim.read(flow))
                assertEquals(ChipAccessProtocol.PACE, result.read.access.protocol)
                assertEquals(1, flow.chip.count(0x00, 0x22), "one option performed")
            }
        }

    @Test
    fun a34_parameterId31Reserved_sendsNoPaceCommand_thenBacOrNoSupportedSuite() =
        runTest {
            Sim.withChips { c, keys ->
                // [DIFF] P4 ISO7816_C_28 asks the reader to abort; the SDK sends no PACE command (the id
                // is not usable) and, where the chip offers BAC, runs BAC with the MRZ key.
                val reserved = listOf(FakePaceSuite(ECDH_GM_AES128, 31))
                val both = FakeReadFlowChip.build(c, keys, pace, cardAccessSuites = reserved)
                assertIs<ChipReadResult.Complete>(Sim.read(both))
                assertEquals(0, both.chip.count(0x00, 0x22) + both.chip.count(0x10, 0x86), "no MSE:Set AT and no GENERAL AUTHENTICATE")
                assertEquals(1, both.chip.count(0x00, 0x82))

                val paceOnly = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY, cardAccessSuites = reserved)
                val error = assertIs<ChipUnsupportedAlgorithm>(Sim.failed(Sim.read(paceOnly)))
                assertEquals(0, paceOnly.chip.count(0x00, 0x22) + paceOnly.chip.count(0x10, 0x86))
                assertTrue(error.reason != ChipUnsupportedAlgorithm.Reason.CHIP_REFUSED_SUITE, "$error")
            }
        }

    // --- Blank chips and odd SELECT answers --------------------------------------------------------

    @Test
    fun a05_paceSucceedsThenTheApplicationIsMissing_failsAsABlankChipWithNoFileRead() =
        runTest {
            Sim.withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY, blank = true)
                assertIs<ChipBlank>(Sim.failed(Sim.read(flow)))
                assertEquals(0, flow.chip.count(0x0C, 0xB0), "no protected READ BINARY")
            }
        }

    @Test
    fun a51_6A86AtTheSelectOfTheApplication_isATypedAnswerNeverAKeyRefusal() =
        runTest {
            Sim.withChips { c, keys ->
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.BAC,
                        extraHandlers = listOf(Sim.answer(0x00, 0xA4, 0x6A86, p1 = 0x04)),
                    )
                val error = Sim.failed(Sim.read(flow))
                assertTrue(error !is ChipAccessDenied || error.reason == ChipAccessDenied.Reason.UNEXPECTED_CHIP_ANSWER, "$error")
                assertEquals(0, flow.chip.count(0x00, 0x82), "no key was tried")
            }
        }

    // --- BAC: refusals and bad chip answers ---------------------------------------------------------

    @Test
    fun a44_externalAuthenticateAnswered6A86Or6A88_isChipRefusedOnceAndNeverRetried() =
        runTest {
            Sim.withChips { c, keys ->
                for (sw in listOf(0x6A86, 0x6A88)) {
                    val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, extraHandlers = listOf(Sim.answer(0x00, 0x82, sw)))
                    assertEquals(
                        ChipAccessDenied(ChipAccessProtocol.BAC, ChipAccessDenied.Reason.CHIP_REFUSED, sw),
                        Sim.failed(Sim.read(flow)),
                    )
                    assertEquals(1, flow.chip.count(0x00, 0x82))
                    assertTrue(flow.chip.last().isCommand(0x00, 0x82), "nothing after the refusal")
                }
            }
        }

    @Test
    fun a44_getChallengeAnswered6982_isTypedAndNoExternalAuthenticateIsSent() =
        runTest {
            Sim.withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, extraHandlers = listOf(Sim.answer(0x00, 0x84, 0x6982)))
                val error = assertIs<ChipAccessDenied>(Sim.failed(Sim.read(flow)))
                assertEquals(ChipAccessProtocol.BAC, error.protocol)
                assertEquals(0x6982, error.statusWord)
                assertEquals(0, flow.chip.count(0x00, 0x82))
            }
        }

    /** Lets the chip answer EXTERNAL AUTHENTICATE, then flips one bit of byte [index] of its answer data. */
    private class FlipBacAnswer(
        private val index: Int,
    ) : FakeCommandHandler {
        override fun handle(
            chip: io.lightine.tessera.emrtd.fakechip.FakeChip,
            command: io.lightine.tessera.emrtd.internal.apdu.CommandApdu,
        ): ResponseApdu? {
            if (command.cla != 0x00 || command.ins != 0x82) return null
            val real = chip.answerInner(command, this)
            if (real.data.size != 40) return real
            val data = real.data.copyOf()
            data[index] = (data[index].toInt() xor 0x02).toByte()
            return ResponseApdu(data, real.sw)
        }
    }

    @Test
    fun a40_bacAnswerWithAWrongMac_failsWithNoFileCommandAfter() =
        runTest {
            Sim.withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, leadingHandlers = listOf(FlipBacAnswer(39)))
                val error = Sim.failed(Sim.read(flow))
                assertEquals(ChipAccessDenied.Reason.UNEXPECTED_CHIP_ANSWER, assertIs<ChipAccessDenied>(error).reason)
                assertTrue(flow.chip.last().isCommand(0x00, 0x82), "no data group read")
            }
        }

    @Test
    fun a41_bacAnswerWithAWrongCryptogram_failsWithNoFileCommandAfter() =
        runTest {
            Sim.withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, leadingHandlers = listOf(FlipBacAnswer(3)))
                val error = Sim.failed(Sim.read(flow))
                assertEquals(ChipAccessDenied.Reason.UNEXPECTED_CHIP_ANSWER, assertIs<ChipAccessDenied>(error).reason)
                assertTrue(flow.chip.last().isCommand(0x00, 0x82), "no data group read")
            }
        }

    @Test
    fun a50_aCanKeyOnABacOnlyChip_needsPaceAndSendsNoAuthentication() =
        runTest {
            Sim.withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val error = assertIs<ChipAccessDenied>(Sim.failed(Sim.read(flow, ChipAccessKey.Can("123456"))))
                assertEquals(ChipAccessDenied.Reason.KEY_NEEDS_PACE, error.reason)
                assertEquals(0, bacOrPaceCommands(flow))
                assertNotEquals(0, flow.chip.received.size)
            }
        }
    // --- Strengthened rows (the existing tests did not cover the stimulus) ---------------------------

    /** A-16 (P4 ISO7816_C_08a-c): 6A80, 6A88 or 6F00 to MSE:Set AT with one suite: no GENERAL AUTHENTICATE, no BAC. */
    @Test
    fun a16_setAtAnswered6A80_6A88Or6F00WithOneSuite_endsWithNoPaceStepAndNoBac() =
        runTest {
            Sim.withChips { c, keys ->
                for (sw in listOf(0x6A80, 0x6A88, 0x6F00)) {
                    val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY)
                    flow.pace!!.refuseSetAt = sw
                    val error = Sim.failed(Sim.read(flow))
                    assertTrue(error is ChipUnsupportedAlgorithm || error is ChipAccessDenied, "${sw.toString(16)}: $error")
                    assertEquals(
                        0,
                        flow.chip.count(0x10, 0x86) + flow.chip.count(0x00, 0x86),
                        "no GENERAL AUTHENTICATE after ${sw.toString(16)}",
                    )
                    assertEquals(0, bacOrPaceCommands(flow))
                    assertTrue(flow.chip.last().isCommand(0x00, 0x22))
                }
            }
        }

    /**
     * A-18, A-20, A-25 (P4 ISO7816_C_10, C_12, C_18): 6A80 or 6F00 at GENERAL AUTHENTICATE steps 1,
     * 2 and 3. The existing test answers only the first chained step and has neither word.
     */
    @Test
    fun a18_a20_a25_6A80Or6F00AtEachEarlyPaceStep_endsWithThatWordAndNothingAfter() =
        runTest {
            Sim.withChips { c, keys ->
                for (step in 1..3) {
                    for (sw in listOf(0x6A80, 0x6F00)) {
                        var seen = 0
                        val refuse =
                            FakeCommandHandler { _, cmd ->
                                if (cmd.cla == 0x10 && cmd.ins == 0x86 && ++seen == step) ResponseApdu(ByteArray(0), sw) else null
                            }
                        val flow = FakeReadFlowChip.build(c, keys, pace, leadingHandlers = listOf(refuse))
                        val error = assertIs<ChipAccessDenied>(Sim.failed(Sim.read(flow)))
                        assertEquals(
                            ChipAccessDenied(ChipAccessProtocol.PACE, ChipAccessDenied.Reason.CHIP_REFUSED, sw),
                            error,
                            "step $step",
                        )
                        assertEquals(step, flow.chip.count(0x10, 0x86), "step $step ${sw.toString(16)}")
                        assertTrue(flow.chip.last().isCommand(0x10, 0x86), "nothing after the refusal")
                        assertEquals(0, bacOrPaceCommands(flow))
                    }
                }
            }
        }

    /**
     * A-35 (P4 ISO7816_C_29 to C_33). [DIFF] P4: "abort communication". The SDK finishes PACE (the
     * session keys are valid) and reports the chip-authentication-mapping check as a fact, failed
     * or not run, never a verdict on the chip. Covers C_32 (8B instead of 8A) and C_33 (8A with no
     * data) through the reader; C_29 (no 8A) and bad padding are covered by
     * PaceCamFakeChipTest::absentOrMalformedDataIsAFactNotAnAbort.
     */
    @Test
    fun a35_camStep4WithTag8BOrAnEmpty8A_opensTheSessionAndTheMappingCheckIsNotPassed() =
        runTest {
            Sim.withChips { c, keys ->
                val params = StandardDomainParameterValues.forId(13) as EcDomainParameters
                val faults: List<(ByteArray) -> ByteArray> =
                    listOf(
                        { inner -> retag(inner, 0x8A, 0x8B) },
                        { inner ->
                            TlvParser().parseAll(inner).fold(ByteArray(0)) { acc, t ->
                                acc +
                                    if (t.tag == 0x8A) TlvWriter.encode(0x8A, ByteArray(0)) else t.encoded()
                            }
                        },
                    )
                for (fault in faults) {
                    val cam =
                        io.lightine.tessera.emrtd.fakechip.FakePaceCam(
                            io.lightine.tessera.emrtd.fakechip.FakePaceMath
                                .randomPrivateKey(c, params, c::randomBytes),
                        )
                    val flow =
                        FakeReadFlowChip.build(
                            c,
                            keys,
                            FlowAccess.PACE_ONLY,
                            paceSuites = listOf(FakePaceSuite(FakeReadFlowChip.ECDH_CAM_AES128, 13)),
                            cam = cam,
                        )
                    flow.pace!!.rewriteAnswer = { s, inner -> if (s == 4) fault(inner) else inner }
                    val read = assertIs<ChipReadResult.Complete>(Sim.read(flow)).read
                    assertEquals(ChipAccessProtocol.PACE, read.access.protocol)
                    val outcome = read.outcomeOf(io.lightine.tessera.emrtd.ChipCheckKind.PaceChipAuthenticationMapping)
                    assertTrue(outcome !is io.lightine.tessera.types.vocabulary.CheckOutcome.Passed, "$outcome")
                }
            }
        }
}
