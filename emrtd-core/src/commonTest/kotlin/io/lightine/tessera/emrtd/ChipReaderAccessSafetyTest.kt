package io.lightine.tessera.emrtd

import io.lightine.tessera.emrtd.fakechip.FakeChip
import io.lightine.tessera.emrtd.fakechip.FakeCommandHandler
import io.lightine.tessera.emrtd.fakechip.FakePaceSuite
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip.ECDH_GM_AES128
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip.ID_PACE
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.ChipCryptoException
import io.lightine.tessera.emrtd.internal.crypto.HashAlgorithm
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.emrtd.internal.pa.withKeys
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.types.errors.ChipAccessDenied
import io.lightine.tessera.types.errors.ChipError
import io.lightine.tessera.types.errors.ChipSecureMessagingLost
import io.lightine.tessera.types.errors.ChipUnsupportedAlgorithm
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.vocabulary.CheckOutcome
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The safety rules of the read: one authentication attempt per call to read, nothing automatic,
 * and `read()` never throws for a chip problem. Every test checks what the fake chip received, not
 * only the result. Fake chips from the project specimen; they run on every platform.
 */
class ChipReaderAccessSafetyTest {
    private val checkTime = Instant.parse("2026-06-01T12:00:00Z")

    private fun options(
        flow: FlowChip,
        key: ChipAccessKey = FakeReadFlowChip.mrzKey(),
    ) = ChipReadOptions(key, setOf(1, 2), listOf(flow.anchor), checkTime)

    private suspend fun read(
        flow: FlowChip,
        transport: ApduTransport = flow.chip,
        key: ChipAccessKey = FakeReadFlowChip.mrzKey(),
        crypto: (() -> ChipCrypto)? = null,
    ): ChipReadResult {
        val reader = if (crypto == null) ChipReader(NoOpTelemetrySink) else ChipReader(NoOpTelemetrySink, crypto)
        return reader.read(transport, options(flow, key))
    }

    private inline fun withChips(block: (ChipCrypto, TestKeys) -> Unit) {
        withCrypto { c -> withKeys { keys -> block(c, keys) } }
    }

    private fun failed(r: ChipReadResult): ChipError = assertIs<ChipReadResult.Failed>(r, "$r").error

    // The plain commands the chip received, by CLA and INS.
    private fun FakeChip.count(
        cla: Int,
        ins: Int,
    ): Int = received.count { (it[0].toInt() and 0xFF) == cla && (it[1].toInt() and 0xFF) == ins }

    private fun ByteArray.isCommand(
        cla: Int,
        ins: Int,
    ): Boolean = (this[0].toInt() and 0xFF) == cla && (this[1].toInt() and 0xFF) == ins

    /** Answers commands with [cla] and [ins] (and [p1] when given) with the bare [sw]. */
    private fun answer(
        cla: Int,
        ins: Int,
        sw: Int,
        p1: Int? = null,
    ) = FakeCommandHandler { _, c ->
        if (c.cla == cla && c.ins == ins && (p1 == null || c.p1 == p1)) ResponseApdu(ByteArray(0), sw) else null
    }

    /** Two PACE suites the reader runs, so a second suite is always there to be (wrongly) tried. */
    private val twoSuites = listOf(FakePaceSuite(ECDH_GM_AES128, 13), FakePaceSuite(ECDH_GM_AES128, 12))

    // --- One authentication attempt: a wrong key ------------------------------------------------

    @Test
    fun aWrongKeyGetsExactlyOneExternalAuthenticateAndNothingAfterIt() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val error = failed(read(flow, key = FakeReadFlowChip.wrongMrzKey()))
                assertEquals(ChipAccessDenied(ChipAccessProtocol.BAC, ChipAccessDenied.Reason.KEY_NOT_MATCHED, 0x6300), error)
                assertEquals(1, flow.chip.count(0x00, 0x82))
                assertTrue(
                    flow.chip.received
                        .last()
                        .isCommand(0x00, 0x82),
                    "nothing after EXTERNAL AUTHENTICATE",
                )
            }
        }

    @Test
    fun aWrongKeyGetsExactlyOnePaceRunAndNothingAfterIt() =
        runTest {
            withChips { c, keys ->
                // PACE and BAC both offered, two suites: neither another suite nor BAC follows.
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_AND_BAC, paceSuites = twoSuites)
                val error = failed(read(flow, key = FakeReadFlowChip.wrongMrzKey()))
                assertEquals(ChipAccessDenied(ChipAccessProtocol.PACE, ChipAccessDenied.Reason.KEY_NOT_MATCHED, 0x6300), error)
                assertEquals(1, flow.chip.count(0x00, 0x22))
                assertEquals(3, flow.chip.count(0x10, 0x86))
                assertEquals(1, flow.chip.count(0x00, 0x86))
                assertTrue(
                    flow.chip.received
                        .last()
                        .isCommand(0x00, 0x86),
                    "nothing after the last GENERAL AUTHENTICATE",
                )
                assertEquals(0, flow.chip.count(0x00, 0x84) + flow.chip.count(0x00, 0x82), "no BAC after PACE")
            }
        }

    @Test
    fun externalAuthenticateIsNeverSentAgainOnWrongLe() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, extraHandlers = listOf(answer(0x00, 0x82, 0x6C28)))
                val error = failed(read(flow))
                assertEquals(ChipAccessDenied(ChipAccessProtocol.BAC, ChipAccessDenied.Reason.CHIP_REFUSED, 0x6C28), error)
                assertEquals(1, flow.chip.count(0x00, 0x82))
                assertTrue(
                    flow.chip.received
                        .last()
                        .isCommand(0x00, 0x82),
                )
            }
        }

    // --- No second suite once GENERAL AUTHENTICATE was sent -------------------------------------

    /** A transport whose command limit drops to [shrunk] bytes once the chip has answered the first GENERAL AUTHENTICATE. */
    private class ShrinksAfterFirstGeneralAuthenticate(
        private val inner: FakeChip,
        private val shrunk: Int,
    ) : ApduTransport {
        private var shrink = false
        override val maxCommandLength: Int get() = if (shrink) shrunk else inner.maxCommandLength
        override val supportsExtendedLength: Boolean get() = inner.supportsExtendedLength

        override suspend fun transceive(command: ByteArray): ByteArray {
            val answer = inner.transceive(command)
            if ((command[1].toInt() and 0xFF) == 0x86) shrink = true
            return answer
        }
    }

    @Test
    fun aCommandTooLongAfterGeneralAuthenticateEndsTheReadWithoutAnotherSuite() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_AND_BAC, paceSuites = twoSuites)
                val error = failed(read(flow, transport = ShrinksAfterFirstGeneralAuthenticate(flow.chip, 30)))
                assertEquals(
                    ChipUnsupportedAlgorithm(ChipAccessProtocol.PACE, ChipUnsupportedAlgorithm.Reason.EXTENDED_LENGTH_REQUIRED),
                    error,
                )
                assertEquals(1, flow.chip.count(0x00, 0x22), "one MSE:Set AT")
                assertEquals(1, flow.chip.count(0x10, 0x86), "one GENERAL AUTHENTICATE")
                assertTrue(
                    flow.chip.received
                        .last()
                        .isCommand(0x10, 0x86),
                )
            }
        }

    @Test
    fun aSuiteThatCannotFitIsSkippedBeforeMseSetAt() =
        runTest {
            withChips { c, keys ->
                // DH 2048-bit with AES ranks first but needs extended length on this short reader.
                val dhAes = FakePaceSuite("$ID_PACE.1.2", 2)
                val ecdh3Des = FakePaceSuite("$ID_PACE.2.1", 13)
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY, paceSuites = listOf(dhAes, ecdh3Des))
                val complete = assertIs<ChipReadResult.Complete>(read(flow))
                assertEquals("$ID_PACE.2.1", complete.read.access.paceProtocolOid)
                assertEquals(1, flow.chip.count(0x00, 0x22), "nothing sent for the suite that cannot fit")
            }
        }

    // --- Counter and blocked status words -------------------------------------------------------

    @Test
    fun aPasswordStatusWordToMseSetAtEndsTheReadWithItsStatusWord() =
        runTest {
            withChips { c, keys ->
                val expected =
                    mapOf(
                        0x63C3 to ChipAccessDenied.Reason.KEY_TRIES_LIMITED,
                        0x63CF to ChipAccessDenied.Reason.KEY_TRIES_LIMITED,
                        0x63C1 to ChipAccessDenied.Reason.KEY_SUSPENDED,
                        0x63C0 to ChipAccessDenied.Reason.KEY_BLOCKED,
                        0x6283 to ChipAccessDenied.Reason.KEY_DEACTIVATED,
                        0x6982 to ChipAccessDenied.Reason.CHIP_REFUSED,
                        0x6983 to ChipAccessDenied.Reason.KEY_BLOCKED,
                        0x6984 to ChipAccessDenied.Reason.KEY_DEACTIVATED,
                        0x6985 to ChipAccessDenied.Reason.KEY_SUSPENDED,
                    )
                for ((sw, reason) in expected) {
                    val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_AND_BAC, paceSuites = twoSuites)
                    flow.pace!!.refuseSetAt = sw
                    val error = failed(read(flow))
                    assertEquals(ChipAccessDenied(ChipAccessProtocol.PACE, reason, sw), error, sw.toString(16))
                    assertEquals(1, flow.chip.count(0x00, 0x22), "no second suite: ${sw.toString(16)}")
                    assertTrue(
                        flow.chip.received
                            .last()
                            .isCommand(0x00, 0x22),
                        "nothing after MSE:Set AT: ${sw.toString(16)}",
                    )
                }
            }
        }

    @Test
    fun aSuiteRefusalToMseSetAtStillTriesTheNextSuite() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY, paceSuites = twoSuites)
                flow.pace!!.refuseSetAt = 0x6A80
                val error = failed(read(flow))
                assertEquals(
                    ChipUnsupportedAlgorithm(ChipAccessProtocol.PACE, ChipUnsupportedAlgorithm.Reason.CHIP_REFUSED_SUITE, 0x6A80),
                    error,
                )
                assertEquals(2, flow.chip.count(0x00, 0x22))
                assertEquals(0, flow.chip.count(0x10, 0x86))
            }
        }

    @Test
    fun everyStatusWordAtMutualAuthenticationReachesTheError() =
        runTest {
            withChips { c, keys ->
                val expected =
                    mapOf(
                        0x6300 to ChipAccessDenied.Reason.KEY_NOT_MATCHED,
                        0x63C2 to ChipAccessDenied.Reason.KEY_NOT_MATCHED,
                        0x63C1 to ChipAccessDenied.Reason.KEY_SUSPENDED,
                        0x63C0 to ChipAccessDenied.Reason.KEY_BLOCKED,
                        0x6982 to ChipAccessDenied.Reason.CHIP_REFUSED,
                        0x6983 to ChipAccessDenied.Reason.KEY_BLOCKED,
                        0x6984 to ChipAccessDenied.Reason.KEY_DEACTIVATED,
                        0x6985 to ChipAccessDenied.Reason.KEY_SUSPENDED,
                    )
                for ((sw, reason) in expected) {
                    val flow =
                        FakeReadFlowChip.build(
                            c,
                            keys,
                            FlowAccess.PACE_AND_BAC,
                            paceSuites = twoSuites,
                            extraHandlers = listOf(answer(0x00, 0x86, sw)),
                        )
                    assertEquals(ChipAccessDenied(ChipAccessProtocol.PACE, reason, sw), failed(read(flow)), sw.toString(16))
                    assertEquals(1, flow.chip.count(0x00, 0x22))
                    assertEquals(1, flow.chip.count(0x00, 0x86))
                    assertTrue(
                        flow.chip.received
                            .last()
                            .isCommand(0x00, 0x86),
                    )
                }
            }
        }

    @Test
    fun everyStatusWordAtAnEarlierPaceStepReachesTheError() =
        runTest {
            withChips { c, keys ->
                val expected =
                    mapOf(
                        0x6300 to ChipAccessDenied.Reason.CHIP_REFUSED,
                        0x63C2 to ChipAccessDenied.Reason.CHIP_REFUSED,
                        0x63C0 to ChipAccessDenied.Reason.KEY_BLOCKED,
                        0x6982 to ChipAccessDenied.Reason.CHIP_REFUSED,
                        0x6983 to ChipAccessDenied.Reason.KEY_BLOCKED,
                        0x6984 to ChipAccessDenied.Reason.KEY_DEACTIVATED,
                        0x6985 to ChipAccessDenied.Reason.KEY_SUSPENDED,
                    )
                for ((sw, reason) in expected) {
                    val flow =
                        FakeReadFlowChip.build(
                            c,
                            keys,
                            FlowAccess.PACE_AND_BAC,
                            paceSuites = twoSuites,
                            extraHandlers = listOf(answer(0x10, 0x86, sw)),
                        )
                    val error = failed(read(flow))
                    assertEquals(ChipAccessDenied(ChipAccessProtocol.PACE, reason, sw), error, sw.toString(16))
                    assertEquals(1, flow.chip.count(0x00, 0x22))
                    assertEquals(1, flow.chip.count(0x10, 0x86))
                    assertTrue(
                        flow.chip.received
                            .last()
                            .isCommand(0x10, 0x86),
                    )
                }
            }
        }

    @Test
    fun everyStatusWordAtExternalAuthenticateReachesTheError() =
        runTest {
            withChips { c, keys ->
                val expected =
                    mapOf(
                        0x6300 to ChipAccessDenied.Reason.KEY_NOT_MATCHED,
                        0x63C2 to ChipAccessDenied.Reason.KEY_NOT_MATCHED,
                        0x63C1 to ChipAccessDenied.Reason.KEY_SUSPENDED,
                        0x63C0 to ChipAccessDenied.Reason.KEY_BLOCKED,
                        0x6982 to ChipAccessDenied.Reason.KEY_NOT_MATCHED,
                        0x6983 to ChipAccessDenied.Reason.KEY_BLOCKED,
                        0x6984 to ChipAccessDenied.Reason.KEY_DEACTIVATED,
                        0x6985 to ChipAccessDenied.Reason.CHIP_REFUSED,
                    )
                for ((sw, reason) in expected) {
                    val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, extraHandlers = listOf(answer(0x00, 0x82, sw)))
                    assertEquals(ChipAccessDenied(ChipAccessProtocol.BAC, reason, sw), failed(read(flow)), sw.toString(16))
                    assertEquals(1, flow.chip.count(0x00, 0x82))
                    assertTrue(
                        flow.chip.received
                            .last()
                            .isCommand(0x00, 0x82),
                    )
                }
            }
        }

    // --- A bare answer inside secure messaging --------------------------------------------------

    @Test
    fun aBareErrorInsideSecureMessagingEndsTheReadAndNothingFollows() =
        runTest {
            withChips { c, keys ->
                for (access in listOf(FlowAccess.BAC, FlowAccess.PACE_ONLY)) {
                    // The second protected SELECT of an EF (EF.SOD) is answered bare.
                    var selects = 0
                    var bareAt = -1
                    val bare =
                        FakeCommandHandler { chip, cmd ->
                            if (cmd.cla == 0x0C && cmd.ins == 0xA4 && cmd.p1 == 0x02 && ++selects == 2) {
                                bareAt = chip.received.lastIndex
                                ResponseApdu(ByteArray(0), 0x6A82)
                            } else {
                                null
                            }
                        }
                    val flow = FakeReadFlowChip.build(c, keys, access, leadingHandlers = listOf(bare))
                    val partial = assertIs<ChipReadResult.Partial>(read(flow), "$access")
                    assertEquals(ChipSecureMessagingLost(ChipSecureMessagingLost.Reason.CHIP_ENDED_SESSION, 0x6A82), partial.stoppedBy)
                    assertEquals(flow.chip.received.lastIndex, bareAt, "nothing sent after the bare answer: $access")
                }
            }
        }

    // --- read() is total ------------------------------------------------------------------------

    /** Throws [failure] instead of sending the first command [at] matches; otherwise passes to [inner]. */
    private class Throwing(
        private val inner: FakeChip,
        private val at: (ByteArray) -> Boolean,
        private val failure: () -> Throwable,
    ) : ApduTransport {
        override val maxCommandLength: Int get() = inner.maxCommandLength
        override val supportsExtendedLength: Boolean get() = inner.supportsExtendedLength

        override suspend fun transceive(command: ByteArray): ByteArray {
            if (at(command)) throw failure()
            return inner.transceive(command)
        }
    }

    private val first: (ByteArray) -> Boolean = { true }
    private val authentication: (ByteArray) -> Boolean = { (it[1].toInt() and 0xFF) == 0x82 || (it[1].toInt() and 0xFF) == 0x86 }
    private val protectedRead: (ByteArray) -> Boolean = { (it[0].toInt() and 0xFF) == 0x0C && (it[1].toInt() and 0xFF) == 0xB0 }

    @Test
    fun aPlatformExceptionFromTheTransportIsALostTag() =
        runTest {
            withChips { c, keys ->
                val failures = listOf({ UnsupportedOperationException("x") }, { IllegalStateException("x") }, { SecurityExceptionLike() })
                for (f in failures) {
                    for (access in listOf(FlowAccess.BAC, FlowAccess.PACE_ONLY)) {
                        val flow = FakeReadFlowChip.build(c, keys, access)
                        assertEquals(NfcTagLost, failed(read(flow, transport = Throwing(flow.chip, authentication, f))), "$access")
                        // After access: Partial.
                        val after = FakeReadFlowChip.build(c, keys, access)
                        val partial =
                            assertIs<ChipReadResult.Partial>(read(after, transport = Throwing(after.chip, protectedRead, f)), "$access")
                        assertEquals(NfcTagLost, partial.stoppedBy)
                    }
                }
            }
        }

    private class SecurityExceptionLike : RuntimeException("tag out of date")

    @Test
    fun cancellationStillCancels() =
        runTest {
            withChips { c, keys ->
                for (access in listOf(FlowAccess.BAC, FlowAccess.PACE_ONLY)) {
                    for (at in listOf(first, authentication, protectedRead)) {
                        val flow = FakeReadFlowChip.build(c, keys, access)
                        assertFailsWith<CancellationException> {
                            read(
                                flow,
                                transport = Throwing(flow.chip, at) { CancellationException("stop") },
                            )
                        }
                    }
                }
            }
        }

    /** Clears [key] once the chip has answered the first command [at] matches, as an app might from another thread. */
    private class ClearsKey(
        private val inner: FakeChip,
        private val key: ChipAccessKey,
        private val at: (ByteArray) -> Boolean,
    ) : ApduTransport {
        override val maxCommandLength: Int get() = inner.maxCommandLength
        override val supportsExtendedLength: Boolean get() = inner.supportsExtendedLength

        override suspend fun transceive(command: ByteArray): ByteArray {
            val answer = inner.transceive(command)
            if (at(command)) key.clear()
            return answer
        }
    }

    @Test
    fun aKeyClearedDuringTheReadIsKeyCleared() =
        runTest {
            withChips { c, keys ->
                val bac = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val k1 = FakeReadFlowChip.mrzKey()
                assertEquals(
                    ChipAccessDenied(ChipAccessProtocol.BAC, ChipAccessDenied.Reason.KEY_CLEARED),
                    failed(read(bac, transport = ClearsKey(bac.chip, k1, first), key = k1)),
                )
                assertEquals(0, bac.chip.count(0x00, 0x82))
                // PACE: cleared after the first GENERAL AUTHENTICATE was answered; nothing follows it.
                val pace = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY, paceSuites = twoSuites)
                val k2 = FakeReadFlowChip.mrzKey()
                val error = failed(read(pace, transport = ClearsKey(pace.chip, k2, authentication), key = k2))
                assertEquals(ChipAccessDenied(ChipAccessProtocol.PACE, ChipAccessDenied.Reason.KEY_CLEARED), error)
                assertTrue(
                    pace.chip.received
                        .last()
                        .isCommand(0x10, 0x86),
                )
                assertEquals(1, pace.chip.count(0x00, 0x22))
            }
        }

    @Test
    fun anAnswerThatNeverEndsDuringBacIsTyped() =
        runTest {
            withChips { c, keys ->
                val endless =
                    FakeCommandHandler { _, cmd ->
                        if (cmd.cla == 0x00 && (cmd.ins == 0x84 || cmd.ins == 0xC0)) ResponseApdu(byteArrayOf(1), 0x6101) else null
                    }
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, leadingHandlers = listOf(endless))
                assertEquals(
                    ChipAccessDenied(ChipAccessProtocol.BAC, ChipAccessDenied.Reason.UNEXPECTED_CHIP_ANSWER),
                    failed(read(flow)),
                )
                assertEquals(0, flow.chip.count(0x00, 0x82))
            }
        }

    @Test
    fun aRandomSourceOfTheWrongLengthIsTyped() =
        runTest {
            withChips { c, keys ->
                val short =
                    object : ChipCrypto by c {
                        override fun randomBytes(n: Int): ByteArray = ByteArray(n - 1)
                    }
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                assertEquals(
                    ChipAccessDenied(ChipAccessProtocol.BAC, ChipAccessDenied.Reason.UNEXPECTED_CHIP_ANSWER),
                    failed(read(flow, crypto = { short })),
                )
                assertEquals(0, flow.chip.count(0x00, 0x82), "nothing sent with a bad nonce")
            }
        }

    @Test
    fun aHashTheBackendCannotComputeLeavesTheCheckNotRun() =
        runTest {
            withChips { c, keys ->
                // BAC needs SHA-1; EF.SOD uses SHA-256, which this backend refuses.
                val noSha256 =
                    object : ChipCrypto by c {
                        override fun digest(
                            algorithm: HashAlgorithm,
                            data: ByteArray,
                        ): ByteArray =
                            if (algorithm ==
                                HashAlgorithm.SHA1
                            ) {
                                c.digest(algorithm, data)
                            } else {
                                throw ChipCryptoException("no hash")
                            }
                    }
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val read = assertIs<ChipReadResult.Complete>(read(flow, crypto = { noSha256 })).read
                assertEquals(
                    CheckOutcome.NotRun(ChipCheckReason.UNSUPPORTED_ALGORITHM),
                    read.outcomeOf(ChipCheckKind.DataGroupHashMatches(1)),
                )
            }
        }

    // --- One SELECT of the application after chip authentication in the master file ------------

    @Test
    fun theApplicationIsSelectedOnceAfterChipAuthenticationInTheMasterFile() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY, caInMasterFile = true)
                val read = assertIs<ChipReadResult.Complete>(read(flow)).read
                assertEquals(1, flow.ca!!.restarts)
                assertTrue(read.files.any { it.fileId == 0x0101 })
                val selectsOfApplication = flow.chip.received.count { it.isCommand(0x0C, 0xA4) && (it[2].toInt() and 0xFF) == 0x04 }
                assertEquals(1, selectsOfApplication)
            }
        }
}
