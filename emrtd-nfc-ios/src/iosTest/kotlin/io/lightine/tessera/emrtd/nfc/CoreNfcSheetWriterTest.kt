package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadEvent
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.ChipReadStep
import io.lightine.tessera.emrtd.ChipReadStepOutcome
import io.lightine.tessera.emrtd.ChipReadUpdate
import io.lightine.tessera.telemetry.TelemetryEvent
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.types.errors.NfcError
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSLock
import platform.Foundation.NSThread
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * TES-346: the sheet's Core NFC calls never run on the thread that hands updates to the app. On an
 * iPhone, setting the sheet's text while the session polled with no document found did not return
 * for about 12 seconds; these tests block the fake setter on a real queue the same way, while the
 * read runs on the virtual clock.
 */
class CoreNfcSheetWriterTest {
    /** Texts that change at 2 seconds of waiting, like the old move hint did at 8. */
    private object ChangingTexts : CoreNfcSheetTexts {
        override fun textFor(update: ChipReadUpdate): String =
            when (val state = update.state) {
                is ChipReadState.WaitingForDocument -> if (state.waitedMillis >= 2_000) MOVE else HOLD
                ChipReadState.DocumentFound -> FOUND
                else -> state::class.simpleName ?: "?"
            }

        override fun textForEnding(ending: ChipReadEnding): String = if (ending is ChipReadEnding.Complete) DONE else "ended"
    }

    private class Events : TelemetrySink {
        private val lock = NSLock()
        private val all = mutableListOf<ChipReadEvent>()
        val sheet: List<ChipReadEvent>
            get() {
                lock.lock()
                try {
                    return all.filter { it.step == ChipReadStep.SHEET_TEXT }
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

    // --- (a) A setter stuck for 12 seconds holds up nothing ------------------------------------------

    @Test
    fun a_text_setting_stuck_while_waiting_holds_up_no_update_and_the_read_completes() =
        chipReadTest {
            val stuck = StuckSetter(blockOn = MOVE)
            val events = Events()
            val (read, rec) = startOnQueue(stuck, events)
            try {
                step(2_000)
                assertTrue(stuck.awaitEntered(), "the text change reached the sheet's queue")

                // Every waiting report comes on time while the setting does not return.
                repeat(9) { step(2_000) }
                val waits = rec.updates.mapIndexedNotNull { i, u -> (u.state as? ChipReadState.WaitingForDocument)?.let { i } }
                assertEquals(
                    (0..10).map { it * 2_000L },
                    waits.map { (rec.updates[it].state as ChipReadState.WaitingForDocument).waitedMillis },
                )
                assertEquals((0..10).map { it * 2_000L }, waits.map { rec.updateTimes[it] }, "each report at its time")

                present(FakeChipTag(FakeChip()))
                assertEquals("DocumentFound", rec.names.last())
                assertEquals(20_000L, rec.updateTimes.last(), "the document is reported when it is found")
                step(400)
                assertIs<ChipReadEnding.Complete>(rec.ending)
                assertTrue(stuck.inFlight, "the setting was still stuck the whole time")
                assertFalse(stuck.calledOnCaller, "no setting ran on the read's own thread")

                // The ending's wait passes with the setting still stuck: success, ended once.
                assertTrue(port.endings.isEmpty())
                step(CoreNfcChipRead.ENDING_WAIT_MILLIS)
                assertEquals(listOf<String?>(null), port.endings)
                val slow = events.sheet.single()
                assertEquals(ChipReadStepOutcome.FAILED, slow.outcome)
                assertTrue(slow.durationMillis >= 18_000, "${slow.durationMillis}")
            } finally {
                stuck.release()
            }
            awaitReal { "alert:$MOVE" in port.calls }
            NSThread.sleepForTimeInterval(0.05)
            assertEquals(listOf(HOLD, MOVE), stuck.texts, "the text for the found document was dropped by the ending")
            assertEquals(1, events.sheet.size, "a slow setting is reported once")
            assertNothingLeft(read)
        }

    // --- (b) The app's cancel ends the read within the bound ---------------------------------------

    @Test
    fun an_app_cancel_while_a_text_setting_is_stuck_ends_the_session_within_the_bound() =
        chipReadTest {
            val stuck = StuckSetter(blockOn = MOVE)
            val (read, rec) = startOnQueue(stuck)
            try {
                step(2_000)
                assertTrue(stuck.awaitEntered())
                step(3_000)
                read.cancel()
                step()
                assertEquals("Stopping", rec.names.last())
                val ending = assertIs<ChipReadEnding.Cancelled>(rec.ending)
                assertEquals(ChipReadEnding.Cancelled.By.APP, ending.by)
                assertEquals(5_000L, rec.endingTimes.single(), "the app hears the ending at once")
                assertTrue(port.endings.isEmpty(), "the end waits a moment for the stuck queue")

                step(CoreNfcChipRead.ENDING_WAIT_MILLIS)
                assertEquals(listOf<String?>("ended"), port.endings, "ended with the error message, at the bound")
                assertTrue(stuck.inFlight)
                assertFalse(stuck.calledOnCaller)
            } finally {
                stuck.release()
            }
            awaitReal { stuck.returned }
            assertEquals(1, port.endings.size, "ended once")
            assertNothingLeft(read)
        }

    // --- (c) Conflation ---------------------------------------------------------------------------

    @Test
    fun texts_asked_for_behind_a_stuck_setting_collapse_to_the_latest() =
        runTest {
            val port = FakeSessionPort(NoEvents)
            val stuck = StuckSetter(blockOn = "A")
            port.onSetAlert = stuck::hook
            val session = OwnedReaderSession(port, keeper = Any())
            session.begin()
            val writer = SheetWriter(session, newSheetQueueWorker(), now = { 0L })
            try {
                writer.show("A")
                assertTrue(stuck.awaitEntered())
                writer.show("B")
                writer.show("C")
                writer.show("D")
            } finally {
                stuck.release()
            }
            awaitRealTime { port.calls.size == 2 }
            assertEquals(listOf("alert:A", "alert:D"), port.calls)
            session.systemEnd()
        }

    // --- (d) The final text comes before the success end -----------------------------------------

    @Test
    fun with_a_healthy_setter_the_final_text_is_set_before_the_success_end() =
        chipReadTest {
            val setter = StuckSetter(blockOn = null)
            val (read, rec) = startOnQueue(setter)
            // Past the sheet's 100 ms text interval, so the found text is asked for at once.
            step(500)
            present(FakeChipTag(FakeChip()))
            awaitReal { setter.texts.lastOrNull() == FOUND }
            step(400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            awaitReal { port.endings.isNotEmpty() }
            val calls = port.calls
            assertEquals(listOf("alert:$DONE", "invalidate"), calls.takeLast(2), "$calls")
            assertEquals(1, calls.count { it.startsWith("invalidate") })
            assertFalse(setter.calledOnCaller, "no setting ran on the read's own thread")
            step(CoreNfcChipRead.ENDING_WAIT_MILLIS)
            assertEquals(listOf<String?>(null), port.endings, "the bound adds no second end")
            assertNothingLeft(read)
        }

    // --- (e) The default texts do not change while waiting ----------------------------------------

    @Test
    fun the_default_text_does_not_change_in_20_seconds_of_waiting() =
        chipReadTest {
            val (read, rec) = start()
            step(20_000)
            assertEquals(11, rec.states.count { it is ChipReadState.WaitingForDocument })
            assertEquals(listOf("Hold the top of your iPhone on the document and move it slowly"), port.alerts)
            present(FakeChipTag(FakeChip()))
            assertEquals("Found. Hold still.", port.alerts.last())
            step(400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertNothingLeft(read)
        }

    // --- (f) A setting that never returns ---------------------------------------------------------

    @Test
    fun when_the_final_text_never_returns_the_session_ends_with_success_once_at_the_bound() =
        chipReadTest {
            val stuck = StuckSetter(blockOn = DONE)
            val events = Events()
            val (read, rec) = startOnQueue(stuck, events)
            try {
                present(FakeChipTag(FakeChip()))
                step(400)
                assertIs<ChipReadEnding.Complete>(rec.ending)
                assertTrue(stuck.awaitEntered(), "the final text is being set")
                assertTrue(port.endings.isEmpty(), "the end waits behind the final text, for a moment")
                step(CoreNfcChipRead.ENDING_WAIT_MILLIS - 1)
                assertTrue(port.endings.isEmpty())
                step(1)
                assertEquals(listOf<String?>(null), port.endings, "success, from the ending's own path")
                assertTrue(read.isEnded)
                val slow = events.sheet.single()
                assertEquals(ChipReadStepOutcome.FAILED, slow.outcome)
                assertTrue(
                    slow.durationMillis in SheetWriter.SLOW_SET_MILLIS + 1..CoreNfcChipRead.ENDING_WAIT_MILLIS,
                    "${slow.durationMillis}",
                )
            } finally {
                stuck.release()
            }
            awaitReal { stuck.returned }
            awaitReal { port.calls.lastOrNull() == "alert:$DONE" }
            assertEquals(1, port.endings.size, "the queue adds no second end once the setting returns")
            assertEquals(1, events.sheet.size)
            assertNothingLeft(read)
        }

    @Test
    fun a_first_text_that_never_returns_delays_the_session_begin_by_the_bound_only() =
        chipReadTest {
            val stuck = StuckSetter(blockOn = HOLD)
            val (read, _) = startOnQueue(stuck)
            try {
                assertTrue(stuck.awaitEntered())
                assertFalse(port.began)
                step(CoreNfcChipRead.FIRST_TEXT_WAIT_MILLIS)
                assertTrue(port.began, "the session begins anyway")
                read.cancel()
                step(CoreNfcChipRead.ENDING_WAIT_MILLIS)
                assertEquals(1, port.endings.size)
            } finally {
                stuck.release()
            }
            awaitReal { stuck.returned }
            assertNothingLeft(read)
        }

    // --- Helpers ----------------------------------------------------------------------------------

    /** Starts a read whose sheet calls run on the real sheet queue, with [setter] inside the fake setter. */
    private fun ChipReadHarness.startOnQueue(
        setter: StuckSetter,
        events: TelemetrySink? = null,
    ): Pair<CoreNfcChipRead, Recorder> {
        sheetWorker = ::newSheetQueueWorker
        onSetAlert = setter::hook
        val recorder = Recorder().also { it.clock = now }
        val (read, rec) =
            start(
                texts = ChangingTexts,
                settings = CoreNfcReadSettings(telemetry = events),
                recorder = recorder,
            )
        // The first text goes through the queue before the session begins; a stuck one is left to the test.
        if (setter.blockOn != HOLD) awaitReal { port.began }
        return read to rec
    }

    private object NoEvents : ReaderSessionEvents {
        override fun onTagsDetected(tags: List<DetectedTag>) = Unit

        override fun onInvalidated(error: NfcError) = Unit
    }

    private companion object {
        const val HOLD = "hold"
        const val MOVE = "move"
        const val FOUND = "found"
        const val DONE = "done"
    }
}

/**
 * A fake text setter that blocks the thread calling it on [blockOn] (once), the way the setter did on
 * a device, until [release] or [maxRealMillis] of real time. It records which thread called it.
 */
internal class StuckSetter(
    val blockOn: String?,
    private val maxRealMillis: Long = 3_000,
) {
    private val lock = NSLock()
    private val gate = dispatch_semaphore_create(0)
    private val entered = dispatch_semaphore_create(0)
    private val callerThread = NSThread.currentThread
    private val seen = mutableListOf<String>()
    private var blockedOnce = false
    private var stuckNow = false
    private var back = false
    private var onCaller = false

    val texts: List<String> get() = locked { seen.toList() }

    /** Whether the blocked setting is still blocked. */
    val inFlight: Boolean get() = locked { stuckNow }

    /** Whether the blocked setting returned. */
    val returned: Boolean get() = locked { back }

    /** Whether any setting ran on the thread that made this setter: the test's, which runs the read. */
    val calledOnCaller: Boolean get() = locked { onCaller }

    fun hook(text: String) {
        val block =
            locked {
                seen += text
                if (NSThread.currentThread == callerThread) onCaller = true
                val now = !blockedOnce && text == blockOn
                if (now) {
                    blockedOnce = true
                    stuckNow = true
                }
                now
            }
        if (!block) return
        dispatch_semaphore_signal(entered)
        dispatch_semaphore_wait(gate, dispatch_time(DISPATCH_TIME_NOW, maxRealMillis * 1_000_000))
        locked {
            stuckNow = false
            back = true
        }
    }

    /** Waits in real time until the blocking call has begun. */
    fun awaitEntered(): Boolean = dispatch_semaphore_wait(entered, dispatch_time(DISPATCH_TIME_NOW, 5_000L * 1_000_000)) == 0L

    fun release() {
        dispatch_semaphore_signal(gate)
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

/** Waits in real time, at most 5 seconds, for [condition]; runs what is due on the virtual clock as it goes. */
internal fun ChipReadHarness.awaitReal(condition: () -> Boolean) {
    repeat(500) {
        step()
        if (condition()) return
        NSThread.sleepForTimeInterval(0.01)
    }
    step()
    check(condition()) { "the condition did not come true in 5 seconds" }
}

/** The same, outside a harness. */
internal fun awaitRealTime(condition: () -> Boolean) {
    repeat(500) {
        if (condition()) return
        NSThread.sleepForTimeInterval(0.01)
    }
    check(condition()) { "the condition did not come true in 5 seconds" }
}
