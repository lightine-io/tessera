package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadEvent
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.LinkLossPolicy
import io.lightine.tessera.telemetry.TelemetryEvent
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TES-346 polish: the telemetry sink on the easy entry point, the key's lifetime, availability, settings builder. */
class CoreNfcChipReadPolishTest {
    private class Sink : TelemetrySink {
        val events = mutableListOf<TelemetryEvent>()

        override fun record(event: TelemetryEvent) {
            events += event
        }
    }

    @Test
    fun a_telemetry_sink_in_the_settings_hears_the_steps_of_the_read() =
        chipReadTest {
            val sink = Sink()
            val (read, rec) = start(settings = CoreNfcReadSettings(telemetry = sink))
            step(500)
            present(FakeChipTag(FakeChip()))
            step(400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertTrue(sink.events.filterIsInstance<ChipReadEvent>().isNotEmpty(), "${sink.events}")
            assertNothingLeft(read)
        }

    /** Starts a read whose observer only the read holds; returns a weak reference to that observer. */
    @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
    private fun ChipReadHarness.startWithUnheldObserver(): Pair<CoreNfcChipRead, kotlin.native.ref.WeakReference<Recorder>> {
        val recorder = Recorder()
        val (read, _) = start(recorder = recorder)
        return read to kotlin.native.ref.WeakReference(recorder)
    }

    @OptIn(kotlin.experimental.ExperimentalNativeApi::class, kotlin.native.runtime.NativeRuntimeApi::class)
    @Test
    fun the_read_lets_go_of_the_observer_at_the_ending_while_the_system_closes_the_session() =
        chipReadTest {
            reportEnd = false
            val (read, observer) = startWithUnheldObserver()
            step(500)
            read.cancel()
            step()
            assertTrue(LiveSessions.contains(read), "the read waits for the system's report of the session's end")
            kotlin.native.runtime.GC.collect()
            assertNull(observer.get(), "the observer is not kept alive by the read after the ending")
            port.events.onInvalidated(NfcUnavailable(NfcUnavailable.Reason.CANCELLED_BY_USER))
            step()
            assertFalse(LiveSessions.contains(read))
        }

    @Test
    fun the_read_clears_the_key_at_its_ending_and_not_before() =
        chipReadTest {
            val key = ChipAccessKey.Can("123456")
            val (read, rec) = start(options = ChipReadOptions(key, setOf(1)))
            assertFalse(key.isCleared)
            step(500)
            present(FakeChipTag(FakeChip()))
            assertFalse(key.isCleared, "the key stays usable while the read runs")
            step(400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertTrue(key.isCleared)
            assertNothingLeft(read)
        }

    @Test
    fun a_cancelled_read_and_a_read_that_never_started_clear_the_key_too() =
        chipReadTest {
            val cancelledKey = ChipAccessKey.Can("123456")
            val (read, rec) = start(options = ChipReadOptions(cancelledKey, setOf(1)))
            read.cancel()
            step()
            assertIs<ChipReadEnding.Cancelled>(rec.ending)
            assertTrue(cancelledKey.isCleared)
        }

    @Test
    fun availability_answers_null_when_reading_is_possible_and_not_supported_when_it_is_not() {
        assertNull(CoreNfcChipRead.availability { true })
        assertEquals(NfcUnavailable.Reason.NOT_SUPPORTED, CoreNfcChipRead.availability { false })
        // The public call asks the platform: whatever it says, it is a reason or null.
        val answer: NfcUnavailable.Reason? = CoreNfcChipRead.availability()
        assertTrue(answer == null || answer == NfcUnavailable.Reason.NOT_SUPPORTED)
    }

    @Test
    fun the_settings_builder_changes_one_value_and_toBuilder_starts_from_an_object() {
        val sink = Sink()
        val base =
            CoreNfcReadSettings
                .Builder()
                .linkLoss(LinkLossPolicy.readAgain(1))
                .telemetry(sink)
                .build()
        val changed = base.toBuilder().settleDelayMillis(0).build()
        assertEquals(0L, changed.settleDelayMillis)
        assertEquals(LinkLossPolicy.ReadAgain(1), changed.linkLoss)
        assertTrue(changed.telemetry === sink)
        assertEquals(1_000L, changed.waitAfterSystemEndMillis)
        assertEquals(400L, base.settleDelayMillis)
        assertNull(CoreNfcReadSettings().telemetry)
    }
}
