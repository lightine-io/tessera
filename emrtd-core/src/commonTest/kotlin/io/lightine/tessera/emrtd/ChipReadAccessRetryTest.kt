package io.lightine.tessera.emrtd

import io.lightine.tessera.emrtd.fakechip.FakeAttemptLink
import io.lightine.tessera.emrtd.fakechip.FakeChip
import io.lightine.tessera.emrtd.fakechip.FakeCommandHandler
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.fakechip.RecordingObserver
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.emrtd.internal.pa.withKeys
import io.lightine.tessera.telemetry.TelemetryEvent
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.types.errors.ChipAccessDenied
import io.lightine.tessera.types.errors.ChipKeyCheckDigitMismatch
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * TES-346, device finding: a BAC-only passport refused a right MRZ key once with `63C0` right after
 * the phone's NFC session was renewed, and accepted a key from the same source on the next read.
 * A read through the coordinator (the platform entry points) tries access once more on the same
 * link after the first refusal in the read, with a key that has no try counter (MRZ, CAN) and looks
 * right; never a third time, never after the check-digit gate or a refusal on a later attempt.
 */
class ChipReadAccessRetryTest {
    private val checkTime = Instant.parse("2026-06-01T12:00:00Z")

    private class Events : TelemetrySink {
        val all = mutableListOf<ChipReadEvent>()

        override fun record(event: TelemetryEvent) {
            if (event is ChipReadEvent) all += event
        }

        fun of(step: ChipReadStep) = all.filter { it.step == step }
    }

    private class Driven(
        val ending: ChipReadEnding,
        val observer: RecordingObserver,
        val events: Events,
        val limits: List<ChipCommandTimeLimit>,
        val links: Int,
    )

    private inline fun withChips(block: (ChipCrypto, TestKeys) -> Unit) {
        withCrypto { c -> withKeys { keys -> block(c, keys) } }
    }

    /** Answers commands with [cla] and [ins] with the bare status word that [swFor] gives for the n-th one (from 1), or passes them on for `null`. */
    private fun answerNth(
        cla: Int,
        ins: Int,
        swFor: (Int) -> Int?,
    ): FakeCommandHandler {
        var n = 0
        return FakeCommandHandler { _, c ->
            if (c.cla == cla && c.ins == ins) swFor(++n)?.let { ResponseApdu(ByteArray(0), it) } else null
        }
    }

    private fun FakeChip.count(
        cla: Int,
        ins: Int,
    ): Int = received.count { (it[0].toInt() and 0xFF) == cla && (it[1].toInt() and 0xFF) == ins }

    /** Drives a read as a platform does, a new link per attempt; [linkFor] may drop a link. */
    private suspend fun drive(
        flow: FlowChip,
        options: ChipReadOptions = ChipReadOptions(FakeReadFlowChip.mrzKey(), setOf(1, 2), listOf(flow.anchor), checkTime),
        policy: LinkLossPolicy = LinkLossPolicy.Resume(2),
        linkFor: (Int) -> ApduTransport = { FakeAttemptLink(flow.chip) },
    ): Driven {
        val observer = RecordingObserver()
        val events = Events()
        val limits = mutableListOf<ChipCommandTimeLimit>()
        val coordinator = ChipReadCoordinator(options, policy, observer, ChipReader(events))
        assertTrue(coordinator.start())
        coordinator.waiting(0)
        var link = 0
        while (true) {
            coordinator.documentFound()
            coordinator.connecting()
            link++
            if (coordinator.read(linkFor(link), useTimeLimit = { limits += it }) == ChipReadAttemptOutcome.ENDED) break
            assertTrue(link <= 5, "too many links")
        }
        return Driven(observer.endings.single(), observer, events, limits, link)
    }

    /** The specimen's MRZ key with a wrong date-of-birth check digit. */
    private fun keyWithAWrongCheckDigit(): ChipAccessKey.Mrz {
        val line = FakeReadFlowChip.lines[1]
        val digit = if (line[19] == '0') '1' else '0'
        return ChipAccessKey.Mrz(line.substring(0, 9), line[9], line.substring(13, 19), digit, line.substring(21, 27), line[27])
    }

    private fun accessOutcomes(
        d: Driven,
        step: ChipReadStep,
    ) = d.events.of(step).map { it.outcome to it.statusWord }

    // --- BAC ----------------------------------------------------------------------------------------

    @Test
    fun bac__63C0_once__the_second_try_on_the_same_link_completes_and_both_tries_are_in_telemetry() =
        runTest {
            withChips { c, keys ->
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.BAC,
                        extraHandlers =
                            listOf(
                                answerNth(0x00, 0x82) {
                                    if (it ==
                                        1
                                    ) {
                                        0x63C0
                                    } else {
                                        null
                                    }
                                },
                            ),
                    )
                val d = drive(flow)
                val complete = assertIs<ChipReadEnding.Complete>(d.ending, "${d.ending}")
                assertEquals(ChipAccessProtocol.BAC, complete.read.access.protocol)
                assertEquals(1, complete.attempts, "the second try is no new attempt")
                assertEquals(1, d.links, "on the same link")
                assertTrue(d.observer.updates.none { it.state is ChipReadState.LinkLost })
                assertEquals(2, flow.chip.count(0x00, 0x82), "two EXTERNAL AUTHENTICATE")
                assertEquals(2, flow.chip.count(0x00, 0x84), "a fresh challenge for each")
                assertEquals(
                    listOf(ChipReadStepOutcome.FAILED to 0x63C0, ChipReadStepOutcome.SUCCEEDED to null),
                    accessOutcomes(d, ChipReadStep.BAC),
                )
                // Each try selects the eMRTD application itself before GET CHALLENGE.
                val commands = flow.chip.received.map { (it[1].toInt() and 0xFF) to (it[2].toInt() and 0xFF) }
                val challenges = commands.indices.filter { commands[it].first == 0x84 }
                for (at in challenges) {
                    val before = commands.subList(0, at).lastOrNull { it.first == 0xA4 }
                    assertEquals(0xA4 to 0x04, before, "SELECT by name before GET CHALLENGE at $at")
                }
                // The second try starts with the short limit again, then the long one for EXTERNAL AUTHENTICATE.
                assertEquals(
                    listOf(ChipCommandTimeLimit.LONG, ChipCommandTimeLimit.BEFORE_ACCESS, ChipCommandTimeLimit.LONG),
                    d.limits.take(3),
                )
            }
        }

    @Test
    fun bac__63C0_once_on_a_link_that_starts_in_the_application_as_on_iphone__each_try_selects_the_application_itself() =
        runTest {
            withChips { c, keys ->
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.BAC,
                        startInApplication = true,
                        extraHandlers = listOf(answerNth(0x00, 0x82) { if (it == 1) 0x63C0 else null }),
                    )
                flow.chip.startSelection = ChipStartSelection.EMRTD_APPLICATION
                val d = drive(flow)
                assertIs<ChipReadEnding.Complete>(d.ending, "${d.ending}")
                val commands = flow.chip.received.map { (it[1].toInt() and 0xFF) to (it[2].toInt() and 0xFF) }
                val challenges = commands.indices.filter { commands[it].first == 0x84 }
                assertEquals(2, challenges.size)
                for (at in challenges) {
                    assertEquals(
                        0xA4 to 0x04,
                        commands.subList(0, at).lastOrNull { it.first == 0xA4 },
                        "SELECT by name before GET CHALLENGE at $at",
                    )
                }
            }
        }

    @Test
    fun bac__refused_twice__ends_key_not_accepted_with_the_last_status_word_and_exactly_two_tries() =
        runTest {
            withChips { c, keys ->
                val sws = listOf(0x63C0, 0x6300)
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.BAC,
                        extraHandlers = listOf(answerNth(0x00, 0x82) { sws.getOrElse(it - 1) { 0x6300 } }),
                    )
                val d = drive(flow)
                val refused = assertIs<ChipReadEnding.KeyNotAccepted>(d.ending, "${d.ending}")
                assertEquals(ChipAccessDenied(ChipAccessProtocol.BAC, ChipAccessDenied.Reason.KEY_NOT_MATCHED, 0x6300), refused.error)
                assertEquals(1, refused.attempts)
                assertEquals(1, d.links)
                assertEquals(2, flow.chip.count(0x00, 0x82), "never a third EXTERNAL AUTHENTICATE")
                assertEquals(
                    listOf(ChipReadStepOutcome.FAILED to 0x63C0, ChipReadStepOutcome.FAILED to 0x6300),
                    accessOutcomes(d, ChipReadStep.BAC),
                )
            }
        }

    @Test
    fun bac__a_wrong_key_gets_two_tries_and_never_a_third_under_every_policy() =
        runTest {
            withChips { c, keys ->
                for (policy in listOf(LinkLossPolicy.End, LinkLossPolicy.ReadAgain(3), LinkLossPolicy.Resume(3))) {
                    val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                    val options = ChipReadOptions(FakeReadFlowChip.wrongMrzKey(), setOf(1, 2), listOf(flow.anchor), checkTime)
                    val d = drive(flow, options, policy)
                    assertIs<ChipReadEnding.KeyNotAccepted>(d.ending, "$policy")
                    assertEquals(2, flow.bac!!.refused, "$policy")
                    assertEquals(1, d.links, "$policy")
                }
            }
        }

    @Test
    fun bac__a_key_let_through_with_wrong_check_digits_gets_no_second_try() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val bad = keyWithAWrongCheckDigit()
                assertTrue(bad.checkDigitFindings.isNotEmpty())
                val options = ChipReadOptions(bad, setOf(1, 2), listOf(flow.anchor), checkTime, allowCheckDigitMismatch = true)
                val d = drive(flow, options)
                assertIs<ChipReadEnding.KeyNotAccepted>(d.ending, "${d.ending}")
                assertEquals(1, flow.chip.count(0x00, 0x82))
            }
        }

    @Test
    fun bac__the_check_digit_gate_sends_nothing_and_tries_nothing() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val bad = keyWithAWrongCheckDigit()
                val observer = RecordingObserver()
                val coordinator =
                    ChipReadCoordinator(ChipReadOptions(bad, setOf(1), checkTime = checkTime), LinkLossPolicy.Resume(2), observer)
                assertTrue(!coordinator.start())
                val rejected = assertIs<ChipReadEnding.KeyRejected>(observer.endings.single())
                assertIs<ChipKeyCheckDigitMismatch>(rejected.error)
                assertTrue(flow.chip.received.isEmpty())
            }
        }

    @Test
    fun bac__a_refusal_on_a_later_attempt_after_a_lost_link_gets_no_second_try() =
        runTest {
            withChips { c, keys ->
                // The first link is granted access and drops at the first protected command; the
                // second link's chip refuses: it may be another document, so no second try.
                var link = 0
                val refuseOnLinkTwo = answerNth(0x00, 0x82) { if (link == 2) 0x6300 else null }
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, extraHandlers = listOf(refuseOnLinkTwo))
                val d =
                    drive(flow) { n ->
                        link = n
                        if (n == 1) DropAtFirstProtected(FakeAttemptLink(flow.chip)) else FakeAttemptLink(flow.chip)
                    }
                val refused = assertIs<ChipReadEnding.KeyNotAccepted>(d.ending, "${d.ending}")
                assertEquals(2, refused.attempts)
                assertEquals(2, flow.chip.count(0x00, 0x82), "one on each link, no second try on the second")
            }
        }

    /** Passes commands on until the first protected one (CLA 0C), which it drops as a lost link. */
    private class DropAtFirstProtected(
        private val base: ApduTransport,
    ) : ApduTransport by base {
        override suspend fun transceive(command: ByteArray): ByteArray {
            if ((command[0].toInt() and 0xFF) == 0x0C) throw ApduTransportException(io.lightine.tessera.types.errors.NfcTagLost)
            return base.transceive(command)
        }
    }

    // --- PACE with an MRZ key and with a CAN ----------------------------------------------------

    @Test
    fun pace_mrz__63C2_at_mutual_authentication_once__the_second_try_completes() =
        runTest {
            withChips { c, keys ->
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.PACE_ONLY,
                        extraHandlers =
                            listOf(
                                answerNth(0x00, 0x86) {
                                    if (it ==
                                        1
                                    ) {
                                        0x63C2
                                    } else {
                                        null
                                    }
                                },
                            ),
                    )
                val d = drive(flow)
                val complete = assertIs<ChipReadEnding.Complete>(d.ending, "${d.ending}")
                assertEquals(ChipAccessProtocol.PACE, complete.read.access.protocol)
                assertEquals(1, complete.attempts)
                assertEquals(2, flow.chip.count(0x00, 0x22), "MSE:Set AT once per try")
                assertEquals(
                    listOf(ChipReadStepOutcome.FAILED to 0x63C2, ChipReadStepOutcome.SUCCEEDED to null),
                    accessOutcomes(d, ChipReadStep.PACE),
                )
            }
        }

    @Test
    fun pace_mrz__refused_twice__ends_key_not_accepted_and_never_a_third_try() =
        runTest {
            withChips { c, keys ->
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.PACE_ONLY,
                        extraHandlers =
                            listOf(
                                answerNth(0x00, 0x86) {
                                    if (it ==
                                        1
                                    ) {
                                        0x63C2
                                    } else {
                                        0x6300
                                    }
                                },
                            ),
                    )
                val d = drive(flow)
                val refused = assertIs<ChipReadEnding.KeyNotAccepted>(d.ending, "${d.ending}")
                assertEquals(ChipAccessDenied(ChipAccessProtocol.PACE, ChipAccessDenied.Reason.KEY_NOT_MATCHED, 0x6300), refused.error)
                assertEquals(1, refused.attempts)
                assertEquals(2, flow.chip.count(0x00, 0x86))
                assertEquals(2, d.events.of(ChipReadStep.PACE).size)
            }
        }

    @Test
    fun pace_can__a_refusal_is_tried_once_more() =
        runTest {
            withChips { c, keys ->
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.PACE_ONLY,
                        can = "123456",
                        extraHandlers = listOf(answerNth(0x00, 0x86) { if (it == 1) 0x6300 else null }),
                    )
                val options = ChipReadOptions(ChipAccessKey.Can("123456"), setOf(1, 2), listOf(flow.anchor), checkTime)
                val d = drive(flow, options)
                assertIs<ChipReadEnding.Complete>(d.ending, "${d.ending}")
                assertEquals(2, flow.chip.count(0x00, 0x86))
            }
        }
}
