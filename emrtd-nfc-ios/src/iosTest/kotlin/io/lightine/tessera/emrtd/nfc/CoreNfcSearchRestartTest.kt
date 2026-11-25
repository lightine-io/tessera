package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadEvent
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.ChipReadStep
import io.lightine.tessera.emrtd.ChipReadStepOutcome
import io.lightine.tessera.telemetry.TelemetryEvent
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import io.lightine.tessera.types.errors.NfcUnavailable.Reason
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSLock
import platform.Foundation.NSThread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TES-346: while no document is reported, the read asks Core NFC to search again on a period. On
 * an iPhone, a first contact in a weak position made Core NFC report no tag at all, and the system
 * ended the session about 22 seconds later; these tests model that with the fakes, on the virtual
 * clock. Also the raw Core NFC code of a session the system ended, as a telemetry fact.
 */
class CoreNfcSearchRestartTest {
    /** The session-end events a read reported, from any thread. */
    private class SessionEnds : TelemetrySink {
        private val lock = NSLock()
        private val all = mutableListOf<ChipReadEvent>()
        val events: List<ChipReadEvent>
            get() {
                lock.lock()
                try {
                    return all.filter { it.step == ChipReadStep.SESSION_END }
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

    /** A worker that keeps what is posted until the test runs it. */
    private class HeldWorker : SerialWorker {
        val blocks = mutableListOf<() -> Unit>()

        override fun post(block: () -> Unit) {
            blocks += block
        }

        fun runAll() {
            while (blocks.isNotEmpty()) blocks.removeAt(0)()
        }
    }

    private fun ChipReadHarness.startTimed(settings: CoreNfcReadSettings = CoreNfcReadSettings()) =
        start(settings = settings, recorder = Recorder().also { it.clock = now })

    private fun waitedTimes(rec: Recorder): List<Long> = rec.states.mapNotNull { (it as? ChipReadState.WaitingForDocument)?.waitedMillis }

    // --- (a) Restarts on the period, and only while no tag was reported ------------------------------

    @Test
    fun no_document_for_20_seconds__a_new_search_every_4_seconds_and_the_waiting_updates_unchanged() =
        chipReadTest {
            val (read, rec) = startTimed(oneSession())
            step(20_000)
            assertEquals(listOf(4_000L, 8_000L, 12_000L, 16_000L, 20_000L), port.restartTimes)
            assertEquals((0..10).map { it * 2_000L }, waitedTimes(rec), "a waiting update every 2 seconds, as before")
            assertEquals(
                listOf(CoreNfcDefaultSheetTexts.WAITING_TEXT, CoreNfcDefaultSheetTexts.KEEP_HOLDING_TEXT),
                port.alerts,
                "the sheet text changes once, at 8 s",
            )
            read.cancel()
            step()
            assertNothingLeft(read)
        }

    @Test
    fun a_document_reported__no_new_search_while_it_is_read_or_after() =
        chipReadTest {
            val chip = FakeChip(scope = scope, answerDelayMillis = 1_000)
            val (read, rec) = startTimed(oneSession())
            step(10_000)
            assertEquals(listOf(4_000L, 8_000L), port.restartTimes)
            present(FakeChipTag(chip))
            // The read takes several seconds (one second per answer): no new search meanwhile.
            step(30_000)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertEquals(listOf(4_000L, 8_000L), port.restartTimes)
            step(20_000)
            assertEquals(2, port.restarts)
            assertNothingLeft(read)
        }

    @Test
    fun the_read_ending__no_new_search_after_the_ending_began() =
        chipReadTest {
            val (read, rec) = startTimed()
            step(6_000)
            assertEquals(1, port.restarts)
            read.cancel()
            step(30_000)
            assertIs<ChipReadEnding.Cancelled>(rec.ending)
            assertEquals(1, port.restarts)
            assertNothingLeft(read)
        }

    @Test
    fun the_read_ending__a_new_search_still_queued_is_dropped() =
        chipReadTest {
            val worker = HeldWorker()
            restartWorker = { worker }
            val (read, rec) = startTimed()
            step(4_000)
            assertEquals(1, worker.blocks.size, "the new search is posted, not run in the read")
            read.cancel()
            step()
            assertIs<ChipReadEnding.Cancelled>(rec.ending)
            worker.runAll()
            assertEquals(0, port.restarts)
            assertNothingLeft(read)
        }

    @Test
    fun the_system_ending_the_session__a_new_search_still_queued_is_dropped() =
        chipReadTest {
            val worker = HeldWorker()
            restartWorker = { worker }
            val (read, rec) = startTimed(oneSession())
            step(4_000)
            systemEnds(CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY)
            assertIs<ChipReadEnding.EndedBySystem>(rec.ending)
            worker.runAll()
            assertEquals(0, port.restarts)
            assertNothingLeft(read)
        }

    @Test
    fun turned_off__no_new_search_at_all() =
        chipReadTest {
            val (read, rec) = startTimed(oneSession(CoreNfcReadSettings(searchRestartMillis = CoreNfcReadSettings.SEARCH_RESTART_OFF)))
            step(20_000)
            assertEquals(0, port.restarts)
            assertEquals((0..10).map { it * 2_000L }, waitedTimes(rec))
            read.cancel()
            step()
            assertNothingLeft(read)
        }

    @Test
    fun another_period__new_searches_at_that_period() =
        chipReadTest {
            val (read, _) = startTimed(CoreNfcReadSettings(searchRestartMillis = 2_000))
            step(9_000)
            assertEquals(listOf(2_000L, 4_000L, 6_000L, 8_000L), port.restartTimes)
            read.cancel()
            step()
            assertNothingLeft(read)
        }

    @Test
    fun a_new_search_still_running__the_next_is_not_piled_on() =
        chipReadTest {
            val worker = HeldWorker()
            restartWorker = { worker }
            val (read, _) = startTimed(oneSession())
            step(20_000)
            assertEquals(1, worker.blocks.size, "one posted, none added while it has not run")
            worker.runAll()
            assertEquals(1, port.restarts)
            step(4_000)
            assertEquals(1, worker.blocks.size, "the next one comes at the period after")
            read.cancel()
            step()
            worker.runAll()
            assertEquals(1, port.restarts)
            assertNothingLeft(read)
        }

    // --- (b) The device case ---------------------------------------------------------------------------

    /**
     * The device case: the document is near from [placedAt], but the system reports it only after a
     * new search (its first contact was lost); with no tag reported, the system ends the session 22
     * seconds after the document came near, with code 202 (the code on a device is not proven).
     */
    private fun ChipReadHarness.firstContactLost(
        placedAt: Long,
        chip: FakeChip,
    ) {
        var reported = false
        port.onRestart = { p ->
            if (now() >= placedAt && !reported) {
                reported = true
                p.events.onTagsDetected(listOf(FakeChipTag(chip)))
            }
        }
        val session = port
        scope.launch {
            delay(placedAt + 22_000)
            if (!reported) {
                session.systemEnd(
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

    @Test
    fun device_case__first_contact_lost_at_0_seconds__the_first_new_search_finds_it_and_the_read_completes() =
        chipReadTest {
            val (read, rec) = startTimed()
            firstContactLost(placedAt = 0, chip = FakeChip())
            step(4_000)
            assertEquals("DocumentFound", rec.names.last())
            step(400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertEquals(4_400L, rec.endingTimes.single())
            step(30_000)
            assertEquals(1, port.restarts)
            assertEquals(0, port.systemEnds)
            assertNothingLeft(read)
        }

    @Test
    fun device_case__first_contact_lost_at_12_seconds__a_later_new_search_finds_it() =
        chipReadTest {
            val (read, rec) = startTimed(oneSession())
            firstContactLost(placedAt = 12_000, chip = FakeChip())
            step(12_400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertEquals(listOf(4_000L, 8_000L, 12_000L), port.restartTimes)
            assertNothingLeft(read)
        }

    @Test
    fun device_case__with_new_searches_off_the_system_ends_the_session_at_22_seconds() =
        chipReadTest {
            val ends = SessionEnds()
            val settings = CoreNfcReadSettings(searchRestartMillis = CoreNfcReadSettings.SEARCH_RESTART_OFF, telemetry = ends)
            val (read, rec) = startTimed(oneSession(settings))
            firstContactLost(placedAt = 0, chip = FakeChip())
            step(21_999)
            assertTrue(rec.endings.isEmpty())
            step(1)
            val ending = assertIs<ChipReadEnding.EndedBySystem>(rec.ending)
            assertEquals(NfcUnavailable(Reason.ENDED_BY_SYSTEM), ending.reason)
            assertEquals(22_000L, rec.endingTimes.single())
            assertEquals(0, port.restarts)
            assertTrue(port.endings.isEmpty(), "the SDK sends no ending of its own")
            val end = ends.events.single()
            assertEquals(202, end.systemCode)
            assertEquals(22_000L, end.durationMillis)
            assertNothingLeft(read)
        }

    // --- (c) A tag at the same instant as a new search -------------------------------------------------

    @Test
    fun a_tag_reported_while_the_new_search_is_queued__handled_once_and_the_search_dropped() =
        chipReadTest {
            val worker = HeldWorker()
            restartWorker = { worker }
            val (read, rec) = startTimed()
            step(4_000)
            assertEquals(1, worker.blocks.size)
            val tag = FakeChipTag(FakeChip())
            present(tag)
            worker.runAll()
            assertEquals(0, port.restarts, "no new search after the tag was reported")
            step(400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertEquals(1, tag.connectCalls, "the tag is handled once")
            assertNothingLeft(read)
        }

    @Test
    fun a_tag_reported_at_the_same_instant_as_the_period__handled_once_and_no_new_search() =
        chipReadTest {
            val worker = HeldWorker()
            restartWorker = { worker }
            val (read, rec) = startTimed()
            step(4_000)
            assertEquals(1, worker.blocks.size)
            // The tag comes on the delegate queue, and the search queue runs before the read has
            // taken it: the report alone stops the new search.
            val tag = FakeChipTag(FakeChip())
            port.events.onTagsDetected(listOf(tag))
            worker.runAll()
            assertEquals(0, port.restarts)
            step()
            assertEquals(0, port.restarts)
            step(400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertEquals(1, tag.connectCalls)
            assertNothingLeft(read)
        }

    @Test
    fun a_tag_reported_during_the_new_search_call__handled_once() =
        chipReadTest {
            val (read, rec) = startTimed()
            val tag = FakeChipTag(FakeChip())
            port.onRestart = { it.events.onTagsDetected(listOf(tag)) }
            step(4_400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertEquals(1, tag.connectCalls)
            assertEquals(1, port.restarts)
            assertNothingLeft(read)
        }

    // --- (d) A restart call that does not return holds up nothing -------------------------------------

    @Test
    fun a_new_search_call_stuck__waiting_updates_detection_and_the_read_go_on() =
        chipReadTest {
            restartWorker = ::newSearchQueueWorker
            val stuck = StuckSetter(blockOn = RESTART)
            val (read, rec) = startTimed(oneSession())
            port.onRestart = { stuck.hook(RESTART) }
            try {
                step(4_000)
                assertTrue(stuck.awaitEntered(), "the new search reached its queue")
                repeat(3) { step(2_000) }
                assertEquals((0..5).map { it * 2_000L }, waitedTimes(rec), "every waiting update on time")
                present(FakeChipTag(FakeChip()))
                assertEquals("DocumentFound", rec.names.last())
                assertEquals(10_000L, rec.updateTimes.last())
                step(400)
                assertIs<ChipReadEnding.Complete>(rec.ending)
                assertTrue(stuck.inFlight)
                assertFalse(stuck.calledOnCaller, "no restart ran on the read's own thread")
                assertEquals(listOf<String?>(null), port.endings, "ended with success while the call is stuck")
            } finally {
                stuck.release()
            }
            awaitReal { stuck.returned }
            NSThread.sleepForTimeInterval(0.05)
            assertEquals(1, port.restarts, "none piled on while it was stuck, none after")
            assertNothingLeft(read)
        }

    @Test
    fun a_new_search_call_stuck__the_app_cancel_ends_the_session_at_once() =
        chipReadTest {
            restartWorker = ::newSearchQueueWorker
            val stuck = StuckSetter(blockOn = RESTART)
            val (read, rec) = startTimed()
            port.onRestart = { stuck.hook(RESTART) }
            try {
                step(4_000)
                assertTrue(stuck.awaitEntered())
                step(1_000)
                read.cancel()
                step()
                assertIs<ChipReadEnding.Cancelled>(rec.ending)
                assertEquals(5_000L, rec.endingTimes.single())
                assertEquals(1, port.endings.size, "the session is ended at once")
            } finally {
                stuck.release()
            }
            awaitReal { stuck.returned }
            assertNothingLeft(read)
        }

    // --- (e) The raw code of a session the system ended ------------------------------------------------

    @Test
    fun the_system_ending_the_session__the_raw_code_reaches_the_sink_for_every_mapped_code() {
        val cases =
            listOf(
                CoreNfcErrorCode.USER_CANCELED to ChipReadEnding.Cancelled::class,
                CoreNfcErrorCode.SESSION_TIMEOUT to ChipReadEnding.EndedBySystem::class,
                CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY to ChipReadEnding.EndedBySystem::class,
                // 5 s after the begin, a 203 is a dead session's end, not a refused begin.
                CoreNfcErrorCode.SYSTEM_IS_BUSY to ChipReadEnding.EndedBySystem::class,
                CoreNfcErrorCode.RADIO_DISABLED to ChipReadEnding.NotStarted::class,
                CoreNfcErrorCode.SESSION_INVALIDATED to ChipReadEnding.EndedBySystem::class,
                CoreNfcErrorCode.FIRST_NDEF_TAG_READ to ChipReadEnding.EndedBySystem::class,
                999L to ChipReadEnding.EndedBySystem::class,
            )
        for ((code, kind) in cases) {
            chipReadTest {
                val ends = SessionEnds()
                val (read, rec) = startTimed(oneSession(CoreNfcReadSettings(telemetry = ends)))
                step(5_000)
                systemEnds(code)
                assertEquals(kind, rec.ending::class, "code $code")
                val end = ends.events.single()
                assertEquals(code.toInt(), end.systemCode, "code $code")
                assertEquals(ChipReadStepOutcome.FAILED, end.outcome)
                assertEquals(5_000L, end.durationMillis)
                assertTrue("systemCode=$code" in end.toString())
                assertNothingLeft(read)
            }
        }
    }

    @Test
    fun a_refused_start__the_raw_code_reaches_the_sink_for_every_mapped_code() {
        val codes =
            listOf(
                CoreNfcErrorCode.UNSUPPORTED_FEATURE to Reason.NOT_SUPPORTED,
                CoreNfcErrorCode.SECURITY_VIOLATION to Reason.NOT_PERMITTED,
                CoreNfcErrorCode.RADIO_DISABLED to Reason.DISABLED,
                CoreNfcErrorCode.INELIGIBLE to Reason.NOT_PERMITTED,
                CoreNfcErrorCode.ACCESS_NOT_ACCEPTED to Reason.NOT_PERMITTED,
                CoreNfcErrorCode.SYSTEM_IS_BUSY to Reason.BUSY,
            )
        for ((code, reason) in codes) {
            chipReadTest {
                val ends = SessionEnds()
                onBegin = { it.systemEnd(nfcErrorFor(platform.CoreNFC.NFCErrorDomain, code, CoreNfcErrorSource.SESSION_END), code) }
                val (read, rec) = startTimed(oneSession(CoreNfcReadSettings(telemetry = ends)))
                val ending = assertIs<ChipReadEnding.NotStarted>(rec.ending, "code $code")
                assertEquals(reason, ending.reason)
                val end = ends.events.single()
                assertEquals(code.toInt(), end.systemCode)
                assertEquals(0L, end.durationMillis)
                step(20_000)
                assertEquals(0, port.restarts, "no new search after a refused start")
                assertNothingLeft(read)
            }
        }
    }

    @Test
    fun an_error_of_another_domain__no_code() =
        chipReadTest {
            val ends = SessionEnds()
            val (read, _) = startTimed(CoreNfcReadSettings(telemetry = ends))
            step(1_000)
            port.systemEnd(NfcTimeout(null), systemCodeOf("SomeOtherDomain", 5))
            step()
            assertNull(ends.events.single().systemCode)
            assertNothingLeft(read)
        }

    @Test
    fun the_sdk_ending_the_session__no_session_end_event_for_its_echo() =
        chipReadTest {
            val ends = SessionEnds()
            val (read, rec) = startTimed(CoreNfcReadSettings(telemetry = ends))
            step(1_000)
            read.cancel()
            step()
            assertIs<ChipReadEnding.Cancelled>(rec.ending)
            assertEquals(1, port.endings.size)
            assertTrue(ends.events.isEmpty(), "the system's echo of the SDK's own end is no event")
            assertNothingLeft(read)
        }

    @Test
    fun the_platform_code_only_for_the_session_end_step() {
        assertEquals(202, ChipReadEvent.sessionEnd(202, 22_000).systemCode)
        assertEquals(ChipReadStep.SESSION_END, ChipReadEvent.sessionEnd(null, 0).step)
        assertNull(ChipReadEvent.platformStep(ChipReadStep.SHEET_TEXT, ChipReadStepOutcome.SUCCEEDED, 600).systemCode)
    }

    // --- (f) The setting --------------------------------------------------------------------------------

    @Test
    fun the_setting__default_off_value_range_and_builder() {
        val defaults = CoreNfcReadSettings()
        assertEquals(4_000, defaults.searchRestartMillis)
        assertEquals(CoreNfcReadSettings.DEFAULT_SEARCH_RESTART_MILLIS, defaults.searchRestartMillis)
        assertEquals(0, CoreNfcReadSettings.SEARCH_RESTART_OFF)
        assertEquals(0, CoreNfcReadSettings(searchRestartMillis = 0).searchRestartMillis)
        assertEquals(2_000, CoreNfcReadSettings(searchRestartMillis = CoreNfcReadSettings.MIN_SEARCH_RESTART_MILLIS).searchRestartMillis)
        assertFailsWith<IllegalArgumentException> { CoreNfcReadSettings(searchRestartMillis = 1_999) }
        assertFailsWith<IllegalArgumentException> { CoreNfcReadSettings(searchRestartMillis = 1) }
        assertFailsWith<IllegalArgumentException> { CoreNfcReadSettings(searchRestartMillis = -1) }
        assertFailsWith<IllegalArgumentException> { CoreNfcReadSettings.Builder().searchRestartMillis(500).build() }
        val built = CoreNfcReadSettings.Builder().searchRestartMillis(6_000).build()
        assertEquals(6_000, built.toBuilder().build().searchRestartMillis, "the builder keeps the value")
        assertTrue("searchRestartMillis=6000" in built.toString())
        assertEquals(
            0,
            CoreNfcReadSettings
                .Builder()
                .searchRestartMillis(0)
                .build()
                .searchRestartMillis,
        )
    }

    private companion object {
        const val RESTART = "restart"
    }
}
