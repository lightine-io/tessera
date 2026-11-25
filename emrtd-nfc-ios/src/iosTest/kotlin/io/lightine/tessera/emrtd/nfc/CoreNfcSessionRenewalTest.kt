package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadEvent
import io.lightine.tessera.emrtd.ChipReadReadiness
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.ChipReadStep
import io.lightine.tessera.emrtd.ChipReadStepOutcome
import io.lightine.tessera.emrtd.ObserverThread
import io.lightine.tessera.telemetry.TelemetryEvent
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import io.lightine.tessera.types.errors.NfcUnavailable.Reason
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TES-346: a read renews its system session while no document was reported. On an iPhone, about one
 * read in four with the session started before the phone was on the document never reported the
 * document, and the system ended the session with code 202 about 22 seconds after the document came
 * near; a new session with the phone already in place found it at once. Also the tries after a busy
 * refusal (code 203), and the readiness of a new read. All on the virtual clock, with fakes.
 */
class CoreNfcSessionRenewalTest {
    /** Every chip-read event a read reported, from any thread. */
    private class Events : TelemetrySink {
        private val lock = NSLock()
        private val all = mutableListOf<ChipReadEvent>()

        fun of(step: ChipReadStep): List<ChipReadEvent> {
            lock.lock()
            try {
                return all.filter { it.step == step }
            } finally {
                lock.unlock()
            }
        }

        override fun record(event: TelemetryEvent) {
            if (event !is ChipReadEvent) return
            lock.lock()
            all += event
            lock.unlock()
        }
    }

    /** A worker that runs blocks at once, or keeps them while [hold] is set. */
    private class SwitchWorker : SerialWorker {
        var hold = false
        val blocks = mutableListOf<() -> Unit>()

        override fun post(block: () -> Unit) {
            if (hold) blocks += block else block()
        }

        fun release() {
            hold = false
            while (blocks.isNotEmpty()) blocks.removeAt(0)()
        }
    }

    private val noEarly = CoreNfcReadSettings(sessionRenewMillis = CoreNfcReadSettings.SESSION_RENEW_OFF)

    // The renewal on a timer, off by default since the device run (TES-346): 10 s, two renewals.
    private val early10 = CoreNfcReadSettings(maxSessionRenewals = 2, sessionRenewMillis = 10_000)

    private fun ChipReadHarness.startTimed(settings: CoreNfcReadSettings = CoreNfcReadSettings()) =
        start(settings = settings, recorder = Recorder().also { it.clock = now })

    private fun ChipReadHarness.end202(p: FakeSessionPort = port) {
        p.systemEnd(
            nfcErrorFor(platform.CoreNFC.NFCErrorDomain, CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY, CoreNfcErrorSource.SESSION_END),
            CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY,
        )
        step()
    }

    private fun busy(p: FakeSessionPort) =
        p.systemEnd(
            nfcErrorFor(platform.CoreNFC.NFCErrorDomain, CoreNfcErrorCode.SYSTEM_IS_BUSY, CoreNfcErrorSource.SESSION_END),
            CoreNfcErrorCode.SYSTEM_IS_BUSY,
        )

    /** Session [number] (from 1) and later ones report a chip at once when they begin. */
    private fun ChipReadHarness.documentFoundFromSession(number: Int) {
        onBegin = { p -> if (ports.size >= number) p.events.onTagsDetected(listOf(FakeChipTag(FakeChip()))) }
    }

    /** Every session that reports no tag is ended by the system with code 202, 22 s after it began. */
    private fun ChipReadHarness.everyDeadSessionEndsAt22Seconds() {
        val before = onBegin
        onBegin = { p ->
            before(p)
            scope.launch {
                delay(22_000)
                if (p.endings.isEmpty() && p.systemEnds == 0) {
                    p.systemEnd(
                        nfcErrorFor(
                            platform.CoreNFC.NFCErrorDomain,
                            CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY,
                            CoreNfcErrorSource.SESSION_END,
                        ),
                        CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY,
                    )
                }
            }
        }
    }

    private fun waited(rec: Recorder): List<Long> = rec.states.mapNotNull { (it as? ChipReadState.WaitingForDocument)?.waitedMillis }

    // --- (a) The device case: the system ends a dead session with 202 --------------------------------

    @Test
    fun a__the_system_ends_a_dead_session_with_202__a_new_session_finds_the_document_and_the_read_completes() =
        chipReadTest {
            val events = Events()
            documentFoundFromSession(2)
            val (read, rec) = startTimed(CoreNfcReadSettings(sessionRenewMillis = 0, telemetry = events))
            step(22_000)
            end202()
            assertEquals("RenewingSession", rec.names.last())
            assertEquals(1, assertIs<ChipReadState.RenewingSession>(rec.states.last()).renewal)
            assertEquals(1, ports.size, "the rest after the system's end comes first")
            step(999)
            assertEquals(1, ports.size)
            step(1)
            assertEquals(2, ports.size)
            step(400)
            val ending = assertIs<ChipReadEnding.Complete>(rec.ending)
            assertEquals(23_400L, rec.endingTimes.single(), "22 s, 1 s of rest, 400 ms to settle; the fake chip answers at once")
            assertEquals(1, ending.attempts, "a renewal is no new attempt")
            assertEquals(1, rec.endings.size)
            // The waiting time goes on from the read's start.
            assertEquals(23_000L, waited(rec).last())
            assertTrue(ports[0].endings.isEmpty(), "the system ended the first session; the SDK sends no ending to it")
            assertEquals(listOf<String?>(null), ports[1].endings, "the second session ends with success")
            assertEquals(
                CoreNfcDefaultSheetTexts.KEEP_HOLDING_TEXT,
                ports[1].alerts.first(),
                "the new sheet opens after 23 s of waiting: the keep-holding text from the start",
            )
            assertTrue(CoreNfcDefaultSheetTexts.WAITING_TEXT !in ports[1].alerts)
            val renew = events.of(ChipReadStep.SESSION_RENEW).single()
            assertEquals(1, renew.renewal)
            assertEquals(202, renew.systemCode)
            assertEquals(ChipReadStepOutcome.SUCCEEDED, renew.outcome)
            assertEquals(1_000L, renew.durationMillis)
            val end = events.of(ChipReadStep.SESSION_END).single()
            assertEquals(202, end.systemCode)
            assertEquals(22_000L, end.durationMillis)
            assertNothingLeft(read)
        }

    @Test
    fun a__the_states_in_order() =
        chipReadTest {
            documentFoundFromSession(2)
            val (read, rec) = startTimed(noEarly)
            step(4_000)
            end202()
            step(1_400)
            assertEquals(
                listOf("Starting") + List(3) { "WaitingForDocument" } +
                    listOf("RenewingSession", "WaitingForDocument", "DocumentFound", "Connecting"),
                rec.names.take(8),
            )
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertNothingLeft(read)
        }

    // --- (b) The renewal on a timer -----------------------------------------------------------------

    @Test
    fun b__no_document_for_10_seconds__the_read_ends_the_session_and_a_new_one_finds_it() =
        chipReadTest {
            val events = Events()
            documentFoundFromSession(2)
            val (read, rec) = startTimed(early10.toBuilder().telemetry(events).build())
            step(9_999)
            assertEquals(1, ports.size)
            assertTrue(ports[0].endings.isEmpty())
            step(1)
            assertEquals(listOf<String?>("Searching again"), ports[0].endings, "the read ends the old session with the renewal's text")
            assertEquals(listOf(4_000L, 8_000L), ports[0].restartTimes)
            step(1_000)
            assertEquals(2, ports.size)
            step(400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertEquals(11_400L, rec.endingTimes.single(), "10 s, 1 s of rest, 400 ms to settle")
            val renew = events.of(ChipReadStep.SESSION_RENEW).single()
            assertNull(renew.systemCode, "a renewal on the timer has no system code")
            assertEquals(1_000L, renew.durationMillis)
            assertTrue(events.of(ChipReadStep.SESSION_END).isEmpty(), "the system's report of the SDK's own end is no event")
            assertNothingLeft(read)
        }

    @Test
    fun b__the_echo_of_the_old_session_comes_late__the_read_waits_for_it_before_the_rest() =
        chipReadTest {
            reportEnd = false
            documentFoundFromSession(2)
            val (read, rec) = startTimed(early10)
            val old = port
            step(10_000)
            assertEquals(1, old.endings.size)
            step(1_500)
            assertEquals(1, ports.size, "no new session before the old one is reported closed")
            old.events.onInvalidated(NfcUnavailable(Reason.CANCELLED_BY_USER), CoreNfcErrorCode.USER_CANCELED)
            step(999)
            assertEquals(1, ports.size)
            step(1)
            assertEquals(2, ports.size, "the rest counts from the old session's end report")
            step(400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            ports[1].events.onInvalidated(NfcUnavailable(Reason.CANCELLED_BY_USER), CoreNfcErrorCode.USER_CANCELED)
            step()
            assertNothingLeft(read)
        }

    @Test
    fun b__the_echo_never_comes__the_read_goes_on_after_its_bound() =
        chipReadTest {
            reportEnd = false
            documentFoundFromSession(2)
            val (read, rec) = startTimed(early10)
            step(10_000 + CoreNfcChipRead.RENEW_END_WAIT_MILLIS - 1)
            assertEquals(1, ports.size)
            step(1 + 1_000)
            assertEquals(2, ports.size)
            step(400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertEquals(13_400L, rec.endingTimes.single())
            ports.forEach { it.events.onInvalidated(NfcUnavailable(Reason.CANCELLED_BY_USER), CoreNfcErrorCode.USER_CANCELED) }
            step()
            assertNothingLeft(read)
        }

    // --- (c) The limit --------------------------------------------------------------------------------

    @Test
    fun c__two_renewals_then_the_third_dead_session_ends_the_read_as_ended_by_the_system() =
        chipReadTest {
            val events = Events()
            everyDeadSessionEndsAt22Seconds()
            val (read, rec) = startTimed(CoreNfcReadSettings(maxSessionRenewals = 2, telemetry = events))
            step(70_000)
            val ending = assertIs<ChipReadEnding.EndedBySystem>(rec.ending)
            assertEquals(NfcUnavailable(Reason.ENDED_BY_SYSTEM), ending.reason)
            assertEquals(68_000L, rec.endingTimes.single(), "22 s, then 1 s of rest and 22 s, twice")
            assertEquals(3, ports.size)
            assertEquals(listOf(1, 2), rec.states.filterIsInstance<ChipReadState.RenewingSession>().map { it.renewal })
            assertEquals(listOf(1, 2), events.of(ChipReadStep.SESSION_RENEW).map { it.renewal })
            assertEquals(3, events.of(ChipReadStep.SESSION_END).size, "each system end is reported")
            assertTrue(ports.all { it.endings.isEmpty() })
            assertNothingLeft(read)
        }

    @Test
    fun c__renewal_on_a_timer_two_renewals__then_the_system_ends_the_third_session() =
        chipReadTest {
            everyDeadSessionEndsAt22Seconds()
            val (read, rec) = startTimed(early10)
            step(50_000)
            assertIs<ChipReadEnding.EndedBySystem>(rec.ending)
            assertEquals(44_000L, rec.endingTimes.single(), "sessions at 0, 11 and 22 s; the third lives until the system ends it")
            assertEquals(listOf<String?>("Searching again"), ports[0].endings)
            assertEquals(listOf<String?>("Searching again"), ports[1].endings)
            assertTrue(ports[2].endings.isEmpty())
            assertNothingLeft(read)
        }

    @Test
    fun c__defaults__no_renewal_on_a_timer__one_renewal_after_the_dead_session__then_the_read_ends() =
        chipReadTest {
            everyDeadSessionEndsAt22Seconds()
            val (read, rec) = startTimed()
            step(21_999)
            assertEquals(1, ports.size, "no renewal on a timer by default")
            assertTrue(ports[0].endings.isEmpty())
            step(80_000)
            val ending = assertIs<ChipReadEnding.EndedBySystem>(rec.ending)
            assertEquals(NfcUnavailable(Reason.ENDED_BY_SYSTEM), ending.reason)
            assertEquals(0, ending.attempts, "the system never reported the document")
            assertEquals(45_000L, rec.endingTimes.single(), "22 s, 1 s of rest, 22 s")
            assertEquals(2, ports.size)
            assertTrue(ports.all { it.endings.isEmpty() })
            assertNothingLeft(read)
        }

    @Test
    fun c__renewals_off__a_202_ends_the_read_as_before() =
        chipReadTest {
            val (read, rec) = startTimed(CoreNfcReadSettings(maxSessionRenewals = CoreNfcReadSettings.SESSION_RENEWALS_OFF))
            step(30_000)
            assertEquals(1, ports.size, "no renewal on the timer either")
            end202()
            assertIs<ChipReadEnding.EndedBySystem>(rec.ending)
            assertNothingLeft(read)
        }

    // --- (d) Tags around a renewal --------------------------------------------------------------------

    @Test
    fun d__a_tag_reported_just_before_the_renewal__no_renewal_and_the_read_completes() =
        chipReadTest {
            val (read, rec) = startTimed()
            step(9_999)
            present(FakeChipTag(FakeChip()))
            step(400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertEquals(10_399L, rec.endingTimes.single())
            assertEquals(1, ports.size)
            assertTrue(rec.states.none { it is ChipReadState.RenewingSession })
            assertNothingLeft(read)
        }

    @Test
    fun d__a_tag_reported_then_lost__no_renewal_and_a_202_ends_the_read() =
        chipReadTest {
            val (read, rec) = startTimed()
            present(FakeChipTag(FakeChip().apply { silentFrom = 1 }))
            // The tag does not answer: tries in the same session, never a renewal.
            step(30_000)
            assertEquals(1, ports.size)
            end202()
            assertIs<ChipReadEnding.EndedBySystem>(rec.ending)
            assertEquals(1, ports.size)
            assertNothingLeft(read)
        }

    @Test
    fun d__a_tag_reported_in_the_old_session_after_its_end_was_asked_for__ignored() =
        chipReadTest {
            val worker = SwitchWorker()
            sheetWorker = { worker }
            documentFoundFromSession(2)
            val (read, rec) = startTimed(early10)
            step(9_999)
            worker.hold = true
            step(1)
            assertEquals("RenewingSession", rec.names.last())
            assertTrue(ports[0].endings.isEmpty(), "the end is queued on the sheet's queue")
            val late = FakeChipTag(FakeChip())
            ports[0].events.onTagsDetected(listOf(late))
            step(100)
            worker.release()
            step(1_000)
            assertEquals(2, ports.size)
            step(400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertEquals(0, late.connectCalls, "the old session's tag is never used")
            assertEquals(1, rec.states.count { it is ChipReadState.DocumentFound })
            assertNothingLeft(read)
        }

    @Test
    fun d__a_202_after_the_read_asked_for_the_old_sessions_end__is_its_echo() =
        chipReadTest {
            val worker = SwitchWorker()
            sheetWorker = { worker }
            documentFoundFromSession(2)
            val events = Events()
            val (read, rec) = startTimed(early10.toBuilder().telemetry(events).build())
            step(9_999)
            worker.hold = true
            step(1)
            end202(ports[0])
            worker.release()
            step(1_400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertEquals(2, ports.size, "one renewal, not two")
            assertEquals(1, rec.states.count { it is ChipReadState.RenewingSession })
            assertTrue(events.of(ChipReadStep.SESSION_END).isEmpty())
            assertNothingLeft(read)
        }

    // --- (e) Cancels around a renewal ---------------------------------------------------------------

    @Test
    fun e__the_person_cancels_on_the_new_session__cancelled_by_the_person_once() =
        chipReadTest {
            val (read, rec) = startTimed(noEarly)
            step(5_000)
            end202()
            step(1_000)
            assertEquals(2, ports.size)
            step(3_000)
            systemEnds(CoreNfcErrorCode.USER_CANCELED)
            assertEquals(ChipReadEnding.Cancelled.By.PERSON, assertIs<ChipReadEnding.Cancelled>(rec.ending).by)
            step(30_000)
            assertEquals(1, rec.endings.size)
            assertEquals(2, ports.size)
            assertNothingLeft(read)
        }

    @Test
    fun e__the_old_sheets_end_report_during_the_gap_is_the_echo__then_the_person_cancels_the_new_one() =
        chipReadTest {
            // The fake reports "user canceled" for the SDK's own end, as Core NFC does: the read
            // cannot tell a Cancel on the closing sheet from that, and opens the new session.
            val (read, rec) = startTimed(early10)
            step(10_000)
            assertEquals(listOf<String?>("Searching again"), ports[0].endings)
            assertTrue(rec.endings.isEmpty())
            step(1_000)
            assertEquals(2, ports.size)
            systemEnds(CoreNfcErrorCode.USER_CANCELED)
            assertEquals(ChipReadEnding.Cancelled.By.PERSON, assertIs<ChipReadEnding.Cancelled>(rec.ending).by)
            assertNothingLeft(read)
        }

    @Test
    fun e__the_person_cancels_before_any_renewal__no_renewal() =
        chipReadTest {
            val (read, rec) = startTimed()
            step(5_000)
            systemEnds(CoreNfcErrorCode.USER_CANCELED)
            assertIs<ChipReadEnding.Cancelled>(rec.ending)
            step(30_000)
            assertEquals(1, ports.size)
            assertNothingLeft(read)
        }

    @Test
    fun e__the_app_cancels_during_the_gap__a_prompt_ending_and_no_new_session() =
        chipReadTest {
            val (read, rec) = startTimed(noEarly)
            step(22_000)
            end202()
            step(500)
            read.cancel()
            step()
            assertEquals(ChipReadEnding.Cancelled.By.APP, assertIs<ChipReadEnding.Cancelled>(rec.ending).by)
            assertEquals(22_500L, rec.endingTimes.single())
            step(30_000)
            assertEquals(1, ports.size, "no session begun after the cancel")
            assertNothingLeft(read)
        }

    @Test
    fun e__the_app_cancels_while_the_old_session_closes__no_new_session() =
        chipReadTest {
            reportEnd = false
            val (read, rec) = startTimed(early10)
            step(10_500)
            read.cancel()
            step()
            assertIs<ChipReadEnding.Cancelled>(rec.ending)
            assertEquals(10_500L, rec.endingTimes.single())
            ports[0].events.onInvalidated(NfcUnavailable(Reason.CANCELLED_BY_USER), CoreNfcErrorCode.USER_CANCELED)
            step(30_000)
            assertEquals(1, ports.size)
            assertNothingLeft(read)
        }

    // --- (f) A new session refused as busy ------------------------------------------------------------

    @Test
    fun f__the_new_session_refused_twice_then_accepted__the_read_goes_on() =
        chipReadTest {
            val events = Events()
            onBegin = { p ->
                when (ports.size) {
                    2, 3 -> busy(p)
                    4 -> p.events.onTagsDetected(listOf(FakeChipTag(FakeChip())))
                }
            }
            val (read, rec) = startTimed(CoreNfcReadSettings(sessionRenewMillis = 0, telemetry = events))
            step(22_000)
            end202()
            step(1_000)
            assertEquals(2, ports.size)
            step(500)
            assertEquals(3, ports.size)
            step(1_000)
            assertEquals(4, ports.size)
            step(400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertEquals(24_900L, rec.endingTimes.single())
            assertEquals(1, rec.states.count { it == ChipReadState.Starting }, "no Starting again")
            assertEquals(1, rec.states.count { it is ChipReadState.RenewingSession })
            val renew = events.of(ChipReadStep.SESSION_RENEW).single()
            assertEquals(ChipReadStepOutcome.SUCCEEDED, renew.outcome)
            assertEquals(2_500L, renew.durationMillis)
            assertEquals(listOf(202, 203, 203), events.of(ChipReadStep.SESSION_END).map { it.systemCode })
            assertNothingLeft(read)
        }

    @Test
    fun f__the_new_session_refused_until_the_bound__ended_by_the_system_with_busy() =
        chipReadTest {
            val events = Events()
            onBegin = { p -> if (ports.size >= 2) busy(p) }
            val (read, rec) = startTimed(CoreNfcReadSettings(sessionRenewMillis = 0, telemetry = events))
            step(22_000)
            end202()
            step(1_000)
            // Tries at 23.0, 23.5, 24.5, 26.5 and 29.0 s: 6 s after the first refusal.
            step(5_999)
            assertTrue(rec.endings.isEmpty())
            assertEquals(5, ports.size)
            step(1)
            val ending = assertIs<ChipReadEnding.EndedBySystem>(rec.ending)
            assertEquals(NfcUnavailable(Reason.BUSY), ending.reason)
            assertEquals(29_000L, rec.endingTimes.single())
            assertEquals(6, ports.size)
            val renew = events.of(ChipReadStep.SESSION_RENEW).single()
            assertEquals(ChipReadStepOutcome.FAILED, renew.outcome)
            assertNothingLeft(read)
        }

    @Test
    fun f__busy_tries_off__the_first_refusal_of_a_new_session_ends_the_read() =
        chipReadTest {
            onBegin = { p -> if (ports.size >= 2) busy(p) }
            val (read, rec) = startTimed(CoreNfcReadSettings(sessionRenewMillis = 0, busyRetryMillis = CoreNfcReadSettings.BUSY_RETRY_OFF))
            step(22_000)
            end202()
            step(1_000)
            assertEquals(NfcUnavailable(Reason.BUSY), assertIs<ChipReadEnding.EndedBySystem>(rec.ending).reason)
            assertEquals(2, ports.size)
            assertNothingLeft(read)
        }

    // --- (g) The first session refused as busy --------------------------------------------------------

    @Test
    fun g__the_first_session_refused_then_accepted__the_read_stays_in_starting_and_completes() =
        chipReadTest {
            onBegin = { p -> if (ports.size == 1) busy(p) else p.events.onTagsDetected(listOf(FakeChipTag(FakeChip()))) }
            val (read, rec) = startTimed()
            assertEquals(listOf("Starting"), rec.names)
            step(499)
            assertEquals(listOf("Starting"), rec.names, "no waiting state while the system refuses")
            step(1)
            assertEquals(2, ports.size)
            assertEquals(listOf(0L), waited(rec), "the wait counts from the session that is on")
            step(400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertEquals(900L, rec.endingTimes.single())
            assertNothingLeft(read)
        }

    @Test
    fun g__the_first_session_refused_until_the_bound__not_started_with_busy() =
        chipReadTest {
            onBegin = { p -> busy(p) }
            val (read, rec) = startTimed()
            step(5_999)
            assertTrue(rec.endings.isEmpty())
            assertEquals(listOf("Starting"), rec.names)
            step(1)
            assertEquals(Reason.BUSY, assertIs<ChipReadEnding.NotStarted>(rec.ending).reason)
            assertEquals(6_000L, rec.endingTimes.single())
            assertEquals(5, ports.size, "tries at 0, 0.5, 1.5, 3.5 and 6 s")
            assertTrue(ports.all { it.endings.isEmpty() }, "nothing to end: the system refused each")
            assertNothingLeft(read)
        }

    @Test
    fun g__the_app_cancels_during_the_tries__a_prompt_ending_and_no_more_tries() =
        chipReadTest {
            onBegin = { p -> busy(p) }
            val (read, rec) = startTimed()
            step(1_000)
            read.cancel()
            step()
            assertIs<ChipReadEnding.Cancelled>(rec.ending)
            assertEquals(1_000L, rec.endingTimes.single())
            val tries = ports.size
            step(10_000)
            assertEquals(tries, ports.size)
            assertNothingLeft(read)
        }

    // --- (h) The system's own limits, as before ----------------------------------------------------------

    @Test
    fun h__the_60_second_limit_ends_the_read_without_a_renewal() =
        chipReadTest {
            val (read, rec) = startTimed(noEarly)
            step(60_000)
            systemEnds(CoreNfcErrorCode.SESSION_TIMEOUT)
            assertEquals(NfcTimeout(null, NfcTimeout.Limit.SESSION), assertIs<ChipReadEnding.EndedBySystem>(rec.ending).reason)
            assertEquals(1, ports.size)
            assertNothingLeft(read)
        }

    @Test
    fun h__the_60_second_limit_of_a_renewed_session_ends_the_read() =
        chipReadTest {
            val (read, rec) = startTimed(early10)
            step(30_000)
            assertEquals(3, ports.size)
            systemEnds(CoreNfcErrorCode.SESSION_TIMEOUT)
            assertIs<ChipReadEnding.EndedBySystem>(rec.ending)
            step(10_000)
            assertEquals(3, ports.size)
            assertNothingLeft(read)
        }

    @Test
    fun h__a_lost_link_after_a_renewal_counts_the_time_left_from_the_new_session() =
        chipReadTest {
            // The third session begins at 22 s; its document comes 30 s later, 52 s into the read,
            // and its first command is not answered. With 30 s of that session left, the read tries
            // again in it: the 60 s count from the session's begin, not the read's.
            onBegin = { p ->
                if (ports.size == 3) {
                    scope.launch {
                        delay(30_000)
                        p.events.onTagsDetected(listOf(FakeChipTag(FakeChip().apply { loseAt += 1 })))
                    }
                }
            }
            val (read, rec) = startTimed(early10)
            step(52_400)
            assertEquals(3, ports.size)
            assertEquals("NoAnswerYet", rec.names.last())
            assertTrue(rec.endings.isEmpty())
            read.cancel()
            step()
            assertNothingLeft(read)
        }

    // --- (i) Readiness ---------------------------------------------------------------------------------

    @Test
    fun i__readiness__in_progress_then_not_yet_after_a_system_end_then_ready_after_the_rest() =
        chipReadTest {
            val main = mutableListOf<() -> Unit>()
            mainQueue = { main += it }
            val heard = mutableListOf<ChipReadReadiness>()
            val watch = env.readiness
            assertEquals(ChipReadReadiness.Ready, watch.current())
            val registration = watch.add { heard += it }
            // The observer on the read's thread: only the listener goes through the main queue here.
            val (read, rec) = startTimed(oneSession(CoreNfcReadSettings(observerOn = ObserverThread.CALLER)))
            assertEquals(ChipReadReadiness.ReadInProgress, watch.current())
            step(5_000)
            end202()
            assertIs<ChipReadEnding.EndedBySystem>(rec.ending)
            val notYet = assertIs<ChipReadReadiness.NotYet>(watch.current())
            assertEquals(1_000L, notYet.remainingMillis)
            step(400)
            assertEquals(600L, assertIs<ChipReadReadiness.NotYet>(watch.current()).remainingMillis)
            assertTrue(heard.isEmpty(), "the listener is called on the main queue, not in place")
            step(600)
            assertEquals(ChipReadReadiness.Ready, watch.current())
            main.toList().forEach { it() }
            main.clear()
            assertEquals(listOf("ReadInProgress", "NotYet", "Ready"), heard.map { it::class.simpleName })
            // No change: no call.
            step(10_000)
            assertTrue(main.isEmpty())
            registration.remove()
            registration.remove()
            startTimed(oneSession(CoreNfcReadSettings(observerOn = ObserverThread.CALLER))).first.cancel()
            step()
            main.toList().forEach { it() }
            assertEquals(3, heard.size, "a removed listener is not called")
            assertNothingLeft(read)
        }

    @Test
    fun i__readiness__the_rest_is_the_one_set_by_the_read_that_saw_the_end__a_later_read_waits_that_long() =
        chipReadTest {
            val caller = CoreNfcReadSettings(observerOn = ObserverThread.CALLER, waitAfterSystemEndMillis = 600)
            val (first, rec) = startTimed(oneSession(caller))
            step(1_000)
            end202()
            assertIs<ChipReadEnding.EndedBySystem>(rec.ending)
            assertEquals(600L, assertIs<ChipReadReadiness.NotYet>(env.readiness.current()).remainingMillis)
            // A later read with a longer rest of its own waits the time readiness said, not its own.
            val (second, _) = startTimed(oneSession(caller.toBuilder().waitAfterSystemEndMillis(5_000).build()))
            step(599)
            assertEquals(1, ports.size, "still resting")
            step(1)
            assertEquals(2, ports.size, "the session begins when readiness said Ready")
            assertEquals(ChipReadReadiness.ReadInProgress, env.readiness.current())
            second.cancel()
            step()
            assertNothingLeft(first)
        }

    @Test
    fun i__readiness__a_change_already_queued_is_dropped_after_remove() =
        chipReadTest {
            val main = mutableListOf<() -> Unit>()
            mainQueue = { main += it }
            val heard = mutableListOf<ChipReadReadiness>()
            val registration = env.readiness.add { heard += it }
            val (read, _) = startTimed(CoreNfcReadSettings(observerOn = ObserverThread.CALLER))
            assertEquals(1, main.size)
            registration.remove()
            main.toList().forEach { it() }
            assertTrue(heard.isEmpty())
            read.cancel()
            step()
        }

    @Test
    fun i__readiness__after_an_app_cancel_ready_once_the_session_is_reported_closed() =
        chipReadTest {
            reportEnd = false
            val heard = mutableListOf<ChipReadReadiness>()
            env.readiness.add { heard += it }
            val (read, _) = startTimed()
            read.cancel()
            step()
            val notYet = assertIs<ChipReadReadiness.NotYet>(env.readiness.current())
            assertEquals(CoreNfcChipRead.RELEASE_WAIT_LIMIT_MILLIS, notYet.remainingMillis)
            port.events.onInvalidated(NfcUnavailable(Reason.CANCELLED_BY_USER), CoreNfcErrorCode.USER_CANCELED)
            step()
            assertEquals(ChipReadReadiness.Ready, env.readiness.current())
            assertEquals(listOf("ReadInProgress", "NotYet", "Ready"), heard.map { it::class.simpleName })
            assertNothingLeft(read)
        }

    @Test
    fun i__readiness__the_release_wait_ends_by_its_bound_with_no_report() =
        chipReadTest {
            reportEnd = false
            val heard = mutableListOf<ChipReadReadiness>()
            env.readiness.add { heard += it }
            val (read, _) = startTimed()
            step(1_000)
            read.cancel()
            step(4_999)
            assertIs<ChipReadReadiness.NotYet>(env.readiness.current())
            step(1)
            assertEquals(ChipReadReadiness.Ready, env.readiness.current())
            assertEquals(ChipReadReadiness.Ready, heard.last())
            // A start now begins at once: the bound counts from the earlier read's ending.
            val (next, rec) = startTimed()
            assertEquals("WaitingForDocument", rec.names.last())
            next.cancel()
            step()
        }

    @Test
    fun i__readiness__unavailable_without_a_reader_and_a_listener_that_throws_is_ignored() =
        chipReadTest {
            env.readiness.add { error("app bug") }
            val heard = mutableListOf<ChipReadReadiness>()
            env.readiness.add { heard += it }
            val (read, _) = startTimed()
            assertEquals(listOf<ChipReadReadiness>(ChipReadReadiness.ReadInProgress), heard)
            read.cancel()
            step()
            readingAvailable = false
            assertEquals(ChipReadReadiness.unavailable(Reason.NOT_SUPPORTED), env.readiness.current())
            assertEquals("Unavailable(NOT_SUPPORTED)", env.readiness.current().toString())
        }

    // --- (j) No Core NFC call on the delivery path during a renewal -------------------------------------

    @Test
    fun j__the_old_sessions_end_call_stuck__updates_go_on_and_the_read_completes_in_a_new_session() =
        chipReadTest {
            sheetWorker = ::newSheetQueueWorker
            val stuck = StuckSetter(blockOn = INVALIDATE)
            onBegin = { p ->
                if (ports.size == 1) p.onInvalidate = { stuck.hook(INVALIDATE) }
                if (ports.size == 2) p.events.onTagsDetected(listOf(FakeChipTag(FakeChip())))
            }
            val (read, rec) = startTimed(early10)
            awaitReal { port.began }
            try {
                step(10_000)
                assertTrue(stuck.awaitEntered(), "the old session's end runs on the sheet's queue")
                assertEquals("RenewingSession", rec.names.last())
                assertEquals(10_000L, rec.updateTimes.last(), "the update is not held up")
                step(CoreNfcChipRead.RENEW_END_WAIT_MILLIS)
                step(1_000)
                awaitReal { ports.size == 2 && ports[1].began }
                step(400)
                assertIs<ChipReadEnding.Complete>(rec.ending)
                assertEquals(13_400L, rec.endingTimes.single())
                assertTrue(stuck.inFlight)
                assertTrue(!stuck.calledOnCaller, "no end call ran on the read's own thread")
            } finally {
                stuck.release()
            }
            awaitRealTime { stuck.returned }
            awaitReal { ports[1].endings.isNotEmpty() }
            assertNothingLeft(read)
        }

    @Test
    fun j__the_old_sessions_end_call_stuck__the_app_cancel_ends_the_read_at_once() =
        chipReadTest {
            sheetWorker = ::newSheetQueueWorker
            val stuck = StuckSetter(blockOn = INVALIDATE)
            onBegin = { p -> if (ports.size == 1) p.onInvalidate = { stuck.hook(INVALIDATE) } }
            val (read, rec) = startTimed(early10)
            awaitReal { port.began }
            try {
                step(10_000)
                assertTrue(stuck.awaitEntered())
                step(500)
                read.cancel()
                step()
                assertIs<ChipReadEnding.Cancelled>(rec.ending)
                assertEquals(10_500L, rec.endingTimes.single())
                step(10_000)
                assertEquals(1, ports.size)
            } finally {
                stuck.release()
            }
            awaitRealTime { stuck.returned }
            assertNothingLeft(read)
        }

    // --- (l) A dead session ended with 203, and the bound on the whole wait (device run, TES-346) ------

    /** The device case B4: 202 at 22.7 s, a renewal, then the new session ends with 203 after 18 s. */
    private fun ChipReadHarness.deviceCaseB4(
        settings: CoreNfcReadSettings,
        events: Events,
        reportActive: Boolean,
    ): Pair<CoreNfcChipRead, Recorder> {
        onBegin = { p -> p.reportActive = reportActive }
        val started = startTimed(settings.toBuilder().telemetry(events).build())
        step(22_700)
        end202()
        step(1_000)
        assertEquals(2, ports.size)
        step(18_012)
        busy(ports[1])
        step()
        return started
    }

    private fun b4WithDefaults(reportActive: Boolean) =
        chipReadTest {
            val events = Events()
            val (read, rec) = deviceCaseB4(CoreNfcReadSettings(), events, reportActive)
            val ending = assertIs<ChipReadEnding.EndedBySystem>(rec.ending)
            assertEquals(NfcUnavailable(Reason.ENDED_BY_SYSTEM), ending.reason, "a dead session's end, not busy")
            assertEquals(0, ending.attempts, "the system never reported the document")
            assertEquals(22_700L + 1_000 + 18_012, rec.endingTimes.single(), "at once: about 42 s")
            step(60_000)
            assertEquals(2, ports.size, "no further session, no busy tries")
            assertEquals(listOf(202, 203), events.of(ChipReadStep.SESSION_END).map { it.systemCode })
            assertEquals(18_012L, events.of(ChipReadStep.SESSION_END).last().durationMillis)
            assertEquals(1, rec.states.count { it is ChipReadState.RenewingSession })
            assertNothingLeft(read)
        }

    @Test
    fun l__device_case_b4__the_new_session_ends_with_203_after_18_s__the_read_ends_at_once() = b4WithDefaults(reportActive = true)

    @Test
    fun l__device_case_b4__the_same_with_no_on_report_from_the_system() = b4WithDefaults(reportActive = false)

    @Test
    fun l__device_case_b4__with_two_renewals_the_203_after_18_s_opens_one_more_session() =
        chipReadTest {
            val events = Events()
            val (read, rec) = deviceCaseB4(CoreNfcReadSettings(maxSessionRenewals = 2), events, reportActive = false)
            assertTrue(rec.endings.isEmpty())
            assertEquals(listOf(1, 2), rec.states.filterIsInstance<ChipReadState.RenewingSession>().map { it.renewal })
            step(1_000)
            assertEquals(3, ports.size, "a new session after the rest, no busy try")
            step(18_000)
            assertEquals(listOf(202, 203), events.of(ChipReadStep.SESSION_RENEW).map { it.systemCode })
            busy(ports[2])
            step(20_000)
            assertEquals(NfcUnavailable(Reason.ENDED_BY_SYSTEM), assertIs<ChipReadEnding.EndedBySystem>(rec.ending).reason)
            assertEquals(3, ports.size, "the third dead session ends the read")
            assertNothingLeft(read)
        }

    @Test
    fun l__a_203_at_begin_with_no_on_report__still_a_refusal_tried_again() =
        chipReadTest {
            onBegin = { p ->
                when (ports.size) {
                    2 -> busy(p)
                    3 -> p.events.onTagsDetected(listOf(FakeChipTag(FakeChip())))
                }
            }
            val (read, rec) = startTimed()
            step(22_000)
            end202()
            step(1_000)
            assertEquals(2, ports.size)
            step(500)
            assertEquals(3, ports.size, "the refused begin is tried again after 0.5 s")
            step(400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertEquals(1, rec.states.count { it is ChipReadState.RenewingSession }, "a refusal costs no renewal")
            assertNothingLeft(read)
        }

    @Test
    fun l__a_203_just_inside_the_refusal_window_with_no_on_report__a_refusal() =
        chipReadTest {
            onBegin = { p ->
                if (ports.size == 1) {
                    p.reportActive = false
                    scope.launch {
                        delay(CoreNfcChipRead.REFUSAL_WINDOW_MILLIS - 1)
                        busy(p)
                    }
                }
                if (ports.size == 2) p.events.onTagsDetected(listOf(FakeChipTag(FakeChip())))
            }
            val (read, rec) = startTimed()
            step(CoreNfcChipRead.REFUSAL_WINDOW_MILLIS - 1)
            assertTrue(rec.states.none { it is ChipReadState.RenewingSession }, "a refusal, not a dead session")
            step(500)
            assertEquals(2, ports.size)
            step(400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertNothingLeft(read)
        }

    @Test
    fun l__a_203_at_the_refusal_window_with_no_on_report__a_dead_session_renewed() =
        chipReadTest {
            onBegin = { p ->
                if (ports.size == 1) {
                    p.reportActive = false
                    scope.launch {
                        delay(CoreNfcChipRead.REFUSAL_WINDOW_MILLIS)
                        busy(p)
                    }
                }
                if (ports.size == 2) p.events.onTagsDetected(listOf(FakeChipTag(FakeChip())))
            }
            val (read, rec) = startTimed()
            step(CoreNfcChipRead.REFUSAL_WINDOW_MILLIS)
            assertEquals(1, assertIs<ChipReadState.RenewingSession>(rec.states.last()).renewal)
            step(1_400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertNothingLeft(read)
        }

    @Test
    fun l__a_203_soon_after_the_on_report__still_a_refusal__the_time_decides() =
        chipReadTest {
            onBegin = { p -> if (ports.size == 2) p.events.onTagsDetected(listOf(FakeChipTag(FakeChip()))) }
            val (read, rec) = startTimed()
            step(300)
            busy(port)
            step()
            assertTrue(rec.states.none { it is ChipReadState.RenewingSession }, "no renewal used")
            step(500)
            assertEquals(2, ports.size, "tried again after 0.5 s")
            step(400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertNothingLeft(read)
        }

    @Test
    fun l__renewals_off__a_dead_203_ends_the_read_as_ended_by_the_system_not_busy() =
        chipReadTest {
            val (read, rec) = startTimed(CoreNfcReadSettings(maxSessionRenewals = CoreNfcReadSettings.SESSION_RENEWALS_OFF))
            step(18_000)
            busy(port)
            step()
            assertEquals(NfcUnavailable(Reason.ENDED_BY_SYSTEM), assertIs<ChipReadEnding.EndedBySystem>(rec.ending).reason)
            assertEquals(1, ports.size)
            assertNothingLeft(read)
        }

    // --- (m) The keep-holding text ----------------------------------------------------------------------

    @Test
    fun m__the_keep_holding_text_comes_once_at_8_s__and_a_renewed_sheet_starts_with_it() =
        chipReadTest {
            val waiting = CoreNfcDefaultSheetTexts.WAITING_TEXT
            val keep = CoreNfcDefaultSheetTexts.KEEP_HOLDING_TEXT
            val (read, rec) = startTimed()
            step(7_999)
            assertEquals(listOf(waiting), ports[0].alerts)
            step(1)
            assertEquals(listOf(waiting, keep), ports[0].alerts)
            assertEquals(listOf(0L, 8_000L), ports[0].alertTimes)
            step(14_000)
            assertEquals(listOf(waiting, keep), ports[0].alerts, "never again, never back")
            end202()
            step(1_000)
            assertEquals(listOf(keep), ports[1].alerts, "the new sheet starts with the keep-holding text")
            step(15_000)
            assertEquals(listOf(keep), ports[1].alerts)
            read.cancel()
            step()
            assertNothingLeft(read)
            assertTrue(rec.endings.isNotEmpty())
        }

    // --- (n) The readiness number -----------------------------------------------------------------------

    @Test
    fun n__readiness__not_yet_counts_the_release_bound_down__ready_as_soon_as_the_system_reports_the_close() =
        chipReadTest {
            reportEnd = false
            val heard = mutableListOf<ChipReadReadiness>()
            env.readiness.add { heard += it }
            val (read, _) = startTimed()
            read.cancel()
            step()
            assertEquals(5_000L, assertIs<ChipReadReadiness.NotYet>(env.readiness.current()).remainingMillis, "the bound, an upper limit")
            step(2_000)
            assertEquals(3_000L, assertIs<ChipReadReadiness.NotYet>(env.readiness.current()).remainingMillis)
            step(700)
            port.events.onInvalidated(NfcUnavailable(Reason.CANCELLED_BY_USER), CoreNfcErrorCode.USER_CANCELED)
            step()
            assertEquals(ChipReadReadiness.Ready, env.readiness.current(), "ready at 2.7 s, before the 5 s bound")
            assertEquals(listOf("ReadInProgress", "NotYet", "Ready"), heard.map { it::class.simpleName })
            assertNothingLeft(read)
        }

    // --- (k) The settings -------------------------------------------------------------------------------

    @Test
    fun k__settings__defaults_off_values_ranges_and_builder() {
        val defaults = CoreNfcReadSettings()
        assertEquals(1, defaults.maxSessionRenewals)
        assertEquals(0L, defaults.sessionRenewMillis, "no renewal on a timer by default")
        assertEquals(6_000L, defaults.busyRetryMillis)
        assertEquals(CoreNfcReadSettings.DEFAULT_MAX_SESSION_RENEWALS, defaults.maxSessionRenewals)
        assertEquals(CoreNfcReadSettings.DEFAULT_SESSION_RENEW_MILLIS, defaults.sessionRenewMillis)
        assertEquals(CoreNfcReadSettings.DEFAULT_BUSY_RETRY_MILLIS, defaults.busyRetryMillis)
        assertEquals(0, CoreNfcReadSettings.SESSION_RENEWALS_OFF)
        assertEquals(0L, CoreNfcReadSettings.SESSION_RENEW_OFF)
        assertEquals(0L, CoreNfcReadSettings.BUSY_RETRY_OFF)
        assertEquals(5_000L, CoreNfcReadSettings.MIN_SESSION_RENEW_MILLIS)
        assertEquals(0L, CoreNfcReadSettings(sessionRenewMillis = 0).sessionRenewMillis)
        assertEquals(5_000L, CoreNfcReadSettings(sessionRenewMillis = 5_000).sessionRenewMillis)
        assertFailsWith<IllegalArgumentException> { CoreNfcReadSettings(sessionRenewMillis = 4_999) }
        assertFailsWith<IllegalArgumentException> { CoreNfcReadSettings(sessionRenewMillis = -1) }
        assertFailsWith<IllegalArgumentException> { CoreNfcReadSettings(maxSessionRenewals = -1) }
        assertFailsWith<IllegalArgumentException> { CoreNfcReadSettings(busyRetryMillis = -1) }
        assertFailsWith<IllegalArgumentException> { CoreNfcReadSettings.Builder().sessionRenewMillis(1).build() }
        val built =
            CoreNfcReadSettings
                .Builder()
                .maxSessionRenewals(5)
                .sessionRenewMillis(8_000)
                .busyRetryMillis(0)
                .build()
        val again = built.toBuilder().build()
        assertEquals(5, again.maxSessionRenewals)
        assertEquals(8_000L, again.sessionRenewMillis)
        assertEquals(0L, again.busyRetryMillis)
        assertTrue("maxSessionRenewals=5, sessionRenewMillis=8000, busyRetryMillis=0" in built.toString())
    }

    @Test
    fun k__the_renewal_event_and_state() {
        val event = ChipReadEvent.sessionRenew(2, 202, ChipReadStepOutcome.SUCCEEDED, 1_000)
        assertEquals(ChipReadStep.SESSION_RENEW, event.step)
        assertEquals(2, event.renewal)
        assertEquals(202, event.systemCode)
        assertTrue("renewal=2" in event.toString())
        assertNull(ChipReadEvent.sessionEnd(202, 0).renewal)
        assertNull(ChipReadEvent.platformStep(ChipReadStep.SHEET_TEXT, ChipReadStepOutcome.SUCCEEDED, 600).renewal)
    }

    private companion object {
        const val INVALIDATE = "invalidate"
    }
}
