package io.lightine.tessera.emrtd.sim

import io.lightine.tessera.emrtd.ApduTransport
import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.emrtd.ChipCheckKind
import io.lightine.tessera.emrtd.ChipCheckReason
import io.lightine.tessera.emrtd.ChipFileContext
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReadResult
import io.lightine.tessera.emrtd.ChipReader
import io.lightine.tessera.emrtd.fakechip.ChipFaultAction
import io.lightine.tessera.emrtd.fakechip.ChipReply
import io.lightine.tessera.emrtd.fakechip.DeviceChipProfile
import io.lightine.tessera.emrtd.fakechip.FakeCa
import io.lightine.tessera.emrtd.fakechip.FakeCaKey
import io.lightine.tessera.emrtd.fakechip.FakeCaOffer
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.ScriptedChip
import io.lightine.tessera.emrtd.fakechip.ScriptedTransport
import io.lightine.tessera.emrtd.internal.ca.ChipAuthentication
import io.lightine.tessera.emrtd.internal.securityinfo.SecurityInfoParser
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.vocabulary.CheckOutcome
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Catalogue group D (chip authentication), card TES-346: through [ChipReader] on BAC fakes with
 * chip authentication in the application. Sources: ICAO TR RF and Protocol Testing Part 4 v3.0
 * (P4) unit ISO7816_G; Doc 9303 Part 11, 6.2.
 */
class CatalogueGroupDTest {
    private fun ca(read: io.lightine.tessera.emrtd.ChipRead) =
        read.outcomeOf(ChipCheckKind.ChipAuthentication(ChipFileContext.EMRTD_APPLICATION))

    /**
     * D-10 (P4 7.7.15, ISO7816_G_15a-i). [DIFF] P4 says the inspection fails; the SDK reports the
     * fact `ChipAuthentication` failed (CHIP_REFUSED), keeps the old session and reads on. Note: the
     * fact does not carry the status word (the catalogue expected it to); the word goes only to the
     * telemetry step event. 6C00 is never sent again with another Le for a chip-authentication command.
     */
    @Test
    fun d10_everyRefusalWordAtSetAtAndAtGeneralAuthenticate_isAFailedFactAndTheReadGoesOnWithTheOldKeys() =
        runTest {
            Sim.withChips { c, keys ->
                val words = listOf(0x6300, 0x6700, 0x6800, 0x6900, 0x6A00, 0x6B00, 0x6C00, 0x6E00, 0x6F00)
                for (atGeneralAuthenticate in listOf(false, true)) {
                    for (sw in words) {
                        val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, caInApplication = true)
                        if (atGeneralAuthenticate) flow.ca!!.refuseGeneralAuthenticate = sw else flow.ca!!.refuseSetAt = sw
                        val result = Sim.read(flow)
                        if (sw == 0x6C00) {
                            // The fake chip mirrors DO'99' in the outer status word, as chips do.
                            // An outer 6Cxx inside a session is taken as a plain length hint, even
                            // with a valid checksum over the protected body (SecureMessaging.unprotect):
                            // the session ends (Partial, SM lost) instead of the fact and reading on.
                            // [JDG] finding of TES-346, harsher than the catalogue's expectation, never unsafe.
                            val partial = assertIs<ChipReadResult.Partial>(result, "6C00 GA=$atGeneralAuthenticate")
                            val lost = assertIs<io.lightine.tessera.types.errors.ChipSecureMessagingLost>(partial.stoppedBy)
                            assertEquals(io.lightine.tessera.types.errors.ChipSecureMessagingLost.Reason.PLAIN_LENGTH_HINT, lost.reason)
                        } else {
                            val read =
                                assertIs<ChipReadResult.Complete>(
                                    result,
                                    "${sw.toString(16)} GA=$atGeneralAuthenticate: $result",
                                ).read
                            assertEquals(
                                CheckOutcome.Failed(ChipCheckReason.CHIP_REFUSED),
                                ca(read),
                                "${sw.toString(16)} GA=$atGeneralAuthenticate",
                            )
                            assertEquals(
                                CheckOutcome.Passed,
                                read.outcomeOf(ChipCheckKind.DataGroupHashMatches(2)),
                                "read goes on under the old keys",
                            )
                        }
                        assertEquals(0, flow.ca!!.restarts, "old session kept")
                        val step = if (atGeneralAuthenticate) 0x86 else 0x22
                        assertEquals(
                            1,
                            flow.ca!!.commands.count { it.first == step },
                            "the refused command is not sent again (${sw.toString(16)})",
                        )
                    }
                }
            }
        }

    /** D-06 (P4 7.7.10, ISO7816_G_10): two reads use different ephemeral keys. */
    @Test
    fun d06_twoReadsOfTheSameChip_sendDifferentEphemeralPublicKeys() =
        runTest {
            Sim.withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, caInApplication = true)
                assertIs<ChipReadResult.Complete>(Sim.read(flow))
                assertIs<ChipReadResult.Complete>(
                    Sim.read(
                        flow,
                        transport =
                            io.lightine.tessera.emrtd.fakechip
                                .FakeAttemptLink(flow.chip),
                    ),
                )
                val generalAuthenticates =
                    flow.ca!!
                        .commands.indices
                        .filter { flow.ca!!.commands[it].first == 0x86 }
                assertEquals(2, generalAuthenticates.size)
                val (first, second) = generalAuthenticates.map { flow.ca!!.commandData[it] }
                assertFalse(first.contentEquals(second), "a fresh ephemeral key per read")
            }
        }

    /** D-03 (P4 7.7.3 to 7.7.5, ISO7816_G_03 to G_05): one, two or three key references in DG14. */
    @Test
    fun d03_oneTwoOrThreeKeysInDg14_eachGivesAPlanAndTheKeyReferenceIsSentWhenThereAreSeveral() =
        Sim.withChips { c, _ ->
            for (count in 1..3) {
                val keyList = (1..count).map { FakeCaKey.generate(c, 13, keyId = if (count == 1) null else it) }
                val offers = keyList.map { FakeCaOffer(dh = false, cipherCode = 2, keyId = it.keyId) }
                val infos = SecurityInfoParser.parse(FakeCa.securityInfos(offers, keyList))
                val plan = ChipAuthentication.plan(c, infos)
                assertEquals(count, plan.suites.size, "$count keys")
                assertTrue(plan.skipped.isEmpty(), "${plan.skipped}")
            }
        }

    /**
     * D-15 and K-06, core part (device evidence: MSE:Set KAT unanswered for 18.3 s on a connected
     * tag). The core reader has no time limit of its own: it waits for the transport. Through a
     * scripted link where the chip never answers MSE:Set KAT, the caller's time limit (the platform
     * entry points set 5 s per command; their tests cover that part) is what ends the wait, at 5 s
     * of virtual time, and nothing is sent after the silent command.
     */
    @Test
    fun d15_aSilentMseSetKat_isCutByTheCallersTimeLimitAndNothingFollows() =
        runTest {
            Sim.withChips { c, keys ->
                val flow = DeviceChipProfile.build(c, keys)
                val script = ScriptedChip(flow.chip, DeviceChipProfile.latency)
                script.fault(ChipFaultAction.Silence) { it.ins == 0x22 && it.apdu?.p2 == 0xA6 }
                val options = ChipReadOptions(DeviceChipProfile.key(), setOf(1, 2, 14, 15), listOf(flow.anchor), Sim.checkTime)
                var silentAt = -1L
                val link = ScriptedTransport(script) { currentTime }
                val limited =
                    object : ApduTransport by link {
                        override suspend fun transceive(command: ByteArray): ByteArray {
                            val start = currentTime
                            return withTimeoutOrNull(5_000) { link.transceive(command) } ?: run {
                                silentAt = start
                                throw ApduTransportException(NfcTimeout(5_000))
                            }
                        }
                    }
                val read = ChipReader(NoOpTelemetrySink).read(limited, options)
                val kat = script.exchanges.single { it.reply === ChipReply.Silent }
                assertEquals(kat, script.exchanges.last(), "nothing sent after the silent command")
                assertEquals(kat.sentAtMillis, silentAt)
                assertEquals(silentAt + 5_000, currentTime, "the loss is declared at 5 s, not 18.3 s")
                assertIs<ChipReadResult.Partial>(read, "$read")
            }
        }

    /** D-24 (Part 11, 6.1): INTERNAL AUTHENTICATE refused with 6985, 6D00, 6A86, 6982 or 6700: a failed fact, the read goes on. */
    @Test
    fun d24_internalAuthenticateRefusedWithEachWord_isAFailedFactAndTheReadGoesOn() =
        runTest {
            Sim.withChips { c, keys ->
                for (sw in listOf(0x6985, 0x6D00, 0x6A86, 0x6982, 0x6700)) {
                    val flow =
                        FakeReadFlowChip.build(
                            c,
                            keys,
                            FlowAccess.BAC,
                            activeAuthentication = true,
                            extraHandlers = listOf(Sim.answer(0x00, 0x88, sw)),
                        )
                    val read = assertIs<ChipReadResult.Complete>(Sim.read(flow, dataGroups = setOf(1, 2, 15)), sw.toString(16)).read
                    assertEquals(
                        CheckOutcome.Failed(ChipCheckReason.CHIP_REFUSED),
                        read.outcomeOf(ChipCheckKind.ActiveAuthentication),
                        sw.toString(16),
                    )
                    assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.DataGroupHashMatches(15)))
                }
            }
        }
}
