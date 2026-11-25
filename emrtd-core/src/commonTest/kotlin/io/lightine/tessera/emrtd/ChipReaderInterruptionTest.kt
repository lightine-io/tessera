package io.lightine.tessera.emrtd

import io.lightine.tessera.emrtd.fakechip.FakeChip
import io.lightine.tessera.emrtd.fakechip.FakePaceCam
import io.lightine.tessera.emrtd.fakechip.FakePaceMath
import io.lightine.tessera.emrtd.fakechip.FakePaceSuite
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip.ECDH_CAM_AES128
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.StandardDomainParameterValues
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.flow.ReadFlow
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.emrtd.internal.pa.withKeys
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Instant

/**
 * TES-345: an interruption at any command of a read ends it cleanly. For every command of a whole
 * read (access, each file chunk, chip authentication, active authentication), the test stops the
 * link at that command and checks the five points the platforms rely on: a typed result (or the
 * cancellation, only that), nothing sent after the interruption, every secure channel closed (its
 * keys wiped), and a new read on a new transport right after that completes as a first read does.
 *
 * The platform part (closing `IsoDep`, ending the Core NFC session) is tested in `emrtd-nfc` and
 * `emrtd-nfc-ios`; this is the part they share.
 */
class ChipReaderInterruptionTest {
    private val checkTime = Instant.parse("2026-06-01T12:00:00Z")

    private class Setup(
        val name: String,
        val build: (ChipCrypto, TestKeys) -> FlowChip,
    )

    private val setups =
        listOf(
            Setup("BAC, chip authentication") { c, k -> FakeReadFlowChip.build(c, k, FlowAccess.BAC, caInApplication = true) },
            Setup("BAC, active authentication") { c, k -> FakeReadFlowChip.build(c, k, FlowAccess.BAC, activeAuthentication = true) },
            Setup("PACE, chip authentication") { c, k -> FakeReadFlowChip.build(c, k, FlowAccess.PACE_ONLY, caInApplication = true) },
            Setup("PACE-CAM") { c, k ->
                FakeReadFlowChip.build(
                    c,
                    k,
                    FlowAccess.PACE_ONLY,
                    paceSuites = listOf(FakePaceSuite(ECDH_CAM_AES128, 13)),
                    cam =
                        FakePaceCam(
                            FakePaceMath.randomPrivateKey(c, StandardDomainParameterValues.forId(13) as EcDomainParameters, c::randomBytes),
                        ),
                )
            },
        )

    private fun options(flow: FlowChip) = ChipReadOptions(FakeReadFlowChip.mrzKey(), setOf(1, 2), listOf(flow.anchor), checkTime)

    /** Stops the link at command [at] (0-based) the way [interruption] says; counts what reached the chip. */
    private class InterruptedLink(
        private val chip: FakeChip,
        private val at: Int,
        private val interruption: Interruption,
    ) : ApduTransport by chip {
        var sent = 0

        override suspend fun transceive(command: ByteArray): ByteArray {
            val index = sent++
            if (index > at) fail("command $index was sent after the interruption at $at")
            if (index < at) return chip.transceive(command)
            return when (interruption) {
                // The command never reached the chip: the link was already gone.
                Interruption.LINK_LOST -> {
                    throw ApduTransportException(NfcTagLost)
                }

                // The app or the person ended the session while the command was out.
                Interruption.SESSION_ENDED -> {
                    throw ApduTransportException(NfcUnavailable(NfcUnavailable.Reason.CLOSED))
                }

                // The chip answered, then the app's coroutine was cancelled: the answer is dropped.
                Interruption.CANCELLED -> {
                    chip.transceive(command)
                    throw CancellationException("the app cancelled the read")
                }
            }
        }
    }

    private enum class Interruption(
        val error: NfcError?,
    ) {
        LINK_LOST(NfcTagLost),
        SESSION_ENDED(NfcUnavailable(NfcUnavailable.Reason.CLOSED)),
        CANCELLED(null),
    }

    private suspend fun commandsOfACleanRead(
        c: ChipCrypto,
        keys: TestKeys,
        setup: Setup,
    ): Int {
        val flow = setup.build(c, keys)
        assertIs<ChipReadResult.Complete>(ChipReader(NoOpTelemetrySink).read(flow.chip, options(flow)), setup.name)
        return flow.chip.received.size
    }

    private suspend fun sweep(interruption: Interruption) {
        withCrypto { c ->
            withKeys { keys ->
                for (setup in setups) {
                    val total = commandsOfACleanRead(c, keys, setup)
                    assertTrue(total > 10, setup.name)
                    for (at in 0 until total) {
                        val where = "${setup.name}, interrupted at command $at of $total"
                        val flow = setup.build(c, keys)
                        val link = InterruptedLink(flow.chip, at, interruption)
                        val run = ReadFlow(link, options(flow), NoOpTelemetrySink, { c })

                        if (interruption.error == null) {
                            assertFailsWith<CancellationException>(where) { run.run() }
                        } else {
                            val error =
                                when (val result = run.run()) {
                                    is ChipReadResult.Complete -> fail("$where: $result")
                                    is ChipReadResult.Partial -> result.stoppedBy
                                    is ChipReadResult.Failed -> result.error
                                }
                            assertEquals(interruption.error, error, where)
                        }

                        assertEquals(at + 1, link.sent, "$where: nothing is sent after the interruption")
                        assertTrue(run.openedChannels.none { it.isOpen }, "$where: every secure channel is closed")
                        run.mappingEvidence?.chipAuthenticationData?.let { data ->
                            assertTrue(data.all { it == 0.toByte() }, "$where: the mapping evidence is wiped")
                        }
                        // A new read on a new transport right after behaves like a first read.
                        val next = setup.build(c, keys)
                        assertIs<ChipReadResult.Complete>(ChipReader(NoOpTelemetrySink).read(next.chip, options(next)), where)
                    }
                }
            }
        }
    }

    @Test
    fun aLinkLostAtAnyCommandIsATypedStopAndNothingIsSentAfterIt() = runTest { sweep(Interruption.LINK_LOST) }

    @Test
    fun aSessionEndedAtAnyCommandIsATypedStopAndNothingIsSentAfterIt() = runTest { sweep(Interruption.SESSION_ENDED) }

    @Test
    fun aCancellationAtAnyCommandIsThrownAndNothingIsSentAfterIt() = runTest { sweep(Interruption.CANCELLED) }
}
