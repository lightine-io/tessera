package io.lightine.tessera.emrtd.nfc

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Looper
import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReadReadiness
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** NFC's state as the test sets it, and the adapter-state broadcast as the test sends it. */
private class FakeNfcState : NfcStateSource {
    @Volatile var answer: NfcUnavailable.Reason? = null
    var listens = 0
    var stops = 0
    val contexts = mutableListOf<Context>()
    private var onChange: (() -> Unit)? = null

    override fun availability(context: Context): NfcUnavailable.Reason? = answer

    override fun listen(
        context: Context,
        onChange: () -> Unit,
    ): () -> Unit {
        listens++
        contexts += context
        this.onChange = onChange
        return {
            stops++
            this.onChange = null
        }
    }

    /** The person switched NFC (or its option for reading tags): the broadcast, when one is heard. */
    fun switchTo(reason: NfcUnavailable.Reason?) {
        answer = reason
        onChange?.invoke()
    }
}

/**
 * TES-346: Android's readiness, the same shape as on iPhone. One adapter-state receiver while at
 * least one listener is registered, with the application context; changes told once per kind, on
 * the main thread; values for each condition.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NfcReadinessTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private class Watch(
        val state: FakeNfcState,
        val main: FakeMain,
        val registry: NfcReadRegistry,
        val watch: NfcReadinessWatch,
    )

    private fun watch(): Watch {
        val state = FakeNfcState()
        val main = FakeMain()
        val registry = NfcReadRegistry()
        return Watch(state, main, registry, NfcReadinessWatch(registry, state, main))
    }

    @Test
    fun `the value for each condition`() {
        val w = watch()
        assertEquals(ChipReadReadiness.Ready, w.watch.current(app))
        for (reason in listOf(
            NfcUnavailable.Reason.NOT_SUPPORTED,
            NfcUnavailable.Reason.DISABLED,
            NfcUnavailable.Reason.TAG_READING_DISABLED,
        )) {
            w.state.answer = reason
            assertEquals(ChipReadReadiness.unavailable(reason), w.watch.current(app), "$reason")
        }
        w.state.answer = null
        val read = Any()
        assertTrue(w.registry.claim(read))
        assertEquals(ChipReadReadiness.ReadInProgress, w.watch.current(app))
        // NFC off wins over a running read, as on iPhone: a start now could not use it.
        w.state.answer = NfcUnavailable.Reason.DISABLED
        assertEquals(ChipReadReadiness.unavailable(NfcUnavailable.Reason.DISABLED), w.watch.current(app))
        w.state.answer = null
        w.registry.ended(read)
        assertEquals(ChipReadReadiness.Ready, w.watch.current(app))
    }

    @Test
    fun `the receiver goes on with the first listener and off with the last, once each`() {
        val w = watch()
        assertFalse(w.watch.isListening, "nothing is heard before a listener")
        val first = w.watch.add(app) { }
        val second = w.watch.add(app) { }
        assertEquals(1, w.state.listens)
        assertTrue(w.watch.isListening)

        first.remove()
        first.remove()
        assertEquals(0, w.state.stops, "one listener is left")
        second.remove()
        assertEquals(1, w.state.stops)
        assertFalse(w.watch.isListening)
        second.remove()
        assertEquals(1, w.state.stops, "removing twice stops nothing twice")

        w.watch.add(app) { }.remove()
        assertEquals(2, w.state.listens)
        assertEquals(2, w.state.stops)
    }

    @Test
    fun `only the application context is kept, never the Activity`() {
        val w = watch()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val registration = w.watch.add(activity) { }
        assertSame(activity.applicationContext, w.state.contexts.single())
        registration.remove()
    }

    @Test
    fun `switching NFC off and on is told once per change of kind, on the main thread`() {
        val w = watch()
        val heard = Collections.synchronizedList(mutableListOf<ChipReadReadiness>())
        val onMain = Collections.synchronizedList(mutableListOf<Boolean>())
        w.watch.add(app) {
            heard += it
            onMain += w.main.onMain
        }
        w.main.onMain = false

        w.state.switchTo(NfcUnavailable.Reason.DISABLED)
        w.state.switchTo(NfcUnavailable.Reason.DISABLED) // STATE_TURNING_OFF then STATE_OFF: one change
        assertTrue(heard.isEmpty(), "nothing is called before the main thread runs it")
        w.main.idle()
        w.state.switchTo(null)
        w.main.idle()

        assertEquals(listOf(ChipReadReadiness.unavailable(NfcUnavailable.Reason.DISABLED), ChipReadReadiness.Ready), heard)
        assertTrue(onMain.all { it })
    }

    @Test
    fun `a read starting and ending is told as ReadInProgress then Ready`() {
        val w = watch()
        val heard = mutableListOf<ChipReadReadiness>()
        w.watch.add(app) { heard += it }
        val read = Any()
        w.registry.claim(read)
        w.registry.ended(read)
        w.main.idle()
        assertEquals(listOf(ChipReadReadiness.ReadInProgress, ChipReadReadiness.Ready), heard)
    }

    @Test
    fun `a listener removed before its call runs is not called, and one that throws breaks nothing`() {
        val w = watch()
        val heard = mutableListOf<ChipReadReadiness>()
        val removed = w.watch.add(app) { heard += it }
        w.watch.add(app) { error("a listener's fault") }
        val kept = mutableListOf<ChipReadReadiness>()
        w.watch.add(app) { kept += it }
        w.state.switchTo(NfcUnavailable.Reason.DISABLED)
        removed.remove()
        w.main.idle()
        assertTrue(heard.isEmpty())
        assertEquals(listOf<ChipReadReadiness>(ChipReadReadiness.unavailable(NfcUnavailable.Reason.DISABLED)), kept)
    }

    @Test
    fun `a real read makes the readiness ReadInProgress until its ending`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val platform = FakeNfcPlatform(dispatcher)
            val state = FakeNfcState()
            val main = FakeMain()
            val env =
                NfcReadEnvironment(platform, dispatcher, { testScheduler.currentTime }, NfcReadRegistry(), main = main, state = state)
            val heard = mutableListOf<ChipReadReadiness>()
            env.readiness.add(app) { heard += it }
            val observer = RecordingChipObserver()
            val read =
                NfcChipRead.start(
                    ChipReadOptions(ChipAccessKey.Can("123456"), setOf(1)),
                    theAdapter(),
                    observer,
                    NfcReadSettings(),
                    env,
                )
            runCurrent()
            assertEquals(ChipReadReadiness.ReadInProgress, env.readiness.current(app))
            read.cancel()
            advanceTimeBy(100)
            runCurrent()
            main.idle()
            assertIs<ChipReadEnding.Cancelled>(observer.endings.single())
            assertEquals(ChipReadReadiness.Ready, env.readiness.current(app))
            assertEquals(listOf(ChipReadReadiness.ReadInProgress, ChipReadReadiness.Ready), heard)
        }

    @Test
    fun `the platform receiver hears the system's adapter-state broadcast and is gone after the stop`() {
        val heard = mutableListOf<Boolean>()
        val stop = PlatformNfcState.listen(app) { heard += (Looper.myLooper() == Looper.getMainLooper()) }
        val registered = shadowOf(app).registeredReceivers.filter { it.intentFilter.hasAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) }
        assertEquals(1, registered.size)

        app.sendBroadcast(Intent(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED).putExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(true), heard, "heard once, on the main thread")

        stop()
        stop()
        assertTrue(shadowOf(app).registeredReceivers.none { it.intentFilter.hasAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) })
        app.sendBroadcast(Intent(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, heard.size)
    }
}
