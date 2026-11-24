package io.lightine.tessera.emrtd

import io.lightine.tessera.emrtd.fakechip.FakeAttemptLink
import io.lightine.tessera.emrtd.fakechip.FakePaceCam
import io.lightine.tessera.emrtd.fakechip.FakePaceMath
import io.lightine.tessera.emrtd.fakechip.FakePaceSuite
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.RecordingObserver
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.StandardDomainParameterValues
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.emrtd.internal.pa.withKeys
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.types.errors.ChipError
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The read coordinator driven with fake platform events: one ending under races, nothing after the
 * ending, the restart rules and budget, the attempt counts, the state order for a PACE and a BAC
 * chip, and no document data in any update or ending text.
 */
class ChipReadCoordinatorTest {
    private val checkTime = Instant.parse("2026-06-01T12:00:00Z")

    /** A transport the test answers by hand: each command waits for [answers]. */
    private class HeldTransport : ApduTransport {
        override val maxCommandLength: Int = 261
        override val supportsExtendedLength: Boolean = false
        val sent = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<ByteArray>()
        var commands = 0

        override suspend fun transceive(command: ByteArray): ByteArray {
            commands++
            sent.complete(Unit)
            return answer.await()
        }
    }

    /** A transport whose every command fails with [error]. */
    private class FailingTransport(
        private val error: ChipError,
    ) : ApduTransport {
        override val maxCommandLength: Int = 261
        override val supportsExtendedLength: Boolean = false

        override suspend fun transceive(command: ByteArray): ByteArray =
            throw ApduTransportException(error as io.lightine.tessera.types.errors.NfcError)
    }

    private fun options(key: ChipAccessKey = ChipAccessKey.Can("123456")) = ChipReadOptions(key, setOf(1, 2), checkTime = checkTime)

    private fun coordinator(
        observer: RecordingObserver,
        policy: LinkLossPolicy = LinkLossPolicy.Resume(2),
        options: ChipReadOptions = options(),
    ) = ChipReadCoordinator(options, policy, observer, ChipReader(NoOpTelemetrySink))

    private fun assertOneEndingLast(observer: RecordingObserver) {
        assertEquals(1, observer.endings.size, "${observer.calls}")
        assertIs<ChipReadEnding>(observer.calls.last())
    }

    // --- Exactly one ending ---------------------------------------------------------------------

    @Test
    fun anAppCancelDuringACommandEndsAtOnceAndTheLateAnswerChangesNothing() =
        runTest {
            val observer = RecordingObserver()
            val c = coordinator(observer)
            assertTrue(c.start())
            c.waiting(0)
            c.documentFound()
            c.connecting()
            val transport = HeldTransport()
            val read = async { c.read(transport) }
            transport.sent.await()
            assertTrue(c.cancel(ChipReadEnding.Cancelled.By.APP))
            // The ending is there before the command returns, with Stopping just before it.
            val ending = assertIs<ChipReadEnding.Cancelled>(observer.endings.single())
            assertEquals(ChipReadEnding.Cancelled.By.APP, ending.by)
            assertEquals(1, ending.attempts)
            assertEquals(ChipReadState.Stopping, observer.updates.last().state)
            // The command answers normally: the ended read still sends nothing more (the core
            // enforces it itself, whatever the platform does with the running attempt).
            transport.answer.complete(byteArrayOf(0x90.toByte(), 0x00))
            assertEquals(ChipReadAttemptOutcome.ENDED, read.await())
            assertEquals(1, transport.commands, "no command after the ending")
            assertOneEndingLast(observer)
            assertTrue(c.isEnded)
        }

    @Test
    fun aSystemEndAfterAnAppCancelDoesNothing() =
        runTest {
            val observer = RecordingObserver()
            val c = coordinator(observer)
            c.start()
            c.waiting(0)
            assertTrue(c.cancel(ChipReadEnding.Cancelled.By.APP))
            assertFalse(c.endedBySystem(NfcUnavailable(NfcUnavailable.Reason.ENDED_BY_SYSTEM)))
            assertFalse(c.cancel(ChipReadEnding.Cancelled.By.PERSON))
            assertFalse(c.notStarted(NfcUnavailable.Reason.BUSY))
            assertFalse(c.documentNotReturned())
            c.waiting(1000)
            c.documentFound()
            assertOneEndingLast(observer)
            assertIs<ChipReadEnding.Cancelled>(observer.endings.single())
        }

    @Test
    fun aLossAndACancelTogetherGiveOneEndingAndNoLinkLostAfterIt() =
        runTest {
            val observer = RecordingObserver()
            val c = coordinator(observer)
            c.start()
            c.documentFound()
            val transport = HeldTransport()
            val read = async { c.read(transport) }
            transport.sent.await()
            c.cancel(ChipReadEnding.Cancelled.By.APP)
            transport.answer.completeExceptionally(ApduTransportException(NfcTagLost))
            assertEquals(ChipReadAttemptOutcome.ENDED, read.await())
            assertOneEndingLast(observer)
            assertTrue(observer.updates.none { it.state is ChipReadState.LinkLost })
        }

    @Test
    fun endingsRacedFromManyThreadsPassTheGateOnce() =
        runTest {
            withContext(Dispatchers.Default) {
                repeat(50) {
                    val observer = RecordingObserver()
                    val c = coordinator(observer)
                    c.start()
                    val winners =
                        (0 until 8)
                            .map { i ->
                                async {
                                    when (i % 4) {
                                        0 -> c.cancel(ChipReadEnding.Cancelled.By.APP)
                                        1 -> c.cancel(ChipReadEnding.Cancelled.By.PERSON)
                                        2 -> c.endedBySystem(NfcUnavailable(NfcUnavailable.Reason.ENDED_BY_SYSTEM))
                                        else -> c.also { it.waiting(5) }.fail(NfcTagLost)
                                    }
                                }
                            }.awaitAll()
                    assertEquals(1, winners.count { it })
                    assertOneEndingLast(observer)
                }
            }
        }

    @Test
    fun cancellingTheReadCoroutineEndsTheReadAsAnAppCancel() =
        runTest {
            val observer = RecordingObserver()
            val c = coordinator(observer)
            c.start()
            val transport = HeldTransport()
            val job = launch { c.read(transport) }
            transport.sent.await()
            job.cancel()
            job.join()
            assertIs<ChipReadEnding.Cancelled>(observer.endings.single())
        }

    @Test
    fun theObserverMayThrowWithoutChangingTheRead() =
        runTest {
            val observer = RecordingObserver(throwing = true)
            val c = coordinator(observer)
            c.start()
            c.waiting(0)
            c.documentFound()
            assertTrue(c.cancel(ChipReadEnding.Cancelled.By.PERSON))
            assertEquals(3, observer.updates.size)
            assertOneEndingLast(observer)
        }

    // --- Waiting, and endings with no read ------------------------------------------------------

    @Test
    fun aCancelDuringTheWaitForTheDocumentEndsWithNoAttempt() =
        runTest {
            val observer = RecordingObserver()
            val c = coordinator(observer)
            c.start()
            c.waiting(0)
            c.waiting(3000)
            val waits = observer.updates.mapNotNull { it.state as? ChipReadState.WaitingForDocument }.map { it.waitedMillis }
            assertEquals(listOf(0L, 3000L), waits)
            c.cancel(ChipReadEnding.Cancelled.By.PERSON)
            val ending = assertIs<ChipReadEnding.Cancelled>(observer.endings.single())
            assertEquals(ChipReadEnding.Cancelled.By.PERSON, ending.by)
            assertEquals(0, ending.attempts)
            // A person's cancel has no Stopping state: the system sheet is already gone.
            assertTrue(observer.updates.none { it.state == ChipReadState.Stopping })
        }

    @Test
    fun aSystemEndDuringTheWaitForTheDocument() =
        runTest {
            val observer = RecordingObserver()
            val c = coordinator(observer)
            c.start()
            c.waiting(0)
            assertTrue(c.endedBySystem(NfcTimeout(null)))
            val ending = assertIs<ChipReadEnding.EndedBySystem>(observer.endings.single())
            assertEquals(NfcTimeout(null), ending.reason)
            assertEquals(0, ending.attempts)
        }

    @Test
    fun notStartedAndAKeyWithWrongCheckDigitsEndBeforeAnyRadio() =
        runTest {
            val observer = RecordingObserver()
            val c = coordinator(observer)
            c.start()
            c.notStarted(NfcUnavailable.Reason.READ_IN_PROGRESS)
            assertEquals(NfcUnavailable.Reason.READ_IN_PROGRESS, assertIs<ChipReadEnding.NotStarted>(observer.endings.single()).reason)

            val keyObserver = RecordingObserver()
            val badKey = ChipAccessKey.Mrz("T22000129", '4', "900315", '2', "340314", '1')
            val k = coordinator(keyObserver, options = options(badKey))
            assertFalse(k.start())
            val rejected = assertIs<ChipReadEnding.KeyRejected>(keyObserver.endings.single())
            assertEquals(setOf(io.lightine.tessera.types.vocabulary.MrzField.DOCUMENT_NUMBER), rejected.error.fields)
            assertEquals(0, rejected.attempts)
            val transport = HeldTransport()
            assertEquals(ChipReadAttemptOutcome.ENDED, k.read(transport))
            assertEquals(0, transport.commands)
        }

    @Test
    fun theCheckDigitMismatchCanBeAllowed() =
        runTest {
            val observer = RecordingObserver()
            val badKey = ChipAccessKey.Mrz("T22000129", '4', "900315", '2', "340314", '1')
            val options = ChipReadOptions.Builder(badKey).allowCheckDigitMismatch(true).build()
            assertTrue(options.allowCheckDigitMismatch)
            assertTrue(coordinator(observer, options = options).start())
        }

    // --- Lost links -----------------------------------------------------------------------------

    @Test
    fun aLinkLossStartsAgainWithinTheBudgetThenEnds() =
        runTest {
            val observer = RecordingObserver()
            val c = coordinator(observer, LinkLossPolicy.ReadAgain(2))
            c.start()
            c.waiting(0)
            var outcomes = mutableListOf<ChipReadAttemptOutcome>()
            repeat(3) {
                c.documentFound()
                c.connecting()
                outcomes += c.read(FailingTransport(NfcTagLost))
                // While waiting after a loss, LinkLost stays: waiting ticks do nothing.
                c.waiting(7000)
            }
            assertEquals(listOf(ChipReadAttemptOutcome.LINK_LOST, ChipReadAttemptOutcome.LINK_LOST, ChipReadAttemptOutcome.ENDED), outcomes)
            val failed = assertIs<ChipReadEnding.Failed>(observer.endings.single())
            assertEquals(NfcTagLost, failed.error)
            assertEquals(3, failed.attempts)
            assertEquals(listOf(2, 1), observer.updates.mapNotNull { (it.state as? ChipReadState.LinkLost)?.restartsLeft })
            assertTrue(
                observer.updates.none {
                    (it.state as? ChipReadState.WaitingForDocument)?.waitedMillis == 7000L
                },
            )
            // DocumentFound after a loss already counts the next attempt.
            val afterFirstLoss = observer.updates.dropWhile { it.state !is ChipReadState.LinkLost }
            assertEquals(1, afterFirstLoss.first().attempt)
            assertEquals(2, afterFirstLoss[1].attempt)
            assertOneEndingLast(observer)
        }

    @Test
    fun linkLostKeepsTheAttemptThatLostTheLinkEachTimeItIsReported() =
        runTest {
            val observer = RecordingObserver()
            val c = coordinator(observer, LinkLossPolicy.ReadAgain(2))
            c.start()
            c.waiting(0)
            c.documentFound()
            c.connecting()
            assertEquals(ChipReadAttemptOutcome.LINK_LOST, c.read(FailingTransport(NfcTagLost)))
            // A connect that fails while the read waits for the document republishes LinkLost.
            c.documentFound()
            c.connecting()
            c.connectionFailed()
            c.documentFound()
            val lost = observer.updates.filter { it.state is ChipReadState.LinkLost }
            assertEquals(2, lost.size)
            assertTrue(lost.all { it.attempt == 1 }, "$lost")
            val afterLoss = observer.updates.dropWhile { it.state !is ChipReadState.LinkLost }
            assertTrue(afterLoss.filter { it.state !is ChipReadState.LinkLost }.all { it.attempt == 2 }, "$afterLoss")
        }

    @Test
    fun aSystemEndAfterAFailedAttemptEndsTheReadInsteadOfARestart() =
        runTest {
            val observer = RecordingObserver()
            val c = coordinator(observer, LinkLossPolicy.ReadAgain(2))
            c.start()
            var asked = 0
            val outcome =
                c.read(FailingTransport(NfcTagLost)) {
                    asked++
                    NfcUnavailable(NfcUnavailable.Reason.DISABLED)
                }
            assertEquals(ChipReadAttemptOutcome.ENDED, outcome)
            assertEquals(1, asked)
            val ending = assertIs<ChipReadEnding.EndedBySystem>(observer.endings.single())
            assertEquals(NfcUnavailable(NfcUnavailable.Reason.DISABLED), ending.reason)
            assertEquals(1, ending.attempts)
            assertTrue(observer.updates.none { it.state is ChipReadState.LinkLost })
            assertOneEndingLast(observer)

            // A check that throws, or finds nothing, changes nothing.
            val quiet = RecordingObserver()
            val q = coordinator(quiet, LinkLossPolicy.ReadAgain(1))
            q.start()
            assertEquals(ChipReadAttemptOutcome.LINK_LOST, q.read(FailingTransport(NfcTagLost)) { error("boom") })
            assertEquals(ChipReadAttemptOutcome.ENDED, q.read(FailingTransport(NfcTagLost)) { null })
            assertEquals(NfcTagLost, assertIs<ChipReadEnding.Failed>(quiet.endings.single()).error)
        }

    @Test
    fun aTimeoutCountsAsALinkLossButNoOtherTransportErrorDoes() =
        runTest {
            val timeout = RecordingObserver()
            val t = coordinator(timeout, LinkLossPolicy.ReadAgain(1))
            t.start()
            assertEquals(ChipReadAttemptOutcome.LINK_LOST, t.read(FailingTransport(NfcTimeout(5000))))

            for ((reason, expected) in listOf(
                NfcUnavailable.Reason.CANCELLED_BY_USER to ChipReadEnding.Cancelled::class,
                NfcUnavailable.Reason.CLOSED to ChipReadEnding.Cancelled::class,
                NfcUnavailable.Reason.ENDED_BY_SYSTEM to ChipReadEnding.EndedBySystem::class,
                NfcUnavailable.Reason.DISABLED to ChipReadEnding.EndedBySystem::class,
                NfcUnavailable.Reason.REQUEST_REFUSED to ChipReadEnding.Failed::class,
            )) {
                val observer = RecordingObserver()
                val c = coordinator(observer, LinkLossPolicy.Resume(3))
                c.start()
                assertEquals(ChipReadAttemptOutcome.ENDED, c.read(FailingTransport(NfcUnavailable(reason))), "$reason")
                assertTrue(expected.isInstance(observer.endings.single()), "$reason: ${observer.endings}")
                assertTrue(observer.updates.none { it.state is ChipReadState.LinkLost })
            }
        }

    @Test
    fun theEndPolicyAndAPlatformThatCannotStartAgainEndAtTheFirstLoss() =
        runTest {
            val end = RecordingObserver()
            val e = coordinator(end, LinkLossPolicy.End)
            e.start()
            assertEquals(ChipReadAttemptOutcome.ENDED, e.read(FailingTransport(NfcTagLost)))
            assertIs<ChipReadEnding.Failed>(end.endings.single())
            assertTrue(end.updates.all { it.maxAttempts == 1 })

            val noTime = RecordingObserver()
            val n = coordinator(noTime, LinkLossPolicy.Resume(3))
            n.start()
            assertEquals(ChipReadAttemptOutcome.ENDED, n.read(FailingTransport(NfcTagLost), readAgainAllowed = false))
            assertIs<ChipReadEnding.Failed>(noTime.endings.single())
        }

    @Test
    fun aDocumentThatDoesNotComeBackEndsWithTheLoss() =
        runTest {
            val observer = RecordingObserver()
            val c = coordinator(observer)
            c.start()
            c.read(FailingTransport(NfcTagLost))
            c.connecting()
            c.connectionFailed()
            // A failed connection after a loss shows LinkLost again, with the same restarts left.
            assertEquals(2, (observer.updates.last().state as ChipReadState.LinkLost).restartsLeft)
            assertTrue(c.documentNotReturned())
            assertEquals(NfcTagLost, assertIs<ChipReadEnding.Failed>(observer.endings.single()).error)
        }

    @Test
    fun aFailedConnectionBeforeAnyCommandGoesBackToWaitingWithNoAttemptUsed() =
        runTest {
            val observer = RecordingObserver()
            val c = coordinator(observer)
            c.start()
            c.waiting(1200)
            c.documentFound()
            c.connecting()
            c.connectionFailed()
            assertEquals(1200L, (observer.updates.last().state as ChipReadState.WaitingForDocument).waitedMillis)
            assertTrue(observer.updates.all { it.attempt == 1 && it.maxAttempts == 3 })
        }

    @Test
    fun linkLossPolicyAllowsOneToThreeRestarts() {
        assertEquals(0, LinkLossPolicy.End.maxRestarts)
        assertEquals(3, LinkLossPolicy.Resume(3).maxRestarts)
        assertEquals(LinkLossPolicy.ReadAgain(1), LinkLossPolicy.ReadAgain(1))
        for (n in listOf(0, 4, -1)) {
            assertFailsWith<IllegalArgumentException> { LinkLossPolicy.ReadAgain(n) }
            assertFailsWith<IllegalArgumentException> { LinkLossPolicy.Resume(n) }
        }
    }

    // --- State order and text -------------------------------------------------------------------

    private inline fun withChips(block: (ChipCrypto, TestKeys) -> Unit) {
        withCrypto { c -> withKeys { keys -> block(c, keys) } }
    }

    private fun stateNames(observer: RecordingObserver): List<String> =
        observer.updates
            .map {
                when (val s = it.state) {
                    is ChipReadState.OpeningAccess -> "OpeningAccess(${s.protocol})"
                    is ChipReadState.ReadingFile -> "ReadingFile(${s.file.fileId.toString(16)},${s.file.inMasterFile})"
                    is ChipReadState.AuthenticatingChip -> "Auth(${s.method})"
                    else -> s::class.simpleName!!
                }
            }.fold(mutableListOf()) { acc, s ->
                if (acc.lastOrNull() != s) acc += s else Unit
                acc
            }

    private suspend fun completeRead(
        flow: io.lightine.tessera.emrtd.fakechip.FlowChip,
        observer: RecordingObserver,
    ): ChipReadEnding.Complete {
        val c =
            ChipReadCoordinator(
                ChipReadOptions(FakeReadFlowChip.mrzKey(), setOf(1, 2), listOf(flow.anchor), checkTime),
                LinkLossPolicy.Resume(2),
                observer,
                ChipReader(NoOpTelemetrySink),
            )
        c.start()
        c.waiting(0)
        c.documentFound()
        c.connecting()
        assertEquals(ChipReadAttemptOutcome.ENDED, c.read(FakeAttemptLink(flow.chip)))
        return assertIs<ChipReadEnding.Complete>(observer.endings.single())
    }

    @Test
    fun theStatesOfAPaceChipWithTheMappingComeInOrder() =
        runTest {
            withChips { c, keys ->
                val params = StandardDomainParameterValues.forId(13) as EcDomainParameters
                val cam = FakePaceCam(FakePaceMath.randomPrivateKey(c, params, c::randomBytes))
                val flow =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.PACE_ONLY,
                        paceSuites = listOf(FakePaceSuite(FakeReadFlowChip.ECDH_CAM_AES128, 13)),
                        cam = cam,
                    )
                val observer = RecordingObserver()
                val ending = completeRead(flow, observer)
                assertEquals(
                    listOf(
                        "Starting",
                        "WaitingForDocument",
                        "DocumentFound",
                        "Connecting",
                        "OpeningAccess(null)",
                        "OpeningAccess(PACE)",
                        "ReadingFile(11d,true)",
                        "ReadingFile(11e,false)",
                        "ReadingFile(11d,false)",
                        "ReadingFile(101,false)",
                        "ReadingFile(102,false)",
                        "CheckingFiles",
                    ),
                    stateNames(observer),
                )
                assertEquals(ChipAccessProtocol.PACE, ending.read.access.protocol)
                assertEquals(1.0, ending.progress.fraction)
                assertEquals(4, ending.progress.filesDone)
                assertEquals(4, ending.progress.filesPlanned)
                assertTextHasNoDocumentData(observer)
            }
        }

    @Test
    fun theStatesOfABacChipWithActiveAuthenticationComeInOrder() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, activeAuthentication = true)
                val observer = RecordingObserver()
                completeRead(flow, observer)
                assertEquals(
                    listOf(
                        "Starting",
                        "WaitingForDocument",
                        "DocumentFound",
                        "Connecting",
                        "OpeningAccess(null)",
                        "OpeningAccess(BAC)",
                        "ReadingFile(11e,false)",
                        "ReadingFile(11d,false)",
                        "ReadingFile(10f,false)",
                        "Auth(ACTIVE_AUTHENTICATION)",
                        "ReadingFile(101,false)",
                        "ReadingFile(102,false)",
                        "CheckingFiles",
                    ),
                    stateNames(observer),
                )
                val reading = observer.updates.mapNotNull { it.state as? ChipReadState.ReadingFile }.filter { it.file.dataGroup == 2 }
                val total = reading.last().bytesTotal!!
                assertEquals(flow.dataGroups.getValue(2).size, total)
                assertEquals(total, reading.last().bytesRead)
                assertTrue(reading.zipWithNext().all { (a, b) -> b.bytesRead >= a.bytesRead })
                assertTextHasNoDocumentData(observer)
            }
        }

    /** No update or ending text carries the key's values or file content. */
    private fun assertTextHasNoDocumentData(observer: RecordingObserver) {
        val line2 = FakeReadFlowChip.lines[1]
        val secrets = listOf(line2.substring(0, 9), line2.substring(13, 19), line2.substring(21, 27), "SPECIMEN", "MORGAN")
        val texts = observer.calls.map { it.toString() }
        for (text in texts) {
            for (s in secrets) assertFalse(s in text, "'$s' in $text")
            assertFalse(Regex("[0-9A-F]{16,}").containsMatchIn(text), text)
        }
    }
}
