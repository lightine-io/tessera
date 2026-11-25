package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadReadiness
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.LinkLossPolicy
import io.lightine.tessera.types.errors.ChipError
import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcNoAnswer
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import io.lightine.tessera.types.errors.NfcUnavailable.Reason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * TES-346: the lifecycle of a [CoreNfcChipRead]: the settle delay, the command time limit, a lost
 * link and the document coming back or not, the restart budget and the session's time budget,
 * every Core NFC code to its ending, one ending under races, and the rules between two reads.
 */
class CoreNfcChipReadLifecycleTest {
    private companion object {
        const val FAILED = "Reading failed. Try again."
        const val PARTIAL = "Not everything could be read. Try again."
        const val CANCELLED = "Cancelled"
    }

    private fun stoppedBy(ending: ChipReadEnding): ChipError =
        when (ending) {
            is ChipReadEnding.Failed -> ending.error
            is ChipReadEnding.Partial -> ending.stoppedBy
            else -> error("not stopped: $ending")
        }

    private fun opError(code: Long): NfcError = nfcErrorFor(platform.CoreNFC.NFCErrorDomain, code, CoreNfcErrorSource.TAG_OPERATION)

    // --- Settle delay -----------------------------------------------------------------------------

    @Test
    fun settle__no_command_before_the_delay_ends() =
        chipReadTest {
            val chip = FakeChip()
            val (read, rec) = start()
            present(FakeChipTag(chip))
            step(399)
            assertEquals("DocumentFound", rec.names.last())
            assertTrue(chip.sent.isEmpty())
            step(1)
            assertTrue(chip.sent.isNotEmpty())
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertNothingLeft(read)
        }

    @Test
    fun settle__cancel_cuts_it_short_and_ends_at_once() =
        chipReadTest {
            val chip = FakeChip()
            val (read, rec) = start()
            present(FakeChipTag(chip))
            step(100)
            read.cancel()
            // At once, before the clock moves.
            val ending = assertIs<ChipReadEnding.Cancelled>(rec.ending)
            assertEquals(ChipReadEnding.Cancelled.By.APP, ending.by)
            assertEquals("Stopping", rec.names.last())
            assertEquals(listOf<String?>(CANCELLED), port.endings)
            step(5_000)
            assertTrue(chip.sent.isEmpty(), "no command after a cancel during the settle delay")
            assertEquals(1, rec.endings.size)
            assertNothingLeft(read)
        }

    @Test
    fun settle__a_system_end_cuts_it_short() =
        chipReadTest {
            val chip = FakeChip()
            val (read, rec) = start()
            present(FakeChipTag(chip))
            step(100)
            systemEnds(CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY)
            assertIs<ChipReadEnding.EndedBySystem>(rec.ending)
            step(5_000)
            assertTrue(chip.sent.isEmpty())
            assertTrue(port.endings.isEmpty(), "the SDK does not end a session the system ended")
            assertNothingLeft(read)
        }

    // --- Command time limit ----------------------------------------------------------------------

    @Test
    fun command_timeout__an_unanswered_command_is_a_lost_link_and_a_late_answer_is_dropped() =
        chipReadTest {
            val chip = FakeChip().apply { silentFrom = 7 }
            val (read, rec) = start(settings = CoreNfcReadSettings(linkLoss = LinkLossPolicy.End))
            present(FakeChipTag(chip))
            step(400)
            assertEquals(7, chip.sent.size)
            // The silent command is a read after access: the read limit, 2 s (TES-346; before, 5 s).
            step(1_999)
            assertTrue(rec.endings.isEmpty(), "still waiting just before the limit")
            step(1)
            assertEquals(NfcTimeout(CoreNfcReadSettings.DEFAULT_READ_TIMEOUT_MILLIS, NfcTimeout.Limit.COMMAND), stoppedBy(rec.ending))
            // Lost after the plain chip was opened: what was read is kept.
            assertEquals(listOf<String?>(PARTIAL), port.endings)
            chip.held.single().invoke(byteArrayOf(0x90.toByte(), 0x00), null)
            step(1_000)
            assertEquals(7, chip.sent.size, "nothing more is sent on the link")
            assertNothingLeft(read)
        }

    @Test
    fun command_timeout__with_resume_the_read_waits_for_the_document_and_reads_again_on_a_new_link() =
        chipReadTest {
            val chip = FakeChip().apply { silentFrom = 7 }
            val (read, rec) = start()
            present(FakeChipTag(chip))
            step(400 + 5_000)
            assertIs<ChipReadState.LinkLost>(rec.states.last())
            assertEquals(1, port.restarts)
            assertTrue(rec.endings.isEmpty())

            chip.silentFrom = null
            present(FakeChipTag(chip))
            step(400)
            val ending = assertIs<ChipReadEnding.Complete>(rec.ending)
            assertEquals(2, ending.attempts)
            assertEquals(listOf<String?>(null), port.endings)
            assertNothingLeft(read)
        }

    // --- Link loss ---------------------------------------------------------------------------------

    @Test
    fun link_lost__the_document_comes_back__a_new_link_and_a_complete_read() =
        chipReadTest {
            val chip = FakeChip().apply { loseAt += 7 }
            val (read, rec) = start()
            present(FakeChipTag(chip))
            step(400)
            val lost = rec.updates.last()
            assertEquals(2, assertIs<ChipReadState.LinkLost>(lost.state).restartsLeft)
            assertEquals(3, lost.maxAttempts)
            assertEquals(1, port.restarts)

            step(4_000)
            // Shown when the 100 ms between sheet texts passed.
            assertEquals("Connection lost. Hold the document again.\nTry 2 of 3", port.alerts.last())
            assertIs<ChipReadState.LinkLost>(rec.states.last(), "the waiting reports stay quiet after a loss")

            val again = FakeChipTag(chip)
            present(again)
            assertEquals("DocumentFound", rec.names.last())
            step(400)
            val ending = assertIs<ChipReadEnding.Complete>(rec.ending)
            assertEquals(2, ending.attempts)
            assertEquals(1, again.connectCalls)
            assertEquals(2, chip.powerUps, "each attempt has its own link")
            assertEquals(0xA4, chip.sent[7][1].toInt() and 0xFF, "the new link starts with a SELECT")
            assertEquals(listOf<String?>(null), port.endings)
            assertNothingLeft(read)
        }

    // --- Session invalidated before the session's end (TES-346) -------------------------------------

    @Test
    fun invalidated__the_command_fails_before_the_persons_cancel_is_reported__cancelled_by_the_person() =
        chipReadTest {
            val chip = FakeChip().apply { loseAt += 3 }
            chip.lossError = opError(CoreNfcErrorCode.SESSION_INVALIDATED)
            val (read, rec) = start()
            present(FakeChipTag(chip))
            step(400)
            assertTrue(rec.endings.isEmpty(), "the read waits for the system's report of the end")
            step(300)
            systemEnds(200)
            assertEquals(ChipReadEnding.Cancelled.By.PERSON, assertIs<ChipReadEnding.Cancelled>(rec.ending).by)
            assertTrue(port.endings.isEmpty(), "the SDK does not end a session the system ended")
            assertNothingLeft(read)
        }

    @Test
    fun invalidated__the_command_fails_before_the_time_limit_is_reported__ended_by_the_system() =
        chipReadTest {
            val chip = FakeChip().apply { loseAt += 3 }
            chip.lossError = opError(CoreNfcErrorCode.SESSION_INVALIDATED)
            val (read, rec) = start()
            present(FakeChipTag(chip))
            step(400)
            assertTrue(rec.endings.isEmpty())
            systemEnds(201)
            assertEquals(NfcTimeout(null, NfcTimeout.Limit.SESSION), assertIs<ChipReadEnding.EndedBySystem>(rec.ending).reason)
            assertNothingLeft(read)
        }

    @Test
    fun invalidated__no_report_of_the_end_comes__ended_by_the_system_never_by_the_app() =
        chipReadTest {
            val chip = FakeChip().apply { loseAt += 3 }
            chip.lossError = opError(CoreNfcErrorCode.SESSION_INVALIDATED)
            val (read, rec) = start()
            present(FakeChipTag(chip))
            step(400)
            step(CoreNfcChipRead.SESSION_END_WAIT_MILLIS - 1)
            assertTrue(rec.endings.isEmpty())
            step(1)
            assertEquals(NfcUnavailable(Reason.CLOSED), assertIs<ChipReadEnding.EndedBySystem>(rec.ending).reason)
            assertEquals(1, rec.endings.size)
            // The session is still open: the SDK ends it with the ending's text.
            assertEquals(1, port.endings.size)
            assertNothingLeft(read)
        }

    @Test
    fun used_key__a_second_read_with_the_same_options_shows_no_sheet() =
        chipReadTest {
            val options = ChipReadHarness.canOptions()
            val (first, _) = start(options = options)
            first.cancel()
            step(2_000)
            val before = ports.size
            val (read, rec) = start(options = options)
            val ending = assertIs<ChipReadEnding.KeyRejected>(rec.ending)
            assertEquals(
                io.lightine.tessera.types.errors.ChipAccessDenied.Reason.KEY_CLEARED,
                assertIs<io.lightine.tessera.types.errors.ChipAccessDenied>(ending.error).reason,
            )
            assertEquals(listOf("Starting"), rec.names)
            assertEquals(before, ports.size, "no system session for a used key")
            assertNothingLeft(read)
        }

    @Test
    fun link_lost__every_operation_code_of_a_lost_link_reads_again() =
        chipReadTest {
            for (code in listOf(100L, 101L, 102L, 104L)) {
                val chip = FakeChip().apply { loseAt += 7 }
                chip.lossError = opError(code)
                val (read, rec) = start()
                present(FakeChipTag(chip))
                step(400)
                assertIs<ChipReadState.LinkLost>(rec.states.last(), "code $code")
                read.cancel()
                assertNothingLeft(read)
            }
        }

    @Test
    fun link_lost__the_document_never_comes_back__the_system_end_is_the_ending() =
        chipReadTest {
            val chip = FakeChip().apply { loseAt += 7 }
            val (read, rec) = start()
            present(FakeChipTag(chip))
            step(400)
            assertIs<ChipReadState.LinkLost>(rec.states.last())
            step(50_000)
            assertTrue(rec.endings.isEmpty())
            systemEnds(CoreNfcErrorCode.SESSION_TIMEOUT)
            val ending = assertIs<ChipReadEnding.EndedBySystem>(rec.ending)
            assertEquals(NfcTimeout(null, NfcTimeout.Limit.SESSION), ending.reason)
            assertTrue(port.endings.isEmpty())
            assertNothingLeft(read)
        }

    @Test
    fun link_lost__the_restart_budget_used_up_ends_the_read_with_the_loss() =
        chipReadTest {
            val chip = FakeChip().apply { loseAt += setOf(7, 14) }
            val (read, rec) = start(settings = CoreNfcReadSettings(linkLoss = LinkLossPolicy.Resume(1)))
            present(FakeChipTag(chip))
            step(400)
            assertIs<ChipReadState.LinkLost>(rec.states.last())
            present(FakeChipTag(chip))
            step(400)
            assertEquals(NfcTagLost, stoppedBy(rec.ending))
            assertEquals(2, rec.ending.attempts)
            // Lost after the plain chip was opened: what was read is kept.
            assertEquals(listOf<String?>(PARTIAL), port.endings)
            assertNothingLeft(read)
        }

    @Test
    fun link_lost__with_less_than_10_seconds_of_the_session_left_the_read_does_not_start_again() =
        chipReadTest {
            val chip = FakeChip().apply { loseAt += 3 }
            val (read, rec) = start(settings = oneSession())
            step(51_000)
            // The new searches while no document was reported (one every 4 seconds) are not the
            // restarts after a loss.
            assertEquals(12, port.restarts)
            present(FakeChipTag(chip))
            step(400)
            // The loss came before access and no try may follow: the chip never got past the first
            // commands, so the read ends with NfcNoAnswer (TES-346; before, the loss itself).
            assertEquals(NfcNoAnswer, stoppedBy(rec.ending))
            assertFalse("LinkLost" in rec.names)
            assertEquals(12, port.restarts, "no restart after the loss")
            assertNothingLeft(read)
        }

    @Test
    fun link_lost__when_the_session_time_runs_low_during_the_attempt_the_read_ends_after_the_loss() =
        chipReadTest {
            // Each answer takes a second: the attempt starts with 20 s left and loses the link at
            // about 7 s left.
            val chip = FakeChip(scope = scope, answerDelayMillis = 1_000, dg1Size = 3_000).apply { loseAt += 13 }
            val (read, rec) = start(settings = oneSession())
            step(40_000)
            val searches = port.restarts
            present(FakeChipTag(chip))
            step(400 + 13_000)
            assertEquals(NfcTagLost, stoppedBy(rec.ending))
            assertTrue("LinkLost" in rec.names)
            assertEquals(searches, port.restarts, "no polling restart once the time is too short")
            assertNothingLeft(read)
        }

    @Test
    fun connect__lost_links_while_connecting_wait_for_the_document__the_third_ends_the_read() =
        chipReadTest {
            val chip = FakeChip()
            val (read, rec) = start()
            repeat(2) {
                present(FakeChipTag(chip, ConnectFailure(NfcTagLost, retryable = true)))
                step(400)
                assertEquals("WaitingForDocument", rec.names.last())
            }
            assertEquals(2, port.restarts)
            present(FakeChipTag(chip, ConnectFailure(NfcTagLost, retryable = true)))
            step(400)
            // The found chip never let the link be set up: no answer, the same ending as on Android.
            assertEquals(NfcNoAnswer, stoppedBy(rec.ending))
            assertEquals(0, rec.ending.attempts, "no command was sent")
            assertEquals(listOf<String?>("No answer from the chip. Try another position."), port.endings)
            assertNothingLeft(read)
        }

    @Test
    fun connect__a_chip_that_does_not_answer_three_connects__code_101__fails_with_no_answer_not_a_system_ending() =
        chipReadTest {
            val chip = FakeChip()
            val (read, rec) = start()
            val noAnswer =
                ConnectFailure(NfcTimeout(null, NfcTimeout.Limit.COMMAND), retryable = true, systemCode = CoreNfcErrorCode.RETRY_EXCEEDED)
            repeat(3) {
                present(FakeChipTag(chip, noAnswer))
                step(400)
            }
            val ending = assertIs<ChipReadEnding.Failed>(rec.ending)
            assertEquals(NfcNoAnswer, ending.error)
            assertEquals(0, ending.attempts)
            assertEquals(101, ending.systemCode)
            assertNothingLeft(read)
        }

    @Test
    fun fault__an_exception_the_read_does_not_expect_ends_it_once_and_never_leaves_it() =
        chipReadTest {
            val (read, rec) = start()
            val throwing =
                object : DetectedTag {
                    override val iso7816: Iso7816Channel = FakeChip()

                    override fun connect(onDone: (failure: ConnectFailure?) -> Unit): Unit = throw IllegalStateException("a platform fault")
                }
            present(throwing)
            step(400)
            val ending = assertIs<ChipReadEnding.Failed>(rec.ending)
            assertEquals(NfcUnavailable(Reason.UNEXPECTED_FAULT), ending.error)
            assertEquals(1, rec.endings.size)
            assertNothingLeft(read)
        }

    @Test
    fun fault__an_exception_before_any_session_ends_the_read_once_as_an_unexpected_fault() =
        chipReadTest {
            readingFault = IllegalStateException("a platform fault")
            val (read, rec) = start()
            step(100)
            assertEquals(NfcUnavailable(Reason.UNEXPECTED_FAULT), assertIs<ChipReadEnding.Failed>(rec.ending).error)
            assertEquals(1, rec.endings.size)
            assertTrue(ports.isEmpty())
            assertNothingLeft(read)
        }

    @Test
    fun connect__a_refused_connect_ends_the_read_with_the_failure_text() =
        chipReadTest {
            val (read, rec) = start()
            present(FakeChipTag(FakeChip(), ConnectFailure(NfcUnavailable(Reason.REQUEST_REFUSED), retryable = false)))
            step(400)
            assertEquals(NfcUnavailable(Reason.REQUEST_REFUSED), stoppedBy(rec.ending))
            assertEquals(listOf<String?>(FAILED), port.endings)
            assertNothingLeft(read)
        }

    @Test
    fun tags__more_than_one_tag_or_a_tag_that_is_not_iso7816_keeps_looking() =
        chipReadTest {
            val (read, rec) = start()
            port.events.onTagsDetected(listOf(FakeChipTag(FakeChip()), FakeChipTag(FakeChip())))
            step()
            port.events.onTagsDetected(listOf(FakeTag(iso7816 = null)))
            step()
            assertEquals(2, port.restarts)
            assertFalse("DocumentFound" in rec.names)
            present(FakeChipTag(FakeChip()))
            step(400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertNothingLeft(read)
        }

    // --- Waiting reports -----------------------------------------------------------------------------

    @Test
    fun waiting__the_time_waited_is_reported_every_2_seconds() =
        chipReadTest {
            val (read, rec) = start()
            step(6_000)
            val waited = rec.states.filterIsInstance<ChipReadState.WaitingForDocument>().map { it.waitedMillis }
            assertEquals(listOf(0L, 2_000L, 4_000L, 6_000L), waited)
            step(14_000)
            assertEquals(
                listOf(
                    "Hold the top of your iPhone on the document and move it slowly",
                    "Still searching. Keep the phone on the document.",
                ),
                port.alerts,
                "the default text changes once while waiting, at 8 s",
            )
            read.cancel()
            assertNothingLeft(read)
        }

    // --- Every Core NFC code at the session's end --------------------------------------------------

    private fun expectedBeforeDocument(code: Long): (ChipReadEnding) -> Boolean =
        when (code) {
            1L -> { e -> e is ChipReadEnding.NotStarted && e.reason == Reason.NOT_SUPPORTED }
            2L -> { e -> e is ChipReadEnding.NotStarted && e.reason == Reason.NOT_PERMITTED }
            7L -> { e -> e is ChipReadEnding.NotStarted && e.reason == Reason.NOT_ELIGIBLE }
            8L -> { e -> e is ChipReadEnding.NotStarted && e.reason == Reason.ACCESS_NOT_ANSWERED }
            6L -> { e -> e is ChipReadEnding.NotStarted && e.reason == Reason.DISABLED }
            203L -> { e -> e is ChipReadEnding.NotStarted && e.reason == Reason.BUSY }
            200L -> { e -> e is ChipReadEnding.Cancelled && e.by == ChipReadEnding.Cancelled.By.PERSON }
            201L -> { e -> e is ChipReadEnding.EndedBySystem && e.reason == NfcTimeout(null, NfcTimeout.Limit.SESSION) }
            101L -> { e -> e is ChipReadEnding.EndedBySystem && e.reason == NfcTimeout(null, NfcTimeout.Limit.COMMAND) }
            103L -> { e -> e is ChipReadEnding.EndedBySystem && e.reason == NfcUnavailable(Reason.CLOSED) }
            3L, 4L, 5L, 300L -> { e -> e is ChipReadEnding.Failed && e.error == NfcUnavailable(Reason.REQUEST_REFUSED) }
            105L -> { e -> e is ChipReadEnding.Failed && e.error == NfcUnavailable(Reason.COMMAND_TOO_LONG) }
            100L, 102L, 104L -> { e -> e is ChipReadEnding.Failed && e.error == NfcTagLost }
            else -> { e -> e is ChipReadEnding.EndedBySystem && e.reason == NfcUnavailable(Reason.ENDED_BY_SYSTEM) }
        }

    private val allCodes =
        listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 100L, 101L, 102L, 103L, 104L, 105L, 200L, 201L, 202L, 203L, 204L, 300L) +
            listOf(400L, 401L, 402L, 403L, 500L, 501L, 502L, 999L)

    @Test
    fun codes__every_code_while_waiting_maps_to_its_ending_and_the_sdk_ends_nothing() =
        chipReadTest {
            for (code in allCodes) {
                val (read, rec) = start(settings = oneSession())
                systemEnds(code)
                assertTrue(expectedBeforeDocument(code)(rec.ending), "code $code gave ${rec.ending}")
                // Core NFC's raw code reaches the ending as a fact (the person's Cancel has no field for it).
                if (rec.ending !is ChipReadEnding.Cancelled) assertEquals(code.toInt(), rec.ending.systemCode, "code $code")
                assertTrue(port.endings.isEmpty(), "code $code")
                assertNothingLeft(read)
                // The rest after a system end: longer after a session that never reported a document (202).
                val rest = (env.readiness.current() as? ChipReadReadiness.NotYet)?.remainingMillis
                when (code) {
                    202L -> assertEquals(CoreNfcChipRead.UNSETTLED_SYSTEM_REST_MILLIS, rest, "code $code")
                    201L, 203L -> assertEquals(1_000L, rest, "code $code")
                }
                step(CoreNfcChipRead.UNSETTLED_SYSTEM_REST_MILLIS) // past the rest after a system end
            }
        }

    @Test
    fun codes__after_a_document_was_found__radio_off_and_busy_are_the_system_ending_and_not_permitted_fails() =
        chipReadTest {
            val cases =
                mapOf<Long, (ChipReadEnding) -> Boolean>(
                    6L to { e -> e is ChipReadEnding.EndedBySystem && e.reason == NfcUnavailable(Reason.DISABLED) },
                    203L to { e -> e is ChipReadEnding.EndedBySystem && e.reason == NfcUnavailable(Reason.BUSY) },
                    2L to { e -> e is ChipReadEnding.Failed && e.error == NfcUnavailable(Reason.NOT_PERMITTED) },
                    200L to { e -> e is ChipReadEnding.Cancelled && e.by == ChipReadEnding.Cancelled.By.PERSON },
                    201L to { e -> e is ChipReadEnding.EndedBySystem && e.reason == NfcTimeout(null, NfcTimeout.Limit.SESSION) },
                    202L to { e -> e is ChipReadEnding.EndedBySystem && e.reason == NfcUnavailable(Reason.ENDED_BY_SYSTEM) },
                )
            for ((code, expected) in cases) {
                val chip = FakeChip().apply { silentFrom = 1 }
                val (read, rec) = start()
                present(FakeChipTag(chip))
                step(400)
                assertEquals(1, chip.sent.size)
                systemEnds(code)
                assertTrue(expected(rec.ending), "code $code gave ${rec.ending}")
                assertTrue(port.endings.isEmpty())
                assertNothingLeft(read)
                step(2_000)
            }
        }

    @Test
    fun codes__no_reader_on_the_device_ends_before_any_session() =
        chipReadTest {
            readingAvailable = false
            val (read, rec) = start()
            assertEquals(Reason.NOT_SUPPORTED, assertIs<ChipReadEnding.NotStarted>(rec.ending).reason)
            assertTrue(ports.isEmpty())
            assertNothingLeft(read)
        }

    @Test
    fun codes__a_key_with_wrong_check_digits_ends_before_any_session() =
        chipReadTest {
            val (read, rec) = start(options = ChipReadHarness.mrzOptions(wrongDigit = true))
            assertIs<ChipReadEnding.KeyRejected>(rec.ending)
            assertEquals(listOf("Starting"), rec.names)
            assertTrue(ports.isEmpty())
            assertNothingLeft(read)
        }

    @Test
    fun codes__a_chip_that_refuses_the_key_ends_with_key_not_accepted_after_one_more_try_on_the_same_link() =
        chipReadTest {
            val chip = FakeChip(bacRefusesKey = true)
            val (read, rec) = start(options = ChipReadHarness.mrzOptions())
            present(FakeChipTag(chip))
            step(400)
            val ending = assertIs<ChipReadEnding.KeyNotAccepted>(rec.ending)
            assertEquals(1, ending.attempts)
            assertEquals(0, port.restarts)
            // TES-346: the first refusal gets one more try on the same link, never a third.
            assertEquals(2, chip.sent.count { (it[1].toInt() and 0xFF) == 0x82 }, "two EXTERNAL AUTHENTICATE")
            assertEquals(listOf<String?>(CoreNfcDefaultSheetTexts.textForEnding(ending)), port.endings)
            assertNothingLeft(read)
        }

    // --- One ending under races ------------------------------------------------------------------------

    @Test
    fun races__cancel_then_the_systems_echo__one_cancelled_by_the_app() =
        chipReadTest {
            val chip = FakeChip().apply { silentFrom = 2 }
            val (read, rec) = start()
            present(FakeChipTag(chip))
            step(400)
            read.cancel()
            read.cancel()
            step(1_000)
            assertEquals(ChipReadEnding.Cancelled.By.APP, assertIs<ChipReadEnding.Cancelled>(rec.ending).by)
            assertEquals(listOf<String?>(CANCELLED), port.endings)
            chip.held.forEach { it(byteArrayOf(0x90.toByte(), 0x00), null) }
            step(1_000)
            assertEquals(2, chip.sent.size, "nothing is sent after the cancel")
            assertEquals(1, rec.endings.size)
            assertNothingLeft(read)
        }

    @Test
    fun races__system_end_then_cancel__one_ended_by_system() =
        chipReadTest {
            val chip = FakeChip().apply { silentFrom = 2 }
            val (read, rec) = start()
            present(FakeChipTag(chip))
            step(400)
            systemEnds(CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY)
            read.cancel()
            step(1_000)
            assertIs<ChipReadEnding.EndedBySystem>(rec.ending)
            assertTrue(port.endings.isEmpty())
            assertNothingLeft(read)
        }

    @Test
    fun races__a_complete_read_then_cancel_and_a_late_system_end__one_complete() =
        chipReadTest {
            val (read, rec) = start()
            present(FakeChipTag(FakeChip()))
            step(400)
            read.cancel()
            port.events.onInvalidated(NfcUnavailable(Reason.ENDED_BY_SYSTEM))
            step(1_000)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertEquals(1, rec.endings.size)
            assertEquals(listOf<String?>(null), port.endings)
            assertNothingLeft(read)
        }

    @Test
    fun races__cancel_and_system_end_from_many_threads__exactly_one_ending() =
        chipReadTest {
            repeat(20) {
                val chip = FakeChip().apply { silentFrom = 2 }
                val (read, rec) = start()
                present(FakeChipTag(chip))
                step(400)
                val session = port
                withContext(Dispatchers.Default) {
                    repeat(4) { launch { read.cancel() } }
                    launch { session.systemEnd(NfcUnavailable(Reason.ENDED_BY_SYSTEM)) }
                }
                step(2_000)
                assertEquals(1, rec.endings.size)
                assertTrue(session.endings.size <= 1, "the SDK ends the session at most once")
                if (rec.ending is ChipReadEnding.EndedBySystem) assertTrue(session.endings.isEmpty())
                assertFalse(LiveSessions.contains(read))
                step(2_000)
            }
        }

    // --- Between two reads ----------------------------------------------------------------------------

    @Test
    fun between_reads__a_second_start_while_one_is_live_is_not_started_and_the_first_goes_on() =
        chipReadTest {
            val (first, rec) = start()
            val (second, other) = start()
            assertEquals(Reason.READ_IN_PROGRESS, assertIs<ChipReadEnding.NotStarted>(other.ending).reason)
            assertEquals(1, ports.size)
            assertTrue(second.isEnded)
            present(FakeChipTag(FakeChip()))
            step(400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertNothingLeft(first)
        }

    @Test
    fun between_reads__a_start_right_after_a_system_end_waits_in_starting() =
        chipReadTest {
            val (first, _) = start(settings = oneSession())
            step(3_000)
            systemEnds(CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY)
            assertNothingLeft(first)
            val (second, rec) = start(settings = oneSession())
            assertEquals(listOf("Starting"), rec.names)
            // The session never reported a document: the longer rest, not the 1 s setting.
            step(CoreNfcChipRead.UNSETTLED_SYSTEM_REST_MILLIS - 1)
            assertEquals(listOf("Starting"), rec.names)
            assertEquals(1, ports.size)
            step(1)
            assertEquals(2, ports.size)
            assertEquals("WaitingForDocument", rec.names.last())
            second.cancel()
            assertNothingLeft(second)
        }

    @Test
    fun between_reads__a_start_right_after_a_busy_refusal_waits_too() =
        chipReadTest {
            onBegin =
                {
                    it.systemEnd(
                        nfcErrorFor(platform.CoreNFC.NFCErrorDomain, CoreNfcErrorCode.SYSTEM_IS_BUSY, CoreNfcErrorSource.SESSION_END),
                    )
                }
            val (first, rec) = start(settings = oneSession())
            assertEquals(Reason.BUSY, assertIs<ChipReadEnding.NotStarted>(rec.ending).reason)
            assertNothingLeft(first)
            onBegin = {}
            val (second, next) = start(settings = oneSession())
            step(999)
            assertEquals(1, ports.size)
            step(1)
            assertEquals(2, ports.size)
            second.cancel()
            assertNothingLeft(second)
            assertEquals(1, next.endings.size)
        }

    @Test
    fun between_reads__a_start_right_after_an_app_cancel_needs_no_wait() =
        chipReadTest {
            val (first, _) = start()
            first.cancel()
            assertNothingLeft(first)
            val (second, rec) = start()
            assertEquals(2, ports.size)
            assertEquals("WaitingForDocument", rec.names.last())
            second.cancel()
        }

    @Test
    fun between_reads__a_previous_session_still_being_released_delays_the_next_read_in_starting() =
        chipReadTest {
            reportEnd = false
            val (first, firstRec) = start()
            val firstPort = port
            first.cancel()
            assertIs<ChipReadEnding.Cancelled>(firstRec.ending)
            assertEquals(listOf<String?>(CANCELLED), firstPort.endings)
            assertTrue(registry.isReleasing())
            assertTrue(LiveSessions.contains(first), "kept until the system reports the end")

            val (second, rec) = start()
            step(1_000)
            assertEquals(listOf("Starting"), rec.names)
            assertEquals(1, ports.size)
            firstPort.events.onInvalidated(NfcUnavailable(Reason.CANCELLED_BY_USER))
            step(100)
            assertEquals(2, ports.size)
            assertFalse(LiveSessions.contains(first))
            second.cancel()
            ports.last().events.onInvalidated(NfcUnavailable(Reason.CANCELLED_BY_USER))
            step()
            assertNothingLeft(second)
        }

    @Test
    fun between_reads__the_release_wait_is_bounded() =
        chipReadTest {
            reportEnd = false
            val (first, _) = start()
            first.cancel()
            val (second, rec) = start()
            step(4_900)
            assertEquals(1, ports.size)
            step(200)
            assertEquals(2, ports.size, "after 5 s the next read begins anyway")
            assertEquals("WaitingForDocument", rec.names.last())
            second.cancel()
        }
}
